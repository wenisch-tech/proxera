-- V4: Move path_prefix and strip_prefix from routes to route_domains
-- Each domain entry now carries its own path prefix and strip flag,
-- allowing one route to be reachable via multiple domain+path combinations
-- (e.g. wenisch.tech/grafana, wenisch.tech/service, test.tech/specialservice).

-- 1. Drop the existing UNIQUE constraint on domain alone
ALTER TABLE route_domains DROP CONSTRAINT IF EXISTS route_domains_domain_key;

-- 2. Add path_prefix and strip_prefix columns to route_domains
ALTER TABLE route_domains ADD COLUMN IF NOT EXISTS path_prefix VARCHAR(255);
ALTER TABLE route_domains ADD COLUMN IF NOT EXISTS strip_prefix BOOLEAN NOT NULL DEFAULT TRUE;

-- 3. Drop the now-redundant columns from routes (IF EXISTS — safe to re-run)
ALTER TABLE routes DROP COLUMN IF EXISTS path_prefix;
ALTER TABLE routes DROP COLUMN IF EXISTS strip_prefix;

-- 4. Add composite unique index on (domain, path_prefix).
-- NULL path_prefix entries are enforced as unique at the application layer
-- (see RouteDomainRepository.existsByDomainAndPathPrefixExcludingRoute).
CREATE UNIQUE INDEX IF NOT EXISTS route_domains_domain_path_key
    ON route_domains (domain, path_prefix);
