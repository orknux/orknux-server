package io.mszymanski.orknux.server.embedded

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathEvaluationResult
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPathNodes

/**
 * An XML file somebody handed an agent, parsed and asked questions of with the
 * JDK's own parser and XPath 1.0.
 *
 * **Hostile input.** A file an agent is asked about came from anywhere, and XML
 * can tell its parser to read another file (an external entity), fetch an
 * address (an external DTD) or expand one entity into gigabytes. So: secure
 * processing on, which bounds entity expansion and switches off XPath extension
 * functions; no external DTD is loaded; every external access is refused, both
 * by the JDK's access properties and by a resolver that throws. A doctype on
 * its own is allowed, because every KiCad export carries one naming the SVG 1.1
 * DTD - refusing it would refuse the files these tools are for - and it is
 * never fetched.
 *
 * **Namespaces both ways.** An SVG puts every element in the SVG namespace, so
 * XPath 1.0's `//path` matches nothing on it - the spec's rule, and the first
 * thing anybody writes. `svg:` and every prefix the document declares are
 * registered, so `//svg:path` and a test on `local-name()` work as written;
 * and an expression that matches nothing on a document with a default
 * namespace is asked again of the same file read without namespaces, where
 * `//path` means what it was meant to.
 */
object HostileXml {

    const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
    private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"

    /** Why a file was not read or an expression not run, in words a model can act on. */
    class Refused(message: String) : RuntimeException(message)

    /** The file as a document; [Refused] where it is not well-formed XML or reaches outside itself. */
    fun parse(bytes: ByteArray, namespaces: Boolean = true): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = namespaces
            isValidating = false
            isXIncludeAware = false
            // Expanded, so an external entity reaches the resolver below and is refused aloud rather than left as an empty reference.
            isExpandEntityReferences = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver(REFUSE_EXTERNAL)
        builder.setErrorHandler(QUIET)
        return try {
            builder.parse(ByteArrayInputStream(bytes))
        } catch (refused: SAXException) {
            val said = refused.message.orEmpty()
            throw Refused(
                if (said.contains(EXTERNAL_REFUSED) || said.contains("accessExternal")) {
                    "this file asks for something outside itself (an external entity or DTD), which is refused: $said"
                } else {
                    val line = (refused as? SAXParseException)?.lineNumber?.takeIf { it > 0 }?.let { " at line $it" }.orEmpty()
                    "this is not well-formed XML$line: $said"
                },
            )
        }
    }

    /** What an expression answered: nodes, or one value. */
    sealed interface Answer {
        data class Nodes(val nodes: List<Node>) : Answer
        data class Value(val value: Any?) : Answer
    }

    /** [expression] evaluated against [document]; [Refused] where it is not XPath 1.0. */
    fun evaluate(document: Document, expression: String, from: Node = document): Answer {
        val xpath = XPathFactory.newInstance().apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }.newXPath()
        xpath.namespaceContext = Prefixes(document)
        val compiled = try {
            xpath.compile(expression)
        } catch (wrong: XPathExpressionException) {
            throw Refused("that is not an XPath 1.0 expression: " + (wrong.cause?.message ?: wrong.message ?: "it does not parse"))
        }
        val result = try {
            compiled.evaluateExpression(from)
        } catch (wrong: XPathExpressionException) {
            throw Refused("that expression could not be evaluated: " + (wrong.cause?.message ?: wrong.message ?: "it failed"))
        }
        return when (result.type()) {
            XPathEvaluationResult.XPathResultType.NODESET -> Answer.Nodes((result.value() as XPathNodes).toList())
            XPathEvaluationResult.XPathResultType.NODE -> Answer.Nodes(listOfNotNull(result.value() as Node?))
            XPathEvaluationResult.XPathResultType.NUMBER -> Answer.Value(
                (result.value() as Number).toDouble().let { if (it == Math.rint(it) && !it.isInfinite() && Math.abs(it) < 1e15) it.toLong() else it },
            )
            else -> Answer.Value(result.value())
        }
    }

    /** What [query] answered, whether the namespace-blind retry is what answered, and the document it ran on. */
    data class Queried(val answer: Answer, val namespacesIgnored: Boolean, val document: Document)

    /**
     * [expression] against the file, retried without namespaces where it found
     * nothing on a document with a default namespace.
     */
    fun query(bytes: ByteArray, expression: String): Queried {
        val document = parse(bytes)
        val first = evaluate(document, expression)
        val defaulted = document.documentElement?.namespaceURI != null && document.documentElement?.prefix == null
        if (!defaulted || first !is Answer.Nodes || first.nodes.isNotEmpty()) return Queried(first, false, document)
        val blind = parse(bytes, namespaces = false)
        val plain = evaluate(blind, expression)
        return if (plain is Answer.Nodes && plain.nodes.isNotEmpty()) Queried(plain, true, blind) else Queried(first, false, document)
    }

    /** `svg`, `xlink`, and every prefix the root element declares. */
    private class Prefixes(document: Document) : NamespaceContext {
        private val declared: Map<String, String> = buildMap {
            put("svg", SVG_NAMESPACE)
            put("xlink", XLINK_NAMESPACE)
            put(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI)
            val root: Element? = document.documentElement
            val attributes = root?.attributes
            if (attributes != null) {
                for (index in 0 until attributes.length) {
                    val attribute = attributes.item(index)
                    if (attribute.nodeName.startsWith("xmlns:")) put(attribute.nodeName.removePrefix("xmlns:"), attribute.nodeValue)
                }
            }
        }

        override fun getNamespaceURI(prefix: String?): String = declared[prefix] ?: XMLConstants.NULL_NS_URI
        override fun getPrefix(namespaceURI: String?): String? = declared.entries.firstOrNull { it.value == namespaceURI }?.key
        override fun getPrefixes(namespaceURI: String?): Iterator<String> =
            declared.filterValues { it == namespaceURI }.keys.iterator()
    }

    private const val EXTERNAL_REFUSED = "external entities and DTDs are refused"

    private val REFUSE_EXTERNAL = EntityResolver { publicId, systemId ->
        throw SAXException("$EXTERNAL_REFUSED (${systemId ?: publicId})")
    }

    /** Throws on what stops a parse, and keeps warnings off the console. */
    private val QUIET = object : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }
}
