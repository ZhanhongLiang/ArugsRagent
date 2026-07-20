from __future__ import annotations

import json
import mimetypes
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
ROOT = Path(os.getenv("RAG_EVAL_ROOT", str(DEFAULT_ROOT))).resolve()
STATE = ROOT / "state"
BASE_URL = os.getenv("RAGENT_BASE_URL", "http://localhost:9090/api/ragent").rstrip("/")
USERNAME = os.getenv("RAGENT_USERNAME", "admin")
PASSWORD = os.getenv("RAGENT_PASSWORD", "admin")


def load_json(path: Path, default=None):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {} if default is None else default


def save_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


class RagentClient:
    def __init__(self):
        self.base_url = BASE_URL
        self.token = None

    def login(self):
        data = self.post_json("/auth/login", {"username": USERNAME, "password": PASSWORD}, auth=False)
        self.token = data["token"]
        return data

    def request(self, method: str, path: str, *, body=None, headers=None, auth=True):
        url = self.base_url + path
        req_headers = dict(headers or {})
        if auth and self.token:
            req_headers["Authorization"] = self.token
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
            req_headers["Content-Type"] = "application/json; charset=utf-8"
        req = urllib.request.Request(url, data=body, method=method, headers=req_headers)
        try:
            with self._open(req) as resp:
                text = resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {e.code} {method} {url}: {detail}") from e
        if not text:
            return None
        payload = json.loads(text)
        if isinstance(payload, dict) and "code" in payload:
            if payload.get("code") != "0":
                raise RuntimeError(f"API failed {method} {path}: {payload}")
            return payload.get("data")
        return payload

    def _open(self, request):
        hostname = urllib.parse.urlparse(self.base_url).hostname
        if hostname in {"localhost", "127.0.0.1", "::1"}:
            return urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=120)
        return urllib.request.urlopen(request, timeout=120)

    def post_json(self, path: str, data: object, auth=True):
        return self.request("POST", path, body=data, auth=auth)

    def put_json(self, path: str, data: object):
        return self.request("PUT", path, body=data)

    def get_json(self, path: str):
        return self.request("GET", path)

    def delete(self, path: str):
        return self.request("DELETE", path)

    def upload_file(self, path: str, file_path: Path, fields: dict):
        boundary = f"----ragent-eval-{int(time.time() * 1000)}"
        parts = []
        for name, value in fields.items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode("utf-8"))
        content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_path.name}\"\r\nContent-Type: {content_type}\r\n\r\n".encode("utf-8")
        )
        parts.append(file_path.read_bytes())
        parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
        body = b"".join(parts)
        return self.request("POST", path, body=body, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
