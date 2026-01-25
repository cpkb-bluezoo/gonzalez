/*
 * XSLTFunctionLibrary.java
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

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.KeyDefinition;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT-specific function library.
 *
 * <p>Extends the XPath core function library with XSLT-specific functions:
 * <ul>
 *   <li>current() - returns the current node being processed</li>
 *   <li>key(name, value) - returns nodes matching a key</li>
 *   <li>document(uri) - loads external documents</li>
 *   <li>format-number(number, pattern) - formats numbers</li>
 *   <li>generate-id(node?) - generates unique ID</li>
 *   <li>system-property(name) - returns system property</li>
 *   <li>element-available(name) - tests for element availability</li>
 *   <li>function-available(name) - tests for function availability</li>
 *   <li>unparsed-entity-uri(name) - returns URI of unparsed entity</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XSLTFunctionLibrary implements XPathFunctionLibrary {

    /** Singleton instance. */
    public static final XSLTFunctionLibrary INSTANCE = new XSLTFunctionLibrary();

    private final Map<String, Function> xsltFunctions;

    private XSLTFunctionLibrary() {
        Map<String, Function> map = new HashMap<>();
        
        map.put("current", new CurrentFunction());
        map.put("key", new KeyFunction());
        map.put("document", new DocumentFunction());
        map.put("format-number", new FormatNumberFunction());
        map.put("generate-id", new GenerateIdFunction());
        map.put("system-property", new SystemPropertyFunction());
        map.put("element-available", new ElementAvailableFunction());
        map.put("function-available", new FunctionAvailableFunction());
        map.put("unparsed-entity-uri", new UnparsedEntityUriFunction());
        
        // XSLT 3.0 accumulator functions
        map.put("accumulator-before", new AccumulatorBeforeFunction());
        map.put("accumulator-after", new AccumulatorAfterFunction());
        
        // XSLT 2.0 grouping functions
        map.put("current-group", new CurrentGroupFunction());
        map.put("current-grouping-key", new CurrentGroupingKeyFunction());
        
        this.xsltFunctions = Collections.unmodifiableMap(map);
    }

    @Override
    public boolean hasFunction(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            if (xsltFunctions.containsKey(localName)) {
                return true;
            }
        }
        return CoreFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName);
    }

    @Override
    public XPathValue invokeFunction(String namespaceURI, String localName, 
                                     List<XPathValue> args, XPathContext context) 
            throws XPathException {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return f.evaluate(args, context);
            }
        }
        return CoreFunctionLibrary.INSTANCE.invokeFunction(namespaceURI, localName, args, context);
    }

    @Override
    public int getArgumentCount(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return -1; // Variable args for most XSLT functions
            }
        }
        return CoreFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
    }

    // ========================================================================
    // XSLT Function Implementations
    // ========================================================================

    /** current() - Returns the XSLT current node being processed. */
    private static class CurrentFunction implements Function {
        @Override
        public String getName() { return "current"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Use getXsltCurrentNode() to get the XSLT current node,
            // which stays the same during predicate evaluation unlike the context node
            XPathNode node = context.getXsltCurrentNode();
            if (node == null) {
                return XPathNodeSet.empty();
            }
            List<XPathNode> nodes = new ArrayList<>();
            nodes.add(node);
            return new XPathNodeSet(nodes);
        }
    }

    /** key(name, value) - Returns nodes matching a key definition. */
    private static class KeyFunction implements Function {
        @Override
        public String getName() { return "key"; }
        
        @Override
        public int getMinArgs() { return 2; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String keyName = args.get(0).asString();
            XPathValue keyValue = args.get(1);
            
            // Get the stylesheet from context (must be TransformContext)
            if (!(context instanceof TransformContext)) {
                return XPathNodeSet.empty();
            }
            TransformContext txContext = (TransformContext) context;
            CompiledStylesheet stylesheet = txContext.getStylesheet();
            
            KeyDefinition keyDef = stylesheet.getKeyDefinition(keyName);
            if (keyDef == null) {
                return XPathNodeSet.empty();
            }
            
            // Get the document root
            XPathNode contextNode = context.getContextNode();
            if (contextNode == null) {
                return XPathNodeSet.empty();
            }
            XPathNode root = contextNode.getRoot();
            if (root == null) {
                return XPathNodeSet.empty();
            }
            
            // Build key index and look up nodes
            // For node-set key values, check each node's string value
            List<String> searchValues = new ArrayList<>();
            if (keyValue instanceof XPathNodeSet) {
                for (XPathNode node : ((XPathNodeSet) keyValue).getNodes()) {
                    searchValues.add(node.getStringValue());
                }
            } else {
                searchValues.add(keyValue.asString());
            }
            
            // Find all matching nodes
            List<XPathNode> result = new ArrayList<>();
            Pattern matchPattern = keyDef.getMatchPattern();
            XPathExpression useExpr = keyDef.getUseExpr();
            
            // Walk the document tree and find matches
            collectKeyMatches(root, matchPattern, useExpr, searchValues, result, txContext);
            
            return new XPathNodeSet(result);
        }
        
        /**
         * Recursively collects nodes that match the key definition and have
         * matching key values.
         */
        private void collectKeyMatches(XPathNode node, Pattern matchPattern, 
                                       XPathExpression useExpr, List<String> searchValues,
                                       List<XPathNode> result, TransformContext context) 
                                       throws XPathException {
            // Check if this node matches the key pattern
            if (matchPattern.matches(node, context)) {
                // Evaluate the use expression for this node
                XPathContext nodeContext = context.withContextNode(node);
                XPathValue useValue = useExpr.evaluate(nodeContext);
                
                // Get the key value(s) for this node
                List<String> nodeKeyValues = new ArrayList<>();
                if (useValue instanceof XPathNodeSet) {
                    for (XPathNode n : ((XPathNodeSet) useValue).getNodes()) {
                        nodeKeyValues.add(n.getStringValue());
                    }
                } else {
                    nodeKeyValues.add(useValue.asString());
                }
                
                // Check if any of this node's key values match our search values
                for (String nodeKey : nodeKeyValues) {
                    for (String searchKey : searchValues) {
                        if (nodeKey.equals(searchKey)) {
                            // Avoid duplicates
                            boolean found = false;
                            for (XPathNode existing : result) {
                                if (existing.isSameNode(node)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                result.add(node);
                            }
                            break;
                        }
                    }
                }
            }
            
            // Recurse into children
            java.util.Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                collectKeyMatches(children.next(), matchPattern, useExpr, 
                                  searchValues, result, context);
            }
        }
    }

    /** document(uri, base?) - Loads an external document. */
    private static class DocumentFunction implements Function {
        @Override
        public String getName() { return "document"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // TODO: Implement document loading
            return XPathNodeSet.empty();
        }
    }

    /** format-number(number, format, decimal-format?) - Formats a number. */
    private static class FormatNumberFunction implements Function {
        @Override
        public String getName() { return "format-number"; }
        
        @Override
        public int getMinArgs() { return 2; }
        
        @Override
        public int getMaxArgs() { return 3; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue numArg = args.size() > 0 ? args.get(0) : null;
            XPathValue patArg = args.size() > 1 ? args.get(1) : null;
            String formatName = args.size() > 2 ? args.get(2).asString() : null;
            
            double number = (numArg != null) ? numArg.asNumber() : Double.NaN;
            String pattern = (patArg != null) ? patArg.asString() : "#";
            
            // Get decimal format from stylesheet if available
            CompiledStylesheet.DecimalFormatInfo decFormat = null;
            if (context instanceof org.bluezoo.gonzalez.transform.runtime.TransformContext) {
                org.bluezoo.gonzalez.transform.runtime.TransformContext tc = 
                    (org.bluezoo.gonzalez.transform.runtime.TransformContext) context;
                CompiledStylesheet stylesheet = tc.getStylesheet();
                if (stylesheet != null) {
                    decFormat = stylesheet.getDecimalFormat(formatName);
                }
            }
            
            // Default format symbols
            char decimalSep = '.';
            char groupingSep = ',';
            char minusSign = '-';
            char percent = '%';
            char perMille = '\u2030';
            char zeroDigit = '0';
            char digit = '#';
            char patternSep = ';';
            String infinity = "Infinity";
            String nan = "NaN";
            
            // Apply custom format if available
            if (decFormat != null) {
                decimalSep = decFormat.decimalSeparator;
                groupingSep = decFormat.groupingSeparator;
                minusSign = decFormat.minusSign;
                percent = decFormat.percent;
                perMille = decFormat.perMille;
                zeroDigit = decFormat.zeroDigit;
                digit = decFormat.digit;
                patternSep = decFormat.patternSeparator;
                infinity = decFormat.infinity;
                nan = decFormat.nan;
            }
            
            // Handle special values
            if (Double.isNaN(number)) {
                return XPathString.of(nan);
            }
            if (Double.isInfinite(number)) {
                if (number < 0) {
                    return XPathString.of(String.valueOf(minusSign) + infinity);
                }
                return XPathString.of(infinity);
            }
            
            // Check for per-mille or percent in pattern (for custom char restoration later)
            boolean hasPerMille = pattern.indexOf(perMille) >= 0;
            boolean hasPercent = pattern.indexOf(percent) >= 0;
            
            // NOTE: We do NOT multiply here. Java's DecimalFormat automatically
            // multiplies by 100 for % and 1000 for ‰ when these symbols appear
            // in the pattern.
            
            // Translate pattern to Java DecimalFormat syntax
            String javaPattern = translatePattern(pattern, decimalSep, groupingSep, 
                minusSign, percent, perMille, zeroDigit, digit, patternSep);
            
            try {
                java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols();
                symbols.setDecimalSeparator(decimalSep);
                symbols.setGroupingSeparator(groupingSep);
                // Don't set custom minus sign in symbols - we'll handle it manually
                // Java DecimalFormat treats '-' in pattern prefix as "use minus sign symbol"
                // but XSLT treats '-' in an explicit negative subpattern as literal
                symbols.setPercent(percent);
                symbols.setPerMill(perMille);
                symbols.setZeroDigit(zeroDigit);
                symbols.setDigit(digit);
                symbols.setPatternSeparator(patternSep);
                symbols.setInfinity(infinity);
                symbols.setNaN(nan);
                
                java.text.DecimalFormat df = new java.text.DecimalFormat(javaPattern, symbols);
                String result = df.format(number);
                
                // Apply custom minus sign only when using DEFAULT negative prefix
                // (i.e., pattern has no explicit negative subpattern)
                boolean hasExplicitNegativeSubpattern = pattern.indexOf(patternSep) >= 0;
                if (!hasExplicitNegativeSubpattern && number < 0 && minusSign != '-') {
                    // Replace the default minus with custom minus sign
                    if (result.startsWith("-")) {
                        result = minusSign + result.substring(1);
                    }
                }
                
                // Restore custom per-mille/percent characters in output
                if (hasPerMille && perMille != '\u2030') {
                    result = result.replace('\u2030', perMille);
                }
                if (hasPercent && percent != '%') {
                    result = result.replace('%', percent);
                }
                
                return XPathString.of(result);
            } catch (IllegalArgumentException e) {
                // Fallback for invalid patterns
                if (number == Math.floor(number) && !Double.isInfinite(number)) {
                    return XPathString.of(String.valueOf((long) number));
                }
                return XPathString.of(String.valueOf(number));
            }
        }
        
        private String translatePattern(String pattern, char decimalSep, char groupingSep,
                char minusSign, char percent, char perMille, char zeroDigit, char digit,
                char patternSep) {
            // If all symbols are default, no translation needed
            if (decimalSep == '.' && groupingSep == ',' &&
                percent == '%' && perMille == '\u2030' && zeroDigit == '0' &&
                digit == '#' && patternSep == ';') {
                return pattern;
            }
            
            // Translate custom symbols to Java DecimalFormat standard symbols
            // NOTE: We do NOT translate minusSign here because in XSLT patterns,
            // the minus character in a prefix/suffix is a LITERAL character,
            // not the special minus-sign symbol. The minus-sign attribute only
            // affects the DEFAULT negative prefix (when no explicit negative
            // subpattern is given).
            StringBuilder sb = new StringBuilder(pattern.length());
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == decimalSep) {
                    sb.append('.');
                } else if (c == groupingSep) {
                    sb.append(',');
                } else if (c == percent) {
                    sb.append('%');
                } else if (c == perMille) {
                    sb.append('\u2030');
                } else if (c == zeroDigit) {
                    sb.append('0');
                } else if (c == digit) {
                    sb.append('#');
                } else if (c == patternSep) {
                    sb.append(';');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    /** generate-id(node?) - Generates a unique ID for a node. */
    private static class GenerateIdFunction implements Function {
        @Override
        public String getName() { return "generate-id"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("generate-id() argument must be a node-set");
                }
                XPathNodeSet ns = (XPathNodeSet) arg;
                if (ns.isEmpty()) {
                    return XPathString.of("");
                }
                node = ns.first();
            }
            
            if (node == null) {
                return XPathString.of("");
            }
            return XPathString.of("id" + node.getDocumentOrder());
        }
    }

    /** system-property(name) - Returns XSLT processor properties. */
    private static class SystemPropertyFunction implements Function {
        @Override
        public String getName() { return "system-property"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            if ("xsl:version".equals(name)) {
                return XPathString.of("1.0");
            }
            if ("xsl:vendor".equals(name)) {
                return XPathString.of("Gonzalez XSLT");
            }
            if ("xsl:vendor-url".equals(name)) {
                return XPathString.of("https://www.nongnu.org/gonzalez/");
            }
            
            return XPathString.of("");
        }
    }

    /** element-available(name) - Tests if an XSLT element is available. */
    private static class ElementAvailableFunction implements Function {
        @Override
        public String getName() { return "element-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            if (name.startsWith("xsl:")) {
                String localName = name.substring(4);
                switch (localName) {
                    case "apply-imports":
                    case "apply-templates":
                    case "attribute":
                    case "attribute-set":
                    case "call-template":
                    case "choose":
                    case "comment":
                    case "copy":
                    case "copy-of":
                    case "decimal-format":
                    case "element":
                    case "fallback":
                    case "for-each":
                    case "if":
                    case "import":
                    case "include":
                    case "key":
                    case "message":
                    case "namespace-alias":
                    case "number":
                    case "otherwise":
                    case "output":
                    case "param":
                    case "preserve-space":
                    case "processing-instruction":
                    case "sort":
                    case "strip-space":
                    case "stylesheet":
                    case "template":
                    case "text":
                    case "transform":
                    case "value-of":
                    case "variable":
                    case "when":
                    case "with-param":
                        return XPathBoolean.TRUE;
                    default:
                        return XPathBoolean.FALSE;
                }
            }
            
            return XPathBoolean.FALSE;
        }
    }

    /** function-available(name) - Tests if a function is available. */
    private static class FunctionAvailableFunction implements Function {
        @Override
        public String getName() { return "function-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            boolean available = INSTANCE.hasFunction(null, name);
            return available ? XPathBoolean.TRUE : XPathBoolean.FALSE;
        }
    }

    /** unparsed-entity-uri(name) - Returns the URI of an unparsed entity. */
    private static class UnparsedEntityUriFunction implements Function {
        @Override
        public String getName() { return "unparsed-entity-uri"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Would need access to DTD declarations
            return XPathString.of("");
        }
    }

    // ========================================================================
    // XSLT 3.0 Accumulator Functions
    // ========================================================================

    /**
     * accumulator-before(name) - Returns the accumulator value before processing
     * the current node.
     *
     * <p>This function returns the value of the named accumulator before any
     * accumulator rules have fired for the current node. It is typically used
     * in pre-descent rules to access the value from the parent context.
     */
    private static class AccumulatorBeforeFunction implements Function {
        @Override
        public String getName() { return "accumulator-before"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            // Get accumulator value from context
            XPathValue value = context.getAccumulatorBefore(name);
            if (value != null) {
                return value;
            }
            
            // Accumulator not found
            throw new XPathException("Unknown accumulator: " + name);
        }
    }

    // ========================================================================
    // XSLT 2.0 Grouping Functions
    // ========================================================================

    /**
     * current-group() - Returns the items in the current group during
     * xsl:for-each-group iteration.
     */
    private static class CurrentGroupFunction implements Function {
        @Override
        public String getName() { return "current-group"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Look for current group in variable scope
            XPathValue group = context.getVariable(null, "__current_group__");
            if (group != null && group.isNodeSet()) {
                return group;
            }
            // Return empty sequence if not in grouping context
            return XPathNodeSet.empty();
        }
    }

    /**
     * current-grouping-key() - Returns the grouping key for the current group
     * during xsl:for-each-group iteration.
     */
    private static class CurrentGroupingKeyFunction implements Function {
        @Override
        public String getName() { return "current-grouping-key"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Look for grouping key in variable scope
            XPathValue key = context.getVariable(null, "__current_grouping_key__");
            if (key != null) {
                return key;
            }
            // Return empty string if not in grouping context
            return XPathString.of("");
        }
    }

    // ========================================================================
    // XSLT 3.0 Accumulator Functions
    // ========================================================================

    /**
     * accumulator-after(name) - Returns the accumulator value after processing
     * the current node.
     *
     * <p>This function returns the value of the named accumulator after all
     * accumulator rules have fired for the current node and its descendants.
     * It is typically used to access the final accumulated value at the end
     * of processing.
     */
    private static class AccumulatorAfterFunction implements Function {
        @Override
        public String getName() { return "accumulator-after"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            // Get accumulator value from context
            XPathValue value = context.getAccumulatorAfter(name);
            if (value != null) {
                return value;
            }
            
            // Accumulator not found
            throw new XPathException("Unknown accumulator: " + name);
        }
    }
}
