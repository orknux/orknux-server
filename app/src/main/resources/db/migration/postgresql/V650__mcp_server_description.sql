-- What an MCP server is for, in a sentence: shown under its name on the
-- Integrations list and told to an agent beside it, as a connection's is
-- (V366). Nullable, so rollback-floor stays where it is.
ALTER TABLE mcp_server ADD COLUMN description VARCHAR(500);
