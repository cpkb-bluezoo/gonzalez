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
                XPathNode second = findFirstElement(children);
                if (second != null) {
                    throw new SAXException("FOJS0006: Document node has more than one element child");
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
            validateAttributes(element, localName);
            writeJsonMap(element, writer);
        } else if ("array".equals(localName)) {
            validateAttributes(element, localName);
            writeJsonArray(element, writer);
        } else if ("string".equals(localName)) {
            validateAttributes(element, localName);
            validateNoChildElements(element);
            boolean escaped = isEscapedTrue(element, "escaped");
            String text = element.getStringValue();
            if (escaped) {
                validateEscapedContent(text);
            }
            writer.writeString(text);
        } else if ("number".equals(localName)) {
            validateAttributes(element, localName);
            String text = element.getStringValue().trim();
            writeJsonNumber(text, writer);
        } else if ("boolean".equals(localName)) {
            validateAttributes(element, localName);
            String text = collapseWhitespace(element.getStringValue());
            if (!"true".equals(text) && !"false".equals(text)
                    && !"1".equals(text) && !"0".equals(text)) {
                throw new SAXException("FOJS0006: Invalid boolean value: '" + text + "'");
            }
            boolean val = "true".equals(text) || "1".equals(text);
            writer.writeBoolean(val);
        } else if ("null".equals(localName)) {
            validateAttributes(element, localName);
            String text = element.getStringValue().trim();
            if (text.length() > 0) {
                throw new SAXException("FOJS0006: null element must not have text content");
            }
            writer.writeNull();
        } else {
            throw new SAXException("FOJS0006: Unknown element in JSON namespace: " + localName);
        }
    }

    private static void writeJsonMap(XPathNode mapElement, JSONWriter writer) throws IOException, SAXException {
        writer.writeStartObject();
        Set<String> seenKeys = new HashSet<String>();
        Iterator<XPathNode> children = mapElement.getChildren();
        if (children != null) {
            while (children.hasNext()) {
                XPathNode child = children.next();
                NodeType childType = child.getNodeType();
                if (childType == NodeType.TEXT) {
                    String text = child.getStringValue();
                    if (!isWhitespace(text)) {
                        throw new SAXException("FOJS0006: Non-whitespace text content in map element");
                    }
                    continue;
                }
                if (childType != NodeType.ELEMENT) {
                    continue;
                }
                String key = getAttributeValue(child, "key");
                if (key == null) {
                    throw new SAXException("FOJS0006: Map entry element missing 'key' attribute");
                }
                String compareKey = key;
                boolean escapedKey = isEscapedTrue(child, "escaped-key");
                if (escapedKey) {
                    validateEscapedContent(key);
                    compareKey = unescapeJsonString(key);
                }
                if (!seenKeys.add(compareKey)) {
                    throw new SAXException("FOJS0006: Duplicate key in map: '" + key + "'");
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
                NodeType childType = child.getNodeType();
                if (childType == NodeType.TEXT) {
                    String text = child.getStringValue();
                    if (!isWhitespace(text)) {
                        throw new SAXException("FOJS0006: Non-whitespace text content in array element");
                    }
                    continue;
                }
                if (childType != NodeType.ELEMENT) {
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
     * Validates attributes on a JSON XML element.
     * Attributes in the fn namespace are always rejected.
     * Attributes in other non-empty namespaces are ignored (xml:space, xsi:type, etc.).
     * No-namespace attributes must be from the allowed set for the element type.
     */
    private static void validateAttributes(XPathNode element, String elementLocalName)
            throws SAXException {
        Iterator<XPathNode> attrs = element.getAttributes();
        if (attrs == null) {
            return;
        }
        while (attrs.hasNext()) {
            XPathNode attr = attrs.next();
            String attrNs = attr.getNamespaceURI();
            if (attrNs == null) {
                attrNs = "";
            }
            if (FN_NS.equals(attrNs)) {
                throw new SAXException("FOJS0006: Attribute in XPath functions namespace not allowed: "
                        + attr.getLocalName());
            }
            if (attrNs.length() > 0) {
                continue;
            }
            String attrName = attr.getLocalName();
            if ("key".equals(attrName) || "escaped-key".equals(attrName)) {
                if ("escaped-key".equals(attrName)) {
                    validateBooleanAttrValue(attr.getStringValue(), attrName);
                }
                continue;
            }
            if ("escaped".equals(attrName)) {
                if (!"string".equals(elementLocalName)) {
                    throw new SAXException("FOJS0006: Attribute 'escaped' is only allowed on string elements");
                }
                validateBooleanAttrValue(attr.getStringValue(), attrName);
                continue;
            }
            throw new SAXException("FOJS0006: Unrecognized attribute '" + attrName
                    + "' on " + elementLocalName + " element");
        }
    }

    /**
     * Validates that an attribute value is a valid xs:boolean
     * after whitespace collapsing: "true", "false", "1", "0".
     */
    private static void validateBooleanAttrValue(String value, String attrName) throws SAXException {
        String collapsed = collapseWhitespace(value);
        if (!"true".equals(collapsed) && !"false".equals(collapsed)
                && !"1".equals(collapsed) && !"0".equals(collapsed)) {
            throw new SAXException("FOJS0006: Invalid value for '" + attrName
                    + "' attribute: '" + value + "'");
        }
    }

    /**
     * Returns true if the given escaped/escaped-key attribute is present and evaluates to true.
     */
    private static boolean isEscapedTrue(XPathNode element, String attrName) {
        String value = getAttributeValue(element, attrName);
        if (value == null) {
            return false;
        }
        String collapsed = collapseWhitespace(value);
        return "true".equals(collapsed) || "1".equals(collapsed);
    }

    /**
     * Validates that an escaped string contains only valid JSON escape sequences.
     * Raises FOJS0007 if an invalid escape is found.
     */
    private static void validateEscapedContent(String content) throws SAXException {
        int len = content.length();
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            if (c != '\\') {
                continue;
            }
            i++;
            if (i >= len) {
                throw new SAXException("FOJS0007: Incomplete escape sequence at end of string");
            }
            char next = content.charAt(i);
            switch (next) {
                case '"':
                case '\\':
                case '/':
                case 'b':
                case 'f':
                case 'n':
                case 'r':
                case 't':
                    break;
                case 'u':
                    if (i + 4 >= len) {
                        throw new SAXException("FOJS0007: Incomplete \\u escape sequence");
                    }
                    for (int j = 1; j <= 4; j++) {
                        char h = content.charAt(i + j);
                        if (!isHexDigit(h)) {
                            throw new SAXException("FOJS0007: Invalid hex digit in \\u escape: " + h);
                        }
                    }
                    i += 4;
                    break;
                default:
                    throw new SAXException("FOJS0007: Invalid escape sequence: \\" + next);
            }
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Validates that a string/number/boolean element has no child elements.
     */
    private static void validateNoChildElements(XPathNode element) throws SAXException {
        Iterator<XPathNode> children = element.getChildren();
        if (children == null) {
            return;
        }
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (child.getNodeType() == NodeType.ELEMENT) {
                throw new SAXException("FOJS0006: Child elements not allowed in "
                        + element.getLocalName() + " element");
            }
        }
    }

    /**
     * Unescapes a JSON escaped string value for comparison purposes.
     */
    private static String unescapeJsonString(String escaped) {
        StringBuilder sb = new StringBuilder();
        int len = escaped.length();
        for (int i = 0; i < len; i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = escaped.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    case 'b': sb.append('\b'); i++; break;
                    case 'f': sb.append('\f'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < len) {
                            String hex = escaped.substring(i + 2, i + 6);
                            int codePoint = Integer.parseInt(hex, 16);
                            sb.append((char) codePoint);
                            i += 5;
                        } else {
                            sb.append(c);
                        }
                        break;
                    default:
                        sb.append(c);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private static String collapseWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            char c = value.charAt(start);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            start++;
        }
        while (end > start) {
            char c = value.charAt(end - 1);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            end--;
        }
        return value.substring(start, end);
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
                if (skipDepth == 1) {
                    skipDepth = 0;
                }
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
                if (skipDepth == 1) {
                    skipDepth = 0;
                }
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
                if (skipDepth == 1) {
                    skipDepth = 0;
                }
                return;
            }
            emitSimpleElement("string", value);
        }

        @Override
        public void numberValue(Number number) throws JSONException {
            if (isSkipping()) {
                if (skipDepth == 1) {
                    skipDepth = 0;
                }
                return;
            }
            String text = formatNumber(number);
            emitSimpleElement("number", text);
        }

        @Override
        public void booleanValue(boolean value) throws JSONException {
            if (isSkipping()) {
                if (skipDepth == 1) {
                    skipDepth = 0;
                }
                return;
            }
            String text = value ? "true" : "false";
            emitSimpleElement("boolean", text);
        }

        @Override
        public void nullValue() throws JSONException {
            if (isSkipping()) {
                if (skipDepth == 1) {
                    skipDepth = 0;
                }
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
