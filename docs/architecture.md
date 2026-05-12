# Proxera — Architecture & Conceptual Design

## 1. Purpose & Overview

Proxera is a self-hosted reverse tunnel that lets HTTP services running in a private LAN be exposed to the internet without opening any inbound firewall rules. It is conceptually similar to Cloudflare Tunnel, implemented as a cloud-native Java / Spring Boot application deployable on Kubernetes.

The system consists of two components:

| Component | Location | Repository |
|-----------|----------|------------|
| **Proxera Server** | Kubernetes / Cloud | *This repository* |
| **Proxera Client** | LAN / on-premise | Separate repository (future) |

The server never dials into the LAN. All connectivity is initiated by the client outbound, which makes inbound firewall rules unnecessary.

---

## 2. High-Level System Diagram

```
                          ┌─────────────────────────────────────────────┐
                          │              Internet / Public Cloud          │
                          │                                               │
  Browser / API Client ───┼──► proxy.example.com (port 443)             │
                          │           │                                   │
                          │    ┌──────▼───────┐                          │
                          │    │ Ingress / LB │                          │
                          │    └──────┬───────┘                          │
                          │           │ :8080                            │
                          │    ┌──────▼───────────────┐                 │
                          │    │   Proxera Server      │                 │
                          │    │  ┌─────────────────┐  │                 │
  Admin Browser ──────────┼──► │  │ Proxy Engine    │  │  :8080          │
  admin.proxera.example   │    │  │ (route match,   │  │                 │
                          │    │  │  frame dispatch) │  │                 │
  .com (port 443)         │    │  └────────┬────────┘  │                 │
         │                │    │           │            │                 │
         │                │    │  ┌────────▼────────┐  │                 │
         └────────────────┼──► │  │ Admin UI / API  │  │  :8080          │
                          │    │  │ (Thymeleaf +    │  │                 │
                          │    │  │  Bootstrap 5)   │  │                 │
                          │    │  └─────────────────┘  │                 │
                          │    │                        │                 │
                          │    │  ┌─────────────────┐  │                 │
                          │    │  │  Pub/Sub Layer   │  │                 │
                          │    │  │ (In-Memory /     │  │                 │
                          │    │  │  Redis)          │  │                 │
                          │    │  └────────┬─────────┘  │                 │
                          │    └───────────┼────────────┘                 │
                          │               │ WebSocket /tunnel             │
                          └───────────────┼─────────────────────────────┘
                                          │  (outbound connection
                                          │   from LAN client)
                          ┌───────────────▼─────────────────────────────┐
                          │                  Private LAN                  │
                          │                                               │
                          │   ┌──────────────┐   ┌─────────────────────┐ │
                          │   │Proxera Client│──►│ 192.168.1.10:8080   │ │
                          │   │   (agent)    │   │ local-service-a     │ │
                          │   └──────────────┘   └─────────────────────┘ │
                          └──────────────────────────────────────────────┘
```

---

## 3. Component Architecture — Server

The server is a **single Spring Boot application** (Spring MVC on embedded Tomcat) deployed as one Docker image. It listens on two TCP ports:

| Port | Purpose |
|------|---------|
| **8080** | **Proxy Port** — Receives public HTTP requests. Matches routes and forwards frames through the WebSocket tunnel. Also hosts the client tunnel endpoint `/tunnel`. |
| **8080** | **Admin Port** — Admin UI (Thymeleaf + Bootstrap 5) and REST API. Intended to be behind an internal network policy or separate ingress with authentication. |

The secondary port is added as an additional Tomcat connector via a `WebServerFactoryCustomizer` bean. A `OncePerRequestFilter` enforces port-level access control (admin paths only on 8080, proxy paths only on 8080).

### 3.1 Internal Modules

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Tunnel Manager** | `tunnel` | Manages active `WebSocketSession` instances by `clientId`. Validates registration tokens during WebSocket handshake. |
| **Proxy Engine** | `proxy` | Receives HTTP requests on port 8080, resolves routes, serialises requests as frames, dispatches via the Pub/Sub layer, writes HTTP responses. Uses Spring MVC async (`DeferredResult`). |
| **Pub/Sub Layer** | `bus` | Abstracted `MessageBus` interface. Two implementations: `InMemoryMessageBus` (default, single-pod) and `RedisMessageBus` (multi-pod). Activated when `REDIS_HOST` is configured. |
| **Route Manager** | `service` | CRUD for routes and domains. Validates domain uniqueness. Maintains an in-memory route cache (invalidated on change). |
| **Auth & Security** | `config` | Spring Security: form login on port 8080, `X-API-KEY` filter for REST, registration token validation for WebSocket handshake. |
| **Access Log** | `service` | Persists `AccessLog` rows after each proxied request. Publishes SSE events to open admin log streams. Runs a daily cleanup job respecting the configured retention period. |
| **Admin SSE** | `sse` | `SseEmitter` endpoints for live topology events (`/admin/sse/topology`) and per-route request logs (`/admin/sse/routes/{id}/log`). |

