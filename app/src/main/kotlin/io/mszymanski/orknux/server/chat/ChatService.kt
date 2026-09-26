package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.llm.LlmSessionKey
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.RememberedTurn
import io.mszymanski.orknux.server.llm.SessionMemoryBudget
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Chats, and the history in them.
 *
 * The history is Spring AI's — [ChatMemoryRepository], keyed by the session's
 * conversation id. Nothing here keeps its own copy of a message, which is what
 * makes this reusable: a workflow run will key a conversation the same way, and
 * every agent in that run will read and append to one thread instead of each
 * carrying its own.
 *
 * Spring AI's store holds a role, some text and an order, and nothing else. What
 * a message was answered by, and how long it took, are this application's
 * business, so they are on the session rather than pretended into the store.
 */
@Service
class ChatService(
    private val sessions: ChatSessionRepository,
    private val history: ChatMemoryRepository,
    private val takes: ChatAnswerTakeRepository,
    private val thoughts: ChatMessageThinkingRepository,
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
    private val entityManager: EntityManager,
    private val llmSessions: LlmSessionRepository,
    private val recorder: LlmSessionRecorder,
    private val budgets: SessionMemoryBudgets,
    /** Keeps a long chat inside the window its model will accept; see [ChatCompaction]. */
    private val compaction: ChatCompaction,
    private val workspaces: WorkspaceRepository,
    /** The working files a chat's agent keeps in the chat's session; see [ScratchpadTools]. Issue #445. */
    private val scratchpads: ScratchpadTools,
) {

    /**
     * How much of a session this chat is allowed to carry.
     *
     * The agent's share of the model the chat is actually using, which for a
     * chat is not always the agent's own: the picker at the top of a chat can
     * point it at another model, and the budget has to be a share of the window
     * the request will really be made against.
     *
     * A chat with no agent has no share of its own and falls through to the
     * workspace default, which is the same step an agent that sets nothing
     * takes - the default is the workspace's answer for the sessions held in
     * it, and a chat is one of those. Where the workspace has set none either,
     * it is the built-in allowance, which is what every chat had before.
     */
    private fun budget(chat: ChatSession): SessionMemoryBudget {
        val share = chat.agentId?.let { agents.findByIdOrNull(it)?.memoryShare }
        return budgets.budget(share, chat.workspaceId, chat.modelId)
    }

    fun sessions(workspaceId: Long, userId: String): List<ChatSession> =
        sessions.findByWorkspaceIdAndUserIdOrderByPinnedDescLastMessageAtDescCreatedAtDesc(workspaceId, userId)

    /**
     * The caller's chats in this workspace that said the given thing.
     *
     * Titles are searched on the screen, where every chat is already held; this
     * is the other question — what was actually said — and it is asked of the
     * store rather than by loading every conversation to look through it.
     *
     * Read straight from Spring AI's table because its repository knows one way
     * of finding a message, by conversation. Only the ids come back: the screen
     * has the chats and only needs to know which of them to keep.
     */
    fun mentioning(workspaceId: Long, userId: String, text: String): List<Long> {
        val looking = text.trim()
        if (looking.isEmpty()) return emptyList()

        val held = sessions(workspaceId, userId).associateBy { it.conversationId }
        if (held.isEmpty()) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val found = entityManager
            .createNativeQuery(
                """
                SELECT DISTINCT conversation_id FROM spring_ai_chat_memory
                WHERE conversation_id IN (:conversations) AND content ILIKE :looking ESCAPE '!'
                """.trimIndent(),
            )
            .setParameter("conversations", held.keys)
            // Escaped, so a chat about 100% or a file_name is searched for and
            // not turned into a wildcard by the search itself.
            .setParameter("looking", "%" + looking.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
            .resultList as List<String>

        return found.mapNotNull { held[it]?.id }
    }

    fun session(id: Long): ChatSession? = sessions.findByIdOrNull(id)

    /**
     * Everything said in one chat, oldest first, as the store kept it - and for
     * a chat continuing a session, who said the part it did not say itself, and
     * what was looked up in between.
     *
     * The store keeps a role and some text, so a turn carried in from a session
     * comes back indistinguishable from one this chat produced, and the screen
     * signs every one of them with the chat's own model. In a chat opened to
     * work out what an agent did, that is the most misleading line on the page:
     * the agent's own words, under somebody else's name. So the names are put
     * back here, from the session they came out of.
     *
     * And with them the calls, which were never in the thread at all - [seed]
     * copies what was *said*, for a reason written there that has not changed.
     * That reason is about the prompt. This is the reading, and an answer shown
     * with no sign of the lookup that produced it reads as the agent having
     * known something it went and found out. Two audiences, two answers: what
     * goes to the model is [ChatMemoryRepository]'s thread and is untouched by
     * any of this, and what goes on the page is assembled here.
     *
     * **And the calls this chat's own agent made, which used to be left out.**
     * The reason they were is worth keeping, because it still holds for the
     * other case: a session is shared by key, so a line written into somebody
     * else's session after this chat opened may have been written by a run that
     * has nothing to do with this chat, and a lookup drawn under an answer that
     * did not make it is a worse lie than the one this fixes.
     *
     * A chat's own session is the one shape that argument does not cover. It is
     * opened by [recording] under this chat's conversation id, which nothing
     * else can compute and nothing else writes into - so every line in it was
     * written by this chat, and there is no other run to confuse it with. That
     * is the whole of why [ownSession] is asked, and why it is asked of the key
     * rather than of a flag somebody would have to keep true.
     *
     * The placing is [carried]'s and is unchanged: the thread keeps a role,
     * some text and an order and no clock at all, so the calls are put back by
     * matching words and only inside the stretch that lined up. A chat whose
     * thread does not line up shows the calls it can be sure of, which for a
     * chat that has drifted is none - the same safe wrong answer as before.
     */
    fun messages(session: ChatSession): List<ChatMessage> {
        /*
         * What each answer said the times it was asked again, put back beside
         * the one that stands. Keyed by place in the thread, which is what the
         * take was written against - so this reads the thread once, with its
         * own index, before anything below reorders or interleaves it.
         */
        val earlier = takes
            .findByChatSessionIdOrderByIdAsc(requireNotNull(session.id))
            .groupBy { it.messageIndex }
        /*
         * And what the model thought on the way to each of them, put back
         * beside the answer it belongs to.
         *
         * Keyed by place in the thread, the same as a take and read the same
         * way - which is what makes every answer carry its own rather than only
         * the one being written now. The first build of this kept none of it,
         * so reloading the page lost the reasoning for every answer on it.
         */
        val thought = thoughts.findByChatSessionId(requireNotNull(session.id)).associateBy { it.messageIndex }
        /*
         * When each line was sent, read straight off the store's own table.
         *
         * Spring AI's repository answers content and role and keeps the
         * timestamp to itself, so the column is read here and zipped by place
         * in the thread - the same ordering the repository reads by. Two lines
         * written in the same millisecond could in principle swap, and would
         * then display the same time, which is the answer either way.
         */
        val sentAt = sentAt(session.conversationId)
        val thread = history.findByConversationId(session.conversationId)
            .mapIndexed { at, message ->
                ChatMessage(message).copy(
                    takes = earlier[at]?.map { it.content }.orEmpty(),
                    thinking = thought[at]?.content,
                    thinkingMillis = thought[at]?.millis?.takeIf { it > 0 },
                    at = sentAt.getOrNull(at)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )
            }
        val llmSessionId = session.llmSessionId ?: return thread
        /*
         * How far into the session to read, which is two different questions.
         *
         * A chat continuing somebody else's session reads it as it stood when
         * this chat opened, under the same budget the copy was taken under, so
         * the page shows the stretch that was actually carried rather than one
         * merely like it.
         *
         * A chat that opened a session of its own reads the whole of it. There
         * was no copy and so no boundary: every line in that session was
         * written by this chat, and `createdAt` is before all of them - which
         * is why an agent's lookups were on the screen while it made them and
         * gone the moment somebody reloaded the page.
         */
        val upTo = if (ownSession(session)) OffsetDateTime.now() else session.createdAt
        return carried(thread, recorder.readBefore(llmSessionId, upTo, budget(session)))
    }

    /**
     * When each line of a conversation was written, in thread order.
     *
     * Straight off the store's table with a native query, because Spring AI's
     * repository reads the same rows and does not pass the column on. Ordered
     * the way the repository orders - time, then the sequence that breaks
     * ties - so index N here is index N there.
     *
     * The column is a bare TIMESTAMP written by this JVM, so it is read back in
     * this JVM's zone; a chat is not a flight log, and same-machine round
     * tripping is the contract the store itself relies on.
     */
    private fun sentAt(conversationId: String): List<OffsetDateTime> =
        entityManager
            .createNativeQuery(
                """select "timestamp" from SPRING_AI_CHAT_MEMORY where conversation_id = ?1 order by "timestamp", sequence_id""",
            )
            .setParameter(1, conversationId)
            .resultList
            .map { held ->
                when (held) {
                    is java.sql.Timestamp -> held.toLocalDateTime().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime()
                    is OffsetDateTime -> held
                    // SQLite hands back text; anything unreadable is a line with no time.
                    else -> runCatching { java.time.LocalDateTime.parse(held.toString().replace(" ", "T")) }
                        .getOrNull()?.atZone(java.time.ZoneId.systemDefault())?.toOffsetDateTime()
                }
            }
            .mapNotNull { it }

    /**
     * Whether the session this chat is bound to is one it opened itself.
     *
     * Asked of the key rather than kept in a column, because the key is already
     * the answer: [recording] opens a chat's own session under [CHAT_PREFIX]
     * and this chat's conversation id, which nothing else can compute. A
     * session bound by [start] was named by whoever opened the chat and carries
     * whatever key its own work chose.
     *
     * A column saying the same thing would be a second answer to a question the
     * data already answers, and one that could be wrong.
     */
    /**
     * Files what the model thought under the answer it belongs to.
     *
     * Replaced rather than added to, which is what makes a regenerate correct:
     * asking again produces a new answer at the same place in the thread, and
     * leaving the old thinking under it would be the same lie as a lookup drawn
     * under an answer that did not make it. The unique constraint says the same
     * thing; this is what keeps the constraint from being the thing that
     * reports it.
     *
     * Nothing is written for a model that thought nothing, so a chat with an
     * ordinary model puts no rows here at all - and a message with no row is
     * drawn with no container rather than an empty one.
     *
     * It never throws. What the model thought is worth keeping and is not worth
     * failing an answer over: the answer is already in the thread by this
     * point, and losing the reasoning leaves a chat that reads exactly as it
     * did before this feature existed.
     */
    private fun keepThinking(session: ChatSession, at: Int, thinking: String, millis: Long) {
        if (thinking.isBlank()) return
        val chatId = session.id ?: return
        try {
            val held = thoughts.findByChatSessionIdAndMessageIndex(chatId, at)
            if (held == null) {
                thoughts.save(
                    ChatMessageThinking(
                        chatSessionId = chatId,
                        messageIndex = at,
                        content = thinking,
                        millis = millis,
                    ),
                )
            } else {
                held.content = thinking
                held.millis = millis
                held.thoughtAt = OffsetDateTime.now()
                thoughts.save(held)
            }
        } catch (failure: Exception) {
            log.warn("What the model thought on chat {} could not be kept", chatId, failure)
        }
    }

    private fun ownSession(session: ChatSession): Boolean {
        val id = session.llmSessionId ?: return false
        val mine = runCatching { LlmSessionKey.of(CHAT_PREFIX, session.conversationId) }.getOrNull() ?: return false
        return llmSessions.findByIdOrNull(id)?.sessionKey == mine
    }

    /**
     * Puts the session's names back on the turns that came from it, and its
     * calls back between them.
     *
     * Matched rather than counted. Nothing recorded how many turns were seeded,
     * and nothing should have to: [seed] copies a run of the session's lines
     * into the head of the thread, so the run can be found again by looking for
     * it - the longest stretch of the thread's opening that reads word for word
     * like a stretch of the session.
     *
     * Which makes the wrong answer the safe one. A thread that does not line up
     * - because the session was emptied, or because the bounds on what is
     * remembered have since moved - names fewer turns rather than naming them
     * wrongly, and a chat that names none of them is exactly the chat we had
     * before.
     *
     * The matching is against what was said and nothing else. A call was never
     * copied into the thread, so a thread turn is never compared with one;
     * counting one as a turn would put every comparison after it a line out and
     * lose the alignment the paragraph above depends on. The calls are put back
     * afterwards, at the places they were recorded in, and only inside the
     * stretch that did line up - outside it there is nothing to be sure they
     * belong between.
     *
     * **And the asides, which the thread never held either.** A round can
     * answer with a message and tool calls in the same reply, and
     * [io.mszymanski.orknux.server.chat.AgentConversation] writes that message
     * into the session before the calls it came with. The thread keeps only
     * what the round finally answered, so an aside is a said line with no turn
     * opposite it - and matched one for one it would knock every comparison
     * after it a line out, which is exactly what a call would have done. So it
     * is stepped over the same way and drawn off the session the same way,
     * under the agent's own name.
     *
     * Only an agent's line is ever stepped over. A person's turn that does not
     * match ends the run, because a thread missing something somebody said is
     * a thread that has drifted rather than one missing an aside.
     *
     * @param read the session as it stood when this chat opened, calls and all.
     */
    private fun carried(thread: List<ChatMessage>, read: List<RememberedTurn>): List<ChatMessage> {
        if (thread.isEmpty() || read.isEmpty()) return thread

        val where = read.indices.filter { !read[it].called }
        val said = where.map { read[it] }
        if (said.isEmpty()) return thread

        var found = 0
        var spans = 0
        var from = 0
        for (start in said.indices.reversed()) {
            val run = aligned(thread, said, start)
            if (run.first > found) {
                found = run.first
                spans = run.second
                from = start
            }
        }

        if (found == 0) return thread

        /*
         * Walked over the session rather than over the thread, because the
         * session is the one that has the calls in it. Every line between the
         * two ends that matched is either a turn the thread holds - taken in
         * order, which is what matching them established - a call, or an aside,
         * and the last two only the session ever held.
         *
         * The same test [aligned] made, in the same order over the same lines,
         * so this walk retraces that alignment rather than guessing at one.
         */
        var turn = 0
        val head = (where[from]..where[from + spans - 1]).map { at ->
            val line = read[at]
            when {
                line.called -> ChatMessage(role = RememberedTurn.CALL, content = line.content, actor = line.actor)
                turn < thread.size && same(thread[turn], line) -> thread[turn++].copy(actor = line.actor)
                else -> ChatMessage(role = line.role, content = line.content, actor = line.actor)
            }
        }
        return head + thread.drop(found)
    }

    /**
     * How far the thread lines up with the session read from [start].
     *
     * @return how many of the thread's turns matched, and how many of the
     *   session's said lines that stretch covers - the second being the larger
     *   of the two wherever the agent said something the thread never kept. The
     *   stretch ends at the last match, so a run is never padded with asides
     *   that lead nowhere.
     */
    private fun aligned(thread: List<ChatMessage>, said: List<RememberedTurn>, start: Int): Pair<Int, Int> {
        var found = 0
        var spans = 0
        var step = 0
        while (found < thread.size && start + step < said.size) {
            val line = said[start + step]
            when {
                same(thread[found], line) -> {
                    found++
                    step++
                    spans = step
                }

                line.role == AGENT_ROLE -> step++
                else -> return found to spans
            }
        }
        return found to spans
    }

    private fun same(message: ChatMessage, line: RememberedTurn) =
        message.role == line.role && message.content == line.content

    /**
     * Opens a chat, optionally continuing an LLM session.
     *
     * @param llmSessionId the session this chat carries on, or null for the
     *   ordinary chat that carries on nothing. What was already said there is
     *   copied into this chat's thread as it opens, which is what makes it a
     *   continuation rather than a blank box — and is why nothing reads the
     *   session again on any later send: the thread already holds it, and
     *   putting it in front of the model twice would have it hear everything
     *   said so far in duplicate.
     */
    @Transactional
    fun start(
        workspaceId: Long,
        userId: String,
        title: String,
        agentId: Long?,
        llmSessionId: Long? = null,
    ): ChatSession {
        val trimmed = title.trim().ifEmpty { throw ChatTitleInvalidException() }
        // Checked before anything is written: a chat bound to a session it may
        // not write into looks like a continuation and is not one.
        llmSessionId?.let { continuable(workspaceId, it) }

        /*
         * What answers: the agent named, or the one this person last talked to.
         *
         * A pair rather than an agent alone because the model comes with it and
         * is written onto the row - the same reasoning [chooseAgent] gives for
         * setting both. Never a model without an agent: that combination is what
         * a bare-model chat is, and this door stopped making them (issue #295).
         */
        val answering = if (agentId != null) answering(workspaceId, agentId) else lastUsed(workspaceId, userId)

        val opened = sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = UUID.randomUUID().toString(),
                title = trimmed.take(TITLE_LENGTH),
                userId = userId,
                modelId = answering.modelId,
                agentId = answering.agentId,
                llmSessionId = llmSessionId,
                createdAt = OffsetDateTime.now(),
            ),
        )
        llmSessionId?.let { seed(opened, it) }
        return opened
    }

    /**
     * Refuses a session this workspace has no business continuing.
     *
     * A session belongs to one workspace, the same way an agent does, and the
     * chat is opened from that workspace's page — so the only way the two
     * disagree is somebody naming an id that is not theirs. Refused by name
     * rather than by silence, because the caller who meant the right session and
     * mistyped deserves to know which half was wrong.
     */
    private fun continuable(workspaceId: Long, llmSessionId: Long) {
        val session = llmSessions.findByIdOrNull(llmSessionId)
            ?: throw ChatLlmSessionUnusableException("That session no longer exists")
        if (session.workspaceId != workspaceId) {
            throw ChatLlmSessionUnusableException("That session belongs to another workspace")
        }
    }

    /**
     * Puts what was already said into the new chat's thread.
     *
     * [LlmSessionRecorder.remembered] rather than the whole transcript: it is
     * already the tail a model can be shown — what was *said*, bounded, oldest
     * first — and the chat wants exactly that. Tool calls and system notes are
     * not turns in a conversation, and a thread holding one would put it in
     * front of the model on every send after this: a call the model never made
     * in this exchange, with no result threaded to it.
     *
     * Which is about the thread, and only the thread. [messages] shows the
     * calls to whoever is reading the chat, off the session and beside the
     * turns rather than in them - the page and the prompt being two different
     * audiences with two different answers.
     */
    private fun seed(chat: ChatSession, llmSessionId: Long) {
        val said = recorder.remembered(llmSessionId, budget(chat))
        if (said.isEmpty()) return
        history.saveAll(
            chat.conversationId,
            said.map { if (it.role == "user") UserMessage(it.content) else AssistantMessage(it.content) },
        )
    }

    /**
     * What a chat answers with: an agent, and the model that agent thinks with.
     *
     * Both non-null. It used to allow a model with no agent, which was a
     * bare-model chat, and a new one is no longer something that can be opened
     * (issue #295). The columns behind it stay nullable because the chats that
     * were opened that way are still there and still work.
     */
    private data class Answering(val modelId: Long, val agentId: Long)

    /**
     * The agent somebody named, checked the way [chooseAgent] checks one.
     *
     * The same four refusals in the same words, because an agent this would
     * accept and the picker would refuse - or the other way round - is two
     * screens disagreeing about the same agent.
     */
    private fun answering(workspaceId: Long, agentId: Long): Answering {
        val agent = agents.findByIdOrNull(agentId) ?: throw ChatAgentUnusableException("That agent no longer exists")
        if (agent.workspaceId != workspaceId) {
            throw ChatAgentUnusableException("That agent belongs to another workspace")
        }
        if (!agent.enabled) throw ChatAgentUnusableException("${agent.name} is not active")
        val model = agent.modelId
            ?: throw ChatAgentUnusableException("${agent.name} has no model chosen, so it cannot answer")
        return Answering(model, agentId)
    }

    /**
     * What a chat answers with when the caller named nobody: whichever agent
     * this person last chatted with in this workspace.
     *
     * ## Why it is remembered here rather than in the browser
     *
     * It is not remembered anywhere. It is *read* — off `chat_session`, which
     * already carries a user, a workspace, an agent, a model and when the chat
     * was last spoken in. Nothing new is stored because there is nothing new to
     * store, and that answer is better than either of the two this could have
     * copied.
     *
     * `recentlyOpened` keeps a trail in local storage on the argument that
     * where somebody has been is theirs and belongs to the machine they are
     * sitting at. `chat_cost_shown` went onto `app_user` on the argument that
     * watching what you spend is a decision that should follow you. This is
     * neither: it is not a preference somebody set, and it is not a trail
     * through the interface — it is a fact about the conversations that exist,
     * and the row it is a fact about is already in the database. A copy in
     * local storage would disagree with it on a second machine and go stale the
     * moment an agent is deleted; a column on `app_user` would be a second
     * place to write on every send, saying what the chat rows already said.
     *
     * ## Scope, and the first visit
     *
     * Per person and per workspace, which falls out of the query rather than
     * being decided: a workspace's agents do not exist in the next one, and
     * pointing a new chat at one of them would be pointing it at nothing.
     *
     * A workspace nobody has chatted in yet has nothing to read, and the
     * fallback is the first agent the workspace has that could answer, in the
     * order the Agents screen lists them. It used to be the first chat model,
     * which was the last place in the product that made a chat on a bare model
     * without anybody asking for one: a fresh workspace's first conversation was
     * inherently bare, whatever the interface offered (issue #295).
     *
     * ## What has since been deleted
     *
     * Read forward through the chats until one of them still names an agent that
     * could answer, rather than taking the newest and hoping. An agent that was
     * deleted, disabled, moved or left without a model fails the same four
     * checks [chooseAgent] applies, so the answer here can never be one the
     * picker would refuse — and a person whose last agent is gone gets the one
     * before it rather than an empty picker.
     *
     * A chat that is on a bare model is skipped rather than repeated. Those
     * chats still work and still answer; what they no longer do is hand their
     * bareness on to the next chat, which is the whole of what was being
     * removed.
     *
     * ## When there is nothing
     *
     * A workspace with no agent at all is refused, by name, rather than given
     * something. That case is new — it is what removing the bare model created —
     * and the screen answers it by saying to add an agent and offering the way,
     * which is a better answer than a conversation nothing can reply to.
     */
    private fun lastUsed(workspaceId: Long, userId: String): Answering {
        val recent = sessions(workspaceId, userId).sortedByDescending { it.lastMessageAt ?: it.createdAt }

        for (chat in recent) {
            val agent = chat.agentId?.let { agents.findByIdOrNull(it) } ?: continue
            if (agent.workspaceId == workspaceId && agent.enabled) {
                agent.modelId?.let { return Answering(it, requireNotNull(agent.id)) }
            }
        }

        val first = agents.findByWorkspaceId(workspaceId, Sort.by("name"))
            .firstOrNull { it.enabled && it.modelId != null }
            ?: throw ChatAgentMissingException()
        return Answering(requireNotNull(first.modelId), requireNotNull(first.id))
    }

    @Transactional
    fun rename(id: Long, title: String): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.title = title.trim().ifEmpty { throw ChatTitleInvalidException() }.take(TITLE_LENGTH)
        return session
    }

    @Transactional
    fun setPinned(id: Long, pinned: Boolean): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.pinned = pinned
        return session
    }

    /**
     * Hands the chat to one of the workspace's agents.
     *
     * The agent's model becomes the chat's, because an agent that cannot be run
     * is not one to hand a conversation to — and a chat answering on some other
     * model would not be answering as what the screen says it is.
     *
     * There is no way back. `chooseModel` was here, and it took the agent off
     * and left the model, which is a bare-model chat made in one press; passing
     * null here did the same thing with one fewer argument. Both are gone with
     * issue #295, and nothing that already exists is changed by their going: a
     * chat on a bare model still opens, still renders and still answers, and
     * this is how it stops being one.
     */
    @Transactional
    fun chooseAgent(id: Long, agentId: Long): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val answering = answering(session.workspaceId, agentId)

        session.agentId = answering.agentId
        session.modelId = answering.modelId
        return session
    }

    /**
     * Deletes the chat and the history with it.
     *
     * The store is keyed by conversation, not by us, so it has to be told: a
     * deleted chat that left its messages behind would be a conversation nobody
     * can reach and nobody can clear.
     */
    @Transactional
    fun delete(id: Long): Boolean {
        val session = sessions.findByIdOrNull(id) ?: return false
        history.deleteByConversationId(session.conversationId)
        sessions.delete(session)
        return true
    }

    /**
     * Asks, in whatever way this chat answers, and in no transaction at all.
     *
     * The middle of a send, and the reason a send is three calls rather than
     * one. Asking a model takes as long as the model takes, and an agent's turn
     * is longer still: the loop calls tools, and every tracker tool opens its
     * own transaction because [io.mszymanski.orknux.server.mcp.IssueTools] says
     * why. A transaction held open across all of that is a connection held out
     * of the pool for a minute on Postgres — and on SQLite it is the whole
     * database, which takes one writer at a time. There the turn's transaction
     * held the write lock, the tool's `REQUIRES_NEW` asked for it on a second
     * connection, waited out the busy timeout twice over and failed at commit:
     * every tracker tool an agent called from a chat came back to the model as
     * `Unable to commit against JDBC Connection`, sixty seconds later, whatever
     * the tool had actually answered - including the sentence naming the title
     * limit, which had been composed and was then thrown away by the commit.
     *
     * So nothing here is transactional, and what has to be written is written
     * either side of it: [beginSend] before, [finishSend] after. It is the shape
     * `ChatStreamAPI` was already composed in, said once for both callers.
     */
    /**
     * @param shed what the door is lending the agent for this round - the chat's
     *   own tools, which are not the agent's and are gone when the round ends.
     *   Built by the caller rather than here, because [ChatTools] has to reach
     *   this service to file what it draws and a bean that reached back would be
     *   a cycle. It is also where `TaskLoop` builds one, and for the same
     *   reason: the thing running the loop is the thing that knows where what
     *   the model makes has to be filed.
     * @param hangup somebody who may give up on this answer while it is still
     *   being worked out, or null for the caller with nobody to walk away. Only
     *   the streaming door passes one: it is the only caller here that has a
     *   reader on the other end of a connection, and therefore the only one that
     *   can lose them.
     */
    fun ask(
        start: ChatSendStart,
        watch: RoundWatch? = null,
        shed: ToolShed? = null,
        hangup: Hangup? = null,
    ): ChatCompletion =
        if (start.agentId == null) {
            // A bare model is offered nothing: no tools of its own, and nothing
            // lent either - there is no round to lend into.
            models.complete(start.modelId, start.turns)
        } else {
            /*
             * An agent may need its tools before it can answer; a bare model
             * cannot. Beside what the door lent, the scratchpads: a chat has a
             * session to keep files in like a task does, and an agent drafting
             * something long in a chat was doing it in its head for want of
             * them. Lent here rather than by each door, so the two doors cannot
             * come to lend different things. Issue #445.
             */
            val lent = sheds(shed, scratchpads.shed(start.llmSessionId))
            conversation.answer(start.modelId, start.agentId, start.turns, start.llmSessionId, lent, watch, hangup)
        }

    /**
     * The first half of a send: everything settled before the model is asked.
     *
     * Split from [finishSend] because a streaming answer takes as long as the
     * model takes, and holding a database transaction open for a minute holds a
     * connection out of the pool for a minute. The user's turn is still written
     * before the call, for the same reason [send] writes it first.
     */
    @Transactional
    /**
     * @param images pictures sent with this message, as `data:` URLs.
     *
     * Handed to the model as part of the turn rather than described in it: a
     * model that can see is sent the picture, and one that cannot ignores the
     * part. They are not written to the history — that store keeps text, and a
     * base64 image in a conversation log is a conversation log nobody can read.
     */
    fun beginSend(
        id: Long,
        text: String,
        images: List<String> = emptyList(),
        /** Called before the summariser is asked, so the stream can say so. */
        compacting: () -> Unit = {},
    ): ChatSendStart {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val message = text.trim().ifEmpty { throw ChatMessageEmptyException() }
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        /*
         * Compacted before the turn is built rather than after one lands, so
         * the turn that would have overflowed is the one that fits. A workspace
         * that has not asked for it is untouched, which is every workspace until
         * somebody sets a threshold. Issue #286.
         */
        val compacted = workspaces.findByIdOrNull(session.workspaceId)?.let { workspace ->
            compaction.compactIfNeeded(workspace, session.conversationId, modelId, beginning = compacting)
        }

        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + UserMessage(message))
        session.lastMessageAt = OffsetDateTime.now()
        val into = recording(session)
        recordSaid(session, into, message)

        return ChatSendStart(
            modelId = modelId,
            agentId = session.agentId,
            llmSessionId = into,
            conversationId = session.conversationId,
            // The turn being sent now is built on its own rather than picked out
            // of the thread by its position, because it is no longer the last
            // thing in the list: what the tools returned goes between.
            turns = briefed(session) +
                thread.map { ChatTurn(role(it), it.text.orEmpty()) } +
                recalled(session, into) +
                ChatTurn("user", message, images),
            compacted = compacted,
        )
    }

    /**
     * The first half of asking the last answer again.
     *
     * [beginSend] with the question already asked. What the chat said last is
     * taken off the thread and kept as a take, and the model is handed the
     * conversation as it stood the moment before it answered — so it is
     * answering the same question rather than answering itself.
     *
     * Nothing is written into the LLM session on the way in, because nobody
     * said anything: [beginSend] records the person's turn there, and a
     * regenerate has no turn of its own to record. The answer is recorded as
     * usual by [finishSend], which is right — it was said, and a transcript
     * that hides the second attempt is a transcript that cannot explain the
     * session's own shape.
     *
     * **Whatever answers now is whatever the chat says answers now.** That is
     * the model or agent the answer was produced with, unless the picker has
     * been moved since — which is the point of moving it, and the reason
     * "regenerate on a different model" needs no second control.
     *
     * What does not come again is a picture. Images are handed to the model as
     * part of the turn and are deliberately never written to the thread — a
     * base64 image in a conversation log is a log nobody can read — and nothing
     * records which message a file arrived with, so there is nothing here to
     * put back. A regenerate asks the words again.
     */
    @Transactional
    fun beginRegenerate(id: Long): ChatSendStart {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        val thread = history.findByConversationId(session.conversationId)
        val last = thread.lastOrNull()
            ?: throw ChatNothingToRegenerateException("Nothing has been said in this chat yet")
        if (last.messageType != MessageType.ASSISTANT) {
            throw ChatNothingToRegenerateException("The last thing in this chat is not an answer")
        }

        val asked = thread.dropLast(1)
        if (asked.none { it.messageType == MessageType.USER }) {
            throw ChatNothingToRegenerateException("There is no question here to ask again")
        }

        // Kept before it comes off, so a press that turns out to have been a
        // mistake has left the answer somewhere it can be read.
        takes.save(
            ChatAnswerTake(
                chatSessionId = requireNotNull(session.id),
                messageIndex = thread.size - 1,
                content = last.text.orEmpty(),
            ),
        )
        history.saveAll(session.conversationId, asked)

        val into = recording(session)
        return ChatSendStart(
            modelId = modelId,
            agentId = session.agentId,
            llmSessionId = into,
            conversationId = session.conversationId,
            turns = briefed(session) +
                asked.map { ChatTurn(role(it), it.text.orEmpty()) } +
                recalled(session, into),
        )
    }

    /**
     * Puts the answer back where asking again came to nothing.
     *
     * [beginRegenerate] takes the answer off the thread before the model is
     * called, because that is the only way to ask the same question twice. A
     * provider that then refuses would leave the conversation ending on the
     * question, with the answer readable only as a take of a turn that is no
     * longer there — which is a worse outcome than the answer somebody did not
     * like, and one they did not ask for.
     *
     * Found by where it would have sat: the take was written against the index
     * the missing answer had, and that index is now the length of the thread.
     * The newest such take is the one this call took off, and it is deleted as
     * it goes back — nothing was displaced, so there is no earlier take to
     * remember.
     */
    @Transactional
    fun abandonRegenerate(id: Long) {
        val session = sessions.findByIdOrNull(id) ?: return
        val thread = history.findByConversationId(session.conversationId)
        val put = takes.findByChatSessionIdOrderByIdAsc(requireNotNull(session.id))
            .lastOrNull { it.messageIndex == thread.size }
            ?: return
        history.saveAll(session.conversationId, thread + AssistantMessage(put.content))
        takes.delete(put)
    }

    /**
     * The second half: what the model finally said goes into the history.
     *
     * Read back rather than appended to what [beginSend] had, because the model
     * was thinking for a while and the thread is the source of truth for what
     * is in it.
     *
     * **And the turn's counts go onto the chat's running total, here.** This is
     * the one place an answered turn passes through - the blocking door, the
     * streaming door and a regenerate all end here - so it is the one place the
     * total can be added to without a second caller being able to forget. It is
     * also, exactly, the place a turn that failed halfway never reaches:
     * `ChatStreamAPI` gives up rather than finishing, and nothing is added,
     * which is the honest answer because a failure carries no counts to add.
     *
     * @param inputTokens what the provider charged for over the whole turn,
     *   every round of it, which is the same figure #227 puts on the answer.
     *   Nought where the provider reported nothing, and nought adds nothing.
     */
    @Transactional
    fun finishSend(
        id: Long,
        answer: String,
        thinking: String = "",
        thinkingMillis: Long = 0,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
    ) {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + AssistantMessage(answer))
        /*
         * Added, never assigned.
         *
         * A regenerated answer counts on top of the one it replaced, because
         * both were paid for. #245 keeps the displaced answer as a take on the
         * ground that it was really said; it was really billed too, and a total
         * that fell when somebody pressed a button that spends money would be
         * the one thing a bill may never do.
         */
        session.spentInputTokens += inputTokens
        session.spentOutputTokens += outputTokens
        // The answer's place in the thread, which is what its thinking is
        // filed under. Taken before nothing else can move it: the thread is
        // only appended to, so the turn just added sits where the old one
        // ended.
        keepThinking(session, thread.size, thinking, thinkingMillis)
        session.lastMessageAt = OffsetDateTime.now()
        // Read off the chat rather than opened: [beginSend] has already opened
        // whatever this chat records into, and opening one here would be a
        // session for an answer whose question was never written down.
        recordAnswer(session, session.llmSessionId, answer)
    }

    /**
     * Writes a drawn picture into the chat, as the one line it is.
     *
     * [beginSend] and [finishSend] one after the other would be the obvious way
     * and would be wrong twice over: they are for a turn a chat model answered,
     * so between them they choose a model this chat may not even have, budget a
     * context window nobody is filling, and hand the description to the chat
     * model as a question it never gets to answer. A picture is complete by the
     * time it arrives here.
     *
     * What goes in is a markdown image pointing at the attachment. In the thread
     * rather than in a table of its own, which is the whole reason a picture
     * survives being drawn: the history is what the chat is read back out of,
     * and a second record of which message carried which file is a second record
     * to keep in step.
     *
     * **The description does not go in with it, and used to.** While a person
     * pressed a button to draw, what they typed was a turn they had taken and
     * belonged in the thread as one. #294 made the drawing something the agent
     * decides on inside its own round, so the description is the model's - and
     * writing it as a user turn would put words in somebody's mouth and then
     * hand them back to the model on the next send as though they had said them.
     *
     * Into the LLM session as well where this chat records into one, under the
     * name a drawing is signed with - a transcript that stops at the picture
     * cannot explain the conversation around it.
     */
    @Transactional
    fun recordPicture(id: Long, said: String) {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + AssistantMessage(said))
        // Counted, and counted as a picture. An image model reports no tokens,
        // so a drawing that only touched the two token columns would leave them
        // where they were and read as a turn that cost nothing.
        session.spentPictures += 1
        session.lastMessageAt = OffsetDateTime.now()

        recording(session)?.let { recorder.agentSaid(it, PICTURE_ACTOR, said) }
    }

    /**
     * The session this chat records into, opened the first time it needs one.
     *
     * Two ways a chat comes to have one. It was opened from a session's page,
     * in which case it is continuing somebody else's conversation and the
     * pointer was set before it existed. Or it has an agent, in which case it
     * needs one of its own - because an agent calls tools, and what a tool
     * returned has nowhere else to survive the round it was fetched in. Without
     * that, the second question about a lookup is answered out of the model's
     * own account of the first.
     *
     * A chat with a bare model still gets nothing, and that is not an oversight
     * being preserved. It calls no tools, so there is nothing to keep, and the
     * rule this bends - that a chat computes no key, because a key is something
     * two callers can independently arrive at - stays true for every chat that
     * does not need it bent. Where it is bent, it is bent as narrowly as it can
     * be: the key is the conversation id, which is this chat's and nothing
     * else's, so nothing can arrive at it by accident and it is plainly not
     * pretending to be a shared conversation.
     *
     * Opened on the first send rather than when the chat is started, because an
     * agent can be chosen at any point in a chat's life and a chat that never
     * gets one should cost nothing.
     */
    private fun recording(chat: ChatSession): Long? {
        chat.llmSessionId?.let { return it }
        if (chat.agentId == null) return null
        return recorder.open(chat.workspaceId, CHAT_PREFIX, chat.conversationId)
            .also { chat.llmSessionId = it }
    }

    /**
     * What this chat's tools returned lately, to go in front of the model.
     *
     * Between the thread and the question rather than inside the thread. The
     * thread is what was said and is appended to for ever; a result put in it
     * would be paid for on every send from then on, with nothing able to drop
     * the oldest one. Read from the session instead, it is bounded, newest
     * first, and assembled fresh each time - which is what lets a chat carry
     * data it could not afford to carry as history.
     */
    private fun recalled(chat: ChatSession, into: Long?): List<ChatTurn> =
        into?.let { recorder.recalled(it, budget(chat)) }.orEmpty()

    /**
     * What the person said, into the session this chat is continuing.
     *
     * Under their own name rather than the chat's title, because a transcript
     * that cannot say who spoke is not a transcript — and the point of this is
     * that a later run reading the session finds what a person told it, not
     * another line from an agent.
     *
     * Written before the model is asked, for the same reason the user's turn
     * goes into the history first: a provider that fails should leave the
     * session holding what was actually said.
     */
    private fun recordSaid(session: ChatSession, into: Long?, said: String) {
        into?.let { recorder.userSaid(it, session.userId, said) }
    }

    /**
     * And what answered, once the answer is whole.
     *
     * Only for a bare model. An agent's answer is already written by
     * [AgentConversation] as its round ends — tool calls and all, which is a
     * record this could not produce — and writing it again here would put every
     * agent answer in the session twice. A bare model has no such round, so
     * nothing else would ever write the line.
     *
     * Called from the end of a send rather than from the stream, so an answer
     * that arrives in eighty pieces is one line in the transcript rather than
     * eighty.
     */
    private fun recordAnswer(session: ChatSession, into: Long?, answer: String) {
        if (into == null) return
        if (session.agentId != null) return
        val named = session.modelId?.let { catalogue.model(it) }?.name ?: FALLBACK_ACTOR
        recorder.agentSaid(into, named, answer)
    }

    /** Spring AI's types, in the words a provider's API uses. */
    private fun role(message: Message): String = when (message.messageType) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.SYSTEM -> "system"
        MessageType.TOOL -> "tool"
    }

    private companion object {
        val log = LoggerFactory.getLogger(ChatService::class.java)

        /** Matches the column. */
        const val TITLE_LENGTH = 200

        /** What a bare model's answer is signed with once the model it named is gone. */
        const val FALLBACK_ACTOR = "model"

        /**
         * The role an agent's own lines read under, in both a thread and a
         * session. Named because [carried] steps over one and never over
         * anybody else's.
         */
        const val AGENT_ROLE = "assistant"

        /**
         * Who a drawn picture is signed by in a transcript.
         *
         * Not the chat's model: the chat's model did not draw it, and a
         * transcript that says it did is a transcript that would have somebody
         * looking for the picture in the wrong provider's bill.
         */
        const val PICTURE_ACTOR = "image model"

        /**
         * What a chat's own session is namespaced under.
         *
         * So the list of sessions says which of them are one chat's private
         * record and which are conversations an agent is having across runs.
         * The two are read very differently and there is nothing else on the
         * row to tell them apart.
         */
        const val CHAT_PREFIX = "chat"
    }

    /**
     * The system turn a chat with an agent opens with.
     *
     * A list rather than a nullable turn so both send paths can prepend it
     * without either having to ask whether there is one — for a chat with a bare
     * model it is simply empty.
     */
    private fun briefed(session: ChatSession): List<ChatTurn> {
        val agent = session.agentId?.let { agents.findByIdOrNull(it) } ?: return emptyList()
        val said = briefing.of(agent) ?: return emptyList()
        return listOf(ChatTurn("system", said))
    }
}

