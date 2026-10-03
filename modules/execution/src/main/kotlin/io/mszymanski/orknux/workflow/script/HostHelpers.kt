package io.mszymanski.orknux.workflow.script

/**
 * The JavaScript both sandboxes hand their guests, written once.
 *
 * A plugin and a workflow function reach the server through the same door and
 * get the same helpers, but the helpers used to be written out twice - once in
 * [ScriptRunner] and once in [PluginRunner] - and the copies had already drifted:
 * when functions were given HTTP on 2026-09-06 the plugin side kept an older
 * shape with no `post` and no parsed `json`, so the same call written in the two
 * places answered differently. Two copies of an API is two APIs.
 *
 * What differs between them is one sentence - what to say when the door is not
 * there - and that is a parameter rather than a reason to write it all again.
 */
internal object HostHelpers {

    /**
     * The levels, as the guest compares them, and what a setting names.
     *
     * `off` is a number nothing reaches rather than a branch of its own: a
     * threshold is one comparison wherever it is written, and a special case
     * would be a second way for this to be wrong.
     */
    val LEVELS = mapOf("debug" to 10, "info" to 20, "warn" to 30, "error" to 40, "off" to 99)

    /** The number a named level compares as, or `info` for a name nobody has. */
    fun threshold(named: String): Int = LEVELS[named.trim().lowercase()] ?: LEVELS.getValue("info")

    /**
     * `orknux.log`, as both a plugin and a function see it.
     *
     * A sandbox with no `console` is a sandbox nobody can debug. `print` and
     * `load` are turned off by name and are staying off - they write to the
     * server's own streams and take whatever they are given - so this is the way
     * to say something: the line is built in the guest, crosses as text, and the
     * server decides where it goes.
     *
     * **The level is decided in here, before anything crosses.** A `debug` line
     * on an installation logging at `info` costs one comparison and is dropped
     * where it was written, so a function may leave its tracing in and pay for
     * it only when somebody turns the level down.
     *
     * Arguments are joined with a space, and anything that is not a string is
     * JSON - which is what somebody logging an object wants, and the alternative
     * is `[object Object]`.
     *
     * @param threshold the lowest level that is kept, as its number.
     */
    fun log(threshold: Int): String = """
        log: (function () {
          const levels = { debug: 10, info: 20, warn: 30, error: 40 };
          const kept = $threshold;

          function say(level, args) {
            if (levels[level] < kept) return;
            const line = Array.prototype.map
              .call(args, function (one) {
                if (typeof one === 'string') return one;
                try {
                  return JSON.stringify(one);
                } catch (ignored) {
                  // A cycle, or something JSON cannot hold. Saying what it was
                  // beats dropping the line somebody wrote to find a problem.
                  return String(one);
                }
              })
              .join(' ');
            globalThis.__orknuxLog(level, line);
          }

          return {
            debug: function () { say('debug', arguments); },
            info: function () { say('info', arguments); },
            warn: function () { say('warn', arguments); },
            error: function () { say('error', arguments); },
          };
        })(),
    """.trimIndent()

