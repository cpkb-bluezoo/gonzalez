/*
 * CompilerBufferOutputHandler.java
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

package org.bluezoo.gonzalez.transform.compiler;

import java.util.ArrayList;
import java.util.List;

import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;

/**
 * Compiler-specific OutputHandler adapter for SAXEventBuffer.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class CompilerBufferOutputHandler implements OutputHandler {
    private final SAXEventBuffer buffer;
    private boolean inStartTag = false;
    private String pendingUri, pendingLocalName, pendingQName;
    private final AttributesImpl pendingAttrs = new AttributesImpl();
    private final List<String[]> pendingNamespaces = new ArrayList<>();
    
    CompilerBufferOutputHandler(SAXEventBuffer buffer) {
        this.buffer = buffer;
    }
    
    @Override public void startDocument() throws SAXException { buffer.startDocument(); }
    @Override public void endDocument() throws SAXException { flush(); buffer.endDocument(); }
    
    @Override 
    public void startElement(String uri, String localName, String qName) throws SAXException {
        flush();
        inStartTag = true;
        pendingUri = OutputHandlerUtils.effectiveUri(uri);
        pendingLocalName = localName;
        pendingQName = OutputHandlerUtils.effectiveQName(qName, localName);
        pendingAttrs.clear();
        pendingNamespaces.clear();
    }
    
    @Override 
    public void endElement(String uri, String localName, String qName) throws SAXException {
        flush();
        String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
        String effectiveQName = OutputHandlerUtils.effectiveQName(qName, localName);
        buffer.endElement(effectiveUri, localName, effectiveQName);
    }
    
    @Override
    public void attribute(String uri, String localName, String qName, String value) throws SAXException {
        if (!inStartTag) {
            throw new SAXException("Attribute outside of start tag");
        }
        String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
        String effectiveQName = OutputHandlerUtils.effectiveQName(qName, localName);
        int existing = pendingAttrs.getIndex(effectiveUri, localName);
        if (existing >= 0) {
            pendingAttrs.setAttribute(existing, effectiveUri, localName,
                effectiveQName, "CDATA", value);
        } else {
            pendingAttrs.addAttribute(effectiveUri, localName,
                effectiveQName, "CDATA", value);
        }
    }
    
    @Override
    public void namespace(String prefix, String uri) throws SAXException {
        
        // Queue namespace if in start tag, otherwise emit immediately
        if (inStartTag) {
            String effectivePrefix = OutputHandlerUtils.effectiveUri(prefix);
            String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
            pendingNamespaces.add(new String[] { effectivePrefix, effectiveUri });
        } else {
            String effectivePrefix = OutputHandlerUtils.effectiveUri(prefix);
            String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
            buffer.startPrefixMapping(effectivePrefix, effectiveUri);
        }
    }
    
    @Override
    public void characters(String text) throws SAXException {
        flush();
        buffer.characters(text.toCharArray(), 0, text.length());
    }
    
    @Override
    public void charactersRaw(String text) throws SAXException {
        characters(text); // No raw support in buffer
    }
    
    @Override
    public void comment(String text) throws SAXException {
        flush();
        // SAXEventBuffer implements LexicalHandler, so we can pass comments
        char[] ch = text.toCharArray();
        buffer.comment(ch, 0, ch.length);
    }
    
    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        flush();
        buffer.processingInstruction(target, data);
    }
    
    // Pending type annotations
    private String pendingTypeNs = null;
    private String pendingTypeLocal = null;
    private final List<String[]> pendingAttrTypes = new ArrayList<>();
    
    @Override
    public void setElementType(String namespaceURI, String localName) throws SAXException {
        if (inStartTag) {
            pendingTypeNs = namespaceURI;
            pendingTypeLocal = localName;
        }
    }
    
    @Override
    public void setAttributeType(String namespaceURI, String localName) throws SAXException {
        if (inStartTag) {
            // Track type for the most recently added attribute
            int attrIdx = pendingAttrs.getLength() - 1;
            while (pendingAttrTypes.size() <= attrIdx) {
                pendingAttrTypes.add(null);
            }
            if (attrIdx >= 0) {
                pendingAttrTypes.set(attrIdx, new String[] {namespaceURI, localName});
            }
        }
    }
    
    @Override
    public void flush() throws SAXException {
        if (inStartTag) {
            // Emit namespace declarations first (SAX requires startPrefixMapping before startElement)
            for (String[] ns : pendingNamespaces) {
                buffer.startPrefixMapping(ns[0], ns[1]);
            }
            // Emit startElement with type annotations if present
            if (pendingTypeLocal != null || !pendingAttrTypes.isEmpty()) {
                buffer.startElementWithTypes(pendingUri, pendingLocalName, pendingQName, 
                    pendingAttrs, pendingTypeNs, pendingTypeLocal, pendingAttrTypes);
            } else {
                buffer.startElement(pendingUri, pendingLocalName, pendingQName, pendingAttrs);
            }
            inStartTag = false;
            pendingAttrs.clear();
            pendingNamespaces.clear();
            pendingTypeNs = null;
            pendingTypeLocal = null;
            pendingAttrTypes.clear();
        }
    }
}
