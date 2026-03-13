/*
 * WherePopulatedNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;

/**
 * WherePopulatedNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class WherePopulatedNode extends XSLTInstruction {
    private final XSLTNode content;
    
    public WherePopulatedNode(XSLTNode content) {
        this.content = content;
    }
    
    @Override public String getInstructionName() { return "where-populated"; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (content == null) {
            return;
        }
        
        // Execute content into a deep-empty filtering handler that splits
        // the output into top-level items and tracks whether each is populated
        DeepEmptyFilter filter = new DeepEmptyFilter();
        content.execute(context, filter);
        filter.finishCurrentItem();
        
        // Replay only populated items
        List<ItemRecord> items = filter.getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemRecord item = items.get(i);
            if (!item.deepEmpty) {
                if (item.isAttribute) {
                    output.attribute(item.attrUri, item.attrLocal, item.attrQName, item.attrValue);
                } else if (item.isNamespace) {
                    output.namespace(item.nsPrefix, item.nsUri);
                } else if (item.buffer != null && !item.buffer.isEmpty()) {
                    item.buffer.replayContent(new SAXToOutputAdapter(output));
                }
            }
        }
    }

    /**
     * Record for a single top-level item in the where-populated trial sequence.
     */
    private static class ItemRecord {
        SAXEventBuffer buffer;
        boolean deepEmpty;
        boolean isAttribute;
        String attrUri;
        String attrLocal;
        String attrQName;
        String attrValue;
        boolean isNamespace;
        String nsPrefix;
        String nsUri;
    }

    /**
     * OutputHandler that splits content into individual top-level items and
     * tracks whether each item is deep-empty per XSLT 3.0 section 11.9.
     *
     * <p>An item is deep-empty if:
     * <ul>
     *   <li>It is a text node of zero length</li>
     *   <li>It is an element whose only children are all deep-empty</li>
     * </ul>
     * Attributes, namespace nodes, PIs, and comments are always populated.
     * Uses deferred start tag to collect attributes before writing to buffer.
     */
    private static class DeepEmptyFilter implements OutputHandler {
        private final List<ItemRecord> items = new ArrayList<>();
        private SAXEventBuffer currentBuffer;
        private int depth = 0;
        private boolean[] populatedAtDepth = new boolean[64];
        private boolean inStartTag = false;
        private String pendingUri;
        private String pendingLocal;
        private String pendingQName;
        private final org.xml.sax.helpers.AttributesImpl pendingAttrs =
            new org.xml.sax.helpers.AttributesImpl();
        private final List<String[]> pendingNs = new ArrayList<>();

        List<ItemRecord> getItems() {
            return items;
        }

        private void flushStartTag() throws SAXException {
            if (!inStartTag) {
                return;
            }
            if (currentBuffer == null) {
                currentBuffer = new SAXEventBuffer();
            }
            for (int i = 0; i < pendingNs.size(); i++) {
                String[] ns = pendingNs.get(i);
                currentBuffer.startPrefixMapping(ns[0], ns[1]);
            }
            pendingNs.clear();
            currentBuffer.startElement(pendingUri, pendingLocal, pendingQName, pendingAttrs);
            pendingAttrs.clear();
            inStartTag = false;
        }

        void finishCurrentItem() throws SAXException {
            flushStartTag();
            if (currentBuffer != null && !currentBuffer.isEmpty()) {
                ItemRecord rec = new ItemRecord();
                rec.buffer = currentBuffer;
                rec.deepEmpty = false;
                items.add(rec);
                currentBuffer = null;
            }
        }

        public void startDocument() throws SAXException {
            flushStartTag();
            if (depth == 0) {
                finishCurrentItem();
                currentBuffer = new SAXEventBuffer();
                populatedAtDepth[0] = false;
            }
            depth++;
            if (depth < populatedAtDepth.length) {
                populatedAtDepth[depth] = false;
            }
        }

        public void endDocument() throws SAXException {
            flushStartTag();
            boolean childrenPopulated = false;
            if (depth > 0 && depth < populatedAtDepth.length) {
                childrenPopulated = populatedAtDepth[depth];
            }
            depth--;
            if (depth >= 0 && depth < populatedAtDepth.length) {
                if (childrenPopulated) {
                    populatedAtDepth[depth] = true;
                }
            }
            if (depth == 0) {
                ItemRecord rec = new ItemRecord();
                rec.buffer = currentBuffer;
                rec.deepEmpty = !populatedAtDepth[0];
                items.add(rec);
                currentBuffer = null;
            }
        }

        public void startElement(String uri, String localName, String qName) throws SAXException {
            flushStartTag();
            if (depth == 0) {
                finishCurrentItem();
                currentBuffer = new SAXEventBuffer();
                populatedAtDepth[0] = false;
            }
            inStartTag = true;
            pendingUri = OutputHandlerUtils.effectiveUri(uri);
            pendingLocal = localName;
            pendingQName = OutputHandlerUtils.effectiveQName(qName, localName);
            pendingAttrs.clear();
            depth++;
            if (depth < populatedAtDepth.length) {
                populatedAtDepth[depth] = false;
            }
        }

        public void endElement(String uri, String localName, String qName) throws SAXException {
            flushStartTag();
            if (currentBuffer == null) {
                currentBuffer = new SAXEventBuffer();
            }
            String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
            String effectiveQName = OutputHandlerUtils.effectiveQName(qName, localName);
            currentBuffer.endElement(effectiveUri, localName, effectiveQName);
            boolean childrenPopulated = false;
            if (depth < populatedAtDepth.length) {
                childrenPopulated = populatedAtDepth[depth];
            }
            depth--;
            if (depth >= 0 && depth < populatedAtDepth.length) {
                if (depth >= 1 || childrenPopulated) {
                    populatedAtDepth[depth] = true;
                }
            }
            if (depth == 0) {
                ItemRecord rec = new ItemRecord();
                rec.buffer = currentBuffer;
                rec.deepEmpty = !populatedAtDepth[0];
                items.add(rec);
                currentBuffer = null;
            }
        }

        public void attribute(String uri, String localName, String qName, String value) 
                throws SAXException {
            if (depth == 0 && !inStartTag) {
                ItemRecord rec = new ItemRecord();
                rec.isAttribute = true;
                rec.attrUri = uri;
                rec.attrLocal = localName;
                rec.attrQName = qName;
                rec.attrValue = value;
                rec.deepEmpty = (value == null || value.isEmpty());
                items.add(rec);
            } else if (inStartTag) {
                String effectiveUri = OutputHandlerUtils.effectiveUri(uri);
                String effectiveQName = OutputHandlerUtils.effectiveQName(qName, localName);
                pendingAttrs.addAttribute(
                    effectiveUri, localName,
                    effectiveQName, "CDATA", value);
            }
        }

        public void namespace(String prefix, String uri) throws SAXException {
            if (depth == 0 && !inStartTag) {
                ItemRecord rec = new ItemRecord();
                rec.isNamespace = true;
                rec.nsPrefix = prefix;
                rec.nsUri = uri;
                rec.deepEmpty = false;
                items.add(rec);
            } else if (inStartTag) {
                pendingNs.add(new String[]{prefix != null ? prefix : "", uri});
            } else {
                flushStartTag();
                if (currentBuffer == null) {
                    currentBuffer = new SAXEventBuffer();
                }
                currentBuffer.startPrefixMapping(prefix != null ? prefix : "", uri);
            }
        }

        public void characters(String text) throws SAXException {
            if (text == null || text.isEmpty()) {
                return;
            }
            flushStartTag();
            if (depth == 0) {
                ItemRecord rec = new ItemRecord();
                rec.buffer = new SAXEventBuffer();
                rec.buffer.characters(text.toCharArray(), 0, text.length());
                rec.deepEmpty = false;
                items.add(rec);
            } else {
                if (currentBuffer == null) {
                    currentBuffer = new SAXEventBuffer();
                }
                currentBuffer.characters(text.toCharArray(), 0, text.length());
                if (depth < populatedAtDepth.length) {
                    populatedAtDepth[depth] = true;
                }
            }
        }

        public void charactersRaw(String text) throws SAXException {
            characters(text);
        }

        public void comment(String text) throws SAXException {
            flushStartTag();
            boolean isEmpty = (text == null || text.isEmpty());
            if (depth == 0) {
                ItemRecord rec = new ItemRecord();
                rec.buffer = new SAXEventBuffer();
                if (!isEmpty) {
                    char[] ch = text.toCharArray();
                    rec.buffer.comment(ch, 0, ch.length);
                }
                rec.deepEmpty = isEmpty;
                items.add(rec);
            } else {
                if (currentBuffer == null) {
                    currentBuffer = new SAXEventBuffer();
                }
                if (!isEmpty) {
                    char[] ch = text.toCharArray();
                    currentBuffer.comment(ch, 0, ch.length);
                }
            }
        }

        public void processingInstruction(String target, String data) throws SAXException {
            flushStartTag();
            boolean isEmpty = (data == null || data.isEmpty());
            if (depth == 0) {
                ItemRecord rec = new ItemRecord();
                rec.buffer = new SAXEventBuffer();
                if (!isEmpty) {
                    rec.buffer.processingInstruction(target, data);
                }
                rec.deepEmpty = isEmpty;
                items.add(rec);
            } else {
                if (currentBuffer == null) {
                    currentBuffer = new SAXEventBuffer();
                }
                currentBuffer.processingInstruction(target, data);
            }
        }

        public void flush() throws SAXException {
            flushStartTag();
        }

        @Override
        public boolean wantsDocumentBoundaries() {
            return true;
        }
    }

    /**
     * Adapts SAXEventBuffer replay (ContentHandler) to OutputHandler,
     * preserving element structure instead of converting to text.
     *
     * <p>SAX sends startPrefixMapping before startElement, but OutputHandler
     * expects namespace() after startElement, so namespace declarations are
     * buffered and emitted after each startElement.
     */
    private static class SAXToOutputAdapter
            implements org.xml.sax.ContentHandler, org.xml.sax.ext.LexicalHandler {
        private final OutputHandler output;
        private final java.util.List<String[]> pendingNs = new java.util.ArrayList<>();

        SAXToOutputAdapter(OutputHandler output) {
            this.output = output;
        }

        public void setDocumentLocator(org.xml.sax.Locator locator) {
        }

        public void startDocument() throws SAXException {
        }

        public void endDocument() throws SAXException {
        }

        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            pendingNs.add(new String[]{prefix, uri});
        }

        public void endPrefixMapping(String prefix) throws SAXException {
        }

        public void startElement(String uri, String localName, String qName,
                                 org.xml.sax.Attributes atts) throws SAXException {
            output.startElement(uri, localName, qName);
            for (int i = 0; i < pendingNs.size(); i++) {
                String[] ns = pendingNs.get(i);
                output.namespace(ns[0], ns[1]);
            }
            pendingNs.clear();
            for (int i = 0; i < atts.getLength(); i++) {
                output.attribute(atts.getURI(i), atts.getLocalName(i),
                                 atts.getQName(i), atts.getValue(i));
            }
        }

        public void endElement(String uri, String localName, String qName) throws SAXException {
            output.endElement(uri, localName, qName);
        }

        public void characters(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            output.characters(new String(ch, start, length));
        }

        public void processingInstruction(String target, String data) throws SAXException {
            output.processingInstruction(target, data);
        }

        public void skippedEntity(String name) throws SAXException {
        }

        public void comment(char[] ch, int start, int length) throws SAXException {
            output.comment(new String(ch, start, length));
        }

        public void startDTD(String name, String publicId, String systemId) throws SAXException {
        }

        public void endDTD() throws SAXException {
        }

        public void startEntity(String name) throws SAXException {
        }

        public void endEntity(String name) throws SAXException {
        }

        public void startCDATA() throws SAXException {
        }

        public void endCDATA() throws SAXException {
        }
    }
}