    /**
     * `orknux.slack`, as both a plugin and a function see it.
     *
     * Read a thread, post a message, add a reaction - each hands the host a
     * connection and gets an answer back as data, the connection read out of an
     * object or taken as a bare id so `trigger.connection` and a number both
     * work. Written here once for the reason http and log are: the two runners
     * had their own copy of the thread reader and were one edit from disagreeing.
     *
     * Each door says its own thing when it is not wired, because the reason
     * differs: a plugin was not granted the capability, a function is on an
     * installation that answers none. Both are data, like every refusal here.
     */
    fun slack(
        readAbsent: String,
        postAbsent: String,
        reactAbsent: String,
        messageAbsent: String,
        userAbsent: String,
        mentionAbsent: String,
        searchAbsent: String,
        suggestAbsent: String,
    ): String = """
        slack: {
          thread(connection, channel, threadTs, limit) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_read_thread === undefined) {
              return { error: '$readAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_read_thread(JSON.stringify([id, channel, threadTs, limit ?? null])));
          },
          post(connection, channel, text, threadTs) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_post_message === undefined) {
              return { error: '$postAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_post_message(JSON.stringify([id, channel, text, threadTs ?? null])));
          },
          react(connection, channel, ts, emoji) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_add_reaction === undefined) {
              return { error: '$reactAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_add_reaction(JSON.stringify([id, channel, ts, emoji])));
          },
          message(connection, link) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_read_message === undefined) {
              return { error: '$messageAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_read_message(JSON.stringify([id, link])));
          },
          user(connection, userId) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_read_user === undefined) {
              return { error: '$userAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_read_user(JSON.stringify([id, userId])));
          },
          mention(connection, name) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_mention === undefined) {
              return { error: '$mentionAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_mention(JSON.stringify([id, name])));
          },
          search(connection, query, limit) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_search === undefined) {
              return { error: '$searchAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_search(JSON.stringify([id, query, limit ?? null])));
          },
          suggest(connection, typed, kind, limit) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_suggest === undefined) {
              return { error: '$suggestAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_suggest(JSON.stringify([id, typed, kind ?? null, limit ?? null])));
          },
        },
    """.trimIndent()

    /**
     * `orknux.connections`, as both a plugin and a function see it. Issue #597.
     *
     * `query(filter)` answers `{ connections: [...] }` - the workspace's own,
     * each a handle the Slack doors take as it is - or `{ error }`. The filter
     * is an object so that a later field is a new key rather than a new call,
     * and only the keys this knows are copied across: a guest handing over an
     * object with a getter or a cycle in it gets the two strings it meant and
     * nothing else crosses.
     *
     * @param absent what to say when this door is not wired: a plugin was not
     *   granted it, or a function is on an installation that answers none.
     */
    fun connections(absent: String): String = """
        connections: {
          query(filter) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.connections_query === undefined) {
              return { error: '$absent' };
            }
            const given = filter === undefined || filter === null ? {} : filter;
            if (typeof given !== 'object' || Array.isArray(given)) {
              return { error: 'connections.query takes an object: { type, name }, both optional' };
            }
            const asked = {};
            if (given.type !== undefined && given.type !== null) asked.type = String(given.type);
            if (given.name !== undefined && given.name !== null) asked.name = String(given.name);
            return JSON.parse(host.connections_query(JSON.stringify(asked)));
          },
        },
    """.trimIndent()

