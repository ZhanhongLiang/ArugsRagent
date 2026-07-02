# AGENTS.md

This file provides global guidance to Codex when working in the Argus repository.

Argus is a rebranded Java 17 + Spring Boot 3.5 enterprise RAG / Agentic RAG platform derived from the `nageoffer/ragent` codebase. Treat this file as the repository-level contract. Stage-specific work must additionally follow the relevant `.agents/skills/**/SKILL.md` instructions.

## Current Stage

The current stage is **Stage 0: Argus Rebrand / Teaching-Trace Removal**.

The goal is to remove user-facing traces of the original `Ragent` project and present the product as **Argus**, while preserving technical compatibility. This is a controlled refactor, not a full package/database/middleware rename.

## Build Commands

### Backend

Run backend commands from the repository root unless the current repository has a different documented workflow.

```bash
# JDK 17 is required for the original Ragent codebase
./mvnw clean compile
./mvnw test
./mvnw test -Dtest=ClassName#methodName
./mvnw clean package -DskipTests
```

On Windows PowerShell:

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests
```

If Maven Wrapper is unavailable, use the installed `mvn` command only after confirming the repository does not provide `mvnw` / `mvnw.cmd`.

### Frontend

```bash
cd frontend
npm install
npm run build
```

Before running optional checks, inspect `frontend/package.json` and use only existing scripts.

```bash
npm run type-check
npm run lint
npm run test
```

If a script does not exist, do not invent it. Use `npm run` or inspect `package.json`.

## Current Repository Layout

```text
project-root/
├── AGENTS.md                       # This global Codex guidance file
├── .agents/                        # Stage-specific Codex skills
├── docs/                           # Local project notes and stage task docs
├── scripts/                        # Root-level verification and inspection scripts
├── bootstrap/                      # Main Spring Boot application: controllers, RAG, knowledge, ingestion, admin, user
├── framework/                      # Shared infrastructure: result wrapper, exceptions, context, idempotency, SSE, trace
├── infra-ai/                       # AI infrastructure: chat, embedding, rerank, model routing and health
├── mcp-server/                     # Independent MCP server application and tools
├── frontend/                       # React + TypeScript + Vite frontend
├── resources/                      # Docker, database, format and middleware resources
├── assets/                         # Static assets and screenshots
├── scripts/                        # Existing repository scripts
├── pom.xml                         # Parent Maven POM
├── mvnw / mvnw.cmd                 # Maven Wrapper
└── README.md
```

Do not rename these directories during Stage 0. Directory/module renames belong to a later migration stage and require a separate plan.

## Current Baseline

- Backend language: Java 17.
- Backend framework: Spring Boot 3.5.x.
- Backend modules: `bootstrap`, `framework`, `infra-ai`, `mcp-server`.
- Frontend stack: React 18 + TypeScript + Vite.
- Database: PostgreSQL + pgvector.
- Cache and distributed coordination: Redis + Redisson.
- Object storage: S3-compatible storage, commonly RustFS / MinIO depending on local setup.
- MQ: RocketMQ.
- Auth: Sa-Token.
- Document parsing: Apache Tika.
- AI capability layer: chat, embedding, rerank, model routing, fallback and circuit breaking.
- RAG capability layer: document ingestion, chunking, query rewrite, intent recognition, retrieval, MCP tool routing, memory, prompt assembly, SSE streaming and trace.

## Stage 0 Branding Contract

### Product name

Use this product naming consistently in user-visible surfaces:

```text
Product short name: Argus
Product full name: Argus AI
Product description: 企业级 RAG 智能问答平台
```

Preferred Chinese wording:

```text
Argus 智能问答平台
Argus 知识库问答
Argus 管理控制台
```

Avoid reusing `Ragent`, `Ragent AI`, `nageoffer`, training-course wording, star/community copy, or other original teaching-project shadows in the frontend UI.

### What may be changed in Stage 0

- Frontend display text.
- Frontend browser title, metadata and favicon/title assets if present.
- Frontend constants/config files added for centralized branding.
- Frontend login page, sidebar/header, dashboard labels, empty states, help text and footer text.
- Public README or docs only when the content is clearly user-facing product packaging, not operational configuration.
- Local `docs/` files that describe the stage work.
- Root `AGENTS.md` and `.agents/skills/**` instructions.

### What must not be changed in Stage 0

The following items are technical compatibility points and must remain unchanged unless the user explicitly starts a later migration stage:

- Database name: keep `ragent`.
- JDBC URLs that point to `/ragent`.
- Docker Compose service names, container names, environment variable values, ports, networks and volumes.
- `POSTGRES_DB=ragent`.
- Redis key prefixes and RocketMQ names if they are used by the running environment.
- SQL schema file names and table names.
- Maven groupId/artifactId if changing them would cascade into code or deployment changes.
- Java package names such as `com.nageoffer.ai.ragent`.
- Java class names such as `RagentApplication`, unless the user explicitly approves a backend identity migration.
- API endpoint paths, request/response contracts and OpenAPI/Knife4j routes.
- Existing database migrations and init data that are needed to start the project.
- Any credentials, API keys, local middleware passwords or deployment secrets.
- Apache License, copyright headers or required legal notices.

## Architecture Rules

### Open/Closed Principle

Do not implement the rebrand as a one-off scattering of literal replacements.

Prefer adding or reusing a single branding abstraction, for example:

```text
frontend/src/config/brand.ts
frontend/src/constants/brand.ts
frontend/src/lib/brand.ts
```

The exact path must follow the existing frontend structure. Export constants such as:

```ts
export const BRAND = {
  shortName: 'Argus',
  fullName: 'Argus AI',
  description: '企业级 RAG 智能问答平台',
};
```

Then import the constants in UI components instead of duplicating string literals.

### Decoupling

- UI display identity must be decoupled from backend package identity.
- Runtime configuration must be decoupled from marketing/product display text.
- Do not couple frontend display text to database names, Docker names or Java package names.
- Keep technical identifiers stable until a dedicated migration stage exists.

### Minimal-risk changes

- Inspect before editing.
- Prefer small, reviewable diffs.
- Avoid global search-and-replace.
- Keep the app startable after each logical group of changes.
- When uncertain whether a `ragent` occurrence is technical or user-facing, keep it and document it in the verification summary.

## Backend Rules

- Preserve the existing module structure.
- Preserve `bootstrap`, `framework`, `infra-ai`, `mcp-server`.
- Preserve existing API contracts.
- Preserve existing DTO/VO/entity field names.
- Do not rename Java packages in Stage 0.
- Do not create database migrations for branding only.
- Do not change `application.yaml` values that affect local middleware.
- Do not change Docker Compose parameters.
- Do not introduce Spring Security; the original project uses Sa-Token.
- Do not replace the model routing, retrieval, ingestion or MCP architecture.

User-facing backend metadata can be adjusted only if it is safe and clearly display-only, for example an API docs title. Keep the underlying application identifiers unchanged.

## Frontend Rules

- Use the existing React + TypeScript + Vite conventions.
- Inspect `frontend/package.json` before adding dependencies. Do not add dependencies for this stage unless unavoidable.
- Centralize brand strings.
- Replace UI-visible `Ragent` / `Ragent AI` / `nageoffer` text with `Argus` / `Argus AI`.
- Update page title and metadata if present.
- Update logos only if assets are text-based or easy to adjust safely. Do not spend time generating new binary art unless the user provided assets.
- Keep routes, API client paths and request payloads unchanged.
- Keep functional behavior unchanged.

## Documentation Rules

- Root README and public-facing docs may be rewritten to present the project as Argus, but do not delete useful setup instructions.
- Do not rewrite operational docs in a way that changes database or middleware configuration.
- Keep a short migration note in `docs/task-board.md` or the stage summary listing intentionally preserved technical `ragent` identifiers.

## Verification Rules

Before finishing Stage 0, run the most appropriate checks available on the local machine:

```powershell
.\scripts\grep-teaching-traces.ps1
.\scripts\verify-frontend-build.ps1
.\scripts\verify-backend-build.ps1
.\scripts\summarize-changed-files.ps1
```

If a check cannot run because a tool is missing, record that honestly in the final summary. Do not claim successful verification without command output.

## Final Response Expectations for Codex

When the stage is complete, summarize:

1. What user-facing branding was changed.
2. What central branding abstraction was added or reused.
3. Which technical `ragent` identifiers were intentionally preserved.
4. Which checks passed or failed.
5. Any remaining visible traces that need user review.
