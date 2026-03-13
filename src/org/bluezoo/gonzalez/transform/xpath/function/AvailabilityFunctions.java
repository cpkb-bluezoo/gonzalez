/*
 * AvailabilityFunctions.java
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.UserFunction;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT availability and introspection functions: system-property, element-available,
 * function-available, function-lookup, type-available, stream-available,
 * available-system-properties.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class AvailabilityFunctions {

    private static final String XSLT_NAMESPACE = "http://www.w3.org/1999/XSL/Transform";
    private static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";
    private static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private static final String FN_NAMESPACE = "http://www.w3.org/2005/xpath-functions";
    private static final String MATH_NAMESPACE = "http://www.w3.org/2005/xpath-functions/math";
    private static final String MAP_NAMESPACE = "http://www.w3.org/2005/xpath-functions/map";
    private static final String ARRAY_NAMESPACE = "http://www.w3.org/2005/xpath-functions/array";

    private AvailabilityFunctions() {
    }

    static Function systemProperty() {
        return new SystemPropertyFunction();
    }

    static Function elementAvailable() {
        return new ElementAvailableFunction();
    }

    static Function functionAvailable() {
        return new FunctionAvailableFunction();
    }

    static Function functionLookup() {
        return new FunctionLookupFunction();
    }

    static Function typeAvailable() {
        return new TypeAvailableFunction();
    }

    static Function streamAvailable() {
        return new StreamAvailableFunction();
    }

    static Function availableSystemProperties() {
        return new AvailableSystemPropertiesFunction();
    }

    // -------------------------------------------------------------------------
    // SystemPropertyFunction
    // -------------------------------------------------------------------------

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
        public String getName() {
            return "system-property";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();

            // XSLT 3.0: accept EQName syntax Q{uri}local in addition to QName
            String namespaceURI = null;
            String localName = null;

            if (name.startsWith("Q{")) {
                if (!AvailabilityFunctions.isValidEQName(name)) {
                    throw new XPathException("XTDE1390: system-property() argument is not a valid EQName: '" + name + "'");
                }
                int closeBrace = name.indexOf('}');
                namespaceURI = name.substring(2, closeBrace);
                localName = name.substring(closeBrace + 1);
            } else {
                if (!AvailabilityFunctions.isValidQName(name)) {
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
                    if (context instanceof TransformContext) {
                        TransformContext tc = (TransformContext) context;
                        CompiledStylesheet stylesheet = tc.getStylesheet();
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

    // -------------------------------------------------------------------------
    // ElementAvailableFunction
    // -------------------------------------------------------------------------

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
        public String getName() {
            return "element-available";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

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

    // -------------------------------------------------------------------------
    // FunctionAvailableFunction
    // -------------------------------------------------------------------------

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
        public String getName() {
            return "function-available";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();

            // XTDE1400: argument must be a valid EQName
            if (!AvailabilityFunctions.isValidQName(name) && !AvailabilityFunctions.isValidEQName(name)) {
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
                Function xsltFunc = XSLTFunctionLibrary.INSTANCE.getXsltFunction(localName);
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
        private static boolean isPostXPath20Function(String localName, int arity) {
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
                case "string":
                case "boolean":
                case "decimal":
                case "float":
                case "double":
                case "integer":
                case "long":
                case "int":
                case "short":
                case "byte":
                case "duration":
                case "dateTime":
                case "time":
                case "date":
                case "gYearMonth":
                case "gYear":
                case "gMonthDay":
                case "gDay":
                case "gMonth":
                case "hexBinary":
                case "base64Binary":
                case "anyURI":
                case "QName":
                case "normalizedString":
                case "token":
                case "language":
                case "NMTOKEN":
                case "Name":
                case "NCName":
                case "ID":
                case "IDREF":
                case "ENTITY":
                case "nonPositiveInteger":
                case "negativeInteger":
                case "nonNegativeInteger":
                case "unsignedLong":
                case "unsignedInt":
                case "unsignedShort":
                case "unsignedByte":
                case "positiveInteger":
                case "untypedAtomic":
                case "yearMonthDuration":
                case "dayTimeDuration":
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
                    minArgs = 0;
                    maxArgs = 0;
                    break;
                case "pow":
                case "atan2":
                    minArgs = 2;
                    maxArgs = 2;
                    break;
                case "exp":
                case "exp10":
                case "log":
                case "log10":
                case "sqrt":
                case "sin":
                case "cos":
                case "tan":
                case "asin":
                case "acos":
                case "atan":
                    minArgs = 1;
                    maxArgs = 1;
                    break;
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
                    minArgs = 1;
                    maxArgs = 2;
                    break;
                case "keys":
                case "size":
                    minArgs = 1;
                    maxArgs = 1;
                    break;
                case "contains":
                case "get":
                case "remove":
                case "for-each":
                case "find":
                    minArgs = 2;
                    maxArgs = 2;
                    break;
                case "put":
                    minArgs = 3;
                    maxArgs = 3;
                    break;
                case "entry":
                    minArgs = 2;
                    maxArgs = 2;
                    break;
                default:
                    return false;
            }
            return arity < 0 || (arity >= minArgs && arity <= maxArgs);
        }

        private boolean isArrayFunctionWithArity(String localName, int arity) {
            int minArgs;
            int maxArgs;
            switch (localName) {
                case "size":
                case "get":
                case "head":
                case "tail":
                case "reverse":
                case "flatten":
                    minArgs = 1;
                    maxArgs = 1;
                    break;
                case "put":
                case "insert-before":
                    minArgs = 3;
                    maxArgs = 3;
                    break;
                case "append":
                case "remove":
                case "for-each":
                case "filter":
                    minArgs = 2;
                    maxArgs = 2;
                    break;
                case "subarray":
                    minArgs = 2;
                    maxArgs = 3;
                    break;
                case "join":
                    minArgs = 1;
                    maxArgs = 1;
                    break;
                case "fold-left":
                case "fold-right":
                    minArgs = 3;
                    maxArgs = 3;
                    break;
                case "for-each-pair":
                    minArgs = 3;
                    maxArgs = 3;
                    break;
                case "sort":
                    minArgs = 1;
                    maxArgs = 2;
                    break;
                default:
                    return false;
            }
            return arity < 0 || (arity >= minArgs && arity <= maxArgs);
        }
    }

    // -------------------------------------------------------------------------
    // FunctionLookupFunction
    // -------------------------------------------------------------------------

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
        public String getName() {
            return "function-lookup";
        }

        @Override
        public int getMinArgs() {
            return 2;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

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
                Function xsltFunc = XSLTFunctionLibrary.INSTANCE.getXsltFunction(localName);
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
                Function mathFunc = XSLTFunctionLibrary.INSTANCE.getXsltFunction(localName);
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
                case "string":
                case "boolean":
                case "decimal":
                case "float":
                case "double":
                case "integer":
                case "long":
                case "int":
                case "short":
                case "byte":
                case "duration":
                case "dateTime":
                case "time":
                case "date":
                case "gYearMonth":
                case "gYear":
                case "gMonthDay":
                case "gDay":
                case "gMonth":
                case "hexBinary":
                case "base64Binary":
                case "anyURI":
                case "QName":
                case "normalizedString":
                case "token":
                case "language":
                case "NMTOKEN":
                case "NMTOKENS":
                case "Name":
                case "NCName":
                case "ID":
                case "IDREF":
                case "IDREFS":
                case "ENTITY":
                case "ENTITIES":
                case "nonPositiveInteger":
                case "negativeInteger":
                case "nonNegativeInteger":
                case "unsignedLong":
                case "unsignedInt":
                case "unsignedShort":
                case "unsignedByte":
                case "positiveInteger":
                case "untypedAtomic":
                case "yearMonthDuration":
                case "dayTimeDuration":
                    return true;
                default:
                    return false;
            }
        }
    }

    // -------------------------------------------------------------------------
    // TypeAvailableFunction
    // -------------------------------------------------------------------------

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
        // Types available on a basic (non-schema-aware) XSLT processor.
        // Per XPath 2.0 B.1 and XSLT 3.0 section 20.3.1, this is:
        //   primitive types (except xs:NOTATION), xs:integer,
        //   xs:untypedAtomic, xs:untyped, xs:anyType, xs:anySimpleType,
        //   xs:anyAtomicType, xs:yearMonthDuration, xs:dayTimeDuration.
        // Derived types (int, short, NCName, etc.) are NOT available
        // on a basic processor.
        private static final Set<String> BUILTIN_TYPES = new HashSet<String>(
                Arrays.asList(
                        "anyType", "anySimpleType", "anyAtomicType",
                        "string", "boolean", "decimal", "float", "double",
                        "duration", "dateTime", "time", "date",
                        "gYearMonth", "gYear", "gMonthDay", "gDay", "gMonth",
                        "hexBinary", "base64Binary", "anyURI", "QName",
                        "integer",
                        "untypedAtomic", "untyped",
                        "yearMonthDuration", "dayTimeDuration",
                        "error"
                )
        );

        @Override
        public String getName() {
            return "type-available";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String qname = args.get(0).asString();

            // XTDE1428: validate the argument is a valid EQName
            if (qname.isEmpty()) {
                throw new XPathException("XTDE1428: Argument to type-available " +
                        "is not a valid EQName: empty string");
            }

            String localName;
            String prefix;
            String namespaceURI = null;

            // Check for EQName Q{uri}local format (XSLT 3.0)
            if (qname.startsWith("Q{")) {
                int closeBrace = qname.indexOf('}');
                if (closeBrace < 0) {
                    throw new XPathException("XTDE1428: Argument to " +
                            "type-available is not a valid EQName: '" +
                            qname + "'");
                }
                namespaceURI = qname.substring(2, closeBrace);
                localName = qname.substring(closeBrace + 1);
                if (!isValidNCName(localName)) {
                    throw new XPathException("XTDE1428: Argument to " +
                            "type-available is not a valid EQName: '" +
                            qname + "'");
                }
                prefix = null;
            } else {
                // Lexical QName
                int colonPos = qname.indexOf(':');
                if (colonPos > 0) {
                    prefix = qname.substring(0, colonPos);
                    localName = qname.substring(colonPos + 1);
                } else {
                    prefix = null;
                    localName = qname;
                }

                // Validate both parts are valid NCNames
                if (prefix != null && !isValidNCName(prefix)) {
                    throw new XPathException("XTDE1428: Argument to " +
                            "type-available is not a valid EQName: '" +
                            qname + "'");
                }
                if (!isValidNCName(localName)) {
                    throw new XPathException("XTDE1428: Argument to " +
                            "type-available is not a valid EQName: '" +
                            qname + "'");
                }

                // XTDE1428: prefix must have a namespace declaration
                if (prefix != null) {
                    namespaceURI = context.resolveNamespacePrefix(prefix);
                    if (namespaceURI == null) {
                        throw new XPathException("XTDE1428: Prefix '" + prefix +
                                "' has no namespace declaration in scope");
                    }
                }
            }

            // Check if it's in the XML Schema namespace
            if (XS_NAMESPACE.equals(namespaceURI)) {
                return BUILTIN_TYPES.contains(localName) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
            }

            // No prefix - check if it's a known built-in type
            if (prefix == null && namespaceURI == null) {
                return BUILTIN_TYPES.contains(localName) ? XPathBoolean.TRUE : XPathBoolean.FALSE;
            }

            // Non-XSD namespace - would need schema imports to check
            return XPathBoolean.FALSE;
        }

        private static boolean isValidNCName(String s) {
            if (s == null || s.isEmpty()) {
                return false;
            }
            char first = s.charAt(0);
            if (!isNCNameStart(first)) {
                return false;
            }
            for (int i = 1; i < s.length(); i++) {
                if (!isNCNameChar(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isNCNameStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isNCNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' ||
                    c == '-' || c == '.' ||
                    c == '\u00B7' ||
                    (c >= '\u0300' && c <= '\u036F') ||
                    (c >= '\u203F' && c <= '\u2040');
        }
    }

    // -------------------------------------------------------------------------
    // StreamAvailableFunction
    // -------------------------------------------------------------------------

    /**
     * stream-available($uri) - Returns true if streaming is available for the URI.
     */
    private static class StreamAvailableFunction implements Function {
        @Override
        public String getName() {
            return "stream-available";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

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

    // -------------------------------------------------------------------------
    // AvailableSystemPropertiesFunction
    // -------------------------------------------------------------------------

    /**
     * available-system-properties() - Returns the QNames of available system properties.
     */
    private static class AvailableSystemPropertiesFunction implements Function {
        @Override
        public String getName() {
            return "available-system-properties";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 0;
        }

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

    static boolean isValidQName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int colonIdx = name.indexOf(':');
        if (colonIdx == 0 || colonIdx == name.length() - 1) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ':') {
                continue;
            }
            if (i == 0 || (colonIdx >= 0 && i == colonIdx + 1)) {
                if (!Character.isLetter(c) && c != '_') {
                    return false;
                }
            } else {
                if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_') {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean isValidEQName(String name) {
        if (name == null || !name.startsWith("Q{")) {
            return false;
        }
        int closeBrace = name.indexOf('}');
        if (closeBrace < 2 || closeBrace == name.length() - 1) {
            return false;
        }
        String localPart = name.substring(closeBrace + 1);
        if (localPart.isEmpty()) {
            return false;
        }
        char first = localPart.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < localPart.length(); i++) {
            char c = localPart.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }
}
