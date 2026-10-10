package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.server.graphql.Refusal

/*
 * What the boxes refuse, each said in English and as a code a client can
 * translate - see RefusedError.kt. BoxExceptionResolver maps them.
 */

class BoxNotFoundException(val id: String) : RuntimeException("There is no box $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class BoxNameTakenException(val name: String) : RuntimeException("A box named \"$name\" already exists"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

/** Not in the contract the screen was written against; it shows the English. */
class BoxNameInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name a box can have. Give it a name of 1 to 255 characters."), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class BoxLabelRequiredException : RuntimeException(
    "A box needs a label of 1 to 255 characters: it is what an agent is granted the box by.",
)

class BoxAddressInvalidException(val address: String) : RuntimeException(
    "\"$address\" is not an address a box can be reached at. Give an http or https URL, such as http://orkbox:8080.",
), Refusal {
    override val arguments get() = mapOf("address" to address)
}

class LocalBoxNotRemovableException : RuntimeException(
    "The local box cannot be removed. Switch it off in Admin Settings instead.",
)

/** The local box has nothing of its own to edit: its label is fixed, the rest is Admin Settings. */
class LocalBoxNotEditableException : RuntimeException(
    "The local box cannot be changed here. Its label is always local; its other settings are in Admin Settings.",
)

/** A box that would not answer, or answered with something that is not orkbox. */
class BoxUnreachableException(val name: String, val reason: String? = null) : RuntimeException(
    "Box $name could not be reached" + (reason?.let { ": $it" } ?: ""),
), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class LocalBoxRuntimeUnknownException(val runtime: String) : RuntimeException(
    "\"$runtime\" is not a runtime the local box has. Choose local or docker.",
), Refusal {
    override val arguments get() = mapOf("runtime" to runtime)
}

class LocalBoxMaxSessionsOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of sessions the local box can hold. " +
        "Choose between $MIN_LOCAL_BOX_SESSIONS and $MAX_LOCAL_BOX_SESSIONS.",
), Refusal {
    override val arguments get() = mapOf("count" to count)
}

class LocalBoxTtlOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a local box session can last. " +
        "Choose between $MIN_LOCAL_BOX_TTL and $MAX_LOCAL_BOX_TTL.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class LocalBoxIdleTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a local box session can sit idle. " +
        "Choose between $MIN_LOCAL_BOX_IDLE and $MAX_LOCAL_BOX_IDLE.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class BoxCommandWaitOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds to wait for a box command. " +
        "Choose between $MIN_BOX_COMMAND_WAIT and $MAX_BOX_COMMAND_WAIT.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class BoxCommandTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a box command can run for. " +
        "Choose between $MIN_BOX_COMMAND_TIMEOUT and $MAX_BOX_COMMAND_TIMEOUT.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class BoxOutputBytesOutOfRangeException(val bytes: Int) : RuntimeException(
    "$bytes is not a number of bytes of a box command's output to hand an agent. " +
        "Choose between $MIN_BOX_OUTPUT_BYTES and $MAX_BOX_OUTPUT_BYTES.",
), Refusal {
    override val arguments get() = mapOf("bytes" to bytes)
}

class LocalBoxOutputKeptOutOfRangeException(val bytes: Int) : RuntimeException(
    "$bytes is not a number of bytes of a local box command's output to keep. " +
        "Choose between $MIN_LOCAL_BOX_OUTPUT_KEPT and $MAX_LOCAL_BOX_OUTPUT_KEPT.",
), Refusal {
    override val arguments get() = mapOf("bytes" to bytes)
}

class BoxOutputShownOutOfRangeException(val bytes: Int) : RuntimeException(
    "$bytes is not a number of bytes of a box command's output to show in a conversation. " +
        "Choose between $MIN_BOX_OUTPUT_SHOWN and $MAX_BOX_OUTPUT_SHOWN.",
), Refusal {
    override val arguments get() = mapOf("bytes" to bytes)
}

class BoxRequestTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds to wait for a box to answer. " +
        "Choose between $MIN_BOX_REQUEST_TIMEOUT and $MAX_BOX_REQUEST_TIMEOUT.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}
