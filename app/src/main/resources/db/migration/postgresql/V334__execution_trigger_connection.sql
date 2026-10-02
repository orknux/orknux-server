-- A run started by an event on a connection - a Slack mention, a message, a
-- reply - is recorded as CONNECTION rather than WEBHOOK. It used to share the
-- webhook's value, so the run page said "Triggered by: Webhook" for a Slack
-- mention, which sent whoever read it looking for a URL nobody had called.
--
-- Runs recorded before this keep WEBHOOK: nothing on an old row says which of
-- the two it was other than the fired trigger, and rewriting history to guess is
-- worse than leaving it as it was recorded. Widening only, so additive in the
-- sense rollback-floor means.

ALTER TABLE workflow_execution DROP CONSTRAINT ck_execution_trigger;
ALTER TABLE workflow_execution
    ADD CONSTRAINT ck_execution_trigger
        CHECK (trigger_type IN ('WEBHOOK', 'MANUAL', 'SCHEDULE', 'API', 'CONNECTION'));
