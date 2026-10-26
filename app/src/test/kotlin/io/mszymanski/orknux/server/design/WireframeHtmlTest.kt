package io.mszymanski.orknux.server.design

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * A wireframe written out as HTML: each frame a screen with its elements placed
 * inside it, every kind its own element, and nothing the drawing says reaching
 * the page as markup - the drawing is untrusted.
 */
class WireframeHtmlTest {

    private val document = WireframeDocument(JsonMapper.builder().build())

    private fun page(name: String, json: String) = WireframeHtml().page(name, document.read(json))

    @Test
    fun `each frame is a screen, and what is in it is placed relative to it`() {
        val html = page(
            "Shop",
            """{"elements":[
              {"id":"checkout","type":"frame","x":500,"y":0,"w":390,"h":844,"text":"Checkout"},
              {"id":"home","type":"frame","x":0,"y":0,"w":390,"h":844,"text":"Home"},
              {"id":"panel","type":"frame","x":20,"y":100,"w":200,"h":200,"text":"Panel"},
              {"id":"pay","type":"button","x":524,"y":760,"w":342,"h":48,"text":"Pay","props":{"variant":"primary"}},
              {"id":"card","type":"input","x":524,"y":120,"w":342,"h":40,"text":"Card number"},
              {"id":"hero","type":"image","x":24,"y":320,"w":342,"h":200,"text":"Hero"},
              {"id":"title","type":"text","x":24,"y":24,"w":300,"h":40,"text":"Welcome","props":{"size":28,"weight":"bold"}},
              {"id":"box","type":"rect","x":24,"y":540,"w":100,"h":50,"text":"Card"},
              {"id":"why","type":"note","x":1000,"y":40,"w":160,"h":80,"text":"Ask design"},
              {"id":"go","type":"arrow","x":1000,"y":200,"w":-80,"h":40},
              {"id":"rule","type":"line","x":24,"y":600,"w":342,"h":0}
            ]}""",
        )

        assertThat(html).startsWith("<!doctype html>").contains("<title>Shop</title>")
        // Two screens from the two outer frames, in reading order, and one for what lies in neither.
        assertThat(Regex("<section class=\"screen\"").findAll(html).count()).isEqualTo(3)
        assertThat(html.indexOf("<h2 class=\"screen-title\">Home</h2>")).isLessThan(html.indexOf("<h2 class=\"screen-title\">Checkout</h2>"))
        assertThat(html).contains("style=\"width:390px;height:844px\"")
        // The nested frame is a panel of Home, not a screen.
        assertThat(html).doesNotContain("<h2 class=\"screen-title\">Panel</h2>")
            .contains("<div class=\"el frame\" data-id=\"panel\" style=\"left:20px;top:100px;width:200px;height:200px\"><span class=\"frame-title\">Panel</span></div>")
        // Real elements, at their place within their screen.
        assertThat(html).contains("<button type=\"button\" class=\"el button primary\" data-id=\"pay\" style=\"left:24px;top:760px;width:342px;height:48px\">Pay</button>")
        assertThat(html).contains("<input type=\"text\" class=\"el input\" data-id=\"card\" style=\"left:24px;top:120px;width:342px;height:40px\" placeholder=\"Card number\" aria-label=\"Card number\">")
        assertThat(html).contains("<figure class=\"el image\" data-id=\"hero\"").contains("<figcaption>Hero</figcaption>")
        assertThat(html).contains("<p class=\"el text\" data-id=\"title\" style=\"left:24px;top:24px;width:300px;height:40px;font-size:28px;font-weight:600\">Welcome</p>")
        assertThat(html).contains("<div class=\"el rect\" data-id=\"box\"")
        assertThat(html).contains("<aside class=\"el note\" data-id=\"why\"")
        // A line runs within its own svg; an arrow drawn leftwards carries its head.
        assertThat(html).contains("<svg class=\"el line\" data-id=\"rule\"")
        assertThat(html).contains("<svg class=\"el arrow\" data-id=\"go\"").contains("marker-end=\"url(#wf-arrowhead)\"")
        // No script, and a policy saying there will be none.
        assertThat(html).doesNotContain("<script").contains("default-src 'none'")
    }

