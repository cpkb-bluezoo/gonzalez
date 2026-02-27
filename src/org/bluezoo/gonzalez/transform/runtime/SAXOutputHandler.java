/*
 * SAXOutputHandler.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.xml.sax.*;
import org.xml.sax.helpers.AttributesImpl;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * ContentHandler that serializes SAX events to a Writer.
 *
 * <p>This handler converts SAX events to XML text output, supporting the
 * various output methods (xml, html, text) and options.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SAXOutputHandler implements ContentHandler, OutputHandler {

    private final Writer writer;
    private final Properties outputProperties;
    private final String method;
    private final String encoding;
    private final boolean indent;
    private final boolean omitXmlDeclaration;
    
    private final Deque<String> elementStack = new ArrayDeque<>();
    private final Map<String, String> pendingNamespaces = new LinkedHashMap<>();
    private final AttributesImpl pendingAttributes = new AttributesImpl();
    private boolean inStartTag = false;
    private int depth = 0;
    
    // XSLT 2.0 atomic value spacing state
    private boolean atomicValuePending = false;
    private boolean inAttributeContent = false;
    private boolean contentReceived = false;

    /**
     * Creates an output handler.
     *
     * @param writer the output writer
     * @param outputProperties output configuration
     */
    public SAXOutputHandler(Writer writer, Properties outputProperties) {
        this.writer = writer;
        this.outputProperties = outputProperties;
        this.method = outputProperties.getProperty("method", "xml");
        this.encoding = outputProperties.getProperty("encoding", "UTF-8");
        this.indent = "yes".equals(outputProperties.getProperty("indent"));
        this.omitXmlDeclaration = "yes".equals(outputProperties.getProperty("omit-xml-declaration"));
    }

    private void write(String s) throws SAXException {
        try {
            writer.write(s);
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    private void closeStartTag() throws SAXException {
        if (inStartTag) {
            // Output pending namespaces
            for (Map.Entry<String, String> ns : pendingNamespaces.entrySet()) {
                String prefix = ns.getKey();
                String uri = ns.getValue();
                if (prefix.isEmpty()) {
                    write(" xmlns=\"" + OutputHandlerUtils.escapeXmlAttribute(uri) + "\"");
                } else {
                    write(" xmlns:" + prefix + "=\"" + OutputHandlerUtils.escapeXmlAttribute(uri) + "\"");
                }
            }
            pendingNamespaces.clear();
            
            // Output pending attributes
            for (int i = 0; i < pendingAttributes.getLength(); i++) {
                write(" " + pendingAttributes.getQName(i) + "=\"" + 
                      OutputHandlerUtils.escapeXmlAttribute(pendingAttributes.getValue(i)) + "\"");
            }
            pendingAttributes.clear();
            
            write(">");
            inStartTag = false;
        }
    }

    // ContentHandler implementation

    @Override
    public void setDocumentLocator(Locator locator) {
    }

    @Override
    public void startDocument() throws SAXException {
        if ("xml".equals(method) && !omitXmlDeclaration) {
            write("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>");
            if (indent) write("\n");
        }
    }

    @Override
    public void endDocument() throws SAXException {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        pendingNamespaces.put(prefix, uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) 
            throws SAXException {
        closeStartTag();
        
        if (indent && depth > 0) {
            write("\n");
            for (int i = 0; i < depth; i++) write("  ");
        }
        
        write("<" + qName);
        
        // Copy attributes to pending
        for (int i = 0; i < atts.getLength(); i++) {
            pendingAttributes.addAttribute(
                atts.getURI(i), atts.getLocalName(i), atts.getQName(i),
                atts.getType(i), atts.getValue(i));
        }
        
        elementStack.push(qName);
        inStartTag = true;
        depth++;
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        depth--;
        
        if (inStartTag) {
            // Empty element - use self-closing tag
            for (Map.Entry<String, String> ns : pendingNamespaces.entrySet()) {
                String prefix = ns.getKey();
                String nsUri = ns.getValue();
                if (prefix.isEmpty()) {
                    write(" xmlns=\"" + OutputHandlerUtils.escapeXmlAttribute(nsUri) + "\"");
                } else {
                    write(" xmlns:" + prefix + "=\"" + OutputHandlerUtils.escapeXmlAttribute(nsUri) + "\"");
                }
            }
            pendingNamespaces.clear();
            
            for (int i = 0; i < pendingAttributes.getLength(); i++) {
                write(" " + pendingAttributes.getQName(i) + "=\"" + 
                      OutputHandlerUtils.escapeXmlAttribute(pendingAttributes.getValue(i)) + "\"");
            }
            pendingAttributes.clear();
            
            write("/>");
            inStartTag = false;
        } else {
            if (indent) {
                write("\n");
                for (int i = 0; i < depth; i++) write("  ");
            }
            write("</" + qName + ">");
        }
        
        elementStack.pop();
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        closeStartTag();
        write(OutputHandlerUtils.escapeXmlText(new String(ch, start, length)));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        closeStartTag();
        write(new String(ch, start, length));
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        closeStartTag();
        write("<?" + target + " " + data + "?>");
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
    }

    // OutputHandler implementation

    @Override
    public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
        contentReceived = true;
        startElement(namespaceURI, localName, qName, new AttributesImpl());
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        if (!inStartTag) {
            throw new SAXException("attribute() called outside element start");
        }
        pendingAttributes.addAttribute(namespaceURI, localName, qName, "CDATA", value);
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        // Skip the xml prefix - it's implicitly bound to http://www.w3.org/XML/1998/namespace
        // and should never be declared
        if (OutputHandlerUtils.isXmlPrefix(prefix)) {
            return;
        }
        pendingNamespaces.put(prefix, uri);
    }

    @Override
    public void characters(String text) throws SAXException {
        contentReceived = true;
        closeStartTag();
        write(OutputHandlerUtils.escapeXmlText(text));
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        closeStartTag();
        write(text); // No escaping
    }

    @Override
    public void comment(String text) throws SAXException {
        closeStartTag();
        write("<!--" + text + "-->");
    }

    @Override
    public void flush() throws SAXException {
        closeStartTag();
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }
    
    @Override
    public boolean isAtomicValuePending() {
        return atomicValuePending;
    }
    
    @Override
    public void setAtomicValuePending(boolean pending) {
        this.atomicValuePending = pending;
    }
    
    @Override
    public boolean isInAttributeContent() {
        return inAttributeContent;
    }
    
    @Override
    public void setInAttributeContent(boolean inAttributeContent) {
        this.inAttributeContent = inAttributeContent;
    }

    @Override
    public boolean hasReceivedContent() {
        return contentReceived;
    }

}
