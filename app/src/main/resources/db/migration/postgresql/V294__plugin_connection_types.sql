-- A plugin can declare connection kinds, so a workspace holds several hosts of
-- its kind - two Prometheus servers, two wikis - each labelled and picked by
-- name rather than every one reading as HTTP. The plugin keeps what it declared;
-- a connection names which declared kind it is. Issue #363.

ALTER TABLE plugin ADD COLUMN declared_connection_types text NOT NULL DEFAULT '[]';

-- Plugin key and declared name joined, e.g. prometheus/server. Only set on an
-- HTTP connection; null for every core type and a plain HTTP endpoint.
ALTER TABLE workspace_connection ADD COLUMN plugin_type varchar(120);
