package io.mszymanski.orknux.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * The deployable. Every module ends up in this one process, so this is where
 * they are wired together: the modules are separate Maven artifacts and cannot
 * see each other's code, but their beans, entities and repositories all belong
 * to the same context.
 */
@SpringBootApplication(scanBasePackages = [MODULES])
@EntityScan(MODULES)
@EnableJpaRepositories(MODULES)
class OrknuxServer

/** Everything under here is part of the platform; nothing else is scanned. */
const val MODULES = "io.mszymanski.orknux"

fun main(args: Array<String>) {
    io.mszymanski.orknux.server.design.JdkXmlParsers.prefer()
    runApplication<OrknuxServer>(*args)
}
