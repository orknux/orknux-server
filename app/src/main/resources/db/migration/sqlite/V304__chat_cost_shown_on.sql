-- A chat shows what an answer cost unless its person says otherwise; every
-- person is turned on, and Preferences turns it off again. The column's own
-- default stays as the baseline wrote it: SQLite cannot change one in place,
-- and every row is written by the application, whose default is now on.

UPDATE app_user SET chat_cost_shown = 1;
