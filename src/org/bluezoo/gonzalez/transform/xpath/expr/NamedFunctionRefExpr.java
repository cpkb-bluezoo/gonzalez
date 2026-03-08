/*
 * NamedFunctionRefExpr.java
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

package org.bluezoo.gonzalez.transform.xpath.expr;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.function.Function;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathFunctionItem;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.UserFunction;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;

import java.util.List;

/**
 * XPath 3.0 named function reference: {@code name#arity}.
 *
 * <p>Evaluates to a function item that can be passed around as a value
 * and invoked via dynamic function calls. The function is resolved at
 * evaluation time from the function library.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NamedFunctionRefExpr implements Expr {

    private static final String FN_NAMESPACE = "http://www.w3.org/2005/xpath-functions";

    private final String prefix;
    private final String localName;
    private final String resolvedURI;
    private final int arity;

    /**
     * Creates a named function reference expression.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the function local name
     * @param arity the expected arity
     */
    public NamedFunctionRefExpr(String prefix, String localName, int arity) {
        this.prefix = prefix;
        this.localName = localName;
        this.resolvedURI = null;
        this.arity = arity;
    }

    /**
     * Creates a named function reference with a resolved namespace URI.
     *
     * @param prefix the namespace prefix (may be null)
     * @param localName the function local name
     * @param resolvedURI the resolved namespace URI (may be null)
     * @param arity the expected arity
     */
    public NamedFunctionRefExpr(String prefix, String localName, String resolvedURI, int arity) {
        this.prefix = prefix;
        this.localName = localName;
        this.resolvedURI = resolvedURI;
        this.arity = arity;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        String fullName;
        if (prefix != null && !prefix.isEmpty()) {
            fullName = prefix + ":" + localName;
        } else {
            fullName = localName;
        }
        
        XPathFunctionLibrary library = context.getFunctionLibrary();
        if (library == null) {
            throw new XPathException("XPST0017: No function library available for " + fullName + "#" + arity);
        }

        // Determine the effective namespace URI for lookup
        String effectiveURI = resolvedURI;
        if (effectiveURI == null && prefix != null && !prefix.isEmpty()) {
            effectiveURI = context.resolveNamespacePrefix(prefix);
        }
        
        // Check built-in functions first (use fullName for unprefixed)
        if (library.hasFunction(effectiveURI, localName)) {
            String itemURI = effectiveURI;
            if (itemURI == null || itemURI.isEmpty()) {
                itemURI = FN_NAMESPACE;
            }
            XPathFunctionItem funcItem = new XPathFunctionItem(fullName, itemURI, arity, library);
            setUserFunctionSignature(funcItem, itemURI, context);
            bindUserFunction(funcItem, itemURI, context);
            return funcItem;
        }
        if (library.hasFunction(null, fullName)) {
            return new XPathFunctionItem(fullName, FN_NAMESPACE, arity, library);
        }
        
        // For namespaced functions, the function library may resolve user-defined
        // functions at invoke time — return a function item that will do that
        if (effectiveURI != null && !effectiveURI.isEmpty()) {
            XPathFunctionItem funcItem = new XPathFunctionItem(fullName, effectiveURI, arity, library);
            setUserFunctionSignature(funcItem, effectiveURI, context);
            bindUserFunction(funcItem, effectiveURI, context);
            return funcItem;
        }
        
        throw new XPathException("XPST0017: Unknown function: " + fullName + "#" + arity);
    }

    /**
     * Looks up the UserFunction signature and sets parameter/return types on the function item.
     */
    private void setUserFunctionSignature(XPathFunctionItem funcItem,
                                          String nsURI, XPathContext context) {
        // Try user function first
        if (context instanceof TransformContext) {
            TransformContext tc = (TransformContext) context;
            CompiledStylesheet stylesheet = tc.getStylesheet();
            if (stylesheet != null) {
                UserFunction uf = stylesheet.getUserFunction(nsURI, localName, arity);
                if (uf != null) {
                    List params = uf.getParameters();
                    SequenceType[] paramTypes = new SequenceType[params.size()];
                    for (int i = 0; i < params.size(); i++) {
                        UserFunction.FunctionParameter fp = (UserFunction.FunctionParameter) params.get(i);
                        String asType = fp.getAsType();
                        if (asType != null && !asType.isEmpty()) {
                            SequenceType pt = SequenceType.parse(asType, null);
                            if (pt != null) {
                                paramTypes[i] = pt;
                            } else {
                                paramTypes[i] = SequenceType.ITEM_STAR;
                            }
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
                    funcItem.setSignature(paramTypes, returnType);
                    return;
                }
            }
        }
        // Fall back to built-in function signature from the function library
        XPathFunctionLibrary library = context.getFunctionLibrary();
        if (library != null) {
            setBuiltInFunctionSignature(funcItem, nsURI, library);
        }
    }

    /**
     * Sets parameter/return types on a function item from built-in function metadata.
     */
    private void setBuiltInFunctionSignature(XPathFunctionItem funcItem,
                                             String nsURI, XPathFunctionLibrary library) {
        Function func = library.getFunction(nsURI, localName, arity);
        if (func == null && (nsURI == null || nsURI.isEmpty() || FN_NAMESPACE.equals(nsURI))) {
            func = library.getFunction(null, localName, arity);
        }
        if (func == null) {
            return;
        }
        SequenceType retType = func.getReturnType();
        SequenceType[] paramSeqTypes = func.getParameterSequenceTypes();
        if (retType == null && paramSeqTypes == null) {
            return;
        }
        SequenceType returnType = retType != null ? retType : SequenceType.ITEM_STAR;
        SequenceType[] paramTypes;
        if (paramSeqTypes != null) {
            paramTypes = new SequenceType[arity];
            for (int i = 0; i < arity; i++) {
                if (i < paramSeqTypes.length) {
                    paramTypes[i] = paramSeqTypes[i];
                } else {
                    paramTypes[i] = SequenceType.ITEM_STAR;
                }
            }
        } else {
            paramTypes = new SequenceType[arity];
            for (int i = 0; i < arity; i++) {
                paramTypes[i] = SequenceType.ITEM_STAR;
            }
        }
        funcItem.setSignature(paramTypes, returnType);
    }

    /**
     * Binds the actual UserFunction to the function item for cross-stylesheet portability.
     */
    private void bindUserFunction(XPathFunctionItem funcItem,
                                  String nsURI, XPathContext context) {
        if (!(context instanceof TransformContext)) {
            return;
        }
        TransformContext tc = (TransformContext) context;
        CompiledStylesheet stylesheet = tc.getStylesheet();
        if (stylesheet == null) {
            return;
        }
        UserFunction uf = stylesheet.getUserFunction(nsURI, localName, arity);
        if (uf != null) {
            funcItem.setBoundUserFunction(uf);
        }
    }

    @Override
    public String toString() {
        if (prefix != null && !prefix.isEmpty()) {
            return prefix + ":" + localName + "#" + arity;
        }
        return localName + "#" + arity;
    }
}
