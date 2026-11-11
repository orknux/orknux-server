-- Who uses which Orknux client - the Chrome extension, Orknux for Windows and
-- for Linux, Orknux Shell, the Claude Code plugin - for Admin > Reports. One
-- row per person and copy of a client, written when it asks for work.
-- Additive: a new table an older jar does not read.
CREATE TABLE client_use
(
    id         bigserial    PRIMARY KEY,
    username   varchar(120) NOT NULL,
    -- The door it came in by: BROWSER, DESKTOP, CLAUDE or SHELL.
    kind       varchar(16)  NOT NULL,
    -- What it called itself: Chrome, Windows, Linux, Shell.
    client     varchar(64),
    -- Which copy, where a person may run several: "Linux on studio".
    name       varchar(200) NOT NULL,
    version    varchar(64),
    first_seen timestamptz  NOT NULL DEFAULT now(),
    last_seen  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_client_use UNIQUE (username, kind, name)
);
CREATE INDEX idx_client_use_seen ON client_use (last_seen);
