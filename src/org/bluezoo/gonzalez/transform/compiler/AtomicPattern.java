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

import java.util.Collections;
import java.util.List;

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
 * <p>Predicates are stored individually and evaluated sequentially.
 * Numeric predicates are compared to position (always 1 for atomics),
 * so {@code [1]} always matches and {@code [2]} never matches.
 *
 * <p>Default priority is {@code 0.5 * predicateCount} per XSLT 3.0 spec.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class AtomicPattern extends AbstractPattern {

    private static final double POSITION_TOLERANCE = 0.0001;

    private final List<String> predicates;

    AtomicPattern(String patternStr, List<String> predicates) {
        super(patternStr, null);
        this.predicates = predicates;
    }

    @Override
    public boolean matches(XPathNode node, TransformContext context) {
        if (predicates == null || predicates.isEmpty()) {
            return true;
        }
        try {
            XPathContext xpathContext = context.withContextNode(node)
                .withPositionAndSize(1, 1);
            return evaluatePredicateList(xpathContext);
        } catch (XPathException e) {
            return false;
        } catch (XPathSyntaxException e) {
            return false;
        }
    }

    @Override
    boolean matchesBase(XPathNode node, TransformContext context,
                        XPathNode targetNode) {
        return matches(node, context);
    }

    @Override
    public boolean canMatchAtomicValues() {
        return true;
    }

    @Override
    public boolean matchesAtomicValue(XPathValue value,
                                      TransformContext context) {
        if (predicates == null || predicates.isEmpty()) {
            return true;
        }
        try {
            XPathContext xpathContext = context.withContextItem(value)
                .withPositionAndSize(1, 1);
            return evaluatePredicateList(xpathContext);
        } catch (XPathException e) {
            return false;
        } catch (XPathSyntaxException e) {
            return false;
        }
    }

    List<String> getPredicates() {
        if (predicates == null) {
            return Collections.emptyList();
        }
        return predicates;
    }

    private boolean evaluatePredicateList(XPathContext xpathContext)
            throws XPathException, XPathSyntaxException {
        XPathParser.NamespaceResolver nsResolver =
            new XPathParser.NamespaceResolver() {
                @Override
                public String resolve(String prefix) {
                    return xpathContext.resolveNamespacePrefix(prefix);
                }
            };

        for (int i = 0; i < predicates.size(); i++) {
            String pred = predicates.get(i);
            XPathParser parser = new XPathParser(pred, nsResolver);
            XPathValue result = parser.parse().evaluate(xpathContext);

            if (result.getType() == XPathValue.Type.NUMBER) {
                double d = result.asNumber();
                if (Double.isNaN(d) ||
                    Math.abs(d - 1.0) > POSITION_TOLERANCE) {
                    return false;
                }
            } else {
                if (!result.asBoolean()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public double getDefaultPriority() {
        if (predicates == null || predicates.isEmpty()) {
            return -0.5;
        }
        return 0.5 * predicates.size();
    }
}
