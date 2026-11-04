-- How many turns in a row a task's model could not be reached, for its waits to grow and to end.
ALTER TABLE task ADD COLUMN model_retries INT NOT NULL DEFAULT 0;
