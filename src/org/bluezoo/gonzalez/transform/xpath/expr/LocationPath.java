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

import org.bluezoo.gonzalez.schema.xsd.XSDAttribute;
import org.bluezoo.gonzalez.schema.xsd.XSDElement;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

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
        // XPath 2.0+: Check for atomic context item with single self-step (".")
        // When the context is an atomic value, "." should return that value directly
        if (!absolute && steps.size() == 1) {
            Step step = steps.get(0);
            if (step.getAxis() == Step.Axis.SELF && 
                step.getNodeTestType() == Step.NodeTestType.NODE &&
                !step.hasPredicates()) {
                // This is a bare "." - check for atomic context item
                if (context instanceof org.bluezoo.gonzalez.transform.runtime.BasicTransformContext) {
                    org.bluezoo.gonzalez.transform.runtime.BasicTransformContext btc = 
                        (org.bluezoo.gonzalez.transform.runtime.BasicTransformContext) context;
                    XPathValue contextItem = btc.getContextItem();
                    if (contextItem != null && !(contextItem instanceof XPathNode)) {
                        // Context is an atomic value, return it directly
                        return contextItem;
                    }
                }
            }
        }
        
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

        // Reusable list for atomic results from EXPR steps
        List<XPathValue> atomicResults = new ArrayList<>();
        
        // Apply each step
        for (Step step : steps) {
            atomicResults.clear();
            currentNodes = evaluateStep(step, currentNodes, context, atomicResults);
            
            // Check if the step produced atomic (non-node) results
            if (!atomicResults.isEmpty()) {
                // Return the atomic results as a sequence
                if (atomicResults.size() == 1) {
                    return atomicResults.get(0);
                }
                return new XPathSequence(atomicResults);
            }
            
            if (currentNodes.isEmpty()) {
                return XPathNodeSet.EMPTY;
            }
        }

        return new XPathNodeSet(currentNodes);
    }

    /**
     * Evaluates a step against a set of nodes.
     * 
     * @param step the step to evaluate
     * @param inputNodes the input node set
     * @param context the XPath context
     * @param atomicResultsOut output parameter - if the step produces atomic results
     *                         (e.g., EXPR steps like star-slash-local-name()), this list will be
     *                         cleared and populated with the atomic results. The method
     *                         returns empty list in this case.
     * @return the resulting nodes, or empty list if atomic results were produced
     * @throws XPathException if an error occurs during evaluation
     */
    private List<XPathNode> evaluateStep(Step step, List<XPathNode> inputNodes, 
                                          XPathContext context,
                                          List<XPathValue> atomicResultsOut) throws XPathException {
        List<XPathNode> result = new ArrayList<>();

        // XPath 2.0/3.0 EXPR step (simple mapping operator)
        // When a function call appears as a path step (e.g., */local-name()),
        // it is evaluated for each input node and results are collected.
        // This can return atomic values, not just nodes.
        if (step.getNodeTestType() == Step.NodeTestType.EXPR) {
            Expr stepExpr = step.getStepExpr();
            List<XPathValue> atomicResults = new ArrayList<>();
            boolean hasAtomicResults = false;
            
            for (XPathNode node : inputNodes) {
                XPathContext nodeContext = context.withContextNode(node);
                XPathValue value = stepExpr.evaluate(nodeContext);
                
                // Collect results - can be nodes or atomic values
                if (value.isNodeSet()) {
                    for (XPathNode resultNode : value.asNodeSet()) {
                        result.add(resultNode);
                    }
                } else if (value.isSequence()) {
                    // Sequence - iterate and collect
                    for (XPathValue item : (XPathSequence) value) {
                        if (item instanceof XPathNode) {
                            result.add((XPathNode) item);
                        } else {
                            atomicResults.add(item);
                            hasAtomicResults = true;
                        }
                    }
                } else if (value instanceof XPathNode) {
                    result.add((XPathNode) value);
                } else {
                    // Atomic value (string, number, etc.)
                    atomicResults.add(value);
                    hasAtomicResults = true;
                }
            }
            
            // If we have atomic results, populate output parameter
            if (hasAtomicResults) {
                // Populate the output parameter with all results
                // Convert nodes to node-sets and add atomic values
                atomicResultsOut.clear();
                for (XPathNode node : result) {
                    atomicResultsOut.add(new XPathNodeSet(Collections.singletonList(node)));
                }
                atomicResultsOut.addAll(atomicResults);
                // Return empty nodes to signal that atomic results are available
                return Collections.emptyList();
            }
            
            // Apply predicates if any
            if (!step.getPredicates().isEmpty()) {
                result = applyPredicates(step.getPredicates(), result, context);
            }
            
            result = removeDuplicates(result);
            return result;
        }

        for (XPathNode node : inputNodes) {
            // Select nodes along the axis
            Iterator<XPathNode> axisNodes = selectAxis(step.getAxis(), node);
            
            // Apply node test - collect candidates for THIS input node
            List<XPathNode> stepResult = new ArrayList<>();
            while (axisNodes.hasNext()) {
                XPathNode candidate = axisNodes.next();
                if (matchesNodeTest(step, candidate, context)) {
                    stepResult.add(candidate);
                }
            }
            
            // Apply predicates to THIS input node's results (position is per-parent)
            // This is the key XPath semantic: z[2] means "the 2nd z child of each node"
            // not "the 2nd z among all z children of all nodes"
            if (!step.getPredicates().isEmpty()) {
                stepResult = applyPredicates(step.getPredicates(), stepResult, context);
            }
            
            result.addAll(stepResult);
        }

        // Remove duplicates from the combined result (nodes can appear multiple times
        // if the input nodes had overlapping axes)
        result = removeDuplicates(result);

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
                
            case ANY_NAMESPACE:
                // *:localname - matches any node with the given local name regardless of namespace
                if (!isPrincipalNodeType(step.getAxis(), node)) {
                    return false;
                }
                return step.getLocalName().equals(node.getLocalName());
                
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
                if (stepNsUri == null) {
                    stepNsUri = "";
                }
                if (nodeNsUri2 == null) {
                    nodeNsUri2 = "";
                }
                return stepNsUri.equals(nodeNsUri2) && 
                       step.getLocalName().equals(node.getLocalName());
            
            // XPath 2.0 kind tests
            case ELEMENT:
                if (!node.isElement()) {
                    return false;
                }
                // Check optional name parameter: element(name) or element(name, type)
                return matchesKindTestName(step, node);
                
            case ATTRIBUTE:
                if (!node.isAttribute()) {
                    return false;
                }
                // Check optional name parameter: attribute(name) or attribute(name, type)
                return matchesKindTestName(step, node);
                
            case DOCUMENT_NODE:
                return node.getNodeType() == NodeType.ROOT;
            
            // XPath 2.0 schema-aware kind tests
            case SCHEMA_ELEMENT:
                if (!node.isElement()) {
                    return false;
                }
                return matchesSchemaElement(step, node, context);
                
            case SCHEMA_ATTRIBUTE:
                if (!node.isAttribute()) {
                    return false;
                }
                return matchesSchemaAttribute(step, node, context);
                
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
    
    /**
     * Checks if a node matches the optional name in a kind test.
     * For element(name) or attribute(name), checks if node name matches.
     * For element(*) or attribute(*) or element() (no args), matches any node.
     * For element(name, type) or attribute(*, type), also checks type annotation.
     */
    private boolean matchesKindTestName(Step step, XPathNode node) {
        String elementName = step.getLocalName();
        
        // Check name match first (unless wildcard or absent)
        if (elementName != null && !"*".equals(elementName)) {
            String localPart = elementName;
            int colonIdx = elementName.indexOf(':');
            if (colonIdx > 0) {
                localPart = elementName.substring(colonIdx + 1);
            }
            if (!localPart.equals(node.getLocalName())) {
                return false;  // Name doesn't match
            }
        }
        
        // Check type annotation if specified
        if (step.hasTypeConstraint()) {
            String typeNs = step.getTypeNamespaceURI();
            String typeLocal = step.getTypeLocalName();
            
            // Node must have a type annotation
            if (!node.hasTypeAnnotation()) {
                return false;
            }
            
            // Get the node's type annotation
            String nodeTypeNs = node.getTypeNamespaceURI();
            String nodeTypeLocal = node.getTypeLocalName();
            
            // Look up the node's type to check derivation
            org.bluezoo.gonzalez.schema.xsd.XSDSimpleType nodeType = 
                org.bluezoo.gonzalez.schema.xsd.XSDSimpleType.getBuiltInType(nodeTypeLocal);
            
            if (nodeType != null) {
                // Check if node's type is same as or derived from the target type
                if (!nodeType.isDerivedFrom(typeNs, typeLocal)) {
                    return false;
                }
            } else {
                // Non-built-in type: check for exact match
                // (or would need schema context for user-defined type derivation)
                if (!typeLocal.equals(nodeTypeLocal)) {
                    return false;
                }
                if (typeNs != null && !typeNs.equals(nodeTypeNs)) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Checks if a node matches a schema-element() kind test.
     *
     * <p>schema-element(name) matches elements that:
     * <ul>
     *   <li>Have a name that matches a global element declaration, OR</li>
     *   <li>Are members of the substitution group headed by that element, OR</li>
     *   <li>Have a type that is derived from the declared element's type</li>
     * </ul>
     *
     * @param step the step containing the schema element name
     * @param node the element node to test
     * @param context the evaluation context
     * @return true if the node matches the schema-element test
     */
    private boolean matchesSchemaElement(Step step, XPathNode node, XPathContext context) {
        String schemaElemName = step.getLocalName();
        if (schemaElemName == null) {
            return false;  // schema-element() requires a name
        }
        
        // Extract local part if prefixed
        String localPart = schemaElemName;
        String prefix = null;
        int colonIdx = schemaElemName.indexOf(':');
        if (colonIdx > 0) {
            prefix = schemaElemName.substring(0, colonIdx);
            localPart = schemaElemName.substring(colonIdx + 1);
        }
        
        // Get the compiled stylesheet to access imported schemas
        if (!(context instanceof TransformContext)) {
            // Not in XSLT context - can't access schemas
            // Fall back to simple name matching
            return localPart.equals(node.getLocalName());
        }
        
        TransformContext transformContext = (TransformContext) context;
        CompiledStylesheet stylesheet = transformContext.getStylesheet();
        if (stylesheet == null) {
            return localPart.equals(node.getLocalName());
        }
        
        // Resolve prefix to namespace URI if present
        String schemaNamespace = step.getNamespaceURI();  // May be stored here from parsing
        
        // Look up the schema element declaration
        // Try all imported schemas if no specific namespace
        XSDElement schemaElement = null;
        if (schemaNamespace != null && !schemaNamespace.isEmpty()) {
            XSDSchema schema = stylesheet.getImportedSchema(schemaNamespace);
            if (schema != null) {
                schemaElement = schema.getElement(localPart);
            }
        } else {
            // Check all imported schemas
            for (XSDSchema schema : stylesheet.getImportedSchemas().values()) {
                schemaElement = schema.getElement(localPart);
                if (schemaElement != null) {
                    schemaNamespace = schema.getTargetNamespace();
                    break;
                }
            }
        }
        
        if (schemaElement == null) {
            // No matching schema element declaration found
            // Fall back to name matching only
            return localPart.equals(node.getLocalName());
        }
        
        // Check if node matches the schema element declaration
        // Primary check: name and namespace match
        String nodeNs = node.getNamespaceURI();
        if (nodeNs == null) nodeNs = "";
        String schemaElemNs = schemaElement.getNamespaceURI();
        if (schemaElemNs == null) schemaElemNs = "";
        
        if (schemaElement.getName().equals(node.getLocalName()) && schemaElemNs.equals(nodeNs)) {
            return true;
        }
        
        // TODO: Check substitution group membership
        // This requires tracking substitution groups in XSDSchema/XSDElement
        
        // TODO: Check if node's type derives from schema element's type
        // This requires type annotations on the node (PSVI) and type hierarchy checking
        
        return false;
    }
    
    /**
     * Checks if a node matches a schema-attribute() kind test.
     *
     * <p>schema-attribute(name) matches attributes that:
     * <ul>
     *   <li>Have a name that matches a global attribute declaration</li>
     * </ul>
     *
     * @param step the step containing the schema attribute name
     * @param node the attribute node to test
     * @param context the evaluation context
     * @return true if the node matches the schema-attribute test
     */
    private boolean matchesSchemaAttribute(Step step, XPathNode node, XPathContext context) {
        String schemaAttrName = step.getLocalName();
        if (schemaAttrName == null) {
            return false;  // schema-attribute() requires a name
        }
        
        // Extract local part if prefixed
        String localPart = schemaAttrName;
        int colonIdx = schemaAttrName.indexOf(':');
        if (colonIdx > 0) {
            localPart = schemaAttrName.substring(colonIdx + 1);
        }
        
        // Get the compiled stylesheet to access imported schemas
        if (!(context instanceof TransformContext)) {
            return localPart.equals(node.getLocalName());
        }
        
        TransformContext transformContext = (TransformContext) context;
        CompiledStylesheet stylesheet = transformContext.getStylesheet();
        if (stylesheet == null) {
            return localPart.equals(node.getLocalName());
        }
        
        // Look up the schema attribute declaration
        String schemaNamespace = step.getNamespaceURI();
        XSDAttribute schemaAttribute = null;
        
        if (schemaNamespace != null && !schemaNamespace.isEmpty()) {
            XSDSchema schema = stylesheet.getImportedSchema(schemaNamespace);
            if (schema != null) {
                schemaAttribute = schema.getAttribute(localPart);
            }
        } else {
            // Check all imported schemas
            for (XSDSchema schema : stylesheet.getImportedSchemas().values()) {
                schemaAttribute = schema.getAttribute(localPart);
                if (schemaAttribute != null) {
                    schemaNamespace = schema.getTargetNamespace();
                    break;
                }
            }
        }
        
        if (schemaAttribute == null) {
            // No matching schema attribute declaration found
            return localPart.equals(node.getLocalName());
        }
        
        // Check if node matches the schema attribute declaration
        String nodeNs = node.getNamespaceURI();
        if (nodeNs == null) nodeNs = "";
        String schemaAttrNs = schemaAttribute.getNamespaceURI();
        if (schemaAttrNs == null) schemaAttrNs = "";
        
        return schemaAttribute.getName().equals(node.getLocalName()) && schemaAttrNs.equals(nodeNs);
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

    /**
     * Iterator for the following axis.
     * Returns nodes in document order.
     */
    private static class FollowingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> nodes = new ArrayList<>();
        private int index;

        FollowingIterator(XPathNode contextNode) {
            // Following axis: all nodes after this node in document order,
            // excluding descendants
            
            // Special handling for attribute and namespace nodes:
            // For these, "following" includes children of the parent element
            // (they come after the attribute in document order)
            if (contextNode.isAttribute() || 
                contextNode.getNodeType() == org.bluezoo.gonzalez.transform.xpath.type.NodeType.NAMESPACE) {
                XPathNode parent = contextNode.getParent();
                if (parent != null) {
                    // First add all children of parent element (they follow attributes)
                    Iterator<XPathNode> children = parent.getChildren();
                    while (children.hasNext()) {
                        collectSubtree(children.next());
                    }
                    // Then add following siblings of parent and ancestors
                    collectFollowing(parent);
                }
            } else {
                collectFollowing(contextNode);
            }
            index = 0;
        }

        private void collectFollowing(XPathNode node) {
            // First collect all following siblings (and their subtrees) at this level
            XPathNode sibling = node.getFollowingSibling();
            while (sibling != null) {
                collectSubtree(sibling);
                sibling = sibling.getFollowingSibling();
            }
            
            // Then recurse to parent to get following siblings at higher levels
            XPathNode parent = node.getParent();
            if (parent != null) {
                collectFollowing(parent);
            }
        }

        private void collectSubtree(XPathNode node) {
            // Add this node first (document order)
            nodes.add(node);
            // Then add all descendants in document order
            Iterator<XPathNode> children = node.getChildren();
            while (children.hasNext()) {
                collectSubtree(children.next());
            }
        }

        @Override
        public boolean hasNext() {
            return index < nodes.size();
        }

        @Override
        public XPathNode next() {
            return nodes.get(index++);
        }
    }

    /**
     * Iterator for the preceding axis.
     * Returns nodes in reverse document order (closest to context node first).
     */
    private static class PrecedingIterator implements Iterator<XPathNode> {
        private final List<XPathNode> nodes = new ArrayList<>();
        private int index;

        PrecedingIterator(XPathNode node) {
            // Collect all preceding nodes in reverse document order
            collectPreceding(node);
            // Nodes are collected in reverse document order, iterate from start
            index = 0;
        }

        private void collectPreceding(XPathNode node) {
            // Preceding axis: all nodes before this node in document order,
            // excluding ancestors. We want reverse document order (closest first).
            XPathNode parent = node.getParent();
            if (parent != null) {
                // Process preceding siblings from closest to furthest
                XPathNode sibling = node.getPrecedingSibling();
                while (sibling != null) {
                    // For each sibling, add subtree in reverse document order
                    // (deepest descendants first, then the node itself)
                    collectSubtreeReverse(sibling);
                    sibling = sibling.getPrecedingSibling();
                }
                // Then recurse to parent's preceding (further away in document)
                collectPreceding(parent);
            }
        }

        private void collectSubtreeReverse(XPathNode node) {
            // For reverse document order within subtree:
            // Process children in reverse order (last child is closest to context)
            Iterator<XPathNode> children = node.getChildren();
            List<XPathNode> childList = new ArrayList<>();
            while (children.hasNext()) {
                childList.add(children.next());
            }
            // Process children in reverse (last child first)
            for (int i = childList.size() - 1; i >= 0; i--) {
                collectSubtreeReverse(childList.get(i));
            }
            // Then add this node (after its descendants in reverse order)
            nodes.add(node);
        }

        @Override
        public boolean hasNext() {
            return index < nodes.size();
        }

        @Override
        public XPathNode next() {
            return nodes.get(index++);
        }
    }

}
