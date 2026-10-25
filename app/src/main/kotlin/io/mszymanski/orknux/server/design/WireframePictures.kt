package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.context.annotation.Lazy
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller

/**
 * A picture brought onto a wireframe: the wireframe editor's Import, from a
 * file or from the pictures already in Orknux, as the image editor's is.
 *
 * Nothing is drawn here - the editor places the picture as an image element,
 * one step Undo goes back over, and the drawing is saved by Save as any change
 * is. What this is for is the two things a browser cannot do alone: keep a
 * file as one of the workspace's pictures, through
 * [DesignImageAPI.importDesignImage] as a pasted picture in a document is, and
 * say in the audit log that it was brought in. An SVG the editor exported is
 * drawn as a PNG in the browser before it arrives - the browser draws what the
 * editor drew, words and all, and an SVG is a document rather than a picture
 * to keep.
 *
 * An image element names its picture by artifact id in `props.artifact`, and
 * is drawn from [DocumentContent.source], the address a document's pictures
 * are drawn from, which serves only the workspace's own.
 */
@Controller
class WireframePictures(
    @param:Lazy private val wireframes: WireframeAPI,
    @param:Lazy private val pictures: DesignImageAPI,
    @param:Lazy private val artifacts: WorkspaceArtifactAPI,
    private val audit: WorkspaceAuditRecorder,
    mapper: tools.jackson.databind.ObjectMapper,
) {

    private val content = DocumentContent(mapper)

    /**
     * @param artifact a picture already in the workspace, by its artifact id; or
     * @param image a picture's file, as base64 or a data URL, kept as a new one.
     */
    @MutationMapping
    fun importWireframePicture(@Argument id: Long, @Argument artifact: String?, @Argument image: String?, @Argument name: String?): WireframePictureView {
        val held = wireframes.find(id)
        val workspaceId = held.workspaceId
        val chosen = when {
            !artifact.isNullOrBlank() -> {
                val asked = artifact.trim()
                val stored = artifacts.storedOf(workspaceId, asked)?.takeIf { it.contentType.startsWith("image/") }
                    ?: throw WireframePictureRefusedException("there is no picture $asked in this workspace")
                WireframePictureView(asked, stored.view.name ?: stored.view.prompt.ifBlank { asked }, content.source(workspaceId, asked))
            }
            !image.isNullOrBlank() -> {
                val kept = pictures.importDesignImage(ImportDesignImageInput(workspaceId = workspaceId.toString(), image = image, name = name?.trim()?.take(200)))
                WireframePictureView(kept.id, kept.name ?: kept.prompt, content.source(workspaceId, kept.id))
            }
            else -> throw WireframePictureRefusedException("there is no picture to bring in")
        }
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Picture ${chosen.name} imported into wireframe ${held.name}")
        return chosen
    }
}

/** A picture ready to be placed on a wireframe: its id, what it is called, and where it is drawn from. */
data class WireframePictureView(val artifact: String, val name: String, val src: String)

class WireframePictureRefusedException(val why: String) :
    RuntimeException("The picture cannot be put on the wireframe: $why"), Refusal {
    override val arguments get() = mapOf("why" to why)
}
