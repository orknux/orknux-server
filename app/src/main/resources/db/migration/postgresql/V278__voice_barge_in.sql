-- How long somebody has to keep talking over the answer before it stops.

-- Voice mode holds the microphone open while the answer is read aloud, so
-- anything said over it was already heard and queued as the next turn - but the
-- answer went on to the end regardless. Which is the one thing a person cannot
-- do in a conversation: say "no, not that" and be listened to. They had to reach
-- for the panel and press, in the one mode whose whole point is not touching
-- anything.

-- A hold rather than a level, because the failure to avoid is not a quiet voice
-- but a short noise: a cough, a door, or this application's own voice getting
-- past the echo cancellation. Somebody interrupting keeps talking; none of those
-- do.

-- Null is the interface's own number. Zero turns it off, which is the setting an
-- installation wants when the room is loud enough or the echo cancellation poor
-- enough that the answer keeps stopping on nothing.
ALTER TABLE workspace ADD COLUMN voice_barge_in_ms integer;
