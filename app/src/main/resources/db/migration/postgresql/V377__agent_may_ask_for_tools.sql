-- Whether an agent may ask the person for tools it was not granted: it is
-- offered a tool that lists what it could be given and one that asks, and a
-- person answers Accept or Refuse in the conversation.
--
-- On for every agent, existing ones included, on purpose: asking grants
-- nothing by itself - a person still decides - and an agent that cannot ask
-- is one that answers "I cannot do that" where one click would have let it.
-- Switched off per agent on its page.
ALTER TABLE agent
    ADD COLUMN may_ask_for_tools BOOLEAN NOT NULL DEFAULT TRUE;

-- What a request is about, item by item - the tools asked for, each with its
-- kind and what it does - drawn as a list under the question. JSON; null for
-- a request that is about one thing its question already names.
ALTER TABLE interaction_request
    ADD COLUMN items TEXT;
