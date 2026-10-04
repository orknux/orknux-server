package io.mszymanski.orknux.server.cluster

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The cluster lease's length on Admin -> Settings. Issue #597.
 *
 * Thirty seconds from the file unless an administrator says otherwise, read by
 * every replica at its next renewal, audited, and refused outside its bounds in
 * words and as a code.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ClusterLeaseSettingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @AfterEach
    fun restore() {
        settings.setClusterLeaseSeconds(settings.clusterLeaseSecondsConfigured(), "alice")
    }

    @Test
    fun `thirty seconds unless somebody says otherwise`() {
        graphQlTester.document("{ installationSettings { clusterLeaseSeconds clusterLeaseSecondsConfigured } }")
            .execute()
            .path("installationSettings.clusterLeaseSeconds").entity(Int::class.java).isEqualTo(30)
            .path("installationSettings.clusterLeaseSecondsConfigured").entity(Int::class.java).isEqualTo(30)
    }

    @Test
    fun `an administrator sets it, the lease reads it, and the change is audited`() {
        graphQlTester.document("mutation { setClusterLeaseSeconds(seconds: 45) { clusterLeaseSeconds clusterLeaseSecondsConfigured } }")
            .execute()
            .path("setClusterLeaseSeconds.clusterLeaseSeconds").entity(Int::class.java).isEqualTo(45)
            .path("setClusterLeaseSeconds.clusterLeaseSecondsConfigured").entity(Int::class.java).isEqualTo(30)

        assertThat(settings.clusterLeaseSeconds()).isEqualTo(45)
        assertThat(audit.findAll().map { it.message }).contains("Cluster lease set to 45s")
    }

    @Test
    fun `a length outside the bounds is refused, in words and as a code`() {
        listOf(4, 601).forEach { seconds ->
            graphQlTester.document("mutation { setClusterLeaseSeconds(seconds: $seconds) { clusterLeaseSeconds } }")
                .execute()
                .errors()
                .satisfy { errors ->
                    assertThat(errors).hasSize(1)
                    assertThat(errors.first().message).contains("Choose between 5 and 600 seconds")
                    assertThat(errors.first().extensions["code"]).isEqualTo("ClusterLeaseOutOfRange")
                }
        }
        assertThat(settings.clusterLeaseSeconds()).isEqualTo(30)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator cannot change it`() {
        graphQlTester.document("mutation { setClusterLeaseSeconds(seconds: 45) { clusterLeaseSeconds } }")
            .execute()
            .errors()
            .satisfy { errors -> assertThat(errors).isNotEmpty() }
        assertThat(settings.clusterLeaseSeconds()).isEqualTo(30)
    }
}
