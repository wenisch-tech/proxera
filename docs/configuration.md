# Configuration

Proxera is configured via standard Spring Boot environment variables or `application.properties`.

## Environment Variables

### Database

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:h2:mem:proxera` | JDBC URL. Use `jdbc:postgresql://host:5432/proxera` for production. |
| `SPRING_DATASOURCE_USERNAME` | `sa` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | *(empty)* | Database password |

### Redis (optional — enables multi-pod scaling)

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | *(unset)* | When set, activates Redis Pub/Sub message bus for multi-pod deployments |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password |

When `REDIS_HOST` is not set, Proxera uses an in-memory message bus suitable for single-replica deployments.

### OIDC / OAuth2 (optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `OIDC_ENABLED` | `false` | Enable OIDC login on the Admin UI |
| `OIDC_ISSUER_URI` | *(empty)* | OIDC provider issuer URI (e.g. `https://keycloak.example.com/realms/myrealm`) |
| `OIDC_CLIENT_ID` | *(empty)* | OIDC client ID |
| `OIDC_CLIENT_SECRET` | *(empty)* | OIDC client secret |

### Application

| Variable | Default | Description |
|----------|---------|-------------|
| `PROXERA_ADMIN_PORT` | `8081` | Admin UI / API port |
| `PROXERA_LOG_RETENTION_DAYS` | `7` | Days to retain access log entries |
| `PROXERA_TUNNEL_HEARTBEAT_INTERVAL` | `30` | Seconds between client heartbeat pings |
| `PROXERA_TUNNEL_HEARTBEAT_TIMEOUT` | `10` | Seconds to wait for pong before disconnecting |
| `PROXERA_POD_ID` | *(hostname)* | Identifier for this pod in multi-pod topology display |

## Helm Values Reference

See [charts/proxera/values.yaml](https://github.com/wenisch-tech/proxera/blob/main/charts/proxera/values.yaml) for the full annotated values file.

### Key sections

```yaml
# Image
image:
  registry: ghcr.io
  repository: wenisch-tech/proxera
  tag: latest

# Ingress — two separate ingress objects
ingress:
  proxy:
    enabled: false
    className: nginx
    hosts:
      - host: proxy.example.com
        paths:
          - path: /
            pathType: Prefix
  admin:
    enabled: false
    className: nginx
    hosts:
      - host: admin.proxera.example.com
        paths:
          - path: /
            pathType: Prefix

# Redis (leave host empty for single-pod in-memory mode)
redis:
  enabled: false
  host: ""
  port: 6379

# Persistence (for H2 file-based database in dev)
persistence:
  enabled: false
  size: 1Gi

# Non-sensitive environment variables
env:
  SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/proxera"
  SPRING_DATASOURCE_USERNAME: "proxera"

# Sensitive environment variables (stored in a Kubernetes Secret)
secrets:
  SPRING_DATASOURCE_PASSWORD: "your-db-password"
  OIDC_CLIENT_SECRET: ""
```

## PostgreSQL Setup

```bash
helm install proxera wenisch-tech/proxera \
  -n proxera --create-namespace \
  --set env.SPRING_DATASOURCE_URL="jdbc:postgresql://postgres:5432/proxera" \
  --set env.SPRING_DATASOURCE_USERNAME="proxera" \
  --set secrets.SPRING_DATASOURCE_PASSWORD="your-password"
```

## Multi-Pod with Redis

```bash
helm install proxera wenisch-tech/proxera \
  -n proxera --create-namespace \
  --set replicaCount=3 \
  --set env.REDIS_HOST="redis" \
  --set env.REDIS_PORT="6379"
```

> Redis is not bundled in the Helm chart. Deploy Redis separately (e.g. Bitnami Redis chart) and point `REDIS_HOST` at the Redis service name.
