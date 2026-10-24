package io.mszymanski.orknux.server.workspace

/**
 * Takes the credentials out of an audit line before it is stored.
 *
 * The audit log keeps what an agent ran, verbatim, and a perfectly ordinary
 * command carries a live credential in it: `git push
 * https://alice:s3cr3t@github.com/acme/repo.git`, `curl -u alice:s3cr3t`,
 * `export GITHUB_TOKEN=…`. Stored as typed, that password is then readable by
 * everyone who can open the admin audit page, everyone who can reach the
 * database, and everyone who has ever taken a backup of it. A secret that has
 * been in a backup has to be treated as disclosed, so this is not a display
 * problem and cannot be fixed on the way out — it is fixed here, before
 * [WorkspaceAuditRecorder] saves the row, so the plaintext never reaches the
 * table at all.
 *
 * **What replaces it.** The fixed marker [MARKER], never nothing. An audit entry
 * that silently differs from what actually ran is its own kind of lie; `***`
 * says a value was here and was removed, which is what somebody reading the log
 * needs to know.
 *
 * **What is caught**, at the time of writing:
 *
 *  - a password in a URL's userinfo — `scheme://user:secret@host` — and a bare
 *    userinfo of twenty characters or more, which is what a token pasted in
 *    place of a username looks like;
 *  - `-u user:secret` and `--user user:secret`, curl's spelling;
 *  - a credential header's value: `Authorization`, `Proxy-Authorization`,
 *    `Authentication`, `X-Api-Key`, `X-Auth-Token`, `X-Access-Token`,
 *    `X-Amz-Security-Token`, `Private-Token`, `Api-Key`, `Auth-Token`, `Cookie`
 *    and `Set-Cookie`, wherever one appears;
 *  - `Bearer …` and `Basic …` anywhere, header or not, when what follows is
 *    eight characters or more and at least one of them is not a lowercase
 *    letter — a digit, a capital, or one of `._~+/=-`. Every base64 or JWT
 *    value has one; `Basic authentication redesign` is a workflow title and
 *    keeps its words;
 *  - `name=value` and `--name value`, quoted or bare, where the name reads like
 *    a credential — it contains `pass`, `pwd`, `secret`, `token`, `key`,
 *    `credential` or `auth` and does not end in something that names a file, a
 *    path, an id, a name, a url, a host, a port, a user or a format. That one
 *    rule covers `--password=`, `--token=`, `PGPASSWORD=`,
 *    `AWS_SECRET_ACCESS_KEY=`, `-Dspring.datasource.password=` and
 *    `--token abc` alike, and leaves `--password-file=/etc/foo` alone, because
 *    the path to a secret is not the secret;
 *  - `-psecret` written against the flag, the way `mysql` takes it, and `-p`,
 *    `-P` or `-a` with a space after them on a line that also mentions
 *    `sshpass`, `mysql`, `redis-cli`, `smbclient` or a `login`;
 *  - things shaped like a known token wherever they stand, with no flag in
 *    front of them at all: GitHub `ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_` and
 *    `github_pat_`, Slack `xox…`, OpenAI and Anthropic `sk-`, GitLab `glpat-`,
 *    AWS `AKIA`/`ASIA` ids, Google `AIza`, and a JWT;
 *  - a PEM private key block.
 *
 * **What is not caught, and this list is the honest half.** This is a list of
 * patterns and a list of patterns is never complete. It catches the credentials
 * that arrive with a name on them; it cannot catch:
 *
 *  - **a secret as a bare positional argument** — `./deploy.sh s3cr3t`,
 *    `vault write token s3cr3t`. Nothing in the text says which word is the
 *    password, and only the well-known token shapes above are recognisable on
 *    sight;
 *  - **a secret in a heredoc, or in a file the command reads.** `curl -K
 *    creds.txt`, `psql -f seed.sql`, `ssh host <<EOF … EOF` — the credential is
 *    not in the command line, so there is nothing here to find;
 *  - **a secret that has been encoded** — a base64 blob, a hex string, a
 *    `$(pass show …)` substitution. Encoded text has no shape this can tell
 *    apart from any other argument;
 *  - **an unusual flag spelling.** `--pw`, `-k`, `--cred`, a program whose
 *    password flag is `--identity` or `--seed`: none of those read as a
 *    credential by name, and the value goes in as typed;
 *  - **a value of nothing but lowercase letters**, in the two places that is
 *    what tells a credential from a word: `-psecret` written against the
 *    flag (`-pS3cr3t` is caught), and `Bearer` or `Basic` followed by one
 *    (`Bearer abcdefgh` is not caught, and neither is a header-less
 *    `Basic dXNlcg` — though inside an `Authorization:` header both are,
 *    because the header names itself). `tar -pxzf`, `find -printf` and
 *    `Basic authentication` are all real, and mangling them would cost more
 *    than it saves;
 *  - **anything past the length an audit message is cut to.** A long command is
 *    trimmed to fit the column, and a credential in the part that was cut was
 *    never at risk — but a credential straddling the cut leaves its first few
 *    characters behind unless the caller redacts before it trims;
 *  - **the output of a command.** A caller that ever put output into a message
 *    would be handing this text a secret in no shape any of these rules would
 *    find.
 *
 * An administrator who believes this is airtight is worse off than one who
 * knows its edges: the first will let an agent run `./deploy.sh $PASSWORD` and
 * assume the log is safe to hand around.
 *
 * **It over-redacts on purpose.** `MONKEY=1` becomes `MONKEY=***` because the
 * name contains `key`, and `--ssh-key id_rsa` loses the filename. An
 * over-redacted audit line is still a usable audit line; an under-redacted one
 * is a leak that has already happened by the time anybody notices.
 *
 * **Rows written before this are untouched.** This changes what is written from
 * now on. Anything already in `workspace_audit` still holds whatever it held,
 * because quietly rewriting an audit table is a worse thing to do than leaving
 * a known problem where it can be seen — what to do about what is already
 * stored is a decision for whoever runs the installation, and a credential that
 * has been sitting in there should be treated as disclosed and rotated whatever
 * they decide.
 *
 * Applying this twice gives the same answer as applying it once, so a caller
 * that redacts a command before it builds a message does no harm.
 *
 * **There are two doors in, and [redactObvious] is the narrow one.** Everything
 * above describes [redact], which is for text that is a command line: an audit
 * message, or a tool call's arguments. [redactObvious] is for text that is a
 * command's *output*, where the full rule set would do more damage than the
 * leak it prevents — see its own documentation for what it keeps and why.
 */
