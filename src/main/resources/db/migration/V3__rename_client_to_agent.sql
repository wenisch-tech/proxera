-- Rename clients table to agents
ALTER TABLE clients RENAME TO agents;

-- Rename client_id foreign key columns
ALTER TABLE registration_tokens RENAME COLUMN client_id TO agent_id;
ALTER TABLE routes RENAME COLUMN client_id TO agent_id;
ALTER TABLE access_log RENAME COLUMN client_id TO agent_id;