    /**
     * `orknux.http`, as both a plugin and a function see it.
     *
     * The guest never holds a socket: it hands over a URL and gets an answer
     * back as data, which is what keeps this a door rather than a network.
     * Where it may get to is the installation's proxy rules - the same rules a
     * Slack call and an MCP call obey - which this cannot see and cannot argue
     * with.
     *
     * Answers `{ status, headers, body }` with `json` beside `body` where the
     * reply parsed, or `{ error }` saying why not. A refusal is data, like every
     * other answer here: a condition that cannot reach a service has to be able
     * to decide rather than throw and become undecidable.
     *
     * @param absent what to say when this installation has no door wired.
     */
    fun http(absent: String): String = """
        http: {
          request(what) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.network_request === undefined) {
              return { error: '$absent' };
            }
            const asked = what === null || typeof what !== 'object' ? { url: what } : what;

            const headers = Object.assign({}, asked.headers ?? {});
            let body = asked.body ?? null;
            /*
             * An object body is JSON, and says so.
             *
             * Stringifying it by hand is the easy half; the header is the half
             * people forget, and a service answering 415 to a body that looks
             * perfectly good is a bad afternoon. A string body is passed through
             * untouched - somebody sending form-encoded text meant it.
             */
            if (body !== null && typeof body === 'object') {
              body = JSON.stringify(body);
              const named = Object.keys(headers).some((name) => name.toLowerCase() === 'content-type');
              if (!named) headers['content-type'] = 'application/json';
            }

            /*
             * Binary crosses as base64, because text is all that crosses.
             *
             * `bodyBase64` says the body is base64 and the bytes it decodes to
             * are what is sent; `binary` asks for the answer's bytes back the
             * same way, as `base64` beside `contentType` and `size`. Neither
             * set, everything is text, exactly as it always was.
             */
            const options = {};
            if (asked.bodyBase64 !== undefined && asked.bodyBase64 !== null) {
              body = asked.bodyBase64;
              options.sendBase64 = true;
            }
            if (asked.binary === true) options.wantBytes = true;

            const answer = JSON.parse(
              host.network_request(
                JSON.stringify([
                  asked.url ?? null,
                  (asked.method ?? 'GET').toUpperCase(),
                  headers,
                  body,
                  Object.keys(options).length === 0 ? null : options,
                ]),
              ),
            );

            /*
             * `json` beside `body`, never instead of it.
             *
             * Nearly every service answers JSON and nearly every caller wants it
             * parsed, so parsing it here saves the same three lines being written
             * every time - and a reply that is not JSON, or is JSON the service
             * got wrong, simply has no `json` rather than throwing. `body` is
             * always the text that arrived, so nothing is hidden by this.
             */
            if (answer.error === undefined && typeof answer.body === 'string') {
              try {
                answer.json = JSON.parse(answer.body);
              } catch (ignored) {
                // Not JSON. `body` still is what it is.
              }
            }
            return answer;
          },

          /** The two nearly everybody wants, spelled out. */
          get(url, headers) {
            return globalThis.orknux.http.request({ url, method: 'GET', headers });
          },

          post(url, body, headers) {
            return globalThis.orknux.http.request({ url, method: 'POST', body, headers });
          },

          /**
           * Sends bytes - a file - given as base64, which is the one shape
           * binary has on this side of the sandbox. The content type is a
           * parameter because a server receiving a file cares what it is;
           * left out, it is sent as an octet stream.
           */
          upload(url, base64, contentType, headers) {
            const named = Object.assign({}, headers ?? {});
            const has = Object.keys(named).some((name) => name.toLowerCase() === 'content-type');
            if (!has) named['content-type'] = contentType ?? 'application/octet-stream';
            return globalThis.orknux.http.request({ url, method: 'POST', bodyBase64: base64, headers: named });
          },

          /**
           * Fetches binary content - an image, a PDF - and answers it as
           * `base64` beside `contentType` and `size`, instead of a string
           * the bytes were never going to survive being read as.
           */
          download(url, headers) {
            return globalThis.orknux.http.request({ url, method: 'GET', headers, binary: true });
          },
        },
    """.trimIndent()

