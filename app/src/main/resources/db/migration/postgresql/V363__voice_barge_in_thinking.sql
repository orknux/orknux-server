-- Whether talking while the model is still thinking stops it, the way talking
-- over the answer does. Null is the workspace having decided nothing, which
-- is on: the interface owns the default, as it does for the hold. A nullable
-- column, so rollback-floor stays where it is.
ALTER TABLE workspace ADD COLUMN voice_barge_in_thinking boolean;
