-- The cluster lease. #597. The postgres copy, V339, carries the reasoning.
--
-- SQLite is one process, so the row is only ever this server's - but it is the
-- same code path, and on the inline engine it is what refuses a second process
-- started on the same file.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
