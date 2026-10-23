-- Whether a sound attached to a chat message - mp3, wav, flac - is sent to this
-- model as an input_audio part of the turn, beside takes_video (V418). Off, so
-- every model that exists refuses a sound in a sentence until somebody says it
-- takes one. Additive: an older jar never reads it.
ALTER TABLE llm_model ADD COLUMN takes_audio BOOLEAN NOT NULL DEFAULT FALSE;

-- midi_read answers a MIDI file kept under a key as text; a built-in in
-- BuiltInTools.GRANTED beside svg_read, marked Always for the agents that
-- predate it, as V419 did svg_read. Additive.
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'midi_read'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'midi_read')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'midi_read');
