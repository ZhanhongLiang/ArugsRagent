from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load(name: str):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def main() -> None:
    kbs = load("kb_specs.json")
    organization = load("organization_spec.json")
    intents = load("intent_tree_spec.json")
    catalog = load("doc_catalog.json")
    access = load("access_assignment_spec.json")
    errors: list[str] = []

    if len(kbs) != 11:
        errors.append(f"expected 11 KBs, got {len(kbs)}")
    if len(organization) != 5:
        errors.append(f"expected 5 workshops, got {len(organization)}")
    teams = {team for spec in organization.values() for team in spec["teams"]}
    if len(teams) != 16:
        errors.append(f"expected 16 teams, got {len(teams)}")
    if len(intents["domains"]) != 7:
        errors.append(f"expected 7 domains, got {len(intents['domains'])}")
    leaves = intents["intents"]
    if not 80 <= len(leaves) <= 120:
        errors.append(f"expected 80-120 leaves, got {len(leaves)}")
    node_codes = set(intents["domains"])
    parent_kind = {code: spec["kind"] for code, spec in intents["domains"].items()}
    for code, spec in intents["categories"].items():
        if code in node_codes:
            errors.append(f"duplicate domain/category code {code}")
        node_codes.add(code)
        if spec["parent"] not in parent_kind:
            errors.append(f"category {code} has unknown domain {spec['parent']}")
            continue
        parent_kind[code] = spec["kind"]
    for leaf in leaves:
        if leaf["code"] in node_codes:
            errors.append(f"duplicate node code {leaf['code']}")
        node_codes.add(leaf["code"])
        if leaf["parent"] not in parent_kind:
            errors.append(f"leaf {leaf['code']} has unknown parent")
            continue
        if parent_kind[leaf["parent"]] != leaf["kind"]:
            errors.append(f"route kind mismatch at {leaf['code']}")
        if leaf["kind"] == 0 and leaf.get("kb_key") not in kbs:
            errors.append(f"KB leaf {leaf['code']} has invalid KB mapping")
        if leaf["kind"] == 2 and not leaf.get("mcp_tool_id"):
            errors.append(f"MCP leaf {leaf['code']} has no tool")
    for code, document in catalog.items():
        if document["kb_key"] not in kbs:
            errors.append(f"document {code} has invalid KB")
        acl = document["acl"]
        if acl["scope_type"] not in {"GLOBAL", "WORKSHOP", "TEAM"}:
            errors.append(f"document {code} has invalid ACL scope")
        if acl["scope_type"] != "GLOBAL" and acl.get("workshop_code") not in organization:
            errors.append(f"document {code} references unknown workshop")
        if acl["scope_type"] == "TEAM" and acl.get("team_code") not in teams:
            errors.append(f"document {code} references unknown team")
        missing_metadata = {"factory_id", "workshop_id", "process_code", "equipment_type", "doc_type", "revision", "effective_status", "confidential_level"} - set(document["metadata"])
        if missing_metadata:
            errors.append(f"document {code} missing metadata {sorted(missing_metadata)}")
    if not access.get("role_templates") or not access.get("sample_assignments"):
        errors.append("missing ACL role templates or sample assignments")
    if errors:
        raise SystemExit("cell factory asset validation failed:\n- " + "\n- ".join(errors))
    print(f"cell factory assets valid: {len(kbs)} KBs, {len(organization)} workshops, {len(teams)} teams, {len(leaves)} leaves, {len(catalog)} documents")


if __name__ == "__main__":
    main()
