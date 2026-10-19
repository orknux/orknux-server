-- Postgres V392: chats show when each message was sent, in every workspace. The
-- column's own default stays false here - SQLite cannot change one in place -
-- and nothing reads it: a workspace is written whole, from the entity.

UPDATE workspace SET chat_show_timestamps = 1;
