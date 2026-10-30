-- Whether a workspace with no completion model of its own uses the one set under
-- Admin -> Settings. False for every workspace there is: they chose "nothing is
-- suggested" or never chose, and a new setting must not start sending what is
-- typed there to a model. The application makes new ones true.
ALTER TABLE workspace ADD COLUMN completion_model_default BOOLEAN NOT NULL DEFAULT FALSE;
