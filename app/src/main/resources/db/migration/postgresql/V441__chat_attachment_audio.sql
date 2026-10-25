-- Where a MIDI file attached to a chat is kept rendered as sound, so the chat's
-- Play renders it once rather than on every press. Null until somebody plays
-- it, and for every file that is not MIDI. Additive: an older jar never reads it.
ALTER TABLE chat_attachment ADD COLUMN audio_location VARCHAR(1000);
