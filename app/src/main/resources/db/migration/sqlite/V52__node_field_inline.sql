-- See V374__node_field_inline.sql on Postgres.
ALTER TABLE workflow_node_mapping
    ADD COLUMN field_inline_properties TEXT;
