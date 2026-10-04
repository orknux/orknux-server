package io.mszymanski.orknux.server.watcher

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a watcher's condition matches, and what the Watchers skill says it
 * matches - which must be the same thing. Issue #606.
 *
 * The skill is something a model believes, and a worked example that does not
 * work teaches it a condition that never fires. So every JSONPath example the
 * skill gives is read out of the file and run against the result it says it
 * is for, every regular expression is compiled, and every tool it names is a
 * tool that exists.
 */
class WatcherConditionTest {

    private val skill: String =
        requireNotNull(javaClass.classLoader.getResource("skills/watchers.md")).readText()

    @Test
    fun `a definite path matches a value that is there and is not null or false`() {
        val path = WatcherConditionKind.JSONPATH
        assertThat(WatcherCondition.match(path, "$.url", """{"url":"https://x"}""")).isEqualTo("\"https://x\"")
        assertThat(WatcherCondition.match(path, "$.url", """{"other":1}""")).isNull()
        assertThat(WatcherCondition.match(path, "$.url", """{"url":null}""")).isNull()
        assertThat(WatcherCondition.match(path, "$.ready", """{"ready":false}""")).isNull()
        assertThat(WatcherCondition.match(path, "$.ready", """{"ready":true}""")).isEqualTo("true")
    }

    @Test
    fun `a filter matches when it keeps something`() {
        val path = WatcherConditionKind.JSONPATH
        val waiting = """$[?(@.status == 'done')]"""
        assertThat(WatcherCondition.match(path, waiting, """{"status":"running"}""")).isNull()
        assertThat(WatcherCondition.match(path, waiting, """{"status":"done"}""")).contains("done")
        assertThat(WatcherCondition.match(path, "$.jobs[?(@.state == 'failed')]", """{"jobs":[]}""")).isNull()
        // Not JSON at all: never a match, never an exception.
        assertThat(WatcherCondition.match(path, waiting, "the build is done")).isNull()
    }

    @Test
    fun `a bad expression is a problem said in words, and a good one is none`() {
        assertThat(WatcherCondition.problemWith(WatcherConditionKind.JSONPATH, "$[?(@.status == ")).contains("not a JSONPath")
        assertThat(WatcherCondition.problemWith(WatcherConditionKind.JSONPATH, "status")).contains("starts with")
        assertThat(WatcherCondition.problemWith(WatcherConditionKind.REGEX, "(open")).contains("not a regular expression")
        assertThat(WatcherCondition.problemWith(WatcherConditionKind.JSONPATH, "$.a.b")).isNull()
        assertThat(WatcherCondition.problemWith(WatcherConditionKind.REGEX, "(?i)done")).isNull()
    }

    @Test
    fun `every JSONPath example in the skill matches the result it is given for`() {
        // "- What it is, in `{json}`:" then the path on the next line.
        val examples = Regex("""in `(\{.*})`:\s*\n\s*`(\$[^`]*)`""").findAll(skill).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertThat(examples).hasSizeGreaterThanOrEqualTo(5)
        examples.forEach { (result, path) ->
            assertThat(WatcherCondition.problemWith(WatcherConditionKind.JSONPATH, path)).describedAs(path).isNull()
            assertThat(WatcherCondition.match(WatcherConditionKind.JSONPATH, path, result))
                .describedAs("$path against $result").isNotNull()
        }
        // And the warning the skill gives is true: a bare path matches any value at all.
        assertThat(WatcherCondition.match(WatcherConditionKind.JSONPATH, "$.status", """{"status":"running"}""")).isNotNull()
        // The wait-for-the-end example matches the failure as well as the success.
        val end = """$[?(@.status == 'done' || @.status == 'failed')]"""
        assertThat(skill).contains(end)
        assertThat(WatcherCondition.match(WatcherConditionKind.JSONPATH, end, """{"status":"failed"}""")).isNotNull()
    }

