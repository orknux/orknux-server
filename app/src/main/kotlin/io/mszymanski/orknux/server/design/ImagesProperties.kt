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
    /**
     * How many of Design's queued pictures are drawn at the same time. One,
     * because a local image server draws one picture at a time and a second
     * sent beside it only makes both slower; a hosted model can take more.
     */
    val drawsAtOnce: Int = 1,
    /**
     * How strongly Harmonize redraws a picture through a Stable Diffusion
     * WebUI, as its denoising strength. Low, because the point is to blend the
     * seams a Magic edit left, not to draw another picture.
     */
    val harmonizeStrength: Double = 0.3,
    /**
     * The longest side, in pixels, Harmonize redraws a picture at: a larger
     * picture is scaled down for the WebUI and its answer scaled back up. A
     * checkpoint draws well near the size it was trained at and slowly past it
     * - a 2048 square sent to an SD 1.5 checkpoint estimated thirteen hours.
     */
    val harmonizeSize: Int = 1024,
)