    /**
     * `orknux.session.store`, the AI session's own scratchpad.
     *
     * What one tool call puts, a later one gets, for as long as the session
     * lives - and no other session ever sees it; see [SessionScratch]. The
     * doors are only bound where the call belongs to a session, so a function
     * tried from its editor page or a webhook script says so in a sentence
     * instead of quietly writing into nowhere.
     */
    /**
     * What a plugin can compute, since the engine cannot.
     *
     * Ungranted, beside the log and the store: a digest reaches nothing, sends
     * nothing and learns nothing. Everything crosses as base64 because only
     * text crosses, and every call takes `{ base64 }`, `{ text }` or a bare
     * string - so a plugin holding a string need not ask for TEXT_ENCODING
     * just to hash it.
     *
     * Every call answers an object: `{ base64 }`, `{ equal }` or `{ error }`,
     * the way the other helpers do. A refusal is data a plugin can act on
     * rather than a throw it has to catch.
     *
     * What crosses the doors is not JSON. Each answers `ok:<base64>` or
     * `no:<sentence>` - two shapes, split on the first colon - because the
     * alternative was a JSON parser on the far side of a boundary that exists
     * to keep things simple.
     */
    fun crypto(): String = """
        crypto: {
          /** Whichever shape was handed over, as the pair a door takes. */
          __bytes(given) {
            if (given === null || given === undefined) return null;
            if (typeof given === 'string') return ['text', given];
            if (typeof given !== 'object') return null;
            if (typeof given.base64 === 'string') return ['base64', given.base64];
            if (typeof given.text === 'string') return ['text', given.text];
            return null;
          },

          /** `ok:...` or `no:...`, as the object a plugin reads. */
          __read(answer, key) {
            if (typeof answer !== 'string') return { error: 'the crypto helper said nothing' };
            const at = answer.indexOf(':');
            const said = answer.slice(0, at);
            const rest = answer.slice(at + 1);
            if (said === 'no') return { error: rest };
            if (key === 'equal') return { equal: rest === 'true' };
            if (key === 'text') return { text: rest };
            return { base64: rest };
          },

          hash(algorithm, input) {
            const door = globalThis.__orknuxCryptoHash;
            if (door === undefined) return { error: 'this server has no crypto helper' };
            const held = this.__bytes(input);
            if (held === null) return { error: 'input has to be { base64 }, { text } or a string' };
            return this.__read(door(String(algorithm), held[0], held[1]));
          },

          hmac(algorithm, key, input) {
            const door = globalThis.__orknuxCryptoHmac;
            if (door === undefined) return { error: 'this server has no crypto helper' };
            const theKey = this.__bytes(key);
            const held = this.__bytes(input);
            if (theKey === null) return { error: 'key has to be { base64 }, { text } or a string' };
            if (held === null) return { error: 'input has to be { base64 }, { text } or a string' };
            return this.__read(door(String(algorithm), theKey[0], theKey[1], held[0], held[1]));
          },

          pbkdf2(algorithm, password, salt, iterations, length) {
            const door = globalThis.__orknuxCryptoPbkdf2;
            if (door === undefined) return { error: 'this server has no crypto helper' };
            const pw = this.__bytes(password);
            const theSalt = this.__bytes(salt);
            if (pw === null) return { error: 'password has to be { base64 }, { text } or a string' };
            if (theSalt === null) return { error: 'salt has to be { base64 }, { text } or a string' };
            return this.__read(
              door(String(algorithm), pw[0], pw[1], theSalt[0], theSalt[1], Number(iterations), Number(length)),
            );
          },

          random(bytes) {
            const door = globalThis.__orknuxCryptoRandom;
            if (door === undefined) return { error: 'this server has no crypto helper' };
            return this.__read(door(Number(bytes)));
          },

          /**
           * Whether two byte strings are equal, in time that does not depend
           * on where they differ. Comparing a signature with === leaks its
           * prefix through how long the comparison took, and a constant-time
           * comparison written here stops being one once a JIT has seen it.
           */
          timingSafeEqual(a, b) {
            const door = globalThis.__orknuxCryptoEqual;
            if (door === undefined) return { error: 'this server has no crypto helper' };
            const left = this.__bytes(a);
            const right = this.__bytes(b);
            if (left === null || right === null) {
              return { error: 'both sides have to be { base64 }, { text } or a string' };
            }
            return this.__read(door(left[0], left[1], right[0], right[1]), 'equal');
          },
        },
    """.trimIndent()

