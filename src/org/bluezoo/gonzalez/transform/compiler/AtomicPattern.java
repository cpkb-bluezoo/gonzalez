/*
 * AtomicPattern.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT 3.0 atomic value pattern: {@code .[ predicate ]}.
 * Matches atomic values rather than nodes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class AtomicPattern extends AbstractPattern {

    private final String atomicPredicate;

    AtomicPattern(String patternStr, String atomicPredicate) {
        super(patternStr, null);
        this.atomicPredicate = atomicPredicate;
    }

    @Override
    public boolean matches(XPathNode node, TransformContext context) {
        return false;
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        return false;
    }

    @Override
    public boolean canMatchAtomicValues() {
        return true;
    }

    @Override
    public boolean matchesAtomicValue(XPathValue value,
                                      TransformContext context) {
        try {
            XPathContext xpathContext = context.withContextItem(value)
                .withPositionAndSize(1, 1);

            XPathParser.NamespaceResolver nsResolver =
                new XPathParser.NamespaceResolver() {
                    @Override
                    public String resolve(String prefix) {
                        return xpathContext.resolveNamespacePrefix(prefix);
                    }
                };

            XPathParser parser = new XPathParser(atomicPredicate, nsResolver);
            XPathValue result = parser.parse().evaluate(xpathContext);
            return result.asBoolean();
        } catch (XPathException e) {
            return false;
        } catch (XPathSyntaxException e) {
            return false;
        }
    }

    @Override
    public double getDefaultPriority() {
        return 0.5;
    }
}
