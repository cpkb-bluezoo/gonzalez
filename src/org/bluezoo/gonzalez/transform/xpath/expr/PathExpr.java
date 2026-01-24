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
        
        if (!filterResult.isNodeSet()) {
            throw new XPathException("Path expression requires node-set from filter");
        }
        
        XPathNodeSet nodeSet = filterResult.asNodeSet();
        if (nodeSet.isEmpty()) {
            return XPathNodeSet.EMPTY;
        }
        
        // Evaluate the path for each node in the filter result
        List<XPathNode> result = new ArrayList<>();
        
        for (XPathNode node : nodeSet) {
            XPathContext nodeContext = context.withContextNode(node);
            XPathValue pathResult = path.evaluate(nodeContext);
            
            if (pathResult.isNodeSet()) {
                for (XPathNode resultNode : pathResult.asNodeSet()) {
                    // Add if not already present (union semantics)
                    boolean found = false;
                    for (XPathNode existing : result) {
                        if (existing.isSameNode(resultNode)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        result.add(resultNode);
                    }
                }
            }
        }
        
        if (result.isEmpty()) {
            return XPathNodeSet.EMPTY;
        }
        return new XPathNodeSet(result);
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
