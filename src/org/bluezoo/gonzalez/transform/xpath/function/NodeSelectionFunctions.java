/*
 * NodeSelectionFunctions.java
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath/XSLT node selection functions: accumulator-before, accumulator-after,
 * snapshot, outermost, innermost.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class NodeSelectionFunctions {

    private NodeSelectionFunctions() {
    }

    static Function accumulatorBefore() {
        return new AccumulatorBeforeFunction();
    }

    static Function accumulatorAfter() {
        return new AccumulatorAfterFunction();
    }

    static Function snapshot() {
        return new SnapshotFunction();
    }

    static Function outermost() {
        return new OutermostFunction();
    }

    static Function innermost() {
        return new InnermostFunction();
    }

    /**
     * XTTE3360: validates that the context item for accumulator-before/after
     * is a node that is not an attribute or namespace node.
     */
    private static void validateAccumulatorContext(XPathContext context) throws XPathException {
        XPathValue contextItem = context.getContextItem();
        if (contextItem != null && !(contextItem instanceof XPathNode)
                && !contextItem.isNodeSet()
                && !(contextItem instanceof XPathResultTreeFragment)) {
            throw new XPathException("XTTE3360: accumulator-before/after " +
                "requires context item to be a node, but got " +
                contextItem.getClass().getSimpleName());
        }
        XPathNode contextNode = context.getContextNode();
        if (contextNode == null) {
            throw new XPathException("XTTE3360: accumulator-before/after " +
                "requires a context node");
        }
        NodeType nodeType = contextNode.getNodeType();
        if (nodeType == NodeType.ATTRIBUTE || nodeType == NodeType.NAMESPACE) {
            throw new XPathException("XTTE3360: accumulator-before/after " +
                "cannot be called when the context item is an " +
                nodeType.name().toLowerCase() + " node");
        }
    }

    /**
     * accumulator-before(name) - Returns the accumulator value before processing
     * the current node.
     *
     * <p>This function returns the value of the named accumulator before any
     * accumulator rules have fired for the current node. It is typically used
     * in pre-descent rules to access the value from the parent context.
     */
    private static class AccumulatorBeforeFunction implements Function {
        @Override
        public String getName() {
            return "accumulator-before";
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

            validateAccumulatorContext(context);

            // Get accumulator value from context
            XPathValue value = context.getAccumulatorBefore(name);
            if (value != null) {
                return value;
            }

            // Accumulator not found
            throw new XPathException("Unknown accumulator: " + name);
        }
    }

    /**
     * accumulator-after(name) - Returns the accumulator value after processing
     * the current node.
     *
     * <p>This function returns the value of the named accumulator after all
     * accumulator rules have fired for the current node and its descendants.
     * It is typically used to access the final accumulated value at the end
     * of processing.
     */
    private static class AccumulatorAfterFunction implements Function {
        @Override
        public String getName() {
            return "accumulator-after";
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

            validateAccumulatorContext(context);

            // Get accumulator value from context
            XPathValue value = context.getAccumulatorAfter(name);
            if (value != null) {
                return value;
            }

            // Accumulator not found
            throw new XPathException("Unknown accumulator: " + name);
        }
    }

    /**
     * snapshot($node) - Returns a deep copy of a node with ancestor information preserved.
     */
    private static class SnapshotFunction implements Function {
        @Override
        public String getName() {
            return "snapshot";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            if (args.isEmpty()) {
                XPathNode node = context.getContextNode();
                if (node == null) {
                    return XPathSequence.EMPTY;
                }
                return snapshotNode(node);
            }

            XPathValue arg = args.get(0);
            if (arg instanceof XPathNode) {
                return snapshotNode((XPathNode) arg);
            }
            if (arg instanceof XPathNodeSet) {
                List<XPathNode> results = new ArrayList<XPathNode>();
                Iterator<XPathNode> iter = ((XPathNodeSet) arg).iterator();
                while (iter.hasNext()) {
                    XPathNode n = iter.next();
                    XPathValue snap = snapshotNode(n);
                    if (snap instanceof XPathNode) {
                        results.add((XPathNode) snap);
                    } else if (snap instanceof XPathNodeSet) {
                        Iterator<XPathNode> si = ((XPathNodeSet) snap).iterator();
                        while (si.hasNext()) {
                            results.add(si.next());
                        }
                    }
                }
                if (results.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return new XPathNodeSet(results);
            }
            if (arg instanceof XPathSequence) {
                XPathSequence seq = (XPathSequence) arg;
                List<XPathValue> results = new ArrayList<XPathValue>();
                Iterator<XPathValue> iter = seq.iterator();
                while (iter.hasNext()) {
                    XPathValue item = iter.next();
                    if (item instanceof XPathNode) {
                        results.add(snapshotNode((XPathNode) item));
                    } else {
                        results.add(item);
                    }
                }
                if (results.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return new XPathSequence(results);
            }
            // Non-node items: return as-is (snapshot of atomic is identity)
            return arg;
        }

        private XPathValue snapshotNode(XPathNode node) {
            // Deep copy the target node and eagerly initialize all descendants
            // so that document order IDs are assigned in tree traversal order
            SequenceFunctions.CopiedNode targetCopy = new SequenceFunctions.CopiedNode(node);
            targetCopy.deepInitialize();

            // Collect ancestors from node's parent up to root
            List<XPathNode> ancestors = new ArrayList<XPathNode>();
            XPathNode ancestor = node.getParent();
            while (ancestor != null) {
                ancestors.add(ancestor);
                ancestor = ancestor.getParent();
            }

            if (ancestors.isEmpty()) {
                return XPathNodeSet.of(targetCopy);
            }

            // Build snapshot tree from target up to root.
            // Each ancestor is a shallow copy with only one child.
            XPathNode currentChild = targetCopy;
            for (int i = 0; i < ancestors.size(); i++) {
                SnapshotAncestorNode shallowCopy = new SnapshotAncestorNode(
                    ancestors.get(i), currentChild);
                if (currentChild instanceof SequenceFunctions.CopiedNode) {
                    ((SequenceFunctions.CopiedNode) currentChild).setParent(shallowCopy);
                } else if (currentChild instanceof SnapshotAncestorNode) {
                    ((SnapshotAncestorNode) currentChild).setParent(shallowCopy);
                }
                currentChild = shallowCopy;
            }

            return XPathNodeSet.of(targetCopy);
        }
    }

    /**
     * Shallow copy of an ancestor node for snapshot trees.
     * Contains the original's attributes and namespaces but only one child
     * (the path leading to the snapshot target).
     */
    static class SnapshotAncestorNode implements XPathNode {
        private final XPathNode original;
        private final XPathNode onlyChild;
        private XPathNode parent;
        private static long snapshotId = Long.MAX_VALUE / 4;
        private final long uniqueId;

        SnapshotAncestorNode(XPathNode original, XPathNode onlyChild) {
            this.original = original;
            this.onlyChild = onlyChild;
            this.uniqueId = snapshotId++;
        }

        public XPathNode getOriginal() {
            return original;
        }

        void setParent(XPathNode parent) {
            this.parent = parent;
        }

        @Override
        public NodeType getNodeType() {
            return original.getNodeType();
        }

        @Override
        public String getNamespaceURI() {
            return original.getNamespaceURI();
        }

        @Override
        public String getLocalName() {
            return original.getLocalName();
        }

        @Override
        public String getPrefix() {
            return original.getPrefix();
        }

        @Override
        public String getStringValue() {
            return onlyChild.getStringValue();
        }

        @Override
        public XPathNode getParent() {
            return parent;
        }

        @Override
        public XPathNode getFollowingSibling() {
            return null;
        }

        @Override
        public XPathNode getPrecedingSibling() {
            return null;
        }

        @Override
        public Iterator<XPathNode> getChildren() {
            return Collections.<XPathNode>singletonList(onlyChild).iterator();
        }

        @Override
        public Iterator<XPathNode> getAttributes() {
            return original.getAttributes();
        }

        @Override
        public Iterator<XPathNode> getNamespaces() {
            return original.getNamespaces();
        }

        @Override
        public long getDocumentOrder() {
            return uniqueId;
        }

        @Override
        public boolean isSameNode(XPathNode other) {
            return this == other;
        }

        @Override
        public XPathNode getRoot() {
            XPathNode node = this;
            while (node.getParent() != null) {
                node = node.getParent();
            }
            return node;
        }

        @Override
        public boolean isFullyNavigable() {
            return true;
        }
    }

    /**
     * outermost($nodes) - Returns nodes that have no ancestor in the input set.
     * Nodes are returned in document order.
     */
    private static class OutermostFunction implements Function {
        @Override
        public String getName() {
            return "outermost";
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
            if (arg == null || (arg.isNodeSet() && arg.asNodeSet().isEmpty())) {
                return XPathNodeSet.empty();
            }
            XPathNodeSet ns;
            if (arg.isNodeSet()) {
                ns = arg.asNodeSet();
            } else if (arg instanceof XPathSequence) {
                ns = ((XPathSequence) arg).asNodeSet();
            } else {
                throw new XPathException("outermost() argument must be a node-set");
            }
            if (ns == null || ns.isEmpty()) {
                return XPathNodeSet.empty();
            }
            List<XPathNode> nodes = ns.getNodes();
            Set<XPathNode> nodeSet = new HashSet<XPathNode>(nodes);
            List<XPathNode> result = new ArrayList<XPathNode>();
            for (XPathNode node : nodes) {
                if (!hasAncestorInSet(node, nodeSet)) {
                    result.add(node);
                }
            }
            return new XPathNodeSet(result);
        }

        private boolean hasAncestorInSet(XPathNode node, Set<XPathNode> nodeSet) {
            XPathNode parent = node.getParent();
            while (parent != null) {
                if (nodeSet.contains(parent)) {
                    return true;
                }
                parent = parent.getParent();
            }
            return false;
        }
    }

    /**
     * innermost($nodes) - Returns nodes that have no descendant in the input set.
     * Nodes are returned in document order.
     */
    private static class InnermostFunction implements Function {
        @Override
        public String getName() {
            return "innermost";
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
            if (arg == null || (arg.isNodeSet() && arg.asNodeSet().isEmpty())) {
                return XPathNodeSet.empty();
            }
            XPathNodeSet ns;
            if (arg.isNodeSet()) {
                ns = arg.asNodeSet();
            } else if (arg instanceof XPathSequence) {
                ns = ((XPathSequence) arg).asNodeSet();
            } else {
                throw new XPathException("innermost() argument must be a node-set");
            }
            if (ns == null || ns.isEmpty()) {
                return XPathNodeSet.empty();
            }
            List<XPathNode> nodes = ns.getNodes();
            Set<XPathNode> nodeSet = new HashSet<XPathNode>(nodes);
            List<XPathNode> result = new ArrayList<XPathNode>();
            for (XPathNode node : nodes) {
                if (!hasDescendantInSet(node, nodeSet)) {
                    result.add(node);
                }
            }
            return new XPathNodeSet(result);
        }

        private boolean hasDescendantInSet(XPathNode node, Set<XPathNode> nodeSet) {
            for (XPathNode other : nodeSet) {
                if (other == node) {
                    continue;
                }
                XPathNode ancestor = other.getParent();
                while (ancestor != null) {
                    if (ancestor == node) {
                        return true;
                    }
                    ancestor = ancestor.getParent();
                }
            }
            return false;
        }
    }
}
