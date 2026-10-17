-- A field an Object node names for itself may be an object defined in
-- place, as a saved object's property may since V373: its fields travel
-- beside it as JSON, the same shape `object_property.inline_properties`
-- holds. Null everywhere a field is a scalar or points at a saved object.
ALTER TABLE workflow_node_mapping
    ADD COLUMN field_inline_properties TEXT;
