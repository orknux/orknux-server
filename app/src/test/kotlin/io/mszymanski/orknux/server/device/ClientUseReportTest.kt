package io.mszymanski.orknux.server.device

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Admin > Reports > Clients: who uses the Chrome extension, Orknux for Windows
 * and for Linux, and Orknux Shell. Written by the door each comes in by, as it
 * asks for work, and read by administrators only.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class ClientUseReportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val doors: DeviceAPI,
    @Autowired val uses: ClientUseRepository,
) {

    @BeforeEach
    fun reset() = uses.deleteAll()

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `each client that asks for work is listed, by who and which copy`() {
        doors.next("browser", 0, "Chrome", "1.0.5", null, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/141.0 Safari/537.36")
        doors.next("desktop", 0, "Windows", "1.2.0", "Windows on PC-7")
        doors.next("desktop", 0, "Linux", "1.1.0", "Linux on studio")
        doors.next("terminal", 0, "Shell", "1.4.1", "a1b2c3")
        // Asking again is the same copy, not a second row.
        doors.next("browser", 0, "Chrome", "1.0.6", null)

        val rows = graphQlTester.document("""{ clientUses { username kind client name version computer connected } }""")
            .execute().errors().verify()
            .path("clientUses").entityList(Map::class.java).get()

        assertThat(rows.map { "${it["kind"]} ${it["client"]} ${it["name"]}" }).containsExactlyInAnyOrder(
            "BROWSER Chrome Chrome",
            "DESKTOP Windows Windows on PC-7",
            "DESKTOP Linux Linux on studio",
            "SHELL Shell a1b2c3",
        )
        assertThat(rows.map { it["username"] }.toSet()).containsExactly("alice")
        // Asking keeps a client connected, so every one of them is, now.
        assertThat(rows.map { it["connected"] }.toSet()).containsExactly(true)
        assertThat(rows.first { it["client"] == "Chrome" }["version"]).isEqualTo("1.0.5")
        // The Computer column: a browser's system, read off its User-Agent, and a desktop client's own name.
        assertThat(rows.first { it["client"] == "Chrome" }["computer"]).isEqualTo("Windows")
        assertThat(rows.first { it["client"] == "Linux" }["computer"]).isEqualTo("Linux on studio")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `nobody but an administrator reads it`() {
        graphQlTester.document("""{ clientUses { username } }""")
            .execute().errors().satisfy { assertThat(it).isNotEmpty() }
    }
}
