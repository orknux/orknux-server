-- Videos made on Design's Videos page. Issue #658.
--
-- A model kind VIDEO, beside IMAGE: text, and a picture to start from where the
-- model takes one, in; a video out. Its own kind rather than a switch on an
-- image model, because a video model answers none of what an image model is
-- asked - no /images/generations, no edits - and the picker of one would offer
-- the other.
--
-- And design_video: one row from the moment somebody asks to the file kept.
-- A video takes minutes and is made by the backend as a job of its own, so the
-- row also holds that job's id, which is what lets a server that restarted
-- mid-way go on asking about it rather than asking for it again.
--
-- A wider CHECK and a new table: additive, so an older jar still starts.

ALTER TABLE llm_model DROP CONSTRAINT ck_llm_model_kind;

ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_kind CHECK (
        kind IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE', 'DECISION', 'UPSCALE', 'VIDEO')
    );

CREATE TABLE design_video (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        BIGINT        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    model_id            BIGINT,
    model_name          VARCHAR(255)  NOT NULL,
    prompt              TEXT          NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    seconds             INTEGER,
    size                VARCHAR(40),
    frames              INTEGER,
    fps                 INTEGER,
    width               INTEGER,
    height              INTEGER,
    seed                BIGINT,
    start_image_id      VARCHAR(80),
    requested_by        VARCHAR(255)  NOT NULL,
    requested_at        TIMESTAMPTZ   NOT NULL,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    status              VARCHAR(20)   NOT NULL,
    reason              TEXT,
    remote_id           VARCHAR(255),
    progress            INTEGER,
    filename            VARCHAR(255),
    content_type        VARCHAR(120),
    size_bytes          BIGINT,
    location            VARCHAR(1000),
    poster_location     VARCHAR(1000),
    poster_content_type VARCHAR(120),
    duration_seconds    DOUBLE PRECISION,
    CONSTRAINT ck_design_video_status CHECK (status IN ('QUEUED', 'GENERATING', 'DONE', 'FAILED', 'STOPPED'))
);

CREATE INDEX ix_design_video_workspace ON design_video (workspace_id, requested_at);
CREATE INDEX ix_design_video_status ON design_video (status);
