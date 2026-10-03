package io.mszymanski.orknux.server.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

private const val SEEDED = "seeded-before-port"

/**
 * The bootstrap administrator exists before the web server answers anything.
 *
 * It was seeded at ApplicationReadyEvent, which comes after the port is open,
 * so a sign-in arriving the moment a fresh container first answered was refused
 * for the half second before the account existed - 0.9.9.10's image check met
 * it twice. Asked when the web server reports itself started, the account has
 * to be there already.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "orknux.bootstrap-admin.username=$SEEDED",
        "orknux.bootstrap-admin.password=a long enough password for this",
    ],
)
@Import(BootstrapAdminBeforePortTest.Watch::class)
class BootstrapAdminBeforePortTest(
    @Autowired val watch: SeenAtStart,
    @Autowired val users: AppUserRepository,
) {

    // A database kept between runs would otherwise answer yes from the last one.
    @AfterEach
    fun forget() {
        users.findByUsername(SEEDED)?.let(users::delete)
    }

    @Test
    fun `the administrator exists when the port opens`() {
        assertThat(watch.existed).isTrue()
    }

    class SeenAtStart(private val users: AppUserRepository) : ApplicationListener<WebServerInitializedEvent> {
        @Volatile
        var existed: Boolean? = null

        override fun onApplicationEvent(event: WebServerInitializedEvent) {
            existed = users.findByUsername(SEEDED) != null
        }
    }

    @TestConfiguration
    class Watch {
        @Bean
        fun seenAtStart(users: AppUserRepository) = SeenAtStart(users)
    }
}
