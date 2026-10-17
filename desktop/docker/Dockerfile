# Where the desktop app is built and smoke-tested on Linux: Node for npm and
# electron-builder, what Electron's Chromium needs to run, a virtual display for
# it to run on, and librsvg for turning the mark into the icons. There is no Node
# on the development machine, and Electron has no headless mode of its own.
FROM node:22-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb xauth \
        libnss3 libgtk-3-0 libgbm1 libasound2 libxss1 libxtst6 libatk-bridge2.0-0 libdrm2 libxkbcommon0 \
        librsvg2-bin \
        rpm fakeroot dpkg-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /desktop
