---
name: Composing music (MIDI)
description: How to compose a melody, a song or a piece of music as a MIDI file - planning the key, tempo, form and instruments, writing it note by note in sections, checking it, and rendering it to MP3 or FLAC to hand over.
---

# Composing music (MIDI)

## Objective

Write music somebody asked for - a melody, a jingle, a song, a loop - as a MIDI
file that sounds like what they described, and hand it over as a file they can
play: an MP3 or a FLAC, with the MIDI beside it where they want to edit it.

You cannot hear what you write. Plan it, write it in sections you can check,
and read it back before you render it.

## The tools

- `{{tool:midi.write}}` writes the notes into a MIDI file and answers its key.
- `{{tool:midi.read}}` reads a MIDI file back as text: tracks, tempo, every note.
- `{{tool:midi.render}}` plays a MIDI file into sound - `mp3` (the default),
  `flac` or `wav` - and answers the audio's key.
- `{{tool:save_artifact}}` saves a file by its key and answers a link.

## 1. Plan before writing a note

Decide, and say in a line or two if the person may want to steer:

- **Key** - C major or A minor are plain; D major and E minor bright; F major
  and D minor warm. Stay in it unless the piece needs a turn.
- **Tempo** - 60-80 beats a minute is slow, 90-110 relaxed, 120-140 driving.
- **Time signature** - 4/4 for most pop, rock and dance; 3/4 for a waltz; 6/8
  for a lilt.
- **Form** - sections in bars: an 8-bar intro, 16-bar verse, 8-bar chorus is a
  song; 4 or 8 bars repeated is a loop. A bar of 4/4 is 4 beats, so bar 9 starts
  on beat 32.
- **Chords** - a progression per section, one chord a bar is plenty:
  I-V-vi-IV (C G Am F), vi-IV-I-V (Am F C G), I-vi-IV-V (C Am F G), ii-V-I.
- **Instruments** - General MIDI programs, by number or name: 0 Acoustic Grand
  Piano, 4 Electric Piano 1, 24 Acoustic Guitar (nylon), 25 Acoustic Guitar
  (steel), 33 Electric Bass (finger), 40 Violin, 42 Cello, 48 String Ensemble 1,
  56 Trumpet, 65 Alto Sax, 73 Flute, 80 Lead 1 (square), 89 Pad 2 (warm). Three
  or four parts is a band; more is mud.
- **Drums** - a track with `"drums": true` is General MIDI's channel 10; its
  pitches are drums: `kick`, `snare`, `closed hihat`, `open hihat`, `crash`,
  `ride`, `clap`, `low tom`, `mid tom`, `high tom`, or note numbers 35-81.

## 2. Write it so it sounds like music

Pitches are names like `C4` (middle C, 60), `F#3`, `Bb2`, or numbers.
`start` and `duration` are in beats from the start of the call's section.
Velocity is 1-127: 50-70 soft, 80-100 normal, 110+ accents.

- **Chords** are notes that start together: give `pitch` a list,
  `["C4", "E4", "G4"]`, in the octave around middle C (C3-C5). Hold them for the
  bar, or strum them as repeated shorter chords.
- **The bass** plays the chord's root an octave or two down (C2-C3), on beat 1
  of every bar and often on beat 3; walk to the next root on the last beat.
- **The melody** sits above the chords (C4-C6), mostly in steps of the scale
  with the odd leap, lands on chord notes on strong beats, repeats and varies a
  short idea, and leaves gaps to breathe. Give it a little more velocity than
  the accompaniment.
- **Drums in 4/4**: kick on beats 1 and 3, snare on 2 and 4, closed hihat on
  every half beat; a crash on beat 1 of a new section.
- **Durations** a hair shorter than the gap to the next note (0.9 rather than 1)
  keep a line from smearing; let the last chord ring for a whole bar or two.

## 3. Write it in sections

A whole song in one call is cut off at your output limit. Write the first
section with `{{tool:midi.write}}`, then each next one with:

- `{{param:midi.write.append}}` set to the key the last call answered - every call answers a new key;
  always pass the newest,
- `{{param:midi.write.at}}` set to the beat the section starts on, so its notes start from 0,
- the same track names, so the notes go into the same tracks.

Name tracks plainly and keep them: `Melody`, `Chords`, `Bass`, `Drums`.

## 4. Check it

Call `{{tool:midi.read}}` with the newest key. Look at what it says, not at what
you meant: the length in beats and seconds, each track's channel and program,
that the bass is below the chords and the melody above, and that no section is
missing or doubled. Fix a mistake by writing the file again rather than piling
more notes onto it.

## 5. Render and hand it over

- Call `{{tool:midi.render}}` with the key: `{{param:midi.render.format}}` `mp3` for listening and
  sending, `flac` where quality matters. The answer says how long it is and
  which instruments' sounds played it - say that plainly if the installation
  has no SoundFont set, rather than apologising for the sound.
- Pass the audio's key to `{{tool:save_artifact}}` with a name like
  `sunrise.mp3` for a link, or to a tool that uploads or sends a file where you
  have one. Save the `.mid` as well when somebody may want to edit it.
- Answer with the link and a sentence on what you wrote: key, tempo, form and
  instruments.

## What does not work

- **Typing audio or MIDI bytes out.** Pass keys.
- **Claiming how it sounds.** You have not heard it; say what you wrote.
- **More than 15 melodic tracks.** MIDI has 16 channels and drums take one.
