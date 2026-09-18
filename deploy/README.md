# Deploying beFree

One node, k3s, Traefik disabled. nginx is the only thing on the node port:
it serves the SPA and proxies `/api` and `/webhooks` to the backend, so the
browser, the phone and Meta all talk to one origin.

```
Cloudflare Tunnel ──> localhost:30080 ──> befree-frontend (nginx)
                                             ├── /            static SPA
                                             ├── /api/*       befree-backend
                                             └── /webhooks/*  befree-backend
```

## First rollout

The login password and the key that signs the session cookie are not in git.
Create them before rolling out the new backend, or nobody can sign in:

```sh
kubectl -n befree create secret generic befree-auth \
  --from-literal=password='<the password you will type>' \
  --from-literal=session-key="$(openssl rand -base64 32)"
```

Then apply, backend first. It gives up node port 30080, which nginx then takes;
the other order fails with "provided port is already allocated".

```sh
kubectl apply -f backend.yaml
kubectl apply -f frontend.yaml
kubectl -n befree rollout status deploy/befree-frontend
```

## Opening the tunnel

Until now `befree.beyouweb.com` only let `^/webhooks/whatsapp` through, because
everything else was unauthenticated. Once the new images are running, the rule
becomes the whole hostname:

```
  { "hostname": "befree.beyouweb.com", "service": "http://localhost:30080" }
```

Do it last. Opening it before the rollout points the internet at a backend with
no login. The other hostnames in `beyou-tunnel` share one config object, so the
change has to send the full ingress list back, not just this rule.

## Rolling back

The images keep a tag per commit, so a bad rollout goes back with:

```sh
kubectl -n befree set image deploy/befree-backend backend=ghcr.io/anddev741/befree-backend:<sha>
kubectl -n befree set image deploy/befree-frontend nginx=ghcr.io/anddev741/befree-frontend:<sha>
```

Flyway does not roll back. V6 only adds columns and a table, so the previous
backend image still runs against a V6 schema; the extra columns sit unused.

## Notes

- Both images on GHCR are public, so the node pulls them without a secret.
  A package that ever turns private needs an imagePullSecret here.
- The webhook URL does not change. Meta keeps calling
  `https://befree.beyouweb.com/webhooks/whatsapp`; nginx forwards it untouched,
  which matters because the signature covers the raw body.
- Losing `session-key` logs everyone out on the next restart. Nothing else breaks.
