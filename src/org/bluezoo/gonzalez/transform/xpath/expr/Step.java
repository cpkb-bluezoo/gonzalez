/*
 * Step.java
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
import java.util.List;

/**
 * A single step in a location path.
 *
 * <p>A step consists of:
 * <ul>
 *   <li>An axis specifier (child, parent, attribute, etc.)</li>
 *   <li>A node test (name test, node type test, or wildcard)</li>
 *   <li>Zero or more predicates</li>
 * </ul>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code child::para} - all para children</li>
 *   <li>{@code attribute::href} - the href attribute</li>
 *   <li>{@code descendant::*} - all descendant elements</li>
 *   <li>{@code following-sibling::chapter[1]} - first following chapter sibling</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class Step {

    /**
     * The XPath axes.
     */
    public enum Axis {
        ANCESTOR("ancestor", true),
        ANCESTOR_OR_SELF("ancestor-or-self", true),
        ATTRIBUTE("attribute", false),
        CHILD("child", false),
        DESCENDANT("descendant", false),
        DESCENDANT_OR_SELF("descendant-or-self", false),
        FOLLOWING("following", false),
        FOLLOWING_SIBLING("following-sibling", false),
        NAMESPACE("namespace", false),
        PARENT("parent", true),
        PRECEDING("preceding", true),
        PRECEDING_SIBLING("preceding-sibling", true),
        SELF("self", false);

        private final String name;
        private final boolean reverse;

        Axis(String name, boolean reverse) {
            this.name = name;
            this.reverse = reverse;
        }

        /**
         * Returns the axis name as used in XPath syntax.
         *
         * @return the axis name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns true if this is a reverse axis.
         * Reverse axes select nodes in reverse document order.
         *
         * @return true for ancestor, ancestor-or-self, parent, preceding, preceding-sibling
         */
        public boolean isReverse() {
            return reverse;
        }

        /**
         * Returns true if this axis requires buffering for streaming.
         *
         * @return true for reverse axes
         */
        public boolean requiresBuffering() {
            return reverse;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Types of node tests.
     */
    public enum NodeTestType {
        /** A specific name (element, attribute, etc.). */
        NAME,
        /** A specific name with namespace prefix. */
        QNAME,
        /** The wildcard '*' matching any name. */
        WILDCARD,
        /** A namespace wildcard like 'prefix:*'. */
        NAMESPACE_WILDCARD,
        /** The node() type test. */
        NODE,
        /** The text() type test. */
        TEXT,
        /** The comment() type test. */
        COMMENT,
        /** The processing-instruction() type test. */
        PROCESSING_INSTRUCTION,
        /** The element() kind test (XPath 2.0). */
        ELEMENT,
        /** The attribute() kind test (XPath 2.0). */
        ATTRIBUTE,
        /** The document-node() kind test (XPath 2.0). */
        DOCUMENT_NODE,
        /** An XPath 3.0 expression step (simple mapping operator). */
        EXPR
    }

    private final Axis axis;
    private final NodeTestType nodeTestType;
    private final String namespaceURI;
    private final String localName;
    private final String piTarget; // For processing-instruction('target')
    private final Expr stepExpr;   // For EXPR node test type (XPath 3.0)
    private final List<Expr> predicates;

    /**
     * Creates a step with a name test.
     *
     * @param axis the axis
     * @param localName the local name to match
     */
    public Step(Axis axis, String localName) {
        this(axis, NodeTestType.NAME, null, localName, null, null, null);
    }

    /**
     * Creates a step with a qualified name test.
     *
     * @param axis the axis
     * @param namespaceURI the namespace URI
     * @param localName the local name
     */
    public Step(Axis axis, String namespaceURI, String localName) {
        this(axis, NodeTestType.QNAME, namespaceURI, localName, null, null, null);
    }

    /**
     * Creates a step with a node type test.
     *
     * @param axis the axis
     * @param nodeTestType the node test type (must be NODE, TEXT, COMMENT, or PROCESSING_INSTRUCTION)
     */
    public Step(Axis axis, NodeTestType nodeTestType) {
        this(axis, nodeTestType, null, null, null, null, null);
    }
    
    /**
     * Creates a step with a kind test that optionally specifies a name and/or type.
     * Used for element(name), element(name, type), attribute(name), etc.
     *
     * @param axis the axis
     * @param nodeTestType the node test type (ELEMENT, ATTRIBUTE, or DOCUMENT_NODE)
     * @param elementName the element/attribute name to match (may be null or "*" for any)
     * @param typeName the type name to match (may be null for any type)
     */
    public Step(Axis axis, NodeTestType nodeTestType, String elementName, String typeName) {
        this(axis, nodeTestType, typeName, elementName, null, null, null);
    }

    /**
     * Creates a step with a processing-instruction test and target.
     *
     * @param axis the axis
     * @param piTarget the PI target name
     */
    public static Step processingInstruction(Axis axis, String piTarget) {
        return new Step(axis, NodeTestType.PROCESSING_INSTRUCTION, null, null, piTarget, null, null);
    }

    /**
     * Creates a wildcard step.
     *
     * @param axis the axis
     */
    public static Step wildcard(Axis axis) {
        return new Step(axis, NodeTestType.WILDCARD, null, null, null, null, null);
    }

    /**
     * Creates a namespace wildcard step (prefix:*).
     *
     * @param axis the axis
     * @param namespaceURI the namespace URI
     */
    public static Step namespaceWildcard(Axis axis, String namespaceURI) {
        return new Step(axis, NodeTestType.NAMESPACE_WILDCARD, namespaceURI, null, null, null, null);
    }

    /**
     * Creates an expression step (XPath 3.0 simple mapping operator).
     * This allows parenthesized expressions as path steps, e.g. /(if (...) then . else foo)
     *
     * @param expr the expression to evaluate for each context node
     */
    public static Step expression(Expr expr) {
        return new Step(Axis.CHILD, NodeTestType.EXPR, null, null, null, expr, null);
    }

    /**
     * Full constructor.
     */
    private Step(Axis axis, NodeTestType nodeTestType, String namespaceURI, 
                 String localName, String piTarget, Expr stepExpr, List<Expr> predicates) {
        this.axis = axis != null ? axis : Axis.CHILD;
        this.nodeTestType = nodeTestType;
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.piTarget = piTarget;
        this.stepExpr = stepExpr;
        this.predicates = predicates != null ? 
            Collections.unmodifiableList(new ArrayList<>(predicates)) : 
            Collections.emptyList();
    }

    /**
     * Creates a new step with added predicates.
     *
     * @param predicates the predicates to add
     * @return a new step with the predicates
     */
    public Step withPredicates(List<Expr> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return this;
        }
        List<Expr> combined = new ArrayList<>(this.predicates);
        combined.addAll(predicates);
        return new Step(axis, nodeTestType, namespaceURI, localName, piTarget, stepExpr, combined);
    }

    /**
     * Returns the axis.
     *
     * @return the axis
     */
    public Axis getAxis() {
        return axis;
    }

    /**
     * Returns the node test type.
     *
     * @return the node test type
     */
    public NodeTestType getNodeTestType() {
        return nodeTestType;
    }

    /**
     * Returns the namespace URI for QNAME and NAMESPACE_WILDCARD tests.
     *
     * @return the namespace URI, or null
     */
    public String getNamespaceURI() {
        return namespaceURI;
    }

    /**
     * Returns the local name for NAME and QNAME tests.
     *
     * @return the local name, or null
     */
    public String getLocalName() {
        return localName;
    }

    /**
     * Returns the PI target for processing-instruction() tests.
     *
     * @return the PI target, or null
     */
    public String getPITarget() {
        return piTarget;
    }

    /**
     * Returns the expression for EXPR steps (XPath 3.0 simple mapping).
     *
     * @return the expression, or null
     */
    public Expr getStepExpr() {
        return stepExpr;
    }

    /**
     * Returns the predicates.
     *
     * @return the predicates (immutable)
     */
    public List<Expr> getPredicates() {
        return predicates;
    }

    /**
     * Returns true if this step has predicates.
     *
     * @return true if predicates are present
     */
    public boolean hasPredicates() {
        return !predicates.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        // Axis
        sb.append(axis.getName()).append("::");
        
        // Node test
        switch (nodeTestType) {
            case NAME:
                sb.append(localName);
                break;
            case QNAME:
                if (namespaceURI != null) {
                    sb.append('{').append(namespaceURI).append('}');
                }
                sb.append(localName);
                break;
            case WILDCARD:
                sb.append('*');
                break;
            case NAMESPACE_WILDCARD:
                sb.append('{').append(namespaceURI).append("}:*");
                break;
            case NODE:
                sb.append("node()");
                break;
            case TEXT:
                sb.append("text()");
                break;
            case COMMENT:
                sb.append("comment()");
                break;
            case PROCESSING_INSTRUCTION:
                sb.append("processing-instruction(");
                if (piTarget != null) {
                    sb.append('\'').append(piTarget).append('\'');
                }
                sb.append(')');
                break;
            case ELEMENT:
                sb.append("element(");
                if (localName != null && !"*".equals(localName)) {
                    sb.append(localName);
                    if (namespaceURI != null) {  // type name stored in namespace field
                        sb.append(", ").append(namespaceURI);
                    }
                }
                sb.append(')');
                break;
            case ATTRIBUTE:
                sb.append("attribute(");
                if (localName != null && !"*".equals(localName)) {
                    sb.append(localName);
                    if (namespaceURI != null) {
                        sb.append(", ").append(namespaceURI);
                    }
                }
                sb.append(')');
                break;
            case DOCUMENT_NODE:
                sb.append("document-node()");
                break;
            case EXPR:
                sb.setLength(0);  // Clear the axis:: prefix
                sb.append('(').append(stepExpr).append(')');
                break;
        }
        
        // Predicates
        for (Expr pred : predicates) {
            sb.append('[').append(pred).append(']');
        }
        
        return sb.toString();
    }

}
