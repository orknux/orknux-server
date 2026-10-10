-- Tins are called boxes now, and what an agent marked follows the new names.
-- The postgres copy, V350, carries the reasoning.

UPDATE agent_hidden_skill
SET name = CASE lower(name)
    WHEN 'tins' THEN 'boxes'
    WHEN 'tin-project-changes' THEN 'box-project-changes'
    WHEN 'tin-new-project' THEN 'box-new-project'
    WHEN 'tin-debugging' THEN 'box-debugging'
    WHEN 'tin-git' THEN 'box-git'
    WHEN 'tin-long-commands' THEN 'box-long-commands'
END
WHERE lower(name) IN ('tins', 'tin-project-changes', 'tin-new-project', 'tin-debugging', 'tin-git', 'tin-long-commands');

UPDATE agent_required_skill
SET name = CASE lower(name)
    WHEN 'tins' THEN 'boxes'
    WHEN 'tin-project-changes' THEN 'box-project-changes'
    WHEN 'tin-new-project' THEN 'box-new-project'
    WHEN 'tin-debugging' THEN 'box-debugging'
    WHEN 'tin-git' THEN 'box-git'
    WHEN 'tin-long-commands' THEN 'box-long-commands'
END
WHERE lower(name) IN ('tins', 'tin-project-changes', 'tin-new-project', 'tin-debugging', 'tin-git', 'tin-long-commands');

UPDATE agent_hidden_tool
SET name = 'orkbox_' || substr(name, 5)
WHERE name IN ('tin_list', 'tin_open', 'tin_sessions', 'tin_close', 'tin_extend', 'tin_exec', 'tin_execution',
               'tin_output', 'tin_stdin', 'tin_signal', 'tin_files', 'tin_read', 'tin_write', 'tin_edit', 'tin_stat',
               'tin_delete', 'tin_move', 'tin_search', 'tin_log', 'tin_export', 'tin_import');

UPDATE agent_required_tool
SET name = 'orkbox_' || substr(name, 5)
WHERE name IN ('tin_list', 'tin_open', 'tin_sessions', 'tin_close', 'tin_extend', 'tin_exec', 'tin_execution',
               'tin_output', 'tin_stdin', 'tin_signal', 'tin_files', 'tin_read', 'tin_write', 'tin_edit', 'tin_stat',
               'tin_delete', 'tin_move', 'tin_search', 'tin_log', 'tin_export', 'tin_import');
