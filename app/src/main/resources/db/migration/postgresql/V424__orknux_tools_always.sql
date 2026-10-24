-- The orknux_* tools take Hide, Offer and Always like every other built-in from
-- 1.2.0. They used to travel on every turn whatever was marked, so every agent
-- with Orknux access has the ones it has not hidden marked Always - with a tool
-- limit or without, so its page reads as it did. The names are the orknux_*
-- surface of 1.2.0, written out because they live in code rather than in a
-- table; one added later arrives at Offer on an existing agent, as any new
-- built-in does until a migration says otherwise. Positions follow on densely
-- after the agent's existing marks. Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_workflows'),
    (2, 'orknux_executions'),
    (3, 'orknux_execution'),
    (4, 'orknux_agents'),
    (5, 'orknux_agent'),
    (6, 'orknux_models'),
    (7, 'orknux_update_model'),
    (8, 'orknux_sessions'),
    (9, 'orknux_session'),
    (10, 'orknux_issues'),
    (11, 'orknux_issue_labels'),
    (12, 'orknux_issue_types'),
    (13, 'orknux_issue'),
    (14, 'orknux_open_issue'),
    (15, 'orknux_comment_on_issue'),
    (16, 'orknux_set_issue_status'),
    (17, 'orknux_delete_issue'),
    (18, 'orknux_update_issue'),
    (19, 'orknux_news'),
    (20, 'orknux_functions'),
    (21, 'orknux_function'),
    (22, 'orknux_tools'),
    (23, 'orknux_tool'),
    (24, 'orknux_run_workflow'),
    (25, 'orknux_start_task'),
    (26, 'orknux_rerun_execution'),
    (27, 'orknux_set_workflow_enabled'),
    (28, 'orknux_set_agent_enabled'),
    (29, 'orknux_create_agent'),
    (30, 'orknux_update_agent'),
    (31, 'orknux_workflow'),
    (32, 'orknux_workflow_parts'),
    (33, 'orknux_workflow_reference'),
    (34, 'orknux_create_workflow'),
    (35, 'orknux_save_workflow_graph'),
    (36, 'orknux_publish_workflow'),
    (37, 'orknux_create_trigger'),
    (38, 'orknux_create_action'),
    (39, 'orknux_create_condition'),
    (40, 'orknux_manual'),
    (41, 'orknux_manual_section'),
    (42, 'orknux_manual_search')
),
missing AS (
  SELECT a.id AS agent_id, t.ord, t.name
  FROM agent a
  CROSS JOIN orknux_name t
  WHERE a.orknux_access = true
    AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = t.name)
    AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = t.name)
)
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT m.agent_id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = m.agent_id), -1)
         + row_number() OVER (PARTITION BY m.agent_id ORDER BY m.ord),
       m.name
FROM missing m;
