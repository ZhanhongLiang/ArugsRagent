# Stage 3 acceptance checklist

## Skill structure

- [ ] Installed path is `.agents/skills/stage-3-github-cicd-release/SKILL.md`.
- [ ] `SKILL.md` front matter has non-empty `name` and `description`.
- [ ] Skill can be explicitly invoked with `$stage-3-github-cicd-release`.

## Workflow

- [ ] Exactly one release workflow exists at `.github/workflows/ragent-ci-release.yml`.
- [ ] `pull_request` targets `master`.
- [ ] `push` targets `master`.
- [ ] A manual `workflow_dispatch` trigger exists.
- [ ] Verification runs Maven, frontend build, Compose render, and Dockerfile build checks without starting services.
- [ ] Publish runs only after verification and never for a pull request.
- [ ] Publish uses `GITHUB_TOKEN` with `packages: write`.
- [ ] API image is `ghcr.io/zhanhongliang/arugsragent-api:<full SHA>`.
- [ ] Web image is `ghcr.io/zhanhongliang/arugsragent-web:<full SHA>`.
- [ ] No deploy, SSH, server, or remote-Docker command appears in the workflow.
- [ ] No `latest`, `master`, branch, date, or short-SHA deployment tag appears.

## Handoff

- [ ] `ops/first-manual-ghcr-deploy.md` exists.
- [ ] It explicitly says initial deployment is manual through Stage 2 scripts.
- [ ] It mentions server-only configuration and does not include real values.
- [ ] It protects the unrelated Argus project.
