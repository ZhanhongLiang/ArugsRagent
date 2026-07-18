# Stage 3 image contract

This contract is deliberately fixed so the server never needs to infer image names from a Git repository name.

| Component | Immutable image reference |
|---|---|
| API | `ghcr.io/zhanhongliang/arugsragent-api:<FULL_40_CHAR_GIT_SHA>` |
| Web | `ghcr.io/zhanhongliang/arugsragent-web:<FULL_40_CHAR_GIT_SHA>` |

## Rules

- The only deployable tag is the full GitHub Actions commit SHA: `${{ github.sha }}`.
- No `latest`, `master`, branch, date, release-name, or abbreviated-SHA tag may be used for deployment.
- The repository owner must be lowercased before it becomes part of a Docker image reference.
- OCI labels must include the source repository and commit revision.
- Stage 2 owns `registry.env` and server-side pull authentication. Stage 3 only publishes images.
