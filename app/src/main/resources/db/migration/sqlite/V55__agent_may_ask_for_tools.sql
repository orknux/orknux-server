-- See V377__agent_may_ask_for_tools.sql on Postgres.
ALTER TABLE agent ADD COLUMN may_ask_for_tools BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE interaction_request ADD COLUMN items TEXT;
