# Proxera Helm Chart

Proxera is a self-hosted reverse tunnel that lets HTTP services running in a private LAN be exposed to the internet without opening inbound firewall rules.

This Helm chart deploys the Proxera Server to a Kubernetes cluster. The server exposes two ports:
- **8080** — proxy port (public-facing, receives HTTP/WebSocket traffic)
- **8081** — admin UI and REST API port

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
  --set env.SPRING_DATASOURCE_URL="jdbc:postgresql://postgres:5432/proxera" \
  --set env.SPRING_DATASOURCE_USERNAME="proxera" \
  --set secrets.SPRING_DATASOURCE_PASSWORD="your-password"
```

### Multi-Pod with Redis

```bash
helm install proxera ./charts/proxera -n proxera --create-namespace \
  --set replicaCount=3 \
  --set env.REDIS_HOST="redis" \
  --set env.REDIS_PORT="6379"
```

## Configuration

See [values.yaml](values.yaml) for all available options.

## License

[GNU AGPL v3.0](https://github.com/wenisch-tech/proxera/blob/main/LICENSE)
