-- The command marker gains an installation default; a workspace's column
-- becomes its override. Issue #402.
--
-- It was NOT NULL with a default of '!'. Null now means "follow the
-- installation", which Admin -> Settings sets. Every workspace that still
-- carries the old default is set to null so it follows the installation;
-- one that deliberately set another marker keeps it.
ALTER TABLE workspace
    ALTER COLUMN command_marker DROP NOT NULL,
    ALTER COLUMN command_marker DROP DEFAULT;

UPDATE workspace SET command_marker = NULL WHERE command_marker = '!';
