/*
 * NodeSetFunctions.java
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
import org.bluezoo.gonzalez.transform.xpath.type.*;

import java.util.List;

/**
 * XPath 1.0 node-set functions.
 *
 * <ul>
 *   <li>last() - returns the context size</li>
 *   <li>position() - returns the context position</li>
 *   <li>count(node-set) - returns the number of nodes</li>
 *   <li>id(object) - selects elements by ID</li>
 *   <li>local-name(node-set?) - returns the local name</li>
 *   <li>namespace-uri(node-set?) - returns the namespace URI</li>
 *   <li>name(node-set?) - returns the qualified name</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class NodeSetFunctions {

    private NodeSetFunctions() {}

    /** last() - returns context size */
    public static final Function LAST = new Function() {
        @Override public String getName() { return "last"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathNumber.of(context.getContextSize());
        }
    };

    /** position() - returns context position */
    public static final Function POSITION = new Function() {
        @Override public String getName() { return "position"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 0; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) {
            return XPathNumber.of(context.getContextPosition());
        }
    };

    /** count(node-set) - returns number of nodes */
    public static final Function COUNT = new Function() {
        @Override public String getName() { return "count"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (!arg.isNodeSet()) {
                throw new XPathException("count() requires a node-set argument");
            }
            return XPathNumber.of(arg.asNodeSet().size());
        }
    };

    /** id(object) - selects elements by ID */
    public static final Function ID = new Function() {
        @Override public String getName() { return "id"; }
        @Override public int getMinArgs() { return 1; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // ID function requires DTD information to know which attributes are IDs
            // For now, return empty node-set (full implementation requires document access)
            // TODO: Implement ID lookup using DTD/Schema information
            return XPathNodeSet.EMPTY;
        }
    };

    /** local-name(node-set?) - returns local name of first node */
    public static final Function LOCAL_NAME = new Function() {
        @Override public String getName() { return "local-name"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("local-name() requires a node-set argument");
                }
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.EMPTY;
                }
                node = nodeSet.first();
            }
            
            String localName = node.getLocalName();
            return XPathString.of(localName != null ? localName : "");
        }
    };

    /** namespace-uri(node-set?) - returns namespace URI of first node */
    public static final Function NAMESPACE_URI = new Function() {
        @Override public String getName() { return "namespace-uri"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("namespace-uri() requires a node-set argument");
                }
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.EMPTY;
                }
                node = nodeSet.first();
            }
            
            String nsUri = node.getNamespaceURI();
            return XPathString.of(nsUri != null ? nsUri : "");
        }
    };

    /** name(node-set?) - returns qualified name of first node */
    public static final Function NAME = new Function() {
        @Override public String getName() { return "name"; }
        @Override public int getMinArgs() { return 0; }
        @Override public int getMaxArgs() { return 1; }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("name() requires a node-set argument");
                }
                XPathNodeSet nodeSet = arg.asNodeSet();
                if (nodeSet.isEmpty()) {
                    return XPathString.EMPTY;
                }
                node = nodeSet.first();
            }
            
            // Build qualified name from prefix and local name
            String prefix = node.getPrefix();
            String localName = node.getLocalName();
            
            if (localName == null) {
                return XPathString.EMPTY;
            }
            
            if (prefix != null && !prefix.isEmpty()) {
                return XPathString.of(prefix + ":" + localName);
            }
            return XPathString.of(localName);
        }
    };

    /** Returns all node-set functions. */
    public static Function[] getAll() {
        return new Function[] { LAST, POSITION, COUNT, ID, LOCAL_NAME, NAMESPACE_URI, NAME };
    }

}
