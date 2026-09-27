-- Which index a workspace searches, and what it authenticates with. Issue #510.
--
-- A workspace's own rather than the installation's: the key is billed to
-- whoever set it, and two teams in one installation may reasonably answer
-- differently.
--
-- `api_key` holds an encrypted envelope, not a key. It goes through the same
-- converter connections and plugin parameters use, so the width is theirs: the
-- envelope is base64 around the bytes, an initialisation vector, an
-- authentication tag and a version prefix, which leaves a long credential
-- comfortably inside four thousand characters.

CREATE TABLE workspace_search (
    workspace_id     bigint       NOT NULL PRIMARY KEY REFERENCES workspace (id) ON DELETE CASCADE,
    engine           varchar(16)  NOT NULL DEFAULT 'tavily',
    api_key          varchar(4000),
    compose_answer   boolean      NOT NULL DEFAULT false,
    last_modified_at timestamptz  NOT NULL DEFAULT now(),
    last_modified_by varchar(120) NOT NULL DEFAULT 'orknux',
    CONSTRAINT ck_workspace_search_engine CHECK (engine IN ('tavily', 'brave'))
);

COMMENT ON COLUMN workspace_search.api_key IS
    'Encrypted with the installation secret key; never read back to a screen.';
