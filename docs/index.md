# Proxera Documentation

[![GitHub Release](https://img.shields.io/github/v/release/wenisch-tech/proxera)](https://github.com/wenisch-tech/proxera/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/wenisch-tech/proxera/blob/main/LICENSE)
[![Container](https://img.shields.io/badge/ghcr.io-proxera-blue)](https://github.com/wenisch-tech/proxera/pkgs/container/proxera)

Welcome to the official documentation for Proxera.

## Native Build

Native image build and runtime instructions are documented here:

- [Native Build (GraalVM)](native-build.md)

---

## What is Proxera?

Proxera is a **self-hosted reverse tunnel** that lets HTTP services running in a private LAN be exposed to the internet — without opening any inbound firewall rules. It is similar in concept to Cloudflare Tunnel, but you remain in full control of where your traffic flows.

!!! note "Early Alpha"
    This application is still in an early alpha and under active development. Breaking changes to the API may be introduced at any time until a v1 release.

---

## How It Works

| Component | Location | Repository |
|-----------|----------|------------|
| **Proxera Server** | Kubernetes / Cloud | *This repository* |
| **Proxera Agent** | LAN / on-premise | [wenisch-tech/proxera-agent](https://github.com/wenisch-tech/proxera-agent) |

1. **Proxera Server** runs in Kubernetes (or Docker). It receives public HTTP requests and dispatches them through a persistent WebSocket tunnel to the registered agent.
2. **Proxera Agent** runs in the LAN. It connects outbound to the server, awaits request frames, performs local HTTP calls, and streams the response back.

No inbound ports need to be opened in the LAN. All connectivity is agent-initiated.

---

## Features

- **WebSocket reverse tunnel** — persistent outbound connection from LAN agent to cloud server
- **Multi-domain routing** — assign one or more public domains and optional path prefixes to any local service
- **Topology dashboard** — interactive live graph showing pods, connected agents, and active routes
- **Live request logs** — per-route scrolling access log streamed in real time
- **GitLab runner-style registration** — admin creates a named agent slot and generates a one-time token
- **Horizontal scaling** — optional Redis Pub/Sub message bus for multi-pod deployments; in-memory fallback for single-pod (no Redis required)
- **Admin UI + REST API** — Bootstrap 5 admin panel and Swagger UI on port 8080
- **Prometheus metrics** — exposed at `/actuator/prometheus`
- **Kubernetes Ingress management** — create, edit, and delete Ingress resources directly from the Topology view when running in-cluster
- **Helm chart** — production-ready Kubernetes deployment with RBAC, service account, and separate proxy/admin ingresses

---

## Quick Start

### Prerequisites

Before deploying, configure DNS records for your domains:

| Domain | Target | Purpose |
|--------|--------|---------|
| `app.intranet.example.com` | Proxera proxy ingress IP / CNAME | Public hostname for all routed services |
| `admin.proxera.example.com` | Proxera admin ingress IP / CNAME | Admin UI, REST API, and agent WebSocket tunnel |

!!! tip "Wildcard DNS"
    Proxera supports dynamic Ingress creation directly from the Topology UI. To avoid adding a new DNS record for every service you expose, configure a wildcard entry `*.intranet.example.com` pointing to the proxy ingress IP / CNAME. Any subdomain you later create as an Ingress host will resolve automatically.

---

### Step 1 — Deploy Proxera Server

**Kubernetes (Helm):**

```bash
helm repo add wenisch-tech https://charts.wenisch.tech
helm repo update
helm install proxera wenisch-tech/proxera \
  --set ingress.admin.enabled=true \
  --set ingress.admin.hosts[0].host=admin.proxera.example.com \
  -n proxera --create-namespace
```

**Docker (local / dev):**

```bash
docker run -d --name proxera -p 8080:8080 ghcr.io/wenisch-tech/proxera:latest
```

Proxera starts with an embedded H2 database by default — no external services required.

---

### Step 2 — Log in to the Admin UI

Open `https://admin.proxera.example.com/admin` (or `http://localhost:8080/admin` for Docker).

| Username | Password |
|----------|----------|
| `admin@proxera.local` | `admin` |

!!! warning "Change default credentials"
    Create a new admin account and remove or change the default credentials immediately after first login via **Admin → Users**.

---

### Step 3 — Create an Agent

An *agent* is a named slot that represents a Proxera Agent instance running in your LAN.

**Via the Admin UI:** Navigate to **Agents → New Agent**, enter a name (e.g. `home-lab`), and click **Create**.

**Via the API:**

```bash
curl -s -X POST http://localhost:8080/admin/api/agents \
  -H "X-API-KEY: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"name": "home-lab"}'
```

---

### Step 4 — Generate a Registration Token

Each agent authenticates with a one-time registration token.

!!! warning "Copy immediately"
    The token is shown **only once** and cannot be retrieved later.

**Via the Admin UI:** Open the agent detail page and click **Generate Token**.

**Via the API:**

```bash
curl -s -X POST http://localhost:8080/admin/api/agents/<agent-id>/token \
  -H "X-API-KEY: your-api-key"
```

The response contains a `token` field — save it for the next step.

---

### Step 5 — Install and Start the Agent

The Proxera Agent runs inside your LAN. Get it from [wenisch-tech/proxera-agent](https://github.com/wenisch-tech/proxera-agent).

**Home Assistant Add-on:**

[![Add repository to Home Assistant](https://my.home-assistant.io/badges/supervisor_add_addon_repository.svg)](https://my.home-assistant.io/redirect/supervisor_add_addon_repository/?repository_url=https%3A%2F%2Fgithub.com%2Fwenisch-tech%2Fproxera-agent)

Add the repository `https://github.com/wenisch-tech/proxera-agent` in **Settings → Add-ons → Add-on Store → Repositories**, then install **Proxera Agent** and set `server_url` and `api_key` in the Configuration tab. See the [Home Assistant guide](homeassistant.md) for full details.

---

**Helm (Kubernetes in LAN):**

```bash
helm repo add wenisch-tech https://charts.wenisch.tech
helm repo update
helm upgrade --install proxera-agent wenisch-tech/proxera-agent \
  --namespace proxera \
  --create-namespace \
  --set config.serverUrl="wss://admin.proxera.example.com/tunnel" \
  --set secret.apiKey="<registration-token>"
```

**Docker:**

```bash
docker run -d --name proxera-agent \
  -e PROXERA_SERVER_URL=wss://admin.proxera.example.com/tunnel \
  -e PROXERA_API_KEY=<registration-token> \
  ghcr.io/wenisch-tech/proxera-agent:latest
```

**Binary — Linux:**

```bash
curl -LO https://github.com/wenisch-tech/proxera-agent/releases/latest/download/proxera-agent-linux-amd64
chmod +x proxera-agent-linux-amd64
./proxera-agent-linux-amd64 \
  --server-url wss://admin.proxera.example.com/tunnel \
  --api-key "<registration-token>"
```

**Binary — Windows (PowerShell):**

```powershell
Invoke-WebRequest -Uri "https://github.com/wenisch-tech/proxera-agent/releases/latest/download/proxera-agent-windows-amd64.exe" -OutFile "proxera-agent.exe"
.\proxera-agent.exe `
  --server-url wss://admin.proxera.example.com/tunnel `
  --api-key "<registration-token>"
```

Once started, the agent connects outbound to the server. Its status changes to **Connected** on the Agents page and in the Topology view. The registration token is consumed on first connect and cannot be reused.

---

### Step 6 — Add Routes

A *route* maps a public domain (and optional path prefix) to a local service reachable by the agent.

**Via the Admin UI:** Navigate to **Routes → New Route** and fill in:

| Field | Example | Description |
|-------|---------|-------------|
| **Agent** | `home-lab` | The agent that handles requests for this route |
| **Domain** | `myapp.proxy.example.com` | Public hostname that Proxera matches incoming requests against |
| **Path prefix** | `/` | URL path prefix to match (use `/` to match all paths) |
| **Target URL** | `http://192.168.1.10:3000` | The local service URL the agent forwards requests to |

**Via the API:**

```bash
curl -s -X POST http://localhost:8080/admin/api/routes \
  -H "X-API-KEY: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "<agent-id>",
    "domain": "myapp.proxy.example.com",
    "prefix": "/",
    "targetUrl": "http://192.168.1.10:3000"
  }'
```

You can add multiple routes per agent — each with a different domain or path prefix.

---

### Step 7 — Create a Proxy Ingress (Kubernetes)

When running in Kubernetes, Proxera manages Kubernetes Ingress resources directly from the **Topology view** — no additional Helm values required. Click the **+** button in the **INGRESS** column to open the creation dialog.

| Field | Example | Description |
|-------|---------|-------------|
| **Name** | `proxera-proxy` | Name of the Kubernetes Ingress resource |
| **Class Name** | `nginx` | Ingress class (e.g. `nginx`, `traefik`) |
| **Annotations** | `cert-manager.io/cluster-issuer: letsencrypt-prod` | Additional Ingress annotations |
| **Host** | `app.intranet.example.com` | Public hostname that routes to Proxera |
| **Path** | `/` | Path prefix (use `/` to match all traffic) |
| **TLS** | ✓ | Enable TLS and reference a cert-manager secret |

!!! info "Local / Docker"
    Ingress management is only available when running in-cluster. Outside Kubernetes, the topology shows an *Ingress — Not available* node.

---

### Step 8 — Use It

With DNS already pointing `app.intranet.example.com` to the proxy ingress, the route is immediately reachable.

Any HTTP request to `https://app.intranet.example.com` is now:

1. Received by the **Proxera Server**
2. Matched against your routes by domain + path prefix
3. Forwarded through the **WebSocket tunnel** to the **Proxera Agent** in your LAN
4. Proxied by the agent to `http://192.168.1.10:3000`
5. The response is streamed back to the original client

!!! tip "Local Docker testing"
    Pass the host header explicitly when testing locally: `curl -H "Host: myapp.proxy.example.com" http://localhost:8080`

Monitor live traffic under **Routes → (route name) → Logs** or watch the **Topology** view for connected agents and in-flight requests.

---

## Navigate the Docs

- [Architecture](architecture.md) — System design, tunnel protocol, scaling model, data model
- [Configuration](configuration.md) — Environment variables and Helm values reference
- [API Reference](api.md) — REST API endpoints and examples
- [Home Assistant](homeassistant.md) — Installing and configuring the Proxera Agent as a Home Assistant add-on

---

## Project Links

- Repository: <https://github.com/wenisch-tech/proxera>
- Agent repository: <https://github.com/wenisch-tech/proxera-agent>
- Container image: <https://github.com/wenisch-tech/proxera/pkgs/container/proxera>
- Helm chart: <https://charts.wenisch.tech>
