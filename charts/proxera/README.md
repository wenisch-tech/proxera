# Proxera Helm Chart

Proxera is a self-hosted reverse tunnel that lets HTTP services running in a private LAN be exposed to the internet without opening inbound firewall rules.

This Helm chart deploys the Proxera Server to a Kubernetes cluster. The server exposes one application port, `8080`, which serves both proxied traffic and the admin UI/API.

## Prerequisites

- Kubernetes 1.20+
- Helm 3.0+

## Installation

```bash
# Direct from chart source
helm install proxera ./charts/proxera -n proxera --create-namespace
```

### With Ingress

```bash
helm install proxera ./charts/proxera -n proxera --create-namespace \
  --set ingress.proxy.enabled=true \
  --set ingress.proxy.className=nginx \
  --set "ingress.proxy.hosts[0].host=proxy.example.com" \
  --set "ingress.proxy.hosts[0].paths[0].path=/" \
  --set "ingress.proxy.hosts[0].paths[0].pathType=Prefix" \
  --set ingress.admin.enabled=true \
  --set ingress.admin.className=nginx \
  --set "ingress.admin.hosts[0].host=admin.proxera.example.com" \
  --set "ingress.admin.hosts[0].paths[0].path=/" \
  --set "ingress.admin.hosts[0].paths[0].pathType=Prefix"
```

### With PostgreSQL

```bash
helm install proxera ./charts/proxera -n proxera --create-namespace \
  --set env.DB_HOST="postgres" \
  --set env.DB_NAME="proxera" \
  --set env.DB_USER="proxera" \
  --set secrets.DB_PASSWORD="your-password"
```

### Multi-Pod with Bundled Redis

```bash
helm install proxera ./charts/proxera -n proxera --create-namespace \
  --set replicaCount=3 \
  --set redis.enabled=true
```

This deploys a single in-cluster Redis instance for convenience. It enables multi-pod Proxera routing, but the Redis tier itself is not highly available.

### Multi-Pod with External Redis

```bash
helm install proxera ./charts/proxera -n proxera --create-namespace \
  --set replicaCount=3 \
  --set redis.host="redis" \
  --set redis.port=6379
```

## HA Setup

For actual high availability, configure all of the following:

- Use PostgreSQL for the application database. The default embedded H2 mode is suitable for single-pod setups, not multiple Proxera replicas.
- Run more than one Proxera replica, either with `replicaCount > 1` or with HPA plus `minReplicas > 1`.
- Enable Redis Pub/Sub, either with `redis.enabled=true` for the bundled in-cluster Redis or with `redis.host` pointing at an external Redis service.
- For production-grade HA, prefer an external Redis service with its own replication/failover. The bundled Redis is a single pod.
- If the admin UI is load-balanced across multiple replicas, enable sticky sessions on the admin ingress or otherwise ensure session affinity. The admin login uses server-side form-login sessions.

Recommended production pattern:

```bash
helm install proxera ./charts/proxera -n proxera --create-namespace \
  --set replicaCount=3 \
  --set env.DB_HOST="postgres" \
  --set env.DB_NAME="proxera" \
  --set env.DB_USER="proxera" \
  --set secrets.DB_PASSWORD="your-password" \
  --set redis.enabled=true
```

If you already operate Redis separately, keep `redis.enabled=false` and set `redis.host` instead.

## Configuration

See [values.yaml](values.yaml) for all available options.

Notable values:

- `proxy.requestTimeout`: server-side timeout for one proxied HTTP request waiting on an agent response. Defaults to `120s`.

## License

[GNU AGPL v3.0](https://github.com/wenisch-tech/proxera/blob/main/LICENSE)
