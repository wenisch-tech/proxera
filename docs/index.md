# Proxera Documentation

Welcome to the official Proxera documentation.

## What is Proxera?

Proxera is a self-hosted reverse tunnel that lets HTTP services running in a private LAN be exposed to the internet — without opening inbound firewall rules. It is similar in concept to Cloudflare Tunnel, implemented as a cloud-native Java / Spring Boot application.

## Quick Start

### Run with Docker

```bash
docker run -d \
  --name proxera \
  -p 8080:8080 \
  -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=dev \
  ghcr.io/wenisch-tech/proxera:latest
```

- Proxy port: [http://localhost:8080](http://localhost:8080)
- Admin UI: [http://localhost:8081/admin](http://localhost:8081/admin)

Default credentials (created on first start):

| Username | Password |
|----------|----------|
| admin@proxera.local | admin |

> **Warning:** Change the default password immediately after first login via Admin → Users.

### Run on Kubernetes with Helm

```bash
helm repo add wenisch-tech https://charts.wenisch.tech
helm repo update
helm install proxera wenisch-tech/proxera \
  --set ingress.proxy.enabled=true \
  --set ingress.proxy.hosts[0].host=proxy.example.com \
  --set ingress.admin.enabled=true \
  --set ingress.admin.hosts[0].host=admin.proxera.example.com \
  -n proxera --create-namespace
```

## Navigation

- [Architecture & Conceptual Design](architecture.md) — Full system design, protocol specification, data model
- [Configuration](configuration.md) — Environment variables, Helm values reference
- [API Reference](api.md) — REST API endpoints
