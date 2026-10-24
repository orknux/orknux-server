-- How hard the model thinks in one chat, chosen from the composer (#654).
--
-- Null is the ordinary case and means the model's own setting applies - its
-- llm_model.reasoning_effort, or nothing at all. A value here is sent in its
-- place for this chat's turns and never written back to the model. Which words
-- are allowed is declared in code (ChatParameters), as for the model's own
-- column, so there is no CHECK.

ALTER TABLE chat_session
    ADD COLUMN reasoning_effort varchar(16);

COMMENT ON COLUMN chat_session.reasoning_effort IS
    'The reasoning effort this chat asks for over its model''s own; null leaves the model''s setting.';
