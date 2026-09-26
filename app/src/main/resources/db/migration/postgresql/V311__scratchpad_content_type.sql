-- A scratchpad can hold a picture. Issue #490.
--
-- An agent building a report had its text in pads and its pictures in the
-- session store, so the one place meant to be the session's working files held
-- half of them. The content column is text, so the bytes ride as base64 and this
-- says so - `image/png`, `application/pdf` - with null meaning a text file,
-- which is every pad written before today.
--
-- The type rather than a flag, because everything downstream wants it: the zip
-- names the file, the page picks the tag, an upload sets a header.

ALTER TABLE session_scratchpad
    ADD COLUMN content_type varchar(120);
