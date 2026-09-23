-- A workflow can say something out loud, and what it said is kept.

-- The product speaks in a chat and nowhere else: a run that wanted to hand
-- somebody audio - a summary to listen to on the way in, a warning read out over
-- a phone bridge, a message for a channel where nobody reads - had no way to
-- make any. Every other kind of output a run produces is a file it leaves
-- behind, and this is the missing one.

-- Filed against the run and the step, exactly as a drawn picture is: the two are
-- the same kind of thing - bytes a step produced, shown under the node that
-- produced them - and a run that kept its pictures one way and its audio another
-- would be two answers to one question.
CREATE TABLE execution_speech
(
    id           bigserial PRIMARY KEY,
    execution_id bigint       NOT NULL REFERENCES workflow_execution (id) ON DELETE CASCADE,
    node_key     varchar(64)  NOT NULL,
    workspace_id bigint       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    -- What was said. Kept because the audio cannot be read at a glance and a
    -- list of files nobody can skim is a list nobody opens.
    said         text         NOT NULL,
    filename     varchar(255) NOT NULL,
    content_type varchar(120) NOT NULL,
    size_bytes   bigint       NOT NULL,
    location     varchar(1000) NOT NULL,
    spoken_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX execution_speech_execution_idx ON execution_speech (execution_id, spoken_at, id);

-- What the action says, and in which voice.
--
-- The text is the action's rather than the node's because an action is the
-- reusable half: "read the summary out" is the action, and which summary is the
-- node's mapping. A voice left empty is the model's own.
ALTER TABLE workflow_action
    ADD COLUMN speech_text text,
    ADD COLUMN speech_voice varchar(80),
    -- Null follows the workspace's own choice, which is where a chat reads it
    -- from too. Named here for the run that wants a different one.
    ADD COLUMN speech_model_id bigint;

ALTER TABLE workflow_action
    DROP CONSTRAINT ck_workflow_action_subtype;
ALTER TABLE workflow_action
    ADD CONSTRAINT ck_workflow_action_subtype
        CHECK (subtype IN ('OUTGOING_CONNECTION', 'SEND_EMAIL', 'HTTP_REQUEST', 'FUNCTION',
                           'INLINE_CONDITION', 'CONDITION', 'TIME', 'SPEAK'));
