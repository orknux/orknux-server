-- The cluster lease. #597.
--
-- Several servers on one database each ran every timer and each opened the
-- Slack sockets. One row here, held by one server and renewed every third of
-- the lease, decides which of them runs what must run once; when that server
-- dies the row runs out and another takes it.
--
-- ShedLock's table, in ShedLock's shape: the library writes it, and only
-- through ClusterLeader. TIMESTAMP rather than TIMESTAMPTZ because the
-- provider runs on the database's clock in UTC, as its documentation asks.
--
-- Additive: an older jar has no use for this table and leaves it alone.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
