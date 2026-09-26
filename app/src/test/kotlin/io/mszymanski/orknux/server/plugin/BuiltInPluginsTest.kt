package io.mszymanski.orknux.server.plugin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The plugins the release brings itself. Issue #474.
 *
 * What is pinned: the bundle in `resources/plugins` is written at boot and comes
 * out the other side as an ordinary plugin row - inspected, with its functions,
 * tools and skills declared - so nothing downstream learns that a second kind of
 * plugin exists; its own declarations are accepted, because the party who would
 * have been asked is the party that chose to run this release; it cannot be
 * removed; and writing it twice writes nothing the second time.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BuiltInPluginsTest(
    @Autowired val builtIn: BuiltInPlugins,
    @Autowired val plugins: PluginRepository,
    @Autowired val api: PluginAPI,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
) {

    @Test
    fun `the PDF plugin is written at boot, declared and accepted`() {
        // Written by the ready event, or by this call where an earlier test in
        // the same database took the row out again.
        builtIn.write()

        val pdf = requireNotNull(plugins.findByKey("pdf")) { "the release brings the PDF plugin" }
        assertThat(pdf.builtIn).isTrue()
        assertThat(pdf.enabled).isTrue()
        assertThat(pdf.name).isEqualTo("PDF")
        assertThat(pdf.version).isNotBlank()
        // What it offers, read back off the row the boot writer stored: the
        // inspection ran, so these are the plugin's own answers.
        assertThat(pdf.declaredTools).contains("fromHtml")
        assertThat(pdf.declaredSkills).contains("Making a PDF")
        assertThat(pdf.declaredFunctions).contains("read")
        /*
         * And what it asked for is accepted. Nobody was asked: an upload is
         * refused until somebody names the permissions, and a bundle inside the
         * release is the installation's own decision to run this version.
         */
        assertThat(pdf.declaredPermissions).isEqualTo(pdf.acceptedPermissions)
        assertThat(pdf.declaredCapabilities).isEqualTo(pdf.acceptedCapabilities)
        assertThat(pdf.declaredCapabilities).contains("RENDER_PDF")
        // A single file: nothing rides in beside it.
        assertThat(pdf.sizeBytes).isGreaterThan(0)
    }

    /** Written twice is written once: the fingerprint is what decides. */
    @Test
    fun `writing it again changes nothing`() {
        builtIn.write()
        val first = requireNotNull(plugins.findByKey("pdf"))
        val stamped = first.uploadedAt

        builtIn.write()
        val second = requireNotNull(plugins.findByKey("pdf"))
        assertThat(second.sha256).isEqualTo(first.sha256)
        assertThat(second.uploadedAt).describedAs("not rewritten").isEqualTo(stamped)
    }

    /**
     * Remove is refused, and the reason is legible.
     *
     * The next start would write it back, so a Remove that appeared to work
     * would be a lie the first restart tells. Switching it off is the decision
     * that holds.
     */
    @Test
    fun `a plugin the release brings cannot be removed`() {
        builtIn.write()
        val pdf = requireNotNull(plugins.findByKey("pdf"))

        assertThatThrownBy { api.unloadPlugin(requireNotNull(pdf.id)) }
            .isInstanceOf(PluginBuiltInException::class.java)
            .hasMessageContaining("ships with Orknux")

        assertThat(plugins.findByKey("pdf")).isNotNull()
    }

    /** And the screen can tell: the field is on the type the list reads. */
    @Test
    fun `the list says which plugins the release brought`() {
        builtIn.write()
        graphQlTester.document("query { plugins { key builtIn enabled } }")
            .execute()
            .path("plugins[?(@.key == 'pdf')].builtIn")
            .entityList(Boolean::class.java)
            .containsExactly(true)
    }
}
