-- A server release fetched from a URL an administrator gives - a company's
-- Artifactory, say. #589.
--
-- A third source beside the official server and an upload, and the address it
-- came from, written down without the credential, query or fragment so nothing
-- that authenticated the download is ever kept. Additive: a wider CHECK and a
-- nullable column, so rollback-floor stays where it is.

ALTER TABLE server_release ADD COLUMN source_url VARCHAR(2000);

ALTER TABLE server_release DROP CONSTRAINT ck_server_release_source;
ALTER TABLE server_release
    ADD CONSTRAINT ck_server_release_source CHECK (source IN ('ORKNUX_AI', 'UPLOAD', 'URL'));
