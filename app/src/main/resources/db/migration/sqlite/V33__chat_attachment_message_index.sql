-- Which message of its chat a file came with. The postgres copy, V355, carries the reasoning.

ALTER TABLE chat_attachment ADD COLUMN message_index integer;
