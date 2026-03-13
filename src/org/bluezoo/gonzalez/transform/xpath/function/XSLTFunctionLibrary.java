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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.ComponentVisibility;
import org.bluezoo.gonzalez.transform.compiler.UserFunction;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import static org.bluezoo.gonzalez.transform.xpath.function.TypeAnnotatedFunction.typed;

/**
 * XSLT-specific function library.
 *
 * <p>Thin facade that registers XSLT functions and dispatches invocations
 * to namespace-specific handler classes and domain-grouped function
 * implementations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XSLTFunctionLibrary implements XPathFunctionLibrary {

    /** Singleton instance. */
    public static final XSLTFunctionLibrary INSTANCE = new XSLTFunctionLibrary();

    private static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private static final String FN_NAMESPACE = "http://www.w3.org/2005/xpath-functions";
    private static final String MATH_NAMESPACE = "http://www.w3.org/2005/xpath-functions/math";
    private static final String MAP_NAMESPACE = "http://www.w3.org/2005/xpath-functions/map";
    private static final String ARRAY_NAMESPACE = "http://www.w3.org/2005/xpath-functions/array";

    private final Map<String, Function> xsltFunctions;

    private XSLTFunctionLibrary() {
        SequenceType NS = SequenceType.NODE_STAR;
        SequenceType NQ = SequenceType.NODE_Q;
        SequenceType DNQ = SequenceType.DOCUMENT_NODE_Q;
        SequenceType DN = SequenceType.DOCUMENT_NODE;
        SequenceType IS = SequenceType.ITEM_STAR;
        SequenceType IT = SequenceType.ITEM;
        SequenceType S = SequenceType.STRING;
        SequenceType SQ = SequenceType.STRING_Q;
        SequenceType SS = SequenceType.STRING_STAR;
        SequenceType B = SequenceType.BOOLEAN;
        SequenceType AAQ = SequenceType.ANY_ATOMIC_Q;
        SequenceType AUQ = SequenceType.ANYURI_Q;
        SequenceType I = SequenceType.INTEGER;
        SequenceType EQ = SequenceType.ELEMENT_Q;

        Map<String, Function> map = new HashMap<>();

        // Current context functions
        map.put("current",              typed(CurrentContextFunctions.current(), NS));
        map.put("current-group",        typed(CurrentContextFunctions.currentGroup(), IS));
        map.put("current-grouping-key", typed(CurrentContextFunctions.currentGroupingKey(), AAQ));
        map.put("current-merge-group",  typed(CurrentContextFunctions.currentMergeGroup(), IS, SQ));
        map.put("current-merge-key",    typed(CurrentContextFunctions.currentMergeKey(), AAQ));
        map.put("current-output-uri",   typed(CurrentContextFunctions.currentOutputUri(), AUQ));
        map.put("regex-group",          typed(CurrentContextFunctions.regexGroup(), SQ, I));

        // Document functions
        map.put("document",        typed(DocumentFunctions.document(), NS, IS, NS));
        map.put("doc",             typed(DocumentFunctions.doc(), DNQ, SQ));
        map.put("doc-available",   typed(DocumentFunctions.docAvailable(), B, S));

        // Key function
        map.put("key", typed(KeyFunctions.key(), NS, S, IS, NQ));

        // Format functions
        map.put("format-number", typed(FormatFunctions.formatNumber(), S, SequenceType.DOUBLE, S, S));
        map.put("generate-id",  typed(FormatFunctions.generateId(), S, NQ));

        // Availability / system introspection
        map.put("system-property",             typed(AvailabilityFunctions.systemProperty(), S, S));
        map.put("element-available",           typed(AvailabilityFunctions.elementAvailable(), B, S));
        map.put("function-available",          typed(AvailabilityFunctions.functionAvailable(), B, S, I));
        map.put("function-lookup",             typed(AvailabilityFunctions.functionLookup(), IS, S, I));
        map.put("type-available",              typed(AvailabilityFunctions.typeAvailable(), B, S));
        map.put("stream-available",            typed(AvailabilityFunctions.streamAvailable(), B, S));
        map.put("available-system-properties", typed(AvailabilityFunctions.availableSystemProperties(), SS));

        // Text parsing / unparsed-text
        map.put("unparsed-text",              typed(TextParseFunctions.unparsedText(), S, S, S));
        map.put("unparsed-text-available",    typed(TextParseFunctions.unparsedTextAvailable(), B, S, S));
        map.put("unparsed-text-lines",        typed(TextParseFunctions.unparsedTextLines(), SS, S, S));
        map.put("parse-xml",                  typed(TextParseFunctions.parseXml(), DN, S));
        map.put("parse-xml-fragment",         typed(TextParseFunctions.parseXmlFragment(), DN, S));
        map.put("analyze-string",             typed(TextParseFunctions.analyzeString(), EQ, SQ, S, S));
        map.put("unparsed-entity-uri",        typed(TextParseFunctions.unparsedEntityUri(), SQ, S));
        map.put("unparsed-entity-public-id",  typed(TextParseFunctions.unparsedEntityPublicId(), SQ, S));

        // Node selection / accumulator / snapshot
        map.put("accumulator-before", typed(NodeSelectionFunctions.accumulatorBefore(), IS, S));
        map.put("accumulator-after",  typed(NodeSelectionFunctions.accumulatorAfter(), IS, S));
        map.put("snapshot",           typed(NodeSelectionFunctions.snapshot(), NS, NQ));
        map.put("outermost",          typed(NodeSelectionFunctions.outermost(), NS, NS));
        map.put("innermost",          typed(NodeSelectionFunctions.innermost(), NS, NS));

        // JSON functions
        map.put("json-to-xml", typed(JsonFunctions.jsonToXml(), DN, S, IS));
        map.put("xml-to-json", typed(JsonFunctions.xmlToJson(), S, NQ, IS));
        map.put("parse-json",  typed(JsonFunctions.parseJson(), IT, S, IS));

        // Higher-order functions
        map.put("fold-left",     typed(HigherOrderFunctions.foldLeft(), IS, IS, IS, IS));
        map.put("fold-right",    typed(HigherOrderFunctions.foldRight(), IS, IS, IS, IS));
        map.put("for-each",      typed(HigherOrderFunctions.forEach(), IS, IS, IS));
        map.put("filter",        typed(HigherOrderFunctions.filter(), IS, IS, IS));
        map.put("for-each-pair", typed(HigherOrderFunctions.forEachPair(), IS, IS, IS, IS));
        map.put("sort",          typed(HigherOrderFunctions.sort(), IS, IS, IS, IS));
        map.put("apply",         typed(HigherOrderFunctions.apply(), IS, IS, IS));
        map.put("transform",     typed(HigherOrderFunctions.transform(), IS, IS));

        this.xsltFunctions = Collections.unmodifiableMap(map);
    }

    @Override
    public boolean hasFunction(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            if (xsltFunctions.containsKey(localName)) {
                return true;
            }
        }
        if (namespaceURI != null && !namespaceURI.isEmpty()) {
            return true;
        }
        return CoreFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName);
    }

    @Override
    public XPathValue invokeFunction(String namespaceURI, String localName, 
                                     List<XPathValue> args, XPathContext context) 
            throws XPathException {
        if (namespaceURI == null || namespaceURI.isEmpty() || FN_NAMESPACE.equals(namespaceURI)) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return f.evaluate(args, context);
            }
            double version = context.getXsltVersion();
            if (version >= 2.0 && version < 3.0) {
                if (isNewArityInXPath30(localName, args.size())) {
                    throw new XPathException("XPST0017: Function " + localName +
                        " with " + args.size() + " argument(s) is not available in XSLT 2.0");
                }
            }
            if (FN_NAMESPACE.equals(namespaceURI)) {
                return CoreFunctionLibrary.INSTANCE.invokeFunction(null, localName, args, context);
            }
        }
        
        if (XS_NAMESPACE.equals(namespaceURI)) {
            return XsConstructorFunctions.invoke(localName, args, context);
        }
        
        if (MATH_NAMESPACE.equals(namespaceURI)) {
            return MathFunctions.invoke(localName, args);
        }
        
        if (MAP_NAMESPACE.equals(namespaceURI)) {
            return MapFunctions.invoke(localName, args, context);
        }
        
        if (ARRAY_NAMESPACE.equals(namespaceURI)) {
            return ArrayFunctions.invoke(localName, args, context);
        }
        
        if (namespaceURI != null && !namespaceURI.isEmpty() && context instanceof TransformContext) {
            TransformContext transformContext = (TransformContext) context;
            CompiledStylesheet stylesheet = transformContext.getStylesheet();
            if (stylesheet != null) {
                UserFunction userFunc = stylesheet.getUserFunction(namespaceURI, localName, args.size());
                if (userFunc != null) {
                    if (transformContext instanceof BasicTransformContext) {
                        BasicTransformContext btc = (BasicTransformContext) transformContext;
                        if (btc.isDynamicEvaluation()) {
                            ComponentVisibility vis = userFunc.getVisibility();
                            if (vis == ComponentVisibility.PRIVATE
                                    || vis == ComponentVisibility.HIDDEN) {
                                throw new XPathException(
                                    "XTDE3160: Cannot call private function "
                                    + localName + " from xsl:evaluate");
                            }
                        }
                    }
                    return invokeUserFunction(userFunc, args, transformContext);
                }
            }
        }
        
        return CoreFunctionLibrary.INSTANCE.invokeFunction(namespaceURI, localName, args, context);
    }
    
    private static boolean isNewArityInXPath30(String localName, int arity) {
        switch (localName) {
            case "round":
                return arity == 2;
            case "string-join":
                return arity == 1;
            case "node-name":
            case "nilled":
            case "data":
            case "document-uri":
                return arity == 0;
            case "unparsed-entity-uri":
            case "unparsed-entity-public-id":
                return arity == 2;
            default:
                return false;
        }
    }

    public XPathValue invokeUserFunction(UserFunction function, List<XPathValue> args, 
                                         TransformContext context) throws XPathException {
        return UserFunctionInvoker.invoke(function, args, context);
    }

    public XPathValue invokeUserFunctionStandalone(UserFunction function,
            List<XPathValue> args, XPathContext context) throws XPathException {
        return UserFunctionInvoker.invokeStandalone(function, args, context);
    }

    static XPathValue invokeFunctionItem(XPathValue funcItem, List<XPathValue> args,
            XPathContext context, String callerName) throws XPathException {
        return UserFunctionInvoker.invokeFunctionItem(funcItem, args, context, callerName);
    }

    @Override
    public int getArgumentCount(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return -1;
            }
        }
        return CoreFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
    }

    @Override
    public Function getFunction(String namespaceURI, String localName, int arity) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return f;
            }
        }
        return CoreFunctionLibrary.INSTANCE.getFunction(namespaceURI, localName, arity);
    }

    Function getXsltFunction(String localName) {
        return xsltFunctions.get(localName);
    }

}
