package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table

@Entity
@Table(name = "workspace")
class Workspace(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    /**
     * The roles that open this workspace. Empty means administrators only.
     *
     * Roles rather than the name of a directory group: the group was the identity
     * provider's vocabulary in this application's model, it only ever made sense
     * for LDAP, and two workspaces meaning the same audience had no way to say so.
     *
     * Eagerly fetched because every access check needs them, and the set is small
     * by construction — a workspace has an audience, not a directory.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "workspace_role",
        joinColumns = [JoinColumn(name = "workspace_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf(),

    /**
     * The roles that also *administer* this workspace. Empty means installation
     * administrators only, which is what every workspace had before this existed.
     *
     * Meant to be a subset of [roles]: administering a workspace one cannot see is
     * nothing, so `WorkspaceAPI.updateWorkspace` refuses a set that is not, and
     * `WorkspaceAccess.canSee` counts these too in case a database was edited by
     * hand.
     *
     * Per workspace, which is the whole of the idea — one role can lead the support
     * workspace and merely work in the backend one. Eagerly fetched for the same
     * reason [roles] is: the access check needs them on every call, and the set is
     * smaller still.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "workspace_admin_role",
        joinColumns = [JoinColumn(name = "workspace_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var adminRoles: MutableSet<Role> = mutableSetOf(),

    /**
     * The model used for the small jobs nobody asks for — naming a chat from
     * what was said, first among them.
     *
     * Set for the workspace rather than per chat: it is not the conversation
     * anyone is having, and a cheap model is the right one for it even where
     * the chat uses an expensive one. Null means those jobs do not happen.
     */
    @Column(name = "companion_model_id")
    var companionModelId: Long? = null,

    /**
     * The model that turns speech into text, for the microphone in a chat.
     *
     * A workspace setting because it is about this installation's hardware —
     * where Whisper is running — rather than about any one conversation. Null
     * means the microphone is not offered: better than a button that fails.
     */
    @Column(name = "transcription_model_id")
    var transcriptionModelId: Long? = null,

    /**
     * The model that reads an answer aloud, for the speaker under one.
     *
     * The mirror of [transcriptionModelId], and a workspace setting for the same
     * reason. Null means no speaker is offered, which is better than one that
     * fails on every answer.
     */
    @Column(name = "speech_model_id")
    var speechModelId: Long? = null,

    /**
     * How much of a chat may pile up before the older half is summarised, in
     * tokens; null leaves it alone.
     *
     * A conversation that outgrows its model's window fails on the next turn,
     * and the failure names a limit rather than the thing to do about it. Above
     * this, everything but the last few turns is replaced by one summary of
     * itself and the chat carries on.
     *
     * Null rather than a number that means off, because "not set up" and "set to
     * never" are the same thing here and one spelling is enough. Off is what
     * every workspace has until somebody asks for it. Issue #286.
     */
    @Column(name = "compact_after_tokens")
    var compactAfterTokens: Int? = null,

    /**
     * How long the summary may be, in tokens.
     *
     * The whole point is a conversation that fits, so a summary with no bound is
     * a compaction that may not compact - a model asked to summarise forty turns
     * will happily write ten. Null is a sensible default rather than no limit.
     */
    @Column(name = "compaction_summary_tokens")
    var compactionSummaryTokens: Int? = null,

    /**
     * Which model writes the summary; null uses the one the chat is held with.
     *
     * Its own setting because summarising is not the conversation: it is a
     * cheaper job than answering, done once in a while, and a workspace talking
     * to an expensive model has every reason to summarise with a small one.
     */
    @Column(name = "compaction_model_id")
    var compactionModelId: Long? = null,

    /**
     * The model that draws a picture, for the picture button in a chat.
     *
     * The third of the three that sit on the Chat card, chosen the same way and
     * for the same reason: which image server this installation can reach is a
     * fact about the installation, not a choice to be made per message. Null
     * means the button is not offered, which is better than one that fails on
     * every press.
     */
    @Column(name = "image_model_id")
    var imageModelId: Long? = null,

    /**
     * The model behind the quick chat, the panel that opens beside the page.
     *
     * Kept apart from the companion model because the jobs are not the same one:
     * naming a chat is a single cheap completion, while this answers questions
     * about the installation and calls orknux's own tools to do it. Null means
     * the button is not offered.
     */
    @Column(name = "quick_chat_model_id")
    var quickChatModelId: Long? = null,

    /**
     * Whether the quick chat may start things, or only look them up.
     *
     * Off by default, which is not the same as off by nature: the panel opens
     * over whatever somebody is reading, and a model that decides "run it" from
     * a question is a worse mistake there than on a page with a button on it.
     * A workspace that wants it can say so.
     */
    @Column(name = "quick_chat_may_write", nullable = false)
    var quickChatMayWrite: Boolean = false,

    /**
     * Whether this workspace's chats show when each message was sent.
     *
     * The time has been stored all along; this decides whether it is drawn.
     * Off by default because a visual change to every chat is not something an
     * upgrade should decide. Issue #323.
     */
    @Column(name = "chat_show_timestamps", nullable = false)
    var chatShowTimestamps: Boolean = false,

    /**
     * What an agent that sets no share of its own is given, as a percentage of
     * its model's context window.
     *
     * The middle step of three: an agent's own share, then this, then the
     * built-in allowance. Null here is what every workspace has until somebody
     * decides otherwise, and a workspace that leaves it null behaves exactly as
     * it did before this column existed.
     *
     * It exists because the per-agent setting is the right place to make an
     * exception and the wrong place to state a policy. An installation that has
     * decided its agents should remember twice as much as the built-in
     * allowance had to say so once per agent and again on every agent created
     * afterwards; this is that decision written down once, in the place the
     * agents already belong to.
     *
     * A percentage rather than a count of tokens for the same reason the
     * agent's is - see `SessionMemoryBudget` - and doubly so here, because a
     * workspace runs several models whose windows differ by an order of
     * magnitude and a share is the only unit that travels between them.
     *
     * Which is also why nothing but the bounds is checked when this is saved.
     * The narrower refusals - a window too small to carry an exchange, a model
     * that reserves most of its window for its answer - need one model, and
     * this default is not tied to one. They still apply, at the place the
     * budget is actually worked out, against the model the agent in question
     * really uses.
     */
    @Column(name = "default_memory_share")
    var defaultMemoryShare: Int? = null,

    /**
     * How many times a task may ask its model before it is stopped.
     *
     * One turn is one round of the agent's own tool loop - it is asked, it may
     * call its tools, and it answers - so this is the outer of two counts and
     * not the only ceiling: a task is also stopped after its working time is
     * spent, which is what bounds a turn sitting on a slow tool.
     *
     * Here rather than only in the configuration file because it is the number
     * somebody wants to change while watching a task run out of turns, and the
     * only way to change it was an environment variable and a restart of the
     * whole server (issue #229). A workspace and not the installation: what a
     * task is worth is a judgement about the work that workspace does, and one
     * doing overnight research has no bearing on one answering questions.
     *
     * Null is what every workspace starts as and means it has decided nothing,
     * so the installation's own number is used. Read when a task is created and
     * copied onto the row, so raising it does not extend a task already going
     * and lowering it does not kill one.
     */
    @Column(name = "task_max_turns")
    var taskMaxTurns: Int? = null,

    /**
     * How many other agents one agent here may ask in one conversation.
     *
     * Each ask is a conversation of its own, started on the asking model's
     * say-so, so this bounds what one question can fan out into. A workspace
     * and not only the installation for the same reason as [taskMaxTurns]:
     * what a question is worth is a judgement about the work this workspace
     * does. Null means it has decided nothing and Admin -> Settings applies.
     * Zero takes the tool off the table here. Issue #380.
     */
    @Column(name = "agent_max_subagents")
    var agentMaxSubagents: Int? = null,

    /**
     * How many identical tool calls in a row end the turn. Issue #516.
     *
     * A loop is not a long turn, and the rounds ceiling cannot tell them apart:
     * it bounds total work, so three hundred rounds of progress and one round
     * repeated three hundred times cost the same and are stopped at the same
     * place - after the whole budget is gone.
     *
     * This is the other question. The same tool with the same arguments
     * answering the same thing is a cycle with nothing in it that can change,
     * and the only way out is from outside. Null takes the installation's
     * number.
     */
    @Column(name = "max_repeated_tool_calls")
    var maxRepeatedToolCalls: Int? = null,

    /**
     * How many tool calls one message here may ask for at once. Issue #518.
     *
     * A different failure from the repetition above. That one is a turn going
     * round across rounds; this is a single decode coming off the rails - one
     * assistant message carrying the same call a hundred and forty-one times.
     * The guard above cannot see it, because it counts between rounds and this
     * all arrives inside one. Null takes the installation's number.
     */
    @Column(name = "max_tool_calls_at_once")
    var maxToolCallsAtOnce: Int? = null,

    /**
     * Compacting a turn that has already outgrown its model. Issue #522.
     *
     * Its own numbers rather than the chat compaction's above, because it is a
     * different judgement. That one decides when a stored conversation has
     * grown long enough to be worth shortening. These decide what to salvage
     * from a turn that has already failed, where the choice is not between a
     * summary and the full text but between a summary and nothing at all.
     *
     * How many of the turn's most recent steps survive word for word.
     */
    /**
     * How long a session's log may grow before it is compacted. Issue #523.
     *
     * The chat threshold above is about a conversation a person is having; this
     * is about one an agent is having, which runs far longer. Null takes the
     * installation's, and zero anywhere turns it off.
     */
    @Column(name = "session_compact_after_tokens")
    var sessionCompactAfterTokens: Int? = null,

    @Column(name = "session_compaction_keep_turns")
    var sessionCompactionKeepTurns: Int? = null,

    /** How long the summary that replaces the rest may be. */
    @Column(name = "session_compaction_summary_tokens")
    var sessionCompactionSummaryTokens: Int? = null,

    /** How many times one turn may be compacted before it gives up. */
    @Column(name = "session_compaction_attempts")
    var sessionCompactionAttempts: Int? = null,

    /** Which model writes that summary; null uses the turn's own. */
    @Column(name = "session_compaction_model_id")
    var sessionCompactionModelId: Long? = null,

    /**
     * And how close together they have to be to count. Issue #516.
     *
     * Repetition on its own is not the fault: an agent watching something
     * checks it, waits, and checks it again, which is the same call with the
     * same answer and entirely correct. The gap is what separates that from a
     * cycle. Null takes the installation's number.
     */
    @Column(name = "repeated_tool_calls_window_seconds")
    var repeatedToolCallsWindowSeconds: Int? = null,

    /**
     * How many times a looping turn is told before it is ended. Issue #516.
     *
     * Policy rather than mechanism: once is a warning worth giving, and how
     * much patience to have with a model that ignores it is a judgement about
     * how much a wasted turn costs here. Null takes the installation's number.
     */
    @Column(name = "repeated_tool_call_warnings")
    var repeatedToolCallWarnings: Int? = null,

    /**
     * Whether this workspace's agents may have built-in tools hidden from them.
     * Issue #482.
     *
     * Off, and off is the answer almost everybody should keep. The tools the
     * server brings are what the product is built on - a scratchpad to work in,
     * a way to finish a turn, a clock, a note, a way to ask another agent - and
     * an agent missing one of them behaves in ways nothing here can stand
     * behind: it retypes a file it could have kept, it answers in prose because
     * it cannot say it has finished, it invents today's date.
     *
     * So hiding one is refused unless a workspace has said, in as many words,
     * that it will take that risk. What is switched here is the *ability to
     * switch*, which is why it reads as unsafe on the screen: the setting does
     * nothing on its own and only ever opens a door.
     */
    @Column(name = "unsafe_built_in_tools", nullable = false)
    var unsafeBuiltInTools: Boolean = false,

    /**
     * What marks a command in a message that starts a run here: `!review`.
     *
     * Orknux's own, because Slack polices `/`: a slash command has to be
     * registered in the Slack app and one that is not is refused before it is
     * sent. One to three characters, none a letter or a digit. Issue #381.
     */
    @Column(name = "command_marker", length = 3)
    var commandMarker: String? = null,

    /**
     * How long one run of this workspace's functions may hold its thread, in
     * seconds, where the function has no timeout of its own.
     *
     * A function runs in four places and only one of them has an agent in it:
     * a workflow's step, a condition being decided, a webhook answering, and a
     * tool an agent called. This bounds the first three — see
     * [toolTimeoutSeconds] for the fourth — because they are different kinds
     * of wait. A webhook answers into a request somebody's server is holding
     * open; a workflow step has all night.
     *
     * Null is what every workspace starts as and means it has decided nothing,
     * so the installation's bound is used — the same shape [taskMaxTurns] has,
     * and for the same reason. Read per call, so changing it changes the next
     * run rather than one in flight.
     *
     * The column keeps its old name. It held both of these until they were
     * split, and renaming a column to match a Kotlin property is a migration
     * that can only go wrong for the sake of a word nobody outside this file
     * reads.
     */
    @Column(name = "script_timeout_seconds")
    var functionTimeoutSeconds: Int? = null,

    /**
     * And how long a tool an agent called may hold its thread.
     *
     * Its own setting because the wait belongs to somebody: a model is
     * stopped mid-turn until the tool answers, and on a chat a person is
     * watching it happen. Twenty seconds is patience in a workflow and a
     * failure in a conversation, which is why one number for both was a number
     * that suited neither.
     *
     * Null means the installation's bound, as above. Existing workspaces were
     * given whatever their single setting was, so nothing changed the day this
     * was split.
     */
    @Column(name = "tool_timeout_seconds")
    var toolTimeoutSeconds: Int? = null,

    /**
     * How long a pause has to run, after somebody has been talking, before
     * voice mode ends their turn and sends what they said.
     *
     * The setting that actually ends a turn, and the one to move when somebody
     * is cut off. It is a judgement about how people talk rather than a fact
     * about audio: people stop mid-sentence to think, and a pause shorter than
     * an ordinary one of those reads every stop as "your go".
     *
     * Null is what every workspace starts as and means the workspace has
     * decided nothing, so voice mode uses its own pause. The number is
     * deliberately not written down here as well - it belongs to the interface,
     * which is the only place that can judge it, and a copy on this side would
     * be a second source of truth that drifts the first time one of them
     * changes.
     */
    @Column(name = "voice_pause_ends_turn_ms")
    var voicePauseEndsTurnMs: Int? = null,

    /**
     * How far above the room's own noise a sound has to stand to count as a
     * voice, as a percentage - 300 is three times the room.
     *
     * A ratio rather than a loudness, because speech is several times the level
     * of the room it is spoken in whatever that room is, so this travels
     * between microphones in a way a fixed level does not. Lower is more
     * sensitive, and it is what to lower for somebody who talks quietly or sits
     * away from the microphone.
     *
     * There is a fixed level in the interface as well, OR'd with this one, and
     * it is not exposed here on purpose. The two ask the same question twice;
     * the fixed one exists only so that a silent room is not absurdly
     * sensitive - where the room is next to nothing, three times nothing is
     * still nothing and every breath clears the ratio. It is a guard against
     * this setting's failure mode rather than a second knob to turn, and
     * offering it as one would invite somebody to defeat the guard.
     *
     * Null means the workspace has decided nothing.
     */
    @Column(name = "voice_speech_over_room_percent")
    var voiceSpeechOverRoomPercent: Int? = null,

    /**
     * How long an open microphone stays open when nothing else has ended the
     * turn.
     *
     * A fuse, not a limit on how much anybody may say. The pause above is what
     * ends a turn; this only fires when no pause ever came, which means a
     * microphone left open in an empty room or a room noisy enough to read as
     * somebody talking. Every value this has held that looked like a reasonable
     * limit on a turn turned out to cut somebody off in the middle of a
     * sentence, which is why the bound on it is where it is.
     *
     * Null means the workspace has decided nothing.
     */
    @Column(name = "voice_unattended_microphone_ms")
    var voiceUnattendedMicrophoneMs: Int? = null,

    /**
     * How long somebody has to keep talking over the answer before it stops.
     *
     * Voice mode holds the microphone open while the answer is read aloud, so
     * anything said over it was already heard and queued as the next turn - but
     * the answer went on to the end regardless, which is the one thing a person
     * cannot do in a conversation: say "no, not that" and be listened to. They
     * had to reach for the panel and press, in the one mode whose point is not
     * touching anything.
     *
     * A hold rather than a level, because what has to be kept out is not a quiet
     * voice but a short noise - a cough, a door, this application's own voice
     * getting past the echo cancellation. Somebody interrupting keeps talking;
     * none of those do.
     *
     * Null means the workspace has decided nothing. Zero turns it off, which is
     * what an installation wants where the room is loud enough or the echo
     * cancellation poor enough that the answer keeps stopping on nothing.
     */
    @Column(name = "voice_barge_in_ms")
    var voiceBargeInMs: Int? = null,

    /**
     * Where an answer is cut before it is handed to the speech model.
     *
     * A value rather than a null, unlike the three above. Those store a
     * departure from a number the interface owns, and null is how a workspace
     * says it has decided nothing; this stores one of three named things a
     * listener can ask for, and [SpeechChunking.SENTENCE] is one of them by
     * name. "The default" as a fourth choice would be a second spelling of a
     * choice already on the list, and a form offering both would have to say
     * which of the two it saved.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "voice_speech_chunking", nullable = false, length = 16)
    var voiceSpeechChunking: SpeechChunking = SpeechChunking.SENTENCE,
)

/**
 * Where an answer being read aloud is cut for the speech provider.
 *
 * Reading is pipelined: a piece is asked for, played, and the next is made
 * while it is in the air, which is what stops the wait before the first word
 * being the wait for the last one to be synthesised. Where the cuts fall is the
 * trade this names, and it has no right answer - it is a listening preference,
 * which is why a workspace states it.
 *
 * The 220-character ceiling that holds a sentence-cut piece to about a breath
 * lives in the interface and is deliberately not offered here. A mode and a
 * size is two knobs describing one thing, and the second is only ever wrong in
 * ways the first already covers.
 */
enum class SpeechChunking {
    /**
     * No cutting at all: one request for the finished answer.
     *
     * Nothing is asked for until the answer is complete, so the silence before
     * the first word is however long the whole thing takes to synthesise - and
     * the longer the answer, the longer the wait. What it buys is one seam-free
     * clip from one request, which is what somebody reading a short answer on a
     * metered provider wants.
     */
    NONE,

    /**
     * Whole sentences, gathered up to about a breath each.
     *
     * The default, and what a hands-free conversation needs: the first sentence
     * is in the air while the second is being made, so somebody hears an answer
     * begin at roughly the speed a person would begin one.
     */
    SENTENCE,

    /**
     * Paragraph boundaries, and nothing shorter.
     *
     * Fewer and longer requests than [SENTENCE], so fewer joins between clips
     * to hear and less pressure on the provider, at the cost of a later first
     * word. The middle of the three, and the one to move to when the seams
     * between sentences are what is noticeable.
     */
    PARAGRAPH,
}
