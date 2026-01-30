/**
 * Gonzalez XML parser module.
 * 
 * <p>Provides a non-blocking, streaming XML parser using event-driven I/O.
 * The parser uses a push model where bytes are fed via
 * {@link org.bluezoo.gonzalez.Parser#receive(java.nio.ByteBuffer)} and SAX
 * events are delivered via the configured {@link org.xml.sax.ContentHandler}.
 * 
 * <p>Gonzalez can be used as a JAXP SAX parser provider via the standard
 * {@link javax.xml.parsers.SAXParserFactory} mechanism.
 * 
 * @see org.bluezoo.gonzalez.Parser
 * @see org.bluezoo.gonzalez.GonzalezSAXParserFactory
 */
module org.bluezoo.gonzalez {
    requires java.xml;
    exports org.bluezoo.gonzalez;
    exports org.bluezoo.gonzalez.schema;
    exports org.bluezoo.gonzalez.schema.xsd;
    
    provides javax.xml.parsers.SAXParserFactory
        with org.bluezoo.gonzalez.GonzalezSAXParserFactory;
}

