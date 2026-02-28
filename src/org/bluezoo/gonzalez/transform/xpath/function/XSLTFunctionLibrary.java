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
import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.SchemaContext;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeWithBaseURI;
import org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.expr.PartialFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAnyURI;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathTypeException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;

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

    private static final Map<String, java.util.regex.Pattern> regexCache = new HashMap<>();

    private final Map<String, Function> xsltFunctions;

    private XSLTFunctionLibrary() {
        Map<String, Function> map = new HashMap<>();
        
        map.put("current", new CurrentFunction());
        map.put("key", new KeyFunction());
        map.put("document", new DocumentFunction());
        map.put("doc", new DocFunction());
        map.put("doc-available", new DocAvailableFunction());
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
        
        // XSLT 3.0 merge functions
        map.put("current-merge-group", new CurrentMergeGroupFunction());
        map.put("current-merge-key", new CurrentMergeKeyFunction());
        
        // XSLT 2.0 analyze-string function
        map.put("regex-group", new RegexGroupFunction());
        
        // XSLT 2.0 unparsed-text functions
        map.put("unparsed-text", new UnparsedTextFunction());
        map.put("unparsed-text-available", new UnparsedTextAvailableFunction());
        
        // XPath 3.0 analyze-string function
        map.put("analyze-string", new AnalyzeStringFunction());
        
        // XSLT 3.0 snapshot function
        map.put("snapshot", new SnapshotFunction());
        
        // XPath 3.1 JSON functions
        map.put("json-to-xml", new JsonToXmlFunction());
        map.put("xml-to-json", new XmlToJsonFunction());
        map.put("parse-json", new ParseJsonFunction());
        
        // XPath 3.0 node-set functions
        map.put("outermost", new OutermostFunction());
        map.put("innermost", new InnermostFunction());
        
        // XPath 3.0/XSLT 3.0 utility functions
        map.put("stream-available", new StreamAvailableFunction());
        map.put("available-system-properties", new AvailableSystemPropertiesFunction());
        map.put("current-output-uri", new CurrentOutputUriFunction());
        map.put("unparsed-text-lines", new UnparsedTextLinesFunction());
        map.put("parse-xml", new ParseXmlFunction());
        map.put("parse-xml-fragment", new ParseXmlFragmentFunction());
        
        // XPath 3.0 higher-order functions
        map.put("fold-left", new FoldLeftFunction());
        map.put("fold-right", new FoldRightFunction());
        map.put("for-each", new ForEachFunction());
        map.put("filter", new FilterFunction());
        map.put("for-each-pair", new ForEachPairFunction());
        map.put("sort", new SortFunction());
        
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
    private static final String FN_NAMESPACE = "http://www.w3.org/2005/xpath-functions";
    private static final String MATH_NAMESPACE = "http://www.w3.org/2005/xpath-functions/math";
    private static final String MAP_NAMESPACE = "http://www.w3.org/2005/xpath-functions/map";
    private static final String ARRAY_NAMESPACE = "http://www.w3.org/2005/xpath-functions/array";
    private static final int IO_BUFFER_SIZE = 8192;
    
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
        // First check built-in XSLT functions (no namespace or fn: namespace)
        if (namespaceURI == null || namespaceURI.isEmpty() || FN_NAMESPACE.equals(namespaceURI)) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return f.evaluate(args, context);
            }
            // Also try core functions with fn: namespace
            if (FN_NAMESPACE.equals(namespaceURI)) {
                return CoreFunctionLibrary.INSTANCE.invokeFunction(null, localName, args, context);
            }
        }
        
        // Check for XML Schema constructor functions (xs:date, xs:dateTime, etc.)
        if (XS_NAMESPACE.equals(namespaceURI)) {
            return invokeXsConstructor(localName, args);
        }
        
        // Check for XPath 3.0 math functions (math:sin, math:cos, etc.)
        if (MATH_NAMESPACE.equals(namespaceURI)) {
            return invokeMathFunction(localName, args);
        }
        
        // Check for XPath 3.1 map functions
        if (MAP_NAMESPACE.equals(namespaceURI)) {
            return invokeMapFunction(localName, args, context);
        }
        
        // Check for XPath 3.1 array functions
        if (ARRAY_NAMESPACE.equals(namespaceURI)) {
            return invokeArrayFunction(localName, args, context);
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
    /**
     * Invokes an XPath 3.0 math function (math:sin, math:cos, etc.).
     *
     * @param localName the local name of the function (e.g., "sin", "cos")
     * @param args the function arguments
     * @return the function result
     * @throws XPathException if the function is unknown or invocation fails
     */
    private XPathValue invokeMathFunction(String localName, List<XPathValue> args) throws XPathException {
        switch (localName) {
            case "pi":
                return XPathNumber.of(Math.PI);
            case "e":
                return XPathNumber.of(Math.E);
            case "sqrt":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.sqrt(args.get(0).asNumber()));
            case "sin":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.sin(args.get(0).asNumber()));
            case "cos":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.cos(args.get(0).asNumber()));
            case "tan":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.tan(args.get(0).asNumber()));
            case "asin":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.asin(args.get(0).asNumber()));
            case "acos":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.acos(args.get(0).asNumber()));
            case "atan":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.atan(args.get(0).asNumber()));
            case "atan2":
                checkMathArgs(localName, args, 2);
                return XPathNumber.of(Math.atan2(args.get(0).asNumber(), args.get(1).asNumber()));
            case "exp":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.exp(args.get(0).asNumber()));
            case "exp10":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.pow(10, args.get(0).asNumber()));
            case "log":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.log(args.get(0).asNumber()));
            case "log10":
                checkMathArgs(localName, args, 1);
                return XPathNumber.of(Math.log10(args.get(0).asNumber()));
            case "pow":
            case "power":
                checkMathArgs(localName, args, 2);
                return XPathNumber.of(Math.pow(args.get(0).asNumber(), args.get(1).asNumber()));
            default:
                throw new XPathException("Unknown math function: math:" + localName);
        }
    }
    
    private void checkMathArgs(String func, List<XPathValue> args, int required) throws XPathException {
        if (args.size() < required) {
            throw new XPathException("math:" + func + " requires " + required + " argument(s)");
        }
        // Handle empty sequence argument
        if (args.get(0) == null || 
            (args.get(0) instanceof XPathSequence && ((XPathSequence)args.get(0)).isEmpty())) {
            // Return empty sequence for empty input
        }
    }

    /**
     * Invokes an XPath 3.1 map function.
     */
    private XPathValue invokeMapFunction(String localName, List<XPathValue> args,
            XPathContext context) throws XPathException {
        switch (localName) {
            case "size":
                return mapSize(args);
            case "keys":
                return mapKeys(args);
            case "contains":
                return mapContains(args);
            case "get":
                return mapGet(args);
            case "put":
                return mapPut(args);
            case "remove":
                return mapRemove(args);
            case "entry":
                return mapEntry(args);
            case "merge":
                return mapMerge(args);
            case "find":
                return mapFind(args);
            case "for-each":
                return mapForEach(args, context);
            default:
                throw new XPathException("Unknown map function: map:" + localName);
        }
    }

    private XPathMap requireMap(List<XPathValue> args, String funcName) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("map:" + funcName + " requires a map argument");
        }
        XPathValue first = args.get(0);
        if (first instanceof XPathMap) {
            return (XPathMap) first;
        }
        throw new XPathException("map:" + funcName + ": first argument is not a map");
    }

    private XPathValue mapSize(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "size");
        return XPathNumber.of(map.size());
    }

    private XPathValue mapKeys(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "keys");
        List<XPathValue> keys = map.keys();
        if (keys.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        return new XPathSequence(keys);
    }

    private XPathValue mapContains(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "contains");
        if (args.size() < 2) {
            throw new XPathException("map:contains requires 2 arguments");
        }
        String key = args.get(1).asString();
        return XPathBoolean.of(map.containsKey(key));
    }

    private XPathValue mapGet(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "get");
        if (args.size() < 2) {
            throw new XPathException("map:get requires 2 arguments");
        }
        String key = args.get(1).asString();
        XPathValue value = map.get(key);
        if (value == null) {
            return XPathSequence.EMPTY;
        }
        return value;
    }

    private XPathValue mapPut(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "put");
        if (args.size() < 3) {
            throw new XPathException("map:put requires 3 arguments");
        }
        String key = args.get(1).asString();
        XPathValue value = args.get(2);
        return map.put(key, value);
    }

    private XPathValue mapRemove(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "remove");
        if (args.size() < 2) {
            throw new XPathException("map:remove requires 2 arguments");
        }
        String key = args.get(1).asString();
        return map.remove(key);
    }

    private XPathValue mapEntry(List<XPathValue> args) throws XPathException {
        if (args.size() < 2) {
            throw new XPathException("map:entry requires 2 arguments");
        }
        String key = args.get(0).asString();
        XPathValue value = args.get(1);
        Map<String, XPathValue> entries = new LinkedHashMap<String, XPathValue>();
        entries.put(key, value);
        return new XPathMap(entries);
    }

    private XPathValue mapMerge(List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("map:merge requires at least 1 argument");
        }
        Map<String, XPathValue> merged = new LinkedHashMap<String, XPathValue>();
        XPathValue first = args.get(0);
        Iterator<XPathValue> it = first.sequenceIterator();
        while (it.hasNext()) {
            XPathValue item = it.next();
            if (item instanceof XPathMap) {
                XPathMap m = (XPathMap) item;
                for (Map.Entry<String, XPathValue> entry : m.entries()) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new XPathMap(merged);
    }

    private XPathValue mapFind(List<XPathValue> args) throws XPathException {
        if (args.size() < 2) {
            throw new XPathException("map:find requires 2 arguments");
        }
        String key = args.get(1).asString();
        List<XPathValue> found = new ArrayList<XPathValue>();
        mapFindRecursive(args.get(0), key, found);
        if (found.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        return new XPathSequence(found);
    }

    private void mapFindRecursive(XPathValue value, String key, List<XPathValue> found) {
        if (value instanceof XPathMap) {
            XPathMap map = (XPathMap) value;
            XPathValue v = map.get(key);
            if (v != null) {
                found.add(v);
            }
            for (Map.Entry<String, XPathValue> entry : map.entries()) {
                mapFindRecursive(entry.getValue(), key, found);
            }
        } else if (value instanceof XPathSequence) {
            Iterator<XPathValue> it = value.sequenceIterator();
            while (it.hasNext()) {
                mapFindRecursive(it.next(), key, found);
            }
        }
    }

    /**
     * map:for-each($map, $action) - applies $action to each key-value pair.
     * The $action function receives (key, value) and returns item()*.
     * Results are concatenated into a single sequence.
     */
    private XPathValue mapForEach(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathMap map = requireMap(args, "for-each");
        if (args.size() < 2) {
            throw new XPathException("map:for-each requires 2 arguments");
        }
        XPathValue funcItem = args.get(1);

        List<XPathValue> results = new ArrayList<XPathValue>();
        for (Map.Entry<String, XPathValue> entry : map.entries()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(XPathString.of(entry.getKey()));
            callArgs.add(entry.getValue());
            XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "map:for-each");
            if (result instanceof XPathSequence) {
                for (XPathValue v : (XPathSequence) result) {
                    results.add(v);
                }
            } else {
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        if (results.size() == 1) {
            return results.get(0);
        }
        return new XPathSequence(results);
    }

    /**
     * Invokes an XPath 3.1 array function.
     */
    private XPathValue invokeArrayFunction(String localName, List<XPathValue> args,
            XPathContext context) throws XPathException {
        switch (localName) {
            case "size":
                return arraySize(args);
            case "get":
                return arrayGet(args);
            case "put":
                return arrayPut(args);
            case "append":
                return arrayAppend(args);
            case "subarray":
                return arraySubarray(args);
            case "remove":
                return arrayRemove(args);
            case "insert-before":
                return arrayInsertBefore(args);
            case "head":
                return arrayHead(args);
            case "tail":
                return arrayTail(args);
            case "reverse":
                return arrayReverse(args);
            case "join":
                return arrayJoin(args);
            case "flatten":
                return arrayFlatten(args);
            case "sort":
                return arraySort(args, context);
            case "for-each":
                return arrayForEach(args, context);
            case "filter":
                return arrayFilter(args, context);
            case "fold-left":
                return arrayFoldLeft(args, context);
            case "fold-right":
                return arrayFoldRight(args, context);
            case "for-each-pair":
                return arrayForEachPair(args, context);
            default:
                throw new XPathException("Unknown array function: array:" + localName);
        }
    }

    private XPathArray requireArray(List<XPathValue> args, String funcName) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("array:" + funcName + " requires an array argument");
        }
        XPathValue first = args.get(0);
        if (first instanceof XPathArray) {
            return (XPathArray) first;
        }
        throw new XPathException("array:" + funcName + ": first argument is not an array");
    }

    /** array:size($array) - number of members. */
    private XPathValue arraySize(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "size");
        return XPathNumber.of(array.size());
    }

    /** array:get($array, $position) - member at 1-based position. */
    private XPathValue arrayGet(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "get");
        if (args.size() < 2) {
            throw new XPathException("array:get requires 2 arguments");
        }
        int pos = (int) args.get(1).asNumber();
        if (pos < 1 || pos > array.size()) {
            throw new XPathException("array:get: index " + pos + " out of bounds (1.." + array.size() + ")");
        }
        return array.get(pos);
    }

    /** array:put($array, $position, $member) - replace member at position. */
    private XPathValue arrayPut(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "put");
        if (args.size() < 3) {
            throw new XPathException("array:put requires 3 arguments");
        }
        int pos = (int) args.get(1).asNumber();
        XPathValue member = args.get(2);
        if (pos < 1 || pos > array.size()) {
            throw new XPathException("array:put: index " + pos + " out of bounds (1.." + array.size() + ")");
        }
        List<XPathValue> members = new ArrayList<XPathValue>(array.members());
        members.set(pos - 1, member);
        return new XPathArray(members);
    }

    /** array:append($array, $appendage) - add member at end. */
    private XPathValue arrayAppend(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "append");
        if (args.size() < 2) {
            throw new XPathException("array:append requires 2 arguments");
        }
        XPathValue appendage = args.get(1);
        List<XPathValue> members = new ArrayList<XPathValue>(array.members());
        members.add(appendage);
        return new XPathArray(members);
    }

    /** array:subarray($array, $start) or array:subarray($array, $start, $length). */
    private XPathValue arraySubarray(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "subarray");
        if (args.size() < 2) {
            throw new XPathException("array:subarray requires at least 2 arguments");
        }
        int start = (int) args.get(1).asNumber();
        if (start < 1 || start > array.size() + 1) {
            throw new XPathException("array:subarray: start " + start + " out of bounds");
        }
        int length;
        if (args.size() >= 3) {
            length = (int) args.get(2).asNumber();
            if (length < 0) {
                throw new XPathException("array:subarray: negative length");
            }
        } else {
            length = array.size() - start + 1;
        }
        int end = start - 1 + length;
        if (end > array.size()) {
            throw new XPathException("array:subarray: start + length exceeds array size");
        }
        List<XPathValue> allMembers = array.members();
        List<XPathValue> sub = new ArrayList<XPathValue>(length);
        for (int i = start - 1; i < end; i++) {
            sub.add(allMembers.get(i));
        }
        return new XPathArray(sub);
    }

    /** array:remove($array, $positions) - remove members at given positions. */
    private XPathValue arrayRemove(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "remove");
        if (args.size() < 2) {
            throw new XPathException("array:remove requires 2 arguments");
        }
        // Positions can be a single integer or a sequence of integers
        java.util.Set<Integer> positions = new java.util.HashSet<Integer>();
        Iterator<XPathValue> posIter = args.get(1).sequenceIterator();
        while (posIter.hasNext()) {
            int pos = (int) posIter.next().asNumber();
            positions.add(Integer.valueOf(pos));
        }
        List<XPathValue> allMembers = array.members();
        List<XPathValue> result = new ArrayList<XPathValue>();
        for (int i = 0; i < allMembers.size(); i++) {
            if (!positions.contains(Integer.valueOf(i + 1))) {
                result.add(allMembers.get(i));
            }
        }
        return new XPathArray(result);
    }

    /** array:insert-before($array, $position, $member) - insert member before position. */
    private XPathValue arrayInsertBefore(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "insert-before");
        if (args.size() < 3) {
            throw new XPathException("array:insert-before requires 3 arguments");
        }
        int pos = (int) args.get(1).asNumber();
        XPathValue member = args.get(2);
        if (pos < 1 || pos > array.size() + 1) {
            throw new XPathException("array:insert-before: index " + pos + " out of bounds");
        }
        List<XPathValue> members = new ArrayList<XPathValue>(array.members());
        members.add(pos - 1, member);
        return new XPathArray(members);
    }

    /** array:head($array) - first member. */
    private XPathValue arrayHead(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "head");
        if (array.size() == 0) {
            throw new XPathException("array:head: empty array");
        }
        return array.get(1);
    }

    /** array:tail($array) - all members except first. */
    private XPathValue arrayTail(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "tail");
        if (array.size() == 0) {
            throw new XPathException("array:tail: empty array");
        }
        List<XPathValue> allMembers = array.members();
        List<XPathValue> tail = new ArrayList<XPathValue>(allMembers.size() - 1);
        for (int i = 1; i < allMembers.size(); i++) {
            tail.add(allMembers.get(i));
        }
        return new XPathArray(tail);
    }

    /** array:reverse($array) - members in reverse order. */
    private XPathValue arrayReverse(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "reverse");
        List<XPathValue> allMembers = array.members();
        List<XPathValue> reversed = new ArrayList<XPathValue>(allMembers.size());
        for (int i = allMembers.size() - 1; i >= 0; i--) {
            reversed.add(allMembers.get(i));
        }
        return new XPathArray(reversed);
    }

    /** array:join($arrays) - concatenate a sequence of arrays. */
    private XPathValue arrayJoin(List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("array:join requires 1 argument");
        }
        List<XPathValue> allMembers = new ArrayList<XPathValue>();
        Iterator<XPathValue> it = args.get(0).sequenceIterator();
        while (it.hasNext()) {
            XPathValue item = it.next();
            if (item instanceof XPathArray) {
                XPathArray arr = (XPathArray) item;
                allMembers.addAll(arr.members());
            } else {
                throw new XPathException("array:join: argument contains a non-array item");
            }
        }
        return new XPathArray(allMembers);
    }

    /** array:flatten($input) - recursively flatten arrays and sequences into a flat sequence. */
    private XPathValue arrayFlatten(List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("array:flatten requires 1 argument");
        }
        List<XPathValue> result = new ArrayList<XPathValue>();
        flattenRecursive(args.get(0), result);
        if (result.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        if (result.size() == 1) {
            return result.get(0);
        }
        return new XPathSequence(result);
    }

    private void flattenRecursive(XPathValue value, List<XPathValue> result) {
        if (value instanceof XPathArray) {
            XPathArray arr = (XPathArray) value;
            for (XPathValue member : arr.members()) {
                flattenRecursive(member, result);
            }
        } else if (value instanceof XPathSequence) {
            Iterator<XPathValue> it = value.sequenceIterator();
            while (it.hasNext()) {
                flattenRecursive(it.next(), result);
            }
        } else {
            result.add(value);
        }
    }

    /** array:sort($array) or array:sort($array, $collation, $key). */
    private XPathValue arraySort(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "sort");
        final List<XPathValue> items = array.members();
        if (items.size() <= 1) {
            return array;
        }

        // Get collation
        final Collation collation;
        if (args.size() >= 2) {
            String collUri = args.get(1).asString();
            if (collUri != null && !collUri.isEmpty()) {
                collation = Collation.forUri(collUri);
            } else {
                String defaultUri = context.getDefaultCollation();
                collation = Collation.forUri(defaultUri);
            }
        } else {
            String defaultUri = context.getDefaultCollation();
            collation = Collation.forUri(defaultUri);
        }

        // Get key function
        final XPathValue keyFunc;
        if (args.size() >= 3) {
            keyFunc = args.get(2);
        } else {
            keyFunc = null;
        }

        // Compute sort keys
        final List<String> keys = new ArrayList<String>(items.size());
        for (int i = 0; i < items.size(); i++) {
            if (keyFunc != null) {
                List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                callArgs.add(items.get(i));
                XPathValue keyVal = invokeFunctionItem(keyFunc, callArgs, context, "array:sort");
                keys.add(keyVal.asString());
            } else {
                keys.add(items.get(i).asString());
            }
        }

        // Sort by keys using collation
        Integer[] indices = new Integer[items.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = Integer.valueOf(i);
        }
        java.util.Arrays.sort(indices, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                String keyA = keys.get(a.intValue());
                String keyB = keys.get(b.intValue());
                return collation.compare(keyA, keyB);
            }
        });

        List<XPathValue> sorted = new ArrayList<XPathValue>(items.size());
        for (int i = 0; i < indices.length; i++) {
            sorted.add(items.get(indices[i].intValue()));
        }
        return new XPathArray(sorted);
    }

    /** array:for-each($array, $function) - apply function to each member. */
    private XPathValue arrayForEach(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "for-each");
        if (args.size() < 2) {
            throw new XPathException("array:for-each requires 2 arguments");
        }
        XPathValue funcItem = args.get(1);
        List<XPathValue> results = new ArrayList<XPathValue>(array.size());
        for (XPathValue member : array.members()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
            callArgs.add(member);
            XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "array:for-each");
            results.add(result);
        }
        return new XPathArray(results);
    }

    /** array:filter($array, $function) - keep members where function returns true. */
    private XPathValue arrayFilter(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "filter");
        if (args.size() < 2) {
            throw new XPathException("array:filter requires 2 arguments");
        }
        XPathValue funcItem = args.get(1);
        List<XPathValue> results = new ArrayList<XPathValue>();
        for (XPathValue member : array.members()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
            callArgs.add(member);
            XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "array:filter");
            if (result.asBoolean()) {
                results.add(member);
            }
        }
        return new XPathArray(results);
    }

    /** array:fold-left($array, $zero, $function) - left fold over members. */
    private XPathValue arrayFoldLeft(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "fold-left");
        if (args.size() < 3) {
            throw new XPathException("array:fold-left requires 3 arguments");
        }
        XPathValue accumulator = args.get(1);
        XPathValue funcItem = args.get(2);
        for (XPathValue member : array.members()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(accumulator);
            callArgs.add(member);
            accumulator = invokeFunctionItem(funcItem, callArgs, context, "array:fold-left");
        }
        return accumulator;
    }

    /** array:fold-right($array, $zero, $function) - right fold over members. */
    private XPathValue arrayFoldRight(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "fold-right");
        if (args.size() < 3) {
            throw new XPathException("array:fold-right requires 3 arguments");
        }
        XPathValue accumulator = args.get(1);
        XPathValue funcItem = args.get(2);
        List<XPathValue> members = array.members();
        for (int i = members.size() - 1; i >= 0; i--) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(members.get(i));
            callArgs.add(accumulator);
            accumulator = invokeFunctionItem(funcItem, callArgs, context, "array:fold-right");
        }
        return accumulator;
    }

    /** array:for-each-pair($array1, $array2, $function) - apply function to corresponding pairs. */
    private XPathValue arrayForEachPair(List<XPathValue> args, XPathContext context)
            throws XPathException {
        if (args.size() < 3) {
            throw new XPathException("array:for-each-pair requires 3 arguments");
        }
        XPathValue first = args.get(0);
        XPathValue second = args.get(1);
        if (!(first instanceof XPathArray)) {
            throw new XPathException("array:for-each-pair: first argument is not an array");
        }
        if (!(second instanceof XPathArray)) {
            throw new XPathException("array:for-each-pair: second argument is not an array");
        }
        XPathArray array1 = (XPathArray) first;
        XPathArray array2 = (XPathArray) second;
        XPathValue funcItem = args.get(2);
        List<XPathValue> members1 = array1.members();
        List<XPathValue> members2 = array2.members();
        int len = Math.min(members1.size(), members2.size());
        List<XPathValue> results = new ArrayList<XPathValue>(len);
        for (int i = 0; i < len; i++) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(members1.get(i));
            callArgs.add(members2.get(i));
            XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "array:for-each-pair");
            results.add(result);
        }
        return new XPathArray(results);
    }

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
        // Per XSLT spec, user-defined functions start with empty tunnel parameter context
        TransformContext funcContext = context.pushVariableScope().withNoTunnelParameters();
        
        // Bind parameters to arguments
        List<UserFunction.FunctionParameter> params = function.getParameters();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            funcContext.getVariableScope().bind(params.get(i).getName(), args.get(i));
        }
        
        // Execute the function body
        try {
            XPathValue result;
            String asType = function.getAsType();
            
            // Check if return type indicates atomic/sequence (not node/RTF)
            // Types like xs:boolean*, xs:integer, item()*, etc. should use sequence construction
            if (asType != null && isAtomicOrSequenceType(asType)) {
                // Use sequence construction mode to preserve item boundaries
                org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler output = 
                    new org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler();
                function.getBody().execute(funcContext, output);
                result = output.getSequence();
                
                // Coerce the result type to match the declared 'as' type (atomize + convert)
                // This replaces validation because coercion includes type checking
                if (asType != null && !asType.isEmpty()) {
                    // Only coerce if not in SILENT mode
                    ErrorHandlingMode errorMode = funcContext instanceof TransformContext ? 
                        ((TransformContext) funcContext).getErrorHandlingMode() : 
                        ErrorHandlingMode.STRICT;
                    
                    if (!errorMode.isSilent()) {
                        try {
                            // Coerce the result to the expected type (atomize + convert)
                            // If coercion succeeds, the result matches the type by definition
                            result = coerceToType(result, asType, funcContext);
                        } catch (XPathTypeException e) {
                            if (errorMode.isRecovery()) {
                                // RECOVER mode: log warning and continue with original result
                                System.err.println("Warning [" + e.getErrorCode() + "]: " + e.getMessage());
                            } else {
                                // STRICT mode: rethrow
                                throw new XPathException(e.getMessage());
                            }
                        }
                    }
                }
            } else {
                // Default: use RTF (Result Tree Fragment) for node/document return types
                SAXEventBuffer resultBuffer = new SAXEventBuffer();
                BufferOutputHandler output = new BufferOutputHandler(resultBuffer);
                function.getBody().execute(funcContext, output);
                result = new XPathResultTreeFragment(resultBuffer);
                
                // TODO: For RTF results with declared types, validate if possible
                // if (asType != null) {
                //     validateFunctionReturnType(result, asType, function.getLocalName());
                // }
            }
            
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
     * Checks if a type annotation indicates an atomic or sequence type
     * (as opposed to a node or document type that should be returned as RTF).
     *
     * <p>Atomic types include xs:string, xs:boolean, xs:integer, xs:decimal, etc.
     * Sequence types include item()*, xs:string*, element()*, etc.
     *
     * @param asType the type annotation (e.g., "xs:boolean*", "item()+")
     * @return true if the type is atomic/sequence and should use sequence construction
     */
    private boolean isAtomicOrSequenceType(String asType) {
        if (asType == null || asType.isEmpty()) {
            // For functions without declared return type, use sequence construction
            // to properly preserve atomic values from xsl:sequence
            return true;
        }
        
        // Remove occurrence indicator (*, +, ?)
        String baseType = asType.replaceAll("[*+?]$", "").trim();
        
        // Check for xs: prefixed atomic types
        if (baseType.startsWith("xs:")) {
            // All xs: types are atomic (string, boolean, integer, decimal, etc.)
            return true;
        }
        
        // Check for item() which can contain atomic values
        if (baseType.equals("item()") || baseType.startsWith("item(")) {
            return true;
        }
        
        // Check for atomic() type (XPath 3.0)
        if (baseType.equals("xs:anyAtomicType") || baseType.contains("atomic")) {
            return true;
        }
        
        // Node types like element(), document-node(), node() should use RTF
        // unless they have occurrence indicators suggesting sequences
        if (asType.endsWith("*") || asType.endsWith("+") || asType.endsWith("?")) {
            // Sequence of nodes - use sequence construction to preserve item boundaries
            // This includes element()*, element()+, element()?, attribute()?, etc.
            return true;
        }
        
        // Default: treat as RTF (node/document type)
        return false;
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
     * @see <a href="https://www.w3.org/TR/xslt20/#function-key">XSLT 2.0 key()</a>
     */
    private static class KeyFunction implements Function {
        @Override
        public String getName() { return "key"; }
        
        @Override
        public int getMinArgs() { return 2; }
        
        @Override
        public int getMaxArgs() { return 3; }  // XSLT 2.0 adds collation parameter
        
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
            
            // Get collation from 3rd argument or use default
            Collation collation;
            if (args.size() > 2) {
                String collUri = args.get(2).asString();
                collation = Collation.forUri(collUri);
            } else {
                String defaultUri = txContext.getDefaultCollation();
                collation = Collation.forUri(defaultUri != null ? defaultUri : Collation.CODEPOINT_URI);
            }
            
            // Validate key name is a valid QName (XTDE1260)
            if (!isValidQName(keyName)) {
                throw new XPathException("XTDE1260: key name is not a valid QName: '" + keyName + "'");
            }
            
            // Expand key name to Clark notation (resolve prefix to URI)
            // This ensures key('bar:foo', ...) finds key defined as baz:foo 
            // when both prefixes map to the same namespace URI
            String expandedName = expandKeyName(keyName, context);
            
            KeyDefinition keyDef = stylesheet.getKeyDefinition(expandedName);
            if (keyDef == null) {
                throw new XPathException("XTDE1260: No xsl:key declaration with name '" + keyName + "'");
            }
            
            // Check for circular reference in key evaluation
            if (txContext instanceof BasicTransformContext) {
                BasicTransformContext btx = (BasicTransformContext) txContext;
                if (btx.isKeyBeingEvaluated(expandedName)) {
                    throw new XPathException("XTDE0640: Circular reference in key: " + keyName);
                }
                btx.startKeyEvaluation(expandedName);
            }
            
            try {
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
                collectKeyMatches(root, matchPattern, useExpr, searchValues, result, txContext, collation);
                
                return new XPathNodeSet(result);
            } finally {
                // Clear the key from being evaluated
                if (txContext instanceof BasicTransformContext) {
                    ((BasicTransformContext) txContext).endKeyEvaluation(expandedName);
                }
            }
        }
        
        /**
         * Recursively collects nodes that match the key definition and have
         * matching key values (using collation for comparison).
         */
        private void collectKeyMatches(XPathNode node, Pattern matchPattern, 
                                       XPathExpression useExpr, List<String> searchValues,
                                       List<XPathNode> result, TransformContext context,
                                       Collation collation) 
                                       throws XPathException {
            BasicTransformContext btx = (BasicTransformContext) context;
            // Check if this node matches the key pattern
            if (matchPattern.matches(node, context)) {
                // Evaluate the use expression with current()=matched node (XSLT 2.0 spec)
                XPathContext nodeContext = btx.withXsltCurrentNode(node);
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
                
                // Check if any of this node's key values match our search values (using collation)
                outer:
                for (String nodeKey : nodeKeyValues) {
                    for (String searchKey : searchValues) {
                        if (collation.equals(nodeKey, searchKey)) {
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
                            break outer;
                        }
                    }
                }
            }
            
            // Check attributes if this is an element (for keys matching @*)
            if (node.isElement()) {
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    if (matchPattern.matches(attr, context)) {
                        // Evaluate the use expression with current()=matched attr (XSLT 2.0 spec)
                        XPathContext attrContext = btx.withXsltCurrentNode(attr);
                        XPathValue useValue = useExpr.evaluate(attrContext);
                        
                        // Get the key value(s) for this attribute
                        List<String> attrKeyValues = new ArrayList<>();
                        if (useValue instanceof XPathNodeSet) {
                            for (XPathNode n : ((XPathNodeSet) useValue).getNodes()) {
                                attrKeyValues.add(n.getStringValue());
                            }
                        } else {
                            attrKeyValues.add(useValue.asString());
                        }
                        
                        // Check if any of this attribute's key values match our search values (using collation)
                        outer:
                        for (String attrKey : attrKeyValues) {
                            for (String searchKey : searchValues) {
                                if (collation.equals(attrKey, searchKey)) {
                                    // Avoid duplicates
                                    boolean found = false;
                                    for (XPathNode existing : result) {
                                        if (existing.isSameNode(attr)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (!found) {
                                        result.add(attr);
                                    }
                                    break outer;
                                }
                            }
                        }
                    }
                }
            }
            
            // Recurse into children
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                collectKeyMatches(children.next(), matchPattern, useExpr, 
                                  searchValues, result, context, collation);
            }
        }
        
        /**
         * Expands a key name to Clark notation {uri}localname.
         * This resolves namespace prefixes using the XPath context.
         */
        private String expandKeyName(String keyName, XPathContext context)
                throws XPathException {
            if (keyName == null) {
                return null;
            }
            int colon = keyName.indexOf(':');
            if (colon > 0) {
                String prefix = keyName.substring(0, colon);
                String localPart = keyName.substring(colon + 1);
                String uri = context.resolveNamespacePrefix(prefix);
                if (uri == null || uri.isEmpty()) {
                    throw new XPathException("XTDE1260: No namespace declaration in scope " +
                        "for prefix '" + prefix + "' in key name '" + keyName + "'");
                }
                return "{" + uri + "}" + localPart;
            }
            return keyName;
        }
        
        private static boolean isValidQName(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            int colon = name.indexOf(':');
            if (colon == 0 || colon == name.length() - 1) {
                return false;
            }
            if (colon > 0) {
                return isValidNCName(name.substring(0, colon))
                    && isValidNCName(name.substring(colon + 1));
            }
            return isValidNCName(name);
        }
        
        private static boolean isValidNCName(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            char first = name.charAt(0);
            if (!isNameStartChar(first)) {
                return false;
            }
            for (int i = 1; i < name.length(); i++) {
                if (!isNameChar(name.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        
        private static boolean isNameStartChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }
        
        private static boolean isNameChar(char c) {
            return isNameStartChar(c) || (c >= '0' && c <= '9') || c == '-' || c == '.';
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
                        // Note: if node doesn't have a base URI, baseUri stays null
                        // and we'll fall back to the static base URI below.
                        // Don't use getStringValue() - that returns text content, not a URI!
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
                    // Get strip-space rules from stylesheet if available
                    List<String> stripSpace = null;
                    List<String> preserveSpace = null;
                    if (context instanceof TransformContext) {
                        CompiledStylesheet stylesheet = ((TransformContext) context).getStylesheet();
                        if (stylesheet != null) {
                            stripSpace = stylesheet.getStripSpaceElements();
                            preserveSpace = stylesheet.getPreserveSpaceElements();
                        }
                    }
                    XPathNode doc = DocumentLoader.loadDocument(uri, nodeBaseUri, stripSpace, preserveSpace);
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
                    // Don't apply strip-space to the stylesheet document itself
                    XPathNode doc = DocumentLoader.loadDocument(stylesheetUri, null, null, null);
                    if (doc != null) {
                        return new XPathNodeSet(Collections.singletonList(doc));
                    }
                }
                // Fallback: return empty if stylesheet URI not available
                return XPathNodeSet.empty();
            }
            
            // Get strip-space rules from stylesheet if available
            List<String> stripSpace = null;
            List<String> preserveSpace = null;
            if (context instanceof TransformContext) {
                CompiledStylesheet stylesheet = ((TransformContext) context).getStylesheet();
                if (stylesheet != null) {
                    stripSpace = stylesheet.getStripSpaceElements();
                    preserveSpace = stylesheet.getPreserveSpaceElements();
                }
            }
            
            XPathNode doc = DocumentLoader.loadDocument(uri, baseUri, stripSpace, preserveSpace);
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
            
            // Get base URI from static context (stylesheet base URI)
            String baseUri = context.getStaticBaseURI();
            
            if (uri.isEmpty()) {
                // Empty string returns the stylesheet document containing the doc() call
                // Per XSLT spec, doc('') behaves like document('') - returns the stylesheet module
                if (baseUri != null && !baseUri.isEmpty()) {
                    // Don't apply strip-space to the stylesheet document itself
                    XPathNode doc = DocumentLoader.loadDocument(baseUri, null, null, null);
                    if (doc != null) {
                        return new XPathNodeSet(Collections.singletonList(doc));
                    }
                }
                throw new XPathException("FODC0002: Cannot determine stylesheet document for doc('')");
            }
            
            // Get strip-space rules from stylesheet if available
            List<String> stripSpace = null;
            List<String> preserveSpace = null;
            if (context instanceof TransformContext) {
                CompiledStylesheet stylesheet = ((TransformContext) context).getStylesheet();
                if (stylesheet != null) {
                    stripSpace = stylesheet.getStripSpaceElements();
                    preserveSpace = stylesheet.getPreserveSpaceElements();
                }
            }
            
            XPathNode doc = DocumentLoader.loadDocument(uri, baseUri, stripSpace, preserveSpace);
            if (doc == null) {
                throw new XPathException("FODC0002: Cannot retrieve document at " + uri);
            }
            return new XPathNodeSet(Collections.singletonList(doc));
        }
    }
    
    /**
     * Implements the XPath 2.0+ doc-available() function.
     *
     * <p>Returns true if a document at the specified URI can be loaded.
     * Returns false (rather than throwing an error) if the document cannot be loaded.
     * 
     * <p>Signature: doc-available(string?) → boolean
     * 
     * @see <a href="https://www.w3.org/TR/xpath-functions-30/#func-doc-available">XPath 3.0 doc-available()</a>
     */
    private static class DocAvailableFunction implements Function {
        @Override
        public String getName() { return "doc-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue uriArg = args.get(0);
            
            // Handle empty sequence - returns false
            if (uriArg == null || (uriArg instanceof XPathSequence && ((XPathSequence)uriArg).isEmpty())) {
                return XPathBoolean.FALSE;
            }
            
            String uri = uriArg.asString();
            
            // Empty string is a special case - check if stylesheet document is available
            if (uri.isEmpty()) {
                String baseUri = context.getStaticBaseURI();
                return XPathBoolean.of(baseUri != null && !baseUri.isEmpty());
            }
            
            // Get base URI from static context
            String baseUri = context.getStaticBaseURI();
            
            // Try to load the document - catch any errors
            XPathNode doc = DocumentLoader.loadDocument(uri, baseUri, null, null);
            return XPathBoolean.of(doc != null);
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
                    // XTDE1280: named decimal-format must be declared
                    if (formatName != null && decFormat == null) {
                        throw new XPathException("XTDE1280: Unknown decimal-format name: '" + formatName + "'");
                    }
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
            
            // Validate pattern before processing (FODF1310)
            validatePattern(pattern, decimalSep, groupingSep, percent, perMille, patternSep, digit, zeroDigit);
            
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
                // FODF1310: Invalid picture string
                throw new XPathException("FODF1310: Invalid format-number picture string: " + pattern + 
                    " - " + e.getMessage());
            }
        }
        
        /**
         * Validates a format-number picture string according to XSLT/XPath rules.
         * Throws FODF1310 for invalid patterns.
         */
        private void validatePattern(String pattern, char decimalSep, char groupingSep,
                char percent, char perMille, char patternSep, char digit, char zeroDigit) throws XPathException {
            // Split into positive and negative sub-patterns
            String[] subPatterns = splitPattern(pattern, patternSep);
            
            // Check for too many sub-patterns (only positive and negative allowed)
            if (subPatterns.length > 2) {
                throw new XPathException("FODF1310: Invalid picture string - too many sub-patterns (found " + 
                    subPatterns.length + " pattern separators)");
            }
            
            // Validate each sub-pattern
            for (String subPattern : subPatterns) {
                validateSubPattern(subPattern, decimalSep, groupingSep, percent, perMille, digit, zeroDigit);
            }
        }
        
        /**
         * Splits a pattern by the pattern separator, but not within quoted literals.
         */
        private String[] splitPattern(String pattern, char patternSep) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;
            
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    current.append(c);
                } else if (c == patternSep && !inQuote) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            parts.add(current.toString());
            return parts.toArray(new String[0]);
        }
        
        /**
         * Validates a single sub-pattern for format-number.
         */
        private void validateSubPattern(String subPattern, char decimalSep, char groupingSep,
                char percent, char perMille, char digit, char zeroDigit) throws XPathException {
            int decimalCount = 0;
            int percentCount = 0;
            int perMilleCount = 0;
            boolean inQuote = false;
            
            // First pass: find the mantissa boundaries (first and last digit positions)
            int firstDigitPos = -1;
            int lastDigitPos = -1;
            for (int i = 0; i < subPattern.length(); i++) {
                char c = subPattern.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    continue;
                }
                if (inQuote) {
                    continue;
                }
                
                if (c == digit || c == zeroDigit) {
                    if (firstDigitPos < 0) firstDigitPos = i;
                    lastDigitPos = i;
                }
            }
            
            // Second pass: validate the pattern
            inQuote = false;
            for (int i = 0; i < subPattern.length(); i++) {
                char c = subPattern.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    continue;
                }
                if (inQuote) {
                    continue; // Ignore characters in quoted literals
                }
                
                boolean inMantissa = (firstDigitPos >= 0 && i >= firstDigitPos && i <= lastDigitPos);
                boolean isDigitChar = (c == digit || c == zeroDigit);
                
                if (c == decimalSep) {
                    decimalCount++;
                    if (decimalCount > 1) {
                        throw new XPathException("FODF1310: Invalid picture string - " +
                            "multiple decimal separators in sub-pattern: " + subPattern);
                    }
                } else if (c == percent) {
                    percentCount++;
                    if (percentCount > 1) {
                        throw new XPathException("FODF1310: Invalid picture string - " +
                            "multiple percent signs in sub-pattern: " + subPattern);
                    }
                } else if (c == perMille) {
                    perMilleCount++;
                    if (perMilleCount > 1) {
                        throw new XPathException("FODF1310: Invalid picture string - " +
                            "multiple per-mille signs in sub-pattern: " + subPattern);
                    }
                } else if (inMantissa && !isDigitChar && c != groupingSep && c != decimalSep) {
                    // Invalid character in mantissa - only digits, grouping sep, and decimal sep allowed
                    // Characters like [, ], $ between digits are invalid
                    throw new XPathException("FODF1310: Invalid picture string - " +
                        "invalid character '" + c + "' in digit pattern: " + subPattern);
                }
            }
            
            // Check for both percent and per-mille
            if (percentCount > 0 && perMilleCount > 0) {
                throw new XPathException("FODF1310: Invalid picture string - " +
                    "cannot have both percent and per-mille in sub-pattern: " + subPattern);
            }
            
            // Must have at least one digit placeholder
            if (firstDigitPos < 0) {
                throw new XPathException("FODF1310: Invalid picture string - " +
                    "no digit placeholder in sub-pattern: " + subPattern);
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
            
            // Translate custom symbols to Java DecimalFormat standard symbols.
            // When custom symbols replace the defaults, the original default characters
            // become literals and must be quoted in the Java pattern.
            StringBuilder sb = new StringBuilder(pattern.length() + 8);
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
                } else if (isInZeroDigitFamily(c, zeroDigit)) {
                    int offset = c - zeroDigit;
                    sb.append((char) ('0' + offset));
                } else if (c == digit) {
                    sb.append('#');
                } else if (c == patternSep) {
                    sb.append(';');
                } else if (isJavaPatternSpecial(c) && !isXsltFormatChar(c, decimalSep, groupingSep, percent, perMille, zeroDigit, digit, patternSep)) {
                    sb.append('\'');
                    sb.append(c);
                    sb.append('\'');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private boolean isInZeroDigitFamily(char c, char zeroDigit) {
            int offset = c - zeroDigit;
            return offset > 0 && offset <= 9;
        }

        private boolean isJavaPatternSpecial(char c) {
            return c == '0' || c == '#' || c == '.' || c == ',' ||
                   c == '%' || c == '\u2030' || c == ';' ||
                   (c >= '1' && c <= '9');
        }

        private boolean isXsltFormatChar(char c, char decimalSep, char groupingSep,
                char percent, char perMille, char zeroDigit, char digit, char patternSep) {
            if (c == decimalSep || c == groupingSep || c == percent || c == perMille ||
                c == zeroDigit || c == digit || c == patternSep) {
                return true;
            }
            int offset = c - zeroDigit;
            return offset > 0 && offset <= 9;
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
            XPathNode root = node.getRoot();
            int docId = System.identityHashCode(root);
            String hex = Integer.toHexString(docId);
            return XPathString.of("d" + hex + "n" + node.getDocumentOrder());
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

            // XSLT 3.0: accept EQName syntax Q{uri}local in addition to QName
            String namespaceURI = null;
            String localName = null;

            if (name.startsWith("Q{")) {
                if (!isValidEQName(name)) {
                    throw new XPathException("XTDE1390: system-property() argument is not a valid EQName: '" + name + "'");
                }
                int closeBrace = name.indexOf('}');
                namespaceURI = name.substring(2, closeBrace);
                localName = name.substring(closeBrace + 1);
            } else {
                if (!isValidQName(name)) {
                    throw new XPathException("XTDE1390: system-property() argument is not a valid QName: '" + name + "'");
                }
                int colon = name.indexOf(':');
                if (colon > 0) {
                    String prefix = name.substring(0, colon);
                    localName = name.substring(colon + 1);
                    if ("xsl".equals(prefix)) {
                        namespaceURI = XSLT_NAMESPACE;
                    } else {
                        namespaceURI = context.resolveNamespacePrefix(prefix);
                        if (namespaceURI == null || namespaceURI.isEmpty()) {
                            throw new XPathException("XTDE1390: No namespace declaration in scope for prefix '" +
                                prefix + "' in system-property argument '" + name + "'");
                        }
                    }
                } else {
                    localName = name;
                }
            }

            return lookupSystemProperty(namespaceURI, localName, context);
        }

        private static final String XSLT_NAMESPACE = "http://www.w3.org/1999/XSL/Transform";

        private XPathValue lookupSystemProperty(String namespaceURI, String localName,
                XPathContext context) throws XPathException {
            if (XSLT_NAMESPACE.equals(namespaceURI)) {
                if ("version".equals(localName)) {
                    return XPathString.of("3.0");
                }
                if ("vendor".equals(localName)) {
                    return XPathString.of("Gonzalez XSLT");
                }
                if ("vendor-url".equals(localName)) {
                    return XPathString.of("https://www.nongnu.org/gonzalez/");
                }
                if ("product-name".equals(localName)) {
                    return XPathString.of("Gonzalez");
                }
                if ("product-version".equals(localName)) {
                    return XPathString.of("1.1");
                }
                if ("is-schema-aware".equals(localName)) {
                    return XPathString.of("no");
                }
                if ("supports-serialization".equals(localName)) {
                    return XPathString.of("yes");
                }
                if ("supports-backwards-compatibility".equals(localName)) {
                    return XPathString.of("yes");
                }
                if ("supports-namespace-axis".equals(localName)) {
                    return XPathString.of("yes");
                }
                if ("supports-streaming".equals(localName)) {
                    return XPathString.of("no");
                }
                if ("supports-dynamic-evaluation".equals(localName)) {
                    return XPathString.of("no");
                }
                if ("xpath-version".equals(localName)) {
                    return XPathString.of("3.1");
                }
                if ("xsd-version".equals(localName)) {
                    return XPathString.of("1.1");
                }
                if ("package-name".equals(localName) || "package-version".equals(localName)) {
                    if (context instanceof org.bluezoo.gonzalez.transform.runtime.TransformContext) {
                        org.bluezoo.gonzalez.transform.runtime.TransformContext tc =
                            (org.bluezoo.gonzalez.transform.runtime.TransformContext) context;
                        org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet stylesheet =
                            tc.getStylesheet();
                        if (stylesheet != null) {
                            if ("package-name".equals(localName)) {
                                String pkgName = stylesheet.getPackageName();
                                return XPathString.of(pkgName != null ? pkgName : "");
                            } else {
                                String pkgVersion = stylesheet.getPackageVersion();
                                return XPathString.of(pkgVersion != null ? pkgVersion : "");
                            }
                        }
                    }
                    return XPathString.of("");
                }
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
            
            // XTDE1400: argument must be a valid EQName
            if (!isValidQName(name) && !isValidEQName(name)) {
                throw new XPathException("XTDE1400: function-available() argument is not a valid EQName: '" + name + "'");
            }
            
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
            
            // Resolve the prefix to a namespace URI
            String namespaceURI = null;
            if (prefix != null) {
                namespaceURI = context.resolveNamespacePrefix(prefix);
            }

            // Check if it's in the XML Schema namespace
            if (XS_NAMESPACE.equals(namespaceURI)) {
                return BUILTIN_TYPES.contains(localName) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
            }

            // No prefix - check if it's a known built-in type
            if (prefix == null) {
                return BUILTIN_TYPES.contains(localName) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
            }

            // Non-XSD namespace - would need schema imports to check
            return XPathBoolean.FALSE;
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
    // XSLT 3.0 Merge Functions
    // ========================================================================

    /**
     * current-merge-group() - Returns all items in the current merge group.
     *
     * <p>Within xsl:merge-action, this returns all items from all merge sources
     * that have the current merge key value.
     *
     * <p>current-merge-group('name') returns only items from the named source.
     */
    private static class CurrentMergeGroupFunction implements Function {
        @Override
        public String getName() { return "current-merge-group"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            if (args.isEmpty()) {
                // Return all items in current merge group
                XPathValue group = context.getVariable(null, "__current_merge_group__");
                if (group != null) {
                    return group;
                }
            } else {
                // Return items from specific source
                String sourceName = args.get(0).asString();
                XPathValue group = context.getVariable(null, "__current_merge_group_" + sourceName + "__");
                if (group != null) {
                    return group;
                }
            }
            // Return empty sequence if not in merge context
            return XPathNodeSet.empty();
        }
    }

    /**
     * current-merge-key() - Returns the current merge key value.
     *
     * <p>Within xsl:merge-action, this returns the key value that is common
     * to all items in the current merge group.
     */
    private static class CurrentMergeKeyFunction implements Function {
        @Override
        public String getName() { return "current-merge-key"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue key = context.getVariable(null, "__current_merge_key__");
            if (key != null) {
                return key;
            }
            // Return empty string if not in merge context
            return XPathString.of("");
        }
    }

    // ========================================================================
    // XSLT 2.0 analyze-string Functions
    // ========================================================================

    /**
     * regex-group(n) - Returns the captured group from the current regex match.
     *
     * <p>This function is used inside xsl:matching-substring within xsl:analyze-string
     * to access captured groups from the regular expression match.
     *
     * <p>regex-group(0) returns the entire matched string.
     * regex-group(1) returns the first captured group, etc.
     */
    private static class RegexGroupFunction implements Function {
        @Override
        public String getName() { return "regex-group"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            int groupNum = (int) args.get(0).asNumber();
            
            // Get the regex matcher from context
            if (context instanceof TransformContext) {
                java.util.regex.Matcher matcher = ((TransformContext) context).getRegexMatcher();
                if (matcher != null) {
                    int groupCount = matcher.groupCount();
                    if (groupNum >= 0 && groupNum <= groupCount) {
                        try {
                            String group = matcher.group(groupNum);
                            if (group == null) {
                                group = "";
                            }
                            return XPathString.of(group);
                        } catch (IllegalStateException e) {
                            // No match operation has been performed yet
                        }
                    }
                }
            }
            
            // No match context or invalid group number - return empty string
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
    
    /**
     * Coerces a function return value to match the declared type in the 'as' attribute.
     * This performs atomization and type conversion according to XSLT 2.0+ rules.
     *
     * @param result the function result
     * @param asType the declared return type string (e.g., "xs:double", "xs:integer?")
     * @param context the XPath context
     * @return the coerced value
     * @throws XPathException if coercion fails
     */
    private XPathValue coerceToType(XPathValue result, String asType, XPathContext context)
            throws XPathException {
        if (asType == null || asType.isEmpty() || result == null) {
            return result;
        }
        
        try {
            // Parse the asType string into a SequenceType
            SequenceType expectedType = parseAsType(asType);
            
            // If the expected type is atomic, try to convert from text/string
            if (expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                String typeLocalName = expectedType.getLocalName();
                String typeNsUri = expectedType.getNamespaceURI();
                
                if (typeNsUri != null && typeNsUri.equals(SequenceType.XS_NAMESPACE)) {
                    // Try to atomize and convert the result
                    // This handles: XPathString, SequenceTextItem, XPathSequence with single string item
                    String stringValue = result.asString();  // Get string value
                    XPathValue converted = convertStringToAtomicType(stringValue, typeLocalName);
                    if (converted != null) {
                        return converted;
                    }
                }
            }
            
            // Check if the result (after any conversion) matches the expected type
            // Get schema context if available
            SchemaContext schemaContext = (context instanceof SchemaContext) ? 
                (SchemaContext) context : SchemaContext.NONE;
            
            // If it matches, return it; otherwise throw an error
            if (expectedType.matches(result, schemaContext)) {
                return result;
            } else {
                throw new XPathTypeException(
                    "XTTE0505",
                    "Function returned value does not match declared type '" + asType + "'",
                    asType,
                    result != null ? result.getClass().getSimpleName() : "null"
                );
            }
            
        } catch (XPathTypeException e) {
            throw e;  // Re-throw XPathTypeException as-is
        } catch (Exception e) {
            throw new XPathTypeException(
                "XTTE0505",
                "Cannot coerce function result to type '" + asType + "': " + e.getMessage(),
                asType,
                result != null ? result.getClass().getSimpleName() : "null"
            );
        }
    }
    
    /**
     * Converts a string value to an XPath atomic type.
     */
    private XPathValue convertStringToAtomicType(String value, String typeName) throws XPathException {
        try {
            switch (typeName) {
                case "string":
                    return new XPathString(value);
                    
                case "double":
                case "decimal":
                case "float":
                    return new XPathNumber(Double.parseDouble(value));
                    
                case "integer":
                case "int":
                case "long":
                case "short":
                case "byte":
                case "nonNegativeInteger":
                case "nonPositiveInteger":
                case "positiveInteger":
                case "negativeInteger":
                case "unsignedInt":
                case "unsignedLong":
                case "unsignedShort":
                case "unsignedByte":
                    // Parse as integer (XPathNumber internally uses double)
                    double num = Double.parseDouble(value);
                    return new XPathNumber(Math.floor(num));
                    
                case "boolean":
                    return XPathBoolean.of("true".equals(value) || "1".equals(value));
                    
                case "anyURI":
                    return new XPathAnyURI(value);
                    
                case "date":
                    return XPathDateTime.parseDate(value);
                case "dateTime":
                    return XPathDateTime.parseDateTime(value);
                case "time":
                    return XPathDateTime.parseTime(value);
                case "duration":
                    return XPathDateTime.parseDuration(value);
                case "yearMonthDuration":
                    return XPathDateTime.parseYearMonthDuration(value);
                case "dayTimeDuration":
                    return XPathDateTime.parseDayTimeDuration(value);
                case "gYear":
                    return XPathDateTime.parseGYear(value);
                case "gMonth":
                    return XPathDateTime.parseGMonth(value);
                case "gDay":
                    return XPathDateTime.parseGDay(value);
                case "gYearMonth":
                    return XPathDateTime.parseGYearMonth(value);
                case "gMonthDay":
                    return XPathDateTime.parseGMonthDay(value);
                    
                case "QName":
                case "NOTATION":
                case "normalizedString":
                case "token":
                case "language":
                case "NMTOKEN":
                case "Name":
                case "NCName":
                case "ID":
                case "IDREF":
                case "ENTITY":
                case "hexBinary":
                case "base64Binary":
                    // These are all string-based types
                    return new XPathString(value);
                    
                default:
                    // Unknown type - return string as-is
                    return new XPathString(value);
            }
        } catch (NumberFormatException e) {
            throw new XPathException("Cannot convert '" + value + "' to " + typeName + ": " + e.getMessage());
        }
    }
    
    /**
     * Validates that a function's return value matches its declared 'as' type.
     * Throws XTTE0505 if the type doesn't match.
     *
     * @param result the actual return value
     * @param asType the declared type string (e.g., "xs:integer", "element()*")
     * @param functionName the function name (for error messages)
     * @param context the XPath context (for schema access)
     * @throws XPathException if the type doesn't match (XTTE0505)
     */
    private void validateFunctionReturnType(XPathValue result, String asType, String functionName,
                                           XPathContext context) 
            throws XPathException {
        if (asType == null || asType.isEmpty()) {
            return;  // No declared type, no validation needed
        }
        
        try {
            // Parse the asType string into a SequenceType
            SequenceType expectedType = parseAsType(asType);
            
            // Get schema context if available (context is passed as parameter)
            SchemaContext schemaContext = (context instanceof SchemaContext) ? 
                (SchemaContext) context : SchemaContext.NONE;
            
            // Check if the result matches the expected type
            if (!expectedType.matches(result, schemaContext)) {
                throw new XPathTypeException(
                    "XTTE0505",
                    "Function " + functionName + " returned value does not match declared type '" + 
                    asType + "'",
                    asType,
                    result != null ? result.getClass().getSimpleName() : "null"
                );
            }
        } catch (XPathTypeException e) {
            throw e;  // Re-throw type exceptions as-is
        } catch (Exception e) {
            // If we can't parse the type, log but don't fail
            // (This handles edge cases with complex schema types)
            System.err.println("Warning: Could not validate return type '" + asType + 
                             "' for function " + functionName + ": " + e.getMessage());
        }
    }
    
    /**
     * Parses an 'as' type string into a SequenceType.
     * Handles common patterns like "xs:integer", "element()*", "item()+", etc.
     *
     * @param asType the type string
     * @return the parsed SequenceType
     * @throws XPathException if the type cannot be parsed
     */
    private SequenceType parseAsType(String asType) throws XPathException {
        if (asType == null || asType.isEmpty()) {
            return SequenceType.ITEM;
        }
        
        // Determine occurrence indicator
        SequenceType.Occurrence occ = SequenceType.Occurrence.ONE;
        String baseType = asType;
        
        if (asType.endsWith("*")) {
            occ = SequenceType.Occurrence.ZERO_OR_MORE;
            baseType = asType.substring(0, asType.length() - 1).trim();
        } else if (asType.endsWith("+")) {
            occ = SequenceType.Occurrence.ONE_OR_MORE;
            baseType = asType.substring(0, asType.length() - 1).trim();
        } else if (asType.endsWith("?")) {
            occ = SequenceType.Occurrence.ZERO_OR_ONE;
            baseType = asType.substring(0, asType.length() - 1).trim();
        }
        
        // Handle empty-sequence()
        if (baseType.equals("empty-sequence()")) {
            return SequenceType.EMPTY;
        }
        
        // Handle item()
        if (baseType.equals("item()")) {
            return new SequenceType(SequenceType.ItemKind.ITEM, null, null, null, occ);
        }
        
        // Handle node()
        if (baseType.equals("node()")) {
            return new SequenceType(SequenceType.ItemKind.NODE, null, null, null, occ);
        }
        
        // Handle element()
        if (baseType.equals("element()") || baseType.startsWith("element(")) {
            return new SequenceType(SequenceType.ItemKind.ELEMENT, null, null, null, occ);
        }
        
        // Handle attribute()
        if (baseType.equals("attribute()") || baseType.startsWith("attribute(")) {
            return new SequenceType(SequenceType.ItemKind.ATTRIBUTE, null, null, null, occ);
        }
        
        // Handle text()
        if (baseType.equals("text()")) {
            return new SequenceType(SequenceType.ItemKind.TEXT, null, null, null, occ);
        }
        
        // Handle comment()
        if (baseType.equals("comment()")) {
            return new SequenceType(SequenceType.ItemKind.COMMENT, null, null, null, occ);
        }
        
        // Handle processing-instruction()
        if (baseType.equals("processing-instruction()") || baseType.startsWith("processing-instruction(")) {
            return new SequenceType(SequenceType.ItemKind.PROCESSING_INSTRUCTION, null, null, null, occ);
        }
        
        // Handle document-node()
        if (baseType.equals("document-node()") || baseType.startsWith("document-node(")) {
            return new SequenceType(SequenceType.ItemKind.DOCUMENT_NODE, null, null, null, occ);
        }
        
        // Handle atomic types (xs:integer, xs:string, etc.)
        if (baseType.startsWith("xs:") || baseType.startsWith("xsd:")) {
            String localName = baseType.substring(baseType.indexOf(':') + 1);
            return SequenceType.atomic(SequenceType.XS_NAMESPACE, localName, occ);
        }
        
        // Default: treat as atomic type in XS namespace
        return SequenceType.atomic(SequenceType.XS_NAMESPACE, baseType, occ);
    }

    // ========================================================================
    // XSLT 2.0 unparsed-text Functions
    // ========================================================================

    /**
     * unparsed-text(href, encoding?) - Reads the contents of a text file.
     */
    private static class UnparsedTextFunction implements Function {
        @Override
        public String getName() { return "unparsed-text"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String href = args.get(0).asString();
            String encoding = args.size() > 1 ? args.get(1).asString() : "UTF-8";
            
            if (href == null || href.isEmpty()) {
                throw new XPathException("FOUT1170: href argument to unparsed-text is empty");
            }
            
            try {
                // Resolve relative URI against static base URI
                String baseUri = context.getStaticBaseURI();
                java.net.URI uri;
                if (baseUri != null && !baseUri.isEmpty()) {
                    uri = new java.net.URI(baseUri).resolve(href);
                } else {
                    uri = new java.net.URI(href);
                }
                
                // Read the file
                java.nio.charset.Charset charset = java.nio.charset.Charset.forName(encoding);
                java.nio.file.Path path;
                if ("file".equals(uri.getScheme())) {
                    path = java.nio.file.Paths.get(uri);
                } else if (uri.getScheme() == null) {
                    // Relative path without scheme
                    path = java.nio.file.Paths.get(href);
                } else {
                    // For http/https, read from URL
                    java.io.InputStream is = uri.toURL().openStream();
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[IO_BUFFER_SIZE];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    is.close();
                    return XPathString.of(new String(baos.toByteArray(), charset));
                }
                
                byte[] bytes = java.nio.file.Files.readAllBytes(path);
                String content = new String(bytes, charset);
                return XPathString.of(content);
            } catch (URISyntaxException e) {
                throw new XPathException("FOUT1170: Invalid URI: " + href);
            } catch (java.nio.charset.UnsupportedCharsetException e) {
                throw new XPathException("FOUT1190: Unsupported encoding: " + encoding);
            } catch (IOException e) {
                throw new XPathException("FOUT1170: Cannot read resource: " + href + " - " + e.getMessage());
            }
        }
    }

    /**
     * unparsed-text-available(href, encoding?) - Tests if a text file can be read.
     */
    private static class UnparsedTextAvailableFunction implements Function {
        @Override
        public String getName() { return "unparsed-text-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String href = args.get(0).asString();
            String encoding = args.size() > 1 ? args.get(1).asString() : "UTF-8";
            
            if (href == null || href.isEmpty()) {
                return XPathBoolean.FALSE;
            }
            
            try {
                // Check if encoding is valid
                java.nio.charset.Charset.forName(encoding);
                
                // Resolve relative URI against static base URI
                String baseUri = context.getStaticBaseURI();
                java.net.URI uri;
                if (baseUri != null && !baseUri.isEmpty()) {
                    uri = new java.net.URI(baseUri).resolve(href);
                } else {
                    uri = new java.net.URI(href);
                }
                
                // Check if file exists
                java.nio.file.Path path;
                if ("file".equals(uri.getScheme())) {
                    path = java.nio.file.Paths.get(uri);
                    return XPathBoolean.of(java.nio.file.Files.exists(path) && 
                                           java.nio.file.Files.isReadable(path));
                } else if (uri.getScheme() == null) {
                    path = java.nio.file.Paths.get(href);
                    return XPathBoolean.of(java.nio.file.Files.exists(path) && 
                                           java.nio.file.Files.isReadable(path));
                } else {
                    // For http/https, try to open connection
                    try {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
                        conn.setRequestMethod("HEAD");
                        int responseCode = conn.getResponseCode();
                        conn.disconnect();
                        boolean success = responseCode >= 200 && responseCode < 300;
                        return XPathBoolean.of(success);
                    } catch (IOException e) {
                        return XPathBoolean.FALSE;
                    } catch (ClassCastException e) {
                        return XPathBoolean.FALSE;
                    }
                }
            } catch (URISyntaxException e) {
                return XPathBoolean.FALSE;
            } catch (InvalidPathException e) {
                return XPathBoolean.FALSE;
            } catch (SecurityException e) {
                return XPathBoolean.FALSE;
            }
        }
    }

    // ========================================================================
    // XPath 3.0 analyze-string Function
    // ========================================================================

    /**
     * analyze-string($input, $pattern) - Analyzes a string using a regular expression.
     * Returns an XML representation of the match results.
     */
    private static class AnalyzeStringFunction implements Function {
        @Override
        public String getName() { return "analyze-string"; }
        
        @Override
        public int getMinArgs() { return 2; }
        
        @Override
        public int getMaxArgs() { return 3; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String input = args.get(0) != null ? args.get(0).asString() : "";
            String pattern = args.get(1).asString();
            String flags = args.size() > 2 ? args.get(2).asString() : "";
            
            // Build regex flags
            int regexFlags = 0;
            if (flags.contains("i")) regexFlags |= java.util.regex.Pattern.CASE_INSENSITIVE;
            if (flags.contains("m")) regexFlags |= java.util.regex.Pattern.MULTILINE;
            if (flags.contains("s")) regexFlags |= java.util.regex.Pattern.DOTALL;
            if (flags.contains("x")) regexFlags |= java.util.regex.Pattern.COMMENTS;
            
            try {
                String cacheKey = pattern + "\0" + regexFlags;
                java.util.regex.Pattern p = regexCache.get(cacheKey);
                if (p == null) {
                    p = java.util.regex.Pattern.compile(pattern, regexFlags);
                    regexCache.put(cacheKey, p);
                }
                java.util.regex.Matcher m = p.matcher(input);
                
                // Build result XML as string (simplified - full implementation needs RTF)
                StringBuilder xml = new StringBuilder();
                xml.append("<fn:analyze-string-result xmlns:fn=\"http://www.w3.org/2005/xpath-functions\">");
                
                int lastEnd = 0;
                while (m.find()) {
                    // Non-matching text before this match
                    if (m.start() > lastEnd) {
                        xml.append("<fn:non-match>");
                        xml.append(escapeXml(input.substring(lastEnd, m.start())));
                        xml.append("</fn:non-match>");
                    }
                    // The match
                    xml.append("<fn:match>");
                    if (m.groupCount() > 0) {
                        // Include groups
                        for (int i = 1; i <= m.groupCount(); i++) {
                            String group = m.group(i);
                            if (group != null) {
                                xml.append("<fn:group nr=\"").append(i).append("\">");
                                xml.append(escapeXml(group));
                                xml.append("</fn:group>");
                            }
                        }
                    } else {
                        xml.append(escapeXml(m.group()));
                    }
                    xml.append("</fn:match>");
                    lastEnd = m.end();
                }
                // Remaining non-matching text
                if (lastEnd < input.length()) {
                    xml.append("<fn:non-match>");
                    xml.append(escapeXml(input.substring(lastEnd)));
                    xml.append("</fn:non-match>");
                }
                
                xml.append("</fn:analyze-string-result>");
                
                // Parse and return as document node
                // For now, return as string - full implementation needs document parsing
                return XPathString.of(xml.toString());
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new XPathException("FORX0002: Invalid regular expression: " + pattern);
            }
        }
        
        private String escapeXml(String s) {
            return s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }

    // ========================================================================
    // XSLT 3.0 snapshot Function
    // ========================================================================

    /**
     * snapshot($node) - Returns a deep copy of a node with ancestor information preserved.
     */
    private static class SnapshotFunction implements Function {
        @Override
        public String getName() { return "snapshot"; }
        
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
                if (arg instanceof XPathNode) {
                    node = (XPathNode) arg;
                } else if (arg instanceof XPathNodeSet) {
                    java.util.Iterator<XPathNode> iter = ((XPathNodeSet) arg).iterator();
                    if (!iter.hasNext()) {
                        return XPathSequence.EMPTY;
                    }
                    node = iter.next();
                } else {
                    throw new XPathException("Argument to snapshot() must be a node");
                }
            }
            
            if (node == null) {
                return XPathSequence.EMPTY;
            }
            // For fully navigable nodes, return as-is (already has all axes)
            if (node.isFullyNavigable()) {
                return XPathNodeSet.of(node);
            }
            // For streaming nodes, return as-is -- the streaming handler ensures
            // the node state is captured at the point of call
            return XPathNodeSet.of(node);
        }
    }

    // ========================================================================
    // XPath 3.1 JSON Functions
    // ========================================================================

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
                if (nodes != null && !nodes.isEmpty()) {
                    node = nodes.get(0);
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
            try {
                String result = JsonXmlConverter.xmlToJson(node);
                return XPathString.of(result);
            } catch (org.xml.sax.SAXException e) {
                throw new XPathException(e.getMessage());
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
            // Full implementation would need proper JSON parsing and XDM map/array support
            // For now, return the string
            return XPathString.of(json);
        }
    }

    // ---- Shared QName/EQName validation helpers ----

    static boolean isValidQName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int colon = name.indexOf(':');
        if (colon == 0 || colon == name.length() - 1) {
            return false;
        }
        if (colon > 0) {
            return isValidNCName(name.substring(0, colon))
                && isValidNCName(name.substring(colon + 1));
        }
        return isValidNCName(name);
    }

    static boolean isValidNCName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!isXmlNameStartChar(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isXmlNameChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidEQName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        // EQName: Q{uri}local or NCName or prefix:local
        if (name.startsWith("Q{")) {
            int closeBrace = name.indexOf('}');
            if (closeBrace < 0 || closeBrace == name.length() - 1) {
                return false;
            }
            return isValidNCName(name.substring(closeBrace + 1));
        }
        return isValidQName(name);
    }

    // ========================================================================
    // XSLT 3.0 current-output-uri() Function
    // ========================================================================

    /**
     * current-output-uri() - Returns the URI of the current output destination.
     */
    private static class CurrentOutputUriFunction implements Function {
        @Override
        public String getName() { return "current-output-uri"; }

        @Override
        public int getMinArgs() { return 0; }

        @Override
        public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String outputUri = context.getStaticBaseURI();
            if (outputUri != null) {
                return XPathString.of(outputUri);
            }
            return XPathString.of("");
        }
    }

    // ========================================================================
    // XPath 3.0 unparsed-text-lines() Function
    // ========================================================================

    /**
     * unparsed-text-lines($href) - Returns the lines of a text file as a sequence of strings.
     */
    private static class UnparsedTextLinesFunction implements Function {
        @Override
        public String getName() { return "unparsed-text-lines"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String href = args.get(0).asString();
            if (href == null || href.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            String encoding = null;
            if (args.size() > 1 && args.get(1) != null) {
                encoding = args.get(1).asString();
            }
            
            String baseUri = context.getStaticBaseURI();
            
            try {
                String resolvedHref = DocumentLoader.resolveUri(href, baseUri);
                URL url = new URL(resolvedHref);
                InputStream in = url.openStream();
                byte[] data = readAllBytes(in);
                in.close();
                
                String charset = encoding != null ? encoding : "UTF-8";
                String text = new String(data, charset);
                
                String[] lines = text.split("\n", -1);
                List<XPathValue> result = new ArrayList<XPathValue>();
                for (String line : lines) {
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    result.add(XPathString.of(line));
                }
                return new XPathSequence(result);
            } catch (Exception e) {
                throw new XPathException("FOUT1170: Error reading " + href + ": " + e.getMessage());
            }
        }
        
        private byte[] readAllBytes(InputStream in) throws IOException {
            List<byte[]> chunks = new ArrayList<byte[]>();
            int totalLen = 0;
            byte[] buf = new byte[IO_BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) >= 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                chunks.add(chunk);
                totalLen += n;
            }
            byte[] result = new byte[totalLen];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }

    // ========================================================================
    // XPath 3.0 parse-xml() / parse-xml-fragment() Functions
    // ========================================================================

    /**
     * parse-xml($arg) - Parses an XML string and returns a document node.
     */
    private static class ParseXmlFunction implements Function {
        @Override
        public String getName() { return "parse-xml"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null) {
                return XPathSequence.EMPTY;
            }
            String xml = arg.asString();
            if (xml == null || xml.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            try {
                XPathNode doc = DocumentLoader.loadDocumentFromString(xml, null, null, null);
                return XPathNodeSet.of(doc);
            } catch (SAXException e) {
                throw new XPathException("FODC0006: parse-xml failed: " + e.getMessage());
            }
        }
    }

    /**
     * parse-xml-fragment($arg) - Parses an XML fragment and returns a document node.
     */
    private static class ParseXmlFragmentFunction implements Function {
        @Override
        public String getName() { return "parse-xml-fragment"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null) {
                return XPathSequence.EMPTY;
            }
            String xml = arg.asString();
            if (xml == null || xml.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            
            // Wrap in a root element to make it well-formed
            String wrapped = "<wrapper>" + xml + "</wrapper>";
            try {
                XPathNode doc = DocumentLoader.loadDocumentFromString(wrapped, null, null, null);
                return XPathNodeSet.of(doc);
            } catch (SAXException e) {
                throw new XPathException("FODC0006: parse-xml-fragment failed: " + e.getMessage());
            }
        }
    }

    private static boolean isXmlNameStartChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isXmlNameChar(char c) {
        return isXmlNameStartChar(c) || (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    // ========================================================================
    // XPath 3.0 outermost() / innermost() Functions
    // ========================================================================

    /**
     * outermost($nodes) - Returns nodes that have no ancestor in the input set.
     * Nodes are returned in document order.
     */
    private static class OutermostFunction implements Function {
        @Override
        public String getName() { return "outermost"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || (arg.isNodeSet() && ((XPathNodeSet) arg).isEmpty())) {
                return XPathNodeSet.empty();
            }
            if (!arg.isNodeSet()) {
                throw new XPathException("outermost() argument must be a node-set");
            }
            XPathNodeSet ns = (XPathNodeSet) arg;
            List<XPathNode> nodes = ns.getNodes();
            Set<XPathNode> nodeSet = new HashSet<XPathNode>(nodes);
            List<XPathNode> result = new ArrayList<XPathNode>();
            for (XPathNode node : nodes) {
                if (!hasAncestorInSet(node, nodeSet)) {
                    result.add(node);
                }
            }
            return new XPathNodeSet(result);
        }

        private boolean hasAncestorInSet(XPathNode node, Set<XPathNode> nodeSet) {
            XPathNode parent = node.getParent();
            while (parent != null) {
                if (nodeSet.contains(parent)) {
                    return true;
                }
                parent = parent.getParent();
            }
            return false;
        }
    }

    /**
     * innermost($nodes) - Returns nodes that have no descendant in the input set.
     * Nodes are returned in document order.
     */
    private static class InnermostFunction implements Function {
        @Override
        public String getName() { return "innermost"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null || (arg.isNodeSet() && ((XPathNodeSet) arg).isEmpty())) {
                return XPathNodeSet.empty();
            }
            if (!arg.isNodeSet()) {
                throw new XPathException("innermost() argument must be a node-set");
            }
            XPathNodeSet ns = (XPathNodeSet) arg;
            List<XPathNode> nodes = ns.getNodes();
            Set<XPathNode> nodeSet = new HashSet<XPathNode>(nodes);
            List<XPathNode> result = new ArrayList<XPathNode>();
            for (XPathNode node : nodes) {
                if (!hasDescendantInSet(node, nodeSet)) {
                    result.add(node);
                }
            }
            return new XPathNodeSet(result);
        }

        private boolean hasDescendantInSet(XPathNode node, Set<XPathNode> nodeSet) {
            for (XPathNode other : nodeSet) {
                if (other == node) {
                    continue;
                }
                XPathNode ancestor = other.getParent();
                while (ancestor != null) {
                    if (ancestor == node) {
                        return true;
                    }
                    ancestor = ancestor.getParent();
                }
            }
            return false;
        }
    }

    // ========================================================================
    // XSLT 3.0 stream-available() Function
    // ========================================================================

    /**
     * stream-available($uri) - Returns true if streaming is available for the URI.
     */
    private static class StreamAvailableFunction implements Function {
        @Override
        public String getName() { return "stream-available"; }

        @Override
        public int getMinArgs() { return 1; }

        @Override
        public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null) {
                return XPathBoolean.FALSE;
            }
            String uri = arg.asString();
            if (uri == null || uri.isEmpty()) {
                return XPathBoolean.FALSE;
            }
            return XPathBoolean.TRUE;
        }
    }

    // ========================================================================
    // XSLT 3.0 available-system-properties() Function
    // ========================================================================

    /**
     * available-system-properties() - Returns the QNames of available system properties.
     */
    private static class AvailableSystemPropertiesFunction implements Function {
        @Override
        public String getName() { return "available-system-properties"; }

        @Override
        public int getMinArgs() { return 0; }

        @Override
        public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String ns = "http://www.w3.org/1999/XSL/Transform";
            String prefix = "xsl";
            List<XPathValue> props = new ArrayList<XPathValue>();
            props.add(XPathQName.of(ns, prefix, "version"));
            props.add(XPathQName.of(ns, prefix, "vendor"));
            props.add(XPathQName.of(ns, prefix, "vendor-url"));
            props.add(XPathQName.of(ns, prefix, "product-name"));
            props.add(XPathQName.of(ns, prefix, "product-version"));
            props.add(XPathQName.of(ns, prefix, "is-schema-aware"));
            props.add(XPathQName.of(ns, prefix, "supports-serialization"));
            props.add(XPathQName.of(ns, prefix, "supports-backwards-compatibility"));
            props.add(XPathQName.of(ns, prefix, "supports-namespace-axis"));
            props.add(XPathQName.of(ns, prefix, "supports-streaming"));
            props.add(XPathQName.of(ns, prefix, "supports-dynamic-evaluation"));
            props.add(XPathQName.of(ns, prefix, "xpath-version"));
            props.add(XPathQName.of(ns, prefix, "xsd-version"));
            return new XPathSequence(props);
        }
    }

    /**
     * XPath 3.0 fn:fold-left($seq, $zero, $f) — processes items left to right.
     * Applies function $f to accumulator and each item: $f($accumulator, $item).
     */
    private static class FoldLeftFunction implements Function {
        @Override
        public String getName() { return "fold-left"; }
        @Override
        public int getMinArgs() { return 3; }
        @Override
        public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue accumulator = args.get(1);
            XPathValue funcItem = args.get(2);

            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
                callArgs.add(accumulator);
                callArgs.add(item);
                accumulator = invokeFunctionItem(funcItem, callArgs, context, "fold-left");
            }
            return accumulator;
        }
    }

    /**
     * XPath 3.0 fn:fold-right($seq, $zero, $f) — processes items right to left.
     * Applies function $f to each item and accumulator: $f($item, $accumulator).
     */
    private static class FoldRightFunction implements Function {
        @Override
        public String getName() { return "fold-right"; }
        @Override
        public int getMinArgs() { return 3; }
        @Override
        public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue accumulator = args.get(1);
            XPathValue funcItem = args.get(2);

            // Collect items to process right-to-left
            List<XPathValue> items = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                items.add(it.next());
            }
            for (int i = items.size() - 1; i >= 0; i--) {
                List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
                callArgs.add(items.get(i));
                callArgs.add(accumulator);
                accumulator = invokeFunctionItem(funcItem, callArgs, context, "fold-right");
            }
            return accumulator;
        }
    }

    /**
     * Invokes a function item value, supporting all function item types.
     */
    static XPathValue invokeFunctionItem(XPathValue funcItem, List<XPathValue> args,
            XPathContext context, String callerName) throws XPathException {
        if (funcItem instanceof InlineFunctionItem) {
            return ((InlineFunctionItem) funcItem).invoke(args, context);
        }
        if (funcItem instanceof PartialFunctionItem) {
            return ((PartialFunctionItem) funcItem).invoke(args, context);
        }
        if (funcItem instanceof XPathFunctionItem) {
            return ((XPathFunctionItem) funcItem).invoke(args, context);
        }
        throw new XPathException(callerName + ": argument is not a function item");
    }

    /**
     * XPath 3.0 fn:for-each($seq, $action) — applies a function to every item in a sequence.
     */
    private static class ForEachFunction implements Function {
        @Override
        public String getName() { return "for-each"; }
        @Override
        public int getMinArgs() { return 2; }
        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue funcItem = args.get(1);

            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                callArgs.add(item);
                XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "for-each");
                // Flatten sequences into the result
                if (result instanceof XPathSequence) {
                    for (XPathValue v : (XPathSequence) result) {
                        results.add(v);
                    }
                } else {
                    results.add(result);
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (results.size() == 1) {
                return results.get(0);
            }
            return new XPathSequence(results);
        }
    }

    /**
     * XPath 3.0 fn:filter($seq, $f) — returns items for which the function returns true.
     */
    private static class FilterFunction implements Function {
        @Override
        public String getName() { return "filter"; }
        @Override
        public int getMinArgs() { return 2; }
        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue funcItem = args.get(1);

            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                callArgs.add(item);
                XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "filter");
                if (result.asBoolean()) {
                    results.add(item);
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (results.size() == 1) {
                return results.get(0);
            }
            return new XPathSequence(results);
        }
    }

    /**
     * XPath 3.0 fn:for-each-pair($seq1, $seq2, $f) — applies $f to corresponding pairs.
     */
    private static class ForEachPairFunction implements Function {
        @Override
        public String getName() { return "for-each-pair"; }
        @Override
        public int getMinArgs() { return 3; }
        @Override
        public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq1 = args.get(0);
            XPathValue seq2 = args.get(1);
            XPathValue funcItem = args.get(2);

            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it1 = seq1.sequenceIterator();
            Iterator<XPathValue> it2 = seq2.sequenceIterator();
            while (it1.hasNext() && it2.hasNext()) {
                XPathValue item1 = it1.next();
                XPathValue item2 = it2.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
                callArgs.add(item1);
                callArgs.add(item2);
                XPathValue result = invokeFunctionItem(funcItem, callArgs, context, "for-each-pair");
                if (result instanceof XPathSequence) {
                    for (XPathValue v : (XPathSequence) result) {
                        results.add(v);
                    }
                } else {
                    results.add(result);
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (results.size() == 1) {
                return results.get(0);
            }
            return new XPathSequence(results);
        }
    }

    /**
     * XPath 3.1 fn:sort($input) or fn:sort($input, $collation, $key) — sorts a sequence.
     * The 1-arg form sorts by string value; the 3-arg form uses a key function.
     */
    private static class SortFunction implements Function {
        @Override
        public String getName() { return "sort"; }
        @Override
        public int getMinArgs() { return 1; }
        @Override
        public int getMaxArgs() { return 3; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);

            List<XPathValue> items = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                items.add(it.next());
            }

            if (items.isEmpty()) {
                return XPathSequence.EMPTY;
            }

            // Get collation from 2nd argument or use default
            final Collation collation;
            if (args.size() >= 2) {
                String collUri = args.get(1).asString();
                if (collUri != null && !collUri.isEmpty()) {
                    collation = Collation.forUri(collUri);
                } else {
                    String defaultUri = context.getDefaultCollation();
                    collation = Collation.forUri(defaultUri);
                }
            } else {
                String defaultUri = context.getDefaultCollation();
                collation = Collation.forUri(defaultUri);
            }

            final XPathValue keyFunc;
            if (args.size() >= 3) {
                keyFunc = args.get(2);
            } else {
                keyFunc = null;
            }

            // Compute sort keys
            final List<String> keys = new ArrayList<String>(items.size());
            for (int i = 0; i < items.size(); i++) {
                if (keyFunc != null) {
                    List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                    callArgs.add(items.get(i));
                    XPathValue keyVal = invokeFunctionItem(keyFunc, callArgs, context, "sort");
                    keys.add(keyVal.asString());
                } else {
                    keys.add(items.get(i).asString());
                }
            }

            // Build index array and sort by keys using collation
            Integer[] indices = new Integer[items.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = Integer.valueOf(i);
            }
            java.util.Arrays.sort(indices, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    String keyA = keys.get(a.intValue());
                    String keyB = keys.get(b.intValue());
                    return collation.compare(keyA, keyB);
                }
            });

            List<XPathValue> sorted = new ArrayList<XPathValue>(items.size());
            for (int i = 0; i < indices.length; i++) {
                sorted.add(items.get(indices[i].intValue()));
            }

            if (sorted.size() == 1) {
                return sorted.get(0);
            }
            return new XPathSequence(sorted);
        }
    }
}

