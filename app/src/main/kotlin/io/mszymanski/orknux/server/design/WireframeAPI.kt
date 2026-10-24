package io.mszymanski.orknux.server.design

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.context.annotation.Lazy
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * A workspace's wireframes, and the one door every change to them comes through.
 * Issue #655.
 *
 * Anybody who can see the workspace may draw in it, as anybody may draw a
 * picture in Design. The orknux_* wireframe tools save through this same class
 * ([io.mszymanski.orknux.server.mcp.WireframeTools]), so a drawing a model made
 * is checked by the same [WireframeDocument], held to the same Tunable and
 * written in the audit log in the same words, under the name of whoever asked.
 */
@Controller
class WireframeAPI(
    private val wireframes: WireframeRepository,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    mapper: ObjectMapper,
    /** Lazy: the chat reaches the orknux_* tools, which save through here. */
    @param:Lazy private val chat: WireframeChat,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val document = WireframeDocument(mapper)

    @QueryMapping
    @Transactional(readOnly = true)
    fun wireframes(@Argument workspaceId: Long): List<WireframeView> {
        access.requireVisible(workspaceId)
        return wireframes.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).map(::view)
    }

    /** One wireframe, or null where there is none this person may see. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun wireframe(@Argument id: Long): WireframeView? {
        val held = wireframes.findByIdOrNull(id) ?: return null
        access.requireVisible(held.workspaceId)
        return view(held)
    }

    @MutationMapping
    @Transactional
    fun createWireframe(@Argument workspaceId: Long, @Argument name: String, @Argument document: String?): WireframeView {
        access.requireVisible(workspaceId)
        val elements = checked(document)
        val who = who()
        val made = wireframes.save(
            Wireframe(
                workspaceId = workspaceId,
                name = cleanName(name),
                document = this.document.write(elements),
                createdBy = who,
                updatedBy = who,
            ),
        )
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Wireframe ${made.name} added")
        return view(made)
    }

    @MutationMapping
    @Transactional
    fun renameWireframe(@Argument id: Long, @Argument name: String): WireframeView {
        val held = find(id)
        val was = held.name
        val called = cleanName(name)
        if (called == was) return view(held)
        held.name = called
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Wireframe $was renamed to $called")
        return view(wireframes.save(held))
    }

    /**
     * Writes a whole drawing over the one held.
     *
     * @param revision the revision the drawing was made from. Where it is given
     *   and is not the one held, somebody - or a model - saved in between, and
     *   writing this over it would lose what they did, so it is refused.
     */
    @MutationMapping
    @Transactional
    fun saveWireframe(@Argument id: Long, @Argument document: String, @Argument revision: Int?): WireframeView {
        val held = find(id)
        if (revision != null && revision != held.revision) throw WireframeChangedException(held.name)
        return write(held, checked(document))
    }

    /** The same save, from the orknux_* tools, which hold elements rather than text. */
    @Transactional
    fun saveElements(id: Long, elements: List<WireframeElement>): WireframeView {
        val held = find(id)
        return write(held, held(elements))
    }

    @MutationMapping
    @Transactional
    fun deleteWireframe(@Argument id: Long): Boolean {
        val held = find(id)
        wireframes.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Wireframe ${held.name} removed")
        return true
    }

    /**
     * The wireframe as one HTML page; see [WireframeHtml].
     *
     * @param document the drawing as the editor holds it, so an export is what
     *   is on the screen rather than what was last saved; checked as a save is,
     *   and written nowhere. Without it, the stored drawing.
     */
    @MutationMapping
    fun exportWireframeHtml(@Argument id: Long, @Argument document: String?): String {
        val held = find(id)
        return html(held, if (document == null) this.document.read(held.document) else checked(document))
    }

    /** The stored drawing as a page, from the download link and the orknux_* tool. */
    fun html(id: Long): WireframeHtmlPage {
        val held = find(id)
        return WireframeHtmlPage(held.name, html(held, document.read(held.document)))
    }

    /** Written out, and said in the audit log: a page leaving the server is a copy somebody now holds. */
    private fun html(held: Wireframe, elements: List<WireframeElement>): String {
        val page = WireframeHtml().page(held.name, elements)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Wireframe ${held.name} exported as HTML")
        return page
    }

    /**
     * Asks a chat model to draw in a wireframe, and answers with what it said and
     * the drawing as it now stands.
     *
     * Not transactional: a round can take a minute, and every change the model
     * makes is its own save through [saveElements], seen by the editor the
     * moment the answer comes back.
     */
    @MutationMapping
    fun askWireframe(
        @Argument id: Long,
        @Argument modelId: Long,
        @Argument messages: List<WireframeChatTurnInput>,
    ): WireframeAnswerView {
        val held = find(id)
        val turns = messages
            .filter { it.content.isNotBlank() }
            .map { ChatTurn(role = if (it.role.equals("assistant", ignoreCase = true)) "assistant" else "user", content = it.content) }
        if (turns.none { it.role == "user" }) throw WireframeAnswerFailedException("there is nothing to answer")
        val answered = chat.answer(view(held), modelId, turns, who())
        val now = find(id)
        return WireframeAnswerView(answer = answered, changed = now.revision != held.revision, wireframe = view(now))
    }

    /** The wireframe by id, checked to be one this caller may see. */
    fun find(id: Long): Wireframe {
        val held = wireframes.findByIdOrNull(id) ?: throw WireframeNotFoundException(id)
        access.requireVisible(held.workspaceId)
        return held
    }

    /** Elements read from a document, held to the Tunable. */
    fun checked(document: String?): List<WireframeElement> = held(this.document.read(document))

    fun view(wireframe: Wireframe): WireframeView = WireframeView(
        id = requireNotNull(wireframe.id),
        workspaceId = wireframe.workspaceId,
        name = wireframe.name,
        document = wireframe.document,
        elementCount = runCatching { document.read(wireframe.document).size }.getOrDefault(0),
        revision = wireframe.revision,
        createdBy = wireframe.createdBy,
        createdAt = wireframe.createdAt.toString(),
        updatedBy = wireframe.updatedBy,
        updatedAt = wireframe.updatedAt.toString(),
    )

    private fun write(held: Wireframe, elements: List<WireframeElement>): WireframeView {
        held.document = document.write(elements)
        held.revision += 1
        touch(held)
        val saved = wireframes.save(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Wireframe ${held.name} saved with ${elements.size} elements")
        return view(saved)
    }

    private fun held(elements: List<WireframeElement>): List<WireframeElement> {
        val most = tuning[Tunable.WIREFRAME_ELEMENTS_MOST]
        if (elements.size > most) throw WireframeTooLargeException(elements.size, most)
        return elements
    }

    private fun touch(held: Wireframe) {
        held.updatedBy = who()
        held.updatedAt = OffsetDateTime.now()
    }

    private fun cleanName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= Wireframes.NAME_LENGTH } ?: throw WireframeNameInvalidException()

    /** Whoever is signed in; an agent drawing through the orknux_* tools with nobody signed in, by its name. */
    private fun who(): String {
        val signedIn = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        return signedIn?.name ?: ActingIn.actor() ?: "orknux"
    }
}

/** A wireframe as the list and the editor read it. Times are ISO-8601. */
data class WireframeView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    /** `{"elements": [...]}`. */
    val document: String,
    val elementCount: Int,
    val revision: Int,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
)

/** A wireframe's page, and the name it was made from, which a download is named by. */
data class WireframeHtmlPage(val name: String, val html: String)

data class WireframeAnswerView(
    val answer: String,
    /** Whether the model saved anything, so the editor knows to take the drawing up. */
    val changed: Boolean,
    val wireframe: WireframeView,
)

/** One turn of the editor's conversation, which the editor keeps and sends up whole each time. */
data class WireframeChatTurnInput @JsonCreator constructor(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String,
)
