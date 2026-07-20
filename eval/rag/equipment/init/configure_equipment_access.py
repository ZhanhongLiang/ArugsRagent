from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "init"))

from client import RagentClient, STATE, load_json, save_json


def scopes(scope_type: str, workshop_id: str | None = None, team_id: str | None = None) -> dict:
    item = {"scopeType": scope_type}
    if workshop_id:
        item["workshopId"] = workshop_id
    if team_id:
        item["teamId"] = team_id
    return {"scopes": [item]}


def main() -> None:
    organization = load_json(ROOT / "organization_spec.json")
    document_map = load_json(STATE / "doc_id_map.json")
    knowledge_bases = load_json(STATE / "kb_ids.json")
    catalog = load_json(ROOT / "doc_catalog.json")
    if not document_map or not knowledge_bases:
        raise SystemExit("missing state files: run create_kbs.py and upload_docs.py first")

    client = RagentClient()
    client.login()
    existing_workshops = {item["code"]: item for item in client.get_json("/knowledge-access/workshops")}
    organization_ids = {"workshops": {}, "teams": {}}
    for workshop_code, workshop_spec in organization.items():
        workshop = existing_workshops.get(workshop_code)
        if workshop is None:
            workshop = client.post_json("/knowledge-access/workshops", {"code": workshop_code, "name": workshop_spec["name"]})
            print(f"created workshop {workshop_code}: {workshop['id']}")
        organization_ids["workshops"][workshop_code] = workshop["id"]
        existing_teams = {
            item["code"]: item
            for item in client.get_json(f"/knowledge-access/workshops/{workshop['id']}/teams")
        }
        for team_code, team_name in workshop_spec["teams"].items():
            team = existing_teams.get(team_code)
            if team is None:
                team = client.post_json(f"/knowledge-access/workshops/{workshop['id']}/teams", {"code": team_code, "name": team_name})
                print(f"created team {team_code}: {team['id']}")
            organization_ids["teams"][team_code] = team["id"]

    for metadata in knowledge_bases.values():
        client.put_json(f"/knowledge-access/KNOWLEDGE_BASE/{metadata['kb_id']}/scopes", scopes("GLOBAL"))
    for document_code, metadata in document_map.items():
        source = catalog[document_code]
        workshop_id = organization_ids["workshops"][source["workshop_code"]]
        team_id = organization_ids["teams"][source["team_code"]]
        client.put_json(
            f"/knowledge-access/DOCUMENT/{metadata['ragent_doc_id']}/scopes",
            scopes("TEAM", workshop_id, team_id),
        )
    save_json(STATE / "organization_ids.json", organization_ids)
    print(f"configured ACL: {len(knowledge_bases)} knowledge bases and {len(document_map)} documents")


if __name__ == "__main__":
    main()
