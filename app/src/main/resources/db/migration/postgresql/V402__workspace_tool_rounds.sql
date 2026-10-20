-- How many tool rounds an agent in this workspace gets when it has no number
-- of its own. Between the agent's own number and the installation's: a
-- workspace whose agents do longer work than the rest raises it here instead of
-- on every agent, or for the whole installation. Null is every workspace as it
-- stands - the installation's number applies.
ALTER TABLE workspace ADD COLUMN chat_max_rounds INTEGER
    CONSTRAINT ck_workspace_chat_max_rounds CHECK (chat_max_rounds IS NULL OR (chat_max_rounds BETWEEN 2 AND 10000));
