# Proxera

[![GitHub Release](https://img.shields.io/github/v/release/wenisch-tech/proxera)](https://github.com/wenisch-tech/proxera/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/wenisch-tech/proxera/blob/main/LICENSE)
[![Container](https://img.shields.io/badge/ghcr.io-proxera-blue)](https://github.com/wenisch-tech/proxera/pkgs/container/proxera)

Proxera is a self-hosted reverse tunnel that lets HTTP services running in a private LAN be exposed to the internet — without opening any inbound firewall rules. It is similar in concept to Cloudflare Tunnel, but you don't have to route your traffic unencrypted through some 3rd party datacenters.

Please note: This application is still in an early alpha and under active development. Breaking changes to the API might be introduce anytime until a v1 Release.

## How It Works

1. **Proxera Server** runs in Kubernetes (this repository). It receives public HTTP requests and dispatches them through a persistent WebSocket tunnel to the registered client.
2. **Proxera Client** (separate repository) runs in the LAN. It connects outbound to the server, awaits request frames, performs local HTTP calls, and sends the response back.

No inbound ports need to be opened in the LAN. All connectivity is client-initiated.

## Features

- **WebSocket reverse tunnel** — persistent outbound connection from LAN client to cloud server
- **Multi-domain routing** — assign one or more public domains + optional path prefixes to any local service
- **Topology dashboard** — interactive live graph showing pods, connected clients, and active routes
- **Live request logs** — per-route scrolling access log streamed in real time
- **GitLab runner-style registration** — admin creates a named client slot and generates a one-time token
- **Horizontal scaling** — optional Redis Pub/Sub message bus for multi-pod deployments; in-memory fallback for single-pod (no Redis required)
- **Admin UI + REST API** — Bootstrap 5 admin panel on a separate port (8081) with Swagger UI
- **Prometheus metrics** — at `/actuator/prometheus`
- **Helm chart** — production-ready Kubernetes deployment with separate ingress objects for proxy and admin

## Quick Start

### Docker

```bash
docker run -d \
  --name proxera \
  -p 8080:8080 \
  ghcr.io/wenisch-tech/proxera:latest
```

Proxera starts with an **embedded H2 database** by default — no external services required.

## Configuration

Proxera is configured entirely through environment variables.

### Database

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | *(unset)* | PostgreSQL host. **When unset, embedded H2 is used.** |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `proxera` | PostgreSQL database name |
| `DB_USER` | `proxera` | PostgreSQL username |
| `DB_PASSWORD` | *(empty)* | PostgreSQL password |

**Example — switch to PostgreSQL:**
```bash
docker run -d \
  -p 8080:8080 \
  -e DB_HOST=postgres.example.com \
  -e DB_NAME=proxera \
  -e DB_USER=proxera \
  -e DB_PASSWORD=secret \
  ghcr.io/wenisch-tech/proxera:latest
```

> The H2 web console is available at `http://localhost:8080/h2-console` when running with the embedded database.

### Redis (optional — multi-pod scaling)

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | *(unset)* | Redis host. **When unset, the in-memory message bus is used.** |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password |

When `REDIS_HOST` is set, Proxera uses Redis Pub/Sub to route tunnel frames across multiple pods, enabling horizontal scaling.

### Other

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Port for all traffic (admin UI + reverse proxy + tunnel WebSocket) |


| URL | Purpose |
|-----|-------|
| http://localhost:8080/admin | Admin UI |
| http://localhost:8080 | Proxy port (receives forwarded traffic) |

Default credentials: **admin@proxera.local / admin** — change immediately after first login.

### Kubernetes with Helm

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

## Architecture

See [docs/architecture.md](docs/architecture.md) for the full conceptual design including:

- System overview and component breakdown
- WebSocket tunnel protocol specification
- Redis Pub/Sub scaling model
- Routing algorithm
- Security model (registration token flow)
- Admin UI topology graph
- Data model
- Helm chart topology

## Development

### Prerequisites

- Java 17+
- Maven 3.8+

### Run from source

No database setup needed — H2 starts automatically.

```bash
git clone https://github.com/wenisch-tech/proxera.git
cd proxera
./mvnw spring-boot:run
```

Admin UI: http://localhost:8080/admin  
Proxy port: http://localhost:8080

### Run tests

```bash
./mvnw test
```

### Build JAR

```bash
./mvnw package -DskipTests
java -jar target/proxera.jar
```

## Documentation

Full documentation is available at [proxera.wenisch.tech](https://proxera.wenisch.tech) (generated from [docs/](docs/)).

## License

Licensed under [AGPL v3.0](LICENSE) by [Jean-Fabian Wenisch](https://github.com/jfwenisch) / [wenisch.tech](https://wenisch.tech)