### 3.2 Port Isolation Detail

```
  port 8080 ──► PortRoutingFilter ──► ProxyController (catch-all /**)
                                  └──► /tunnel ──► TunnelWebSocketHandler

  port 8080 ──► PortRoutingFilter ──► Spring Security form login
                                  └──► AdminControllers (/admin/**)
                                  └──► /login, /webjars/**, /css/**, /js/**
```

Any request arriving on the wrong port receives `404 Not Found`.

---

## 4. Component Architecture — Client

> The client is implemented in a **separate repository**. This section documents its responsibilities for complete system context.

The Proxera Client is a lightweight agent deployed within the LAN (Docker container, systemd service, or Kubernetes DaemonSet). It:

1. Reads configuration: server URL, registration token, list of local `host:port` targets per route.
2. Connects outbound to `wss://<proxy-domain>/tunnel` with header `X-Proxera-Token: <token>`.
3. On successful registration receives a `REGISTER_ACK` frame; client status is set to `CONNECTED` by the server.
4. Enters a receive loop: on each `REQUEST` frame it performs a local HTTP call to the configured `localHost:localPort`, then sends a `RESPONSE` frame with the same `correlationId`.
5. Sends a `PING` frame every 30 seconds; expects `PONG` within 10 seconds, otherwise reconnects.
6. On disconnect, reconnects with exponential backoff (initial 1 s, cap 60 s, ±30% jitter).

---

## 5. Transport Protocol — WebSocket Tunnel

All communication over the tunnel WebSocket uses **text frames containing JSON** (Phase 1). The client always initiates the WebSocket connection; subsequent communication is bidirectional.

### 5.1 Frame Envelope

```json
{
  "type": "REQUEST",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "payload": { ... }
}
```

### 5.2 Frame Types

| Type | Direction | Description |
|------|-----------|-------------|
| `REGISTER_ACK` | Server → Client | Registration confirmed. Payload: `{ "clientId": "...", "name": "..." }` |
| `REQUEST` | Server → Client | Proxy an HTTP request. See §5.3. |
| `RESPONSE` | Client → Server | Result of proxying. Same `correlationId` as the `REQUEST`. |
| `PING` | Either | Heartbeat. No payload. |
| `PONG` | Either | Heartbeat reply. Same `correlationId` as `PING`. |
| `ERROR` | Either | Frame processing error. Payload: `{ "code": "...", "message": "..." }` |

> **Note:** There is no explicit `REGISTER` frame. Registration is performed during the WebSocket handshake — the token is presented in the `X-Proxera-Token` HTTP header before the upgrade completes.

### 5.3 REQUEST / RESPONSE Payloads

**REQUEST payload:**
```json
{
  "method": "GET",
  "path": "/api/data",
  "queryString": "page=1&size=10",
  "headers": { "Accept": "application/json" },
  "body": "<base64-encoded body or null>",
  "localHost": "192.168.1.10",
  "localPort": 8080,
  "stripPrefix": "/api",
  "remoteAddress": "203.0.113.42"
}
```

**RESPONSE payload:**
```json
{
  "status": 200,
  "headers": { "Content-Type": "application/json" },
  "body": "<base64-encoded body or null>",
  "latencyMs": 42
}
```

### 5.4 Concurrency & Multiplexing

Multiple in-flight HTTP requests are multiplexed on a single WebSocket connection using `correlationId`. The server maintains a `ConcurrentHashMap<String, CompletableFuture<ResponsePayload>>` per session. When a `RESPONSE` frame arrives the matching future is completed, unblocking the HTTP thread (or async `DeferredResult`) waiting for the proxy response.

### 5.5 Phase 2: Binary Frame Protocol

