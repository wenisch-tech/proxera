# Home Assistant Integration

The Proxera Agent is available as a native **Home Assistant Add-on**, letting you expose services running on your Home Assistant host (or anywhere reachable from it) to the internet — without opening inbound firewall ports or configuring port forwarding.

---

## How It Works

The add-on connects outbound to your Proxera server using a persistent WebSocket tunnel authenticated with a registration token. The server forwards incoming HTTP requests through the tunnel to services running locally on your Home Assistant host.

```
Internet → Proxera Server → WebSocket tunnel → Proxera Agent (HA add-on) → local service
```

No inbound ports need to be opened on your Home Assistant host. All connectivity is agent-initiated.

---

## Prerequisites

- A running Proxera server reachable over `wss://` (or `ws://` for local testing)
- A registration token generated for an agent slot on your Proxera server (see [Quick Start](index.md#step-4-generate-a-registration-token))

---

## Installation

[![Add repository to Home Assistant](https://my.home-assistant.io/badges/supervisor_add_addon_repository.svg)](https://my.home-assistant.io/redirect/supervisor_add_addon_repository/?repository_url=https%3A%2F%2Fgithub.com%2Fwenisch-tech%2Fproxera-agent)

Click the button above to add the repository automatically, or follow the manual steps:

1. In Home Assistant, go to **Settings → Add-ons → Add-on Store**
2. Click the menu **(⋮)** in the top right and choose **Repositories**
3. Add the following URL and click **Add**:
   ```
   https://github.com/wenisch-tech/proxera-agent
   ```
4. Find **Proxera Agent** in the store and click **Install**

---

## Configuration

After installation, open the add-on's **Configuration** tab and set at minimum `server_url` and `api_key`.

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `server_url` | Yes | — | WebSocket URL of your Proxera server, e.g. `wss://admin.proxera.example.com/tunnel` |
| `api_key` | Yes | — | Registration token generated in the Proxera Admin UI |
| `log_level` | No | `info` | Log verbosity: `debug`, `info`, `warn`, `error` |
| `heartbeat_interval` | No | `30s` | How often a ping is sent to keep the tunnel alive |
| `heartbeat_timeout` | No | `10s` | How long to wait for a pong before treating the connection as dead |
| `reconnect_base` | No | `1s` | Initial backoff duration before the first reconnect attempt |
| `reconnect_max` | No | `60s` | Maximum backoff duration between reconnect attempts |
| `request_timeout` | No | `30s` | Timeout for proxied HTTP requests to local services |
| `concurrency_limit` | No | `100` | Maximum number of in-flight proxied requests |

Duration values use Go duration syntax: `30s`, `1m`, `500ms`.

### Example configuration

```yaml
server_url: wss://admin.proxera.example.com/tunnel
api_key: "your-registration-token"
log_level: info
```

---

## Starting the Add-on

1. Save your configuration
2. Go to the **Info** tab and click **Start**
3. Check the **Log** tab — you should see output similar to:
   ```
   Starting proxera-agent...
   Server URL: wss://admin.proxera.example.com/tunnel
   ```
   followed by a line confirming the tunnel is registered with the server.

The add-on uses `startup: services`, so it starts automatically before Home Assistant Core on every boot.

Once running, the agent's status changes to **Connected** in the Proxera Admin UI under **Agents** and in the **Topology** view.

!!! tip "Exposing the Home Assistant UI"
    To expose the Home Assistant frontend itself, create a route in Proxera pointing to `http://homeassistant.local:8123` (or `http://localhost:8123` if the agent runs directly on the HA host). Make sure to also configure an Ingress in the Topology view for the public hostname.

!!! warning "Forwarded client IP headers"
    For the route that exposes Home Assistant itself, disable **Forward client IP headers** in the Proxera route settings. Home Assistant can reject login flows with an `IP address changed` error when it receives changing `X-Forwarded-For` / `X-Real-IP` values during authentication.

    Keep Home Assistant's `http.use_x_forwarded_for` unset or set it to `false` unless you explicitly need Home Assistant to use the original public client IP. If you set `use_x_forwarded_for: true`, Home Assistant also requires a correct `trusted_proxies` entry for the immediate proxy source, and changing forwarded client IP chains can still break strict login checks.

---

## Troubleshooting

**Add-on fails to start**
Check the **Log** tab. The most common causes are a missing `server_url` or `api_key`, or a server URL that does not start with `ws://` or `wss://`.

**Tunnel connects but requests time out**
Ensure the local service your Proxera server is routing to is actually running on the Home Assistant host and accessible on the expected host and port.

**Home Assistant login redirects back but token exchange fails**
Disable **Forward client IP headers** on the Home Assistant route in Proxera. Also keep Home Assistant's `http.use_x_forwarded_for` unset or set to `false` unless you have a specific need for forwarded public client IPs. A typical symptom is that `/auth/login_flow` accepts the credentials locally, but the proxied login flow fails with `IP address changed` or the browser cannot complete `/auth/token`.

**Frequent disconnections**
Try lowering `heartbeat_interval` (e.g. `15s`) or increasing `heartbeat_timeout` (e.g. `20s`) if you have a high-latency connection to your Proxera server.

**Enable debug logging**
Set `log_level: debug` and restart the add-on to see detailed frame-level traffic in the Log tab.

---

## Links

- [Proxera Agent repository](https://github.com/wenisch-tech/proxera-agent)
- [Proxera Server repository](https://github.com/wenisch-tech/proxera)
- [Agent Changelog](https://github.com/wenisch-tech/proxera-agent/blob/main/CHANGELOG.md)
