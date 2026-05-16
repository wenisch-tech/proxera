# REST API

The Proxera REST API is available on the Admin port (8080) at `/admin/api`.

An interactive Swagger UI is served at [http://localhost:8080/api](http://localhost:8080/api).

The raw OpenAPI JSON spec is at `/v3/api-docs`.

## Authentication

All API endpoints require authentication. Pass a named API key via the `X-API-KEY` header:

```bash
curl -H "X-API-KEY: your-api-key" http://localhost:8080/admin/api/agents
```

API keys can be generated in the Admin UI under **API Keys**.

## Agents

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/api/agents` | List all agents |
| `POST` | `/admin/api/agents` | Create a new agent slot |
| `GET` | `/admin/api/agents/{id}` | Get agent details |
| `DELETE` | `/admin/api/agents/{id}` | Delete an agent |
| `POST` | `/admin/api/agents/{id}/token` | Generate a new registration token (invalidates previous) |

## Routes

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/api/routes` | List all routes |
| `POST` | `/admin/api/routes` | Create a route |
| `GET` | `/admin/api/routes/{id}` | Get route details |
| `PUT` | `/admin/api/routes/{id}` | Update a route |
| `DELETE` | `/admin/api/routes/{id}` | Delete a route |
| `GET` | `/admin/api/routes/{id}/log` | Get recent access log entries for a route |

## Topology

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/api/topology` | Full topology snapshot (pods, agents, routes, edges) |

## API Keys

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/api/api-keys` | List API keys |
| `POST` | `/admin/api/api-keys` | Generate a new API key |
| `DELETE` | `/admin/api/api-keys/{id}` | Revoke an API key |

## Users

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/api/users` | List users |
| `POST` | `/admin/api/users` | Create a user |
| `PUT` | `/admin/api/users/{id}` | Update a user |
| `DELETE` | `/admin/api/users/{id}` | Delete a user |

## SSE Streams

| Path | Port | Description |
|------|------|-------------|
| `GET /admin/sse/topology` | 8080 | Live topology events (`AGENT_CONNECTED`, `AGENT_DISCONNECTED`, `REQUEST_IN_FLIGHT`, ...) |
| `GET /admin/sse/routes/{id}/log` | 8080 | Live access log entries for a specific route |

## Example: Create an Agent and Generate a Token

```bash
# Create agent slot
AGENT=$(curl -s -X POST http://localhost:8080/admin/api/agents \
  -H "X-API-KEY: your-key" \
  -H "Content-Type: application/json" \
  -d '{"name": "home-lab"}')

AGENT_ID=$(echo $AGENT | jq -r '.id')

# Generate registration token
TOKEN=$(curl -s -X POST http://localhost:8080/admin/api/agents/$AGENT_ID/token \
  -H "X-API-KEY: your-key")

echo "Registration token: $(echo $TOKEN | jq -r '.token')"
# Copy this token to your Proxera Agent config — it is shown only once.
```
