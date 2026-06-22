# Configuration

Proxera is configured entirely through environment variables (or the equivalent `env` / `secrets` sections in the Helm chart).

---

## Database

By default, Proxera starts with an **embedded H2 database** — no external services required.  
Set `DB_HOST` to switch to PostgreSQL.

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | *(unset)* | PostgreSQL host. **When unset, embedded H2 is used.** |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `proxera` | PostgreSQL database name |
| `DB_USER` | `proxera` | PostgreSQL username |
| `DB_PASSWORD` | *(empty)* | PostgreSQL password — store in a Kubernetes Secret |

!!! info "H2 console"
    The H2 web console is accessible at `/h2-console` when running with the embedded database.

**Example — switch to PostgreSQL (Docker):**

```bash
docker run -d -p 8080:8080 \
  -e DB_HOST=postgres.example.com \
  -e DB_NAME=proxera \
  -e DB_USER=proxera \
  -e DB_PASSWORD=secret \
  ghcr.io/wenisch-tech/proxera:latest
```

---

## Redis (optional — multi-pod scaling)

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | *(unset)* | Redis host. **When unset, the in-memory message bus is used.** |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password |

When `REDIS_HOST` is set, Proxera uses Redis Pub/Sub to route tunnel frames across multiple pods, enabling horizontal scaling.

!!! note
    The Helm chart can deploy a bundled Redis when `redis.enabled=true`. If you prefer to run Redis separately, leave `redis.enabled=false` and point `redis.host` at your existing Redis service.

!!! warning
    The bundled chart Redis is a single pod intended to make multi-replica Proxera setup easy. For end-to-end HA, use an external Redis service that provides replication and failover.

---

## OIDC / OAuth2 (optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `OIDC_ENABLED` | `false` | Enable OIDC login on the Admin UI |
| `OIDC_ISSUER_URI` | *(empty)* | OIDC provider issuer URI (e.g. `https://keycloak.example.com/realms/myrealm`) |
| `OIDC_CLIENT_ID` | *(empty)* | OIDC client ID |
| `OIDC_CLIENT_SECRET` | *(empty)* | OIDC client secret — store in a Kubernetes Secret |

---

## Application

| Variable | Default | Description |
|----------|---------|-------------|
| `PROXERA_LOG_RETENTION_DAYS` | `7` | Days to retain access log entries |
| `PROXERA_TUNNEL_HEARTBEAT_INTERVAL` | `30` | Seconds between agent heartbeat pings |
| `PROXERA_TUNNEL_HEARTBEAT_TIMEOUT` | `10` | Seconds to wait for pong before disconnecting |
| `PROXERA_PROXY_REQUEST_TIMEOUT` | `120s` | Maximum time one proxied HTTP request waits for an agent response |
| `PROXERA_POD_ID` | *(hostname)* | Identifier for this pod in multi-pod topology display |

---

## Helm Values Reference

See [charts/proxera/values.yaml](https://github.com/wenisch-tech/proxera/blob/main/charts/proxera/values.yaml) for the full annotated values file.

### Key sections

```yaml
image:
  registry: ghcr.io
  repository: wenisch-tech/proxera
  tag: latest

# Admin ingress (proxy ingress is managed at runtime via the Topology UI)
ingress:
  admin:
    enabled: false
    className: nginx
    annotations:
      nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
      nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    hosts:
      - host: admin.proxera.example.com
        paths:
          - path: /
            pathType: Prefix
    tls: []

# Redis (`enabled=true` deploys bundled Redis; otherwise set `host` for external Redis)
redis:
  enabled: false
  host: ""
  port: 6379

# Persistence (for H2 file-based database)
persistence:
  enabled: false
  size: 1Gi

# Non-sensitive environment variables (mounted as ConfigMap)
env:
  PROXERA_LOG_RETENTION_DAYS: "7"
  # DB_HOST: "postgres"
  # DB_PORT: "5432"
  # DB_NAME: "proxera"
  # DB_USER: "proxera"

# Sensitive environment variables (stored in a Kubernetes Secret)
secrets:
  DB_PASSWORD: ""
  OIDC_CLIENT_SECRET: ""
  REDIS_PASSWORD: ""
```

### PostgreSQL example

```bash
helm install proxera wenisch-tech/proxera \
  -n proxera --create-namespace \
  --set env.DB_HOST="postgres" \
  --set env.DB_NAME="proxera" \
  --set env.DB_USER="proxera" \
  --set secrets.DB_PASSWORD="your-password"
```

### Multi-pod with Redis

```bash
helm install proxera wenisch-tech/proxera \
  -n proxera --create-namespace \
  --set replicaCount=3 \
  --set redis.enabled=true
```

### Multi-pod with external Redis

```bash
helm install proxera wenisch-tech/proxera \
  -n proxera --create-namespace \
  --set replicaCount=3 \
  --set redis.host="redis" \
  --set redis.port=6379
```

### HA prerequisites

- Use PostgreSQL for shared application state. Embedded H2 is not appropriate for multi-replica deployments.
- Run at least two Proxera replicas.
- Enable Redis Pub/Sub, either bundled or external.
- Prefer external Redis if you need the Redis layer itself to be highly available.
- If the admin UI is balanced across replicas, enable sticky sessions on the admin ingress because login state is session-based.

