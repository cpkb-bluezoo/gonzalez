/*
 * PathExpr.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.List;

/**
 * A path expression (filter expression followed by relative location path).
 *
 * <p>A path expression combines a filter expression with a relative location
 * path. The filter is evaluated first, and then the location path is evaluated
 * relative to each node in the result.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code $var/child} - child elements of nodes in $var</li>
 *   <li>{@code id('foo')//bar} - bar descendants of element with id 'foo'</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class PathExpr implements Expr {

    private final Expr filter;
    private final LocationPath path;

    /**
     * Creates a path expression.
     *
     * @param filter the filter expression (produces the starting node-set)
     * @param path the relative location path to apply
     */
    public PathExpr(Expr filter, LocationPath path) {
        if (filter == null || path == null) {
            throw new NullPointerException("Filter and path cannot be null");
        }
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be relative");
        }
        this.filter = filter;
        this.path = path;
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        XPathValue filterResult = filter.evaluate(context);

        if (filterResult == null) {
            return XPathNodeSet.EMPTY;
        }

        // Collect context nodes from the filter result
        List<XPathNode> contextNodes = new ArrayList<>();

        if (filterResult.isNodeSet()) {
            XPathNodeSet nodeSet = filterResult.asNodeSet();
            if (nodeSet != null) {
                for (XPathNode n : nodeSet) {
                    contextNodes.add(n);
                }
            }
        } else if (filterResult.isSequence()) {
            // XPath 2.0+: sequences may contain nodes
            for (XPathValue item : (XPathSequence) filterResult) {
                if (item instanceof XPathNode) {
                    contextNodes.add((XPathNode) item);
                }
            }
        } else if (filterResult instanceof XPathNode) {
            contextNodes.add((XPathNode) filterResult);
        }

        if (contextNodes.isEmpty()) {
            return XPathNodeSet.EMPTY;
        }

        // Evaluate the path for each context node
        List<XPathNode> nodeResults = new ArrayList<>();
        List<XPathValue> atomicResults = new ArrayList<>();
        boolean hasAtomicResults = false;

        for (XPathNode node : contextNodes) {
            XPathContext nodeContext = context.withContextNode(node);
            XPathValue pathResult = path.evaluate(nodeContext);

            if (pathResult.isNodeSet()) {
                for (XPathNode resultNode : pathResult.asNodeSet()) {
                    boolean found = false;
                    for (XPathNode existing : nodeResults) {
                        if (existing.isSameNode(resultNode)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        nodeResults.add(resultNode);
                    }
                }
            } else if (pathResult.isSequence()) {
                for (XPathValue item : (XPathSequence) pathResult) {
                    if (item instanceof XPathNode) {
                        nodeResults.add((XPathNode) item);
                    } else {
                        atomicResults.add(item);
                        hasAtomicResults = true;
                    }
                }
            } else {
                atomicResults.add(pathResult);
                hasAtomicResults = true;
            }
        }

        if (hasAtomicResults) {
            if (atomicResults.size() == 1) {
                return atomicResults.get(0);
            }
            return new XPathSequence(atomicResults);
        }

        if (nodeResults.isEmpty()) {
            return XPathNodeSet.EMPTY;
        }
        return new XPathNodeSet(nodeResults);
    }

    /**
     * Returns the filter expression.
     *
     * @return the filter
     */
    public Expr getFilter() {
        return filter;
    }

    /**
     * Returns the relative location path.
     *
     * @return the path
     */
    public LocationPath getPath() {
        return path;
    }

    @Override
    public String toString() {
        return filter + "/" + path;
    }

}
