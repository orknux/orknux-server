#!/bin/sh
# The app's icons, drawn from assets/icon.svg - the interface's own favicon, so
# the window, the tray and the browser tab wear the same mark. Run in the
# desktop image (docker/Dockerfile), which has librsvg; the PNGs are committed.
set -eu
cd "$(dirname "$0")/.."
rsvg-convert -w 512 -h 512 assets/icon.svg -o build/icon.png
rsvg-convert -w 32 -h 32 assets/icon.svg -o resources/tray/tray.png
rsvg-convert -w 64 -h 64 assets/icon.svg -o resources/tray/tray@2x.png
