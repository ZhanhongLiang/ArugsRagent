# Codex Prompt — Stage 0 Argus Rebrand

You are working in the repository cloned from `https://github.com/nageoffer/ragent`.

Use the repository-level `AGENTS.md` and the skill `.agents/skills/stage-0-argus-rebrand/SKILL.md`.

## Mission

Execute **Stage 0: Argus Rebrand / Teaching-Trace Removal**.

Rename the user-facing product identity from the original Ragent branding to:

- Product short name: `Argus`
- Product full name: `Argus AI`
- Description: `企业级 RAG 智能问答平台`

This stage focuses on removing visible original-project shadows from the frontend and product-facing docs while preserving runtime compatibility.

## Absolutely Forbidden

Do **not** modify anything that would require database, Docker, middleware, deployment or backend package reconfiguration.

Specifically, do not change:

- Database name `ragent`
- `POSTGRES_DB=ragent`
- JDBC URLs containing `/ragent`
- Docker Compose service/container/env/port/volume/network settings
- Redis key prefixes and RocketMQ names if used by runtime
- Java packages such as `com.nageoffer.ai.ragent`
- `RagentApplication` or `MCPServerApplication`
- Maven modules `bootstrap`, `framework`, `infra-ai`, `mcp-server`
- SQL schema/init/upgrade scripts
- API endpoint paths
- Request/response DTO/VO/entity field names
- Authentication behavior
- RAG retrieval, model routing, MCP, ingestion or memory architecture
- License or required copyright notices

Do not perform blind global search-and-replace.

## Required Design

Implement the rebrand using a decoupled branding abstraction in the frontend.

Preferred shape:

```ts
export const BRAND = {
  shortName: 'Argus',
  fullName: 'Argus AI',
  description: '企业级 RAG 智能问答平台',
  consoleName: 'Argus 管理控制台',
} as const;
```

Choose the actual path according to the existing frontend structure, for example:

- `frontend/src/config/brand.ts`
- `frontend/src/constants/brand.ts`
- `frontend/src/lib/brand.ts`

Then replace visible UI copy by importing the brand constants where practical.

## Work Steps

1. Read:
   - `AGENTS.md`
   - `.agents/skills/stage-0-argus-rebrand/SKILL.md`
   - `.agents/skills/stage-0-argus-rebrand/references/forbidden-changes.md`
   - `.agents/skills/stage-0-argus-rebrand/references/domain-map.md`

2. Run or inspect the trace scan:
   - `.\scripts\grep-teaching-traces.ps1`

3. Classify hits:
   - Replace now: frontend user-visible product copy.
   - Preserve intentionally: DB, Docker, Java package, Maven, API, SQL, middleware identifiers.
   - Review carefully: README/docs/legal/setup text.

4. Update frontend visible branding:
   - `index.html` title/meta if present.
   - Login page.
   - Sidebar/header product title.
   - Dashboard and console page copy.
   - Chat welcome text.
   - Knowledge-base management copy.
   - Intent/MCP/trace/settings copy.
   - Empty states, toasts, footer/about/help text.
   - Any visible `nageoffer` / training-course / community copy.

5. Update product-facing README/docs only when safe:
   - Product packaging may become Argus.
   - Setup commands and DB/Docker parameters must remain technically correct.
   - Keep legal notices.

6. Verify:
   - `.\scripts\grep-teaching-traces.ps1`
   - `.\scripts\verify-frontend-build.ps1`
   - `.\scripts\verify-backend-build.ps1`
   - `.\scripts\summarize-changed-files.ps1`

If a check cannot run, state the exact reason.

## Completion Summary Required

When done, respond with:

1. User-facing branding changes made.
2. Brand abstraction file path.
3. Technical `ragent` identifiers intentionally preserved.
4. Verification commands and results.
5. Remaining review items.
