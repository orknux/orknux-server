-- Where a MIDI attachment is kept rendered as sound. The postgres copy, V440, carries the reasoning.

ALTER TABLE chat_attachment ADD COLUMN audio_location varchar(1000);
