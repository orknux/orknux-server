-- Code and Jira shapes read through the GitHub and Jira plugins themselves
-- rather than through a function a workspace picked, so the two picked
-- function columns go; what is left to choose is which Jira connection a
-- Jira shape reads through, null being the one the plugin is pointed at.
--
-- The dropped columns came in with V689 and no release ever read them, so no
-- jar a database could be rolled back to knows them: the rollback floor stays.
ALTER TABLE workspace DROP COLUMN diagram_code_function_id;
ALTER TABLE workspace DROP COLUMN diagram_jira_function_id;
ALTER TABLE workspace ADD COLUMN diagram_jira_connection_id BIGINT;
