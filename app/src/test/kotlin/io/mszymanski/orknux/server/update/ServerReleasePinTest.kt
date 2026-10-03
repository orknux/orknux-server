package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import javax.sql.DataSource

/**
 * ORKNUX_RELEASE_PIN as the server sees it, #593: Admin -> Updates says what
 * the launcher decided, from the same rule, and cannot choose over it.
 */
@SpringBootTest(properties = ["orknux.update.pin=$PINNED"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ServerReleasePinTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val updates: ServerReleases,
    @Autowired val dataSource: DataSource,
) {

    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM server_release_part")
        jdbc.update("DELETE FROM server_release")
        jdbc.update("DELETE FROM installation_setting WHERE name LIKE 'release%'")
    }

    @Test
    fun `a pin to a release the database does not keep is reported, and the image is what is wanted`() {
        val answer = graphQlTester.document("{ serverUpdates { pin pinRefusal } }").execute()
        answer.path("serverUpdates.pin").entity(String::class.java).isEqualTo(PINNED)
        assertThat(answer.path("serverUpdates.pinRefusal").entity(String::class.java).get()).contains("keeps no release $PINNED")
        assertThat(updates.wantedReleaseId()).isNull()
    }

    @Test
    fun `a kept pinned release is what every server should run, and nothing can be chosen over it`() {
        val pinned = updates.store(TestReleaseJars.signed(Shape(version = PINNED)), ServerReleaseSource.UPLOAD, "alice")
        val other = updates.store(TestReleaseJars.signed(Shape(version = "8.2.2")), ServerReleaseSource.UPLOAD, "alice")

        val answer = graphQlTester.document("{ serverUpdates { pin pinRefusal stored { version activatable refusal } } }").execute()
        answer.path("serverUpdates.pinRefusal").valueIsNull()
        answer.path("serverUpdates.stored[0].activatable").entity(Boolean::class.java).isEqualTo(false)
        assertThat(answer.path("serverUpdates.stored[0].refusal").entity(String::class.java).get())
            .contains("pinned to $PINNED by ORKNUX_RELEASE_PIN")
        assertThat(updates.wantedReleaseId()).isEqualTo(pinned.id)

        graphQlTester.document("mutation { activateServerRelease(id: ${other.id}) { restarting } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleasePinned") }
        graphQlTester.document("mutation { activateImageRelease { restarting } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleasePinned") }

        // Given up on by the launcher: the image is wanted, and the page says why.
        JdbcReleaseStore { dataSource.connection }.fail(pinned.id!!, "it did not start in 3 attempts")
        assertThat(updates.wantedReleaseId()).isNull()
        assertThat(updates.pinRefusal()).contains("marked failed").contains("did not start in 3 attempts")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("orknux.update.certificate") { TestReleaseJars.trusted.pem.toUri().toString() }
        }
    }
}

private const val PINNED = "8.2.1"
