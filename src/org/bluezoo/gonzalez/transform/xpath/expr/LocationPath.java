/*
 * LocationPath.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A location path expression.
 *
 * <p>A location path is a sequence of steps that selects a set of nodes
 * relative to the context node. It can be:
 * <ul>
 *   <li>Relative: starts from the context node (e.g., {@code child::para})</li>
 *   <li>Absolute: starts from the root (e.g., {@code /doc/chapter})</li>
 * </ul>
 *
 * <p>The abbreviated syntax uses shortcuts:
 * <ul>
 *   <li>{@code foo} = {@code child::foo}</li>
 *   <li>{@code @attr} = {@code attribute::attr}</li>
 *   <li>{@code .} = {@code self::node()}</li>
 *   <li>{@code ..} = {@code parent::node()}</li>
 *   <li>{@code //} = {@code /descendant-or-self::node()/}</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class LocationPath implements Expr {

    private final boolean absolute;
    private final List<Step> steps;

    /**
     * Creates a location path.
     *
     * @param absolute true if this is an absolute path (starts with /)
     * @param steps the steps in the path
     */
    public LocationPath(boolean absolute, List<Step> steps) {
        this.absolute = absolute;
        this.steps = steps != null ? 
            Collections.unmodifiableList(new ArrayList<>(steps)) : 
            Collections.emptyList();
    }

    /**
     * Creates a relative location path.
     *
     * @param steps the steps
     * @return the location path
     */
    public static LocationPath relative(List<Step> steps) {
        return new LocationPath(false, steps);
    }

    /**
     * Creates a relative location path with a single step.
     *
     * @param step the step
     * @return the location path
     */
    public static LocationPath relative(Step step) {
        return new LocationPath(false, Collections.singletonList(step));
    }

    /**
     * Creates an absolute location path.
     *
     * @param steps the steps (may be empty for just "/")
     * @return the location path
     */
    public static LocationPath absolute(List<Step> steps) {
        return new LocationPath(true, steps);
    }

    @Override
    public XPathValue evaluate(XPathContext context) throws XPathException {
        // Start with the initial node set
        List<XPathNode> currentNodes = new ArrayList<>();
        
        if (absolute) {
            // Absolute path: start from root
            XPathNode root = context.getContextNode().getRoot();
            currentNodes.add(root);
        } else {
            // Relative path: start from context node
            currentNodes.add(context.getContextNode());
        }

        // Apply each step
        for (Step step : steps) {
            currentNodes = evaluateStep(step, currentNodes, context);
            if (currentNodes.isEmpty()) {
                return XPathNodeSet.EMPTY;
            }
        }

        return new XPathNodeSet(currentNodes);
    }

    private List<XPathNode> evaluateStep(Step step, List<XPathNode> inputNodes, 
                                          XPathContext context) throws XPathException {
        List<XPathNode> result = new ArrayList<>();

        for (XPathNode node : inputNodes) {
            // Select nodes along the axis
            Iterator<XPathNode> axisNodes = selectAxis(step.getAxis(), node);
            
            // Apply node test
            while (axisNodes.hasNext()) {
                XPathNode candidate = axisNodes.next();
                if (matchesNodeTest(step, candidate, context)) {
                    result.add(candidate);
                }
            }
        }

        // Remove duplicates and apply predicates
        result = removeDuplicates(result);
        
        if (!step.getPredicates().isEmpty()) {
            result = applyPredicates(step.getPredicates(), result, context);
        }

        return result;
    }

    private Iterator<XPathNode> selectAxis(Step.Axis axis, XPathNode node) throws XPathException {
        // This is a simplified implementation. The full implementation would
        // use the axis classes from the axis package.
        switch (axis) {
            case CHILD:
                return node.getChildren();
            case ATTRIBUTE:
                return node.getAttributes();
            case NAMESPACE:
                return node.getNamespaces();
            case SELF:
                return Collections.singleton(node).iterator();
            case PARENT:
                XPathNode parent = node.getParent();
                return parent != null ? 
                    Collections.singleton(parent).iterator() : 
                    Collections.emptyIterator();
            case DESCENDANT:
                return new DescendantIterator(node, false);
            case DESCENDANT_OR_SELF:
                return new DescendantIterator(node, true);
            case ANCESTOR:
                return new AncestorIterator(node, false);
            case ANCESTOR_OR_SELF:
                return new AncestorIterator(node, true);
            case FOLLOWING_SIBLING:
                return new FollowingSiblingIterator(node);
            case PRECEDING_SIBLING:
                return new PrecedingSiblingIterator(node);
            case FOLLOWING:
                return new FollowingIterator(node);
            case PRECEDING:
                return new PrecedingIterator(node);
            default:
                throw new XPathException("Unsupported axis: " + axis);
        }
    }

    private boolean matchesNodeTest(Step step, XPathNode node, XPathContext context) {
        switch (step.getNodeTestType()) {
            case NODE:
                return true;
                
            case TEXT:
                return node.isText();
                
            case COMMENT:
                return node.getNodeType() == NodeType.COMMENT;
                
            case PROCESSING_INSTRUCTION:
                if (node.getNodeType() != NodeType.PROCESSING_INSTRUCTION) {
                    return false;
                }
                String target = step.getPITarget();
                return target == null || target.equals(node.getLocalName());
                
            case WILDCARD:
                // Wildcard matches any node of the principal node type for the axis
                return isPrincipalNodeType(step.getAxis(), node);
                
            case NAMESPACE_WILDCARD:
                if (!isPrincipalNodeType(step.getAxis(), node)) {
                    return false;
                }
                String nodeNs = node.getNamespaceURI();
                String stepNs = step.getNamespaceURI();
                return (nodeNs == null ? "" : nodeNs).equals(stepNs == null ? "" : stepNs);
                
            case NAME:
                if (!isPrincipalNodeType(step.getAxis(), node)) {
                    return false;
                }
                // No namespace - match local name only when node has no namespace
                String nodeNsUri = node.getNamespaceURI();
                if (nodeNsUri != null && !nodeNsUri.isEmpty()) {
                    return false;
                }
                return step.getLocalName().equals(node.getLocalName());
                
            case QNAME:
                if (!isPrincipalNodeType(step.getAxis(), node)) {
                    return false;
                }
                String stepNsUri = step.getNamespaceURI();
                String nodeNsUri2 = node.getNamespaceURI();
                if (stepNsUri == null) stepNsUri = "";
                if (nodeNsUri2 == null) nodeNsUri2 = "";
                return stepNsUri.equals(nodeNsUri2) && 
                       step.getLocalName().equals(node.getLocalName());
                
            default:
                return false;
        }
    }

    private boolean isPrincipalNodeType(Step.Axis axis, XPathNode node) {
        switch (axis) {
            case ATTRIBUTE:
                return node.isAttribute();
            case NAMESPACE:
                return node.getNodeType() == NodeType.NAMESPACE;
            default:
                return node.isElement();
        }
    }

    private List<XPathNode> removeDuplicates(List<XPathNode> nodes) {
        if (nodes.size() <= 1) {
            return nodes;
        }
        List<XPathNode> result = new ArrayList<>(nodes.size());
        for (XPathNode node : nodes) {
            boolean found = false;
            for (XPathNode existing : result) {
                if (existing.isSameNode(node)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.add(node);
            }
        }
        return result;
    }

    private List<XPathNode> applyPredicates(List<Expr> predicates, List<XPathNode> nodes, 
                                            XPathContext context) throws XPathException {
        List<XPathNode> current = nodes;
        
        for (Expr predicate : predicates) {
            List<XPathNode> filtered = new ArrayList<>();
            int size = current.size();
            int position = 1;
            
            for (XPathNode node : current) {
                // Create context for predicate evaluation
                XPathContext predContext = context
                    .withContextNode(node)
                    .withPositionAndSize(position, size);
                
                XPathValue result = predicate.evaluate(predContext);
                
                // XPath 1.0: if result is number, compare with position
                // Otherwise, convert to boolean
                boolean include;
                if (result.getType() == XPathValue.Type.NUMBER) {
                    include = (result.asNumber() == position);
                } else {
                    include = result.asBoolean();
                }
                
                if (include) {
                    filtered.add(node);
                }
                position++;
            }
            
            current = filtered;
        }
        
        return current;
    }

    /**
     * Returns true if this is an absolute path.
     *
     * @return true if absolute
     */
    public boolean isAbsolute() {
        return absolute;
    }

    /**
     * Returns the steps in this path.
     *
     * @return the steps (immutable)
     */
    public List<Step> getSteps() {
        return steps;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append('/');
        }
        boolean first = true;
        for (Step step : steps) {
            if (!first) {
                sb.append('/');
            }
            sb.append(step);
            first = false;
        }
        return sb.toString();
    }

    // ========================================================================
    // Iterator implementations for axes
    // ========================================================================

    private static class DescendantIterator implements Iterator<XPathNode> {
        private final List<XPathNode> stack = new ArrayList<>();
        private XPathNode next;

        DescendantIterator(XPathNode node, boolean includeSelf) {
            if (includeSelf) {
                next = node;
            }
            // Add children in reverse order so we process them in document order
            Iterator<XPathNode> children = node.getChildren();
            List<XPathNode> childList = new ArrayList<>();
            while (children.hasNext()) {
                childList.add(children.next());
            }
            for (int i = childList.size() - 1; i >= 0; i--) {
                stack.add(childList.get(i));
            }
            if (!includeSelf) {
                advance();
            }
        }

        private void advance() {
            if (stack.isEmpty()) {
                next = null;
            } else {
                next = stack.remove(stack.size() - 1);
                // Add children
                Iterator<XPathNode> children = next.getChildren();
                List<XPathNode> childList = new ArrayList<>();
                while (children.hasNext()) {
                    childList.add(children.next());
                }
                for (int i = childList.size() - 1; i >= 0; i--) {
                    stack.add(childList.get(i));
                }
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = next;
            advance();
            return result;
        }
    }

    private static class AncestorIterator implements Iterator<XPathNode> {
        private XPathNode current;

        AncestorIterator(XPathNode node, boolean includeSelf) {
            current = includeSelf ? node : node.getParent();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getParent();
            return result;
        }
    }

    private static class FollowingSiblingIterator implements Iterator<XPathNode> {
        private XPathNode current;

        FollowingSiblingIterator(XPathNode node) {
            current = node.getFollowingSibling();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getFollowingSibling();
            return result;
        }
    }

    private static class PrecedingSiblingIterator implements Iterator<XPathNode> {
        private XPathNode current;

        PrecedingSiblingIterator(XPathNode node) {
            current = node.getPrecedingSibling();
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = current;
            current = current.getPrecedingSibling();
            return result;
        }
    }

    private static class FollowingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> pending = new ArrayList<>();
        private XPathNode next;

        FollowingIterator(XPathNode node) {
            // Following axis: all nodes after this node in document order,
            // excluding descendants
            XPathNode current = node;
            while (current != null) {
                XPathNode sibling = current.getFollowingSibling();
                if (sibling != null) {
                    pending.add(sibling);
                    break;
                }
                current = current.getParent();
            }
            advance();
        }

        private void advance() {
            if (pending.isEmpty()) {
                next = null;
            } else {
                next = pending.remove(0);
                // Add children to front (depth-first)
                Iterator<XPathNode> children = next.getChildren();
                int insertPos = 0;
                while (children.hasNext()) {
                    pending.add(insertPos++, children.next());
                }
                // Add following sibling
                XPathNode sibling = next.getFollowingSibling();
                if (sibling != null) {
                    pending.add(sibling);
                }
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public XPathNode next() {
            XPathNode result = next;
            advance();
            return result;
        }
    }

    private static class PrecedingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> nodes = new ArrayList<>();
        private int index;

        PrecedingIterator(XPathNode node) {
            // Collect all preceding nodes
            collectPreceding(node);
            index = nodes.size() - 1; // Start from end (reverse document order)
        }

        private void collectPreceding(XPathNode node) {
            // Preceding axis: all nodes before this node in document order,
            // excluding ancestors
            XPathNode parent = node.getParent();
            if (parent != null) {
                XPathNode sibling = node.getPrecedingSibling();
                while (sibling != null) {
                    collectSubtree(sibling);
                    sibling = sibling.getPrecedingSibling();
                }
                collectPreceding(parent);
            }
        }

        private void collectSubtree(XPathNode node) {
            nodes.add(node);
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                collectSubtree(children.next());
            }
        }

        @Override
        public boolean hasNext() {
            return index >= 0;
        }

        @Override
        public XPathNode next() {
            return nodes.get(index--);
        }
    }

}
