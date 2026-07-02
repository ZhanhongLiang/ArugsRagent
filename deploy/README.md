# Stage 1 Local Deploy Packaging

This directory is a local-only deployment package for the Ragent/Argus repository. It prepares reviewable Docker image definitions, an image-first Compose runtime contract, local build overlays, Nginx proxy configuration, placeholder-only production configuration, and local validation commands.

Stage 1 does not deploy to any server. It does not create CI/CD workflows, registry login steps, rollback scripts, server bootstrap scripts, production secrets, or middleware stacks.

## Files

```text
deploy/
├── README.md
├── docker/
│   ├── Dockerfile.api
│   └── Dockerfile.web
├── nginx/
│   └── default.conf
├── compose/
│   ├── compose.runtime.yaml
│   ├── compose.local.yaml
│   ├── .env.example
│   └── application-prod.yaml.example
└── scripts/
    └── local-smoke.ps1
```

## Runtime Split

`deploy/compose/compose.runtime.yaml` is the portable runtime contract. It uses immutable image variables and contains no `build:` section. It defines only the application services:

- `api`: Spring Boot service, private to the Compose network, no host port mapping.
- `web`: Nginx static web and reverse proxy, the only service publishing a host port.

`deploy/compose/compose.local.yaml` is the local build overlay. It adds `build:` definitions for local image creation and must be used together with the runtime file during local validation.

## Local Validation

From the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-1-local-deploy-packaging/scripts/preflight-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-1-local-deploy-packaging/scripts/verify-backend-build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-1-local-deploy-packaging/scripts/verify-frontend-build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-1-local-deploy-packaging/scripts/verify-compose.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/local-smoke.ps1 -BuildImages
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-1-local-deploy-packaging/scripts/assert-local-only.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-1-local-deploy-packaging/scripts/summarize-changed-files.ps1
```

To render the Compose contract directly:

```powershell
docker compose --env-file deploy/compose/.env.example `
  -f deploy/compose/compose.runtime.yaml `
  -f deploy/compose/compose.local.yaml config
```

To build local images without starting containers:

```powershell
docker compose --env-file deploy/compose/.env.example `
  -f deploy/compose/compose.runtime.yaml `
  -f deploy/compose/compose.local.yaml build api web
```

Do not use destructive Docker cleanup commands for Stage 1 validation.

## Configuration Inputs

All values below are placeholders or local defaults. Real production values must be supplied outside Git-tracked files.

| Variable | Purpose | Example placeholder |
| --- | --- | --- |
| `RAGENT_API_IMAGE` | API image reference | `ragent-api:local` |
| `RAGENT_WEB_IMAGE` | Web image reference | `ragent-web:local` |
| `RAGENT_WEB_BIND_HOST` | Host bind address for web only | `127.0.0.1` |
| `RAGENT_WEB_PORT` | Host port for web only | `18080` |
| `RAGENT_API_CONFIG_FILE` | External Spring production config mounted read-only | `./application-prod.yaml.example` |
| `RAGENT_POSTGRES_URL` | External PostgreSQL JDBC URL | `jdbc:postgresql://postgres-host:5432/ragent?client_encoding=UTF8` |
| `RAGENT_REDIS_HOST` | External Redis host | `redis-host` |
| `RAGENT_ROCKETMQ_NAME_SERVER` | External RocketMQ name server | `rocketmq-host:9876` |
| `RAGENT_RUSTFS_URL` | External S3-compatible object storage endpoint | `http://rustfs-host:29000` |
| `RAGENT_MCP_DEFAULT_URL` | External MCP server endpoint | `http://mcp-host:9099` |
| `RAGENT_*_PASSWORD` / `*_KEY*` | Secret placeholders only | `CHANGE_ME` |

`deploy/compose/application-prod.yaml.example` deliberately sets:

```yaml
app:
  eval:
    enabled: false
```

The evaluation endpoint is for local evaluation workflows and should not be enabled by the production template.

## SSE Proxy Contract

The frontend calls the API through `/api`. Nginx proxies `/api/` to the private `api:9090` service. The stream endpoint `/api/ragent/rag/v3/chat` has a dedicated location that disables buffering and caching, uses HTTP/1.1 upstream behavior, and keeps long-lived reads open so token streaming is not buffered.

## Known Blockers and Uncertainties

- The current backend dependencies do not include Spring Boot Actuator, and repository scanning did not find a dedicated unauthenticated health endpoint. Because Stage 1 must not change backend business code, `compose.runtime.yaml` documents this instead of inventing a port-only health check.
- The API depends on externally managed PostgreSQL, Redis, RocketMQ, object storage, model providers, and optionally MCP/Milvus depending on runtime configuration. Stage 1 does not create or replace those services.
- The current local workspace used during packaging was not a Git repository, so Git-based changed-file scripts may not be able to summarize diffs in this checkout.

## Stage 2 Hand-Off

Stage 2 consumes:

1. `deploy/compose/compose.runtime.yaml`
2. `deploy/compose/.env.example`
3. `deploy/compose/application-prod.yaml.example`
4. immutable API and Web image references selected by a future release process
5. this README

Stage 2 owns server paths, registry authentication, real secrets, host gateway integration, deployment, rollback, and remote health checks. Stage 2 must not edit RAG source code to deploy the package.