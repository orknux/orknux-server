-- Which time zone one person reads times in, as an IANA name such as
-- Europe/Warsaw. On the person, for the reason the language is: it follows
-- them between machines, and a borrowed browser's clock is not their choice.
-- Null means they have not chosen, and the browser's own zone is used.
ALTER TABLE app_user ADD COLUMN time_zone VARCHAR(64);
