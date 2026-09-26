package io.mszymanski.orknux.server.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A key, or the document where a key was meant. Issue #466.
 *
 * Every tool taking a contentKey takes it so the bytes do not travel through the
 * model, and a model with the document in front of it will sometimes send the
 * document. What it used to get back was a lookup that failed, saying nothing
 * was kept under eight thousand characters of HTML - which reads as the store
 * being broken rather than as the argument being wrong.
 *
 * The rule has to be loose in one direction and strict in the other: a real key
 * must never be refused, and only what is plainly a document is.
 */
@SpringBootTest
class RetypedKeyTest(@Autowired val caller: PluginToolCaller) {

    @Test
    fun `a key passes, whatever it is named`() {
        assertThat(caller.retypedKeyIn("""{"contentKey":"pdf.1lt2fm6"}""")).isNull()
        assertThat(caller.retypedKeyIn("""{"contentKey":"Global_Price_Report_Package.zip"}""")).isNull()
        // The name is matched at its end, so a plugin's own spelling is covered.
        assertThat(caller.retypedKeyIn("""{"fileContentKey":"report.pdf"}""")).isNull()
        // And everything else is left alone: only a key-shaped name is judged.
        assertThat(caller.retypedKeyIn("""{"html":"<p>a very long page</p>","title":"Report"}""")).isNull()
        assertThat(caller.retypedKeyIn("not json")).isNull()
    }

    @Test
    fun `a document sent as a key is refused, and told what to do instead`() {
        val page = "<html>" + "x".repeat(400) + "</html>"
        val said = caller.retypedKeyIn("""{"contentKey":"$page"}""")
        assertThat(said).isNotNull()
        assertThat(said).contains("takes the key")
        // The length is in it, because that is what tells somebody what happened.
        assertThat(said).contains("${page.length} characters")

        /*
         * A line break is the other tell: short, but nothing hands out a key
         * with a newline in it. Built as JSON escapes it, which is how it
         * arrives from a provider - a raw break would not be valid JSON and the
         * guard would never see the string at all.
         */
        val escape = "\\" + "n"
        assertThat(caller.retypedKeyIn("""{"contentKey":"first line${escape}second line"}""")).isNotNull()
    }
}
