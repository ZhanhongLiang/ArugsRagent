# Forbidden changes for Stage 3

Stage 3 is a repository-side CI and image-release task. It must not become a deployment task.

Forbidden:

- Any SSH action, `ssh`, `scp`, `rsync`, Tencent Cloud API/CLI, remote Docker context, `docker context use`, server webhook, or server URL.
- Any use of `DEPLOY_HOST`, `DEPLOY_PORT`, `DEPLOY_USER`, SSH private keys, registry pull tokens, production database credentials, model keys, or other real secrets.
- Any workflow action that deploys, restarts, stops, rolls back, or checks a remote server.
- Any change under `deploy/`, application source modules, schema, prompts, evaluation code, or frontend product code.
- Mutable image tags such as `latest`, `master`, a branch, or a short SHA.
- Any change to the unrelated Argus project or assumptions that Ragent can reuse Argus runtime services.
