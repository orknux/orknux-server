-- The Charts plugin ships with the release too. Issue #484.
--
-- The same reasoning as the PDF bundle in V304 and V306: drawing the shape of a
-- number is something every installation wants, the bundle is 48 KB with its
-- renderer inside it, and nothing about it reaches the network. It is written at
-- boot by the same writer, from resources/plugins/charts.
--
-- And a catalogue-installed copy is adopted, so an installation that already had
-- Charts sees the shipped one rather than keeping a copy nobody updates. A
-- bundle somebody uploaded themselves is left alone: marketplace_key is null for
-- a file or a URL of your own, and that row stays theirs.

UPDATE plugin
SET built_in = true
WHERE plugin_key = 'charts'
  AND marketplace_key IS NOT NULL;
