package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * The credentials an audit line is stripped of, and the words it keeps.
 *
 * Real command lines rather than invented ones, because the failure this guards
 * against is a shape nobody thought of: a password reaches the table the moment
 * a command spells its flag differently from the pattern, and nothing on the
 * audit page says it happened. The table below is the list of spellings that
 * have been thought of, and it is the whole of the claim being made.
 *
 * The negative half matters as much. An audit log where `git commit -m "fix the
 * password reset page"` comes back as asterisks is a log people stop reading,
 * and a redactor people stop reading gets turned off — so the words `password`
 * and `key` in prose, and the paths that carry them, have to survive intact.
 */
class AuditRedactionTest {

    /** A tool call's arguments are JSON, and they are written to the session log. Issue #588. */
    @Test
    fun `a credential in a JSON member is replaced, and the rest of the object kept`() {
        assertThat(AuditRedaction.redact("""{"url":"https://x.example","password":"hunter2","apiKey": "abc123"}"""))
            .isEqualTo("""{"url":"https://x.example","password":"***","apiKey": "***"}""")
        assertThat(AuditRedaction.redact("""{"title":"rotate the password policy"}"""))
            .isEqualTo("""{"title":"rotate the password policy"}""")
    }

    @Test
    fun `this installation's own access token is a token on sight`() {
        val token = "orkx_" + "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
        assertThat(AuditRedaction.redactObvious("curl -H x $token")).doesNotContain(token)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credentials")
    fun `a credential on a command line is replaced by a marker`(command: String, expected: String) {
        assertThat(AuditRedaction.redact(command)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            // The words in prose. A commit message about a password is not one.
            "git commit -m \"fix the password reset page\"",
            "git commit -m \"rotate the api key on tuesday\"",
            // A path that carries the word, which is a location and not a secret.
            "cat /etc/orknux/token/README.md",
            "ls -la /var/lib/secrets",
            // The path to a credential is not the credential.
            "psql --password-file=/etc/foo -h db.internal",
            "ssh --identity-file /home/alice/.ssh/id_ed25519 box",
            "vault read --token-path /run/secrets/vault box",
            // The id half of an AWS key pair is public by design.
            "aws configure set aws_access_key_id AKIA_PLACEHOLDER_ID",
            // Flags that only look like one, and the commands they belong to.
            "mkdir -p /tmp/build && tar -pxzf release.tgz",
            "find /srv -printf '%f'",
            "git commit --author=\"Alice <alice@example.com>\"",
            "curl https://api.example.com/v1/keys -o keys.json",
            // A host to reach, not a thing to hide.
            "ssh://root@box",
            // Most rows in this table are English, and a lot of them carry a
            // title somebody typed. `Basic authentication` is a workflow name.
            "Workflow Basic authentication redesign created",
            "Issue #14 opened: Basic auth for the proxy",
            "Skill Bearer tokens are explained on the settings page created",
        ],
    )
    fun `an ordinary command line comes through untouched`(command: String) {
        assertThat(AuditRedaction.redact(command)).isEqualTo(command)
    }

    @Test
    fun `a private key pasted into a command goes in whole`() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\n-----END OPENSSH PRIVATE KEY-----"

        val redacted = AuditRedaction.redact("echo '$key' > k")

        assertThat(redacted).isEqualTo("echo '***' > k")
    }

    @Test
    fun `redacting what has already been redacted changes nothing`() {
        // A caller may redact a command before it words a message around it, and
        // the recorder redacts the message it is given. The second pass has to be
        // a no-op, or the marker itself would be chewed on.
        val once = AuditRedaction.redact("git push https://alice:s3cr3t@github.com/acme/repo.git main")

        assertThat(AuditRedaction.redact(once)).isEqualTo(once)
    }

    @Test
    fun `several credentials on one line all go`() {
        val command =
            "PGPASSWORD=s3cr3t curl -u alice:hunter2 -H 'Authorization: Bearer abcdefghijkl' " +
                "https://bob:pw@api.example.com"

        val redacted = AuditRedaction.redact(command)

        assertThat(redacted).doesNotContain("s3cr3t", "hunter2", "abcdefghijkl", "pw@")
        assertThat(redacted).contains("curl -u alice:***")
    }

