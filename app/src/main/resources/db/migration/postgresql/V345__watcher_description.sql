-- A watcher carries a short label, at most 100 characters, shown on the
-- Watchers page for whoever is debugging, beside the note the agent leaves itself. Nullable, so a
-- watcher set before this simply has none. Issue #621.
ALTER TABLE watcher ADD COLUMN description text;
