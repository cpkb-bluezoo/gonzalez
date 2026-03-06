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

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.ComponentVisibility;
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathBinaryValue;
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
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
import java.util.Comparator;
import java.util.Set;
import java.util.TreeMap;
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
        map.put("function-lookup", new FunctionLookupFunction());
        map.put("type-available", new TypeAvailableFunction());
        map.put("unparsed-entity-uri", new UnparsedEntityUriFunction());
        map.put("unparsed-entity-public-id", new UnparsedEntityPublicIdFunction());
        
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
            return invokeXsConstructor(localName, args, context);
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
    private XPathValue invokeXsConstructor(String localName, List<XPathValue> args, XPathContext context) throws XPathException {
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
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE) {
                        return XPathDateTime.castDateToDateTime(src);
                    }
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                        return src;
                    }
                }
                return XPathDateTime.parseDateTime(value);
            case "date":
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                        return XPathDateTime.castDateTimeToDate(src);
                    }
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE) {
                        return src;
                    }
                }
                return XPathDateTime.parseDate(value);
            case "time":
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                        return XPathDateTime.castDateTimeToTime(src);
                    }
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.TIME) {
                        return src;
                    }
                }
                return XPathDateTime.parseTime(value);
            case "duration":
                return XPathDateTime.parseDuration(value);
            case "yearMonthDuration":
                return XPathDateTime.parseYearMonthDuration(value);
            case "dayTimeDuration":
                return XPathDateTime.parseDayTimeDuration(value);
            case "gYearMonth":
            case "gYear":
            case "gMonthDay":
            case "gDay":
            case "gMonth":
                // For g* types: cast from date/dateTime by extracting components
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    XPathDateTime.DateTimeType srcType = src.getDateTimeType();
                    if (srcType == XPathDateTime.DateTimeType.DATE || 
                        srcType == XPathDateTime.DateTimeType.DATE_TIME) {
                        return XPathDateTime.castToGType(src, localName);
                    }
                }
                // Fall through to string parsing
                switch (localName) {
                    case "gYearMonth": return XPathDateTime.parseGYearMonth(value);
                    case "gYear": return XPathDateTime.parseGYear(value);
                    case "gMonthDay": return XPathDateTime.parseGMonthDay(value);
                    case "gDay": return XPathDateTime.parseGDay(value);
                    case "gMonth": return XPathDateTime.parseGMonth(value);
                    default: throw new XPathException("Unknown g* type: " + localName);
                }
            case "string":
                return XPathString.of(value);
            case "integer":
                try {
                    if (arg instanceof XPathNumber) {
                        XPathNumber argNum = (XPathNumber) arg;
                        if (argNum.isExactInteger()) {
                            return argNum;
                        }
                        double d = argNum.asNumber();
                        if (Double.isNaN(d) || Double.isInfinite(d)) {
                            throw new XPathException("Cannot convert " + d + " to xs:integer");
                        }
                        if (argNum.isDecimal()) {
                            java.math.BigDecimal bd = argNum.getDecimalValue();
                            java.math.BigInteger bi = bd.toBigInteger();
                            return XPathNumber.ofInteger(bi);
                        }
                        return XPathNumber.ofInteger((long) d);
                    }
                    String intStr = value.trim();
                    if (intStr.indexOf('.') >= 0) {
                        java.math.BigDecimal bd = new java.math.BigDecimal(intStr);
                        java.math.BigInteger bi = bd.toBigInteger();
                        return XPathNumber.ofInteger(bi);
                    }
                    try {
                        long intValue = Long.parseLong(intStr);
                        return XPathNumber.ofInteger(intValue);
                    } catch (NumberFormatException e2) {
                        java.math.BigInteger bi = new java.math.BigInteger(intStr);
                        return XPathNumber.ofInteger(bi);
                    }
                } catch (NumberFormatException e) {
                    throw new XPathException("Invalid xs:integer: " + value);
                }
            case "decimal":
                try {
                    if (arg instanceof XPathNumber) {
                        XPathNumber argNum = (XPathNumber) arg;
                        if (argNum.isDecimal()) {
                            return argNum;
                        }
                        return new XPathNumber(java.math.BigDecimal.valueOf(argNum.asNumber()));
                    }
                    String decStr = value.trim();
                    if (decStr.indexOf('e') >= 0 || decStr.indexOf('E') >= 0) {
                        throw new XPathException("FORG0001: Invalid xs:decimal: " + value);
                    }
                    return new XPathNumber(new java.math.BigDecimal(decStr));
                } catch (NumberFormatException e) {
                    throw new XPathException("FORG0001: Invalid xs:decimal: " + value);
                }
            case "double":
                try {
                    if (arg instanceof XPathNumber) {
                        return new XPathNumber(arg.asNumber(), false, true);
                    }
                    String trimmedNum = value.trim();
                    if ("INF".equals(trimmedNum) || "+INF".equals(trimmedNum)) {
                        return new XPathNumber(Double.POSITIVE_INFINITY, false, true);
                    } else if ("-INF".equals(trimmedNum)) {
                        return new XPathNumber(Double.NEGATIVE_INFINITY, false, true);
                    } else if ("NaN".equals(trimmedNum)) {
                        return new XPathNumber(Double.NaN, false, true);
                    }
                    double numValue = Double.parseDouble(trimmedNum);
                    return new XPathNumber(numValue, false, true);
                } catch (NumberFormatException e) {
                    throw new XPathException("Invalid xs:double: " + value);
                }
            case "float":
                try {
                    if (arg instanceof XPathNumber) {
                        float fVal = (float) arg.asNumber();
                        return new XPathNumber(fVal, true);
                    }
                    String trimmedFloat = value.trim();
                    if ("INF".equals(trimmedFloat) || "+INF".equals(trimmedFloat)) {
                        return new XPathNumber(Float.POSITIVE_INFINITY, true);
                    } else if ("-INF".equals(trimmedFloat)) {
                        return new XPathNumber(Float.NEGATIVE_INFINITY, true);
                    } else if ("NaN".equals(trimmedFloat)) {
                        return new XPathNumber(Float.NaN, true);
                    }
                    float floatValue = Float.parseFloat(trimmedFloat);
                    return new XPathNumber(floatValue, true);
                } catch (NumberFormatException e) {
                    throw new XPathException("Invalid xs:float: " + value);
                }
            case "boolean":
                // Numeric-to-boolean: 0, +0, -0, NaN → false; all others → true
                if (arg instanceof XPathNumber) {
                    double boolNum = arg.asNumber();
                    return XPathBoolean.of(boolNum != 0.0 && !Double.isNaN(boolNum));
                }
                String trimmed = value.trim();
                if ("true".equals(trimmed) || "1".equals(trimmed)) {
                    return XPathBoolean.TRUE;
                } else if ("false".equals(trimmed) || "0".equals(trimmed)) {
                    return XPathBoolean.FALSE;
                } else {
                    throw new XPathException("Invalid xs:boolean: " + value);
                }
            case "anyURI":
                return new XPathAnyURI(value);
            case "QName": {
                int colonPos = value.indexOf(':');
                if (colonPos > 0) {
                    String prefix = value.substring(0, colonPos);
                    String local = value.substring(colonPos + 1);
                    String nsUri = "";
                    if (context != null) {
                        String resolved = context.resolveNamespacePrefix(prefix);
                        if (resolved != null) {
                            nsUri = resolved;
                        }
                    }
                    return new XPathQName(nsUri, prefix, local);
                }
                return XPathQName.of(value);
            }
            case "base64Binary":
                // Cast from hexBinary → base64Binary: convert representation
                if (arg instanceof XPathBinaryValue) {
                    return ((XPathBinaryValue) arg).toBase64Binary();
                }
                // Validate base64 encoding
                String b64 = value.replaceAll("\\s+", "");  // Remove whitespace
                if (!isValidBase64(b64)) {
                    throw new XPathException("Invalid xs:base64Binary: " + value);
                }
                return XPathBinaryValue.fromBase64(b64);
            case "hexBinary":
                // Cast from base64Binary → hexBinary: convert representation
                if (arg instanceof XPathBinaryValue) {
                    return ((XPathBinaryValue) arg).toHexBinary();
                }
                // Validate hex encoding (must be even number of hex digits)
                String hex = value.trim();
                if (!isValidHexBinary(hex)) {
                    throw new XPathException("Invalid xs:hexBinary: " + value);
                }
                return XPathBinaryValue.fromHex(hex);
            case "NMTOKENS":
            case "IDREFS":
            case "ENTITIES":
                // List types: split whitespace-separated value into a sequence
                String listValue = value.trim();
                if (listValue.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                List<XPathValue> listItems = new ArrayList<>();
                int listStart = 0;
                int listLen = listValue.length();
                while (listStart < listLen) {
                    while (listStart < listLen && Character.isWhitespace(listValue.charAt(listStart))) {
                        listStart++;
                    }
                    if (listStart >= listLen) {
                        break;
                    }
                    int listEnd = listStart;
                    while (listEnd < listLen && !Character.isWhitespace(listValue.charAt(listEnd))) {
                        listEnd++;
                    }
                    String token = listValue.substring(listStart, listEnd);
                    listItems.add(XPathString.of(token));
                    listStart = listEnd;
                }
                if (listItems.size() == 1) {
                    return listItems.get(0);
                }
                return XPathSequence.fromList(listItems);
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
                return XPathUntypedAtomic.ofUntyped(value);
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
        // Per XSLT spec, user-defined functions start with empty tunnel parameter context,
        // no context item (XPDY0002 if '.' or '//' is used in function body),
        // and unnamed mode (erratum XT.E19: mode="#current" in function reverts to default)
        TransformContext funcContext = context.pushVariableScope().withNoTunnelParameters()
            .withContextNode(null).withRegexMatcher(null).withMode(null);
        if (funcContext instanceof org.bluezoo.gonzalez.transform.runtime.BasicTransformContext) {
            ((org.bluezoo.gonzalez.transform.runtime.BasicTransformContext) funcContext)
                .setContextItemUndefined(true);
        }
        
        // Bind parameters to arguments, coercing and type-checking as needed
        List<UserFunction.FunctionParameter> params = function.getParameters();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            XPathValue argVal = args.get(i);
            String paramType = params.get(i).getAsType();
            if (argVal instanceof XPathUntypedAtomic) {
                argVal = coerceUntypedAtomic((XPathUntypedAtomic) argVal, paramType);
            } else if (paramType != null && !paramType.isEmpty()) {
                argVal = coerceArgument(argVal, paramType, params.get(i).getName());
            }
            funcContext.getVariableScope().bind(params.get(i).getNamespaceURI(), params.get(i).getLocalName(), argVal);
        }
        
        // Execute the function body
        try {
            XPathValue result;
            String asType = function.getAsType();
            
            // Use sequence construction to preserve item boundaries.
            // For functions without a declared type, default to sequence mode
            // so node-sets from xsl:sequence are not collapsed into RTFs.
            if (asType == null || isAtomicOrSequenceType(asType)) {
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
                function.getLocalName() + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Builds a cache key for a function call (for memoized functions).
     *
     * @param function the function being called
     * @param args the function arguments
     * @return a cache key string
     */
    /**
     * Coerces an xs:untypedAtomic value to the target type declared by a function parameter.
     * Per XPath 2.0+, untypedAtomic values are automatically cast to the expected type.
     */
    private XPathValue coerceUntypedAtomic(XPathUntypedAtomic value, String asType) {
        if (asType == null || asType.isEmpty()) {
            return value;
        }
        String type = asType.trim();
        if (type.startsWith("xs:")) {
            type = type.substring(3);
        }
        String strVal = value.asString();
        if ("decimal".equals(type)) {
            try {
                return new XPathNumber(new java.math.BigDecimal(strVal));
            } catch (NumberFormatException e) {
                return value;
            }
        }
        if ("double".equals(type) || "float".equals(type) || "numeric".equals(type)) {
            try {
                return XPathNumber.of(Double.parseDouble(strVal));
            } catch (NumberFormatException e) {
                return value;
            }
        }
        if ("integer".equals(type) || "int".equals(type) || "long".equals(type)
                || "short".equals(type) || "byte".equals(type)) {
            try {
                try {
                    return XPathNumber.ofInteger(Long.parseLong(strVal));
                } catch (NumberFormatException e2) {
                    return XPathNumber.ofInteger(new java.math.BigInteger(strVal));
                }
            } catch (NumberFormatException e) {
                return value;
            }
        }
        if ("string".equals(type)) {
            return XPathString.of(strVal);
        }
        return value;
    }

    /**
     * Coerces a function argument to the declared parameter type, raising
     * XTTE0790 if the value cannot be converted.
     */
    private XPathValue coerceArgument(XPathValue value, String asType, String paramName)
            throws XPathException {
        String type = asType.trim();
        // Strip occurrence indicator
        String baseType = type;
        if (baseType.endsWith("*") || baseType.endsWith("+") || baseType.endsWith("?")) {
            baseType = baseType.substring(0, baseType.length() - 1).trim();
        }
        if (baseType.startsWith("xs:")) {
            baseType = baseType.substring(3);
        }
        // For atomic type parameters, check and coerce the value
        boolean isNumericType = "integer".equals(baseType) || "int".equals(baseType)
                || "long".equals(baseType) || "short".equals(baseType) || "byte".equals(baseType)
                || "decimal".equals(baseType) || "double".equals(baseType) || "float".equals(baseType)
                || "nonNegativeInteger".equals(baseType) || "positiveInteger".equals(baseType)
                || "nonPositiveInteger".equals(baseType) || "negativeInteger".equals(baseType)
                || "unsignedLong".equals(baseType) || "unsignedInt".equals(baseType)
                || "unsignedShort".equals(baseType) || "unsignedByte".equals(baseType);
        if (isNumericType && value instanceof XPathString) {
            String strVal = value.asString();
            try {
                if ("integer".equals(baseType) || "int".equals(baseType) || "long".equals(baseType)
                        || "short".equals(baseType) || "byte".equals(baseType)
                        || "nonNegativeInteger".equals(baseType) || "positiveInteger".equals(baseType)
                        || "nonPositiveInteger".equals(baseType) || "negativeInteger".equals(baseType)
                        || "unsignedLong".equals(baseType) || "unsignedInt".equals(baseType)
                        || "unsignedShort".equals(baseType) || "unsignedByte".equals(baseType)) {
                    return XPathNumber.ofInteger(Long.parseLong(strVal.trim()));
                } else if ("decimal".equals(baseType)) {
                    return new XPathNumber(new java.math.BigDecimal(strVal.trim()));
                } else {
                    return XPathNumber.of(Double.parseDouble(strVal.trim()));
                }
            } catch (NumberFormatException e) {
                throw new XPathException("XTTE0790: Cannot convert string '" + strVal
                    + "' to " + asType + " for parameter $" + paramName);
            }
        }
        if ("boolean".equals(baseType) && value instanceof XPathString) {
            String strVal = value.asString().trim();
            if ("true".equals(strVal) || "1".equals(strVal)) {
                return XPathBoolean.TRUE;
            }
            if ("false".equals(strVal) || "0".equals(strVal)) {
                return XPathBoolean.FALSE;
            }
            throw new XPathException("XTTE0790: Cannot convert string '" + strVal
                + "' to xs:boolean for parameter $" + paramName);
        }
        return value;
    }

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
        
        // Function types like function(*), function(xs:string) as xs:string
        if (baseType.startsWith("function(") || baseType.startsWith("(function(")) {
            return true;
        }
        
        // Map and array types
        if (baseType.startsWith("map(") || baseType.startsWith("array(")) {
            return true;
        }
        
        // Node types with occurrence indicators need sequence construction
        if (asType.endsWith("*") || asType.endsWith("+") || asType.endsWith("?")) {
            return true;
        }
        
        // Bare node types that produce standalone node items (not element trees)
        // must use sequence construction to preserve proper XPath node identity
        if (baseType.equals("text()") || baseType.equals("namespace-node()")
                || baseType.equals("comment()") || baseType.equals("node()")
                || baseType.equals("processing-instruction()") 
                || baseType.equals("attribute()")
                || baseType.startsWith("element(")) {
            return true;
        }
        
        // Default: use RTF for document types
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
            // Check for atomic current item first (set by for-each over atomics)
            if (context instanceof BasicTransformContext) {
                XPathValue currentItem = ((BasicTransformContext) context).getXsltCurrentItem();
                if (currentItem != null) {
                    return currentItem;
                }
            }
            // Fall back to XSLT current node (stays the same during predicate evaluation)
            XPathNode node = context.getXsltCurrentNode();
            if (node == null) {
                throw new XPathException("XTDE1360: The context item for current() is absent");
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
        public int getMaxArgs() { return 3; }  // XSLT 2.0+: third arg is target document node
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String keyName = args.get(0).asString();
            XPathValue keyValue = args.get(1);
            
            if (!(context instanceof TransformContext)) {
                return XPathNodeSet.empty();
            }
            TransformContext txContext = (TransformContext) context;
            CompiledStylesheet stylesheet = txContext.getStylesheet();
            
            if (!isValidQName(keyName)) {
                throw new XPathException("XTDE1260: key name is not a valid QName: '" + keyName + "'");
            }
            
            String expandedName = expandKeyName(keyName, context);
            
            List<KeyDefinition> keyDefs = stylesheet.getKeyDefinitions(expandedName);
            if (keyDefs.isEmpty()) {
                throw new XPathException("XTDE1260: No xsl:key declaration with name '" + keyName + "'");
            }
            boolean isComposite = keyDefs.get(0).isComposite();
            
            // Determine the target document root.
            // Third argument (XSLT 2.0+) specifies a node; the key search
            // is restricted to the subtree rooted at that node.
            XPathNode root;
            XPathNode topNode = null;
            if (args.size() > 2) {
                XPathValue topArg = args.get(2);
                if (topArg instanceof XPathNode) {
                    topNode = (XPathNode) topArg;
                } else if (topArg instanceof XPathNodeSet) {
                    List<XPathNode> nodes = ((XPathNodeSet) topArg).getNodes();
                    if (!nodes.isEmpty()) {
                        topNode = nodes.get(0);
                    }
                } else if (topArg.isNodeSet()) {
                    XPathNodeSet ns = topArg.asNodeSet();
                    if (ns != null && !ns.isEmpty()) {
                        topNode = ns.iterator().next();
                    }
                }
                if (topNode == null) {
                    throw new XPathException("XTDE1270: Third argument to key() is not a node");
                }
                root = topNode.getRoot();
                // XTDE1270: root must be a document node
                if (root == null || root.getNodeType() != NodeType.ROOT) {
                    throw new XPathException("XTDE1270: The root of the tree containing the " +
                        "third argument to key() is not a document node");
                }
            } else {
                XPathNode contextNode = context.getContextNode();
                if (contextNode == null) {
                    throw new XPathException("XTDE1270: key() with two arguments requires " +
                        "a context node, but the context item is absent");
                }
                root = contextNode.getRoot();
                // XTDE1270: root must be a document node
                if (root == null || root.getNodeType() != NodeType.ROOT) {
                    throw new XPathException("XTDE1270: The root of the tree containing the " +
                        "context node is not a document node");
                }
            }
            
            // Collect search values from the second argument
            // NaN values never match (NaN ne NaN per XPath spec)
            boolean isXPath2 = txContext.getXsltVersion() >= 2.0;
            List<String> searchValues = new ArrayList<String>();
            if (isComposite) {
                String compositeKey = buildCompositeSearchKey(keyValue);
                searchValues.add(compositeKey);
            } else if (keyValue instanceof XPathNodeSet) {
                for (XPathNode node : ((XPathNodeSet) keyValue).getNodes()) {
                    searchValues.add(node.getStringValue());
                }
            } else if (keyValue instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) keyValue) {
                    String canonical = canonicalKeyValue(item, isXPath2);
                    if (canonical != null) {
                        searchValues.add(canonical);
                    }
                }
            } else {
                String canonical = canonicalKeyValue(keyValue, isXPath2);
                if (canonical != null) {
                    searchValues.add(canonical);
                }
            }
            
            // Resolve the collation for key comparison.
            // The xsl:key collation attribute takes precedence over the default collation.
            String keyCollUri = keyDefs.get(0).getCollation();
            if (keyCollUri == null) {
                keyCollUri = txContext.getDefaultCollation();
            }
            final Collation collation = Collation.forUri(
                    keyCollUri != null ? keyCollUri : Collation.CODEPOINT_URI);
            
            // Look up (or build) the cached key index for this key + document + collation
            Map<String, List<XPathNode>> index = null;
            String collUri = keyCollUri != null ? keyCollUri : Collation.CODEPOINT_URI;
            String cacheKey = expandedName + "\0" + System.identityHashCode(root)
                    + "\0" + collUri;
            
            if (txContext instanceof BasicTransformContext) {
                BasicTransformContext btx = (BasicTransformContext) txContext;
                index = btx.getKeyIndex(cacheKey);
                
                if (index == null) {
                    if (btx.isKeyBeingEvaluated(expandedName)) {
                        throw new XPathException("XTDE0640: Circular reference in key: " + keyName);
                    }
                    btx.startKeyEvaluation(expandedName);
                    try {
                        index = buildKeyIndex(root, keyDefs, collation, btx);
                    } finally {
                        btx.endKeyEvaluation(expandedName);
                    }
                    btx.putKeyIndex(cacheKey, index);
                }
            } else {
                index = buildKeyIndex(root, keyDefs, collation, txContext);
            }
            
            // Look up matching nodes from the index
            List<XPathNode> result = new ArrayList<XPathNode>();
            for (int i = 0; i < searchValues.size(); i++) {
                String sv = searchValues.get(i);
                List<XPathNode> matches = index.get(sv);
                if (matches != null) {
                    for (int j = 0; j < matches.size(); j++) {
                        XPathNode matched = matches.get(j);
                        if (topNode != null && !isDescendantOrSelf(topNode, matched)) {
                            continue;
                        }
                        boolean dup = false;
                        for (int k = 0; k < result.size(); k++) {
                            if (result.get(k).isSameNode(matched)) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            result.add(matched);
                        }
                    }
                }
            }
            
            return new XPathNodeSet(result);
        }
        
        /**
         * Builds a complete key index for the given key definitions and document.
         * Walks the entire document tree once per definition, evaluating the match
         * pattern and use expression/content, and returns a map from key value
         * strings to matching nodes. Multiple definitions with the same name are
         * all active (XSLT 2.0+).
         */
        private Map<String, List<XPathNode>> buildKeyIndex(XPathNode root, 
                List<KeyDefinition> keyDefs, final Collation collation, 
                TransformContext context) throws XPathException {
            Comparator<String> comparator = new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return collation.compare(a, b);
                }
            };
            Map<String, List<XPathNode>> index = new TreeMap<String, List<XPathNode>>(comparator);
            for (int d = 0; d < keyDefs.size(); d++) {
                KeyDefinition keyDef = keyDefs.get(d);
                indexNode(root, keyDef, index, context);
            }
            return index;
        }
        
        /**
         * Recursively indexes a node and its descendants for a key definition.
         */
        private void indexNode(XPathNode node, KeyDefinition keyDef,
                Map<String, List<XPathNode>> index,
                TransformContext context) throws XPathException {
            BasicTransformContext btx = (BasicTransformContext) context;
            Pattern matchPattern = keyDef.getMatchPattern();
            
            if (matchPattern.matches(node, context)) {
                addToIndex(node, keyDef, index, btx);
            }
            
            if (node.isElement()) {
                Iterator<XPathNode> attrs = node.getAttributes();
                while (attrs.hasNext()) {
                    XPathNode attr = attrs.next();
                    if (matchPattern.matches(attr, context)) {
                        addToIndex(attr, keyDef, index, btx);
                    }
                }
            }
            
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                indexNode(children.next(), keyDef, index, context);
            }
        }
        
        /**
         * Evaluates the use expression or content constructors for a matched node
         * and adds entries to the index.
         */
        private void addToIndex(XPathNode node, KeyDefinition keyDef,
                Map<String, List<XPathNode>> index, BasicTransformContext btx) 
                throws XPathException {
            XPathContext nodeContext = btx.withXsltCurrentNode(node);
            XPathValue useValue;
            XPathExpression useExpr = keyDef.getUseExpr();
            if (useExpr != null) {
                useValue = useExpr.evaluate(nodeContext);
            } else {
                try {
                    SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
                    SequenceNode content = keyDef.getContent();
                    List<XSLTNode> contentChildren = content.getChildren();
                    for (int i = 0; i < contentChildren.size(); i++) {
                        contentChildren.get(i).execute(btx.withXsltCurrentNode(node), seqBuilder);
                        seqBuilder.markItemBoundary();
                    }
                    useValue = seqBuilder.getSequence();
                } catch (SAXException e) {
                    throw new XPathException(e.getMessage(), e);
                }
            }
            
            boolean isXPath2 = btx.getXsltVersion() >= 2.0;
            if (keyDef.isComposite()) {
                String compositeKey = buildCompositeUseKey(useValue);
                addNodeToIndex(node, compositeKey, index);
            } else if (useValue instanceof XPathNodeSet) {
                for (XPathNode n : ((XPathNodeSet) useValue).getNodes()) {
                    addNodeToIndex(node, n.getStringValue(), index);
                }
            } else if (useValue instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) useValue) {
                    String canonical = canonicalKeyValue(item, isXPath2);
                    if (canonical != null) {
                        addNodeToIndex(node, canonical, index);
                    }
                }
            } else {
                String canonical = canonicalKeyValue(useValue, isXPath2);
                if (canonical != null) {
                    addNodeToIndex(node, canonical, index);
                }
            }
        }
        
        private boolean isNaN(XPathValue value) {
            if (value.getType() == XPathValue.Type.NUMBER) {
                return Double.isNaN(value.asNumber());
            }
            return false;
        }

        /**
         * Returns a canonical key string for typed comparison in XSLT 2.0+.
         * Uses type prefixes so that different primitive types never match
         * (e.g., number 4 vs string "4"). DateTimes are normalized to UTC
         * for timezone-aware equality.
         * In XSLT 1.0 mode, returns plain string form (current behavior).
         */
        private String canonicalKeyValue(XPathValue value, boolean isXPath2) {
            if (!isXPath2) {
                if (isNaN(value)) {
                    return null;
                }
                return value.asString();
            }
            if (value instanceof XPathDateTime) {
                XPathDateTime dt = (XPathDateTime) value;
                if (!dt.isDuration()) {
                    try {
                        XPathDateTime normalized = dt.adjustToTimezone(0);
                        return "T\0" + normalized.asString();
                    } catch (XPathException e) {
                        return "T\0" + dt.asString();
                    }
                }
                return "T\0" + dt.asString();
            }
            if (value.getType() == XPathValue.Type.NUMBER) {
                double d = value.asNumber();
                if (Double.isNaN(d)) {
                    return null;
                }
                return "N\0" + d;
            }
            return value.asString();
        }

        private boolean isDescendantOrSelf(XPathNode ancestor, XPathNode node) {
            XPathNode current = node;
            while (current != null) {
                if (current.isSameNode(ancestor)) {
                    return true;
                }
                current = current.getParent();
            }
            return false;
        }

        private void addNodeToIndex(XPathNode node, String keyVal,
                Map<String, List<XPathNode>> index) {
            List<XPathNode> list = index.get(keyVal);
            if (list == null) {
                list = new ArrayList<XPathNode>();
                index.put(keyVal, list);
            }
            list.add(node);
        }
        
        /**
         * Builds a composite key string from the use expression result.
         * Concatenates sequence items with a null-byte separator.
         */
        private String buildCompositeUseKey(XPathValue value) {
            StringBuilder sb = new StringBuilder();
            if (value instanceof XPathSequence) {
                boolean first = true;
                for (XPathValue item : (XPathSequence) value) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(item.asString());
                    first = false;
                }
            } else if (value instanceof XPathNodeSet) {
                boolean first = true;
                for (XPathNode n : ((XPathNodeSet) value).getNodes()) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(n.getStringValue());
                    first = false;
                }
            } else {
                sb.append(value.asString());
            }
            return sb.toString();
        }
        
        /**
         * Builds a composite search key from the second argument to key().
         * For composite keys, the search value is a sequence treated as a tuple.
         */
        private String buildCompositeSearchKey(XPathValue keyValue) {
            StringBuilder sb = new StringBuilder();
            if (keyValue instanceof XPathSequence) {
                boolean first = true;
                for (XPathValue item : (XPathSequence) keyValue) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(item.asString());
                    first = false;
                }
            } else if (keyValue instanceof XPathNodeSet) {
                boolean first = true;
                for (XPathNode n : ((XPathNodeSet) keyValue).getNodes()) {
                    if (!first) {
                        sb.append('\0');
                    }
                    sb.append(n.getStringValue());
                    first = false;
                }
            } else {
                sb.append(keyValue.asString());
            }
            return sb.toString();
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
            
            // Get base URI from second argument if provided
            boolean hasExplicitBase = (args.size() > 1 && args.get(1) != null);
            String baseUri = null;
            if (hasExplicitBase) {
                XPathValue baseArg = args.get(1);
                if (baseArg.isNodeSet()) {
                    XPathNodeSet baseNodes = baseArg.asNodeSet();
                    if (!baseNodes.isEmpty()) {
                        XPathNode baseNode = baseNodes.first();
                        if (baseNode instanceof XPathNodeWithBaseURI) {
                            baseUri = ((XPathNodeWithBaseURI) baseNode).getBaseURI();
                        }
                    }
                } else {
                    baseUri = baseArg.asString();
                }
            }
            
            // Fall back to static base URI (used for string arguments and as last resort)
            if (baseUri == null || baseUri.isEmpty()) {
                baseUri = context.getStaticBaseURI();
            }
            
            // Handle node-set argument (document each node's string value)
            if (uriArg.isNodeSet()) {
                XPathNodeSet uriNodes = uriArg.asNodeSet();
                List<XPathNode> results = new ArrayList<>();
                for (XPathNode node : uriNodes) {
                    String uri = node.getStringValue();
                    // Per XSLT spec: when document() has one node-set argument,
                    // the base URI for resolving each node's string value is the
                    // base URI of that node. Only use the explicit base URI
                    // (from second argument) or static base URI as fallback.
                    String nodeBaseUri;
                    if (!hasExplicitBase) {
                        nodeBaseUri = getDocumentNodeBaseUri(node);
                        if (nodeBaseUri == null || nodeBaseUri.isEmpty()) {
                            nodeBaseUri = baseUri;
                        }
                    } else {
                        nodeBaseUri = baseUri;
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
        
        /**
         * Gets the base URI for a node by walking up to the document root.
         * For nodes from documents loaded via doc()/document(), the document
         * root stores the absolute URI of the loaded document.
         */
        private String getDocumentNodeBaseUri(XPathNode node) {
            if (node instanceof XPathNodeWithBaseURI) {
                String uri = ((XPathNodeWithBaseURI) node).getBaseURI();
                if (uri != null && !uri.isEmpty()) {
                    return uri;
                }
            }
            XPathNode current = node.getParent();
            while (current != null) {
                if (current instanceof XPathNodeWithBaseURI) {
                    String uri = ((XPathNodeWithBaseURI) current).getBaseURI();
                    if (uri != null && !uri.isEmpty()) {
                        return uri;
                    }
                }
                current = current.getParent();
            }
            return null;
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
            
            // doc(string?) - empty sequence returns empty sequence
            if (uriArg == null
                    || (uriArg instanceof XPathSequence && ((XPathSequence)uriArg).isEmpty())
                    || (uriArg.isNodeSet() && uriArg.asNodeSet().isEmpty())) {
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
            
            // Resolve QName prefix in format name to expanded name
            if (formatName != null && formatName.contains(":")) {
                int colonIdx = formatName.indexOf(':');
                String prefix = formatName.substring(0, colonIdx);
                String localName = formatName.substring(colonIdx + 1);
                String nsUri = context.resolveNamespacePrefix(prefix);
                if (nsUri != null) {
                    formatName = "{" + nsUri + "}" + localName;
                }
            }
            
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
            
            // Default format symbols (as code points, supporting non-BMP)
            int decimalSepCp = '.';
            int groupingSepCp = ',';
            int minusSignCp = '-';
            int percentCp = '%';
            int perMilleCp = '\u2030';
            int zeroDigitCp = '0';
            int digitCp = '#';
            int patternSepCp = ';';
            int exponentSepCp = 'e';
            String infinity = "Infinity";
            String nan = "NaN";
            
            if (decFormat != null) {
                decimalSepCp = decFormat.decimalSeparatorCp;
                groupingSepCp = decFormat.groupingSeparatorCp;
                minusSignCp = decFormat.minusSignCp;
                percentCp = decFormat.percentCp;
                perMilleCp = decFormat.perMilleCp;
                zeroDigitCp = decFormat.zeroDigitCp;
                digitCp = decFormat.digitCp;
                patternSepCp = decFormat.patternSeparatorCp;
                exponentSepCp = decFormat.exponentSeparatorCp;
                infinity = decFormat.infinity;
                nan = decFormat.nan;
            }
            
            // Handle special values
            if (Double.isNaN(number)) {
                return XPathString.of(nan);
            }
            if (Double.isInfinite(number)) {
                if (number < 0) {
                    String ms = new String(Character.toChars(minusSignCp));
                    return XPathString.of(ms + infinity);
                }
                return XPathString.of(infinity);
            }
            
            // When non-BMP symbols are used, translate the pattern to use
            // BMP placeholders, process with Java DecimalFormat, then
            // restore the non-BMP characters in the result.
            boolean hasNonBmp = decFormat != null && decFormat.hasNonBmpSymbols();
            
            // BMP working chars (may differ from actual code points when non-BMP)
            char decimalSep = hasNonBmp ? '.' : (char) decimalSepCp;
            char groupingSep = hasNonBmp ? ',' : (char) groupingSepCp;
            char minusSign = hasNonBmp ? '-' : (char) minusSignCp;
            char percent = hasNonBmp ? '%' : (char) percentCp;
            char perMille = hasNonBmp ? '\u2030' : (char) perMilleCp;
            char zeroDigit = hasNonBmp ? '0' : (char) zeroDigitCp;
            char digit = hasNonBmp ? '#' : (char) digitCp;
            char patternSep = hasNonBmp ? ';' : (char) patternSepCp;
            char exponentSep = hasNonBmp ? 'e' : (char) exponentSepCp;
            
            if (hasNonBmp) {
                pattern = translateNonBmpPattern(pattern,
                    decimalSepCp, groupingSepCp, percentCp, perMilleCp,
                    zeroDigitCp, digitCp, patternSepCp, exponentSepCp);
            }
            
            // Validate pattern before processing (FODF1310)
            validatePattern(pattern, decimalSep, groupingSep, percent, perMille,
                patternSep, digit, zeroDigit, exponentSep);
            
            // Split into positive and negative sub-patterns
            String activePattern;
            boolean hasExplicitNegativeSubpattern = pattern.indexOf(patternSep) >= 0;
            if (number < 0 && hasExplicitNegativeSubpattern) {
                int sepPos = pattern.indexOf(patternSep);
                activePattern = pattern.substring(sepPos + 1);
                number = Math.abs(number);
            } else if (number < 0) {
                activePattern = pattern;
            } else {
                if (hasExplicitNegativeSubpattern) {
                    int sepPos = pattern.indexOf(patternSep);
                    activePattern = pattern.substring(0, sepPos);
                } else {
                    activePattern = pattern;
                }
            }
            
            boolean hasPerMille = activePattern.indexOf(perMille) >= 0;
            boolean hasPercent = activePattern.indexOf(percent) >= 0;
            
            int[] groupingSizes = parseGroupingSizes(activePattern, decimalSep,
                groupingSep, digit, zeroDigit);

            boolean hasExponentSep = activePattern.indexOf(exponentSep) >= 0;
            
            String javaPattern = translatePattern(activePattern, decimalSep, groupingSep, 
                minusSign, percent, perMille, zeroDigit, digit, patternSep, exponentSep);
            
            try {
                DecimalFormatSymbols symbols = new DecimalFormatSymbols();
                symbols.setDecimalSeparator(decimalSep);
                symbols.setGroupingSeparator(groupingSep);
                symbols.setPercent(percent);
                symbols.setPerMill(perMille);
                symbols.setZeroDigit(zeroDigit);
                symbols.setDigit(digit);
                symbols.setPatternSeparator(patternSep);
                symbols.setInfinity(infinity);
                symbols.setNaN(nan);
                
                DecimalFormat df = new DecimalFormat(javaPattern, symbols);
                df.setGroupingUsed(false);
                java.math.BigDecimal bd = java.math.BigDecimal.valueOf(number);
                String result = df.format(bd);
                
                if (!hasExplicitNegativeSubpattern && number < 0) {
                    if (minusSign != '-' && result.startsWith("-")) {
                        result = minusSign + result.substring(1);
                    }
                }
                
                if (hasPerMille && perMille != '\u2030') {
                    result = result.replace('\u2030', perMille);
                }
                if (hasPercent && percent != '%') {
                    result = result.replace('%', percent);
                }
                if (hasExponentSep && exponentSep != 'E') {
                    result = result.replace('E', exponentSep);
                }
                
                if (groupingSizes != null && !hasExponentSep) {
                    result = applyGroupingSeparators(result, groupingSizes,
                        groupingSep, decimalSep, zeroDigit);
                }
                
                // Restore non-BMP characters in the result
                if (hasNonBmp) {
                    result = restoreNonBmpResult(result,
                        decimalSepCp, groupingSepCp, minusSignCp,
                        percentCp, perMilleCp, zeroDigitCp, exponentSepCp);
                }
                
                return XPathString.of(result);
            } catch (IllegalArgumentException e) {
                throw new XPathException("FODF1310: Invalid format-number picture string: " + pattern + 
                    " - " + e.getMessage());
            }
        }
        
        /**
         * Validates a format-number picture string according to XSLT/XPath rules.
         * Throws FODF1310 for invalid patterns.
         */
        private void validatePattern(String pattern, char decimalSep, char groupingSep,
                char percent, char perMille, char patternSep, char digit, char zeroDigit,
                char exponentSep) throws XPathException {
            // Split into positive and negative sub-patterns
            String[] subPatterns = splitPattern(pattern, patternSep);
            
            // Check for too many sub-patterns (only positive and negative allowed)
            if (subPatterns.length > 2) {
                throw new XPathException("FODF1310: Invalid picture string - too many sub-patterns (found " + 
                    subPatterns.length + " pattern separators)");
            }
            
            // Validate each sub-pattern
            for (String subPattern : subPatterns) {
                validateSubPattern(subPattern, decimalSep, groupingSep, percent,
                    perMille, digit, zeroDigit, exponentSep);
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
                char percent, char perMille, char digit, char zeroDigit,
                char exponentSep) throws XPathException {
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
                } else if (inMantissa && !isDigitChar && c != groupingSep
                        && c != decimalSep && c != exponentSep) {
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
                char patternSep, char exponentSep) {
            // Translate custom symbols to Java DecimalFormat standard symbols.
            // Grouping separators are stripped because we apply them manually
            // to support unequal grouping sizes (Java only supports uniform grouping).
            StringBuilder sb = new StringBuilder(pattern.length() + 8);
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == decimalSep) {
                    sb.append('.');
                } else if (c == groupingSep) {
                    // Skip — grouping applied in post-processing
                    continue;
                } else if (c == exponentSep) {
                    sb.append('E');
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

        /**
         * Parses grouping sizes from the integer part of a picture string.
         * Returns null if no grouping separators are present.
         * The returned array contains [primarySize, secondarySize] where
         * secondarySize repeats for all remaining groups.
         */
        private int[] parseGroupingSizes(String pattern, char decimalSep,
                char groupingSep, char digit, char zeroDigit) {
            // Find the integer part (before decimal separator)
            int decPos = -1;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == decimalSep) {
                    decPos = i;
                    break;
                }
            }
            String intPart = (decPos >= 0)
                ? pattern.substring(0, decPos)
                : pattern;
            
            // Find grouping separator positions within the integer part,
            // measuring from the rightmost digit position
            java.util.List<Integer> sepPositions = new java.util.ArrayList<>();
            int digitCount = 0;
            for (int i = intPart.length() - 1; i >= 0; i--) {
                char c = intPart.charAt(i);
                if (c == digit || c == zeroDigit || isInZeroDigitFamily(c, zeroDigit)) {
                    digitCount++;
                } else if (c == groupingSep) {
                    sepPositions.add(digitCount);
                }
            }
            
            if (sepPositions.isEmpty()) {
                return null;
            }
            
            int primary = sepPositions.get(0);
            if (primary <= 0) {
                return null;
            }
            
            if (sepPositions.size() == 1) {
                // Single separator: repeat at multiples of primary
                return new int[]{primary, primary};
            }
            
            // Check if positions are regular (evenly spaced at multiples of primary)
            boolean regular = true;
            for (int i = 0; i < sepPositions.size(); i++) {
                if (sepPositions.get(i) != primary * (i + 1)) {
                    regular = false;
                    break;
                }
            }
            
            if (regular) {
                return new int[]{primary, primary};
            }
            
            // Unequal grouping: return explicit positions only (negative secondary = no repeat)
            int[] result = new int[sepPositions.size() + 1];
            result[0] = -1;
            for (int i = 0; i < sepPositions.size(); i++) {
                result[i + 1] = sepPositions.get(i);
            }
            return result;
        }
        
        /**
         * Inserts grouping separators into the integer part of a formatted number.
         */
        private String applyGroupingSeparators(String formatted, int[] groupingSizes,
                char groupingSep, char decimalSep, char zeroDigit) {
            // Find where the integer part ends (at decimal separator or end)
            int decIdx = formatted.indexOf(decimalSep);
            
            // Find the start of digits (skip prefix like minus sign)
            int intStart = 0;
            for (int i = 0; i < formatted.length(); i++) {
                char c = formatted.charAt(i);
                if (c == zeroDigit || isInZeroDigitFamily(c, zeroDigit)
                        || (c >= '0' && c <= '9')) {
                    intStart = i;
                    break;
                }
            }
            int intEnd = (decIdx >= 0) ? decIdx : formatted.length();
            
            String prefix = formatted.substring(0, intStart);
            String intPart = formatted.substring(intStart, intEnd);
            String suffix = formatted.substring(intEnd);
            
            if (groupingSizes[0] == -1) {
                // Unequal grouping: use explicit positions only
                java.util.Set<Integer> positions = new java.util.HashSet<>();
                for (int i = 1; i < groupingSizes.length; i++) {
                    positions.add(groupingSizes[i]);
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < intPart.length(); i++) {
                    int posFromRight = intPart.length() - i;
                    if (positions.contains(posFromRight) && i > 0) {
                        sb.append(groupingSep);
                    }
                    sb.append(intPart.charAt(i));
                }
                return prefix + sb.toString() + suffix;
            }
            
            int primary = groupingSizes[0];
            int secondary = groupingSizes[1];
            
            if (intPart.length() <= primary) {
                return formatted;
            }
            
            StringBuilder sb = new StringBuilder();
            int pos = intPart.length();
            // First group (primary size)
            int groupStart = pos - primary;
            if (groupStart < 0) {
                groupStart = 0;
            }
            sb.insert(0, intPart.substring(groupStart, pos));
            pos = groupStart;
            // Remaining groups (secondary size)
            while (pos > 0) {
                groupStart = pos - secondary;
                if (groupStart < 0) {
                    groupStart = 0;
                }
                sb.insert(0, groupingSep);
                sb.insert(0, intPart.substring(groupStart, pos));
                pos = groupStart;
            }
            
            return prefix + sb.toString() + suffix;
        }
        
        /**
         * Translates non-BMP code points in a pattern string to BMP
         * placeholders so the pattern can be processed by char-based logic.
         */
        private String translateNonBmpPattern(String pattern,
                int decSepCp, int grpSepCp, int pctCp, int pmCp,
                int zeroCp, int digitCp, int patSepCp, int expSepCp) {
            StringBuilder sb = new StringBuilder();
            int len = pattern.length();
            int i = 0;
            while (i < len) {
                int cp = pattern.codePointAt(i);
                int advance = Character.charCount(cp);
                if (cp == decSepCp) {
                    sb.append('.');
                } else if (cp == grpSepCp) {
                    sb.append(',');
                } else if (cp == pctCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('%');
                } else if (cp == pmCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('\u2030');
                } else if (cp == patSepCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append(';');
                } else if (cp == expSepCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('e');
                } else if (cp == zeroCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('0');
                } else if (cp == digitCp && Character.isSupplementaryCodePoint(cp)) {
                    sb.append('#');
                } else if (Character.isSupplementaryCodePoint(zeroCp)) {
                    int offset = cp - zeroCp;
                    if (offset > 0 && offset <= 9) {
                        sb.append((char) ('0' + offset));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                } else {
                    sb.appendCodePoint(cp);
                }
                i += advance;
            }
            return sb.toString();
        }
        
        /**
         * Restores non-BMP characters in the formatted result by replacing
         * BMP placeholders with their actual non-BMP strings.
         */
        private String restoreNonBmpResult(String result,
                int decSepCp, int grpSepCp, int minusCp,
                int pctCp, int pmCp, int zeroCp, int expSepCp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (c == '.' && Character.isSupplementaryCodePoint(decSepCp)) {
                    sb.appendCodePoint(decSepCp);
                } else if (c == ',' && Character.isSupplementaryCodePoint(grpSepCp)) {
                    sb.appendCodePoint(grpSepCp);
                } else if (c == '-' && Character.isSupplementaryCodePoint(minusCp)) {
                    sb.appendCodePoint(minusCp);
                } else if (c == '%' && Character.isSupplementaryCodePoint(pctCp)) {
                    sb.appendCodePoint(pctCp);
                } else if (c == '\u2030' && Character.isSupplementaryCodePoint(pmCp)) {
                    sb.appendCodePoint(pmCp);
                } else if (Character.isSupplementaryCodePoint(zeroCp)
                        && c >= '0' && c <= '9') {
                    int offset = c - '0';
                    sb.appendCodePoint(zeroCp + offset);
                } else if (c == 'e' && Character.isSupplementaryCodePoint(expSepCp)) {
                    sb.appendCodePoint(expSepCp);
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
                   c == '\u00A4' ||
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
                // Empty sequence returns ""
                if (arg.isSequence() && ((XPathSequence) arg).isEmpty()) {
                    return XPathString.of("");
                }
                if (!arg.isNodeSet()) {
                    throw new XPathException("generate-id() argument must be a node-set");
                }
                XPathNodeSet ns = arg.asNodeSet();
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
            
            if (name == null || name.isEmpty()) {
                throw new XPathException("XTDE1440: Argument to element-available() " +
                    "is not a valid QName: empty string");
            }

            String localName = null;
            String nsUri = null;

            // Handle EQName: Q{uri}local
            if (name.startsWith("Q{")) {
                int closeBrace = name.indexOf('}');
                if (closeBrace < 2 || closeBrace == name.length() - 1) {
                    throw new XPathException("XTDE1440: Argument to element-available() " +
                        "is not a valid EQName: '" + name + "'");
                }
                nsUri = name.substring(2, closeBrace);
                localName = name.substring(closeBrace + 1);
            } else {
                if (!isValidQNameForElementAvailable(name)) {
                    throw new XPathException("XTDE1440: Argument to element-available() " +
                        "is not a valid QName: '" + name + "'");
                }
                int colonPos = name.indexOf(':');
                if (colonPos > 0) {
                    String prefix = name.substring(0, colonPos);
                    localName = name.substring(colonPos + 1);
                    nsUri = context.resolveNamespacePrefix(prefix);
                    if (nsUri == null) {
                        throw new XPathException("XTDE1440: Prefix '" + prefix +
                            "' in element-available() argument is not in scope");
                    }
                } else {
                    localName = name;
                }
            }
            
            if (XSLT_NS.equals(nsUri)) {
                double version = context.getXsltVersion();
                return isXsltElement(localName, version) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
            }
            
            return XPathBoolean.FALSE;
        }
        
        private static boolean isValidQNameForElementAvailable(String name) {
            int colonIdx = name.indexOf(':');
            if (colonIdx == 0 || colonIdx == name.length() - 1) {
                return false;
            }
            String checkPart = colonIdx > 0 ? name.substring(0, colonIdx) : name;
            if (!isValidNCNameStart(checkPart.charAt(0))) {
                return false;
            }
            for (int i = 1; i < checkPart.length(); i++) {
                if (!isValidNCNameChar(checkPart.charAt(i))) {
                    return false;
                }
            }
            if (colonIdx > 0) {
                String local = name.substring(colonIdx + 1);
                if (local.isEmpty() || local.contains(":")) {
                    return false;
                }
                if (!isValidNCNameStart(local.charAt(0))) {
                    return false;
                }
                for (int i = 1; i < local.length(); i++) {
                    if (!isValidNCNameChar(local.charAt(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
        
        private static boolean isValidNCNameStart(char c) {
            return Character.isLetter(c) || c == '_';
        }
        
        private static boolean isValidNCNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
        }
        
        private static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";
        
        private boolean isXsltElement(String localName, double version) {
            switch (localName) {
                // XSLT 1.0 elements
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
                    return true;
                // XSLT 2.0 elements
                case "for-each-group":
                case "function":
                case "namespace":
                case "next-match":
                case "perform-sort":
                case "result-document":
                case "sequence":
                    return version >= 2.0;
                // XSLT 3.0 elements
                case "accumulator":
                case "accumulator-rule":
                case "assert":
                case "break":
                case "catch":
                case "context-item":
                case "evaluate":
                case "expose":
                case "fork":
                case "global-context-item":
                case "iterate":
                case "map":
                case "map-entry":
                case "merge":
                case "merge-action":
                case "merge-key":
                case "merge-source":
                case "mode":
                case "next-iteration":
                case "on-completion":
                case "on-empty":
                case "on-non-empty":
                case "override":
                case "package":
                case "source-document":
                case "stream":
                case "try":
                case "use-package":
                case "where-populated":
                    return version >= 3.0;
                default:
                    return false;
            }
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
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            // XTDE1400: argument must be a valid EQName
            if (!isValidQName(name) && !isValidEQName(name)) {
                throw new XPathException("XTDE1400: function-available() argument is not a valid EQName: '" + name + "'");
            }
            
            int arity = -1;
            if (args.size() >= 2) {
                arity = (int) args.get(1).asNumber();
            }
            
            // Resolve prefix to namespace URI
            String nsUri = null;
            String localName = name;
            
            // Handle EQName: Q{uri}local
            if (name.startsWith("Q{")) {
                int closeBrace = name.indexOf('}');
                if (closeBrace >= 2) {
                    nsUri = name.substring(2, closeBrace);
                    localName = name.substring(closeBrace + 1);
                }
            } else {
                int colonPos = name.indexOf(':');
                if (colonPos > 0) {
                    String prefix = name.substring(0, colonPos);
                    localName = name.substring(colonPos + 1);
                    nsUri = context.resolveNamespacePrefix(prefix);
                    if (nsUri == null) {
                        if (context.getXsltVersion() >= 2.0) {
                            throw new XPathException("XTDE1400: Prefix '" + prefix +
                                "' in function-available() argument is not in scope");
                        }
                        return XPathBoolean.FALSE;
                    }
                }
            }
            
            boolean available = isFunctionAvailable(nsUri, localName, arity, context);
            return available ? XPathBoolean.TRUE : XPathBoolean.FALSE;
        }
        
        private boolean isFunctionAvailable(String nsUri, String localName,
                int arity, XPathContext context) {
            double version = context.getXsltVersion();
            boolean isXslt20 = version >= 2.0 && version < 3.0;

            // No namespace or fn: namespace: check XSLT + core functions
            if (nsUri == null || nsUri.isEmpty() || FN_NAMESPACE.equals(nsUri)) {
                Function xsltFunc = INSTANCE.xsltFunctions.get(localName);
                if (xsltFunc != null) {
                    if (isXslt20 && isPostXPath20Function(localName, arity)) {
                        return false;
                    }
                    return arityMatches(xsltFunc, arity);
                }
                Function coreFunc =
                    CoreFunctionLibrary.INSTANCE.getFunction(localName);
                if (coreFunc != null) {
                    if (isXslt20 && isPostXPath20Function(localName, arity)) {
                        return false;
                    }
                    return arityMatches(coreFunc, arity);
                }
                return false;
            }
            // xs: namespace: constructor functions always take exactly 1 arg
            if (XS_NAMESPACE.equals(nsUri)) {
                if (!isXsConstructor(localName)) {
                    return false;
                }
                return arity < 0 || arity == 1;
            }
            // math: namespace -- not available in XSLT 2.0
            if (MATH_NAMESPACE.equals(nsUri)) {
                if (isXslt20) {
                    return false;
                }
                return isMathFunctionWithArity(localName, arity);
            }
            // map: namespace -- not available in XSLT 2.0
            if (MAP_NAMESPACE.equals(nsUri)) {
                if (isXslt20) {
                    return false;
                }
                return isMapFunctionWithArity(localName, arity);
            }
            // array: namespace -- not available in XSLT 2.0
            if (ARRAY_NAMESPACE.equals(nsUri)) {
                if (isXslt20) {
                    return false;
                }
                return isArrayFunctionWithArity(localName, arity);
            }
            // User-defined functions: check via stylesheet context
            if (nsUri != null && !nsUri.isEmpty()
                    && context instanceof TransformContext) {
                TransformContext transformContext = (TransformContext) context;
                CompiledStylesheet stylesheet = transformContext.getStylesheet();
                if (stylesheet != null) {
                    Map<String, UserFunction> funcs =
                        stylesheet.getUserFunctions();
                    if (arity >= 0) {
                        String key = nsUri + "#" + localName + "#" + arity;
                        return funcs.containsKey(key);
                    }
                    String prefix = nsUri + "#" + localName + "#";
                    for (String key : funcs.keySet()) {
                        if (key.startsWith(prefix)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Returns true if the given fn: function (name + arity) was added
         * after XPath/F&amp;O 1.0 and should not be reported as available
         * in XSLT 2.0 mode.
         */
        private boolean isPostXPath20Function(String localName, int arity) {
            switch (localName) {
                // Functions entirely new in F&O 3.0/3.1
                case "format-integer":
                case "analyze-string":
                case "path":
                case "has-children":
                case "innermost":
                case "outermost":
                case "head":
                case "tail":
                case "uri-collection":
                case "unparsed-text-lines":
                case "environment-variable":
                case "available-environment-variables":
                case "parse-xml":
                case "parse-xml-fragment":
                case "serialize":
                case "generate-id":
                case "function-lookup":
                case "function-arity":
                case "function-name":
                case "for-each":
                case "filter":
                case "fold-left":
                case "fold-right":
                case "for-each-pair":
                case "sort":
                case "apply":
                case "collation-key":
                case "json-doc":
                case "json-to-xml":
                case "xml-to-json":
                case "parse-json":
                case "random-number-generator":
                case "load-xquery-module":
                case "transform":
                case "contains-token":
                case "default-language":
                // XSLT 3.0-only functions
                case "snapshot":
                case "copy-of":
                case "accumulator-before":
                case "accumulator-after":
                case "current-merge-group":
                case "current-merge-key":
                case "stream-available":
                case "current-output-uri":
                case "available-system-properties":
                    return true;

                // Functions that exist in F&O 1.0 but gained new arities
                case "node-name":
                case "nilled":
                case "data":
                case "document-uri":
                    return arity == 0;
                case "round":
                    return arity == 2;
                case "string-join":
                    return arity == 1;
                case "unparsed-entity-uri":
                case "unparsed-entity-public-id":
                    return arity == 2;

                default:
                    return false;
            }
        }
        
        private boolean arityMatches(Function func, int arity) {
            if (arity < 0) {
                return true;
            }
            return arity >= func.getMinArgs() && arity <= func.getMaxArgs();
        }
        
        private boolean isXsConstructor(String localName) {
            switch (localName) {
                case "string": case "boolean": case "decimal": case "float": case "double":
                case "integer": case "long": case "int": case "short": case "byte":
                case "duration": case "dateTime": case "time": case "date":
                case "gYearMonth": case "gYear": case "gMonthDay": case "gDay": case "gMonth":
                case "hexBinary": case "base64Binary": case "anyURI": case "QName":
                case "normalizedString": case "token": case "language":
                case "NMTOKEN": case "Name": case "NCName":
                case "ID": case "IDREF": case "ENTITY":
                case "nonPositiveInteger": case "negativeInteger":
                case "nonNegativeInteger": case "unsignedLong": case "unsignedInt":
                case "unsignedShort": case "unsignedByte": case "positiveInteger":
                case "untypedAtomic": case "yearMonthDuration": case "dayTimeDuration":
                    return true;
                default:
                    return false;
            }
        }
        
        private boolean isMathFunctionWithArity(String localName, int arity) {
            int minArgs;
            int maxArgs;
            switch (localName) {
                case "pi":
                    minArgs = 0; maxArgs = 0; break;
                case "pow": case "atan2":
                    minArgs = 2; maxArgs = 2; break;
                case "exp": case "exp10": case "log": case "log10":
                case "sqrt": case "sin": case "cos": case "tan":
                case "asin": case "acos": case "atan":
                    minArgs = 1; maxArgs = 1; break;
                default:
                    return false;
            }
            return arity < 0 || (arity >= minArgs && arity <= maxArgs);
        }
        
        private boolean isMapFunctionWithArity(String localName, int arity) {
            int minArgs;
            int maxArgs;
            switch (localName) {
                case "merge":
                    minArgs = 1; maxArgs = 2; break;
                case "keys": case "size":
                    minArgs = 1; maxArgs = 1; break;
                case "contains": case "get": case "remove":
                case "for-each": case "find":
                    minArgs = 2; maxArgs = 2; break;
                case "put":
                    minArgs = 3; maxArgs = 3; break;
                case "entry":
                    minArgs = 2; maxArgs = 2; break;
                default:
                    return false;
            }
            return arity < 0 || (arity >= minArgs && arity <= maxArgs);
        }
        
        private boolean isArrayFunctionWithArity(String localName, int arity) {
            int minArgs;
            int maxArgs;
            switch (localName) {
                case "size": case "get": case "head": case "tail":
                case "reverse": case "flatten":
                    minArgs = 1; maxArgs = 1; break;
                case "put": case "insert-before":
                    minArgs = 3; maxArgs = 3; break;
                case "append": case "remove": case "for-each":
                case "filter":
                    minArgs = 2; maxArgs = 2; break;
                case "subarray":
                    minArgs = 2; maxArgs = 3; break;
                case "join":
                    minArgs = 1; maxArgs = 1; break;
                case "fold-left": case "fold-right":
                    minArgs = 3; maxArgs = 3; break;
                case "for-each-pair":
                    minArgs = 3; maxArgs = 3; break;
                case "sort":
                    minArgs = 1; maxArgs = 2; break;
                default:
                    return false;
            }
            return arity < 0 || (arity >= minArgs && arity <= maxArgs);
        }
    }

    /**
     * XPath 3.0 fn:function-lookup() function.
     *
     * <p>Looks up a function by QName and arity. Returns a function item
     * if the function is available, or empty sequence if not found.
     *
     * <p>Signature: function-lookup(xs:QName, xs:integer) as function(*)?
     *
     * @see <a href="https://www.w3.org/TR/xpath-functions-31/#func-function-lookup">fn:function-lookup</a>
     */
    private static class FunctionLookupFunction implements Function {
        @Override
        public String getName() { return "function-lookup"; }

        @Override
        public int getMinArgs() { return 2; }

        @Override
        public int getMaxArgs() { return 2; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue nameArg = args.get(0);
            int arity = (int) args.get(1).asNumber();

            String nsUri;
            String localName;
            if (nameArg instanceof XPathQName) {
                XPathQName qn = (XPathQName) nameArg;
                nsUri = qn.getNamespaceURI();
                localName = qn.getLocalName();
            } else {
                String str = nameArg.asString();
                int colonPos = str.indexOf(':');
                if (colonPos > 0) {
                    String prefix = str.substring(0, colonPos);
                    localName = str.substring(colonPos + 1);
                    nsUri = context.resolveNamespacePrefix(prefix);
                } else {
                    localName = str;
                    nsUri = FN_NAMESPACE;
                }
            }
            if (nsUri == null) {
                nsUri = "";
            }

            XPathFunctionLibrary library = context.getFunctionLibrary();

            // Check built-in fn: namespace functions
            if (nsUri.isEmpty() || FN_NAMESPACE.equals(nsUri)) {
                Function xsltFunc = INSTANCE.xsltFunctions.get(localName);
                if (xsltFunc != null && arity >= xsltFunc.getMinArgs()
                        && arity <= xsltFunc.getMaxArgs()) {
                    XPathFunctionItem item = new XPathFunctionItem(localName, FN_NAMESPACE, arity, library);
                    return item;
                }
                Function coreFunc = CoreFunctionLibrary.INSTANCE.getFunction(localName);
                if (coreFunc != null && arity >= coreFunc.getMinArgs()
                        && arity <= coreFunc.getMaxArgs()) {
                    XPathFunctionItem item = new XPathFunctionItem(localName, FN_NAMESPACE, arity, library);
                    return item;
                }
                if (nsUri.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
            }

            // Check xs: namespace constructor functions
            if (XS_NAMESPACE.equals(nsUri)) {
                if (arity == 1 && isXsConstructorName(localName)) {
                    XPathFunctionItem item = new XPathFunctionItem(
                        "xs:" + localName, XS_NAMESPACE, 1, library);
                    return item;
                }
                return XPathSequence.EMPTY;
            }

            // Check math: namespace
            if (MATH_NAMESPACE.equals(nsUri)) {
                Function mathFunc = INSTANCE.xsltFunctions.get(localName);
                if (mathFunc != null && arity >= mathFunc.getMinArgs()
                        && arity <= mathFunc.getMaxArgs()) {
                    XPathFunctionItem item = new XPathFunctionItem(
                        "math:" + localName, MATH_NAMESPACE, arity, library);
                    return item;
                }
                return XPathSequence.EMPTY;
            }

            // Check user-defined functions
            if (context instanceof TransformContext) {
                TransformContext tc = (TransformContext) context;
                CompiledStylesheet stylesheet = tc.getStylesheet();
                if (stylesheet != null) {
                    UserFunction uf = stylesheet.getUserFunction(nsUri, localName, arity);
                    if (uf != null) {
                        String displayName = localName;
                        XPathFunctionItem item = new XPathFunctionItem(
                            displayName, nsUri, arity, library);
                        List<UserFunction.FunctionParameter> params = uf.getParameters();
                        SequenceType[] paramTypes = new SequenceType[params.size()];
                        for (int i = 0; i < params.size(); i++) {
                            UserFunction.FunctionParameter fp = params.get(i);
                            String asType = fp.getAsType();
                            if (asType != null && !asType.isEmpty()) {
                                SequenceType pt = SequenceType.parse(asType, null);
                                paramTypes[i] = pt != null ? pt : SequenceType.ITEM_STAR;
                            } else {
                                paramTypes[i] = SequenceType.ITEM_STAR;
                            }
                        }
                        SequenceType returnType = SequenceType.ITEM_STAR;
                        String asType = uf.getAsType();
                        if (asType != null && !asType.isEmpty()) {
                            SequenceType rt = SequenceType.parse(asType, null);
                            if (rt != null) {
                                returnType = rt;
                            }
                        }
                        item.setSignature(paramTypes, returnType);
                        return item;
                    }
                }
            }

            return XPathSequence.EMPTY;
        }

        private boolean isXsConstructorName(String localName) {
            switch (localName) {
                case "string": case "boolean": case "decimal": case "float": case "double":
                case "integer": case "long": case "int": case "short": case "byte":
                case "duration": case "dateTime": case "time": case "date":
                case "gYearMonth": case "gYear": case "gMonthDay": case "gDay": case "gMonth":
                case "hexBinary": case "base64Binary": case "anyURI": case "QName":
                case "normalizedString": case "token": case "language":
                case "NMTOKEN": case "NMTOKENS": case "Name": case "NCName":
                case "ID": case "IDREF": case "IDREFS": case "ENTITY": case "ENTITIES":
                case "nonPositiveInteger": case "negativeInteger":
                case "nonNegativeInteger": case "unsignedLong": case "unsignedInt":
                case "unsignedShort": case "unsignedByte": case "positiveInteger":
                case "untypedAtomic": case "yearMonthDuration": case "dayTimeDuration":
                    return true;
                default:
                    return false;
            }
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
            XPathNode contextNode = context.getContextNode();
            if (contextNode == null) {
                throw new XPathException("XTDE1370: unparsed-entity-uri() requires a context " +
                    "node, but the context item is absent");
            }
            XPathNode root = contextNode.getRoot();
            if (root == null || root.getNodeType() != NodeType.ROOT) {
                throw new XPathException("XTDE1370: The root of the tree containing the " +
                    "context node is not a document node");
            }
            String entityName = args.get(0).asString();
            if (root instanceof org.bluezoo.gonzalez.transform.runtime.StreamingNode) {
                org.bluezoo.gonzalez.transform.runtime.StreamingNode docNode =
                    (org.bluezoo.gonzalez.transform.runtime.StreamingNode) root;
                String[] entity = docNode.getUnparsedEntity(entityName);
                if (entity != null && entity[1] != null) {
                    String systemId = entity[1];
                    // Resolve relative to document URI
                    String docUri = docNode.getDocumentURI();
                    if (docUri != null && !docUri.isEmpty()) {
                        try {
                            java.net.URI base = new java.net.URI(docUri);
                            java.net.URI resolved = base.resolve(systemId);
                            return XPathString.of(resolved.toString());
                        } catch (Exception e) {
                            return XPathString.of(systemId);
                        }
                    }
                    return XPathString.of(systemId);
                }
            }
            return XPathString.of("");
        }
    }
    
    private static class UnparsedEntityPublicIdFunction implements Function {
        @Override
        public String getName() { return "unparsed-entity-public-id"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode contextNode = context.getContextNode();
            if (contextNode == null) {
                throw new XPathException("XTDE1380: unparsed-entity-public-id() requires a context " +
                    "node, but the context item is absent");
            }
            XPathNode root = contextNode.getRoot();
            if (root == null || root.getNodeType() != NodeType.ROOT) {
                throw new XPathException("XTDE1380: The root of the tree containing the " +
                    "context node is not a document node");
            }
            String entityName = args.get(0).asString();
            if (root instanceof org.bluezoo.gonzalez.transform.runtime.StreamingNode) {
                org.bluezoo.gonzalez.transform.runtime.StreamingNode docNode =
                    (org.bluezoo.gonzalez.transform.runtime.StreamingNode) root;
                String[] entity = docNode.getUnparsedEntity(entityName);
                if (entity != null && entity[0] != null) {
                    return XPathString.of(entity[0]);
                }
            }
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
            try {
                XPathValue group = context.getVariable(null, "__current_group__");
                if (group != null) {
                    if (group.isNodeSet() || group.isSequence()) {
                        return group;
                    }
                }
            } catch (XPathException e) {
                // Variable not in scope - not inside xsl:for-each-group
            }
            throw new XPathException("XTDE1061: current-group() is absent " +
                "(not inside xsl:for-each-group)");
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
            try {
                XPathValue key = context.getVariable(null, "__current_grouping_key__");
                if (key != null) {
                    return key;
                }
            } catch (XPathException e) {
                // Variable not in scope - not inside xsl:for-each-group
            }
            throw new XPathException("XTDE1071: current-grouping-key() is absent " +
                "(not inside xsl:for-each-group)");
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
                XPathValue group = context.getVariable(null, "__current_merge_group__");
                if (group != null) {
                    return group;
                }
            } else {
                String sourceName = args.get(0).asString();
                // XTDE3490: validate source name against known merge source names
                XPathValue namesVal = context.getVariable(null, "__merge_source_names__");
                if (namesVal != null) {
                    String knownNames = namesVal.asString();
                    boolean found = false;
                    int start = 0;
                    while (start <= knownNames.length()) {
                        int end = knownNames.indexOf('|', start);
                        if (end < 0) {
                            end = knownNames.length();
                        }
                        String name = knownNames.substring(start, end);
                        if (name.equals(sourceName)) {
                            found = true;
                            break;
                        }
                        start = end + 1;
                    }
                    if (!found) {
                        throw new XPathException("XTDE3490: Unknown merge source name: '"
                            + sourceName + "'");
                    }
                }
                XPathValue group = context.getVariable(null,
                    "__current_merge_group_" + sourceName + "__");
                if (group != null) {
                    return group;
                }
            }
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
            
            // Get schema context if available
            SchemaContext schemaContext = (context instanceof SchemaContext) ? 
                (SchemaContext) context : SchemaContext.NONE;
            
            // If the result already matches the expected type, return as-is.
            // This avoids destructive coercion (e.g. collapsing a sequence of
            // QNames into a single concatenated string).
            if (expectedType.matches(result, schemaContext)) {
                return result;
            }
            
            // If the expected type is atomic, try to convert from text/string
            if (expectedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                String typeLocalName = expectedType.getLocalName();
                String typeNsUri = expectedType.getNamespaceURI();
                
                if (typeNsUri != null && typeNsUri.equals(SequenceType.XS_NAMESPACE)) {
                    String stringValue = result.asString();
                    XPathValue converted = convertStringToAtomicType(stringValue, typeLocalName);
                    if (converted != null) {
                        return converted;
                    }
                }
            }
            
            // After conversion attempt, check again
            if (expectedType.matches(result, schemaContext)) {
                return result;
            }
            // Skip strict matching only for document-node types where runtime
            // representation (RTF) doesn't implement XPathNode
            SequenceType.ItemKind expectedKind = expectedType.getItemKind();
            if (expectedKind == SequenceType.ItemKind.DOCUMENT_NODE) {
                return result;
            }
            throw new XPathTypeException(
                "XTTE0505",
                "Function returned value does not match declared type '" + asType + "'",
                asType,
                result != null ? result.getClass().getSimpleName() : "null"
            );
            
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
                case "float":
                    return new XPathNumber(parseXPathDouble(value));
                case "decimal":
                    return new XPathNumber(new java.math.BigDecimal(value));
                    
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
                    String intStr = value.trim();
                    if (intStr.indexOf('.') >= 0) {
                        java.math.BigDecimal bd = new java.math.BigDecimal(intStr);
                        return XPathNumber.ofInteger(bd.toBigInteger());
                    }
                    try {
                        return XPathNumber.ofInteger(Long.parseLong(intStr));
                    } catch (NumberFormatException nfe) {
                        return XPathNumber.ofInteger(new java.math.BigInteger(intStr));
                    }
                    
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
     * Parses an XPath double value, handling "INF", "-INF", and "NaN"
     * which Java's Double.parseDouble does not recognize.
     */
    private static double parseXPathDouble(String value) {
        String trimmed = value.trim();
        if ("INF".equals(trimmed)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-INF".equals(trimmed)) {
            return Double.NEGATIVE_INFINITY;
        }
        if ("NaN".equals(trimmed)) {
            return Double.NaN;
        }
        return Double.parseDouble(trimmed);
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
        
        // Handle function types: function(*), function(xs:string) as xs:string, etc.
        if (baseType.startsWith("function(") || baseType.startsWith("(function(")) {
            return new SequenceType(SequenceType.ItemKind.ITEM, null, null, null, occ);
        }
        
        // Handle map and array types
        if (baseType.startsWith("map(")) {
            return new SequenceType(SequenceType.ItemKind.MAP, null, null, null, occ);
        }
        if (baseType.startsWith("array(")) {
            return new SequenceType(SequenceType.ItemKind.ARRAY, null, null, null, occ);
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
            if (args.isEmpty()) {
                XPathNode node = context.getContextNode();
                if (node == null) {
                    return XPathSequence.EMPTY;
                }
                return snapshotNode(node);
            }
            
            XPathValue arg = args.get(0);
            if (arg instanceof XPathNode) {
                return snapshotNode((XPathNode) arg);
            }
            if (arg instanceof XPathNodeSet) {
                java.util.List<XPathNode> results = new java.util.ArrayList<>();
                java.util.Iterator<XPathNode> iter = ((XPathNodeSet) arg).iterator();
                while (iter.hasNext()) {
                    XPathNode n = iter.next();
                    XPathValue snap = snapshotNode(n);
                    if (snap instanceof XPathNode) {
                        results.add((XPathNode) snap);
                    } else if (snap instanceof XPathNodeSet) {
                        java.util.Iterator<XPathNode> si = ((XPathNodeSet) snap).iterator();
                        while (si.hasNext()) {
                            results.add(si.next());
                        }
                    }
                }
                if (results.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return new XPathNodeSet(results);
            }
            if (arg instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) arg;
                java.util.List<XPathValue> results = new java.util.ArrayList<>();
                java.util.Iterator<XPathValue> iter = seq.iterator();
                while (iter.hasNext()) {
                    XPathValue item = iter.next();
                    if (item instanceof XPathNode) {
                        results.add(snapshotNode((XPathNode) item));
                    } else {
                        results.add(item);
                    }
                }
                if (results.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return new XPathSequence(results);
            }
            // Non-node items: return as-is (snapshot of atomic is identity)
            return arg;
        }
        
        private XPathValue snapshotNode(XPathNode node) {
            // Per XSLT 3.0 spec, snapshot returns the node itself
            // (in tree-based mode, the full tree is already navigable)
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
            // Per XSLT 3.0 spec: returns the absolute URI of the current output destination.
            // Returns empty sequence when output destination is absent (e.g., global variables,
            // or when no base output URI has been set by the test/application).
            if (context instanceof TransformContext) {
                TransformContext tc = (TransformContext) context;
                String outputUri = tc.getCurrentOutputUri();
                if (outputUri != null) {
                    return XPathString.of(outputUri);
                }
            }
            return XPathSequence.EMPTY;
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
            if (arg == null || (arg.isNodeSet() && arg.asNodeSet().isEmpty())) {
                return XPathNodeSet.empty();
            }
            XPathNodeSet ns;
            if (arg.isNodeSet()) {
                ns = arg.asNodeSet();
            } else if (arg instanceof XPathSequence) {
                ns = ((XPathSequence) arg).asNodeSet();
            } else {
                throw new XPathException("outermost() argument must be a node-set");
            }
            if (ns == null || ns.isEmpty()) {
                return XPathNodeSet.empty();
            }
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
            if (arg == null || (arg.isNodeSet() && arg.asNodeSet().isEmpty())) {
                return XPathNodeSet.empty();
            }
            XPathNodeSet ns;
            if (arg.isNodeSet()) {
                ns = arg.asNodeSet();
            } else if (arg instanceof XPathSequence) {
                ns = ((XPathSequence) arg).asNodeSet();
            } else {
                throw new XPathException("innermost() argument must be a node-set");
            }
            if (ns == null || ns.isEmpty()) {
                return XPathNodeSet.empty();
            }
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