object AuditRedaction {

    /** What stands where a credential stood. Visible on purpose. */
    const val MARKER = "***"

    /** One audit message, with everything that reads like a credential removed. */
    fun redact(text: String): String {
        var out = text
        RULES.forEach { rule -> out = rule(out) }
        return out
    }

    /**
     * The same text, with only what is a credential beyond argument removed.
     *
     * For arbitrary output rather than for a command line, and it deliberately
     * catches **less** than [redact] does. A Maven build log, a `--help` dump, a
     * `docker inspect` or a config listing is full of `key=`, `--token` and the
     * word `password` that are not credentials — they are the option a program
     * documents, the field a JSON object has, the line a compiler failed on.
     * [redact] would turn all of them into `***`, and output is not an audit row
     * that nothing reads back: it is handed to the model as the answer to the
     * lookup it just made, and put in front of it again by
     * `LlmSessionRecorder.recalled`. A model whose build log says
     * `[ERROR] cannot find symbol ***` cannot fix the build. That is a
     * functional regression, and it is the reason this exists as a second
     * method rather than [redact] being called everywhere.
     *
     * **What it catches**: only the two rules that need no flag, no name and no
     * context to be sure — a value that is a credential on sight and is
     * essentially never anything else. The known token shapes (GitHub
     * `ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_` and `github_pat_`, Slack `xox…`,
     * OpenAI and Anthropic `sk-`, GitLab `glpat-`, AWS `AKIA`/`ASIA`, Google
     * `AIza`, and a JWT) and a PEM private key block. Those are what leaks from
     * `cat ~/.ssh/id_rsa`, `env | grep TOKEN` and a `gh auth status`, which is
     * the shape this is here for.
     *
     * **What it does not catch is everything else, and that is the honest
     * half.** A secret in command output that does not match one of those
     * shapes stays in the transcript, in full: a password a script echoed, a
     * credential inside a JSON blob or a YAML dump, a base64 or hex value, a
     * database URL with a password in it, a self-issued token with no prefix on
     * it, anything a `--help` text calls a default. None of those are
     * distinguishable from ordinary output, and guessing at them is what
     * [redact] does and what output cannot afford. Whoever runs an installation
     * should read this as: a tool that prints a secret puts that secret in the
     * session transcript.
     *
     * The rules are the same functions [redact] runs, so a token prefix added to
     * `KNOWN_TOKEN_SHAPES` is added to both at once and neither can drift from
     * the other.
     */
    fun redactObvious(text: String): String {
        var out = text
        OBVIOUS_RULES.forEach { rule -> out = rule(out) }
        return out
    }

