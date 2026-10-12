# Deploying beFree

Production runs on a single-node k3s cluster managed by Flux from
[be-infra](https://github.com/AndDev741/be-infra). The manifests live there, in
`apps/befree` (namespace, quota, network policies, SOPS-encrypted Secrets,
backend, frontend, Ingress, image automation). The database is `befree` in the
cluster's shared CloudNativePG Postgres (`data/` in be-infra), and it is backed
up nightly, with a weekly restore drill, by beConscient's backup job
(`apps/beconscient/backup.yaml`), which dumps both databases.

```
Cloudflare Tunnel ──> Traefik (befree.beyouweb.com) ──> befree-frontend (nginx)
                                                          ├── /            static SPA
                                                          ├── /api/*       befree-backend
                                                          └── /webhooks/*  befree-backend
```

nginx serves the SPA and proxies `/api` and `/webhooks` to the backend, so the
browser, the phone and Meta all talk to one origin.

## Rolling out

CI pushes `:latest` and a tag per commit to GHCR on every push to `main`. Flux
image automation in be-infra commits the new `:latest` digest and rolls it out
within a few minutes. Roll back with `git revert` of that commit in be-infra;
`kubectl set image` is undone by the next reconcile.

Flyway does not roll back. Before a migration that is not purely additive, make
sure the previous backend image still runs against the new schema, or take a
manual backup first (`kubectl -n beconscient create job --from=cronjob/backup backup-now`).

## Notes

- Both images on GHCR are public, so the node pulls them without a secret.
  A package that ever turns private needs an imagePullSecret in be-infra.
- The webhook URL does not change. Meta keeps calling
  `https://befree.beyouweb.com/webhooks/whatsapp`; nginx forwards it untouched,
  which matters because the signature covers the raw body.
- The `befree-auth` Secret holds the login password and the key that signs the
  session cookie. Losing `session-key` logs everyone out on the next restart.
  Nothing else breaks.
