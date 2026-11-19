-- Postgres V691: the shapes read through the plugins, and only a Jira
-- connection is left to choose. The dropped columns were never in a release.
ALTER TABLE workspace DROP COLUMN diagram_code_function_id;
ALTER TABLE workspace DROP COLUMN diagram_jira_function_id;
ALTER TABLE workspace ADD COLUMN diagram_jira_connection_id INTEGER;