    /**
     * A screen scrolls sideways and never down: a framed screen's contents sit
     * inside its border, so a rectangle filling the frame stood 1px below it and
     * the unreleased report drew a second scrollbar inside the page's.
     */
    @Test
    fun `a screen scrolls sideways when it is wide, and never down`() {
        val html = page("Report", """{"elements":[{"id":"f","type":"frame","x":0,"y":0,"w":400,"h":300,"text":"F"},{"id":"bg","type":"rect","x":0,"y":0,"w":400,"h":300}]}""")

        val rule = Regex("""\.screen \{[^}]*}""").find(html)?.value
        assertThat(rule).contains("overflow-x: auto").contains("overflow-y: hidden")
    }

    @Test
    fun `screens are split by where each element's middle lies`() {
        val screens = WireframeHtml().screens(
            document.read(
                """{"elements":[
                  {"id":"a","type":"frame","x":0,"y":0,"w":100,"h":100},
                  {"id":"b","type":"frame","x":0,"y":0,"w":100,"h":100},
                  {"id":"straddle","type":"button","x":80,"y":10,"w":30,"h":20},
                  {"id":"out","type":"rect","x":95,"y":10,"w":40,"h":20}
                ]}""",
            ),
        )
        // Two identical frames: the first is the screen and the second a panel of it.
        assertThat(screens).hasSize(2)
        assertThat(screens[0].framed).isTrue()
        assertThat(screens[0].elements.map { it.id }).containsExactly("b", "straddle")
        assertThat(screens[1].framed).isFalse()
        assertThat(screens[1].elements.map { it.id }).containsExactly("out")
        assertThat(listOf(screens[1].x, screens[1].y, screens[1].w, screens[1].h)).containsExactly(95, 10, 40, 20)
    }

    @Test
    fun `nothing a drawing says reaches the page as markup`() {
        val hostile = "</title><script>alert(1)</script><img src=x onerror=alert(2)>\"' & done"
        val html = page(
            hostile,
            """{"elements":[
              {"id":"f\"><script>x</script>","type":"frame","x":0,"y":0,"w":400,"h":400,"text":${quoted(hostile)}},
              {"type":"button","x":10,"y":10,"w":100,"h":40,"text":${quoted(hostile)},"props":{"variant":"primary\" onclick=\"alert(3)"}},
              {"type":"input","x":10,"y":60,"w":100,"h":40,"text":"\" autofocus onfocus=\"alert(4)"},
              {"type":"text","x":10,"y":110,"w":100,"h":40,"text":"x","props":{"size":"12px;background:url(javascript:alert(5))","weight":"bold;color:red"}},
              {"type":"note","x":10,"y":160,"w":100,"h":40,"text":"<b>bold</b>"}
            ]}""",
        )
        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("<b>")
            .doesNotContain("onclick=\"").doesNotContain("onfocus=\"").doesNotContain("onerror=alert(2)>").doesNotContain("javascript:alert(5)\"")
            .doesNotContain("background:url").doesNotContain("color:red")
        assertThat(html).contains("<title>&lt;/title&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;img src=x onerror=alert(2)&gt;&quot;&#39; &amp; done</title>")
        assertThat(html).contains("data-id=\"f&quot;&gt;&lt;script&gt;x&lt;/script&gt;\"")
        assertThat(html).contains("placeholder=\"&quot; autofocus onfocus=&quot;alert(4)\"")
        // A prop that is not one of the values looked for is no prop at all: the default size, the normal weight, no variant.
        assertThat(html).contains("font-size:14px;font-weight:400").contains("class=\"el button\"")
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;")
    }

