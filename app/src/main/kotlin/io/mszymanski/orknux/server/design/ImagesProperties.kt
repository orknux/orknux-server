package io.mszymanski.orknux.server.design

import org.springframework.boot.context.properties.ConfigurationProperties

/** Where a fresh installation starts on drawing; Admin > Settings holds what it is now. */
@ConfigurationProperties(prefix = "orknux.images")
data class ImagesProperties(
    /**
     * The longest description a picture is drawn from, in characters - on
     * Design's Images page, from a chat and from a task alike. Past it a prompt
     * is a document, and every image endpoint has a ceiling of its own below
     * what a model could be handed.
     */
    val maxPrompt: Int = 2_000,
)
