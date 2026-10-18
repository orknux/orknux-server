-- How many of a chat's answers its owner has seen, so the sidebar can say how
-- many have arrived since: the answers in the thread less this. Counted rather
-- than timed because the thread is rewritten whole on every save and its
-- timestamps move with it. Existing chats start as read: what was there before
-- this column is not news.
ALTER TABLE chat_session ADD COLUMN read_answers INTEGER NOT NULL DEFAULT 0;
UPDATE chat_session SET read_answers = (
    SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY m
    WHERE m.conversation_id = chat_session.conversation_id AND m.type = 'ASSISTANT'
);
