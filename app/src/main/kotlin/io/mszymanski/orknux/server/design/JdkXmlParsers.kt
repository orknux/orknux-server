package io.mszymanski.orknux.server.design

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

/**
 * The JDK's own XML parsers stay the ones `newInstance()` hands out.
 *
 * The ODF Toolkit, which writes a document's ODT, brings Xerces, and Xerces'
 * jar names itself as the provider of every JAXP factory - so with it on the
 * class path, every `SAXParserFactory.newInstance()` in the server became
 * Xerces 2.12, which predates the JDK's security properties. LanguageTool sets
 * one (`jdk.xml.maxGeneralEntitySizeLimit`) while it reads its rules, and
 * Xerces refused it: no language could be loaded. The ODF Toolkit builds its
 * Xerces factory by name rather than asking JAXP for one, so naming the JDK's
 * factories here gives everything else back the parser it had before the
 * toolkit arrived, and costs the toolkit nothing.
 *
 * Done once as the context starts, and again from `main` before anything has
 * had the chance to parse; a property set by hand on the command line wins.
 */
@Configuration
class JdkXmlParsers {

    init {
        prefer()
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdkXmlParsers::class.java)

        private val FACTORIES = mapOf(
            "javax.xml.parsers.SAXParserFactory" to "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl",
            "javax.xml.parsers.DocumentBuilderFactory" to "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl",
            "javax.xml.datatype.DatatypeFactory" to "com.sun.org.apache.xerces.internal.jaxp.datatype.DatatypeFactoryImpl",
            "javax.xml.validation.SchemaFactory:http://www.w3.org/2001/XMLSchema" to
                "com.sun.org.apache.xerces.internal.jaxp.validation.XMLSchemaFactory",
            "org.xml.sax.driver" to "com.sun.org.apache.xerces.internal.parsers.SAXParser",
        )

        fun prefer() {
            FACTORIES.forEach { (property, factory) ->
                if (System.getProperty(property) == null) System.setProperty(property, factory)
            }
            log.debug("JAXP factories are the JDK's own")
        }
    }
}
