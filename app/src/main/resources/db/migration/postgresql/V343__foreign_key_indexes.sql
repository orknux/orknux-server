-- Every single-column foreign key gets an index on its referencing side.
--
-- Postgres indexes the referenced key, never the referencing one. So deleting
-- an agent, a function, a session or an issue scanned each child table to
-- check the constraint, and the lookups that go the same way - the chats of
-- an agent, the tasks of a session, the actions calling a function, the
-- workspaces a workflow is assigned to, which is what "Used by" asks - had
-- nothing to use. Thirty-six of them, found by asking the catalogue which
-- foreign keys have no index leading with their column. Issue #616.
--
-- Additive only: an older jar runs on this schema unchanged, so the rollback
-- floor stays where it is.

CREATE INDEX IF NOT EXISTS idx_app_user_role_role_id ON app_user_role (role_id);
CREATE INDEX IF NOT EXISTS idx_chat_session_agent_id ON chat_session (agent_id);
CREATE INDEX IF NOT EXISTS idx_chat_session_llm_session_id ON chat_session (llm_session_id);
CREATE INDEX IF NOT EXISTS idx_chat_session_model_id ON chat_session (model_id);
CREATE INDEX IF NOT EXISTS idx_component_revision_workspace_id ON component_revision (workspace_id);
CREATE INDEX IF NOT EXISTS idx_execution_picture_workspace_id ON execution_picture (workspace_id);
CREATE INDEX IF NOT EXISTS idx_execution_speech_workspace_id ON execution_speech (workspace_id);
CREATE INDEX IF NOT EXISTS idx_issue_news_issue_id ON issue_news (issue_id);
CREATE INDEX IF NOT EXISTS idx_issue_news_task_id ON issue_news (task_id);
CREATE INDEX IF NOT EXISTS idx_plugin_parameter_variable_id ON plugin_parameter (variable_id);
CREATE INDEX IF NOT EXISTS idx_shell_session_shell_id ON shell_session (shell_id);
CREATE INDEX IF NOT EXISTS idx_task_agent_id ON task (agent_id);
CREATE INDEX IF NOT EXISTS idx_task_grant_request_id ON task_grant (request_id);
CREATE INDEX IF NOT EXISTS idx_task_issue_id ON task (issue_id);
CREATE INDEX IF NOT EXISTS idx_task_model_id ON task (model_id);
CREATE INDEX IF NOT EXISTS idx_task_picture_workspace_id ON task_picture (workspace_id);
CREATE INDEX IF NOT EXISTS idx_task_session_id ON task (session_id);
CREATE INDEX IF NOT EXISTS idx_watcher_session_id ON watcher (session_id);
CREATE INDEX IF NOT EXISTS idx_workflow_action_condition_id ON workflow_action (condition_id);
CREATE INDEX IF NOT EXISTS idx_workflow_action_function_id ON workflow_action (function_id);
CREATE INDEX IF NOT EXISTS idx_workflow_condition_function_id ON workflow_condition (function_id);
CREATE INDEX IF NOT EXISTS idx_workflow_condition_member_member_id ON workflow_condition_member (member_id);
CREATE INDEX IF NOT EXISTS idx_workflow_execution_started_from ON workflow_execution (started_from);
CREATE INDEX IF NOT EXISTS idx_workflow_node_object_id ON workflow_node (object_id);
CREATE INDEX IF NOT EXISTS idx_workflow_trigger_auth_function_id ON workflow_trigger (auth_function_id);
CREATE INDEX IF NOT EXISTS idx_workflow_trigger_condition_id ON workflow_trigger (condition_id);
CREATE INDEX IF NOT EXISTS idx_workspace_admin_role_role_id ON workspace_admin_role (role_id);
CREATE INDEX IF NOT EXISTS idx_workspace_connection_connection_id ON workspace_connection (connection_id);
CREATE INDEX IF NOT EXISTS idx_workspace_image_model_id ON workspace (image_model_id);
CREATE INDEX IF NOT EXISTS idx_workspace_issue_type_id ON workspace_issue (type_id);
CREATE INDEX IF NOT EXISTS idx_workspace_quick_chat_model_id ON workspace (quick_chat_model_id);
CREATE INDEX IF NOT EXISTS idx_workspace_role_role_id ON workspace_role (role_id);
CREATE INDEX IF NOT EXISTS idx_workspace_session_compaction_model_id ON workspace (session_compaction_model_id);
CREATE INDEX IF NOT EXISTS idx_workspace_speech_model_id ON workspace (speech_model_id);
CREATE INDEX IF NOT EXISTS idx_workspace_transcription_model_id ON workspace (transcription_model_id);
CREATE INDEX IF NOT EXISTS idx_workspace_workflow_workflow_id ON workspace_workflow (workflow_id);
