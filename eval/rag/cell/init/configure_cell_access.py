from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "init"))

from client import RagentClient, STATE, load_json, save_json


def resource_scope(scope: dict, organization_ids: dict) -> dict:
    item = {"scopeType": scope["scope_type"]}
    if scope["scope_type"] != "GLOBAL":
        item["workshopId"] = organization_ids["workshops"][scope["workshop_code"]]
    if scope["scope_type"] == "TEAM":
        item["teamId"] = organization_ids["teams"][scope["team_code"]]
    return item


def user_scope(scope: dict, organization_ids: dict) -> dict:
    item = {"scopeType": scope["scope_type"], "workshopId": organization_ids["workshops"][scope["workshop_code"]]}
    if scope["scope_type"] == "TEAM":
        item["teamId"] = organization_ids["teams"][scope["team_code"]]
    return item


def main() -> None:
    parser = argparse.ArgumentParser(description="Create cell-factory workshops/teams and configure resource ACLs.")
    parser.add_argument("--assign-samples", action="store_true", help="Bind only already-existing sample usernames from access_assignment_spec.json.")
    args = parser.parse_args()

    organization = load_json(ROOT / "organization_spec.json")
    catalog = load_json(ROOT / "doc_catalog.json")
    knowledge_bases = load_json(STATE / "kb_ids.json")
    document_map = load_json(STATE / "doc_id_map.json")
    if not knowledge_bases or not document_map:
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
        existing_teams = {item["code"]: item for item in client.get_json(f"/knowledge-access/workshops/{workshop['id']}/teams")}
        for team_code, team_name in workshop_spec["teams"].items():
            team = existing_teams.get(team_code)
            if team is None:
                team = client.post_json(f"/knowledge-access/workshops/{workshop['id']}/teams", {"code": team_code, "name": team_name})
                print(f"created team {team_code}: {team['id']}")
            organization_ids["teams"][team_code] = team["id"]

    for metadata in knowledge_bases.values():
        client.put_json(f"/knowledge-access/KNOWLEDGE_BASE/{metadata['kb_id']}/scopes", {"scopes": [{"scopeType": "GLOBAL"}]})
    for document_code, document in document_map.items():
        source = catalog[document_code]
        client.put_json(
            f"/knowledge-access/DOCUMENT/{document['ragent_doc_id']}/scopes",
            {"scopes": [resource_scope(source["acl"], organization_ids)]},
        )

    save_json(STATE / "organization_ids.json", organization_ids)
    print(f"configured ACL: {len(knowledge_bases)} knowledge bases, {len(document_map)} documents")

    if not args.assign_samples:
        return
    assignments = load_json(ROOT / "access_assignment_spec.json")["sample_assignments"]
    users = {item["username"]: item for item in client.get_json("/users?current=1&size=200").get("records", [])}
    for username, scopes in assignments.items():
        user = users.get(username)
        if user is None:
            print(f"skip missing sample user: {username}")
            continue
        client.put_json(f"/knowledge-access/users/{user['id']}/scopes", {"scopes": [user_scope(scope, organization_ids) for scope in scopes]})
        print(f"assigned sample user {username}")


if __name__ == "__main__":
    main()
