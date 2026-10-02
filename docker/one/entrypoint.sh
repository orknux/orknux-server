#!/bin/sh
#
# What has to be true before an Orknux with nothing supplied can be signed into.
#
# `docker run orknux/orknux-one` is meant to be the whole command. That means
# three things have to be invented here, because nobody was asked for them: an
# encryption key, a first administrator, and somewhere to put the database. The
# first of those is the one that has to be right - see below.
#
# Everything is only ever created, never replaced. A file that is already in the
# data directory is what this installation is, and a start that overwrote one
# would be a start that destroyed something.

set -eu

DATA="${ORKNUX_DATA:-/var/lib/orknux}"

note() { printf '%s\n' "$*"; }
banner() {
    printf '\n================================================================\n'
    printf '%s\n' "$*"
    printf '================================================================\n\n'
}

# ---------------------------------------------------------------------------
# Where the database is.
#
# Read back out of the connection URL rather than assumed, so that overriding
# ORKNUX_DB_URL to point at a Postgres - which this image supports, it simply
# does not run one - does not leave this script guarding a file that is never
# written. The server creates the database but not the directory holding it, and
# says which path is missing rather than failing with a connection error, so the
# directory is made here.
# ---------------------------------------------------------------------------

DB_FILE=""
case "${ORKNUX_DB_URL:-}" in
    jdbc:sqlite:*) DB_FILE="${ORKNUX_DB_URL#jdbc:sqlite:}" ;;
esac

mkdir -p "$DATA"
if [ -n "$DB_FILE" ]; then
    mkdir -p "$(dirname "$DB_FILE")"
fi

# Is this a database that already holds something? The answer decides whether
# anything below may be invented: a key generated beside an existing database is
# a key that cannot read it.
existing_installation() {
    [ -n "$DB_FILE" ] && [ -s "$DB_FILE" ]
}

# ---------------------------------------------------------------------------
# The encryption key. The one thing in this script that can lose data.
#
# Every credential the server is trusted with - model provider keys, Slack
# tokens, MCP secrets - is encrypted with it before it reaches the database.
# Generating a *different* key on the next start does not fail loudly: the server
# boots, reports itself healthy, and every stored credential is unreadable from
# then on, discovered one at a time by whoever pressed Save on them. So the key
# is written into the data directory, beside the database it protects, and the
# two travel together or not at all.
#
# A key supplied in the environment always wins and is never written down - an
# operator who keeps their own is keeping it somewhere better than this.
# ---------------------------------------------------------------------------

KEY_FILE="$DATA/secret.key"

if [ -n "${ORKNUX_SECRET_KEY:-}" ]; then
    note "Using the encryption key from ORKNUX_SECRET_KEY."
elif [ -f "$KEY_FILE" ]; then
    ORKNUX_SECRET_KEY="$(cat "$KEY_FILE")"
    export ORKNUX_SECRET_KEY
    note "Using the encryption key kept at $KEY_FILE."
else
    if existing_installation; then
        # The database survived and the key did not, which is what happens when
        # only part of the data directory was restored, or when the database was
        # bind-mounted from one place and the rest of the directory from another.
        # Generating one here would encrypt new credentials with a key that
        # cannot read the old ones, so the server would come up half working. It
        # still starts - the database is worth more than the credentials - but
        # nobody gets to find this out later.
        banner "There is a database at $DB_FILE but no $KEY_FILE.
A new key is being generated, and every credential stored with the old one is
unreadable from now on. They are not corrupted and they are not recoverable:
they have to be entered again, by hand. Admin -> Doctor lists which.
If the old key still exists somewhere, stop this container, put it back at
$KEY_FILE, and start again before anybody saves anything."
    fi
    ( umask 077; head -c 32 /dev/urandom | base64 | tr -d '\n' > "$KEY_FILE" )
    ORKNUX_SECRET_KEY="$(cat "$KEY_FILE")"
    export ORKNUX_SECRET_KEY
    note "Generated an encryption key and kept it at $KEY_FILE. It belongs with the database; back the two up together."
fi

# ---------------------------------------------------------------------------
# The first administrator.
#
# An account is made by an administrator, or written down when a directory
# vouches for somebody at the door. This image has neither, so without the
# bootstrap variables it would come up with no way in at all.
#
# A generated password rather than a documented one. A default written in a
# README is a password every copy of this image shares and most of them keep,
# which on anything reachable is the same as no password; this one is different
# per installation and is printed where the person who started the container is
# already looking. It is also kept in the data directory, because a password
# shown once and scrolled past is a password somebody has to reinstall to get
# back - and because the server only ever *creates* this account, so a value
# that changed between starts would silently stop matching.
# ---------------------------------------------------------------------------