> **Future work.** The Phase 1 JSON + Base64 encoding adds approximately 33% overhead for binary request/response bodies. A Phase 2 protocol update will introduce binary WebSocket frames: a compact binary header (1 byte frame type, 16 bytes correlationId, 4 bytes header-section length) followed by serialised headers and raw body bytes. JSON text frames will continue to be used for control messages (`PING`, `PONG`, `ERROR`).

---

## 6. Scalability — Pub/Sub Layer

### 6.1 Deployment Modes

| Mode | Activation | Behaviour |
|------|------------|-----------|
| **In-Memory** (default) | No Redis configured | All request dispatch happens within a single JVM using `ApplicationEventPublisher`. Suitable for single-replica deployments; no external dependency. |
| **Redis Pub/Sub** | `REDIS_HOST` environment variable set (or `redis.host` in Helm values) | Requests and responses are routed between pods via Redis channels. Enables true stateless horizontal scaling. |

### 6.2 Request Flow in Redis Mode

```
  Pod A                      Redis                      Pod B
   │  receives HTTP request    │                           │
   │                           │                           │
   │──publish──────────────────►  proxera:client:{id}:req  │
   │  subscribe to response    │                           │
   │                           │◄──subscribe───────────────│ (Pod B holds WS)
   │                           │──deliver──────────────────►│
   │                           │                           │──► WebSocket frame to client
   │                           │                           │◄── RESPONSE frame from client
   │                           │◄──publish──────────────────│
   │                           │  proxera:corr:{corrId}:resp│
   │◄──deliver─────────────────│                           │
   │  CompleatableFuture       │                           │
   │  completed                │                           │
   │──► write HTTP response    │                           │
```

### 6.3 Redis Channels

| Channel | Published by | Consumed by |
|---------|--------------|-------------|
| `proxera:client:{clientId}:req` | Any pod receiving a proxied HTTP request | The pod holding the client's WebSocket session |
| `proxera:corr:{correlationId}:resp` | The pod holding the WebSocket session | The pod that published the request |
| `proxera:topology` | Any pod on client connect/disconnect | All pods (for SSE topology fan-out) |

### 6.4 Client Presence Tracking

In Redis mode, client presence is stored as `proxera:presence:{clientId}` (hash: `podId`, `connectedAt`) with a TTL of 60 seconds. The holding pod refreshes the TTL every 30 seconds. On graceful pod shutdown all owned sessions are closed and presence keys deleted. On unclean shutdown, TTL expiry drives cleanup.

---

## 7. Routing Model

### 7.1 Route Definition

A **Route** is the core configuration entity that maps one or more public domain names (+ optional path prefix) to a local service reachable by the client.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `name` | String | Human-readable label |
| `clientId` | UUID FK → `clients` | The client agent responsible for this route |
| `localHost` | String | LAN hostname or IP the client forwards to |
| `localPort` | int | LAN port |
| `pathPrefix` | String | Optional path prefix to match (e.g. `/api`) |
| `stripPrefix` | boolean | If true, `pathPrefix` is stripped before forwarding |
| `enabled` | boolean | Allows disabling a route without deleting it |
| `domains` | `route_domains` | One or more external hostnames (unique constraint) |

### 7.2 Matching Algorithm

1. Extract the `Host` header from the inbound HTTP request (strip port if present, lowercase).
2. Look up `route_domains` by `domain = host` → retrieve `route_id`.
3. If `pathPrefix` is configured, verify `request.path.startsWith(pathPrefix)`. When multiple routes share a domain (different path prefixes), the **longest matching prefix wins**.
4. No match → `502 Bad Gateway`.
5. Matched client not connected → `503 Service Unavailable`.
6. Client connected → dispatch request frame.

### 7.3 Multiple Domains per Route

A single route may serve multiple domains (e.g. `api.example.com` and `api.example.org`). The `route_domains` table enforces a unique constraint on the `domain` column. The Admin UI validates uniqueness before saving.

---

## 8. Security Model

### 8.1 Admin Authentication

