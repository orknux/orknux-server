-- A property that is an object defined in place, rather than a pointer at a
-- named one: its own fields, as JSON, each of which may be another such
-- object. Null for every property that points or holds a scalar, which is
-- every property that exists. Kept as JSON rather than as rows because a
-- nested field has no identity of its own - it is edited, saved and read as
-- part of the property it belongs to, and a table of them would be a table
-- whose rows nothing ever addresses.
ALTER TABLE object_property ADD COLUMN inline_properties TEXT;
