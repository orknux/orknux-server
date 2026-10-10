package io.mszymanski.orknux.server.tin

import io.mszymanski.orknux.server.graphql.Refusal

/*
 * What the tins refuse, each said in English and as a code a client can
 * translate - see RefusedError.kt. TinExceptionResolver maps them.
 */

class TinNotFoundException(val id: String) : RuntimeException("There is no tin $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class TinNameTakenException(val name: String) : RuntimeException("A tin named \"$name\" already exists"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

/** Not in the contract the screen was written against; it shows the English. */
class TinNameInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name a tin can have. Give it a name of 1 to 255 characters."), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class TinAddressInvalidException(val address: String) : RuntimeException(
    "\"$address\" is not an address a tin can be reached at. Give an http or https URL, such as http://orktin:8080.",
), Refusal {
    override val arguments get() = mapOf("address" to address)
}

class LocalTinNotRemovableException : RuntimeException(
    "The local tin cannot be removed. Switch it off in Admin Settings instead.",
)

/** A tin that would not answer, or answered with something that is not orktin. */
class TinUnreachableException(val name: String, val reason: String? = null) : RuntimeException(
    "Tin $name could not be reached" + (reason?.let { ": $it" } ?: ""),
), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class LocalTinRuntimeUnknownException(val runtime: String) : RuntimeException(
    "\"$runtime\" is not a runtime the local tin has. Choose local or docker.",
), Refusal {
    override val arguments get() = mapOf("runtime" to runtime)
}

class LocalTinMaxSessionsOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of sessions the local tin can hold. " +
        "Choose between $MIN_LOCAL_TIN_SESSIONS and $MAX_LOCAL_TIN_SESSIONS.",
), Refusal {
    override val arguments get() = mapOf("count" to count)
}

class LocalTinTtlOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a local tin session can last. " +
        "Choose between $MIN_LOCAL_TIN_TTL and $MAX_LOCAL_TIN_TTL.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class LocalTinIdleTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a local tin session can sit idle. " +
        "Choose between $MIN_LOCAL_TIN_IDLE and $MAX_LOCAL_TIN_IDLE.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class TinCommandWaitOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds to wait for a tin command. " +
        "Choose between $MIN_TIN_COMMAND_WAIT and $MAX_TIN_COMMAND_WAIT.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class TinCommandTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a tin command can run for. " +
        "Choose between $MIN_TIN_COMMAND_TIMEOUT and $MAX_TIN_COMMAND_TIMEOUT.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class TinOutputBytesOutOfRangeException(val bytes: Int) : RuntimeException(
    "$bytes is not a number of bytes of a tin command's output to hand an agent. " +
        "Choose between $MIN_TIN_OUTPUT_BYTES and $MAX_TIN_OUTPUT_BYTES.",
), Refusal {
    override val arguments get() = mapOf("bytes" to bytes)
}

class TinRequestTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds to wait for a tin to answer. " +
        "Choose between $MIN_TIN_REQUEST_TIMEOUT and $MAX_TIN_REQUEST_TIMEOUT.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}
