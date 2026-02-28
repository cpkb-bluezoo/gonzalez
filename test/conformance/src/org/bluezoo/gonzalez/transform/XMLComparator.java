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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * XML comparison utility for test assertions.
 *
 * <p>Compares two XML documents for semantic equality, handling:
 * <ul>
 *   <li>XML declaration differences</li>
 *   <li>Whitespace normalization</li>
 *   <li>Attribute order independence</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XMLComparator {

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

        // Remove XML declarations
        expected = removeXmlDeclaration(expected).trim();
        actual = removeXmlDeclaration(actual).trim();

        // Quick string comparison first
        if (expected.equals(actual)) {
            return Result.equal();
        }

        // Normalize character references so &#xNN; and &#DDD; are equivalent
        expected = normalizeCharacterReferences(expected);
        actual = normalizeCharacterReferences(actual);

        if (expected.equals(actual)) {
            return Result.equal();
        }

        // Normalize and compare
        String normExpected = normalize(expected);
        String normActual = normalize(actual);
        
        if (normExpected.equals(normActual)) {
            return Result.equal();
        }

        // Try attribute-order-independent comparison
        String sortedExpected = sortAttributes(normExpected);
        String sortedActual = sortAttributes(normActual);
        
        if (sortedExpected.equals(sortedActual)) {
            return Result.equal();
        }

        // Try namespace-aware DOM comparison as fallback
        if (domEquals(expected, actual)) {
            return Result.equal();
        }

        // Provide more detail on what differs
        if (sortedExpected.length() != sortedActual.length()) {
            return Result.different("Length differs: expected " + sortedExpected.length() + 
                ", got " + sortedActual.length());
        }
        
        // Find first difference
        for (int i = 0; i < sortedExpected.length(); i++) {
            if (sortedExpected.charAt(i) != sortedActual.charAt(i)) {
                int start = Math.max(0, i - 10);
                int end = Math.min(sortedExpected.length(), i + 20);
                return Result.different("Differs at position " + i + 
                    ": expected '..." + sortedExpected.substring(start, end) + "...' " +
                    "got '..." + sortedActual.substring(start, Math.min(sortedActual.length(), end)) + "...'");
            }
        }
        
        return Result.different("Content differs");
    }

    /**
     * Normalizes all numeric character references to decimal form.
     * Converts &#xNN; (hex) to &#DDD; (decimal) so both formats compare equal.
     */
    private String normalizeCharacterReferences(String xml) {
        StringBuilder sb = new StringBuilder(xml.length());
        int i = 0;
        while (i < xml.length()) {
            if (i + 3 < xml.length() && xml.charAt(i) == '&' && xml.charAt(i + 1) == '#') {
                int semiPos = xml.indexOf(';', i + 2);
                if (semiPos > 0 && semiPos - i < 12) {
                    String ref = xml.substring(i + 2, semiPos);
                    int codePoint = -1;
                    if (ref.length() > 0 && (ref.charAt(0) == 'x' || ref.charAt(0) == 'X')) {
                        try {
                            codePoint = Integer.parseInt(ref.substring(1), 16);
                        } catch (NumberFormatException e) {
                            // not a valid hex ref
                        }
                    } else {
                        try {
                            codePoint = Integer.parseInt(ref);
                        } catch (NumberFormatException e) {
                            // not a valid decimal ref
                        }
                    }
                    if (codePoint >= 0) {
                        sb.append("&#");
                        sb.append(codePoint);
                        sb.append(';');
                        i = semiPos + 1;
                        continue;
                    }
                }
            }
            sb.append(xml.charAt(i));
            i++;
        }
        return sb.toString();
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
     * Normalizes XML by stripping boundary whitespace and collapsing internal whitespace.
     * Whitespace at the beginning and end of text content (after > and before <) is removed.
     * Internal whitespace is collapsed to a single space.
     */
    private String normalize(String xml) {
        StringBuilder sb = new StringBuilder(xml.length());
        boolean inTag = false;
        boolean lastWasSpace = false;
        boolean justAfterTag = false;
        
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            
            if (c == '<') {
                // Starting a new tag - trim any trailing whitespace from text content
                while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                inTag = true;
                lastWasSpace = false;
                justAfterTag = false;
                sb.append(c);
            } else if (c == '>') {
                // Trim trailing space before > (handles " >" -> ">")
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                // Also handle " />" -> "/>" (space before self-closing slash)
                if (sb.length() >= 2 && sb.charAt(sb.length() - 1) == '/' 
                        && sb.charAt(sb.length() - 2) == ' ') {
                    sb.deleteCharAt(sb.length() - 2);
                }
                inTag = false;
                lastWasSpace = false;
                justAfterTag = true;
                sb.append(c);
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (inTag) {
                    // Inside tag - preserve single space for attribute separation
                    if (!lastWasSpace) {
                        sb.append(' ');
                        lastWasSpace = true;
                    }
                } else if (justAfterTag) {
                    // Skip leading whitespace after a tag
                    // Don't update lastWasSpace - we're still in "just after tag" mode
                } else {
                    // In text content - collapse multiple whitespace to single space
                    if (!lastWasSpace) {
                        sb.append(' ');
                        lastWasSpace = true;
                    }
                }
            } else {
                // Non-whitespace character
                justAfterTag = false;
                lastWasSpace = false;
                sb.append(c);
            }
        }
        
        return sb.toString().trim();
    }

    /**
     * Sorts attributes within each element tag for order-independent comparison.
     */
    private String sortAttributes(String xml) {
        StringBuilder result = new StringBuilder(xml.length());
        int i = 0;
        
        while (i < xml.length()) {
            char c = xml.charAt(i);
            
            if (c == '<' && i + 1 < xml.length() && xml.charAt(i + 1) != '/' && xml.charAt(i + 1) != '!' && xml.charAt(i + 1) != '?') {
                // Start of an element tag - find the end
                int tagEnd = xml.indexOf('>', i);
                if (tagEnd == -1) {
                    result.append(xml.substring(i));
                    break;
                }
                
                String tag = xml.substring(i, tagEnd + 1);
                result.append(sortTagAttributes(tag));
                i = tagEnd + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        
        return result.toString();
    }

    /**
     * Sorts attributes within a single tag.
     */
    private String sortTagAttributes(String tag) {
        // Check if it's a self-closing tag
        boolean selfClosing = tag.endsWith("/>");
        String suffix = selfClosing ? "/>" : ">";
        String content = selfClosing ? tag.substring(0, tag.length() - 2) : tag.substring(0, tag.length() - 1);
        
        // Find the element name
        int nameEnd = content.indexOf(' ');
        if (nameEnd == -1) {
            return tag; // No attributes
        }
        
        String elementStart = content.substring(0, nameEnd);
        String attrPart = content.substring(nameEnd).trim();
        
        if (attrPart.isEmpty()) {
            return tag;
        }
        
        // Parse attributes
        List<String> attrs = parseAttributes(attrPart);
        if (attrs.size() <= 1) {
            return tag;
        }
        
        // Sort attributes alphabetically
        Collections.sort(attrs);
        
        // Rebuild tag
        StringBuilder sb = new StringBuilder();
        sb.append(elementStart);
        for (String attr : attrs) {
            sb.append(' ');
            sb.append(attr);
        }
        sb.append(suffix);
        
        return sb.toString();
    }

    /**
     * Parses attributes from a string like: attr1="val1" attr2="val2"
     */
    private List<String> parseAttributes(String attrPart) {
        List<String> attrs = new ArrayList<>();
        int i = 0;
        
        while (i < attrPart.length()) {
            // Skip whitespace
            while (i < attrPart.length() && Character.isWhitespace(attrPart.charAt(i))) {
                i++;
            }
            
            if (i >= attrPart.length()) {
                break;
            }
            
            // Find attribute name
            int nameStart = i;
            while (i < attrPart.length() && attrPart.charAt(i) != '=' && !Character.isWhitespace(attrPart.charAt(i))) {
                i++;
            }
            
            if (i >= attrPart.length() || attrPart.charAt(i) != '=') {
                break; // Malformed
            }
            
            i++; // Skip =
            
            if (i >= attrPart.length()) {
                break;
            }
            
            char quote = attrPart.charAt(i);
            if (quote != '"' && quote != '\'') {
                break; // Malformed
            }
            
            i++; // Skip opening quote
            int valueStart = i;
            
            while (i < attrPart.length() && attrPart.charAt(i) != quote) {
                i++;
            }
            
            if (i >= attrPart.length()) {
                break; // Malformed
            }
            
            i++; // Skip closing quote
            
            attrs.add(attrPart.substring(nameStart, i));
        }
        
        return attrs;
    }

    /**
     * Performs namespace-aware DOM comparison of two XML fragments.
     * Handles namespace inheritance equivalence (e.g. redundant declarations).
     */
    private boolean domEquals(String expected, String actual) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            String wrappedExpected = "<_wrap>" + expected + "</_wrap>";
            String wrappedActual = "<_wrap>" + actual + "</_wrap>";
            Document expectedDoc = builder.parse(
                new InputSource(new StringReader(wrappedExpected)));
            Document actualDoc = builder.parse(
                new InputSource(new StringReader(wrappedActual)));
            return nodesEqual(expectedDoc.getDocumentElement(), actualDoc.getDocumentElement());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nodesEqual(Node expected, Node actual) {
        if (expected.getNodeType() != actual.getNodeType()) {
            return false;
        }
        switch (expected.getNodeType()) {
            case Node.ELEMENT_NODE:
                return elementsEqual((Element) expected, (Element) actual);
            case Node.TEXT_NODE:
            case Node.CDATA_SECTION_NODE:
                String expText = expected.getTextContent();
                String actText = actual.getTextContent();
                if (expText == null) {
                    return actText == null;
                }
                return expText.equals(actText);
            case Node.COMMENT_NODE:
                return safeEquals(expected.getNodeValue(), actual.getNodeValue());
            case Node.PROCESSING_INSTRUCTION_NODE:
                return safeEquals(expected.getNodeName(), actual.getNodeName())
                    && safeEquals(expected.getNodeValue(), actual.getNodeValue());
            default:
                return true;
        }
    }

    private boolean elementsEqual(Element expected, Element actual) {
        String expNs = expected.getNamespaceURI();
        String actNs = actual.getNamespaceURI();
        if (expNs == null) {
            expNs = "";
        }
        if (actNs == null) {
            actNs = "";
        }
        if (!expNs.equals(actNs)) {
            return false;
        }
        if (!safeEquals(expected.getLocalName(), actual.getLocalName())) {
            return false;
        }
        Map<String, String> expAttrs = getNonNamespaceAttributes(expected);
        Map<String, String> actAttrs = getNonNamespaceAttributes(actual);
        if (!expAttrs.equals(actAttrs)) {
            return false;
        }
        NodeList expChildren = expected.getChildNodes();
        NodeList actChildren = actual.getChildNodes();
        List<Node> expSignificant = getSignificantChildren(expChildren);
        List<Node> actSignificant = getSignificantChildren(actChildren);
        if (expSignificant.size() != actSignificant.size()) {
            return false;
        }
        for (int i = 0; i < expSignificant.size(); i++) {
            if (!nodesEqual(expSignificant.get(i), actSignificant.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> getNonNamespaceAttributes(Element element) {
        Map<String, String> result = new TreeMap<String, String>();
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String ns = attr.getNamespaceURI();
            if ("http://www.w3.org/2000/xmlns/".equals(ns)) {
                continue;
            }
            String key = (ns != null && !ns.isEmpty()) ? "{" + ns + "}" + attr.getLocalName()
                : attr.getLocalName();
            result.put(key, attr.getNodeValue());
        }
        return result;
    }

    private List<Node> getSignificantChildren(NodeList children) {
        List<Node> result = new ArrayList<Node>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    result.add(child);
                }
            } else if (child.getNodeType() != Node.DOCUMENT_TYPE_NODE) {
                result.add(child);
            }
        }
        return result;
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
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
