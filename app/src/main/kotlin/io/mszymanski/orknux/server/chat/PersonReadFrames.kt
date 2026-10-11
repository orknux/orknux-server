package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.llm.PersonSaidInTurn
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Tells the open chat that the agent has read what the person wrote while it
 * was working.
 *
 * A message posted mid-turn waits in the inbox for the agent's next step, and
 * a model reading a long prompt can take minutes to get there. The page said
 * "Sent" the whole time, which read as a message the agent had ignored; it
 * says "Queued" now, and this frame is what turns that into "Read".
 */
@Component
class PersonReadFrames(
    private val chats: ChatSessionRepository,
    private val generations: ChatGenerations,
) {

    @EventListener
    fun read(said: PersonSaidInTurn) {
        val chat = chats.findFirstByLlmSessionId(said.sessionId) ?: return
        val id = chat.id ?: return
        generations.current(id)?.post("personRead", mapOf("text" to said.text))
    }
}
