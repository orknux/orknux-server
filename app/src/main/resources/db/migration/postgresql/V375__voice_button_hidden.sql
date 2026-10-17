-- Whether a workspace hides the voice mode button in its chats. Off by
-- default: the button is shown, drawn off where a model is missing, unless
-- somebody chose to hide it.
ALTER TABLE workspace
    ADD COLUMN voice_button_hidden BOOLEAN NOT NULL DEFAULT FALSE;
