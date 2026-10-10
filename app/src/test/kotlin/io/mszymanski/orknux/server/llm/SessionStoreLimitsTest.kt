package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How big a session store value may be and how many keys a store holds are
 * Admin settings. Both were constants: 8 MB refused a 26 MB exported project,
 * which is exactly what the store exists to carry to an upload tool.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionStoreLimitsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val store: LlmSessionStore,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val settings: InstallationSettings,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var session: Long = 0
    private var sizeBefore = 0
    private var keysBefore = 0

    @BeforeEach
    fun open() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "store-${System.nanoTime()}")).id)
        session = recorder.open(workspace, "test", "store-${System.nanoTime()}")
        sizeBefore = settings.sessionValueMaxMb()
        keysBefore = settings.sessionStoreMaxKeys()
    }

    @AfterEach
    fun restore() {
        settings.setSessionValueMaxMb(sizeBefore, "alice")
        settings.setSessionStoreMaxKeys(keysBefore, "alice")
    }

    @Test
    fun `a fresh installation keeps 8 MB a value and 200 keys`() {
        graphQlTester.document("{ installationSettings { sessionValueMaxMbConfigured sessionStoreMaxKeysConfigured } }")
            .execute().also {
                it.path("installationSettings.sessionValueMaxMbConfigured").entity(Int::class.java).isEqualTo(8)
                it.path("installationSettings.sessionStoreMaxKeysConfigured").entity(Int::class.java).isEqualTo(200)
            }
    }

    @Test
    fun `a value over the set size is refused, naming the setting, and one under it kept`() {
        graphQlTester.document("mutation { setSessionValueMaxMb(megabytes: 1) { sessionValueMaxMb } }")
            .execute().path("setSessionValueMaxMb.sessionValueMaxMb").entity(Int::class.java).isEqualTo(1)

        val big = "\"" + "x".repeat(2 * 1024 * 1024) + "\""
        assertThat(store.put(session, "archive", big)).contains("1024 KB").contains("Session Value Size")

        graphQlTester.document("mutation { setSessionValueMaxMb(megabytes: 4) { sessionValueMaxMb } }").execute()
        assertThat(store.put(session, "archive", big)).isNull()
    }

    @Test
    fun `the store holds as many keys as set`() {
        settings.setSessionStoreMaxKeys(10, "alice")
        repeat(10) { assertThat(store.put(session, "k$it", "1")).isNull() }
        assertThat(store.put(session, "k10", "1")).contains("10 keys")
    }

    @Test
    fun `sizes and counts outside the bounds are refused with a code`() {
        listOf(
            "mutation { setSessionValueMaxMb(megabytes: 0) { sessionValueMaxMb } }" to "SessionValueSizeOutOfRange",
            "mutation { setSessionValueMaxMb(megabytes: 257) { sessionValueMaxMb } }" to "SessionValueSizeOutOfRange",
            "mutation { setSessionStoreMaxKeys(keys: 9) { sessionStoreMaxKeys } }" to "SessionStoreKeysOutOfRange",
        ).forEach { (document, code) ->
            graphQlTester.document(document).execute().errors().satisfy { errors ->
                assertThat(errors).hasSize(1)
                assertThat(errors.single().extensions["code"]).isEqualTo(code)
            }
        }
    }
}
