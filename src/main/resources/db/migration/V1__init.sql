-- Proxera V1 Initial Schema
-- Compatible with PostgreSQL and H2 in PostgreSQL mode

-- Users (admin UI login)
CREATE TABLE IF NOT EXISTS users (
    id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    username    VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role        VARCHAR(32) NOT NULL DEFAULT 'ROLE_ADMIN',
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Clients (tunnel endpoints)
CREATE TABLE IF NOT EXISTS clients (
    id              UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    connected_pod_id VARCHAR(255),
    last_seen_at    TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Registration tokens (one-time, GitLab-runner-style)
CREATE TABLE IF NOT EXISTS registration_tokens (
    id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    client_id   UUID        NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Routes
CREATE TABLE IF NOT EXISTS routes (
    id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    client_id   UUID        NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name        VARCHAR(128) NOT NULL,
    local_host  VARCHAR(255) NOT NULL,
    local_port  INT         NOT NULL DEFAULT 80,
    path_prefix VARCHAR(255),
    strip_prefix BOOLEAN    NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Route domains (one route can serve multiple domains)
CREATE TABLE IF NOT EXISTS route_domains (
    id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    route_id    UUID        NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    domain      VARCHAR(255) NOT NULL UNIQUE
);

-- API keys
CREATE TABLE IF NOT EXISTS api_keys (
    id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    key_hash    VARCHAR(255) NOT NULL,
    last_used_at TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Access log
CREATE TABLE IF NOT EXISTS access_log (
    id              BIGSERIAL   PRIMARY KEY,
    route_id        UUID        NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    client_id       UUID        NOT NULL,
    method          VARCHAR(16) NOT NULL,
    path            TEXT        NOT NULL,
    status_code     INT         NOT NULL,
    latency_ms      BIGINT      NOT NULL,
    remote_ip       VARCHAR(64),
    timestamp       TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_access_log_route_ts ON access_log(route_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_access_log_ts       ON access_log(timestamp DESC);