    @Test
    fun `a checkbox, a radio button and a switch are real inputs in a label, ticked where they are checked`() {
        val html = page(
            "Settings",
            """{"elements":[
              {"id":"terms","type":"checkbox","x":0,"y":0,"w":200,"h":24,"text":"I agree <now>","props":{"checked":true}},
              {"id":"news","type":"checkbox","x":0,"y":40,"w":200,"h":24,"text":"Newsletter"},
              {"id":"card","type":"radio","x":0,"y":80,"w":200,"h":24,"text":"Card","props":{"checked":"true"}},
              {"id":"dark","type":"toggle","x":0,"y":120,"w":200,"h":24,"text":"Dark mode","props":{"checked":false}}
            ]}""",
        )
        assertThat(html).contains("<label class=\"el check checkbox\" data-id=\"terms\" style=\"left:0px;top:0px;width:200px;height:24px\"><input type=\"checkbox\" checked><span>I agree &lt;now&gt;</span></label>")
        assertThat(html).contains("<label class=\"el check checkbox\" data-id=\"news\"").contains("<input type=\"checkbox\"><span>Newsletter</span>")
        assertThat(html).contains("<input type=\"radio\" checked><span>Card</span>")
        assertThat(html).contains("<input type=\"checkbox\" role=\"switch\" class=\"switch\"><span>Dark mode</span>")
    }

    @Test
    fun `a style reaches the page as CSS, in the one spelling it was checked into`() {
        val html = page(
            "Styled",
            """{"elements":[
              {"id":"hero","type":"rect","x":0,"y":0,"w":300,"h":100,"props":{
                "fill":"linear-gradient(90DEG, #F0A, rgb(0, 0, 255), white 100%)","color":"rgba(255, 0, 0, 0.5)",
                "borderWidth":2,"borderStyle":"Dashed","borderColor":"#123","radius":12,"shadow":"medium"}},
              {"id":"plain","type":"text","x":0,"y":120,"w":100,"h":20,"text":"x","props":{"fill":"navy","borderColor":"#00ff00"}},
              {"id":"go","type":"arrow","x":0,"y":200,"w":100,"h":0,"props":{"borderColor":"#ff0000","borderWidth":3,"borderStyle":"dotted"}}
            ]}""",
        )
        assertThat(html).contains(
            "data-id=\"hero\" style=\"left:0px;top:0px;width:300px;height:100px;background:linear-gradient(90deg, #ff00aa 0%, #0000ff 50%, #ffffff 100%)" +
                ";color:#ff000080;border:2px dashed #112233;border-radius:12px;box-shadow:0 4px 10px rgba(0,0,0,0.22)\"",
        )
        // A colour given to a kind with no outline draws a solid one.
        assertThat(html).contains("data-id=\"plain\" style=\"left:0px;top:120px;width:100px;height:20px;background:#000080;border:1px solid #00ff00;")
        assertThat(html).contains("<marker id=\"wf-arrowhead-go\"").contains("fill=\"#ff0000\"")
            .contains("style=\"stroke:#ff0000;stroke-width:3;stroke-dasharray:3 3;\"")
    }

    /** A screen is drawn in its frame's style: a frame's fill was dropped, and a dark frame exported white. */
    @Test
    fun `a frame's fill, border and radius are its screen's`() {
        val html = page(
            "Dark",
            """{"elements":[
              {"id":"page","type":"frame","x":0,"y":0,"w":400,"h":300,"text":"Report","props":{"fill":"#0f0f12","borderColor":"#2e2e33","borderWidth":1,"radius":12}},
              {"id":"t","type":"text","x":20,"y":20,"w":200,"h":20,"text":"Inside"}
            ]}""",
        )
        assertThat(html).contains(
            "class=\"canvas framed\" style=\"width:400px;height:300px;background:#0f0f12;border:1px solid #2e2e33;border-radius:12px\"",
        )
        // And the page is that page: drawn in its colour, dark, with no export chrome over one screen.
        assertThat(html).contains("<style>body { background: #0f0f12; color-scheme: dark; }</style>")
            .doesNotContain("class=\"wireframe-name\"").doesNotContain("class=\"screen-title\"")
            .contains("<title>Dark</title>")
        assertThat(page("Light", """{"elements":[{"id":"p","type":"frame","x":0,"y":0,"w":100,"h":100,"props":{"fill":"#ffffff"}}]}"""))
            .contains("<style>body { background: #ffffff; color-scheme: light; }</style>")
    }

