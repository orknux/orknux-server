-- How many other agents one agent in this workspace may ask in one
-- conversation. Issue #380.
--
-- Each ask starts a conversation of its own - its own model calls, its own
-- tools - on the asking model's say-so, so this is the number that bounds
-- what one question can fan out into. It was unbounded: an agent given ten
-- specialists could ask each of them ten times over and nothing but the
-- rounds bound would notice.
--
-- The installation carries the number in Admin -> Settings; this column is a
-- workspace's own, for the same reason a task's turns are: what a question is
-- worth is a judgement about the work this workspace does. Null is every
-- workspace as it stands and means it has decided nothing, so nothing changes
-- until somebody fills it in. Zero is a real answer: no agent here asks
-- another.
ALTER TABLE workspace
    ADD COLUMN agent_max_subagents INTEGER;

-- The bounds the screen offers, held by the database as well. A hundred is a
-- bill rather than a brief; the ceiling exists to catch a digit too many.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_agent_max_subagents
        CHECK (agent_max_subagents IS NULL OR (agent_max_subagents BETWEEN 0 AND 100));
