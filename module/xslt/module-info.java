/**
 * Gonzalez XSLT transformer module.
 *
 * <p>Provides XSLT 1.0, 2.0, and 3.0 transformation with streaming support.
 * Requires the Gonzalez core module for XML parsing and the jsonparser
 * library for JSON functions (xml-to-json, json-to-xml, parse-json).
 *
 * @see org.bluezoo.gonzalez.transform.GonzalezTransformerFactory
 */
module org.bluezoo.gonzalez.xslt {
    requires org.bluezoo.gonzalez;
    requires org.bluezoo.json;
    requires java.xml;

    exports org.bluezoo.gonzalez.schema;
    exports org.bluezoo.gonzalez.schema.xsd;
    exports org.bluezoo.gonzalez.transform;

    provides javax.xml.transform.TransformerFactory
        with org.bluezoo.gonzalez.transform.GonzalezTransformerFactory;
}
