-- The line a tool gets in the system prompt. Issue #481.
--
-- Every tool an agent holds is listed in its briefing now, so it knows what it
-- has instead of searching for words it hopes exist. A list like that is only
-- affordable if each line is short: fifty characters, which is a phrase rather
-- than a sentence.
--
-- Apart from the description, which is what a model reads at the moment of
-- calling, having already decided to call something. Null here means nobody has
-- written one and the description's first words stand in, so nothing changes for
-- a tool written before today.

ALTER TABLE agent_tool
    ADD COLUMN summary varchar(50);
