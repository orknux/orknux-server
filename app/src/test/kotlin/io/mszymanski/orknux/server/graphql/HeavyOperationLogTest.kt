package io.mszymanski.orknux.server.graphql

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import graphql.ExecutionInput
import graphql.ExecutionResultImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.http.HttpHeaders
import org.springframework.util.LinkedMultiValueMap
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.Locale

/**
 * A request over a threshold is named in the log, with its time and size and
 * nothing it carried. Issue #616: a page that ran a server out of memory left
 * no line saying which of its requests had done it.
 */
class HeavyOperationLogTest {

    private val logger = LoggerFactory.getLogger(HeavyOperationLog::class.java) as Logger
    private val seen = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun watch() {
        seen.start()
        logger.addAppender(seen)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(seen)
    }

    private fun run(log: HeavyOperationLog, secret: String) {
        val request = WebGraphQlRequest(
            URI.create("http://localhost/graphql"), HttpHeaders(), LinkedMultiValueMap(), null, emptyMap(),
            mapOf("query" to "query Workspaces { workspaces { name } }", "operationName" to "Workspaces"),
            "1", Locale.ENGLISH,
        )
        val answered = DefaultExecutionGraphQlResponse(
            ExecutionInput.newExecutionInput("{ x }").build(),
            ExecutionResultImpl.newExecutionResult().data(mapOf("workspaces" to listOf(mapOf("name" to secret)))).build(),
        )
        log.intercept(request) { Mono.just(WebGraphQlResponse(answered)) }.block()
    }

    @Test
    fun `a slow or large request is logged by its name and size, never by what it answered`() {
        run(HeavyOperationLog(JsonMapper(), slowerThanMs = 0, largerThanKb = 0), "nothing")
        assertThat(seen.list).describedAs("both thresholds off").isEmpty()

        run(HeavyOperationLog(JsonMapper(), slowerThanMs = 0, largerThanKb = 1), "x".repeat(4096))
        val line = seen.list.single().formattedMessage
        assertThat(line).matches("""GraphQL operation Workspaces took \d+ ms and answered 4 KB""")
        assertThat(line).doesNotContain("xxxx")
    }
}
