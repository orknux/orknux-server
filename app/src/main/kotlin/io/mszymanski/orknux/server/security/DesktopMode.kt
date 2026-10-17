package io.mszymanski.orknux.server.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.net.InetAddress
import kotlin.system.exitProcess

/**
 * Whether this server is the desktop app's, and run for one person only.
 *
 * The desktop app starts the jar with authentication off and listens on the
 * loopback address alone, which makes "anyone who reaches this administers
 * it" true and alarming and also beside the point: the only one who can reach
 * it is the person at the machine. [localOnly] is what says so - all three of
 * the desktop flag, NONE, and a loopback bind, so the flag can never quiet the
 * warning on a server somebody else can reach.
 */
@Component
class DesktopMode(
    @Value("\${orknux.security.desktop:false}") private val desktop: Boolean,
    @Value("\${server.address:}") private val address: String,
    private val properties: SecurityProperties,
) {
    val localOnly: Boolean
        get() = desktop && properties.authMethod == AuthMethod.NONE && loopback(address)

    /** Who that person is, as the machine names them. */
    val person: String
        get() = System.getProperty("user.name")?.trim()?.ifEmpty { null } ?: "you"

    private fun loopback(bound: String): Boolean =
        bound.isNotBlank() && runCatching { InetAddress.getByName(bound).isLoopbackAddress }.getOrDefault(false)
}

/**
 * The desktop app's way to stop the server: closing its standard input.
 *
 * A Java process with no window cannot be asked to stop on Windows - there is
 * no SIGTERM to send it, only a kill - so a quit that waited and then killed
 * cut off whatever the server was writing. Reading standard input to its end
 * is a signal every platform has. Only under the desktop flag: a container's
 * input is closed from the start, and would stop the server as it began.
 */
@Component
class DesktopStop(
    @Value("\${orknux.security.desktop:false}") private val desktop: Boolean,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!desktop) return
        Thread({
            runCatching { while (System.`in`.read() >= 0) Unit }
            log.info("The desktop app closed this server's input; stopping.")
            exitProcess(SpringApplication.exit(context))
        }, "desktop-stop").apply { isDaemon = true }.start()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DesktopStop::class.java)
    }
}
