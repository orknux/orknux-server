package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * One conversation, as this application knows it.
 *
 * The messages are not here. They live in Spring AI's chat memory store, keyed
 * by [conversationId] — which is the reason for using it: a workflow run will
 * key a conversation the same way, so every agent in that run reads and writes
 * one thread instead of each keeping its own. What this holds is everything
 * that store has no opinion about: who owns it, what it is called, what answers
 * it, and whether it is pinned.
 */
@Entity
@Table(name = "chat_session")
class ChatSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** A UUID: Spring AI's column is VARCHAR(36), which is exactly one. */
    @Column(name = "conversation_id", nullable = false, length = 36)
    val conversationId: String,

    @Column(nullable = false, length = 200)
    var title: String,

    /** The LDAP uid of whoever started it; the sidebar says USER CHATS. */
    @Column(name = "user_id", nullable = false, length = 120)
    val userId: String,

    /** Null once the model it named was removed, which leaves the history readable. */
    @Column(name = "model_id")
    var modelId: Long? = null,

    /**
     * The agent answering, when it is one rather than a bare model.
     *
     * The model stays set alongside: an agent supplies one, and keeping what was
     * actually used means the history still says what answered it after the
     * agent is edited or deleted.
     */
    @Column(name = "agent_id")
    var agentId: Long? = null,

    /**
     * The LLM session this chat writes into, or null for a chat that needs
     * none.
     *
     * Two ways it gets one. It was opened from a session's page, and is
     * continuing a conversation an agent was already having — that is a
     * pointer, set before the chat exists. Or it has an agent of its own, and
     * `ChatService.recording` opens it one on the first send: an agent calls
     * tools, and what a tool returned survives nowhere else once its round has
     * ended.
     *
     * The second of those bends a rule worth restating. A session is found by a
     * key its caller computed, and a chat computes no key — one invented for a
     * chat names a conversation nothing else can arrive at, which is the
     * opposite of what a session is for. It is bent only where a chat has
     * something a thread cannot hold, and bent as narrowly as it can be: the
     * key is this chat's conversation id, so nothing else can arrive at it even
     * by accident. A chat with a bare model calls no tools, has nothing to
     * keep, and still gets nothing at all.
     *
     * Never moved once set, which is why nothing outside that one method
     * assigns it. What was already said is copied into this chat's thread when
     * it opens, and a chat that changed session afterwards would be holding one
     * conversation's words while writing into another's.
     */
    @Column(name = "llm_session_id")
    var llmSessionId: Long? = null,

    @Column(nullable = false)
    var pinned: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** Orders RECENT, and is null on a chat nobody has said anything in. */
    @Column(name = "last_message_at")
    var lastMessageAt: OffsetDateTime? = null,

    /**
     * What this whole chat has read and written, added to as each turn lands.
     *
     * A running total rather than a table of turns: it is one number and one
     * number is what it is for. `chat_answer_take` set out what a chat-side
     * table has to justify and a per-turn one would clear the bar without
     * earning anything - `model_usage_day` already holds every call at the
     * grain an invoice is read at, and a per-turn row here would need to say
     * which turn, which a regenerate makes ambiguous exactly where the money
     * is.
     *
     * Every round of every turn, an agent's lookups included, because that is
     * what the provider charged for and what #227 already reports on the answer
     * itself. A turn that failed halfway adds nothing: what is added is added by
     * [ChatService.finishSend], which only an answered turn reaches, and a
     * failure carries no counts to add anyway.
     *
     * Tokens, never money. Prices belong to the model and models are repriced,
     * so a stored money total is arithmetic nobody can check afterwards - and a
     * chat can be moved onto another model mid-conversation, so costing the
     * total at whichever model answers now would be no better. The per-answer
     * line keeps the money, where the model and its prices are both in front of
     * you.
     */
    @Column(name = "spent_input_tokens", nullable = false)
    var spentInputTokens: Long = 0,

    @Column(name = "spent_output_tokens", nullable = false)
    var spentOutputTokens: Long = 0,

    /**
     * How many pictures this chat has had drawn in it.
     *
     * Counted rather than costed in tokens. An image model charges per picture
     * and reports no counts at all, so a drawn picture adds nought to the two
     * above; left at that, a chat that spent real money on pictures would read
     * as a chat that spent nothing. It is said beside the tokens rather than
     * folded into them, because a picture is not a token.
     */
    @Column(name = "spent_pictures", nullable = false)
    var spentPictures: Int = 0,
)

interface ChatSessionRepository : JpaRepository<ChatSession, Long> {

    /**
     * The sidebar, in the order it draws: pinned first, then by when each was
     * last spoken in, then by when it was made — a new chat has no messages and
     * would otherwise sort last.
     */
    fun findByWorkspaceIdAndUserIdOrderByPinnedDescLastMessageAtDescCreatedAtDesc(
        workspaceId: Long,
        userId: String,
    ): List<ChatSession>

    fun findByConversationId(conversationId: String): ChatSession?

    /** The chat recording into this LLM session, for something arriving at it; see `ChatWake`. */
    fun findFirstByLlmSessionId(llmSessionId: Long): ChatSession?

    fun findByModelId(modelId: Long): List<ChatSession>
}

class ChatSessionNotFoundException(val id: Long) : RuntimeException("No chat with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/** An agent that cannot answer, and why — the picker says so rather than failing later. */
class ChatAgentUnusableException(val says: String) : RuntimeException(says), Refusal {

    override val arguments get() = mapOf("says" to says)
}

/**
 * A workspace with no agent to chat with.
 *
 * A condition that did not exist until a chat on a bare model stopped being
 * something this product opens (issue #295): before that every workspace with a
 * model could hold a conversation, whether or not anybody had built an agent.
 *
 * Its own refusal, and not [ChatAgentUnusableException] with a sentence in it,
 * because the two ask for different things. An unusable agent is one to pick
 * again; this one is one to *make*, and the screen that catches it says so and
 * offers the way — which it can only do if it can tell the two apart by code.
 */
class ChatAgentMissingException :
    RuntimeException("This workspace has no agent to chat with; add one first")

/**
 * A session that cannot be continued here, and why.
 *
 * Refused at the start rather than at the first send. A chat bound to a session
 * it may not write into is a chat that looks like a continuation and is not one,
 * and the first thing anybody would do with it is say something into nowhere.
 */
class ChatLlmSessionUnusableException(val says: String) : RuntimeException(says), Refusal {

    override val arguments get() = mapOf("says" to says)
}

class ChatTitleInvalidException : RuntimeException("A chat needs a title")

class ChatMessageEmptyException : RuntimeException("There is nothing to send")

class ChatDisabledException : RuntimeException("Chat is turned off for this installation")

class ChatModelNotChosenException :
    RuntimeException("This chat has no model to answer with; choose one first")

class ChatModelUnusableException(val reason: String) : RuntimeException(reason), Refusal {

    override val arguments get() = mapOf("reason" to reason)
}

