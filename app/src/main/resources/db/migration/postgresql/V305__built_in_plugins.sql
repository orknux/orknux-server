-- A plugin the server brings itself. Issue #474.
--
-- The PDF plugin is one file with the writer, the layout, the fonts and the
-- diagram renderer inside it; it declares no libraries and asks for one
-- permission and one capability whose server half is already core. Making a
-- document is something every installation wants, so it ships in the release
-- and is written at boot rather than found in a catalogue and installed.
--
-- The column says which rows the boot writer owns. It only ever writes a row
-- that is missing or still built in, so somebody who uploads their own bundle
-- under the same key takes the row over - the upload clears the flag - and the
-- shipped one stops being written on top of it. Remove is refused for a row
-- this flag is set on, because the next start would write it back; switching it
-- off is how an installation says no to one, and that survives every write.

ALTER TABLE plugin
    ADD COLUMN built_in boolean NOT NULL DEFAULT false;