    @Test
    fun `a colour or a gradient that is not one is refused, and never reaches the page as it was written`() {
        val hostile = listOf(
            "red;background:url(javascript:alert(1))",
            "expression(alert(1))",
            "#fff\" onmouseover=\"alert(2)",
            "url(#x)",
            "</style><script>alert(3)</script>",
            "linear-gradient(90deg, red 0%, url(javascript:alert(4)) 100%)",
            "linear-gradient(90deg, red 0%, blue 100%); color: red",
            "linear-gradient(90deg, red)",
            "rgb(300, 0, 0)",
            "#ggg",
            "var(--x)",
        )
        hostile.forEach { said ->
            val json = """{"elements":[{"id":"a","type":"rect","x":0,"y":0,"w":10,"h":10,"props":{"fill":${quoted(said)},"color":${quoted(said)}}}]}"""
            assertThatThrownBy { document.read(json) }.describedAs(said).isInstanceOf(WireframeDocumentInvalidException::class.java)
                .hasMessageContaining("fill")
            // A drawing stored before styles were checked still opens, without the value.
            val stored = document.stored(json)
            assertThat(stored.single().props).describedAs(said).doesNotContainKeys("fill", "color")
            val html = WireframeHtml().page("x", stored)
            assertThat(html).describedAs(said).doesNotContain("javascript").doesNotContain("<script").doesNotContain("onmouseover")
                .doesNotContain("expression(").doesNotContain("url(#x)").doesNotContain("var(")
        }
        listOf("borderStyle" to "groove", "shadow" to "0 0 4px red", "borderWidth" to "99", "radius" to "-1", "checked" to "maybe").forEach { (key, value) ->
            val json = """{"elements":[{"id":"a","type":"checkbox","x":0,"y":0,"w":10,"h":10,"props":{"$key":${quoted(value)}}}]}"""
            assertThatThrownBy { document.read(json) }.describedAs(key).isInstanceOf(WireframeDocumentInvalidException::class.java).hasMessageContaining(key)
        }
    }

    @Test
    fun `a drawn style is the picture's too, with gradients and shadows the renderer reads`() {
        val svg = requireNotNull(
            WireframeSvg().drawing(
                document.read(
                    """{"elements":[
                      {"id":"a","type":"button","x":0,"y":0,"w":100,"h":40,"text":"Go","props":{"fill":"linear-gradient(0deg, #ff0000, #0000ff80)","color":"#00ff00","shadow":"large","radius":20}},
                      {"id":"b","type":"checkbox","x":0,"y":60,"w":100,"h":24,"text":"Yes","props":{"checked":true}},
                      {"id":"c","type":"line","x":0,"y":100,"w":100,"h":0,"props":{"borderStyle":"none"}}
                    ]}""",
                ),
            ),
        )
        assertThat(svg).contains("<linearGradient id=\"wf-fill-1\" gradientUnits=\"userSpaceOnUse\" x1=\"50\" y1=\"40\" x2=\"50\" y2=\"0\">")
            .contains("<stop offset=\"100%\" stop-color=\"#0000ff\" stop-opacity=\"0.5\"/>")
            .contains("rx=\"20\" fill=\"url(#wf-fill-1)\"").contains("filter=\"url(#wf-shadow-large)\"")
            .contains("<filter id=\"wf-shadow-large\"").doesNotContain("feDropShadow")
            .contains("fill=\"#00ff00\" text-anchor=\"middle\">Go</text>")
            .contains("<path d=\"M 4 72 L 7 75 L 12 69\"")
            .contains("<line x1=\"0\" y1=\"100\" x2=\"100\" y2=\"100\" stroke=\"none\"")
    }

    @Test
    fun `an empty drawing is a page with its name and nothing else`() {
        val html = page("Blank", """{"elements":[]}""")
        assertThat(html).contains("<h1 class=\"wireframe-name\">Blank</h1>").doesNotContain("<section")
    }

    @Test
    fun `a file is named after the wireframe in words a file system takes`() {
        assertThat(WireframeHtml.fileName("Sign in / up: v2?", "html")).isEqualTo("Sign in up v2.html")
        assertThat(WireframeHtml.fileName("  ", "html")).isEqualTo("wireframe.html")
    }

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
