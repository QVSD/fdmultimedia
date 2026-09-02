# infra/docker

Reserved for Docker resources shared across services (e.g. a common base
image, provisioning scripts) once they're needed.

For Phase 1, each app owns its own `Dockerfile` (`apps/api-spring/Dockerfile`,
`apps/web-angular/Dockerfile`), and the reverse proxy config lives in
[`../nginx`](../nginx). The root [`docker-compose.yml`](../../docker-compose.yml)
wires everything together.
