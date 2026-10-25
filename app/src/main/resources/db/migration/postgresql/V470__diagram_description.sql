-- What a diagram is for, in a sentence or two: shown on the list, edited in
-- the editor, and read by an agent choosing which diagram to look in. Its
-- length is the installation's (diagram.description.characters). Additive: a
-- nullable column.
ALTER TABLE diagram ADD COLUMN description TEXT;
