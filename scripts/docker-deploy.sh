#!/usr/bin/env sh
# Stands up the docker dependencies and runs the server with the interface in
# it, at http://localhost:8080 - the way both images ship it since 0.9.9.8.
#
# The bundle is built in the interface's container (there is no Node on the
# host) and packaged by -Pwith-ui; -am is not optional, or a change in a module
# is invisible to the app build. ORKNUX_SECRET_KEY must be in the environment.
set -e
cd "$(dirname "$0")/.."

(cd orknux-ui && docker compose run --rm dev sh -c "npm install --no-fund --no-audit && npm run build")
docker compose up -d
./mvnw spring-boot:run -Pwith-ui -pl app -am