- **Form login** (Spring Security) on port 8080. Session-cookie based.
- **OIDC / OAuth2** (optional): any OpenID Connect provider (Keycloak, Auth0, etc.) configured via environment variables `OIDC_ENABLED`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`.
- **API Keys**: named keys for machine-to-machine REST API access, passed as `X-API-KEY: <key>` header. A `OncePerRequestFilter` validates the key (hashed comparison) before the Spring Security filter chain.

### 8.2 Client Registration (GitLab Runner Pattern)

```
  Admin UI              Proxera Server                    Proxera Client
      │                        │                                 │
      │── Create client slot ──►│                                 │
      │   { name: "home-lab" }  │                                 │
      │◄── Registration token ──│                                 │
      │    (shown once,         │                                 │
      │     stored hashed)      │                                 │
      │                        │                                 │
      │  (Admin copies token to client config file)              │
      │                        │                                 │
      │                        │◄── WS Upgrade ─────────────────│
      │                        │    X-Proxera-Token: <token>     │
      │                        │                                 │
      │                        │── validate token ──┐            │
      │                        │   mark used=true   │            │
      │                        │   status=CONNECTED │            │
      │                        │◄──────────────────┘            │
      │                        │                                 │
      │                        │── REGISTER_ACK ────────────────►│
      │                        │   { clientId, name }            │
