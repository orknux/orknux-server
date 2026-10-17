-- See V375__voice_button_hidden.sql on Postgres.
ALTER TABLE workspace
    ADD COLUMN voice_button_hidden boolean NOT NULL DEFAULT false;
