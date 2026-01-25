/*
 * XPathResultTreeFragment.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.transform.xpath.type;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * An XPath value representing a Result Tree Fragment (RTF).
 *
 * <p>In XSLT 1.0, when a variable is assigned content (not via select),
 * the result is a result tree fragment. RTFs can be:
 * <ul>
 *   <li>Converted to string (text content only)</li>
 *   <li>Copied via xsl:copy-of (full tree structure)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathResultTreeFragment implements XPathValue {

    private final SAXEventBuffer buffer;

    /**
     * Creates a result tree fragment from a SAX event buffer.
     *
     * @param buffer the buffered SAX events
     */
    public XPathResultTreeFragment(SAXEventBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Returns the underlying SAX event buffer.
     *
     * @return the buffer
     */
    public SAXEventBuffer getBuffer() {
        return buffer;
    }

    /**
     * Replays the buffered events to a content handler.
     * This is used by xsl:copy-of to copy the full tree structure.
     *
     * @param handler the target handler
     * @throws SAXException if replay fails
     */
    public void replay(ContentHandler handler) throws SAXException {
        buffer.replayContent(handler);
    }

    /**
     * Replays the buffered events to an output handler.
     * This adapts the SAX events to OutputHandler calls.
     *
     * @param output the target output handler
     * @throws SAXException if replay fails
     */
    public void replayToOutput(OutputHandler output) throws SAXException {
        // Use an adapter that converts ContentHandler calls to OutputHandler calls
        ContentHandler adapter = new OutputHandlerAdapter(output);
        buffer.replayContent(adapter);
    }

    /**
     * Adapter that converts ContentHandler calls to OutputHandler calls.
     */
    private static class OutputHandlerAdapter implements ContentHandler {
        private final OutputHandler output;

        OutputHandlerAdapter(OutputHandler output) {
            this.output = output;
        }

        @Override
        public void setDocumentLocator(org.xml.sax.Locator locator) {}

        @Override
        public void startDocument() throws SAXException {
            output.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            output.endDocument();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            // Namespace mappings will be handled in startElement
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            // Nothing needed
        }

        @Override
        public void startElement(String uri, String localName, String qName, 
                                 org.xml.sax.Attributes atts) throws SAXException {
            output.startElement(uri, localName, qName);
            // Copy attributes
            for (int i = 0; i < atts.getLength(); i++) {
                String attrUri = atts.getURI(i);
                String attrLocal = atts.getLocalName(i);
                String attrQName = atts.getQName(i);
                String attrValue = atts.getValue(i);
                
                // Check if this is a namespace declaration
                if (attrQName.startsWith("xmlns")) {
                    String prefix = attrQName.equals("xmlns") ? "" : attrQName.substring(6);
                    output.namespace(prefix, attrValue);
                } else {
                    output.attribute(attrUri != null ? attrUri : "", 
                                    attrLocal, attrQName, attrValue);
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            output.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            output.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            // Ignore
        }
    }

    @Override
    public Type getType() {
        // RTF is not a standard XPath 1.0 type; treat as nodeset for compatibility
        return Type.NODESET;
    }

    @Override
    public String asString() {
        return buffer.getTextContent();
    }

    @Override
    public double asNumber() {
        try {
            return Double.parseDouble(asString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Override
    public boolean asBoolean() {
        // RTFs are converted to string for boolean - non-empty is true
        return !asString().isEmpty();
    }

    @Override
    public XPathNodeSet asNodeSet() {
        // RTFs cannot be directly converted to node-sets in XSLT 1.0
        // They can only be used with xsl:copy-of or converted to string
        return null;
    }

    @Override
    public String toString() {
        return "RTF[" + asString() + "]";
    }

}
