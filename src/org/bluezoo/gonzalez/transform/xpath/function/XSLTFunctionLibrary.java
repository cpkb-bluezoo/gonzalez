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
import org.bluezoo.gonzalez.transform.compiler.UserFunction;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeWithBaseURI;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.bluezoo.gonzalez.transform.xpath.type.NodeType;

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
        map.put("doc", new DocFunction());
        map.put("format-number", new FormatNumberFunction());
        map.put("generate-id", new GenerateIdFunction());
        map.put("system-property", new SystemPropertyFunction());
        map.put("element-available", new ElementAvailableFunction());
        map.put("function-available", new FunctionAvailableFunction());
        map.put("type-available", new TypeAvailableFunction());
        map.put("unparsed-entity-uri", new UnparsedEntityUriFunction());
        
        // XSLT 3.0 accumulator functions
        map.put("accumulator-before", new AccumulatorBeforeFunction());
        map.put("accumulator-after", new AccumulatorAfterFunction());
        
        // XSLT 2.0 grouping functions
        map.put("current-group", new CurrentGroupFunction());
        map.put("current-grouping-key", new CurrentGroupingKeyFunction());
        
        this.xsltFunctions = Collections.unmodifiableMap(map);
    }

    /**
     * Checks if a function with the given namespace and local name is available.
     *
     * @param namespaceURI the namespace URI, or null/empty for built-in functions
     * @param localName the local name of the function
     * @return true if the function is available
     */
    @Override
    public boolean hasFunction(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            if (xsltFunctions.containsKey(localName)) {
                return true;
            }
        }
        // User-defined functions are checked at invoke time since we need context
        // to access the stylesheet. Return true to avoid "unknown function" errors.
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return true; // May be a user-defined function
        }
        return CoreFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName);
    }

    private static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    
    /**
     * Invokes a function with the given namespace, local name, and arguments.
     *
     * @param namespaceURI the namespace URI, or null/empty for built-in functions
     * @param localName the local name of the function
     * @param args the evaluated function arguments
     * @param context the XPath evaluation context
     * @return the function result
     * @throws XPathException if the function is unknown or invocation fails
     */
    @Override
    public XPathValue invokeFunction(String namespaceURI, String localName, 
                                     List<XPathValue> args, XPathContext context) 
            throws XPathException {
        // First check built-in XSLT functions (no namespace)
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return f.evaluate(args, context);
            }
        }
        
        // Check for XML Schema constructor functions (xs:date, xs:dateTime, etc.)
        if (XS_NAMESPACE.equals(namespaceURI)) {
            return invokeXsConstructor(localName, args);
        }
        
        // Check for user-defined functions (must be in a namespace)
        if (namespaceURI != null && !namespaceURI.isEmpty() && context instanceof TransformContext) {
            TransformContext transformContext = (TransformContext) context;
            CompiledStylesheet stylesheet = transformContext.getStylesheet();
            if (stylesheet != null) {
                UserFunction userFunc = stylesheet.getUserFunction(namespaceURI, localName, args.size());
                if (userFunc != null) {
                    return invokeUserFunction(userFunc, args, transformContext);
                }
            }
        }
        
        return CoreFunctionLibrary.INSTANCE.invokeFunction(namespaceURI, localName, args, context);
    }
    
    /**
     * Invokes an XML Schema constructor function (xs:date, xs:dateTime, etc.).
     *
     * @param localName the local name of the constructor (e.g., "date", "integer")
     * @param args the constructor arguments
     * @return the constructed value
     * @throws XPathException if construction fails
     */
    private XPathValue invokeXsConstructor(String localName, List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("xs:" + localName + " requires an argument");
        }
        
        XPathValue arg = args.get(0);
        
        // Handle empty sequence
        if (arg == null || (arg instanceof XPathSequence && ((XPathSequence)arg).isEmpty())) {
            return XPathSequence.EMPTY;
        }
        
        String value = arg.asString();
        
        switch (localName) {
            case "dateTime":
                return XPathDateTime.parseDateTime(value);
            case "date":
                return XPathDateTime.parseDate(value);
            case "time":
                return XPathDateTime.parseTime(value);
            case "duration":
                return XPathDateTime.parseDuration(value);
            case "yearMonthDuration":
                return XPathDateTime.parseYearMonthDuration(value);
            case "dayTimeDuration":
                return XPathDateTime.parseDayTimeDuration(value);
            case "gYearMonth":
                return XPathDateTime.parseGYearMonth(value);
            case "gYear":
                return XPathDateTime.parseGYear(value);
            case "gMonthDay":
                return XPathDateTime.parseGMonthDay(value);
            case "gDay":
                return XPathDateTime.parseGDay(value);
            case "gMonth":
                return XPathDateTime.parseGMonth(value);
            case "string":
                return XPathString.of(value);
            case "integer":
                try {
                    // If the argument is already a number, convert directly
                    if (arg instanceof XPathNumber) {
                        double d = arg.asNumber();
                        if (Double.isNaN(d) || Double.isInfinite(d)) {
                            throw new XPathException("Cannot convert " + d + " to xs:integer");
                        }
                        return XPathNumber.of((long) d);
                    }
                    long intValue = Long.parseLong(value.trim());
                    return XPathNumber.of(intValue);
                } catch (NumberFormatException e) {
                    throw new XPathException("Invalid xs:integer: " + value);
                }
            case "decimal":
            case "double":
            case "float":
                try {
                    // If the argument is already a number, return it directly
                    if (arg instanceof XPathNumber) {
                        return arg;
                    }
                    String trimmedNum = value.trim();
                    // Handle XPath special values (INF, -INF, +INF, NaN)
                    if ("INF".equals(trimmedNum) || "+INF".equals(trimmedNum)) {
                        return XPathNumber.of(Double.POSITIVE_INFINITY);
                    } else if ("-INF".equals(trimmedNum)) {
                        return XPathNumber.of(Double.NEGATIVE_INFINITY);
                    } else if ("NaN".equals(trimmedNum)) {
                        return XPathNumber.of(Double.NaN);
                    }
                    double numValue = Double.parseDouble(trimmedNum);
                    return XPathNumber.of(numValue);
                } catch (NumberFormatException e) {
                    throw new XPathException("Invalid xs:" + localName + ": " + value);
                }
            case "boolean":
                String trimmed = value.trim();
                if ("true".equals(trimmed) || "1".equals(trimmed)) {
                    return XPathBoolean.TRUE;
                } else if ("false".equals(trimmed) || "0".equals(trimmed)) {
                    return XPathBoolean.FALSE;
                } else {
                    throw new XPathException("Invalid xs:boolean: " + value);
                }
            case "anyURI":
                return XPathString.of(value);  // Just return as string for now
            case "QName":
                return XPathString.of(value);  // Just return as string for now
            case "base64Binary":
                // Validate base64 encoding
                String b64 = value.replaceAll("\\s+", "");  // Remove whitespace
                if (!isValidBase64(b64)) {
                    throw new XPathException("Invalid xs:base64Binary: " + value);
                }
                return XPathString.of(b64);
            case "hexBinary":
                // Validate hex encoding (must be even number of hex digits)
                String hex = value.trim();
                if (!isValidHexBinary(hex)) {
                    throw new XPathException("Invalid xs:hexBinary: " + value);
                }
                return XPathString.of(hex.toUpperCase());
            case "normalizedString":
            case "token":
            case "language":
            case "NMTOKEN":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "ENTITY":
            case "untypedAtomic":
                return XPathString.of(value);
            default:
                // For other types, return as string (basic support)
                return XPathString.of(value);
        }
    }

    // Cache for memoized functions (XSLT 3.0 cache="yes")
    // Key: function key + stringified arguments, Value: cached result
    private static final Map<String, XPathValue> functionCache = new ConcurrentHashMap<>();
    
    /**
     * Invokes a user-defined function.
     *
     * @param function the user-defined function to invoke
     * @param args the function arguments
     * @param context the transformation context
     * @return the function result as a Result Tree Fragment
     * @throws XPathException if function execution fails
     */
    private XPathValue invokeUserFunction(UserFunction function, List<XPathValue> args, 
                                          TransformContext context) throws XPathException {
        // Check for cached result if caching is enabled
        String cacheKey = null;
        if (function.isCached()) {
            cacheKey = buildCacheKey(function, args);
            XPathValue cached = functionCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        
        // Create new variable scope for function execution
        TransformContext funcContext = context.pushVariableScope();
        
        // Bind parameters to arguments
        List<UserFunction.FunctionParameter> params = function.getParameters();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            funcContext.getVariableScope().bind(params.get(i).getName(), args.get(i));
        }
        
        // Execute the function body
        try {
            SAXEventBuffer resultBuffer = new SAXEventBuffer();
            BufferOutputHandler output = new BufferOutputHandler(resultBuffer);
            function.getBody().execute(funcContext, output);
            
            // Return the result as an RTF (Result Tree Fragment)
            XPathValue result = new XPathResultTreeFragment(resultBuffer);
            
            // Cache the result if caching is enabled
            if (cacheKey != null) {
                functionCache.put(cacheKey, result);
            }
            
            return result;
        } catch (org.xml.sax.SAXException e) {
            throw new XPathException("Error executing function " + 
                function.getLocalName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Builds a cache key for a function call (for memoized functions).
     *
     * @param function the function being called
     * @param args the function arguments
     * @return a cache key string
     */
    private String buildCacheKey(UserFunction function, List<XPathValue> args) {
        StringBuilder sb = new StringBuilder();
        sb.append(function.getKey());
        for (XPathValue arg : args) {
            sb.append("|");
            sb.append(arg != null ? arg.asString() : "null");
        }
        return sb.toString();
    }
    
    /**
     * Validates a base64Binary string.
     *
     * @param s the string to validate
     * @return true if valid base64
     */
    private static boolean isValidBase64(String s) {
        if (s.isEmpty()) {
            return true;  // Empty is valid
        }
        // Base64 characters: A-Z, a-z, 0-9, +, /, =
        // Length must be divisible by 4 (with padding)
        int len = s.length();
        if (len % 4 != 0) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (i >= len - 2 && c == '=') {
                continue;  // Padding
            }
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || 
                  (c >= '0' && c <= '9') || c == '+' || c == '/')) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Validates a hexBinary string.
     *
     * @param s the string to validate
     * @return true if valid hexadecimal
     */
    private static boolean isValidHexBinary(String s) {
        if (s.isEmpty()) {
            return true;  // Empty is valid
        }
        // Must be even length and all hex digits
        if (s.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the fixed argument count for a function, or -1 if variable.
     *
     * @param namespaceURI the namespace URI, or null/empty for built-in functions
     * @param localName the local name of the function
     * @return the fixed argument count, or -1 if the function has variable arity
     */
    @Override
    public int getArgumentCount(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return -1; // Variable args for most XSLT functions
            }
        }
        // User-defined functions have variable arity (checked at invoke time)
        return CoreFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
    }

    // ========================================================================
    // XSLT Function Implementations
    // ========================================================================

    /**
     * XSLT current() function.
     * 
     * <p>Returns the XSLT current node being processed. Unlike the context node,
     * the current node remains constant during predicate evaluation.
     * 
     * <p>Signature: current() → node-set
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-current">XSLT 1.0 current()</a>
     */
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

    /**
     * XSLT key() function.
     * 
     * <p>Returns nodes matching a key definition. The key name is resolved to a namespace URI
     * if it contains a prefix. Searches the document tree for nodes matching the key pattern
     * and use expression.
     * 
     * <p>Signature: key(string, object) → node-set
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-key">XSLT 1.0 key()</a>
     */
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
            
            // Expand key name to Clark notation (resolve prefix to URI)
            // This ensures key('bar:foo', ...) finds key defined as baz:foo 
            // when both prefixes map to the same namespace URI
            String expandedName = expandKeyName(keyName, context);
            
            KeyDefinition keyDef = stylesheet.getKeyDefinition(expandedName);
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
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                collectKeyMatches(children.next(), matchPattern, useExpr, 
                                  searchValues, result, context);
            }
        }
        
        /**
         * Expands a key name to Clark notation {uri}localname.
         * This resolves namespace prefixes using the XPath context.
         */
        private String expandKeyName(String keyName, XPathContext context) {
            if (keyName == null) {
                return null;
            }
            int colon = keyName.indexOf(':');
            if (colon > 0) {
                String prefix = keyName.substring(0, colon);
                String localPart = keyName.substring(colon + 1);
                String uri = context.resolveNamespacePrefix(prefix);
                if (uri != null && !uri.isEmpty()) {
                    return "{" + uri + "}" + localPart;
                }
            }
            return keyName;
        }
    }

    /**
     * XSLT document() function.
     * 
     * <p>Loads an external XML document from a URI. If the URI is empty, returns the
     * stylesheet document. Results are cached so the same URI always returns the same document.
     * 
     * <p>Signature: document(object, node-set?) → node-set
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-document">XSLT 1.0 document()</a>
     */
    private static class DocumentFunction implements Function {
        @Override
        public String getName() { return "document"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue uriArg = args.get(0);
            
            // Handle empty argument
            if (uriArg == null) {
                return XPathNodeSet.empty();
            }
            
            // Get base URI from second argument, or static base URI (from stylesheet)
            String baseUri = null;
            if (args.size() > 1 && args.get(1) != null) {
                XPathValue baseArg = args.get(1);
                if (baseArg.isNodeSet()) {
                    // Use the base URI from the node if it has one
                    XPathNodeSet baseNodes = (XPathNodeSet) baseArg;
                    if (!baseNodes.isEmpty()) {
                        XPathNode baseNode = baseNodes.first();
                        if (baseNode instanceof XPathNodeWithBaseURI) {
                            baseUri = ((XPathNodeWithBaseURI) baseNode).getBaseURI();
                        }
                        if (baseUri == null) {
                            baseUri = baseNode.getStringValue();
                        }
                    }
                } else {
                    baseUri = baseArg.asString();
                }
            }
            
            // If no explicit base URI, try the static base URI (from stylesheet)
            if (baseUri == null || baseUri.isEmpty()) {
                baseUri = context.getStaticBaseURI();
            }
            
            // Handle node-set argument (document each node's string value)
            if (uriArg.isNodeSet()) {
                XPathNodeSet uriNodes = (XPathNodeSet) uriArg;
                List<XPathNode> results = new ArrayList<>();
                for (XPathNode node : uriNodes) {
                    String uri = node.getStringValue();
                    // For node-set argument, use base URI from node if available
                    String nodeBaseUri = baseUri;
                    if ((nodeBaseUri == null || nodeBaseUri.isEmpty()) && node instanceof XPathNodeWithBaseURI) {
                        nodeBaseUri = ((XPathNodeWithBaseURI) node).getBaseURI();
                    }
                    XPathNode doc = loadDocument(uri, nodeBaseUri);
                    if (doc != null) {
                        results.add(doc);
                    }
                }
                return new XPathNodeSet(results);
            }
            
            // Handle string argument
            String uri = uriArg.asString();
            if (uri.isEmpty()) {
                // Empty string returns the stylesheet document containing the document() call
                // Per XSLT spec, document('') returns the document node of the stylesheet module
                String stylesheetUri = context.getStaticBaseURI();
                if (stylesheetUri != null && !stylesheetUri.isEmpty()) {
                    XPathNode doc = loadDocument(stylesheetUri, null);
                    if (doc != null) {
                        return new XPathNodeSet(Collections.singletonList(doc));
                    }
                }
                // Fallback: return empty if stylesheet URI not available
                return XPathNodeSet.empty();
            }
            
            XPathNode doc = loadDocument(uri, baseUri);
            if (doc != null) {
                return new XPathNodeSet(Collections.singletonList(doc));
            }
            return XPathNodeSet.empty();
        }
    }
    
    /**
     * XPath 2.0 doc() function.
     * 
     * <p>Loads an external XML document from a URI. Unlike document(), this function
     * throws an error if the document cannot be loaded.
     * 
     * <p>Signature: doc(string?) → document-node?
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-doc">XPath 3.0 doc()</a>
     */
    private static class DocFunction implements Function {
        @Override
        public String getName() { return "doc"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue uriArg = args.get(0);
            
            // Handle empty sequence
            if (uriArg == null || (uriArg instanceof XPathSequence && ((XPathSequence)uriArg).isEmpty())) {
                return XPathSequence.EMPTY;
            }
            
            String uri = uriArg.asString();
            if (uri.isEmpty()) {
                throw new XPathException("FODC0002: Empty URI passed to doc()");
            }
            
            // Get base URI from static context (stylesheet base URI)
            String baseUri = context.getStaticBaseURI();
            
            XPathNode doc = loadDocument(uri, baseUri);
            if (doc == null) {
                throw new XPathException("FODC0002: Cannot retrieve document at " + uri);
            }
            return new XPathNodeSet(Collections.singletonList(doc));
        }
    }
    
    // Cache for loaded documents (same document URI should return same tree)
    private static final Map<String, XPathNode> documentCache = new ConcurrentHashMap<>();
    
    /**
     * Loads an XML document from a URI and returns its document node.
     * Results are cached so the same URI always returns the same document.
     *
     * @param uri the document URI (may be relative)
     * @param baseUri the base URI for resolving relative URIs
     * @return the document node, or null if loading fails
     */
    private static XPathNode loadDocument(String uri, String baseUri) {
        try {
            // Resolve the URI against the base URI
            URI resolved;
            if (baseUri != null && !baseUri.isEmpty()) {
                URI base = new URI(baseUri);
                resolved = base.resolve(uri);
            } else {
                resolved = new URI(uri);
            }
            
            String absoluteUri = resolved.toString();
            
            // Check cache
            XPathNode cached = documentCache.get(absoluteUri);
            if (cached != null) {
                return cached;
            }
            
            // Parse the document
            URL url = resolved.toURL();
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            
            DocumentTreeBuilder builder = new DocumentTreeBuilder(absoluteUri);
            try (InputStream in = url.openStream()) {
                InputSource source = new InputSource(in);
                source.setSystemId(absoluteUri);
                parser.parse(source, builder);
            }
            
            XPathNode root = builder.getRoot();
            
            // Cache the result
            if (root != null) {
                documentCache.put(absoluteUri, root);
            }
            
            return root;
        } catch (Exception e) {
            // Return null on any error (caller decides whether to throw)
            return null;
        }
    }
    
    /**
     * SAX handler that builds a navigable node tree from a parsed XML document.
     */
    private static class DocumentTreeBuilder extends DefaultHandler {
        private final String baseUri;
        private DocumentNode root;
        private DocumentNode current;
        private StringBuilder textBuffer = new StringBuilder();
        private int documentOrder = 0;
        
        DocumentTreeBuilder(String baseUri) {
            this.baseUri = baseUri;
        }
        
        XPathNode getRoot() {
            return root;
        }
        
        @Override
        public void startDocument() {
            root = new DocumentNode(NodeType.ROOT, null, null, null, baseUri);
            root.documentOrder = documentOrder++;
            current = root;
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            flushText();
            DocumentNode element = new DocumentNode(NodeType.ELEMENT, uri, localName, 
                qName.contains(":") ? qName.substring(0, qName.indexOf(':')) : null, baseUri);
            element.documentOrder = documentOrder++;
            element.parent = current;
            if (current != null) {
                current.addChild(element);
            }
            
            // Add attributes
            for (int i = 0; i < attrs.getLength(); i++) {
                DocumentNode attr = new DocumentNode(NodeType.ATTRIBUTE, 
                    attrs.getURI(i), attrs.getLocalName(i), null, baseUri);
                attr.documentOrder = documentOrder++;
                attr.value = attrs.getValue(i);
                attr.parent = element;
                element.addAttribute(attr);
            }
            
            current = element;
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            if (current != null && current.parent != null) {
                current = current.parent;
            }
        }
        
        @Override
        public void characters(char[] ch, int start, int length) {
            textBuffer.append(ch, start, length);
        }
        
        @Override
        public void processingInstruction(String target, String data) {
            flushText();
            DocumentNode pi = new DocumentNode(NodeType.PROCESSING_INSTRUCTION, null, target, null, baseUri);
            pi.documentOrder = documentOrder++;
            pi.value = data;
            pi.parent = current;
            if (current != null) {
                current.addChild(pi);
            }
        }
        
        // Note: comment() is from LexicalHandler, not DefaultHandler
        // For now we don't handle comments in loaded documents
        public void handleComment(char[] ch, int start, int length) {
            flushText();
            DocumentNode comment = new DocumentNode(NodeType.COMMENT, null, null, null, baseUri);
            comment.documentOrder = documentOrder++;
            comment.value = new String(ch, start, length);
            comment.parent = current;
            if (current != null) {
                current.addChild(comment);
            }
        }
        
        private void flushText() {
            if (textBuffer.length() > 0) {
                DocumentNode text = new DocumentNode(NodeType.TEXT, null, null, null, baseUri);
                text.documentOrder = documentOrder++;
                text.value = textBuffer.toString();
                text.parent = current;
                if (current != null) {
                    current.addChild(text);
                }
                textBuffer.setLength(0);
            }
        }
    }
    
    /**
     * Node implementation for loaded documents.
     */
    private static class DocumentNode implements XPathNodeWithBaseURI {
        final NodeType type;
        final String namespaceURI;
        final String localName;
        final String prefix;
        final String baseUri;
        String value;
        DocumentNode parent;
        List<DocumentNode> children;
        List<DocumentNode> attributes;
        long documentOrder;
        
        DocumentNode(NodeType type, String namespaceURI, String localName, String prefix, String baseUri) {
            this.type = type;
            this.namespaceURI = namespaceURI;
            this.localName = localName;
            this.prefix = prefix;
            this.baseUri = baseUri;
        }
        
        void addChild(DocumentNode child) {
            if (children == null) {
                children = new ArrayList<>();
            }
            children.add(child);
        }
        
        void addAttribute(DocumentNode attr) {
            if (attributes == null) {
                attributes = new ArrayList<>();
            }
            attributes.add(attr);
        }
        
        @Override public NodeType getNodeType() { return type; }
        @Override public String getNamespaceURI() { return namespaceURI; }
        @Override public String getLocalName() { return localName; }
        @Override public String getPrefix() { return prefix; }
        
        @Override
        public String getStringValue() {
            if (type == NodeType.ELEMENT || type == NodeType.ROOT) {
                StringBuilder sb = new StringBuilder();
                collectText(this, sb);
                return sb.toString();
            }
            return value != null ? value : "";
        }
        
        private void collectText(DocumentNode node, StringBuilder sb) {
            if (node.type == NodeType.TEXT) {
                sb.append(node.value);
            }
            if (node.children != null) {
                for (DocumentNode child : node.children) {
                    collectText(child, sb);
                }
            }
        }
        
        @Override public XPathNode getParent() { return parent; }
        @Override public long getDocumentOrder() { return documentOrder; }
        @Override public boolean isFullyNavigable() { return true; }
        
        @Override
        public Iterator<XPathNode> getChildren() {
            if (children == null) {
                return Collections.<XPathNode>emptyList().iterator();
            }
            return new ArrayList<XPathNode>(children).iterator();
        }
        
        @Override
        public Iterator<XPathNode> getAttributes() {
            if (attributes == null) {
                return Collections.<XPathNode>emptyList().iterator();
            }
            return new ArrayList<XPathNode>(attributes).iterator();
        }
        
        @Override
        public Iterator<XPathNode> getNamespaces() {
            return Collections.<XPathNode>emptyList().iterator();
        }
        
        @Override
        public XPathNode getFollowingSibling() {
            if (parent == null || parent.children == null) {
                return null;
            }
            int idx = parent.children.indexOf(this);
            if (idx >= 0 && idx < parent.children.size() - 1) {
                return parent.children.get(idx + 1);
            }
            return null;
        }
        
        @Override
        public XPathNode getPrecedingSibling() {
            if (parent == null || parent.children == null) {
                return null;
            }
            int idx = parent.children.indexOf(this);
            if (idx > 0) {
                return parent.children.get(idx - 1);
            }
            return null;
        }
        
        @Override
        public XPathNode getAttribute(String namespaceURI, String localName) {
            if (attributes != null) {
                for (DocumentNode attr : attributes) {
                    String attrUri = attr.namespaceURI != null ? attr.namespaceURI : "";
                    String testUri = namespaceURI != null ? namespaceURI : "";
                    if (attrUri.equals(testUri) && attr.localName.equals(localName)) {
                        return attr;
                    }
                }
            }
            return null;
        }
        
        @Override
        public XPathNode getRoot() {
            DocumentNode n = this;
            while (n.parent != null) {
                n = n.parent;
            }
            return n;
        }
        
        @Override
        public boolean isSameNode(XPathNode other) {
            return this == other;
        }
        
        /**
         * Returns the base URI of this node.
         */
        public String getBaseURI() {
            return baseUri;
        }
        
        /**
         * Returns the document URI (only for ROOT nodes).
         */
        public String getDocumentURI() {
            if (type == NodeType.ROOT) {
                return baseUri;  // Document URI is same as base URI for loaded docs
            }
            return null;
        }
    }

    /**
     * XSLT format-number() function.
     * 
     * <p>Formats a number according to a format pattern string. Supports custom decimal formats
     * defined via xsl:decimal-format.
     * 
     * <p>Signature: format-number(number, string, string?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-format-number">XSLT 1.0 format-number()</a>
     */
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
                DecimalFormatSymbols symbols = new DecimalFormatSymbols();
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
                
                DecimalFormat df = new DecimalFormat(javaPattern, symbols);
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

    /**
     * XSLT generate-id() function.
     * 
     * <p>Generates a unique identifier for a node. The ID is based on the node's document order.
     * 
     * <p>Signature: generate-id(node-set?) → string
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-generate-id">XSLT 1.0 generate-id()</a>
     */
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

    /**
     * XSLT system-property() function.
     * 
     * <p>Returns XSLT processor properties such as xsl:version, xsl:vendor, etc.
     * 
     * <p>Signature: system-property(string) → object
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-system-property">XSLT 1.0 system-property()</a>
     */
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
                // Per XSLT spec, this returns the version of XSLT implemented by the processor
                // We support XSLT 3.0 features (though incomplete), so report 3.0
                return XPathString.of("3.0");
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

    /**
     * XSLT element-available() function.
     * 
     * <p>Tests if an XSLT element is available in the processor.
     * 
     * <p>Signature: element-available(string) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-element-available">XSLT 1.0 element-available()</a>
     */
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

    /**
     * XSLT function-available() function.
     * 
     * <p>Tests if a function is available in the processor.
     * 
     * <p>Signature: function-available(string) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-function-available">XSLT 1.0 function-available()</a>
     */
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

    /**
     * XSLT 2.0 type-available() function.
     * 
     * <p>Tests if a schema type is available (imported via xsl:import-schema).
     * 
     * <p>Signature: type-available(string) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xslt20/#function-type-available">XSLT 2.0 type-available()</a>
     */
    private static class TypeAvailableFunction implements Function {
        // Built-in XSD types that are always available (even without import-schema)
        private static final Set<String> BUILTIN_TYPES = new HashSet<>(
            Arrays.asList(
                // Primitive types
                "anyType", "anySimpleType", "anyAtomicType",
                "string", "boolean", "decimal", "float", "double",
                "duration", "dateTime", "time", "date",
                "gYearMonth", "gYear", "gMonthDay", "gDay", "gMonth",
                "hexBinary", "base64Binary", "anyURI", "QName", "NOTATION",
                // Derived string types
                "normalizedString", "token", "language", "NMTOKEN", "NMTOKENS",
                "Name", "NCName", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES",
                // Derived numeric types
                "integer", "nonPositiveInteger", "negativeInteger",
                "long", "int", "short", "byte",
                "nonNegativeInteger", "unsignedLong", "unsignedInt", 
                "unsignedShort", "unsignedByte", "positiveInteger",
                // XPath 2.0 types
                "untypedAtomic", "untyped",
                "yearMonthDuration", "dayTimeDuration"
            )
        );
        
        @Override
        public String getName() { return "type-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String qname = args.get(0).asString();
            
            // Parse QName - extract local name and check namespace
            String localName;
            String prefix;
            int colonPos = qname.indexOf(':');
            if (colonPos > 0) {
                prefix = qname.substring(0, colonPos);
                localName = qname.substring(colonPos + 1);
            } else {
                prefix = null;
                localName = qname;
            }
            
            // Check if it's an xs: prefixed type
            if ("xs".equals(prefix) || "xsd".equals(prefix)) {
                return BUILTIN_TYPES.contains(localName) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
            }
            
            // No prefix or unknown prefix - check if we have schema imports
            // For now, without import-schema support, return false for non-xs types
            if (prefix != null) {
                return XPathBoolean.FALSE;
            }
            
            // Unprefixed - check if it's a known built-in type
            return BUILTIN_TYPES.contains(localName) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
        }
    }

    /**
     * XSLT unparsed-entity-uri() function.
     * 
     * <p>Returns the URI of an unparsed entity declared in the DTD.
     * 
     * <p>Signature: unparsed-entity-uri(string) → string
     * 
     * @see <a href="https://www.w3.org/TR/xslt/#function-unparsed-entity-uri">XSLT 1.0 unparsed-entity-uri()</a>
     */
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