    /**
     * Turning bytes into text and back, which is not cryptography.
     *
     * It lived under `crypto` while that was the only helper that needed it,
     * which put base64 - a way of writing bytes down - beside digests and key
     * derivation. A plugin encoding a payload is not doing cryptography, and a
     * name is where somebody looks first.
     *
     * Here at all because without it the sandbox cannot do this: there is no
     * `TextEncoder` unless somebody granted TEXT_ENCODING, so a plugin holding
     * a string has no way to make bytes of it and no way to read bytes back.
     */
    /**
     * Drawing an SVG, which the sandbox cannot do for itself.
     *
     * There is no WebAssembly in this engine and no rasteriser in the language,
     * so a plugin holding a diagram has no way to turn it into a picture - the
     * work is the server's, and this is how it is asked for.
     *
     * The answer is base64 because bytes are not JSON and JSON is all that
     * crosses a door. That is not the failure the model kept hitting: this
     * base64 is handed straight to whatever takes bytes, inside the sandbox,
     * and never passes through anything that has to retype it.
     */
    fun render(absent: String, absentPdf: String): String = """
        render: {
          /**
           * An SVG drawn as a PNG.
           *
           * @param svg the markup itself, not base64 and not a url.
           * @param width how wide the picture should be in pixels, or left out
           *   for the size the document declares.
           * @returns `{ base64, bytes }`, or `{ error }` saying what was wrong.
           */
          pngFromSvg(svg, width) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.render_png === undefined) {
              return { error: '$absent' };
            }
            if (typeof svg !== 'string') return { error: 'the svg has to be the markup, as a string' };

            return JSON.parse(host.render_png(JSON.stringify([svg, width ?? null])));
          },

          /**
           * One page of a PDF, drawn as a PNG.
           *
           * What a plugin that just made a document uses to look at what it
           * actually produced - and what lets an agent check its own work
           * rather than describing what it meant to write.
           *
           * @param pdf the document as base64, which is the shape a plugin
           *   already holds it in.
           * @param page which page, counting from one. Left out for the first.
           * @param width how wide the picture should be in pixels, or left out
           *   for something a screen can read.
           * @returns `{ base64, bytes, width, height, pages }`, or `{ error }`
           *   saying what was wrong.
           */
          pngFromPdf(pdf, page, width) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.render_pdf === undefined) {
              return { error: '$absentPdf' };
            }
            if (typeof pdf !== 'string') return { error: 'the pdf has to be base64, as a string' };

            return JSON.parse(host.render_pdf(JSON.stringify([pdf, page ?? null, width ?? null])));
          },

          /**
           * What a PDF *says*, as HTML.
           *
           * The other question about a document: `pngFromPdf` is for looking
           * at a page - is the table cut in half, did the diagram land - and
           * this is for reading it. Reading a thousand words back out of a
           * picture costs a vision model a thousand words of tokens and some
           * guessing; the text is already in the file.
           *
           * Text in reading order, a paragraph per block and a `<section>` per
           * page. Not the page's design: columns and tables are flattened into
           * the order they are read in. Where the layout is the question, draw
           * the page.
           *
           * @param pdf the document as base64.
           * @param from the first page to read, counting from one. Left out
           *   starts at the beginning.
           * @param to the last page to read. Left out reads to the end - and a
           *   long document is refused by size, with the number in the
           *   sentence, so a range is the answer to that.
           * @returns `{ html, pages, from, to, characters }`, or `{ error }`
           *   saying what was wrong.
           */
          htmlFromPdf(pdf, from, to) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.render_pdf === undefined) {
              return { error: '$absentPdf' };
            }
            if (typeof pdf !== 'string') return { error: 'the pdf has to be base64, as a string' };

            // An object rather than a third positional array, so the door can
            // tell the two calls apart: they share one grant because they share
            // one parser, which is what the grant is about.
            return JSON.parse(
              host.render_pdf(JSON.stringify({ op: 'html', pdf: pdf, from: from ?? null, to: to ?? null })),
            );
          },
        },
    """.trimIndent()

    fun encoding(): String = """
        encoding: {
          /** Whichever shape was handed over, as the pair a door takes. */
          __bytes(given) {
            if (given === null || given === undefined) return null;
            if (typeof given === 'string') return ['text', given];
            if (typeof given !== 'object') return null;
            if (typeof given.base64 === 'string') return ['base64', given.base64];
            if (typeof given.text === 'string') return ['text', given.text];
            return null;
          },

          /** Text, or bytes, as the base64 that writes them down. */
          encodeBase64(input) {
            const door = globalThis.__orknuxCryptoEncode;
            if (door === undefined) return { error: 'this server has no encoding helper' };
            const held = this.__bytes(input);
            if (held === null) return { error: 'input has to be { base64 }, { text } or a string' };
            const answer = door(held[0], held[1]);
            const at = answer.indexOf(':');
            return answer.slice(0, at) === 'no'
              ? { error: answer.slice(at + 1) }
              : { base64: answer.slice(at + 1) };
          },

          /** Base64 as the text it spells; a refusal where it spells none. */
          decodeBase64(base64) {
            const door = globalThis.__orknuxCryptoDecode;
            if (door === undefined) return { error: 'this server has no encoding helper' };
            if (typeof base64 !== 'string') return { error: 'base64 has to be a string' };
            const answer = door(base64);
            const at = answer.indexOf(':');
            return answer.slice(0, at) === 'no'
              ? { error: answer.slice(at + 1) }
              : { text: answer.slice(at + 1) };
          },
        },
    """.trimIndent()

