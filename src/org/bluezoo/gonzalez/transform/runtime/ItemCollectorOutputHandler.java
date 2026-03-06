/*
 * ItemCollectorOutputHandler.java
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

import java.util.ArrayList;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * An OutputHandler that collects output items for use in constructing
 * comment, processing-instruction, and value-of content with separator support.
 *
 * <p>Per the XSLT 3.0 spec for simple content constructors:
 * <ul>
 *   <li>Adjacent text nodes are merged into a single item (no separator between them)</li>
 *   <li>Zero-length text nodes are discarded</li>
 *   <li>Atomic values are converted to strings and become separate items</li>
 *   <li>Node items are atomized: element/document/PI/comment nodes contribute their
 *       string-value as a single item each</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ItemCollectorOutputHandler implements OutputHandler {

    /**
     * Collected string items (non-empty, with adjacent text merged).
     */
    private final List<String> items = new ArrayList<String>();

    /**
     * Buffer for accumulating text from the current "item".
     * Flushed when an item boundary is detected (atomicValue, itemBoundary, endElement-at-root).
     */
    private final StringBuilder textBuffer = new StringBuilder();

    /**
     * Whether we're currently inside an element (depth > 0).
     * If so, characters go into the element's string-value accumulation.
     */
    private int elementDepth = 0;

    /**
     * Whether the last character output was from a text node.
     * Used to detect adjacent text nodes that should be merged.
     */
    private boolean lastWasText = false;

    /**
     * Whether the last item was atomic (used to track item type for boundary decisions).
     */
    private boolean atomicValuePending = false;

    private boolean inAttributeContent = false;

    /**
     * Returns the collected content joined with the given separator.
     * Zero-length items are excluded.
     *
     * @param separator the separator to place between items (null means no separator)
     * @return the joined string
     */
    public String getContentAsString(String separator) {
        flushCurrentItem();
        String sep = (separator != null) ? separator : "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(sep);
            }
            sb.append(item);
            first = false;
        }
        return sb.toString();
    }

    /**
     * Flushes the text buffer as a completed item (if non-empty).
     */
    private void flushCurrentItem() {
        if (textBuffer.length() > 0) {
            items.add(textBuffer.toString());
            textBuffer.setLength(0);
        }
        lastWasText = false;
    }

    @Override
    public void startDocument() throws SAXException {
    }

    @Override
    public void endDocument() throws SAXException {
        flushCurrentItem();
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qName)
            throws SAXException {
        elementDepth++;
        // Starting an element: flush any pending text as its own item first
        // (unless we're already inside an element, in which case the element
        // is a child and its text content contributes to the parent's string-value)
        if (elementDepth == 1) {
            flushCurrentItem();
        }
    }

    @Override
    public void endElement(String namespaceURI, String localName, String qName)
            throws SAXException {
        elementDepth--;
        if (elementDepth == 0) {
            // Element ended at root level: the accumulated text is the element's
            // string-value (atomized). Finalize it as a separate item.
            flushCurrentItem();
        }
    }

    @Override
    public void attribute(String namespaceURI, String localName, String qName, String value)
            throws SAXException {
        // Atomize attribute node: its string value (the attribute value) becomes an item
        if (elementDepth == 0 && value != null && !value.isEmpty()) {
            flushCurrentItem();
            items.add(value);
            lastWasText = false;
            atomicValuePending = false;
        }
    }

    @Override
    public void namespace(String prefix, String uri) throws SAXException {
    }

    @Override
    public void characters(String text) throws SAXException {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Accumulate text: either inside an element (its string-value) or as a text node
        textBuffer.append(text);
        lastWasText = true;
        atomicValuePending = false;
    }

    @Override
    public void charactersRaw(String text) throws SAXException {
        characters(text);
    }

    @Override
    public void comment(String text) throws SAXException {
        // Comment nodes contribute their string-value as a separate item
        if (elementDepth == 0) {
            flushCurrentItem();
            if (text != null && !text.isEmpty()) {
                items.add(text);
            }
            lastWasText = false;
            atomicValuePending = false;
        }
        // Inside an element, comment text does NOT contribute to element string-value
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        // PI nodes contribute their string-value (data) as a separate item
        if (elementDepth == 0) {
            flushCurrentItem();
            if (data != null && !data.isEmpty()) {
                items.add(data);
            }
            lastWasText = false;
            atomicValuePending = false;
        }
        // Inside an element, PI data does NOT contribute to element string-value
    }

    @Override
    public void flush() throws SAXException {
    }

    @Override
    public void setElementType(String namespaceURI, String localName) throws SAXException {
    }

    @Override
    public void setAttributeType(String namespaceURI, String localName) throws SAXException {
    }

    @Override
    public boolean isAtomicValuePending() {
        return atomicValuePending;
    }

    @Override
    public void setAtomicValuePending(boolean pending) {
        atomicValuePending = pending;
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
    public void atomicValue(XPathValue value) throws SAXException {
        if (value == null) {
            return;
        }
        String s = value.asString();
        // Flush any accumulated text before adding this atomic item
        flushCurrentItem();
        if (s != null && !s.isEmpty()) {
            items.add(s);
        }
        lastWasText = false;
        atomicValuePending = true;
    }

    @Override
    public void itemBoundary() throws SAXException {
        // Item boundaries do NOT flush accumulated text, because adjacent text nodes
        // from consecutive instructions (e.g. xsl:text, xsl:value-of) must be merged.
        // Text is only finalized when a non-text item arrives (atomicValue, element, etc.).
        // The atomicValuePending flag helps track when we need to switch item types.
        atomicValuePending = false;
    }
}
