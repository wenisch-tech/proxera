# Proxera — Architecture & Conceptual Design

## 1. Purpose & Overview

Proxera is a self-hosted reverse tunnel that lets HTTP services running in a private LAN be exposed to the internet without opening any inbound firewall rules. It is conceptually similar to Cloudflare Tunnel, implemented as a cloud-native Java / Spring Boot application deployable on Kubernetes.

The system consists of two components:

| Component | Location | Repository |
|-----------|----------|------------|
| **Proxera Server** | Kubernetes / Cloud | *This repository* |
| **Proxera Agent** | LAN / on-premise | [wenisch-tech/proxera-agent](https://github.com/wenisch-tech/proxera-agent) |

The server never dials into the LAN. All connectivity is initiated by the agent outbound, which makes inbound firewall rules unnecessary.

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
                                          │   from LAN agent)
                          ┌───────────────▼─────────────────────────────┐
                          │                  Private LAN                  │
                          │                                               │
                          │   ┌──────────────┐   ┌─────────────────────┐ │
                          │   │Proxera Agent │──►│ 192.168.1.10:8080   │ │
                          │   │   (agent)    │   │ local-service-a     │ │
                          │   └──────────────┘   └─────────────────────┘ │
                          └──────────────────────────────────────────────┘
```

---

## 3. Component Architecture — Server

The server is a **single Spring Boot application** (Spring MVC on embedded Tomcat) deployed as one Docker image. It listens on a **single TCP port** (default `8080`) and serves all traffic on that port:

| Traffic | Path pattern | Notes |
|---------|-------------|-------|
| **Proxy** | `/**` (catch-all) | Receives public HTTP requests, matches routes, forwards frames through the WebSocket tunnel |
| **Tunnel** | `/tunnel` | WebSocket endpoint for agent connections; validated by registration token during handshake |
| **Admin UI / API** | `/admin/**`, `/login`, `/logout` | Thymeleaf + Bootstrap 5 admin panel and REST API; protected by Spring Security form login |

Path-based access control is enforced by Spring Security filter chains. The Helm chart exposes the same port via two separate `Ingress` objects — one for the public proxy domain and one for the (ideally internal) admin domain — keeping the routing concern at the ingress layer.

### 3.1 Internal Modules

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Tunnel Manager** | `tunnel` | Manages active `WebSocketSession` instances by `agentId`. Validates registration tokens during WebSocket handshake. |
| **Proxy Engine** | `proxy` | Receives HTTP requests on port 8080, resolves routes, serialises requests as frames, dispatches via the Pub/Sub layer, writes HTTP responses. Uses Spring MVC async (`DeferredResult`). |
| **Pub/Sub Layer** | `bus` | Abstracted `MessageBus` interface. Two implementations: `InMemoryMessageBus` (default, single-pod) and `RedisMessageBus` (multi-pod). Activated when `REDIS_HOST` is configured. |
| **Route Manager** | `service` | CRUD for routes and domains. Validates domain uniqueness. Maintains an in-memory route cache (invalidated on change). |
| **Auth & Security** | `config` | Spring Security: form login on port 8080, `X-API-KEY` filter for REST, registration token validation for WebSocket handshake. |
| **Access Log** | `service` | Persists `AccessLog` rows after each proxied request. Publishes SSE events to open admin log streams. Runs a daily cleanup job respecting the configured retention period. |
| **Admin SSE** | `sse` | `SseEmitter` endpoints for live topology events (`/admin/sse/topology`) and per-route request logs (`/admin/sse/routes/{id}/log`). |

### 3.2 Path-Based Access Control

```
  :8080 ──► Spring Security filter chain
            ├──► /tunnel                   ──► TunnelWebSocketHandler (token validation)
            ├──► /admin/**, /login, /logout ──► Form login required
            ├──► /**                        ──► ProxyController (catch-all, no auth)
            └──► /webjars/**, /css/**, /js/**,
                 /actuator/**, /v3/api-docs,
                 /swagger-ui/**            ──► Public (shared)
```

Admin paths are secured by Spring Security. Proxy catch-all requests pass through unauthenticated. The two domains (proxy and admin) are separated at the Ingress level — both backed by the same Kubernetes `Service` on port 8080.

---

## 4. Component Architecture — Agent

> The agent is implemented in a **separate repository**: [wenisch-tech/proxera-agent](https://github.com/wenisch-tech/proxera-agent). This section documents its responsibilities for complete system context.

The Proxera Agent is a lightweight agent deployed within the LAN (Docker container, systemd service, or Kubernetes DaemonSet). It:

1. Reads configuration: server URL, registration token, list of local `host:port` targets per route.
2. Connects outbound to `wss://<proxy-domain>/tunnel` with header `X-Proxera-Token: <token>`.
3. On successful registration receives a `REGISTER_ACK` frame; agent status is set to `CONNECTED` by the server.
4. Enters a receive loop: on each `REQUEST` frame it performs a local HTTP call to the configured `localHost:localPort`, then sends a `RESPONSE` frame with the same `correlationId`.
5. Sends a `PING` frame every 30 seconds; expects `PONG` within 10 seconds, otherwise reconnects.
6. On disconnect, reconnects with exponential backoff (initial 1 s, cap 60 s, ±30% jitter).

---

## 5. Transport Protocol — WebSocket Tunnel

All communication over the tunnel WebSocket uses **text frames containing JSON** (Phase 1). The agent always initiates the WebSocket connection; subsequent communication is bidirectional.

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
| `REGISTER_ACK` | Server → Agent | Registration confirmed. Payload: `{ "agentId": "...", "name": "..." }` |
| `REQUEST` | Server → Agent | Proxy an HTTP request. See §5.3. |
| `RESPONSE` | Agent → Server | Result of proxying. Same `correlationId` as the `REQUEST`. |
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
  "headers": { "accept": ["application/json"] },
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
  "headers": {
    "content-type": ["application/json"],
    "set-cookie": [
      "session=abc; Path=/; HttpOnly",
      "session_expiry=1748...; Path=/"
    ]
  },
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
   │──publish──────────────────►  proxera:agent:{id}:req  │
   │  subscribe to response    │                           │
   │                           │◄──subscribe───────────────│ (Pod B holds WS)
   │                           │──deliver──────────────────►│
   │                           │                           │──► WebSocket frame to client
   │                           │                           │◄── RESPONSE frame from agent
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
| `proxera:agent:{agentId}:req` | Any pod receiving a proxied HTTP request | The pod holding the agent's WebSocket session |
| `proxera:corr:{correlationId}:resp` | The pod holding the WebSocket session | The pod that published the request |
| `proxera:topology` | Any pod on agent connect/disconnect | All pods (for SSE topology fan-out) |

### 6.4 Agent Presence Tracking

In Redis mode, agent presence is stored as `proxera:presence:{agentId}` (hash: `podId`, `connectedAt`) with a TTL of 60 seconds. The holding pod refreshes the TTL every 30 seconds. On graceful pod shutdown all owned sessions are closed and presence keys deleted. On unclean shutdown, TTL expiry drives cleanup.

---

## 7. Routing Model

### 7.1 Route Definition

A **Route** is the core configuration entity that maps one or more public domain names (+ optional path prefix) to a local service reachable by the agent.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `name` | String | Human-readable label |
| `agentId` | UUID FK → `agents` | The agent responsible for this route |
| `localHost` | String | LAN hostname or IP the agent forwards to |
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
5. Matched agent not connected → `503 Service Unavailable`.
6. Agent connected → dispatch request frame.

### 7.3 Multiple Domains per Route

A single route may serve multiple domains (e.g. `api.example.com` and `api.example.org`). The `route_domains` table enforces a unique constraint on the `domain` column. The Admin UI validates uniqueness before saving.

---

## 8. Security Model

### 8.1 Admin Authentication

- **Form login** (Spring Security) on port 8080. Session-cookie based.
- **OIDC / OAuth2** (optional): any OpenID Connect provider (Keycloak, Auth0, etc.) configured via environment variables `OIDC_ENABLED`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`.
- **API Keys**: named keys for machine-to-machine REST API access, passed as `X-API-KEY: <key>` header. A `OncePerRequestFilter` validates the key (hashed comparison) before the Spring Security filter chain.

### 8.2 Agent Registration (GitLab Runner Pattern)

```
  Admin UI              Proxera Server                    Proxera Agent
      │                        │                                 │
      │── Create agent slot ───►│                                 │
      │   { name: "home-lab" }  │                                 │
      │◄── Registration token ──│                                 │
      │    (shown once,         │                                 │
      │     stored hashed)      │                                 │
      │                        │                                 │
      │  (Admin copies token to agent config file)               │
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
      │                        │   { agentId, name }             │
```

**Token lifecycle:**
- Token is a cryptographically random 32-byte value (hex encoded, 64 chars), stored **BCrypt-hashed** in `registration_tokens`.
- Token is displayed **once** in the Admin UI after generation. It is never recoverable; a new token can always be generated (invalidating the previous).
- On first WebSocket connect: token is validated in the handshake interceptor, marked `used=true`, and the agent status is set to `CONNECTED`. Subsequent reconnects by the same agent use the same token (validated again each time) if the token is still valid and `used=true`.
- If an agent is deleted, its registration tokens are cascade-deleted.

### 8.3 Path-Level Access Control

All traffic arrives on a single port (`:8080`). Separation between the public proxy domain and the admin domain is enforced at the Kubernetes Ingress level; within the application, Spring Security filter chains control path access:

| Path pattern | Auth required | Notes |
|---|---|---|
| `/**` (catch-all proxy) | None | Public HTTP traffic forwarded to agents |
| `/tunnel` | Registration token (WebSocket header) | Agent WebSocket connections only |
| `/admin/**`, `/login`, `/logout` | Form login / OIDC | Admin UI and REST API |

---

## 9. Admin UI

### 9.1 Pages

| Page | URL | Description |
|------|-----|-------------|
| **Dashboard** | `/admin/` | Summary cards: connected agents, active routes, requests/min (last 60 s). Recent access log entries table. |
| **Topology** | `/admin/topology` | Interactive live node graph (D3.js via WebJar). Server pod nodes → agent nodes → route nodes. Node colour encodes status (green = connected, grey = disconnected, red = error). Edges pulse on in-flight requests. SSE-driven live updates from `/admin/sse/topology`. |
| **Routes** | `/admin/routes` | Route list with status badge, domain/path, requests/min. Create / edit / delete. |
| **Route Detail** | `/admin/routes/{id}` | Route config; live traffic-rate sparkline (Chart.js); scrolling request log table streamed via SSE from `/admin/sse/routes/{id}/log`. |
| **Agents** | `/admin/agents` | Registered agents: name, status indicator, last seen, connected pod, assigned routes count. |
| **Agent Detail** | `/admin/agents/{id}` | Agent config; registration token management (generate, invalidate); list of assigned routes. |
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
| `GET /admin/sse/topology` | 8080 | `AGENT_CONNECTED`, `AGENT_DISCONNECTED`, `ROUTE_UPDATED`, `REQUEST_IN_FLIGHT`, `REQUEST_COMPLETED` | Topology page |
| `GET /admin/sse/routes/{id}/log` | 8080 | `AccessLogEntry` JSON objects | Route Detail page |
| `GET /admin/api/topology` | 8080 | Full topology snapshot (REST, used on initial page load) | Topology page |

### 9.4 Topology Graph Design

```
  [Pod: proxera-7d9f8b-xkz4]          colour: blue rectangle
          │
          ├──── [Agent: home-lab]      colour: green circle (connected)
          │           │
          │           ├── [Route: api.example.com/api]   diamond
          │           └── [Route: files.example.com]     diamond
          │
          └──── [Agent: office-net]   colour: grey circle (disconnected)
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
agents ────────────────────────────────────────────────────┤
  id UUID PK                                                 │
  name VARCHAR UNIQUE                                        │
  status VARCHAR (PENDING|REGISTERED|CONNECTED|DISCONNECTED) │
  connected_pod_id VARCHAR                                   │
  last_seen_at TIMESTAMP                                     │
  created_at TIMESTAMP                                       │
       │                                                     │
       ├──► registration_tokens                              │
       │      id UUID PK                                     │
       │      agent_id UUID FK→agents                        │
       │      token_hash VARCHAR                             │
       │      used BOOLEAN                                   │
       │      created_at TIMESTAMP                           │
       │      used_at TIMESTAMP                              │
       │                                                     │
       └──► routes                                           │
              id UUID PK                                     │
              name VARCHAR                                   │
              agent_id UUID FK→agents                        │
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
                           agent_id UUID FK→agents (nullable on delete)
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

The Proxy Engine adds the following headers to every forwarded request before sending the `REQUEST` frame to the agent:

| Header | Value |
|--------|-------|
| `X-Forwarded-For` | Client IP appended to any existing chain (multi-hop safe) |
| `X-Forwarded-Proto` | `https` or `http` (scheme as seen by Proxera, honoring upstream `X-Forwarded-Proto` via Tomcat RemoteIpValve) |
| `X-Forwarded-Host` | Full `Host` header including port (e.g. `ha.example.com:8123`) |
| `X-Forwarded-Port` | Server port number (required by HA OIDC/OAuth to construct redirect URIs) |
| `X-Real-IP` | Original client IP (first value only) |

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
├── Deployment: proxera-redis  (optional, when redis.enabled=true)
├── Service: proxera-redis     (optional, when redis.enabled=true)
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
  enabled: false   # true = deploy bundled Redis + enable Redis Pub/Sub
  host: ""         # set only when using an external Redis service
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

## 13. WebSocket Proxy Protocol

Proxera transparently forwards WebSocket upgrades from public clients through the tunnel to the local service running in the agent's LAN. The feature works in both **single-pod** (in-memory) and **multi-pod** (Redis Pub/Sub) deployments.

### 13.1 Frame Types

Five new frame types are added to the tunnel protocol:

| Frame | Direction | `correlationId` | Payload fields |
|-------|-----------|-----------------|----------------|
| `WS_OPEN` | Server → Agent | `wsSessionId` | `wsSessionId`, `localHost`, `localPort`, `path`, `queryString`, `headers` |
| `WS_OPEN_ACK` | Agent → Server | `wsSessionId` | _(empty)_ |
| `WS_OPEN_REJECT` | Agent → Server | `wsSessionId` | `code` (int), `reason` (string) |
| `WS_DATA` | Bidirectional | — | `wsSessionId`, `data` (Base64), `binary` (bool) |
| `WS_CLOSE` | Bidirectional | — | `wsSessionId`, `code` (int), `reason` (string) |

> **Note**: For `WS_OPEN` / `WS_OPEN_ACK` / `WS_OPEN_REJECT` the `correlationId` field **is** the `wsSessionId`, matching the existing `REQUEST`/`RESPONSE` pattern.
> For `WS_DATA` and `WS_CLOSE` the `wsSessionId` is a top-level payload field (correlationId is not used).

### 13.2 End-to-End Sequence

```
Client             Proxera (ProxyController)        WsProxyRegistry          Agent
  │                        │                              │                    │
  │── HTTP Upgrade ────────►│                              │                    │
  │                        │── doHandshake() ────────────►│                    │
  │◄── 101 Switching ──────│  (WebSocket established)     │                    │
  │                        │── registerClientSession() ──►│                    │
  │                        │── sendToAgent(WS_OPEN) ──────────────────────────►│
  │                        │                              │                    │── dial local WS
  │                        │                              │◄── WS_OPEN_ACK ───│
  │                        │◄── registerAgentSession() ──│                    │
  │                        │◄── publishAgentOpenAck() ───│                    │
  │  (connection ready)    │                              │                    │
  │                        │                              │                    │
  │── WS text/binary ──────►│                              │                    │
  │                        │── publishFromClient(DATA) ──►│── WS_DATA ────────►│
  │                        │                              │                    │── forward to local WS
  │                        │                              │◄── WS_DATA ───────│
  │◄── WS text/binary ─────│◄── publishFromAgent(DATA) ──│                    │
  │                        │                              │                    │
  │── close ───────────────►│                              │                    │
  │                        │── publishClientClose() ──────►│── WS_CLOSE ───────►│
  │                        │                              │                    │── close local WS
```

### 13.3 Multi-Pod Redis Flow

When multiple Proxera pods are running with Redis enabled, the client and the agent may be connected to **different pods**. The `WsRelayBus` abstraction handles this transparently:

```
  Pod A (client connected)            Redis              Pod B (agent connected)
         │                              │                        │
         │── publishC2A(DATA) ──────── ►│── proxera:ws:<id>:c2a─►│
         │                              │                        │── WS_DATA frame ──► Agent
         │                              │                        │
  Agent ─►│── WS_DATA frame ────────────│─────────────────────── ►│
         │◄─ proxera:ws:<id>:a2c ───── ─│◄── publishA2C(DATA) ───│
         │── send to client             │                        │
```

### 13.4 Redis Channel Reference

| Channel | Publisher | Subscriber | Content |
|---------|-----------|-----------|---------|
| `proxera:agent:<agentId>` | Any pod | Agent's pod (via `onAgentConnected`) | `TunnelFrame` JSON |
| `proxera:corr:<correlationId>` | Agent's pod | Requesting pod | `ResponsePayload` JSON |
| `proxera:ws:<wsSessionId>:a2c` | Agent's pod | Client's pod | `WsRelayMessage` JSON |
| `proxera:ws:<wsSessionId>:c2a` | Client's pod | Agent's pod | `WsRelayMessage` JSON |
| `proxera:topology` | Any pod | All pods | `TopologyEvent` JSON |

### 13.5 Go Agent Implementation Guide

This section describes how to implement WebSocket proxying in the Proxera Agent (Go).

#### Data Structures

```go
// Track one proxied WebSocket connection to a local service
type wsSession struct {
    conn   *websocket.Conn // local WS connection
    sendCh chan wsOutbound  // buffered channel to local WS
}

type wsOutbound struct {
    data   []byte
    binary bool
    close  bool
    code   int
    reason string
}

var wsSessions sync.Map // wsSessionId (string) → *wsSession
```

#### Handling `WS_OPEN`

```go
func handleWsOpen(frame TunnelFrame, tunnelConn *websocket.Conn) {
    payload := frame.Payload
    wsSessionId := payload["wsSessionId"].(string)
    localHost := payload["localHost"].(string)
    localPort := int(payload["localPort"].(float64))
    path := payload["path"].(string)
    query := payload["queryString"].(string)
    headers := buildHeaders(payload["headers"].(map[string]interface{}))

    targetURL := fmt.Sprintf("ws://%s:%d%s", localHost, localPort, path)
    if query != "" {
        targetURL += "?" + query
    }

    localConn, resp, err := websocket.DefaultDialer.Dial(targetURL, headers)
    if err != nil {
        code := 1011
        if resp != nil {
            code = resp.StatusCode
        }
        sendFrame(tunnelConn, TunnelFrame{
            Type:          "WS_OPEN_REJECT",
            CorrelationId: wsSessionId,
            Payload:       map[string]any{"code": code, "reason": err.Error()},
        })
        return
    }

    session := &wsSession{
        conn:   localConn,
        sendCh: make(chan wsOutbound, 64),
    }
    wsSessions.Store(wsSessionId, session)

    // Acknowledge the open
    sendFrame(tunnelConn, TunnelFrame{
        Type:          "WS_OPEN_ACK",
        CorrelationId: wsSessionId,
        Payload:       map[string]any{},
    })

    // Goroutine: local WS → tunnel (agent-to-cloud direction)
    go func() {
        defer wsSessions.Delete(wsSessionId)
        defer localConn.Close()
        for {
            msgType, data, err := localConn.ReadMessage()
            if err != nil {
                code, reason := websocket.CloseNormalClosure, ""
                if ce, ok := err.(*websocket.CloseError); ok {
                    code, reason = ce.Code, ce.Text
                }
                sendFrame(tunnelConn, TunnelFrame{
                    Type:    "WS_CLOSE",
                    Payload: map[string]any{"wsSessionId": wsSessionId, "code": code, "reason": reason},
                })
                return
            }
            binary := msgType == websocket.BinaryMessage
            sendFrame(tunnelConn, TunnelFrame{
                Type:    "WS_DATA",
                Payload: map[string]any{
                    "wsSessionId": wsSessionId,
                    "data":        base64.StdEncoding.EncodeToString(data),
                    "binary":      binary,
                },
            })
        }
    }()

    // Goroutine: sendCh → local WS (cloud-to-agent direction)
    go func() {
        for out := range session.sendCh {
            if out.close {
                localConn.WriteMessage(websocket.CloseMessage,
                    websocket.FormatCloseMessage(out.code, out.reason))
                localConn.Close()
                return
            }
            msgType := websocket.TextMessage
            if out.binary {
                msgType = websocket.BinaryMessage
            }
            if err := localConn.WriteMessage(msgType, out.data); err != nil {
                return
            }
        }
    }()
}
```

#### Handling `WS_DATA` (cloud → local)

```go
case "WS_DATA":
    wsSessionId := frame.Payload["wsSessionId"].(string)
    dataB64 := frame.Payload["data"].(string)
    binary := frame.Payload["binary"].(bool)
    data, _ := base64.StdEncoding.DecodeString(dataB64)
    if v, ok := wsSessions.Load(wsSessionId); ok {
        v.(*wsSession).sendCh <- wsOutbound{data: data, binary: binary}
    }
```

#### Handling `WS_CLOSE` (cloud → local)

```go
case "WS_CLOSE":
    wsSessionId := frame.Payload["wsSessionId"].(string)
    code := int(frame.Payload["code"].(float64))
    reason, _ := frame.Payload["reason"].(string)
    if v, ok := wsSessions.LoadAndDelete(wsSessionId); ok {
        v.(*wsSession).sendCh <- wsOutbound{close: true, code: code, reason: reason}
    }
```

#### Header Forwarding (`buildHeaders`)

The `WS_OPEN` payload includes all non-hop-by-hop headers already set by Proxera (including `X-Forwarded-*`). The agent should forward them to the local service, skipping WebSocket handshake headers that the dialer manages itself:

```go
var wsHandshakeHeaders = map[string]bool{
    "upgrade":              true,
    "connection":           true,
    "sec-websocket-key":    true,
    "sec-websocket-version": true,
    "sec-websocket-extensions": true,
}

func buildHeaders(raw map[string]interface{}) http.Header {
    h := http.Header{}
    for k, v := range raw {
        lower := strings.ToLower(k)
        if wsHandshakeHeaders[lower] {
            continue
        }
        h.Set(k, fmt.Sprintf("%v", v))
    }
    return h
}
```

---

## 14. Technology Stack

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

## 15. CI/CD Workflow

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

## 16. Future Work

| Item | Priority | Description |
|------|----------|-------------|
| **Binary frame protocol** | High | Replace JSON+Base64 for request/response bodies with binary WebSocket frames (~33% payload reduction) |
| **WebSocket proxying** | ~~High~~ | ~~Forward WebSocket upgrade requests through the tunnel~~ **Done** — see Section 13 |
| **mTLS for tunnel** | Medium | Mutual TLS on `/tunnel` endpoint for hardware-bound agent authentication |
| **Rate limiting per route** | Medium | Configurable per-route request rate limits with burst allowance |
| **Multi-agent load balancing** | Low | A route pointing to multiple agents with round-robin or least-connections |
| **Proxera Agent Helm chart** | Low | Helm chart for deploying the agent within a Kubernetes LAN |
| **HTTP/2 proxying** | Low | Full HTTP/2 support in both the proxy engine and tunnel protocol |

---

## 17. Protocol Changelog

### v0.3 — Multi-Value Header Support & Redirect Transparency

**Released:** May 2026  
**Affects:** `proxera` (server) and `proxera-agent` (agent) — must be deployed together.

#### Background

Two bugs were identified when proxying applications that rely on HTTP redirects carrying `Set-Cookie` response headers (e.g. Grafana, Authentik, most form-login flows):

1. **Agent silently followed redirects.** Go's `http.Client` defaults to following up to 10 redirects automatically. When the backend returned a `302 Found` with session cookies after a login POST, the agent consumed the redirect internally and returned the final `200 OK` body. The browser never saw the `302`, never updated the URL, and never received the cookies — resulting in an authentication loop.

2. **Protocol dropped duplicate headers.** Both the wire format (`map[string]string` / `Map<String, String>`) and the agent collection code (`values[0]`) could only represent a single value per header name. Applications commonly set multiple `Set-Cookie` headers in a single response (e.g. `grafana_session` + `grafana_session_expiry`); all but the first were silently discarded.

#### Changes

**Agent (`proxera-agent` / `proxera-client`)**

| File | Change |
|------|--------|
| `internal/proxy/proxy.go` | Added `CheckRedirect: func(...) error { return http.ErrUseLastResponse }` to `http.Client` — agent now returns redirect responses as-is without following them. |
| `internal/proxy/proxy.go` | Response header collection changed from `responseHeaders[k] = values[0]` to `responseHeaders[k] = values` — all values for each header name are now preserved. |
| `internal/proxy/proxy.go` | Request header forwarding loop changed from `Header.Set` to `Header.Add` per value — all incoming header values are forwarded to the local service. |
| `internal/protocol/frame.go` | `RequestPayload.Headers` and `ResponsePayload.Headers` changed from `map[string]string` to `map[string][]string`. |

**Server (`proxera`)**

| File | Change |
|------|--------|
| `tunnel/RequestPayload.java` | `headers` field changed from `Map<String, String>` to `Map<String, List<String>>`. |
| `tunnel/ResponsePayload.java` | `headers` field changed from `Map<String, String>` to `Map<String, List<String>>`. |
| `proxy/ProxyService.java` | `buildPayload()`: uses `Collections.list(request.getHeaders(name))` to collect all request header values; synthesised `X-Forwarded-*` headers wrapped in `List.of(...)`. |
| `proxy/ProxyService.java` | `writeResponse()`: iterates over each value in the response header list and calls `response.addHeader(name, value)` — all `Set-Cookie` (and other multi-value) headers are now forwarded to the browser. |

#### Wire Format

The `headers` field in `REQUEST` and `RESPONSE` payloads changed from an object with string values to an object with array values:

```jsonc
// Before (v0.2 and earlier)
"headers": {
  "content-type": "text/html",
  "set-cookie": "grafana_session=abc123; Path=/; HttpOnly"
}

// After (v0.3)
"headers": {
  "content-type": ["text/html"],
  "set-cookie": [
    "grafana_session=abc123; Path=/; HttpOnly",
    "grafana_session_expiry=1748...; Path=/"
  ]
}
```

> **Deployment note:** The wire format change is not backward-compatible. A v0.3 server will fail to deserialise frames from a v0.2 agent and vice versa. Both components must be upgraded together.