    @Test
    fun `every regular expression example in the skill compiles and finds what it says`() {
        val examples = Regex("""^- [^`\n]+: `([^`]+)`$""", RegexOption.MULTILINE).findAll(
            skill.substringAfter("### Regular expression").substringBefore("Remember that"),
        ).map { it.groupValues[1] }.toList()
        assertThat(examples).hasSize(4)
        examples.forEach { assertThat(WatcherCondition.problemWith(WatcherConditionKind.REGEX, it)).describedAs(it).isNull() }
        assertThat(WatcherCondition.match(WatcherConditionKind.REGEX, examples[0], "Service DEPLOYED at 10:02")).isEqualTo("DEPLOYED")
        assertThat(WatcherCondition.match(WatcherConditionKind.REGEX, examples[1], """{"status" : "done"}""")).isNotNull()
        assertThat(WatcherCondition.match(WatcherConditionKind.REGEX, examples[2], "Run Failed")).isEqualTo("Failed")
        assertThat(WatcherCondition.match(WatcherConditionKind.REGEX, examples[3], "released v2.4.17")).isEqualTo("v2.4.17")
    }

    @Test
    fun `the skill names the real tools and parameters, and no others`() {
        listOf(WatcherTools.SET, WatcherTools.LIST, WatcherTools.FINISH).forEach { assertThat(skill).contains("`$it`") }
        assertThat(Regex("""watcher_[a-z_]+""").findAll(skill).map { it.value }.toSet())
            .isSubsetOf(WatcherTools.NAMES)
        listOf(
            WatcherTools.TOOL, WatcherTools.ARGUMENTS, WatcherTools.CONDITION_TYPE, WatcherTools.CONDITION,
            WatcherTools.INTERVAL, WatcherTools.TIMEOUT, WatcherTools.NOTE, WatcherTools.FINISHED,
        ).forEach { assertThat(skill).contains("`$it`") }
        WatcherService.UNWATCHABLE.forEach { assertThat(skill).contains("`$it`") }
        // The wake-up opens as the skill says it does.
        assertThat(skill).contains("\"Watcher #N fired.\"")
        // And the defaults it quotes are the defaults.
        assertThat(DEFAULT_WATCHER_MAX_SECONDS).isEqualTo(7 * 24 * 60 * 60)
        assertThat(skill).contains("a week unless somebody changed it")
        assertThat(DEFAULT_WATCHER_MAX_PER_AGENT).isEqualTo(10)
        assertThat(skill).contains("ten unless")
    }

    /**
     * The path says which part of the result the condition is held against. An
     * agent waiting for an http_get body of 0 wrote the regex 0, which the whole
     * result answered with the 0 of status 200; held against $.body it does not.
     */
    @Test
    fun `a result path holds the condition against the part of the result it names`() {
        val regex = WatcherConditionKind.REGEX
        val one = """{"status":200,"ok":true,"body":"1"}"""
        assertThat(WatcherCondition.match(regex, "0", "$", one)).describedAs("the whole result").isEqualTo("0")
        assertThat(WatcherCondition.match(regex, "0", "$.body", one)).describedAs("only the body").isNull()
        assertThat(WatcherCondition.match(regex, "^0$", "$.body", """{"status":200,"body":"0"}""")).isEqualTo("0")
        // A selected object is held as its JSON.
        assertThat(WatcherCondition.match(regex, "done", "$.build", """{"build":{"state":"done"}}""")).isEqualTo("done")
        assertThat(
            WatcherCondition.match(WatcherConditionKind.JSONPATH, "$[?(@.state == 'done')]", "$.build", """{"build":{"state":"done"}}"""),
        ).contains("done")
        // A path that finds nothing, or a result that is not JSON, never matches.
        assertThat(WatcherCondition.match(regex, "1", "$.missing", one)).isNull()
        assertThat(WatcherCondition.match(regex, "1", "$.body", "plain text 1")).isNull()
        // A watcher set before the path existed reads the whole result, as it did.
        assertThat(WatcherCondition.match(regex, "0", null, one)).isEqualTo("0")
    }

    @Test
    fun `a result path is a JSONPath or it is refused with the reason`() {
        assertThat(WatcherCondition.problemWithPath("$")).isNull()
        assertThat(WatcherCondition.problemWithPath("$.body")).isNull()
        assertThat(WatcherCondition.problemWithPath("")).contains("tool_result_path")
        assertThat(WatcherCondition.problemWithPath("body")).contains("starts with")
        assertThat(WatcherCondition.problemWithPath("$[")).contains("not a JSONPath")
    }
}
