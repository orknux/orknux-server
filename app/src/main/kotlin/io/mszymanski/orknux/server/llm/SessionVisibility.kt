package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * Who is reading sessions, as the one rule in [SessionVisibility] needs it.
 *
 * Three facts and nothing else, because they are also what the list queries
 * are handed - the SQL half of the rule reads exactly these, so the two halves
 * cannot be told different things.
 *
 * @property everyone an installation administrator, who reads every session.
 * @property person the name of whoever is signed in, or the empty string where
 *   nobody is - an agent working on its own. Empty matches no chat, because no
 *   chat is started by nobody.
 * @property chat the chat family the reading agent is working in -
 *   `chat:<conversation id>` - or the empty string. An agent may read the chat
 *   it is answering in and the sessions it asked from there, whether or not a
 *   person is signed in on its thread, and nothing else private.
 */
data class SessionReader(val everyone: Boolean, val person: String, val chat: String)

/**
 * Who may see a session, decided here and nowhere else.
 *
 * A chat is private to the person who started it ([io.mszymanski.orknux.server.chat.ChatOwnership]),
 * and every chat with an agent writes a session keyed `chat:<conversation id>`.
 * That session is the chat's transcript in another form, so it is private in
 * the same way: readable by the chat's owner and by an installation
 * administrator, and to everybody else who can see the workspace it is not
 * there at all - not listed, not counted, and asked for by id it answers
 * exactly as an id nothing was saved under.
 *
 * The sessions an agent asks from a chat are keyed under it
 * (`chat:<id>:ask-…`, [LlmSessionRecorder.openUnder]) and go with it: they are
 * the chat's work, written from what the person said, so they are the chat
 * owner's too.
 *
 * Every other session - a workflow's, a task's, a Slack thread's - is the
 * workspace's, as it always was, including one a person carried on in a chat
 * with "Continue in chat": that session was keyed by its own work and stays
 * shared.
 *
 * What makes a session a chat's is the shape of its key, `chat:` and a UUID,
 * which only [io.mszymanski.orknux.server.chat.ChatService] composes - and not
 * whether the chat still exists. A chat deleted while its session is kept
 * leaves a session nobody owns, and that is read by administrators only rather
 * than becoming everybody's. [READABLE_SQL] is the same rule for the list
 * queries, so a page, its count and its total are filtered by the database
 * rather than after paging; `SessionPrivacyTest` holds the two halves to one
 * answer.
 */
@Component
class SessionVisibility(
    private val access: WorkspaceAccess,
    private val sessions: LlmSessionRepository,
    private val chats: ChatSessionRepository,
) {

    /**
     * The caller, read off the thread.
     *
     * @param within the session the caller is itself working in, where it is an
     *   agent in one - `OrknuxScope.session`. Never something a request names:
     *   a person's door passes nothing.
     */
    fun reader(within: Long? = null): SessionReader = SessionReader(
        everyone = access.isAdmin(),
        person = if (access.signedIn()) SecurityContextHolder.getContext().authentication?.name.orEmpty() else "",
        chat = within?.let { sessions.findByIdOrNull(it) }?.let { chatOf(it.sessionKey) }.orEmpty(),
    )

    /**
     * The privacy half alone: whether [reader] may read this session, given
     * that they can see its workspace. For a door that has settled the
     * workspace already - the orknux_* tools, whose scope is the workspace.
     */
    fun mayRead(session: LlmSession, reader: SessionReader = reader()): Boolean {
        if (reader.everyone) return true
        val chat = chatOf(session.sessionKey) ?: return true
        if (reader.chat.isNotEmpty() && reader.chat == chat) return true
        if (reader.person.isEmpty()) return false
        val owner = chats.findByConversationId(chat.removePrefix(CHAT_KEY_START))
            ?.takeIf { it.workspaceId == session.workspaceId }
            ?: return false
        return owner.userId == reader.person
    }

    /** The session behind an id, or null where it is not there, its workspace is not the caller's, or it is somebody else's chat. */
    fun readable(id: Long): LlmSession? =
        sessions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) && mayRead(it) }

    /**
     * The same, for a door that refuses: [LlmSessionNotFoundException] for a
     * session that is not there and for somebody else's chat alike. A workspace
     * the caller cannot see is refused as it always was.
     */
    fun requireReadable(id: Long): LlmSession {
        val session = sessions.findByIdOrNull(id) ?: throw LlmSessionNotFoundException(id)
        access.requireVisible(session.workspaceId)
        if (!mayRead(session)) throw LlmSessionNotFoundException(id)
        return session
    }

    companion object {
        private const val CHAT_KEY_START = "chat:"

        /**
         * `chat:` and a UUID, as a SQL `LIKE` - the spelling [chatOf] checks.
         * The underscores are any one character; the hyphens are the UUID's.
         */
        const val CHAT_KEY_LIKE = "chat:________-____-____-____-____________%"

        /** How long `chat:<uuid>` is. */
        private const val CHAT_KEY_LENGTH = 41

        /**
         * The chat family a session key belongs to - `chat:<conversation id>` -
         * or null for a session that is not a chat's. Spelled to match
         * [CHAT_KEY_LIKE] character for character.
         */
        fun chatOf(key: String): String? {
            if (key.length < CHAT_KEY_LENGTH || !key.startsWith(CHAT_KEY_START)) return null
            if (listOf(13, 18, 23, 28).any { key[it] != '-' }) return null
            return key.substring(0, CHAT_KEY_LENGTH)
        }

        /**
         * [mayRead] in JPQL, over a session aliased `s`, reading the
         * [SessionReader]'s three fields as `:everyone`, `:person` and `:chat`.
         */
        const val READABLE_SQL = """
            (
              :everyone = true
              or s.sessionKey not like '$CHAT_KEY_LIKE'
              or (:chat <> '' and (s.sessionKey = :chat or s.sessionKey like concat(:chat, ':%')))
              or exists (
                select c.id from io.mszymanski.orknux.server.chat.ChatSession c
                where c.workspaceId = s.workspaceId
                  and c.userId = :person
                  and (
                    s.sessionKey = concat('chat:', c.conversationId)
                    or s.sessionKey like concat('chat:', c.conversationId, ':%')
                  )
              )
            )
        """
    }
}