    fun sessionStore(): String = """
        session: {
          store: {
            /**
             * Stores one value under a key, replacing what was there. The value
             * makes the trip as JSON, so what comes back out is a copy - and
             * anything JSON cannot say (a function, undefined) does not survive.
             */
            put(key, value, kind) {
              const door = globalThis.__orknuxStorePut;
              if (door === undefined) {
                return { error: 'there is no session store here: only a call made inside an AI session carries one' };
              }
              const type = kind && typeof kind.contentType === 'string' ? kind.contentType : '';
              const binary = kind && typeof kind.binary === 'boolean' ? (kind.binary ? '1' : '0') : null;
              const refused = door(String(key), JSON.stringify(value === undefined ? null : value), type, binary);
              return refused === null ? { ok: true } : { error: refused };
            },

            /**
             * What the key was recorded as holding: { contentType, binary },
             * where binary means the value is base64 bytes. Null where nothing
             * was said, or nothing is kept - an older server says nothing at
             * all. Issue #559.
             */
            kind(key) {
              const door = globalThis.__orknuxStoreKind;
              if (door === undefined) return null;
              const said = door(String(key));
              if (said === null || said === undefined) return null;
              const at = String(said).lastIndexOf('\n');
              const type = String(said).slice(0, at);
              return { contentType: type === '' ? null : type, binary: String(said).slice(at + 1) === '1' };
            },

            /** What the key holds, parsed, or null where nothing does. */
            get(key) {
              const door = globalThis.__orknuxStoreGet;
              if (door === undefined) return null;
              const held = door(String(key));
              return held === null ? null : JSON.parse(held);
            },

            /**
             * Removes what the key holds, if anything. Idempotent - a key that
             * holds nothing is left as it was. Answers { ok: true }, or an error
             * where there is no store here. Issue #418.
             */
            unset(key) {
              const door = globalThis.__orknuxStoreUnset;
              if (door === undefined) {
                return { error: 'there is no session store here: only a call made inside an AI session carries one' };
              }
              door(String(key));
              return { ok: true };
            },
          },
        },
    """.trimIndent()

    /**
     * `orknux.scratchpad`, the AI session's working files.
     *
     * A scratchpad is a document kept for the life of the session - the same
     * files the session's own agent reaches through its tools, so a plugin or a
     * function running inside a session works on the very same ones. One door
     * carries every operation as JSON in and JSON out, for the reason the store
     * keeps its doors few; the named methods here are what a script actually
     * calls. Every one answers an object - `{ ok: ... }` or `{ error: '...' }` -
     * the way the other helpers do, so a refusal is data rather than a throw.
     *
     * Bound only where the call belongs to a session; elsewhere every method
     * says there are no scratchpads here instead of writing into nowhere.
     */
    /**
     * `orknux.validate`: whether a document is the thing it claims to be.
     * Issue #416.
     *
     * Four named functions rather than one `check(format, text)`, because the
     * format is a fact about the call and not an argument somebody should be
     * able to misspell: `orknux.validate.json(text)` cannot be asked for a
     * format that does not exist.
     */
    fun validation(): String = """
        validate: {
          __call(format, text) {
            const door = globalThis.__orknuxValidate;
            if (door === undefined) {
              return { error: 'validation is not available here' };
            }
            const answer = door(JSON.stringify({ format: format, text: String(text ?? '') }));
            try {
              return JSON.parse(answer);
            } catch (error) {
              return { error: 'the validator said nothing' };
            }
          },
          /** True or false, for the common case: `if (!orknux.validate.isJson(text))`. */
          isJson(text) { return this.json(text).valid === true; },
          isYaml(text) { return this.yaml(text).valid === true; },
          isHtml(text) { return this.html(text).valid === true; },
          isMarkdown(text) { return this.markdown(text).valid === true; },
          /** `{ valid }`, or `{ valid: false, problems: [{ line, column, message }] }`. */
          json(text) { return this.__call('json', text); },
          yaml(text) { return this.__call('yaml', text); },
          html(text) { return this.__call('html', text); },
          markdown(text) { return this.__call('markdown', text); },
        },
    """.trimIndent()

