/*
 * XMLComparator.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.bluezoo.gonzalez.Parser;

/**
 * XML comparison utility for test assertions.
 *
 * <p>Compares two XML documents for semantic equality, handling:
 * <ul>
 *   <li>Attribute order independence</li>
 *   <li>Whitespace normalization options</li>
 *   <li>Namespace prefix independence</li>
 *   <li>XML declaration differences</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XMLComparator {

    /** Comparison options. */
    public static class Options {
        /** Ignore all whitespace-only text nodes. */
        public boolean ignoreWhitespace = true;
        
        /** Normalize whitespace in text content. */
        public boolean normalizeWhitespace = true;
        
        /** Ignore namespace prefixes (compare by URI). */
        public boolean ignoreNamespacePrefixes = true;
        
        /** Ignore comments. */
        public boolean ignoreComments = true;
        
        /** Ignore processing instructions. */
        public boolean ignoreProcessingInstructions = false;
        
        /** Default options for XSLT test comparison. */
        public static Options defaultOptions() {
            return new Options();
        }
    }

    /** Represents an XML node for comparison. */
    private static class Node {
        static final int ELEMENT = 1;
        static final int TEXT = 2;
        static final int COMMENT = 3;
        static final int PI = 4;

        final int type;
        final String namespaceURI;
        final String localName;
        final String text;
        final Map<String, String> attributes;
        final List<Node> children;

        Node(int type, String namespaceURI, String localName, String text) {
            this.type = type;
            this.namespaceURI = namespaceURI != null ? namespaceURI : "";
            this.localName = localName;
            this.text = text;
            this.attributes = new HashMap<>();
            this.children = new ArrayList<>();
        }

        static Node element(String namespaceURI, String localName) {
            return new Node(ELEMENT, namespaceURI, localName, null);
        }

        static Node text(String content) {
            return new Node(TEXT, null, null, content);
        }

        static Node comment(String content) {
            return new Node(COMMENT, null, null, content);
        }

        static Node pi(String target, String data) {
            return new Node(PI, null, target, data);
        }
    }

    /** Result of a comparison. */
    public static class Result {
        public final boolean equal;
        public final String difference;

        Result(boolean equal, String difference) {
            this.equal = equal;
            this.difference = difference;
        }

        static Result equal() {
            return new Result(true, null);
        }

        static Result different(String reason) {
            return new Result(false, reason);
        }
    }

    private final Options options;

    /**
     * Creates a comparator with default options.
     */
    public XMLComparator() {
        this(Options.defaultOptions());
    }

    /**
     * Creates a comparator with custom options.
     *
     * @param options the comparison options
     */
    public XMLComparator(Options options) {
        this.options = options;
    }

    /**
     * Compares two XML strings for semantic equality.
     *
     * @param expected the expected XML
     * @param actual the actual XML
     * @return the comparison result
     */
    public Result compare(String expected, String actual) {
        if (expected == null && actual == null) {
            return Result.equal();
        }
        if (expected == null || actual == null) {
            return Result.different("One value is null");
        }

        // Remove XML declarations for comparison
        expected = removeXmlDeclaration(expected).trim();
        actual = removeXmlDeclaration(actual).trim();

        // Quick string comparison first
        if (expected.equals(actual)) {
            return Result.equal();
        }

        // Fast-path: try quick normalization comparison before full parse
        String normExpected = quickNormalize(expected);
        String normActual = quickNormalize(actual);
        if (normExpected.equals(normActual)) {
            return Result.equal();
        }

        // Parse both documents for full semantic comparison
        Node expectedTree;
        Node actualTree;

        try {
            expectedTree = parse(expected);
        } catch (Exception e) {
            return Result.different("Failed to parse expected XML: " + e.getMessage());
        }

        try {
            actualTree = parse(actual);
        } catch (Exception e) {
            return Result.different("Failed to parse actual XML: " + e.getMessage());
        }

        // Compare trees
        return compareNodes(expectedTree, actualTree, "/");
    }

    /**
     * Quick whitespace normalization without full parsing.
     */
    private String quickNormalize(String xml) {
        StringBuilder sb = new StringBuilder(xml.length());
        boolean inTag = false;
        boolean lastWasSpace = false;
        
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            
            if (c == '<') {
                inTag = true;
                lastWasSpace = false;
                sb.append(c);
            } else if (c == '>') {
                inTag = false;
                lastWasSpace = false;
                sb.append(c);
            } else if (inTag) {
                // Inside tag - preserve single spaces only
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    if (!lastWasSpace) {
                        sb.append(' ');
                        lastWasSpace = true;
                    }
                } else {
                    lastWasSpace = false;
                    sb.append(c);
                }
            } else {
                // Outside tag - collapse whitespace
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    if (!lastWasSpace && sb.length() > 0 && sb.charAt(sb.length()-1) != '>') {
                        sb.append(' ');
                        lastWasSpace = true;
                    }
                } else {
                    lastWasSpace = false;
                    sb.append(c);
                }
            }
        }
        
        return sb.toString().trim();
    }

    /**
     * Removes XML declaration from a string.
     */
    private String removeXmlDeclaration(String xml) {
        int start = xml.indexOf("<?xml");
        if (start >= 0) {
            int end = xml.indexOf("?>", start);
            if (end >= 0) {
                return xml.substring(0, start) + xml.substring(end + 2);
            }
        }
        return xml;
    }

    /**
     * Parses XML into a node tree.
     */
    private Node parse(String xml) throws SAXException, IOException {
        Node root = Node.element("", "root");
        List<Node> stack = new ArrayList<>();
        stack.add(root);

        Parser parser = new Parser();
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        
        parser.setContentHandler(new DefaultHandler() {
            private StringBuilder textBuffer = new StringBuilder();

            private void flushText() {
                if (textBuffer.length() > 0) {
                    String text = textBuffer.toString();
                    textBuffer.setLength(0);

                    if (options.ignoreWhitespace && isWhitespaceOnly(text)) {
                        return;
                    }

                    if (options.normalizeWhitespace) {
                        text = normalizeWS(text);
                    }

                    if (!text.isEmpty()) {
                        Node parent = stack.get(stack.size() - 1);
                        parent.children.add(Node.text(text));
                    }
                }
            }

            @Override
            public void startElement(String uri, String localName, String qName,
                                     Attributes atts) {
                flushText();
                
                Node elem = Node.element(uri, localName);

                // Collect attributes (namespace-aware)
                for (int i = 0; i < atts.getLength(); i++) {
                    String attrUri = atts.getURI(i);
                    String attrLocal = atts.getLocalName(i);
                    String attrValue = atts.getValue(i);

                    // Skip xmlns declarations
                    String qn = atts.getQName(i);
                    if (qn.equals("xmlns") || qn.startsWith("xmlns:")) {
                        continue;
                    }

                    String key;
                    if (options.ignoreNamespacePrefixes) {
                        key = "{" + (attrUri != null ? attrUri : "") + "}" + attrLocal;
                    } else {
                        key = qn;
                    }
                    elem.attributes.put(key, attrValue);
                }

                Node parent = stack.get(stack.size() - 1);
                parent.children.add(elem);
                stack.add(elem);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                flushText();
                stack.remove(stack.size() - 1);
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                textBuffer.append(ch, start, length);
            }

            @Override
            public void processingInstruction(String target, String data) {
                flushText();
                if (!options.ignoreProcessingInstructions) {
                    Node parent = stack.get(stack.size() - 1);
                    parent.children.add(Node.pi(target, data));
                }
            }
        });

        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        InputSource source = new InputSource(new ByteArrayInputStream(bytes));
        parser.parse(source);

        // Return the single child of root (the actual document element)
        if (root.children.isEmpty()) {
            return root;
        }
        return root.children.get(0);
    }

    /**
     * Compares two node trees recursively.
     */
    private Result compareNodes(Node expected, Node actual, String path) {
        // Compare node types
        if (expected.type != actual.type) {
            return Result.different("Node type mismatch at " + path + 
                ": expected " + nodeTypeName(expected.type) + 
                ", got " + nodeTypeName(actual.type));
        }

        switch (expected.type) {
            case Node.ELEMENT:
                return compareElements(expected, actual, path);
            case Node.TEXT:
                return compareText(expected, actual, path);
            case Node.COMMENT:
                if (options.ignoreComments) {
                    return Result.equal();
                }
                return compareText(expected, actual, path);
            case Node.PI:
                return comparePI(expected, actual, path);
            default:
                return Result.equal();
        }
    }

    private Result compareElements(Node expected, Node actual, String path) {
        // Compare element name
        if (!expected.localName.equals(actual.localName)) {
            return Result.different("Element name mismatch at " + path + 
                ": expected <" + expected.localName + ">, got <" + actual.localName + ">");
        }

        // Compare namespace (if not ignoring prefixes)
        if (!options.ignoreNamespacePrefixes) {
            if (!expected.namespaceURI.equals(actual.namespaceURI)) {
                return Result.different("Namespace mismatch at " + path + 
                    ": expected {" + expected.namespaceURI + "}, got {" + actual.namespaceURI + "}");
            }
        }

        // Compare attributes
        Result attrResult = compareAttributes(expected.attributes, actual.attributes, path);
        if (!attrResult.equal) {
            return attrResult;
        }

        // Compare children
        return compareChildren(expected.children, actual.children, path + expected.localName + "/");
    }

    private Result compareAttributes(Map<String, String> expected, Map<String, String> actual, 
                                     String path) {
        // Check all expected attributes exist in actual
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String key = entry.getKey();
            String expectedVal = entry.getValue();
            String actualVal = actual.get(key);

            if (actualVal == null) {
                return Result.different("Missing attribute at " + path + ": " + key);
            }

            if (!expectedVal.equals(actualVal)) {
                return Result.different("Attribute value mismatch at " + path + 
                    "@" + key + ": expected \"" + expectedVal + "\", got \"" + actualVal + "\"");
            }
        }

        // Check no extra attributes in actual
        for (String key : actual.keySet()) {
            if (!expected.containsKey(key)) {
                return Result.different("Extra attribute at " + path + ": " + key);
            }
        }

        return Result.equal();
    }

    private Result compareChildren(List<Node> expected, List<Node> actual, String path) {
        // Filter out ignorable nodes
        List<Node> expFiltered = filterChildren(expected);
        List<Node> actFiltered = filterChildren(actual);

        if (expFiltered.size() != actFiltered.size()) {
            return Result.different("Child count mismatch at " + path + 
                ": expected " + expFiltered.size() + ", got " + actFiltered.size());
        }

        for (int i = 0; i < expFiltered.size(); i++) {
            Result r = compareNodes(expFiltered.get(i), actFiltered.get(i), 
                path + "[" + i + "]/");
            if (!r.equal) {
                return r;
            }
        }

        return Result.equal();
    }

    private List<Node> filterChildren(List<Node> children) {
        List<Node> result = new ArrayList<>();
        for (Node child : children) {
            if (child.type == Node.COMMENT && options.ignoreComments) {
                continue;
            }
            if (child.type == Node.PI && options.ignoreProcessingInstructions) {
                continue;
            }
            if (child.type == Node.TEXT && options.ignoreWhitespace && 
                isWhitespaceOnly(child.text)) {
                continue;
            }
            result.add(child);
        }
        return result;
    }

    private Result compareText(Node expected, Node actual, String path) {
        String expText = expected.text;
        String actText = actual.text;

        if (options.normalizeWhitespace) {
            expText = normalizeWS(expText);
            actText = normalizeWS(actText);
        }

        if (!expText.equals(actText)) {
            return Result.different("Text content mismatch at " + path + 
                ": expected \"" + abbreviate(expText, 50) + 
                "\", got \"" + abbreviate(actText, 50) + "\"");
        }

        return Result.equal();
    }

    private Result comparePI(Node expected, Node actual, String path) {
        if (!expected.localName.equals(actual.localName)) {
            return Result.different("PI target mismatch at " + path);
        }
        if (!safeEquals(expected.text, actual.text)) {
            return Result.different("PI data mismatch at " + path);
        }
        return Result.equal();
    }

    private boolean safeEquals(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private String nodeTypeName(int type) {
        switch (type) {
            case Node.ELEMENT: return "element";
            case Node.TEXT: return "text";
            case Node.COMMENT: return "comment";
            case Node.PI: return "processing-instruction";
            default: return "unknown";
        }
    }

    private boolean isWhitespaceOnly(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private String normalizeWS(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inWs = false;
        boolean hasContent = false;
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (hasContent && !inWs) {
                    inWs = true;
                }
            } else {
                if (inWs) {
                    sb.append(' ');
                    inWs = false;
                }
                sb.append(c);
                hasContent = true;
            }
        }
        return sb.toString();
    }

    private String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Convenience method for quick comparison.
     *
     * @param expected the expected XML
     * @param actual the actual XML
     * @return true if semantically equal
     */
    public static boolean xmlEquals(String expected, String actual) {
        return new XMLComparator().compare(expected, actual).equal;
    }
}