ADMIN_FILE="$DATA/admin-password"

if [ -n "${ORKNUX_BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
    ORKNUX_BOOTSTRAP_ADMIN_USERNAME="${ORKNUX_BOOTSTRAP_ADMIN_USERNAME:-admin}"
    export ORKNUX_BOOTSTRAP_ADMIN_USERNAME
    note "Seeding the administrator named in ORKNUX_BOOTSTRAP_ADMIN_USERNAME / _PASSWORD."
else
    if [ ! -f "$ADMIN_FILE" ] && ! existing_installation; then
        # Alphanumeric and 24 characters. Long past the twelve the product
        # requires of any password, and nothing in it needs quoting - this gets
        # copied out of a terminal into a form, and a password somebody has to
        # escape is a password they mistype.
        ( umask 077; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24 > "$ADMIN_FILE" )
        FIRST_START=yes
    fi

    if [ -f "$ADMIN_FILE" ]; then
        ORKNUX_BOOTSTRAP_ADMIN_USERNAME="${ORKNUX_BOOTSTRAP_ADMIN_USERNAME:-admin}"
        ORKNUX_BOOTSTRAP_ADMIN_PASSWORD="$(cat "$ADMIN_FILE")"
        export ORKNUX_BOOTSTRAP_ADMIN_USERNAME ORKNUX_BOOTSTRAP_ADMIN_PASSWORD

        if [ "${FIRST_START:-}" = "yes" ]; then
            banner "Sign in with

    username   $ORKNUX_BOOTSTRAP_ADMIN_USERNAME
    password   $ORKNUX_BOOTSTRAP_ADMIN_PASSWORD

on whichever port 8080 was published as. This was generated for this
installation and is kept at $ADMIN_FILE - read it back with
\`docker exec <container> cat $ADMIN_FILE\`.
Change it under the account itself, then delete that file. Nothing here will
change it for you: the server only ever creates this account and never touches
one that exists."
        else
            note "The administrator password from the first start is still at $ADMIN_FILE. Delete it once it has been changed."
        fi
    else
        # No file, and a database that already holds something: the password was
        # changed and the file deleted, exactly as asked. Nothing to seed, and
        # nothing to say about it.
        note "No bootstrap administrator: $ADMIN_FILE is gone and the database is not new."
    fi
fi

# ---------------------------------------------------------------------------
# How people sign in.
#
# There is no directory in this image and no identity provider, so the only
# accounts it has are the ones it holds itself - the generated administrator
# above, and whoever that administrator makes afterwards. INTERNAL is the server
# saying exactly that.
#
# It matters because the default is LDAP, and the default was visibly wrong here
# in two places at once: the sign-in card announced single sign-on and offered a
# directory that is not in this container, and the monitoring screen probed
# localhost:389, failed, and reported the whole server degraded. The generated
# administrator signed in anyway - internal accounts are checked before the
# directory - so the image worked and looked broken, which is a poor first
# impression to make on somebody who has run one command.
#
# Overridable, and only a default. Anybody pointing ORKNUX_LDAP_URLS or the OIDC
# settings at something real - which DOCKERHUB-ONE.md says how to do - sets
# ORKNUX_AUTH_METHOD alongside them, and this leaves it alone.
# ---------------------------------------------------------------------------

ORKNUX_AUTH_METHOD="${ORKNUX_AUTH_METHOD:-INTERNAL}"
export ORKNUX_AUTH_METHOD
if [ "$ORKNUX_AUTH_METHOD" = "INTERNAL" ]; then
    note "Signing in with accounts held by this installation; no directory and no provider."
elif [ "$ORKNUX_AUTH_METHOD" = "NONE" ]; then
    # Louder than the others on purpose: this one was chosen in the environment
    # and it is the only value that opens the container to whoever reaches it.
    note "AUTHENTICATION IS OFF. Nobody signs in, and anyone who can reach this port administers it."
else
    note "Signing in with $ORKNUX_AUTH_METHOD, as set in the environment."
fi

note "Orknux is starting on ${ORKNUX_PORT:-8080}, the interface with it."

# `exec` into the start loop (#584), which becomes PID 1: it chooses a jar - an
# update an administrator installed, or this image's own - runs it, starts it
# again when it asks, and hands `docker stop`'s SIGTERM on to the JVM so the
# shutdown is graceful rather than a kill at the end of the grace period.
exec /usr/local/bin/orknux-run