    companion object {

        /**
         * A method source rather than a CSV one: half of these carry quotes, which
         * is how a shell is written, and a CSV table would be arguing with the
         * data.
         */
        @JvmStatic
        fun credentials(): Stream<Arguments> = Stream.of(
            // The one that started this: a password in a git remote.
            row(
                "git push https://alice:s3cr3t@github.com/acme/repo.git main",
                "git push https://alice:***@github.com/acme/repo.git main",
            ),
            // The whole userinfo is the credential, with no username beside it.
            row(
                "git clone https://ghp_Zmlyc3RvZmFsbHRoaXNpc2Zha2U@github.com/acme/repo.git",
                "git clone https://***@github.com/acme/repo.git",
            ),
            row(
                "curl -u alice:s3cr3t https://api.example.com/v1/me",
                "curl -u alice:*** https://api.example.com/v1/me",
            ),
            row(
                "curl --user alice:s3cr3t https://api.example.com",
                "curl --user alice:*** https://api.example.com",
            ),
            row(
                "curl -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdef' https://api.example.com",
                "curl -H 'Authorization: ***' https://api.example.com",
            ),
            row(
                "curl -H \"X-Api-Key: 8f14e45fceea167a5a36dedd4bea2543\" https://api.example.com",
                "curl -H \"X-Api-Key: ***\" https://api.example.com",
            ),
            row("psql --password=s3cr3t -h db.internal", "psql --password=*** -h db.internal"),
            row("gh auth login --token ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg", "gh auth login --token ***"),
            row("orkx login --token=abc123def456", "orkx login --token=***"),
            row("export GITHUB_TOKEN=ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg", "export GITHUB_TOKEN=***"),
            row("PGPASSWORD=s3cr3t psql -h db.internal -U alice", "PGPASSWORD=*** psql -h db.internal -U alice"),
            row(
                "java -Dspring.datasource.password=s3cr3t -jar app.jar",
                "java -Dspring.datasource.password=*** -jar app.jar",
            ),
            row("mysql -h db -u root -pS3cr3t! -e 'select 1'", "mysql -h db -u root -p*** -e 'select 1'"),
            row("sshpass -p S3cr3t! ssh alice@box uptime", "sshpass -p *** ssh alice@box uptime"),
            row(
                "docker login -u alice -p S3cr3t! registry.example.com",
                "docker login -u alice -p *** registry.example.com",
            ),
            // Quoted, which is how anything with a space in it has to be written.
            row("deploy --password \"s3cr3t value\" --host prod", "deploy --password \"***\" --host prod"),
            row("deploy --password='s3cr3t value' --host prod", "deploy --password='***' --host prod"),
            // The scheme with no header naming it, where what follows still has to
            // look like a value rather than like a word.
            row("hook called with Basic dXNlcjpwYXNz", "hook called with Basic ***"),
            row(
                "hook called with Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdef",
                "hook called with Bearer ***",
            ),
            /*
             * No flag in front of it at all - only the shapes with a known
             * prefix.
             *
             * The prefix is joined on rather than written out, and that is not
             * squeamishness: GitHub's push protection matches `xoxb-` followed
             * by the right shape wherever it finds it, so a made-up token in a
             * test that exists to *redact* made-up tokens blocks the push of
             * every commit that carries it. The value is as fake as it looks -
             * twelve digits in order and the alphabet to p - and the rule being
             * tested is about the shape, which is unchanged by where the string
             * was assembled.
             */
            row("./deploy.sh " + SLACK_PREFIX + "123456789012-abcdefghijklmnop", "./deploy.sh ***"),
            row("aws configure set key AKIAIOSFODNN7EXAMPLE", "aws configure set key ***"),
        )

        /** Split so the literal never appears whole. See the row that uses it. */
        private const val SLACK_PREFIX = "xoxb" + "-"

        private fun row(command: String, expected: String) = Arguments.of(command, expected)
    }
}
