package io.mszymanski.orknux.server.design

import org.assertj.core.api.Assertions.assertThat
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
