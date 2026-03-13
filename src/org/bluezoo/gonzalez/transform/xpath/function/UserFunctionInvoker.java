/*
 * UserFunctionInvoker.java
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gonzalez.transform.ErrorHandlingMode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.compiler.UserFunction;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TemplateMatcher;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.expr.PartialFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathTypeException;
import org.bluezoo.gonzalez.transform.xpath.type.SchemaContext;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAnyURI;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.NativeFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;

import org.xml.sax.SAXException;

/**
 * Extracts user function invocation and type coercion logic.
 * Package-private utility for invoking user-defined XSLT functions.
 *
 * @author Chris Burdess
 */
final class UserFunctionInvoker {

    // Key: function key + stringified arguments, Value: cached result
    static final Map<String, XPathValue> functionCache = new ConcurrentHashMap<String, XPathValue>();

    private UserFunctionInvoker() {
    }

    /**
     * Invokes a user-defined function.
     *
     * @param function the user-defined function to invoke
     * @param args the function arguments
     * @param context the transformation context
     * @return the function result as a Result Tree Fragment
     * @throws XPathException if function execution fails
     */
    static XPathValue invoke(UserFunction function, List<XPathValue> args,
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
        // For functions imported from packages, execute with the defining
        // package's stylesheet so private functions/templates are accessible
        CompiledStylesheet definingStylesheet = function.getDefiningStylesheet();
        if (definingStylesheet != null) {
            funcContext = funcContext.withStylesheet(definingStylesheet);
        }
        if (funcContext instanceof BasicTransformContext) {
            BasicTransformContext btc = (BasicTransformContext) funcContext;
            btc.setContextItemUndefined(true);
            btc.setInsideMergeAction(false);
        }

        // Bind parameters to arguments, coercing and type-checking as needed.
        // In dynamic evaluation (xsl:evaluate), xs:string cannot promote to
        // numeric types per XSLT 3.0 spec — only xs:untypedAtomic can.
        boolean isDynamic = false;
        if (context instanceof BasicTransformContext) {
            isDynamic = ((BasicTransformContext) context).isDynamicEvaluation();
        }
        List<UserFunction.FunctionParameter> params = function.getParameters();
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            XPathValue argVal = args.get(i);
            String paramType = params.get(i).getAsType();
            if (argVal instanceof XPathUntypedAtomic) {
                argVal = coerceUntypedAtomic((XPathUntypedAtomic) argVal, paramType);
            } else if (paramType != null && !paramType.isEmpty()) {
                if (isDynamic && argVal instanceof XPathString
                        && isNumericParamType(paramType)) {
                    throw new XPathException("XPTY0004: Required item type of "
                        + "parameter $" + params.get(i).getName() + " is "
                        + paramType + "; supplied value has type xs:string");
                }
                // Validate function-typed parameters against declared type
                if (paramType.contains("function(")) {
                    validateFunctionTypeParam(argVal, paramType, params.get(i).getName());
                }
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
                SequenceBuilderOutputHandler output =
                    new SequenceBuilderOutputHandler();
                function.getBody().execute(funcContext, output);
                // When an explicit 'as' type is declared, merge adjacent text
                // items produced by consecutive instructions in the function
                // body. Without 'as', each instruction produces a separate
                // item (per XSLT spec for default item()* return type).
                if (asType != null && !asType.isEmpty()) {
                    output.mergeAdjacentTextItems();
                }
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
        } catch (SAXException e) {
            throw new XPathException("Error executing function " +
                function.getLocalName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Invokes a user-defined function with a plain XPathContext (no TransformContext).
     * Used for cross-stylesheet function references (e.g., from fn:transform() results
     * invoked in a static/use-when evaluation context).
     */
    static XPathValue invokeStandalone(UserFunction function,
            List<XPathValue> args, XPathContext context) throws XPathException {
        try {
            SequenceBuilderOutputHandler output = new SequenceBuilderOutputHandler();
            BasicTransformContext funcContext = new BasicTransformContext(
                null, null, new TemplateMatcher(null), output, null);
            funcContext.setContextItemUndefined(true);

            List<UserFunction.FunctionParameter> params = function.getParameters();
            for (int i = 0; i < params.size() && i < args.size(); i++) {
                XPathValue argVal = args.get(i);
                String paramType = params.get(i).getAsType();
                if (argVal instanceof XPathUntypedAtomic) {
                    argVal = coerceUntypedAtomic((XPathUntypedAtomic) argVal, paramType);
                } else if (paramType != null && !paramType.isEmpty()) {
                    argVal = coerceArgument(argVal, paramType, params.get(i).getName());
                }
                funcContext.getVariableScope().bind(
                    params.get(i).getNamespaceURI(),
                    params.get(i).getLocalName(), argVal);
            }

            function.getBody().execute(funcContext, output);
            return output.getSequence();
        } catch (SAXException e) {
            throw new XPathException("Error executing function " +
                function.getLocalName() + ": " + e.getMessage(), e);
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
        if (funcItem instanceof NativeFunctionItem) {
            return ((NativeFunctionItem) funcItem).invoke(args, context);
        }
        if (funcItem instanceof XPathFunctionItem) {
            return ((XPathFunctionItem) funcItem).invoke(args, context);
        }
        throw new XPathException(callerName + ": argument is not a function item");
    }

    /**
     * Builds a cache key for a function call (for memoized functions).
     *
     * @param function the function being called
     * @param args the function arguments
     * @return a cache key string
     */
    static String buildCacheKey(UserFunction function, List<XPathValue> args) {
        StringBuilder sb = new StringBuilder();
        sb.append(function.getKey());
        for (XPathValue arg : args) {
            sb.append("|");
            if (arg == null) {
                sb.append("null");
            } else if (arg instanceof XPathQName) {
                XPathQName qn = (XPathQName) arg;
                sb.append("Q{");
                String ns = qn.getNamespaceURI();
                if (ns != null) {
                    sb.append(ns);
                }
                sb.append("}");
                sb.append(qn.getLocalName());
            } else if (arg instanceof XPathNode) {
                sb.append("node@");
                sb.append(System.identityHashCode(arg));
            } else if (arg instanceof XPathNodeSet) {
                sb.append("ns@");
                sb.append(System.identityHashCode(arg));
            } else if (arg instanceof XPathMap) {
                sb.append("map@");
                sb.append(System.identityHashCode(arg));
            } else if (arg instanceof InlineFunctionItem || arg instanceof PartialFunctionItem) {
                sb.append("fn@");
                sb.append(System.identityHashCode(arg));
            } else {
                sb.append(arg.asString());
            }
        }
        return sb.toString();
    }

    /**
     * Coerces an xs:untypedAtomic value to the target type declared by a function parameter.
     * Per XPath 2.0+, untypedAtomic values are automatically cast to the expected type.
     */
    static XPathValue coerceUntypedAtomic(XPathUntypedAtomic value, String asType) {
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
                return new XPathNumber(new BigDecimal(strVal));
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
                    return XPathNumber.ofInteger(new BigInteger(strVal));
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
    static boolean isNumericParamType(String asType) {
        String baseType = asType.trim();
        if (baseType.endsWith("*") || baseType.endsWith("+") || baseType.endsWith("?")) {
            baseType = baseType.substring(0, baseType.length() - 1).trim();
        }
        if (baseType.startsWith("xs:")) {
            baseType = baseType.substring(3);
        }
        return "integer".equals(baseType) || "int".equals(baseType)
            || "long".equals(baseType) || "short".equals(baseType)
            || "byte".equals(baseType) || "decimal".equals(baseType)
            || "double".equals(baseType) || "float".equals(baseType)
            || "nonNegativeInteger".equals(baseType)
            || "positiveInteger".equals(baseType)
            || "nonPositiveInteger".equals(baseType)
            || "negativeInteger".equals(baseType)
            || "unsignedLong".equals(baseType) || "unsignedInt".equals(baseType)
            || "unsignedShort".equals(baseType) || "unsignedByte".equals(baseType);
    }

    static XPathValue coerceArgument(XPathValue value, String asType, String paramName)
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
                    String trimmedVal = strVal.trim();
                    return new XPathNumber(new BigDecimal(trimmedVal));
                } else {
                    String trimmedVal = strVal.trim();
                    return XPathNumber.of(Double.parseDouble(trimmedVal));
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
            throw new XPathException("XTTE0790", "Cannot convert string '" + strVal
                + "' to xs:boolean for parameter $" + paramName);
        }
        // Atomize node values to string when target type is xs:string
        if ("string".equals(baseType)
                && (value instanceof XPathNode || value.isNodeSet())) {
            // For sequence types (xs:string*, xs:string+), atomize each
            // node individually to produce a sequence of strings
            boolean isSequenceType = type.endsWith("*") || type.endsWith("+");
            if (isSequenceType && value instanceof XPathNodeSet) {
                XPathNodeSet ns = (XPathNodeSet) value;
                if (ns.isEmpty()) {
                    return XPathSequence.empty();
                }
                List<XPathValue> strings = new ArrayList<XPathValue>();
                for (int n = 0; n < ns.size(); n++) {
                    XPathNode node = ns.get(n);
                    if (node != null) {
                        strings.add(new XPathString(node.getStringValue()));
                    }
                }
                if (strings.isEmpty()) {
                    return XPathSequence.empty();
                }
                return new XPathSequence(strings);
            }
            return new XPathString(value.asString());
        }
        // XTTE0790: reject values incompatible with date/time types
        boolean isDateTimeType = "date".equals(baseType) || "time".equals(baseType)
                || "dateTime".equals(baseType) || "dateTimeStamp".equals(baseType)
                || "duration".equals(baseType) || "dayTimeDuration".equals(baseType)
                || "yearMonthDuration".equals(baseType) || "gYear".equals(baseType)
                || "gMonth".equals(baseType) || "gDay".equals(baseType)
                || "gYearMonth".equals(baseType) || "gMonthDay".equals(baseType);
        if (isDateTimeType && !(value instanceof XPathDateTime)) {
            throw new XPathException("XTTE0790", "Cannot convert "
                    + value.getType() + " to xs:" + baseType
                    + " for parameter $" + paramName);
        }
        return value;
    }

    /**
     * Validates that a function item (or sequence of function items) is
     * coercion-compatible with the declared function type of a parameter.
     * Throws XPTY0004 only if the types are fundamentally incompatible
     * (e.g., xs:integer return where xs:string is expected).
     * Per XSLT 3.0, function coercion succeeds if the arity matches;
     * type errors only arise when the coerced function is actually invoked.
     * However, if the function's declared signature is known and the types
     * are from incompatible families, we raise the error early.
     */
    static void validateFunctionTypeParam(XPathValue value, String paramType,
            String paramName) throws XPathException {
        SequenceType expectedType = SequenceType.parse(paramType, null);
        if (expectedType == null) {
            return;
        }
        if (expectedType.getItemKind() != SequenceType.ItemKind.FUNCTION) {
            return;
        }
        SequenceType[] expectedParams = expectedType.getParameterTypes();
        SequenceType expectedReturn = expectedType.getReturnType();
        if (expectedParams == null) {
            return;
        }
        List<XPathValue> items = new ArrayList<XPathValue>();
        if (value instanceof XPathSequence) {
            for (XPathValue item : (XPathSequence) value) {
                items.add(item);
            }
        } else {
            items.add(value);
        }
        for (XPathValue item : items) {
            SequenceType[] actualParams = null;
            SequenceType actualReturn = null;
            if (item instanceof XPathFunctionItem) {
                XPathFunctionItem fi = (XPathFunctionItem) item;
                actualParams = fi.getParameterTypes();
                actualReturn = fi.getReturnType();
            } else if (item instanceof InlineFunctionItem) {
                InlineFunctionItem inl = (InlineFunctionItem) item;
                actualParams = inl.getParameterTypes();
                actualReturn = inl.getReturnType();
            }
            if (actualParams == null && actualReturn == null) {
                continue;
            }
            int actualArity = actualParams != null ? actualParams.length : -1;
            if (actualArity >= 0 && actualArity != expectedParams.length) {
                throw new XPathException("XPTY0004: Required item type of "
                    + "parameter $" + paramName + " is " + paramType
                    + "; supplied function has wrong arity");
            }
            // Check return type compatibility (covariant):
            // actual return must be coercible to expected return
            if (expectedReturn != null && !isCoercionCompatible(actualReturn, expectedReturn)) {
                throw new XPathException("XPTY0004: Required item type of "
                    + "parameter $" + paramName + " is " + paramType
                    + "; supplied function does not match");
            }
            // Check parameter type compatibility (contravariant):
            // expected param must be coercible to actual param
            if (actualParams == null) {
                continue;
            }
            for (int p = 0; p < expectedParams.length && p < actualParams.length; p++) {
                if (!isCoercionCompatible(expectedParams[p], actualParams[p])) {
                    throw new XPathException("XPTY0004: Required item type of "
                        + "parameter $" + paramName + " is " + paramType
                        + "; supplied function does not match");
                }
            }
        }
    }

    /**
     * Checks if a source type can be coerced to a target type under function
     * coercion rules. More permissive than subtype: allows numeric promotion
     * and same-family types with different occurrences, but rejects
     * cross-family coercions (e.g., xs:integer to xs:string).
     */
    static boolean isCoercionCompatible(SequenceType source, SequenceType target) {
        if (source == null || target == null) {
            return true;
        }
        // item() or item()* etc. is universally compatible
        if (source.getItemKind() == SequenceType.ItemKind.ITEM
                || target.getItemKind() == SequenceType.ItemKind.ITEM) {
            return true;
        }
        // Both must be atomic for detailed checking
        if (source.getItemKind() != SequenceType.ItemKind.ATOMIC
                || target.getItemKind() != SequenceType.ItemKind.ATOMIC) {
            return true;
        }
        String sourceLocal = source.getLocalName();
        String targetLocal = target.getLocalName();
        if (sourceLocal == null || targetLocal == null) {
            return true;
        }
        // anyAtomicType is compatible with anything
        if ("anyAtomicType".equals(sourceLocal) || "anyAtomicType".equals(targetLocal)) {
            return true;
        }
        // Same type (ignoring occurrence) is always compatible
        if (sourceLocal.equals(targetLocal)) {
            return true;
        }
        // Numeric family: all numeric types are promotion-compatible
        if (isNumericFamily(sourceLocal) && isNumericFamily(targetLocal)) {
            return true;
        }
        // String family: string subtypes are compatible
        if (isStringFamily(sourceLocal) && isStringFamily(targetLocal)) {
            return true;
        }
        // anyURI -> string promotion
        if ("anyURI".equals(sourceLocal) && isStringFamily(targetLocal)) {
            return true;
        }
        if ("anyURI".equals(targetLocal) && isStringFamily(sourceLocal)) {
            return true;
        }
        // untypedAtomic can be promoted to any type
        if ("untypedAtomic".equals(sourceLocal)) {
            return true;
        }
        // Different families: incompatible
        return false;
    }

    static boolean isNumericFamily(String localName) {
        return "integer".equals(localName) || "decimal".equals(localName)
            || "float".equals(localName) || "double".equals(localName)
            || "numeric".equals(localName) || "long".equals(localName)
            || "int".equals(localName) || "short".equals(localName)
            || "byte".equals(localName) || "nonNegativeInteger".equals(localName)
            || "positiveInteger".equals(localName) || "nonPositiveInteger".equals(localName)
            || "negativeInteger".equals(localName) || "unsignedLong".equals(localName)
            || "unsignedInt".equals(localName) || "unsignedShort".equals(localName)
            || "unsignedByte".equals(localName);
    }

    static boolean isStringFamily(String localName) {
        return "string".equals(localName) || "normalizedString".equals(localName)
            || "token".equals(localName) || "language".equals(localName)
            || "NMTOKEN".equals(localName) || "Name".equals(localName)
            || "NCName".equals(localName) || "ID".equals(localName)
            || "IDREF".equals(localName) || "ENTITY".equals(localName);
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
    static boolean isAtomicOrSequenceType(String asType) {
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
     * Coerces the function result to match the declared 'as' type.
     *
     * @param result the function result
     * @param asType the declared return type string (e.g., "xs:double", "xs:integer?")
     * @param context the XPath context
     * @return the coerced value
     * @throws XPathException if coercion fails
     */
    static XPathValue coerceToType(XPathValue result, String asType, XPathContext context)
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
                    // Sequence-to-sequence coercion: convert each item individually
                    SequenceType.Occurrence occ = expectedType.getOccurrence();
                    boolean multiAllowed =
                        occ == SequenceType.Occurrence.ZERO_OR_MORE
                        || occ == SequenceType.Occurrence.ONE_OR_MORE;
                    if (multiAllowed && result instanceof XPathSequence) {
                        XPathSequence seq = (XPathSequence) result;
                        List<XPathValue> converted = new ArrayList<>(seq.size());
                        for (XPathValue item : seq) {
                            String itemStr = item.asString();
                            XPathValue conv = convertStringToAtomicType(itemStr, typeLocalName);
                            if (conv == null) {
                                throw new XPathTypeException(
                                    "XTTE0505",
                                    "Cannot coerce sequence item '" + itemStr
                                    + "' to type " + typeLocalName,
                                    asType,
                                    item.getClass().getSimpleName());
                            }
                            converted.add(conv);
                        }
                        return new XPathSequence(converted);
                    }

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
    static XPathValue convertStringToAtomicType(String value, String typeName) throws XPathException {
        try {
            switch (typeName) {
                case "string":
                    return new XPathString(value);

                case "double":
                case "float":
                    return new XPathNumber(parseXPathDouble(value));
                case "decimal":
                    return new XPathNumber(new BigDecimal(value));

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
                        BigDecimal bd = new BigDecimal(intStr);
                        return XPathNumber.ofInteger(bd.toBigInteger());
                    }
                    try {
                        return XPathNumber.ofInteger(Long.parseLong(intStr));
                    } catch (NumberFormatException nfe) {
                        return XPathNumber.ofInteger(new BigInteger(intStr));
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

    static double parseXPathDouble(String value) {
        return XPathNumber.parseXPathDouble(value);
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
    static void validateFunctionReturnType(XPathValue result, String asType, String functionName,
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
    static SequenceType parseAsType(String asType) throws XPathException {
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
}
