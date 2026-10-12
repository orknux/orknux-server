-- Where the agent gave no agent check, its looks back off exponentially: each
-- delay is the last times a multiplier, up to a ceiling. This marks the
-- watchers whose interval grows after each look; an interval the agent chose
-- stays as it chose it. Watchers set before this keep their fixed spacing.
ALTER TABLE watcher ADD COLUMN agent_check_backoff boolean NOT NULL DEFAULT false;
