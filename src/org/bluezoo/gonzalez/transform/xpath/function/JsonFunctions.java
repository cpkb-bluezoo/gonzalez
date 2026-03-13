/*
 * JsonFunctions.java
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.LinkedHashMap;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.1 JSON function implementations: json-to-xml, xml-to-json, parse-json.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class JsonFunctions {

    private JsonFunctions() {
    }

    static Function jsonToXml() {
        return new JsonToXmlFunction();
    }

    static Function xmlToJson() {
        return new XmlToJsonFunction();
    }

    static Function parseJson() {
        return new ParseJsonFunction();
    }

    /**
     * json-to-xml($json-text) - Parses JSON text and returns an XML representation
     * using the W3C XPath Functions namespace.
     */
    private static class JsonToXmlFunction implements Function {
        @Override
        public String getName() { return "json-to-xml"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String json = args.get(0).asString();
            boolean escape = false;
            String duplicates = "use-first";
            if (args.size() > 1) {
                XPathValue opts = args.get(1);
                if (opts instanceof XPathMap) {
                    XPathMap map = (XPathMap) opts;
                    validateJsonOptions(map);
                    boolean validate = extractBooleanOption(map, "validate");
                    if (validate) {
                        throw new XPathException("FOJS0004: json-to-xml validate " +
                            "option requires a schema-aware processor");
                    }
                    escape = extractBooleanOption(map, "escape");
                    XPathValue dupVal = map.get("duplicates");
                    if (dupVal != null) {
                        duplicates = dupVal.asString();
                    }
                }
            }
            try {
                return JsonXmlConverter.jsonToXml(json, escape, duplicates);
            } catch (org.xml.sax.SAXException e) {
                throw new XPathException(e.getMessage());
            }
        }

        private void validateJsonOptions(XPathMap map) throws XPathException {
            for (Map.Entry<String, XPathValue> entry : map.entries()) {
                String key = entry.getKey();
                XPathValue value = entry.getValue();
                switch (key) {
                    case "liberal":
                    case "escape":
                    case "validate":
                        validateBooleanOption(key, value);
                        break;
                    case "duplicates":
                        validateStringOption(key, value);
                        break;
                    case "fallback":
                        throw new XPathException("FOJS0005: option 'fallback' requires a function item");
                    default:
                        break;
                }
            }
        }

        private void validateBooleanOption(String key, XPathValue value) throws XPathException {
            if (value instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) value;
                if (seq.isEmpty()) {
                    throw new XPathException("FOJS0005: option '" + key + "' must be a boolean, got empty sequence");
                }
                if (seq.size() > 1) {
                    throw new XPathException("FOJS0005: option '" + key + "' must be a single boolean");
                }
            }
            if (value instanceof XPathString) {
                throw new XPathException("FOJS0005: option '" + key + "' must be a boolean, got string");
            }
        }

        private void validateStringOption(String key, XPathValue value) throws XPathException {
            if (value instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) value;
                if (seq.isEmpty()) {
                    throw new XPathException("FOJS0005: option '" + key + "' must be a string, got empty sequence");
                }
            }
            if ("duplicates".equals(key)) {
                String s = value.asString();
                if (!"use-first".equals(s) && !"reject".equals(s) && !"retain".equals(s)) {
                    throw new XPathException("FOJS0005: invalid value for 'duplicates': " + s);
                }
            }
        }

        private boolean extractBooleanOption(XPathMap map, String key) {
            XPathValue value = map.get(key);
            if (value != null) {
                return value.asBoolean();
            }
            return false;
        }
    }

    /**
     * xml-to-json($node) - Converts XML in the W3C XPath Functions namespace
     * representation back to a JSON string.
     */
    private static class XmlToJsonFunction implements Function {
        @Override
        public String getName() { return "xml-to-json"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            XPathNode node = null;
            if (arg instanceof XPathNode) {
                node = (XPathNode) arg;
            } else if (arg instanceof XPathNodeSet) {
                XPathNodeSet nodeSet = (XPathNodeSet) arg;
                List<XPathNode> nodes = nodeSet.getNodes();
                if (nodes != null && nodes.size() > 1) {
                    throw new XPathException("XPTY0004: xml-to-json() expects a " +
                        "single node, but " + nodes.size() + " nodes were supplied");
                }
                if (nodes != null && !nodes.isEmpty()) {
                    node = nodes.get(0);
                }
            } else if (arg instanceof XPathSequence) {
                List<XPathNode> collected = new ArrayList<XPathNode>();
                Iterator<XPathValue> it = arg.sequenceIterator();
                while (it.hasNext()) {
                    XPathValue item = it.next();
                    if (item instanceof XPathNode) {
                        collected.add((XPathNode) item);
                    } else if (item.isNodeSet()) {
                        for (XPathNode n : item.asNodeSet()) {
                            collected.add(n);
                        }
                    }
                }
                if (collected.size() > 1) {
                    throw new XPathException("XPTY0004: xml-to-json() expects a " +
                        "single node, but " + collected.size() + " nodes were supplied");
                }
                if (!collected.isEmpty()) {
                    node = collected.get(0);
                }
            } else if (arg instanceof XPathResultTreeFragment) {
                XPathResultTreeFragment rtf = (XPathResultTreeFragment) arg;
                XPathNodeSet nodeSet = rtf.asNodeSet();
                List<XPathNode> nodes = nodeSet.getNodes();
                if (nodes != null && !nodes.isEmpty()) {
                    node = nodes.get(0);
                }
            }
            if (node == null) {
                throw new XPathException("FOJS0006: xml-to-json requires a node argument");
            }
            if (args.size() > 1) {
                XPathValue opts = args.get(1);
                if (opts instanceof XPathMap) {
                    XPathMap map = (XPathMap) opts;
                    validateXmlToJsonOptions(map);
                }
            }
            try {
                String result = JsonXmlConverter.xmlToJson(node);
                return XPathString.of(result);
            } catch (org.xml.sax.SAXException e) {
                throw new XPathException(e.getMessage());
            }
        }

        private void validateXmlToJsonOptions(XPathMap map) throws XPathException {
            for (Map.Entry<String, XPathValue> entry : map.entries()) {
                String key = entry.getKey();
                XPathValue value = entry.getValue();
                if ("indent".equals(key)) {
                    if (value instanceof XPathString) {
                        throw new XPathException("XPTY0004: Option 'indent' must be a boolean, got string");
                    }
                    if (value instanceof XPathSequence) {
                        XPathSequence seq = (XPathSequence) value;
                        if (seq.isEmpty()) {
                            throw new XPathException("XPTY0004: Option 'indent' must be a boolean, got empty sequence");
                        }
                        if (seq.size() > 1) {
                            throw new XPathException("XPTY0004: Option 'indent' must be a single boolean");
                        }
                    }
                    if (!(value instanceof XPathBoolean)) {
                        throw new XPathException("XPTY0004: Option 'indent' must be a boolean");
                    }
                }
            }
        }
    }

    /**
     * parse-json($json-text) - Parses JSON text and returns an XDM map/array.
     */
    private static class ParseJsonFunction implements Function {
        @Override
        public String getName() { return "parse-json"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String json = args.get(0).asString();
            if (json == null) {
                return XPathSequence.EMPTY;
            }
            JsonParser parser = new JsonParser(json);
            return parser.parseValue();
        }
    }

    /**
     * Recursive-descent JSON parser producing XDM maps, arrays, and atomic values.
     */
    private static class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        XPathValue parseValue() throws XPathException {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new XPathException("FOJS0001: Unexpected end of JSON input");
            }
            char c = input.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return XPathString.of(parseString());
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            if (c == 'n') {
                return parseNull();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw new XPathException("FOJS0001: Unexpected character '" + c +
                "' at position " + pos);
        }

        private XPathMap parseObject() throws XPathException {
            pos++; // consume '{'
            skipWhitespace();
            Map<String, XPathValue> entries = new LinkedHashMap<String, XPathValue>();
            if (pos < input.length() && input.charAt(pos) == '}') {
                pos++;
                return new XPathMap(entries);
            }
            while (true) {
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != '"') {
                    throw new XPathException("FOJS0001: Expected string key in object");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                XPathValue value = parseValue();
                entries.put(key, value);
                skipWhitespace();
                if (pos >= input.length()) {
                    throw new XPathException("FOJS0001: Unterminated object");
                }
                char c = input.charAt(pos);
                if (c == '}') {
                    pos++;
                    break;
                }
                if (c == ',') {
                    pos++;
                    continue;
                }
                throw new XPathException("FOJS0001: Expected ',' or '}' in object");
            }
            return new XPathMap(entries);
        }

        private XPathArray parseArray() throws XPathException {
            pos++; // consume '['
            skipWhitespace();
            List<XPathValue> members = new ArrayList<XPathValue>();
            if (pos < input.length() && input.charAt(pos) == ']') {
                pos++;
                return new XPathArray(members);
            }
            while (true) {
                members.add(parseValue());
                skipWhitespace();
                if (pos >= input.length()) {
                    throw new XPathException("FOJS0001: Unterminated array");
                }
                char c = input.charAt(pos);
                if (c == ']') {
                    pos++;
                    break;
                }
                if (c == ',') {
                    pos++;
                    continue;
                }
                throw new XPathException("FOJS0001: Expected ',' or ']' in array");
            }
            return new XPathArray(members);
        }

        private String parseString() throws XPathException {
            pos++; // consume opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= input.length()) {
                        throw new XPathException("FOJS0001: Unterminated string escape");
                    }
                    char esc = input.charAt(pos);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 >= input.length()) {
                                throw new XPathException("FOJS0001: Invalid unicode escape");
                            }
                            String hex = input.substring(pos + 1, pos + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            throw new XPathException("FOJS0001: Invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
                pos++;
            }
            throw new XPathException("FOJS0001: Unterminated string");
        }

        private XPathValue parseBoolean() throws XPathException {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return XPathBoolean.TRUE;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return XPathBoolean.FALSE;
            }
            throw new XPathException("FOJS0001: Invalid JSON token at position " + pos);
        }

        private XPathValue parseNull() throws XPathException {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return XPathSequence.EMPTY;
            }
            throw new XPathException("FOJS0001: Invalid JSON token at position " + pos);
        }

        private XPathValue parseNumber() throws XPathException {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
            }
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                pos++;
            }
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
            }
            String numStr = input.substring(start, pos);
            double val = Double.parseDouble(numStr);
            return new XPathNumber(val);
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    break;
                }
                pos++;
            }
        }

        private void expect(char expected) throws XPathException {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new XPathException("FOJS0001: Expected '" + expected +
                    "' at position " + pos);
            }
            pos++;
        }
    }
}
