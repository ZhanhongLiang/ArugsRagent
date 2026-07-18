---
name: stage-3-github-cicd-release
description: Create or update the master-branch GitHub Actions CI and GHCR image-release workflow for Ragent. Use this only in the local ArugsRagent repository after Stage 1 packaging and before the first server deployment. Publish immutable API and web images tagged only with the full commit SHA; do not SSH, deploy, or modify any server or Argus resource.
---

# Stage 3 — GitHub CI/CD Release (local repository only)

## Mission

Create the repository-side CI and image-release contract that Stage 2 will consume later:

```text
Pull request / master push
  -> source + deployment-contract verification
  -> master-only GHCR publish
  -> immutable API/Web images tagged by the full Git commit SHA
  -> first deployment remains a deliberate server-side manual action
```

This Skill runs **only in the local VSCode/Codex working tree**. It changes Git-tracked repository files only. It must never connect to Tencent Cloud, SSH to any host, inspect a remote Docker daemon, or run a production deployment.

## Fixed project contract

- Repository SSH source: `git@github.com:ZhanhongLiang/ArugsRagent.git`
- Release branch: `master`
- GHCR API repository: `ghcr.io/zhanhongliang/arugsragent-api`
- GHCR Web repository: `ghcr.io/zhanhongliang/arugsragent-web`
- Release tag: exactly the full 40-character `${{ github.sha }}`
- Runtime service names: `api`, `web`
- Runtime compose contract: `deploy/compose/compose.runtime.yaml`
- API Dockerfile: `deploy/docker/Dockerfile.api`
- Web Dockerfile: `deploy/docker/Dockerfile.web`
- First production deployment: manual on the server by `/opt/ragent/bin/deploy-release.sh <full-sha>` after images and server-only inputs are verified.

Do not substitute `latest`, `master`, a branch tag, or a short SHA for the deployable image tag.

## Hard boundaries

### Allowed changes

Only make the smallest necessary repository changes in:

- `.github/workflows/ragent-ci-release.yml`
- `ops/first-manual-ghcr-deploy.md` (create if absent)
- `.agents/skills/stage-3-github-cicd-release/**` only when maintaining this Skill itself

### Forbidden changes

Do **not**:

- SSH, use `scp`, `rsync`, cloud CLIs, Tencent Cloud APIs, or remote Docker contexts.
- Add any server host, port, private key, registry pull token, database password, model key, or production value to Git, workflow YAML, logs, or docs.
- Add a deployment job, `appleboy/ssh-action`, `ssh`, `curl` to a server, `docker context`, or any call to `/opt/ragent/bin/deploy-release.sh` from GitHub Actions.
- Modify `deploy/compose/**`, `deploy/docker/**`, `deploy/nginx/**`, application source, database schema, prompts, RAG logic, frontend features, or existing evaluation code.
- Publish `latest`, `master`, branch, or mutable image tags.
- Add a second Compose stack, a middleware stack, a health endpoint, or a server bootstrap script.
- Change or reference the unrelated Argus project, its `infra` project, its containers, its 80/443 ownership, or its configuration.

## Required reading before edits

Read these files first and report any mismatch rather than guessing:

1. `AGENTS.md`
2. `deploy/README.md`
3. `deploy/docker/Dockerfile.api`
4. `deploy/docker/Dockerfile.web`
5. `deploy/compose/compose.runtime.yaml`
6. `deploy/compose/.env.example`
7. `pom.xml`
8. `frontend/package.json`
9. `references/image-contract.md`
10. `references/forbidden-changes.md`
11. `references/workflow-template.yml`

Confirm that the current branch contract is `master`. Do not rename the repository default branch.

## Implementation procedure

### 1. Inspect, then preserve the Stage 1 contract

- Verify `deploy/compose/compose.runtime.yaml` remains image-first and contains no `build:` section.
- Verify `api` and `web` are the actual service names.
- Verify the API and Web Dockerfile paths above exist.
- Reuse the existing Maven wrapper and the frontend lockfile; do not introduce a new build system.

### 2. Create one workflow

Create `.github/workflows/ragent-ci-release.yml` from the reference template, adapting only commands that are proven necessary from the repository.

The workflow must have these behaviors:

#### Verification job — pull requests and pushes to `master`

- `actions/checkout@v4`
- Java 17 (Temurin) with Maven cache.
- Maven build through the repository wrapper and the actual bootstrap module dependency graph.
- Node 20 with npm cache.
- `npm ci`, lint only when the project has a lint script, and frontend production build.
- Render the runtime Compose contract with `docker compose ... config` using only `deploy/compose/.env.example`.
- Build the API and Web Dockerfiles without starting containers and without pushing images.

Do not weaken a failing build by adding `continue-on-error`, swallowing errors, disabling existing tests, or replacing commands with no-op commands.

#### Publish job — only `push` to `master` or manual dispatch from `master`

- Depend on successful verification.
- Use least privilege: `contents: read` and `packages: write` only for the publishing job.
- Resolve `GITHUB_REPOSITORY_OWNER` to lowercase before forming GHCR paths.
- Use `docker/login-action@v3` with `ghcr.io`, `${{ github.actor }}`, and `${{ secrets.GITHUB_TOKEN }}`.
- Use `docker/setup-buildx-action@v3` and `docker/build-push-action@v6`.
- Build API from repository root with `deploy/docker/Dockerfile.api`.
- Build Web from repository root with `deploy/docker/Dockerfile.web`.
- Push exactly one deployable tag per image: `${{ github.sha }}`.
- Add standard OCI source/revision labels.
- Print the complete API/Web image references and release SHA to the GitHub job summary.

No remote deployment step is allowed in this first-release workflow.

### 3. Document the first manual deployment handoff

Create `ops/first-manual-ghcr-deploy.md`.

It must document:

1. How to identify a successful master workflow run and full SHA.
2. The two exact GHCR repository bases.
3. That the server must first populate its existing `/opt/ragent/shared/registry.env`, `runtime.env`, `application-prod.yaml`, and `control.env` with server-only Ragent values according to its installed Stage 2 scripts.
4. How to authenticate to GHCR on the server only if the packages remain private. Never include an actual token.
5. How to verify image manifests for the full SHA.
6. The first manual server deployment sequence:
   - validate inputs;
   - deploy the exact full SHA;
   - run health checks;
   - compare the protected Docker snapshot.
7. That Argus remains unrelated and must not be touched.
8. That automatic SSH deployment is intentionally deferred to a later stage after the first manual release succeeds.

The document must not claim a health URL that is not already implemented. Refer to the Stage 2 `control.env` health URL contract instead.

### 4. Validate the result

Run these scripts after editing:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-3-github-cicd-release/scripts/verify-release-workflow.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-3-github-cicd-release/scripts/verify-ghcr-contract.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-3-github-cicd-release/scripts/verify-no-remote-deploy.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/stage-3-github-cicd-release/scripts/summarize-changed-files.ps1
```

Also run the repository’s non-destructive local build and Compose-render checks where the local environment has the required tools. Do not start Compose services.

## Completion report

In Chinese, report:

1. Exact files created or changed.
2. The CI triggers and which job runs on PR versus master push.
3. The fixed API/Web image bases and SHA-tag contract.
4. Every validation command and its result.
5. The required GitHub permission (`packages: write`) and whether any repository setting must be changed manually after the first publish.
6. The exact manual server handoff, with no secrets printed.
7. An explicit statement that no SSH, server deployment, remote Docker operation, or Argus change was made.
