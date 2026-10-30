-- What a step's model calls cost where it keeps no session to hold that: an
-- agent node with no session wired to it was counted nowhere.
ALTER TABLE execution_step ADD COLUMN spent_cost NUMERIC(20, 8) NOT NULL DEFAULT 0;
ALTER TABLE execution_step ADD COLUMN spent_partly_unpriced BOOLEAN NOT NULL DEFAULT FALSE;