    /*
     * The rules run in order, and the order matters in two places: the
     * `user:password` forms are handled before the general name-and-value rules,
     * so a URL is not taken apart by something looking for a flag, and the
     * quoted forms are handled before the bare ones, so `--password="a b"` loses
     * both of its words rather than the first.
     */
    private val RULES: List<(String) -> String> = listOf(
        ::pemBlocks,
        ::urlCredentials,
        ::headerValues,
        ::bearerTokens,
        ::userColonPassword,
        ::jsonMembers,
        ::assignments,
        ::spacedLongFlags,
        ::attachedShortPassword,
        ::spacedShortPassword,
        ::knownTokenShapes,
    )

    /**
     * The two of those rules that are safe to run over arbitrary output.
     *
     * The same function references, not copies of them: there is one definition
     * of what a known token looks like and one of what a key block looks like,
     * and both entry points reach them through here. A list of its own rather
     * than a flag on [redact], because the difference between the two doors is
     * which rules run and saying that as a list is saying it exactly once.
     */
    private val OBVIOUS_RULES: List<(String) -> String> = listOf(
        ::pemBlocks,
        ::knownTokenShapes,
    )

    // ---- URLs ----------------------------------------------------------

    /** `scheme://user:secret@host`. The user is kept; a username is not a secret. */
    private val URL_USER_PASSWORD = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)([^\s/:@]+):([^\s/@]*)@""")

    /**
     * `scheme://token@host`, where the whole userinfo is the credential.
     *
     * Twenty characters is the line, and it is a guess. Below it lives
     * `ssh://root@box`, which is a host to reach and not a thing to hide; above
     * it lives every token long enough to be one. A short bare token in a URL
     * goes in as typed.
     */
    private val URL_BARE_TOKEN = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)([A-Za-z0-9_.\-]{20,})@""")

    private fun urlCredentials(text: String): String = text
        .replace(URL_USER_PASSWORD) { "${it.groupValues[1]}${it.groupValues[2]}:$MARKER@" }
        .replace(URL_BARE_TOKEN) { "${it.groupValues[1]}$MARKER@" }

    // ---- Headers -------------------------------------------------------

    private const val CREDENTIAL_HEADERS =
        "(?:proxy-)?authorization|authentication|x-api-key|x-auth-token|x-access-token|" +
            "x-amz-security-token|private-token|api-key|auth-token|set-cookie|cookie"

    /**
     * A credential header and what follows the colon.
     *
     * At most two words are taken, so `-H Authorization:Bearer x` on an
     * unquoted line does not swallow the URL that comes after it; a quote ends
     * it either way, which is how `-H "Authorization: Bearer x"` is nearly
     * always written.
     */
    private val HEADER_VALUE = Regex(
        """(?i)\b($CREDENTIAL_HEADERS)(\s*:\s*)([^\s"'\r\n]+(?:\s+[^\s"'\r\n]+)?)""",
    )

    private fun headerValues(text: String): String =
        text.replace(HEADER_VALUE) { "${it.groupValues[1]}${it.groupValues[2]}$MARKER" }

    /**
     * The scheme on its own, for the lines that carry it without a header name.
     *
     * The word after it has to look like a credential rather than like a word.
     * Most of what this recorder writes is English — a workflow saved, an issue
     * opened, and the title somebody typed on it — and `Basic authentication
     * redesign` is a title this product's own tracker collects. A rule that took
     * any eight characters would turn that into `Basic ***` and lose the audit
     * line without a credential anywhere near it. Every real base64 or JWT value
     * carries a digit, a capital or one of `._~+/=-`; `authentication`,
     * `credentials` and `permissions` do not.
     */
    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+([A-Za-z0-9._~+/=\-]{8,})""")

    private fun bearerTokens(text: String): String = text.replace(BEARER) { match ->
        val value = match.groupValues[2]
        if (value.all { it in 'a'..'z' }) match.value else "${match.groupValues[1]} $MARKER"
    }

    // ---- user:password against a flag ----------------------------------

    private val USER_COLON_PASSWORD = Regex("""(^|\s)(-u|--user)(\s+|=)(["']?)([^\s:"']+):([^\s"']+)""")

    private fun userColonPassword(text: String): String = text.replace(USER_COLON_PASSWORD) { match ->
        val lead = match.groupValues[1]
        val flag = match.groupValues[2]
        val separator = match.groupValues[3]
        val quote = match.groupValues[4]
        val user = match.groupValues[5]
        "$lead$flag$separator$quote$user:$MARKER"
    }

    // ---- a name and its value ------------------------------------------

    /**
     * What makes a name read like a credential. Substrings, not whole words, so
     * `PGPASSWORD`, `--oauth-token` and `spring.datasource.password` all land.
     */
    private val SECRET_FRAGMENTS = listOf("pass", "pwd", "secret", "token", "key", "credential", "auth")

    /**
     * What takes a name back out again: an ending that says the value names
     * something *about* a credential rather than being one. `--password-file` is
     * a path, `--auth-user` is a username, `AWS_ACCESS_KEY_ID` is an identifier
     * that is public by design.
     */
    private val BENIGN_ENDINGS = listOf(
        "file", "files", "path", "dir", "id", "name", "url", "uri",
        "host", "port", "user", "type", "format", "length", "count", "env", "mode", "algorithm",
    )

    /** Names that carry a fragment by accident and are never a credential. */
    private val BENIGN_NAMES = setOf("author", "authors")

    private fun looksLikeCredentialName(name: String): Boolean {
        val cleaned = name.trimStart('-').lowercase()
        if (cleaned in BENIGN_NAMES) return false
        if (BENIGN_ENDINGS.any { cleaned.endsWith(it) }) return false
        return SECRET_FRAGMENTS.any { cleaned.contains(it) }
    }

    /**
     * `"password": "x"` - a JSON member whose name says it holds a credential.
     *
     * The arguments of a tool call an agent makes are JSON, and they are what
     * is written here; `password=x` was caught and `{"password":"x"}` was not.
     * Only a string value, and only under a name the assignment rule would also
     * have hidden. Issue #588.
     */
    // Unrolled, with possessive runs: `(?:[^"\\]|\\.)*` recursed once per character, and a tool call carrying a
    // script a few thousand characters long overflowed the chat thread's stack and lost the turn.
    private val JSON_MEMBER = Regex("""("([A-Za-z0-9_.\-]{1,64})"\s*:\s*")([^"\\]*+(?:\\.[^"\\]*+)*+)"""")

    private fun jsonMembers(text: String): String = text.replace(JSON_MEMBER) { match ->
        if (looksLikeCredentialName(match.groupValues[2]) && match.groupValues[3].isNotEmpty()) {
            "${match.groupValues[1]}$MARKER\""
        } else {
            match.value
        }
    }

    private val QUOTED_ASSIGNMENT = Regex("""([A-Za-z0-9_.\-]{1,64})=(["'])([^"']*)\2""")
    private val BARE_ASSIGNMENT = Regex("""([A-Za-z0-9_.\-]{1,64})=([^\s"']+)""")

    private fun assignments(text: String): String = text
        .replace(QUOTED_ASSIGNMENT) { match ->
            val name = match.groupValues[1]
            val quote = match.groupValues[2]
            if (looksLikeCredentialName(name)) "$name=$quote$MARKER$quote" else match.value
        }
        .replace(BARE_ASSIGNMENT) { match ->
            val name = match.groupValues[1]
            if (looksLikeCredentialName(name)) "$name=$MARKER" else match.value
        }

    /**
     * `--token abc`, the same names with a space instead of an equals sign.
     *
     * A value starting with `-` is left alone: `--token --verbose` is a flag
     * that was given nothing, and redacting the next flag would hide what ran
     * without hiding a secret.
     */
    private val QUOTED_LONG_FLAG = Regex("""(^|\s)(--[A-Za-z0-9_.\-]{1,64})\s+(["'])([^"']*)\3""")
    private val BARE_LONG_FLAG = Regex("""(^|\s)(--[A-Za-z0-9_.\-]{1,64})\s+([^\s"'\-][^\s"']*)""")

    private fun spacedLongFlags(text: String): String = text
        .replace(QUOTED_LONG_FLAG) { match ->
            val lead = match.groupValues[1]
            val flag = match.groupValues[2]
            val quote = match.groupValues[3]
            if (looksLikeCredentialName(flag)) "$lead$flag $quote$MARKER$quote" else match.value
        }
        .replace(BARE_LONG_FLAG) { match ->
            val lead = match.groupValues[1]
            val flag = match.groupValues[2]
            if (looksLikeCredentialName(flag)) "$lead$flag $MARKER" else match.value
        }

    // ---- short flags ---------------------------------------------------

    /**
     * `-pS3cr3t`, which is how `mysql` and its family take a password.
     *
     * Only when the value has something in it that is not a lowercase letter,
     * and does not start like a path. `tar -pxzf`, `find -printf` and
     * `mkdir -p/tmp` are all real, and a redactor that ate them would be a
     * redactor people turn off. A password of nothing but lowercase letters
     * survives here, which is in the list of what this does not catch.
     */
    private val ATTACHED_SHORT_PASSWORD = Regex("""(^|\s)-p([^\s"']{4,})""")

    private fun attachedShortPassword(text: String): String = text.replace(ATTACHED_SHORT_PASSWORD) { match ->
        val value = match.groupValues[2]
        val pathLike = value.first() in "/.~"
        val allLowercase = value.all { it in 'a'..'z' }
        if (pathLike || allLowercase) match.value else "${match.groupValues[1]}-p$MARKER"
    }

    /**
     * `-p secret` and `-a secret` with a space, which only some programs mean
     * that way.
     *
     * Scoped to lines that name one of those programs, because `mkdir -p /tmp`
     * is far commoner than `docker login -p`, and a rule that redacted every
     * `-p` would turn most of the audit log into asterisks. The scoping is by
     * the whole line rather than by the word in front of the flag, so a pipeline
     * whose second command is the `mysql` one is still covered — and so a
     * `mkdir -p` sharing a line with a `login` is redacted for nothing, which is
     * the direction to err in.
     */
    private val PASSWORD_TAKING_COMMANDS =
        listOf("sshpass", "mysql", "redis-cli", "smbclient", "login")

    private val SPACED_SHORT_PASSWORD = Regex("""(^|\s)(-p|-a|-P)\s+(["']?)([^\s"'\-][^\s"']*)""")

    private fun spacedShortPassword(text: String): String {
        val lowered = text.lowercase()
        if (PASSWORD_TAKING_COMMANDS.none { lowered.contains(it) }) return text
        return text.replace(SPACED_SHORT_PASSWORD) { match ->
            val lead = match.groupValues[1]
            val flag = match.groupValues[2]
            val quote = match.groupValues[3]
            "$lead$flag $quote$MARKER"
        }
    }

    // ---- shapes we know on sight ---------------------------------------

    /**
     * Tokens recognisable with no flag in front of them, which is the only
     * answer this has to a secret passed as a bare argument — and it is an
     * answer for the issuers who chose a prefix, not for anybody else.
     */
    private val KNOWN_TOKEN_SHAPES = Regex(
        // This installation's own access tokens first: `orkx_` and 32 random bytes. #588.
        """(orkx_[A-Za-z0-9_\-]{20,}|gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{20,}|xox[abprse]-[A-Za-z0-9\-]{10,}|""" +
            """sk-(?:ant-)?[A-Za-z0-9_\-]{16,}|glpat-[A-Za-z0-9_\-]{16,}|(?:AKIA|ASIA)[0-9A-Z]{16}|""" +
            """AIza[0-9A-Za-z_\-]{30,}|ey[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,})""",
    )

    private fun knownTokenShapes(text: String): String = text.replace(KNOWN_TOKEN_SHAPES, MARKER)

    /** A key pasted into a command, from its opening line to its closing one. */
    private val PEM_BLOCK = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|\z)""",
    )

    private fun pemBlocks(text: String): String = text.replace(PEM_BLOCK, MARKER)
}
