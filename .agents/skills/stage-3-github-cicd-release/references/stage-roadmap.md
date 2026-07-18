# Release roadmap

```text
Stage 1 — Local packaging
  deploy/ Dockerfiles + image-first Compose + static validation

Stage 2 — Isolated server runtime bootstrap
  /opt/ragent + ragent-prod + server-only config + protected Docker snapshots

Stage 3 — This Skill
  master CI verification + GHCR full-SHA API/Web image publication
  First deployment remains manual on the server

Stage 4 — Only after manual deployment is proven
  Optional, narrowly scoped GitHub Actions SSH trigger calling the already-tested
  /opt/ragent/bin/deploy-release.sh <full-sha>
```
