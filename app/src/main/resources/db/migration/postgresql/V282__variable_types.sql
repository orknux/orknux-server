-- A variable can be a list, or a type a plugin defines. Issue #377.

-- A list of one of the three scalars, kept as a JSON array in the same text
-- column; element_type says what it holds, and is null on anything else.
ALTER TABLE workspace_variable DROP CONSTRAINT ck_workspace_variable_type;
ALTER TABLE workspace_variable
    ADD CONSTRAINT ck_workspace_variable_type CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN', 'LIST'));
ALTER TABLE workspace_variable ADD COLUMN element_type varchar(16);
ALTER TABLE workspace_variable
    ADD CONSTRAINT ck_workspace_variable_element_type
        CHECK (element_type IS NULL OR element_type IN ('STRING', 'NUMBER', 'BOOLEAN'));

-- A type a plugin defines over the base type - slack:SlackUser - and what that
-- type was told, as a JSON object. A Slack user id is a string only some
-- values of are real, and the plugin is the one thing that can say which; the
-- name buys the picker and the check at the moment somebody types a value.
--
-- A name, not a foreign key: the plugin may be switched off or gone by the
-- time the variable is read, and the variable still holds what it held.
ALTER TABLE workspace_variable ADD COLUMN custom_type varchar(120);
ALTER TABLE workspace_variable ADD COLUMN type_arguments text;

-- What the plugin declared, kept beside its objects.
ALTER TABLE plugin ADD COLUMN declared_types text NOT NULL DEFAULT '[]';
