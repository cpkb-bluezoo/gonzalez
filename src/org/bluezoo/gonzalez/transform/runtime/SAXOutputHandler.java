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
 * ContentHandler that serializes SAX events to a Writer as XML.
 *
 * <p>This handler is used for XML/XHTML output when a Writer is provided
 * (rather than an OutputStream). For stream-based XML output, prefer
 * {@link XMLWriterOutputHandler} which uses the optimized {@link org.bluezoo.gonzalez.XMLWriter}.
 *
 * <p>For HTML output, use {@link HTMLOutputHandler}.
 * For text output, use {@link TextOutputHandler}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SAXOutputHandler implements ContentHandler, OutputHandler {

    private final Writer writer;
    private final Properties outputProperties;
    private final String encoding;
    private final boolean indent;
    private final boolean omitXmlDeclaration;
    
    private final Deque<String> elementStack = new ArrayDeque<>();
    private final Map<String, String> pendingNamespaces = new LinkedHashMap<>();
    private final AttributesImpl pendingAttributes = new AttributesImpl();
    private boolean inStartTag = false;
    private int depth = 0;
    
    // Namespace scope tracking - stack of maps from prefix to URI
    private final Deque<Map<String, String>> namespaceScopeStack = new ArrayDeque<>();

    /**
     * Creates an XML output handler.
     *
     * @param writer the output writer
     * @param outputProperties output configuration
     */
    public SAXOutputHandler(Writer writer, Properties outputProperties) {
        this.writer = writer;
        this.outputProperties = outputProperties;
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
            // Get current namespace scope (created by startElement)
            Map<String, String> currentScope = namespaceScopeStack.peek();
            
            // Output pending namespaces (only if not already in scope with same URI)
            for (Map.Entry<String, String> ns : pendingNamespaces.entrySet()) {
                String prefix = ns.getKey();
                String uri = ns.getValue();
                
                // Check if this namespace is already in scope
                String inScopeUri = getNamespaceInScope(prefix);
                if (inScopeUri != null && inScopeUri.equals(uri)) {
                    continue; // Already in scope, don't output
                }
                
                // Output the namespace declaration
                if (prefix.isEmpty()) {
                    write(" xmlns=\"" + escapeAttr(uri) + "\"");
                } else {
                    write(" xmlns:" + prefix + "=\"" + escapeAttr(uri) + "\"");
                }
                
                // Add to current scope
                if (currentScope != null) {
                    currentScope.put(prefix, uri);
                }
            }
            pendingNamespaces.clear();
            
            // Output pending attributes
            for (int i = 0; i < pendingAttributes.getLength(); i++) {
                write(" " + pendingAttributes.getQName(i) + "=\"" + 
                      escapeAttr(pendingAttributes.getValue(i)) + "\"");
            }
            pendingAttributes.clear();
            
            write(">");
            inStartTag = false;
        }
    }

    private String escapeAttr(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;");
    }

    private String escapeText(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ContentHandler implementation

    @Override
    public void setDocumentLocator(Locator locator) {
    }

    @Override
    public void startDocument() throws SAXException {
        if (!omitXmlDeclaration) {
            String version = outputProperties.getProperty("version", "1.0");
            write("<?xml version=\"" + version + "\" encoding=\"" + encoding + "\"?>");
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
        
        // Push new namespace scope
        namespaceScopeStack.push(new LinkedHashMap<>());
        
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
            // Get current namespace scope for checking
            Map<String, String> currentScope = namespaceScopeStack.peek();
            
            for (Map.Entry<String, String> ns : pendingNamespaces.entrySet()) {
                String prefix = ns.getKey();
                String nsUri = ns.getValue();
                
                // Check if namespace is already in scope
                String inScopeUri = getNamespaceInScope(prefix);
                if (inScopeUri != null && inScopeUri.equals(nsUri)) {
                    continue; // Already in scope
                }
                
                if (prefix.isEmpty()) {
                    write(" xmlns=\"" + escapeAttr(nsUri) + "\"");
                } else {
                    write(" xmlns:" + prefix + "=\"" + escapeAttr(nsUri) + "\"");
                }
                
                // Record in current scope
                if (currentScope != null) {
                    currentScope.put(prefix, nsUri);
                }
            }
            pendingNamespaces.clear();
            
            for (int i = 0; i < pendingAttributes.getLength(); i++) {
                write(" " + pendingAttributes.getQName(i) + "=\"" + 
                      escapeAttr(pendingAttributes.getValue(i)) + "\"");
            }
            pendingAttributes.clear();
            
            write("/>");
            inStartTag = false;
            
            // Pop namespace scope for empty element
            if (!namespaceScopeStack.isEmpty()) {
                namespaceScopeStack.pop();
            }
        } else {
            if (indent) {
                write("\n");
                for (int i = 0; i < depth; i++) write("  ");
            }
            write("</" + qName + ">");
            
            // Pop namespace scope for non-empty element
            if (!namespaceScopeStack.isEmpty()) {
                namespaceScopeStack.pop();
            }
        }
        
        elementStack.pop();
    }
    
    /**
     * Gets the namespace URI bound to a prefix in the current scope stack.
     * Walks up the stack to find the nearest binding.
     */
    private String getNamespaceInScope(String prefix) {
        for (Map<String, String> scope : namespaceScopeStack) {
            String uri = scope.get(prefix);
            if (uri != null) {
                return uri;
            }
        }
        return null;
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        closeStartTag();
        write(escapeText(new String(ch, start, length)));
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
        startElement(namespaceURI, localName, qName, new AttributesImpl());
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value) 
            throws SAXException {
        if (!inStartTag) {
            throw new SAXException("attribute() called outside element start");
        }
        
        // Check for existing attribute with same name (for duplicate detection)
        int existingIndex = -1;
        for (int i = 0; i < pendingAttributes.getLength(); i++) {
            String existingUri = pendingAttributes.getURI(i);
            String existingLocal = pendingAttributes.getLocalName(i);
            
            // Match by namespace URI + local name
            boolean uriMatch = (namespaceURI == null || namespaceURI.isEmpty()) 
                ? (existingUri == null || existingUri.isEmpty())
                : namespaceURI.equals(existingUri);
            boolean localMatch = localName.equals(existingLocal);
            
            if (uriMatch && localMatch) {
                existingIndex = i;
                break;
            }
        }
        
        if (existingIndex >= 0) {
            // Update existing attribute value
            pendingAttributes.setValue(existingIndex, value);
        } else {
            // Add new attribute
            pendingAttributes.addAttribute(namespaceURI, localName, qName, "CDATA", value);
        }
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        pendingNamespaces.put(prefix, uri);
    }

    @Override
    public void characters(String text) throws SAXException {
        closeStartTag();
        write(escapeText(text));
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

}
