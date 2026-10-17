-- See the Postgres V373: a property that is an object defined in place, its fields as JSON.
ALTER TABLE object_property ADD COLUMN inline_properties TEXT;
