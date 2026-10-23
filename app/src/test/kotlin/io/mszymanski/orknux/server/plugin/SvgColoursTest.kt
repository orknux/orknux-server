package io.mszymanski.orknux.server.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** CSS's newer colour words, rewritten as the hex and opacity Batik draws rather than as black. */
class SvgColoursTest {

    @Test
    fun `each newer word means the hex it says`() {
        assertThat(SvgColours.parsed("hsl(0, 100%, 50%)")).isEqualTo("#ff0000" to 1.0)
        assertThat(SvgColours.parsed("hsl(120deg 100% 25%)")).isEqualTo("#008000" to 1.0)
        assertThat(SvgColours.parsed("rgba(0, 0, 255, 0.5)")).isEqualTo("#0000ff" to 0.5)
        assertThat(SvgColours.parsed("rgb(0 128 255 / 25%)")).isEqualTo("#0080ff" to 0.25)
        assertThat(SvgColours.parsed("#f008")?.first).isEqualTo("#ff0000")
        assertThat(SvgColours.parsed("transparent")).isEqualTo("#000000" to 0.0)
        // What SVG 1.1 already says is left as it was written.
        assertThat(SvgColours.parsed("rgb(1, 2, 3)")).isNull()
        assertThat(SvgColours.parsed("teal")).isNull()
    }

    @Test
    fun `an alpha becomes the opacity beside it, multiplied into one already there`() {
        assertThat(SvgColours.normalized("""<svg><rect fill="rgba(255,0,0,0.5)"/></svg>"""))
            .isEqualTo("""<svg><rect fill="#ff0000" fill-opacity="0.5"/></svg>""")
        assertThat(SvgColours.normalized("""<svg><rect fill-opacity="0.5" stroke='hsla(0,100%,50%,0.5)' fill="#00ff0080"></rect></svg>"""))
            .isEqualTo("""<svg><rect fill-opacity="0.251" stroke='#ff0000' fill="#00ff00" stroke-opacity="0.5"></rect></svg>""")
    }

    @Test
    fun `style attributes and style blocks are rewritten too`() {
        assertThat(SvgColours.normalized("""<svg><style>.a { fill: hsl(0,100%,50%); stroke: rgba(0,0,0,.2) }</style><rect style="fill: transparent"/></svg>"""))
            .isEqualTo("""<svg><style>.a { fill: #ff0000; stroke: #000000; stroke-opacity: 0.2}</style><rect style="fill: #000000; fill-opacity: 0"/></svg>""")
    }
}
