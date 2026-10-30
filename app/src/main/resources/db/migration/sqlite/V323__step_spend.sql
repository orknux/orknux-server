-- What a step's model calls cost where it keeps no session; see the Postgres V643.
ALTER TABLE execution_step ADD COLUMN spent_cost NUMERIC(20, 8) NOT NULL DEFAULT 0;
ALTER TABLE execution_step ADD COLUMN spent_partly_unpriced BOOLEAN NOT NULL DEFAULT FALSE;
