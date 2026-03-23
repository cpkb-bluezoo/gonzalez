/**
 * Gonzalez full module (core + XSLT combined).
 *
 * <p>Single-module fat jar for backward compatibility. Combines the parser,
 * writer, DTD, XSLT transformer, and schema support.
 *
 * @see org.bluezoo.gonzalez.Parser
 * @see org.bluezoo.gonzalez.transform.GonzalezTransformerFactory
 */
module org.bluezoo.gonzalez {
    requires java.xml;
    requires org.bluezoo.json;
    exports org.bluezoo.gonzalez;
    exports org.bluezoo.gonzalez.schema;
    exports org.bluezoo.gonzalez.schema.xsd;
    exports org.bluezoo.gonzalez.transform;

    provides javax.xml.parsers.SAXParserFactory
        with org.bluezoo.gonzalez.GonzalezSAXParserFactory;
    provides javax.xml.transform.TransformerFactory
        with org.bluezoo.gonzalez.transform.GonzalezTransformerFactory;
}
