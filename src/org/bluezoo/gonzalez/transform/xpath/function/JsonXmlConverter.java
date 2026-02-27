/*
 * JsonXmlConverter.java
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

package org.bluezoo.gonzalez.transform.xpath.function;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.json.JSONContentHandler;
import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONLocator;
import org.bluezoo.json.JSONParser;
import org.bluezoo.json.JSONWriter;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Converts between JSON and the W3C XML representation defined in the
 * XPath Functions 3.1 specification (Section 22.1).
 *
 * <p>The XML representation uses elements in the
 * {@code http://www.w3.org/2005/xpath-functions} namespace:
 * <ul>
 *   <li>{@code <j:map>} for JSON objects</li>
 *   <li>{@code <j:array>} for JSON arrays</li>
 *   <li>{@code <j:string>} for JSON strings</li>
 *   <li>{@code <j:number>} for JSON numbers</li>
 *   <li>{@code <j:boolean>} for JSON booleans</li>
 *   <li>{@code <j:null>} for JSON null</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class JsonXmlConverter {

    static final String FN_NS = "http://www.w3.org/2005/xpath-functions";
    private static final String J_PREFIX = "j";
    private static final String CDATA = "CDATA";

    private JsonXmlConverter() {
    }

    /**
     * Converts a JSON string to the W3C XML representation, returning
     * a document node (result tree fragment).
     *
     * @param json the JSON text to parse
     * @param escape if true, backslash-escaped characters are preserved
     * @param duplicates handling mode: "use-first", "use-last", or "reject"
     * @return a document node containing the XML representation
     * @throws SAXException wrapping FOJS0001 if the JSON is invalid
     */
    static XPathValue jsonToXml(String json, boolean escape, String duplicates) throws SAXException {
        SAXEventBuffer buffer = new SAXEventBuffer();
        try {
            buffer.startDocument();
            buffer.startPrefixMapping(J_PREFIX, FN_NS);

            JsonToSaxHandler handler = new JsonToSaxHandler(buffer, duplicates);
            JSONParser parser = new JSONParser();
            parser.setContentHandler(handler);

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer data = ByteBuffer.wrap(bytes);
            parser.receive(data);
            parser.close();

            buffer.endPrefixMapping(J_PREFIX);
            buffer.endDocument();
        } catch (JSONException e) {
            throw new SAXException("FOJS0001: Invalid JSON: " + e.getMessage(), e);
        }

        return new XPathResultTreeFragment(buffer);
    }

    /**
     * Converts an XML node in the W3C JSON representation back to a JSON string.
     *
     * @param node the document or element node to convert
     * @return the JSON string
     * @throws SAXException wrapping FOJS0006 if the XML is not valid
     */
    static String xmlToJson(XPathNode node) throws SAXException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);
        try {
            XPathNode target = node;
            if (node.getNodeType() == NodeType.ROOT) {
                Iterator<XPathNode> children = node.getChildren();
                if (children == null || !children.hasNext()) {
                    throw new SAXException("FOJS0006: Document node has no element child");
                }
                target = findFirstElement(children);
                if (target == null) {
                    throw new SAXException("FOJS0006: Document node has no element child");
                }
            }
            writeJsonValue(target, writer);
            writer.close();
        } catch (IOException e) {
            throw new SAXException("FOJS0006: Error writing JSON: " + e.getMessage(), e);
        }
        return out.toString();
    }

    private static void writeJsonValue(XPathNode element, JSONWriter writer) throws IOException, SAXException {
        String localName = element.getLocalName();
        String nsUri = element.getNamespaceURI();
        if (!FN_NS.equals(nsUri)) {
            throw new SAXException("FOJS0006: Element is not in the XPath functions namespace: " + localName);
        }

        if ("map".equals(localName)) {
            writeJsonMap(element, writer);
        } else if ("array".equals(localName)) {
            writeJsonArray(element, writer);
        } else if ("string".equals(localName)) {
            writer.writeString(element.getStringValue());
        } else if ("number".equals(localName)) {
            String text = element.getStringValue().trim();
            writeJsonNumber(text, writer);
        } else if ("boolean".equals(localName)) {
            String text = element.getStringValue().trim();
            boolean val = "true".equals(text) || "1".equals(text);
            writer.writeBoolean(val);
        } else if ("null".equals(localName)) {
            writer.writeNull();
        } else {
            throw new SAXException("FOJS0006: Unknown element in JSON namespace: " + localName);
        }
    }

    private static void writeJsonMap(XPathNode mapElement, JSONWriter writer) throws IOException, SAXException {
        writer.writeStartObject();
        Iterator<XPathNode> children = mapElement.getChildren();
        if (children != null) {
            while (children.hasNext()) {
                XPathNode child = children.next();
                if (child.getNodeType() != NodeType.ELEMENT) {
                    continue;
                }
                String key = getAttributeValue(child, "key");
                if (key == null) {
                    throw new SAXException("FOJS0006: Map entry element missing 'key' attribute");
                }
                writer.writeKey(key);
                writeJsonValue(child, writer);
            }
        }
        writer.writeEndObject();
    }

    private static void writeJsonArray(XPathNode arrayElement, JSONWriter writer) throws IOException, SAXException {
        writer.writeStartArray();
        Iterator<XPathNode> children = arrayElement.getChildren();
        if (children != null) {
            while (children.hasNext()) {
                XPathNode child = children.next();
                if (child.getNodeType() != NodeType.ELEMENT) {
                    continue;
                }
                writeJsonValue(child, writer);
            }
        }
        writer.writeEndArray();
    }

    private static void writeJsonNumber(String text, JSONWriter writer) throws IOException, SAXException {
        int dotIndex = text.indexOf('.');
        int eIndex = text.indexOf('e');
        if (eIndex < 0) {
            eIndex = text.indexOf('E');
        }
        boolean isFloat = dotIndex >= 0 || eIndex >= 0;
        try {
            if (isFloat) {
                double d = Double.parseDouble(text);
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new SAXException("FOJS0006: Invalid JSON number: " + text);
                }
                writer.writeNumber(d);
            } else {
                long val = Long.parseLong(text);
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    writer.writeNumber((int) val);
                } else {
                    writer.writeNumber(val);
                }
            }
        } catch (NumberFormatException e) {
            throw new SAXException("FOJS0006: Invalid number value: " + text);
        }
    }

    private static String getAttributeValue(XPathNode element, String attrName) {
        Iterator<XPathNode> attrs = element.getAttributes();
        if (attrs == null) {
            return null;
        }
        while (attrs.hasNext()) {
            XPathNode attr = attrs.next();
            String localName = attr.getLocalName();
            if (attrName.equals(localName)) {
                return attr.getStringValue();
            }
        }
        return null;
    }

    private static XPathNode findFirstElement(Iterator<XPathNode> children) {
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (child.getNodeType() == NodeType.ELEMENT) {
                return child;
            }
        }
        return null;
    }

    /**
     * JSONContentHandler that translates JSON parsing events into SAX events
     * on a ContentHandler, using the W3C XML representation for JSON.
     */
    private static class JsonToSaxHandler implements JSONContentHandler {

        private final SAXEventBuffer sax;
        private final String duplicates;
        private final Deque<Boolean> inObject = new ArrayDeque<Boolean>();
        private final Deque<Set<String>> seenKeys = new ArrayDeque<Set<String>>();
        private String pendingKey;
        private int skipDepth;

        JsonToSaxHandler(SAXEventBuffer sax, String duplicates) {
            this.sax = sax;
            this.duplicates = duplicates;
        }

        private boolean isSkipping() {
            return skipDepth > 0;
        }

        @Override
        public void setLocator(JSONLocator locator) {
        }

        @Override
        public void startObject() throws JSONException {
            if (isSkipping()) {
                skipDepth++;
                return;
            }
            try {
                AttributesImpl attrs = makeAttrs();
                sax.startElement(FN_NS, "map", "j:map", attrs);
                inObject.push(Boolean.TRUE);
                seenKeys.push(new HashSet<String>());
                pendingKey = null;
            } catch (SAXException e) {
                throw new JSONException(e.getMessage());
            }
        }

        @Override
        public void endObject() throws JSONException {
            if (isSkipping()) {
                skipDepth--;
                return;
            }
            try {
                sax.endElement(FN_NS, "map", "j:map");
                inObject.pop();
                seenKeys.pop();
            } catch (SAXException e) {
                throw new JSONException(e.getMessage());
            }
        }

        @Override
        public void startArray() throws JSONException {
            if (isSkipping()) {
                skipDepth++;
                return;
            }
            try {
                AttributesImpl attrs = makeAttrs();
                sax.startElement(FN_NS, "array", "j:array", attrs);
                inObject.push(Boolean.FALSE);
                pendingKey = null;
            } catch (SAXException e) {
                throw new JSONException(e.getMessage());
            }
        }

        @Override
        public void endArray() throws JSONException {
            if (isSkipping()) {
                skipDepth--;
                return;
            }
            try {
                sax.endElement(FN_NS, "array", "j:array");
                inObject.pop();
            } catch (SAXException e) {
                throw new JSONException(e.getMessage());
            }
        }

        @Override
        public void key(String key) throws JSONException {
            if (isSkipping()) {
                return;
            }
            if (!"retain".equals(duplicates) && !seenKeys.isEmpty()) {
                Set<String> currentKeys = seenKeys.peek();
                if (currentKeys.contains(key)) {
                    if ("reject".equals(duplicates)) {
                        throw new JSONException("FOJS0003: Duplicate key: " + key);
                    }
                    // use-first: skip this key's value
                    skipDepth = 1;
                    return;
                }
                currentKeys.add(key);
            }
            pendingKey = key;
        }

        @Override
        public void stringValue(String value) throws JSONException {
            if (isSkipping()) {
                skipDepth--;
                return;
            }
            emitSimpleElement("string", value);
        }

        @Override
        public void numberValue(Number number) throws JSONException {
            if (isSkipping()) {
                skipDepth--;
                return;
            }
            String text = formatNumber(number);
            emitSimpleElement("number", text);
        }

        @Override
        public void booleanValue(boolean value) throws JSONException {
            if (isSkipping()) {
                skipDepth--;
                return;
            }
            String text = value ? "true" : "false";
            emitSimpleElement("boolean", text);
        }

        @Override
        public void nullValue() throws JSONException {
            if (isSkipping()) {
                skipDepth--;
                return;
            }
            try {
                AttributesImpl attrs = makeAttrs();
                sax.startElement(FN_NS, "null", "j:null", attrs);
                sax.endElement(FN_NS, "null", "j:null");
                pendingKey = null;
            } catch (SAXException e) {
                throw new JSONException(e.getMessage());
            }
        }

        @Override
        public void whitespace(String whitespace) throws JSONException {
        }

        private void emitSimpleElement(String localName, String text) throws JSONException {
            try {
                String qName = "j:" + localName;
                AttributesImpl attrs = makeAttrs();
                sax.startElement(FN_NS, localName, qName, attrs);
                char[] chars = text.toCharArray();
                sax.characters(chars, 0, chars.length);
                sax.endElement(FN_NS, localName, qName);
                pendingKey = null;
            } catch (SAXException e) {
                throw new JSONException(e.getMessage());
            }
        }

        private AttributesImpl makeAttrs() {
            AttributesImpl attrs = new AttributesImpl();
            if (pendingKey != null) {
                attrs.addAttribute("", "key", "key", CDATA, pendingKey);
            }
            return attrs;
        }

        private String formatNumber(Number number) {
            if (number instanceof Integer || number instanceof Long) {
                return number.toString();
            }
            double d = number.doubleValue();
            long longVal = (long) d;
            if (d == longVal && !Double.isInfinite(d)) {
                return Long.toString(longVal);
            }
            return number.toString();
        }
    }
}
