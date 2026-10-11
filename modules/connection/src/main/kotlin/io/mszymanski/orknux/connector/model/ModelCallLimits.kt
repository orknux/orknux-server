package io.mszymanski.orknux.connector.model

import java.time.Duration

/**
 * How long one call to a model may take, start to finish.
 *
 * The OpenAI SDK gives up on any request after ten minutes unless told
 * otherwise, and nothing here told it. A local model that thinks for a long
 * time - Qwen on llama.cpp, eighteen thousand tokens of reasoning at thirty a
 * second - was cut at exactly 600 seconds, mid-thought and well inside its
 * output limit, and the failure read as the server being unreachable. How long
 * is long enough depends on the hardware the model runs on, so it is a number
 * an administrator sets. Declared here and answered by the app, which keeps it
 * among the installation's settings: this module holds no settings of its own.
 */
fun interface ModelCallLimits {

    fun answer(): Duration

    companion object {
        /** What a fresh installation allows: an hour. */
        val DEFAULT: Duration = Duration.ofMinutes(60)

        /** The limit a fresh installation has, for whoever is not handed one. */
        val FRESH = ModelCallLimits { DEFAULT }
    }
}
