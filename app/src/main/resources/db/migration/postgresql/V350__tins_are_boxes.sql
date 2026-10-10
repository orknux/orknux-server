-- Tins are called boxes now, and what an agent marked follows the new names.
--
-- The built-in skills are keyed by their name - "Tins" was `tins`, "Tin Git"
-- `tin-git` - and the sandbox tools went from `tin_*` to `orkbox_*`. An agent's
-- Hidden and Always lists hold those keys and names as text, so a skill or a
-- tool somebody switched off or made Always would quietly come back as
-- Offered under its new name. Only the names are rewritten; no row is added or
-- removed, and an older jar reading a name it does not know ignores it.
--
-- The tables themselves keep their names (`tin`, `tin_session`, ...), as do
-- the `tin.*` installation settings: nobody sees those.

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
