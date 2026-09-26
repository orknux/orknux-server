-- The catalogue's copy of a plugin the release now brings. Issue #474.
--
-- V305 gave every existing row built_in = false, which is right for a plugin
-- somebody wrote and uploaded and wrong for the one this release ships: an
-- installation that had already installed PDF from the catalogue would keep
-- that copy for ever and never see the one inside the release, so the feature
-- would work on new installations and quietly do nothing on every existing one.
--
-- So a row that came from the catalogue under a key the release ships is
-- adopted. It is the same plugin from the same author, and the boot writer then
-- keeps it at the shipped version the way it keeps its own. Its `enabled` is
-- untouched, so an installation that switched PDF off stays without it.
--
-- A row somebody uploaded themselves is not adopted: marketplace_key is null
-- for a file or a URL of your own, and that copy stays theirs.

UPDATE plugin
SET built_in = true
WHERE plugin_key = 'pdf'
  AND marketplace_key IS NOT NULL;