/**
 * One message out of the history.
 *
 * [actor] is who said it in the session this chat is continuing, and is null
 * for everything the chat said itself. Null is therefore also the boundary: the
 * turns that were already there when the chat opened, and the ones said since.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val actor: String? = null,
    /**
     * What this answer said the earlier times it was given, oldest first, for
     * an answer that has been asked for again. Empty for every other line.
     *
     * The one that stands is [content] and is not in here: this is what a
     * regenerate displaced, kept so that pressing the button is not a way of
     * losing the answer somebody was about to keep.
     */
    val takes: List<String> = emptyList(),
    /**
     * What the model thought on its way to this answer, or null where it
     * thought nothing anybody kept.
     *
     * Null rather than empty, and the difference is drawn: a model that emits
     * no reasoning gets no container at all, rather than an empty one asserting
     * there was thinking to see.
     *
     * Never part of [content]. That is the whole arrangement rather than a
     * detail - the copy control, the speech model and the next turn's prompt
     * all read the content, and they are correct because the string they read
     * does not hold this, not because three places remember to strip it.
     */
    val thinking: String? = null,
    /**
     * How long that thinking went on for, or null where nobody measured it.
     *
     * Null rather than nought, and never the turn's own time: the turn is
     * already reported on the answer's disclosure, and a screen showing two
     * numbers that look like the same measurement and are not is worse than one
     * that shows a number less.
     */
    val thinkingMillis: Long? = null,
    /**
     * When it was sent, as ISO-8601, or null where nobody wrote it down.
     *
     * The store has carried a timestamp per line since the table was made; this
     * is the first thing to read it back. Null covers lines carried in from the
     * LLM session a chat continues - they were said before this chat existed,
     * and inventing a time for them would be worse than none. Issue #323.
     */
    val at: String? = null,
) {
    constructor(message: Message) : this(message.messageType.name.lowercase(), message.text.orEmpty())
}

/**
 * What a send needs before the model is called.
 *
 * [agentId] is set when an agent is answering, because the streaming endpoint
 * has to know whether to run the tool loop and cannot ask the session again
 * without another transaction.
 */
data class ChatSendStart(
    val modelId: Long,
    val conversationId: String,
    val turns: List<ChatTurn>,
    val agentId: Long? = null,
    /**
     * The LLM session this round is recorded into, or null for a chat
     * continuing none. Carried here for the same reason [agentId] is: the
     * streaming endpoint runs outside the transaction that read the chat and
     * cannot ask it again.
     */
    val llmSessionId: Long? = null,
    /**
     * What compaction did to this conversation on the way in, or null.
     *
     * Carried out to the stream because the chat has to say it. Everything else
     * this feature does is invisible by design - messages are replaced by a
     * summary and the old ones are gone - and invisible is exactly how a person
     * comes to believe the product lost their conversation.
     */
    val compacted: Compacted? = null,
)
