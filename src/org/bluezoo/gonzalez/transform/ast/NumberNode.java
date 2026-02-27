/*
 * NumberNode.java
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * NumberNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class NumberNode extends XSLTInstruction {
    private final XPathExpression valueExpr;
    private final XPathExpression selectExpr; // XSLT 2.0+ select attribute
    private final String level;
    private final Pattern countPattern;
    private final Pattern fromPattern;
    private final AttributeValueTemplate formatAVT;
    private final String groupingSeparator;
    private final int groupingSize;
    private final String lang;
    private final String letterValue;
    private final AttributeValueTemplate startAtAVT; // XSLT 3.0
    
    public NumberNode(XPathExpression valueExpr, XPathExpression selectExpr, String level, 
              Pattern countPattern, Pattern fromPattern, AttributeValueTemplate formatAVT, String groupingSeparator,
              int groupingSize, String lang, String letterValue,
              AttributeValueTemplate startAtAVT) {
        this.valueExpr = valueExpr;
        this.selectExpr = selectExpr;
        this.level = level;
        this.countPattern = countPattern;
        this.fromPattern = fromPattern;
        this.formatAVT = formatAVT;
        this.groupingSeparator = groupingSeparator;
        this.groupingSize = groupingSize;
        this.lang = lang;
        this.letterValue = letterValue;
        this.startAtAVT = startAtAVT;
    }
    
    @Override 
    public String getInstructionName() { 
        return "number"; 
    }
    
    @Override 
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Handle special case: if valueExpr produces NaN, output "NaN" directly (XSLT 1.0 behavior)
        if (valueExpr != null) {
            try {
                XPathValue val = valueExpr.evaluate(context);
                double d = val.asNumber();
                if (Double.isNaN(d)) {
                    output.characters("NaN");
                    return;
                }
                if (Double.isInfinite(d)) {
                    output.characters(d > 0 ? "Infinity" : "-Infinity");
                    return;
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:number value: " + e.getMessage(), e);
            }
        }
        
        // Get the numbers to format
        List<Integer> numbers = getNumbers(context);
        
        // Apply start-at offset (XSLT 3.0)
        if (startAtAVT != null && !numbers.isEmpty()) {
            int[] startAtValues = evaluateStartAt(context);
            for (int i = 0; i < numbers.size(); i++) {
                int offset;
                if (i < startAtValues.length) {
                    offset = startAtValues[i];
                } else if (startAtValues.length > 0) {
                    offset = startAtValues[startAtValues.length - 1];
                } else {
                    offset = 1;
                }
                numbers.set(i, numbers.get(i) + (offset - 1));
            }
        }
        
        // Evaluate the format AVT
        String format;
        try {
            format = formatAVT.evaluate(context);
        } catch (XPathException e) {
            throw new SAXException("Error evaluating xsl:number format: " + e.getMessage(), e);
        }
        
        // Format and output
        String result = formatNumbers(numbers, format);
        output.characters(result);
    }
    
    private List<Integer> getNumbers(TransformContext context) throws SAXException {
        List<Integer> numbers = new ArrayList<>();
        
        if (valueExpr != null) {
            // Use value expression
            try {
                XPathValue val = valueExpr.evaluate(context);
                double d = val.asNumber();
                if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                    // XTDE0980: Negative numbers are not allowed in xsl:number value
                    if (d < 0) {
                        throw new SAXException("XTDE0980: xsl:number value must not be negative: " + d);
                    }
                    numbers.add((int) Math.round(d));
                }
            } catch (XPathException e) {
                throw new SAXException("Error evaluating xsl:number value: " + e.getMessage(), e);
            }
        } else {
            // Determine which node to number
            XPathNode node;
            if (selectExpr != null) {
                // XSLT 2.0+ select attribute - number the selected node
                try {
                    XPathValue selectResult = selectExpr.evaluate(context);
                    if (selectResult instanceof XPathNodeSet) {
                        XPathNodeSet ns = (XPathNodeSet) selectResult;
                        if (ns.isEmpty()) {
                            return numbers;
                        }
                        node = ns.iterator().next();
                    } else if (selectResult instanceof XPathNode) {
                        node = (XPathNode) selectResult;
                    } else {
                        // Not a node - can't number it
                        return numbers;
                    }
                } catch (XPathException e) {
                    throw new SAXException("Error evaluating xsl:number select: " + e.getMessage(), e);
                }
            } else {
                // Default: number the context node
                node = context.getContextNode();
            }
            
            if (node == null) {
                return numbers;
            }
            
            if ("single".equals(level)) {
                int count = countSingle(node, context);
                if (count > 0) {
                    numbers.add(count);
                }
            } else if ("multiple".equals(level)) {
                numbers = countMultiple(node, context);
            } else if ("any".equals(level)) {
                int count = countAny(node, context);
                if (count > 0) {
                    numbers.add(count);
                }
            }
        }
        
        return numbers;
    }
    
    /**
     * Evaluates the start-at AVT and parses the resulting space-separated
     * integers into an array of offset values.
     *
     * @throws SAXException XTDE0030 if the value is not a valid list of integers
     */
    private int[] evaluateStartAt(TransformContext context) throws SAXException {
        String startAtStr;
        try {
            startAtStr = startAtAVT.evaluate(context);
        } catch (XPathException e) {
            throw new SAXException("Error evaluating xsl:number start-at: " + e.getMessage(), e);
        }
        String trimmed = startAtStr.trim();
        if (trimmed.isEmpty()) {
            throw new SAXException("XTDE0030: xsl:number start-at must be " +
                "a whitespace-separated list of integers");
        }
        List<Integer> values = new ArrayList<>();
        int pos = 0;
        int len = trimmed.length();
        while (pos < len) {
            // Skip whitespace
            while (pos < len && isWhitespace(trimmed.charAt(pos))) {
                pos++;
            }
            if (pos >= len) {
                break;
            }
            // Parse integer token (possibly with leading sign)
            int start = pos;
            if (trimmed.charAt(pos) == '-' || trimmed.charAt(pos) == '+') {
                pos++;
            }
            while (pos < len && trimmed.charAt(pos) >= '0' && trimmed.charAt(pos) <= '9') {
                pos++;
            }
            // Validate: must have consumed at least one digit, and next char
            // must be whitespace or end of string
            boolean hasDigits = false;
            for (int j = start; j < pos; j++) {
                char ch = trimmed.charAt(j);
                if (ch >= '0' && ch <= '9') {
                    hasDigits = true;
                    break;
                }
            }
            if (!hasDigits || (pos < len && !isWhitespace(trimmed.charAt(pos)))) {
                throw new SAXException("XTDE0030: Invalid xsl:number start-at value: " +
                    "'" + trimmed + "' is not a valid list of integers");
            }
            String token = trimmed.substring(start, pos);
            try {
                values.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                throw new SAXException("XTDE0030: Invalid integer in xsl:number start-at: " +
                    "'" + token + "'");
            }
        }
        if (values.isEmpty()) {
            throw new SAXException("XTDE0030: xsl:number start-at must be " +
                "a whitespace-separated list of integers");
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
    
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
    
    private int countSingle(XPathNode node, TransformContext context) {
        // Per XSLT spec: "starting from the focus node, go upwards to find 
        // the first node n that matches the count pattern"
        
        // Find from boundary if specified
        // If from pattern is specified but not found, continue counting from document root
        XPathNode fromBoundary = null;
        if (fromPattern != null) {
            XPathNode ancestor = node;
            while (ancestor != null) {
                if (fromPattern.matches(ancestor, context)) {
                    fromBoundary = ancestor;
                    break;
                }
                ancestor = ancestor.getParent();
            }
            // Note: if fromBoundary is null, we continue counting without a boundary
            // This matches XSLT spec behavior where nodes before any "from" match are still counted
        }
        
        // Go up from the focus node to find the first node matching count pattern
        XPathNode current = node;
        while (current != null) {
            // Check if current matches the count pattern
            if (matchesCount(current, context)) {
                // Found a matching node - count it and its preceding siblings
                int count = 1;
                XPathNode sibling = current.getPrecedingSibling();
                while (sibling != null) {
                    if (matchesCount(sibling, context)) {
                        count++;
                    }
                    sibling = sibling.getPrecedingSibling();
                }
                return count;
            }
            
            // Stop if we've reached the from boundary (don't go into or past it)
            if (fromBoundary != null && current == fromBoundary) {
                break;
            }
            
            current = current.getParent();
        }
        
        return 0;
    }
    
    private List<Integer> countMultiple(XPathNode node, TransformContext context) {
        // Build list of counts at each ancestor level
        List<Integer> counts = new ArrayList<>();
        XPathNode current = node;
        
        while (current != null && current.getNodeType() != NodeType.ROOT) {
            // First check if current matches count pattern and count it
            if (matchesCount(current, context)) {
                int count = 1;
                XPathNode sibling = current.getPrecedingSibling();
                while (sibling != null) {
                    if (matchesCount(sibling, context)) {
                        count++;
                    }
                    sibling = sibling.getPrecedingSibling();
                }
                counts.add(0, count); // Prepend to get correct order
            }
            
            // Then check from pattern - stop if matched (after counting this node)
            if (fromPattern != null && fromPattern.matches(current, context)) {
                break;
            }
            
            current = current.getParent();
        }
        
        return counts;
    }
    
    private int countAny(XPathNode node, TransformContext context) {
        // Count all matching nodes before this one in document order
        // starting from the most recent node matching 'from' pattern
        
        // Get document root
        XPathNode root = node;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        
        // Find the most recent node matching 'from' pattern
        // If the current node itself matches 'from', use it as the boundary
        XPathNode fromBoundary = null;
        if (fromPattern != null) {
            if (fromPattern.matches(node, context)) {
                // Current node matches 'from' - count restarts from here
                fromBoundary = node;
            } else {
                // Find the most recent preceding node matching 'from'
                fromBoundary = findLastMatchingBefore(root, node, fromPattern, context);
            }
        }
        
        // Count all matching nodes from start (or from boundary)
        int count = countNodesAfterBoundary(root, node, fromBoundary, context);
        
        // Include current node if it matches
        if (matchesCount(node, context)) {
            count++;
        }
        
        return count;
    }
    
    private XPathNode findLastMatchingBefore(XPathNode current, XPathNode target,
                                             Pattern pattern, TransformContext context) {
        // Find the last node in document order before target that matches pattern
        if (current == target) {
            return null;
        }
        
        XPathNode lastMatch = null;
        
        // Check current node
        if (current.getDocumentOrder() < target.getDocumentOrder()) {
            if (pattern.matches(current, context)) {
                lastMatch = current;
            }
        }
        
        // Recurse into children
        Iterator<XPathNode> children = current.getChildren();
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (child.getDocumentOrder() < target.getDocumentOrder()) {
                XPathNode childMatch = findLastMatchingBefore(child, target, pattern, context);
                if (childMatch != null) {
                    lastMatch = childMatch;
                }
            }
        }
        
        return lastMatch;
    }
    
    private int countNodesAfterBoundary(XPathNode current, XPathNode target,
                                        XPathNode fromBoundary, TransformContext context) {
        // Count nodes matching 'count' pattern that are after fromBoundary and before target
        if (current.isSameNode(target)) {
            return 0;
        }
        
        int count = 0;
        long currentOrder = current.getDocumentOrder();
        long targetOrder = target.getDocumentOrder();
        long boundaryOrder = fromBoundary != null ? fromBoundary.getDocumentOrder() : -1;
        
        // Check if we can use document order (non-zero indicates properly set up)
        boolean useDocOrder = targetOrder != 0;
        
        // Only count if at or after boundary and before target
        // The boundary node itself is included if it matches the count pattern
        if (useDocOrder) {
            if (currentOrder >= boundaryOrder && currentOrder < targetOrder) {
                if (matchesCount(current, context)) {
                    count++;
                }
            }
        } else {
            // For RTF nodes without document order, use recursive tree walk
            // Count this node if it matches and comes before target in document order
            // In document order, ancestors come before descendants, so count them
            if (matchesCount(current, context)) {
                count++;
            }
        }
        
        // Recurse into children
        Iterator<XPathNode> children = current.getChildren();
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (useDocOrder) {
                if (child.getDocumentOrder() < targetOrder) {
                    count += countNodesAfterBoundary(child, target, fromBoundary, context);
                }
            } else {
                // For RTF without doc order, continue recursive walk
                // We'll stop when we hit the target (checked at start of method)
                count += countNodesAfterBoundary(child, target, fromBoundary, context);
            }
        }
        
        return count;
    }
    
    
    private boolean matchesCount(XPathNode node, TransformContext context) {
        if (countPattern != null) {
            return countPattern.matches(node, context);
        }
        // Default: match nodes with same name and type as context node
        XPathNode contextNode = context.getContextNode();
        if (contextNode == null) {
            return false;
        }
        if (node.getNodeType() != contextNode.getNodeType()) {
            return false;
        }
        // For elements, also check the name
        if (node.getNodeType() == NodeType.ELEMENT) {
            String nodeName = node.getLocalName();
            String contextName = contextNode.getLocalName();
            String nodeNs = node.getNamespaceURI();
            String contextNs = contextNode.getNamespaceURI();
            
            if (nodeName == null || !nodeName.equals(contextName)) {
                return false;
            }
            if (nodeNs == null) {
                return contextNs == null || contextNs.isEmpty();
            }
            return nodeNs.equals(contextNs);
        }
        return true;
    }
    
    private String formatNumbers(List<Integer> numbers, String format) {
        // Parse format string into components
        ParsedFormat parsed = parseFormatString(format);
        
        // When no numbers, just output prefix + suffix (e.g., "[]" for format "[1]")
        if (numbers.isEmpty()) {
            return parsed.prefix + parsed.suffix;
        }
        
        StringBuilder result = new StringBuilder();
        
        // Add leading prefix
        result.append(parsed.prefix);
        
        for (int i = 0; i < numbers.size(); i++) {
            int num = numbers.get(i);
            
            // Add separator before this number (except first)
            if (i > 0) {
                // Use separator from previous position, or last available
                int sepIdx = i - 1;
                if (sepIdx < parsed.separators.size()) {
                    result.append(parsed.separators.get(sepIdx));
                } else if (!parsed.separators.isEmpty()) {
                    result.append(parsed.separators.get(parsed.separators.size() - 1));
                } else {
                    result.append(".");  // Default separator
                }
            }
            
            // Get format specifier for this number (reuse last if not enough)
            String specifier;
            if (i < parsed.specifiers.size()) {
                specifier = parsed.specifiers.get(i);
            } else if (!parsed.specifiers.isEmpty()) {
                specifier = parsed.specifiers.get(parsed.specifiers.size() - 1);
            } else {
                specifier = "1";
            }
            
            // Format the number
            String formatted = formatSingleNumber(num, specifier);
            
            // Apply grouping if specified
            if (groupingSeparator != null && groupingSize > 0) {
                formatted = applyGrouping(formatted, groupingSeparator, groupingSize);
            }
            
            result.append(formatted);
        }
        
        // Add trailing suffix
        result.append(parsed.suffix);
        
        return result.toString();
    }
    
    private ParsedFormat parseFormatString(String fmt) {
        String prefix = "";
        List<String> specifiers = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        String suffix = "";
        
        int i = 0;
        int len = fmt.length();
        
        // Collect leading prefix (non-alphanumeric chars before first specifier)
        StringBuilder prefixBuf = new StringBuilder();
        while (i < len && !isFormatChar(fmt.charAt(i))) {
            prefixBuf.append(fmt.charAt(i));
            i++;
        }
        prefix = prefixBuf.toString();
        
        // Parse specifiers and separators
        while (i < len) {
            // Collect format specifier (alphanumeric sequence)
            StringBuilder specifier = new StringBuilder();
            while (i < len && isFormatChar(fmt.charAt(i))) {
                specifier.append(fmt.charAt(i));
                i++;
            }
            
            if (specifier.length() > 0) {
                specifiers.add(specifier.toString());
            }
            
            // Collect separator (non-alphanumeric chars until next specifier or end)
            StringBuilder sep = new StringBuilder();
            while (i < len && !isFormatChar(fmt.charAt(i))) {
                sep.append(fmt.charAt(i));
                i++;
            }
            
            if (i < len) {
                // More specifiers to come - this is a separator
                separators.add(sep.toString());
            } else {
                // End of string - this is the suffix
                suffix = sep.toString();
            }
        }
        
        // Default specifier if none found
        if (specifiers.isEmpty()) {
            specifiers.add("1");
        }
        
        return new ParsedFormat(prefix, specifiers, separators, suffix);
    }
    
    // Helper class for parsed format
    private static class ParsedFormat {
        final String prefix;
        final List<String> specifiers;
        final List<String> separators;
        final String suffix;
        
        ParsedFormat(String prefix, List<String> specifiers, 
                    List<String> separators, String suffix) {
            this.prefix = prefix;
            this.specifiers = specifiers;
            this.separators = separators;
            this.suffix = suffix;
        }
    }
    
    private boolean isFormatChar(char c) {
        return (c >= '0' && c <= '9') || 
               (c >= 'a' && c <= 'z') || 
               (c >= 'A' && c <= 'Z');
    }
    
    private String formatSingleNumber(int num, String specifier) {
        if (specifier.isEmpty()) {
            specifier = "1";
        }
        
        char first = specifier.charAt(0);
        
        // Determine format type from first character
        if (first == 'a') {
            return toAlphabetic(num, false);
        } else if (first == 'A') {
            return toAlphabetic(num, true);
        } else if (first == 'i') {
            return toRoman(num, false);
        } else if (first == 'I') {
            return toRoman(num, true);
        } else if (first >= '0' && first <= '9') {
            // Numeric format - check for zero padding
            int minWidth = specifier.length();
            return zeroPad(num, minWidth);
        } else {
            // Default to decimal
            return String.valueOf(num);
        }
    }
    
    private String zeroPad(int num, int minWidth) {
        String s = String.valueOf(num);
        if (s.length() >= minWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int padding = minWidth - s.length();
        for (int i = 0; i < padding; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }
    
    private String toAlphabetic(int num, boolean uppercase) {
        if (num <= 0) {
            return String.valueOf(num);
        }
        
        StringBuilder sb = new StringBuilder();
        int n = num;
        
        while (n > 0) {
            n--; // Adjust for 1-based
            int remainder = n % 26;
            char c;
            if (uppercase) {
                c = (char) ('A' + remainder);
            } else {
                c = (char) ('a' + remainder);
            }
            sb.insert(0, c);
            n = n / 26;
        }
        
        return sb.toString();
    }
    
    private static final int MAX_ROMAN_NUMERAL = 3999;

    private String toRoman(int num, boolean uppercase) {
        if (num <= 0 || num > MAX_ROMAN_NUMERAL) {
            return String.valueOf(num);
        }
        
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        
        StringBuilder sb = new StringBuilder();
        sb.append(thousands[num / 1000]);
        sb.append(hundreds[(num % 1000) / 100]);
        sb.append(tens[(num % 100) / 10]);
        sb.append(ones[num % 10]);
        
        String result = sb.toString();
        if (!uppercase) {
            result = result.toLowerCase();
        }
        return result;
    }
    
    private String applyGrouping(String numStr, String separator, int size) {
        if (size <= 0 || numStr.length() <= size) {
            return numStr;
        }
        
        StringBuilder sb = new StringBuilder();
        int start = numStr.length() % size;
        if (start == 0) {
            start = size;
        }
        
        sb.append(numStr.substring(0, start));
        
        for (int i = start; i < numStr.length(); i += size) {
            sb.append(separator);
            int end = i + size;
            if (end > numStr.length()) {
                end = numStr.length();
            }
            sb.append(numStr.substring(i, end));
        }
        
        return sb.toString();
    }
}