    fun scratchpads(): String = """
        scratchpad: {
          /** The one door, request and answer both JSON. */
          __call(request) {
            const door = globalThis.__orknuxScratchpad;
            if (door === undefined) {
              return { error: 'there are no scratchpads here: only a call made inside an AI session carries them' };
            }
            const answer = door(JSON.stringify(request));
            try {
              return JSON.parse(answer);
            } catch (error) {
              return { error: 'the scratchpad helper said nothing' };
            }
          },

          /** The session's scratchpads: name, what each is for, its size, whether it is shared. */
          list() {
            return this.__call({ op: 'list' });
          },

          /** A scratchpad's content, or a fragment when from and length are given. */
          read(name, from, length) {
            return this.__call({ op: 'read', name: String(name), from: from, length: length });
          },

          /** Creates a scratchpad or replaces the whole of one; description is optional. */
          write(name, content, description) {
            return this.__call({
              op: 'write',
              name: String(name),
              content: content === undefined || content === null ? '' : String(content),
              description: description === undefined ? null : description,
            });
          },

          /** Adds text to the end of a scratchpad. */
          append(name, text) {
            return this.__call({ op: 'append', name: String(name), text: String(text) });
          },

          /** Replaces one occurrence of a piece of text inside a scratchpad. */
          replace(name, oldText, newText) {
            return this.__call({ op: 'replace', name: String(name), old: String(oldText), new: String(newText) });
          },

          /** Sets what a scratchpad is for. */
          describe(name, description) {
            return this.__call({ op: 'describe', name: String(name), description: description === undefined ? null : description });
          },

          /** Lets the sessions started under this one read and add to a scratchpad, or stops them. */
          share(name, shared) {
            return this.__call({ op: 'share', name: String(name), shared: shared === undefined ? true : !!shared });
          },

          /** Where a piece of text appears across the session's scratchpads. */
          search(query) {
            return this.__call({ op: 'search', query: String(query) });
          },

          /** Removes a scratchpad. */
          remove(name) {
            return this.__call({ op: 'delete', name: String(name) });
          },
        },
    """.trimIndent()
}

/**
 * Which of this installation's things a script call belongs to, for the log.
 *
 * A line saying `isFirstSlackMessage: no thread` is the beginning of a question
 * rather than an answer: on a server running many workflows at once the next
 * thing anybody asks is *which run*, and matching on the clock is what this
 * exists to save. Everything here is optional because not every caller has it —
 * a function being test-run from the editor belongs to no execution, and a
 * webhook's authenticator to no workflow — and what is absent is left out
 * rather than printed as a question mark, so a line carries only what is true.
 */
data class ScriptOrigin(
    val functionId: Long? = null,
    val workflowId: Long? = null,
    val executionId: Long? = null,
) {

    /** `[workspace 9 function 448 execution 2682]`, with the workspace the run's. */
    fun said(workspace: Long?): String {
        val parts = buildList {
            workspace?.let { add("workspace $it") }
            functionId?.let { add("function $it") }
            workflowId?.let { add("workflow $it") }
            executionId?.let { add("execution $it") }
        }
        return if (parts.isEmpty()) "[script]" else parts.joinToString(" ", "[", "]")
    }
}
