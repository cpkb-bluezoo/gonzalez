/*
 * TypeAnnotatedFunction.java
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

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.List;

/**
 * Wraps an existing {@link Function} with static return type and parameter
 * type annotations. All other method calls are delegated to the wrapped
 * function.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class TypeAnnotatedFunction implements Function {

    private final Function delegate;
    private final SequenceType returnType;
    private final SequenceType[] parameterTypes;

    TypeAnnotatedFunction(Function delegate, SequenceType returnType,
                          SequenceType[] parameterTypes) {
        this.delegate = delegate;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
    }

    /**
     * Wraps a function with type annotations.
     *
     * @param f the function to wrap
     * @param ret the return type
     * @param params the parameter types
     * @return the annotated function
     */
    static Function typed(Function f, SequenceType ret, SequenceType... params) {
        return new TypeAnnotatedFunction(f, ret, params.length > 0 ? params : null);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public int getMinArgs() {
        return delegate.getMinArgs();
    }

    @Override
    public int getMaxArgs() {
        return delegate.getMaxArgs();
    }

    @Override
    public ArgType[] getArgumentTypes() {
        return delegate.getArgumentTypes();
    }

    @Override
    public SequenceType getReturnType() {
        return returnType;
    }

    @Override
    public SequenceType[] getParameterSequenceTypes() {
        return parameterTypes;
    }

    @Override
    public XPathValue evaluate(List<XPathValue> args, XPathContext context)
            throws XPathException {
        return delegate.evaluate(args, context);
    }
}