```

**Token lifecycle:**
- Token is a cryptographically random 32-byte value (hex encoded, 64 chars), stored **BCrypt-hashed** in `registration_tokens`.
- Token is displayed **once** in the Admin UI after generation. It is never recoverable; a new token can always be generated (invalidating the previous).
- On first WebSocket connect: token is validated in the handshake interceptor, marked `used=true`, and the client status is set to `CONNECTED`. Subsequent reconnects by the same client use the same token (validated again each time) if the token is still valid and `used=true`.
- If a client is deleted, its registration tokens are cascade-deleted.

### 8.3 Port-Level Access Control

| Port | Internet-facing | Spring Security |
|------|----------------|----------------|
| 8080 | Yes (proxy domain) | No auth on proxied requests; `/tunnel` WebSocket validated by registration token in handshake |
| 8080 | Ideally restricted (admin domain) | Form login / OIDC required for all `/admin/**` paths |

---

## 9. Admin UI

### 9.1 Pages

| Page | URL | Description |
|------|-----|-------------|
| **Dashboard** | `/admin/` | Summary cards: connected clients, active routes, requests/min (last 60 s). Recent access log entries table. |
| **Topology** | `/admin/topology` | Interactive live node graph (D3.js via WebJar). Server pod nodes → client nodes → route nodes. Node colour encodes status (green = connected, grey = disconnected, red = error). Edges pulse on in-flight requests. SSE-driven live updates from `/admin/sse/topology`. |
| **Routes** | `/admin/routes` | Route list with status badge, domain/path, requests/min. Create / edit / delete. |
| **Route Detail** | `/admin/routes/{id}` | Route config; live traffic-rate sparkline (Chart.js); scrolling request log table streamed via SSE from `/admin/sse/routes/{id}/log`. |
| **Clients** | `/admin/clients` | Registered clients: name, status indicator, last seen, connected pod, assigned routes count. |
| **Client Detail** | `/admin/clients/{id}` | Client config; registration token management (generate, invalidate); list of assigned routes. |
| **API Keys** | `/admin/api-keys` | Generate / revoke named API keys for REST access. |
| **Users** | `/admin/users` | Create / update / delete local user accounts. |
| **Settings** | `/admin/settings` | Global config: access log retention days, proxy header options, OIDC settings. |

### 9.2 Frontend Libraries (all via WebJars — no CDN dependency)

| Library | WebJar artifact | Purpose |
|---------|----------------|---------|
| Bootstrap 5 | `org.webjars:bootstrap` | Layout, components, typography |
| Bootstrap Icons | `org.webjars.npm:bootstrap-icons` | Icon set |
| D3.js | `org.webjars.npm:d3` | Topology graph force simulation |
| Chart.js | `org.webjars.npm:chart.js` | Traffic-rate sparklines |

### 9.3 Live Data Endpoints (SSE)

| Endpoint | Port | Payload events | Used by |
|----------|------|---------------|---------|
| `GET /admin/sse/topology` | 8080 | `CLIENT_CONNECTED`, `CLIENT_DISCONNECTED`, `ROUTE_UPDATED`, `REQUEST_IN_FLIGHT`, `REQUEST_COMPLETED` | Topology page |
| `GET /admin/sse/routes/{id}/log` | 8080 | `AccessLogEntry` JSON objects | Route Detail page |
| `GET /admin/api/topology` | 8080 | Full topology snapshot (REST, used on initial page load) | Topology page |

### 9.4 Topology Graph Design

```
  [Pod: proxera-7d9f8b-xkz4]          colour: blue rectangle
          │
          ├──── [Client: home-lab]     colour: green circle (connected)
          │           │
          │           ├── [Route: api.example.com/api]   diamond
          │           └── [Route: files.example.com]     diamond
          │
          └──── [Client: office-net]  colour: grey circle (disconnected)
                      │
                      └── [Route: office.example.com]    diamond
```

Edges pulse (CSS animation) when a `REQUEST_IN_FLIGHT` event matches that route.

---

## 10. Data Model

### 10.1 Entity Relationship (abbreviated)

```
users ──────────────────────────────────────────────────────┐
  id UUID PK                                                 │ (managed by)
  username VARCHAR UNIQUE                                    │
  password_hash VARCHAR                                      │
  role VARCHAR                                               │
  created_at TIMESTAMP                                       │
                                                             │
clients ────────────────────────────────────────────────────┤
  id UUID PK                                                 │
  name VARCHAR UNIQUE                                        │
  status VARCHAR (PENDING|REGISTERED|CONNECTED|DISCONNECTED) │
  connected_pod_id VARCHAR                                   │
  last_seen_at TIMESTAMP                                     │
  created_at TIMESTAMP                                       │
       │                                                     │
       ├──► registration_tokens                              │
       │      id UUID PK                                     │
       │      client_id UUID FK→clients                      │
       │      token_hash VARCHAR                             │
       │      used BOOLEAN                                   │
       │      created_at TIMESTAMP                           │
       │      used_at TIMESTAMP                              │
       │                                                     │
       └──► routes                                           │
              id UUID PK                                     │
              name VARCHAR                                   │
              client_id UUID FK→clients                      │
              local_host VARCHAR                             │
              local_port INT                                 │
              path_prefix VARCHAR                            │
              strip_prefix BOOLEAN                           │
              enabled BOOLEAN                                │
              created_at TIMESTAMP                           │
              updated_at TIMESTAMP                           │
                    │
                    ├──► route_domains
                    │      id UUID PK
                    │      route_id UUID FK→routes
                    │      domain VARCHAR UNIQUE
                    │
                    └──► access_log
                           id BIGINT IDENTITY PK
                           route_id UUID FK→routes (nullable on delete)
                           client_id UUID FK→clients (nullable on delete)
                           timestamp TIMESTAMP
                           method VARCHAR
                           path TEXT
                           status_code INT
                           latency_ms BIGINT
                           remote_ip VARCHAR
                           INDEX (route_id, timestamp DESC)
                           INDEX (timestamp DESC)

api_keys (standalone)
  id UUID PK
  name VARCHAR
  key_hash VARCHAR
  created_at TIMESTAMP
  last_used_at TIMESTAMP
  revoked BOOLEAN
```

### 10.2 Access Log Retention

A `@Scheduled` task runs daily and deletes `access_log` rows older than `proxera.log.retention-days` (default: 7). This value is configurable via the Settings page without restart.

---

## 11. Proxy Header Handling

The Proxy Engine adds the following headers to every forwarded request before sending the `REQUEST` frame to the client:

| Header | Value |
|--------|-------|
| `X-Forwarded-For` | Original client IP (appended if already present) |
| `X-Forwarded-Proto` | `https` or `http` |
| `X-Forwarded-Host` | Original `Host` header value |
| `X-Real-IP` | Original client IP (first value only) |
| `Via` | `proxera/1.0` |

**Hop-by-hop headers** are stripped before forwarding (`Connection`, `Transfer-Encoding`, `Upgrade`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailers`).

**Path prefix stripping**: if `route.stripPrefix=true` and `route.pathPrefix=/api`, a request to `/api/v1/data` is forwarded to the local service as `/v1/data`.

---

## 12. Helm Chart Topology

```
Kubernetes Namespace: proxera
│
├── Deployment: proxera
│     └── Container: proxera
│           ├── containerPort: 8080 (proxy)
│           ├── containerPort: 8080 (admin)
│           ├── Liveness probe:   GET /actuator/health        (port 8080)
│           └── Readiness probe:  GET /actuator/health/readiness (port 8080)
│
├── Service: proxera
│     ├── port 8080 → containerPort 8080 (proxy)
│     └── port 8080 → containerPort 8080 (admin)
│
├── Ingress: proxera-proxy
│     └── host: proxy.example.com → Service:8080
│
├── Ingress: proxera-admin
│     └── host: admin.proxera.example.com → Service:8080
│
├── ConfigMap: proxera   (non-sensitive env vars)
├── Secret: proxera      (sensitive env vars, e.g. DB password)
└── PersistentVolumeClaim: proxera-data  (optional, for H2 dev mode)
```

### 12.1 Key Helm Values

```yaml
image:
  registry: ghcr.io
  repository: wenisch-tech/proxera
  tag: latest

service:
  proxy:
    port: 8080
  admin:
    port: 8080

ingress:
  proxy:
    enabled: false
    className: nginx
    hosts: []
    tls: []
  admin:
    enabled: false
    className: nginx
    hosts: []
    tls: []

redis:
  enabled: false   # true = Redis Pub/Sub mode (multi-pod)
  host: ""
  port: 6379

persistence:
  enabled: false
  size: 1Gi

resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi
```

---

## 13. Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | Java 25 (LTS) | Latest LTS; records, pattern matching, virtual threads (Project Loom) |
| Framework | Spring Boot 3.5.x | Mature ecosystem, WebSocket support, Security, Data JPA, Actuator |
| Web layer | Spring MVC (Tomcat) | Two-port via additional connector; WebSocket via `@EnableWebSocket` |
| Async | Spring MVC `DeferredResult` | Non-blocking HTTP while awaiting tunnel responses |
| UI | Thymeleaf + Bootstrap 5 | Server-side rendering; no separate frontend build step |
| Live updates | Server-Sent Events (SSE) | One-directional push for topology and log streams |
| Topology graph | D3.js (force simulation) | Rich interactive graph; delivered as WebJar |
| Charts | Chart.js | Sparklines for traffic rates; delivered as WebJar |
| Database | PostgreSQL (prod) / H2 (dev) | H2 with `MODE=PostgreSQL` for local dev without Docker |
| Migrations | Flyway | Version-controlled schema evolution |
| Pub/Sub | In-memory (default) / Redis | Progressive scaling: start without Redis, add when going multi-pod |
| Security | Spring Security | Form login, OIDC, API key filter |
| Metrics | Micrometer + Prometheus | Exposed at `/actuator/prometheus` |
| API docs | springdoc-openapi | Swagger UI at `/api` |
| Container | Chainguard JRE (distroless) | Minimal attack surface |
| Orchestration | Helm 3 | Kubernetes deployment with values-driven config |
| CI/CD | GitHub Actions | Follows Kairos workflow pattern |

---

## 14. CI/CD Workflow

Follows the same orchestrator + reusable-workflow pattern as the Kairos project:

```
ci.yml (orchestrator, triggers on push/PR/workflow_dispatch)
  │
  ├── versioning job: auto-tag (mathieudutour/github-tag-action), derive version
  │
  ├── _test.yml (reusable)
  │     └── Java 25 / Maven compile + test + JAR build
  │
  ├── _docker.yml (reusable, on PR + main push)
  │     ├── Build JAR
  │     ├── Build Docker image (both ports 8080+8080)
  │     ├── Container endpoint check (proxy health + admin health)
  │     ├── Trivy vulnerability scan
  │     ├── Push to ghcr.io/wenisch-tech/proxera (on main push)
  │     └── Cosign keyless sign + SLSA provenance attestation
  │
  └── _release.yml (reusable, on main push only)
        ├── Update Helm chart version (Chart.yaml, values.yaml)
        ├── Generate Helm values schema (helm-schema-gen)
        ├── Update CHANGELOG.md
        ├── Generate CycloneDX SBOM
        ├── Sign Helm chart (Cosign)
        ├── Publish Helm chart to charts repository
        └── Create GitHub Release (chart .tgz, SBOM, attestation bundle)
```

---

## 15. Future Work

| Item | Priority | Description |
|------|----------|-------------|
| **Binary frame protocol** | High | Replace JSON+Base64 for request/response bodies with binary WebSocket frames (~33% payload reduction) |
| **WebSocket proxying** | High | Forward WebSocket upgrade requests through the tunnel (HTTP/1.1 only in Phase 1) |
| **mTLS for tunnel** | Medium | Mutual TLS on `/tunnel` endpoint for hardware-bound client authentication |
| **Rate limiting per route** | Medium | Configurable per-route request rate limits with burst allowance |
| **Multi-client load balancing** | Low | A route pointing to multiple clients with round-robin or least-connections |
| **Proxera Client Helm chart** | Low | Helm chart for deploying the client agent within a Kubernetes LAN |
| **HTTP/2 proxying** | Low | Full HTTP/2 support in both the proxy engine and tunnel protocol |
