package io.mszymanski.orknux.server.obj

import org.springframework.boot.context.properties.ConfigurationProperties

/** Where a fresh installation starts on what an object may be; Admin > Settings holds what it is now. */
@ConfigurationProperties(prefix = "orknux.objects")
data class ObjectsProperties(
    /**
     * How deep an object's shape may go - an object inside an object inside
     * an object - and how deep a value is checked against one. The same
     * number bounds what a JSON Schema is read to, what the describers print
     * for a model, and what a webhook's body is matched against.
     */
    val maxDepth: Int = 30,
)
