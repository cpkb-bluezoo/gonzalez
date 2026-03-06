/*
 * AccumulatorManager.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.AccumulatorDefinition;
import org.bluezoo.gonzalez.transform.compiler.AccumulatorDefinition.AccumulatorRule;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.List;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Manages accumulator state during transformation.
 *
 * <p>For tree-based (non-streaming) transforms, accumulators are computed by
 * performing a depth-first traversal of each document before template values
 * are accessed. Each document (main input, doc() results, temporary trees)
 * gets its own independent traversal starting from the initial accumulator
 * values, per XSLT 3.0 Section 6.1.
 *
 * <p>For streaming transforms, accumulator rules fire on SAX events
 * via {@link #notifyStartElement} and {@link #notifyEndElement}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AccumulatorManager {

    /**
     * Sentinel XPathValue that wraps a deferred error.
     * When an accumulator rule or initial-value causes an error,
     * it is stored as a DeferredError and re-thrown when the value is accessed.
     */
    static final class DeferredError implements XPathValue {
        final Exception cause;
        DeferredError(Exception cause) {
            this.cause = cause;
        }
        @Override public Type getType() { return Type.STRING; }
        @Override public String asString() { return ""; }
        @Override public double asNumber() { return Double.NaN; }
        @Override public boolean asBoolean() { return false; }
        @Override public XPathNodeSet asNodeSet() { return null; }
    }

    private final CompiledStylesheet stylesheet;
    private final TransformContext transformContext;
    private final Map<String, AccumulatorState> accumulators;
    private boolean initialized;
    private boolean streamingMode;

    /**
     * Pre-computed before/after values keyed by accumulator name then node
     * identity. Populated lazily as documents are encountered.
     */
    private final Map<String, Map<XPathNode, XPathValue>> beforeValues;
    private final Map<String, Map<XPathNode, XPathValue>> afterValues;

    /** Document roots that have been fully traversed (by identity). */
    private final Set<XPathNode> traversedDocuments;

    /** Accumulators currently being evaluated (for cycle detection). */
    private final Set<String> evaluatingAccumulators;

    /**
     * Creates a new AccumulatorManager.
     *
     * @param stylesheet the compiled stylesheet containing accumulator definitions
     * @param transformContext the transformation context for expression evaluation
     */
    public AccumulatorManager(CompiledStylesheet stylesheet, TransformContext transformContext) {
        this.stylesheet = stylesheet;
        this.transformContext = transformContext;
        this.accumulators = new HashMap<>();
        this.initialized = false;
        this.beforeValues = new HashMap<>();
        this.afterValues = new HashMap<>();
        this.traversedDocuments = new HashSet<XPathNode>();
        this.evaluatingAccumulators = new HashSet<>();
    }

    /**
     * Creates a copy of an existing AccumulatorManager with independent state.
     * Used for xsl:fork to give each branch its own accumulator state.
     *
     * @param other the manager to copy
     * @param newContext the transformation context for the new manager
     */
    public AccumulatorManager(AccumulatorManager other, TransformContext newContext) {
        this.stylesheet = other.stylesheet;
        this.transformContext = newContext;
        this.accumulators = new HashMap<>();
        this.initialized = other.initialized;
        this.beforeValues = other.beforeValues;
        this.afterValues = other.afterValues;
        this.traversedDocuments = other.traversedDocuments;
        this.evaluatingAccumulators = other.evaluatingAccumulators;

        for (Map.Entry<String, AccumulatorState> entry : other.accumulators.entrySet()) {
            this.accumulators.put(entry.getKey(), new AccumulatorState(entry.getValue()));
        }
    }

    /**
     * Initializes all accumulators with their initial values.
     * Must be called before document processing begins.
     *
     * @throws SAXException if initialization fails
     */
    public void initialize() throws SAXException {
        accumulators.clear();

        for (Map.Entry<String, AccumulatorDefinition> entry : stylesheet.getAccumulators().entrySet()) {
            AccumulatorDefinition def = entry.getValue();
            XPathValue initialValue = evaluateInitialValue(def);
            AccumulatorState state = new AccumulatorState(def, initialValue);
            accumulators.put(entry.getKey(), state);
        }

        for (String accName : accumulators.keySet()) {
            if (!beforeValues.containsKey(accName)) {
                beforeValues.put(accName, new IdentityHashMap<XPathNode, XPathValue>());
            }
            if (!afterValues.containsKey(accName)) {
                afterValues.put(accName, new IdentityHashMap<XPathNode, XPathValue>());
            }
        }

        initialized = true;
    }

    /**
     * Pre-traverses a grounded (tree-based) document to compute all accumulator
     * values. Each document starts from initial accumulator values per the spec.
     *
     * @param documentRoot the root node of the document to traverse
     * @throws SAXException if rule evaluation fails
     */
    public void preTraverseDocument(XPathNode documentRoot) throws SAXException {
        if (!initialized) {
            initialize();
        }

        if (traversedDocuments.contains(documentRoot)) {
            return;
        }

        // Mark before traversal to prevent reentrant calls when rules
        // reference accumulator-before/after during evaluation
        traversedDocuments.add(documentRoot);

        Map<String, XPathValue> savedValues = new HashMap<>();
        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            savedValues.put(entry.getKey(), entry.getValue().getCurrentValue());
        }

        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            AccumulatorDefinition def = entry.getValue().getDefinition();
            XPathValue initialValue = evaluateInitialValue(def);
            entry.getValue().setCurrentValue(initialValue);
        }

        traverseNode(documentRoot);

        for (Map.Entry<String, XPathValue> entry : savedValues.entrySet()) {
            AccumulatorState state = accumulators.get(entry.getKey());
            if (state != null) {
                state.setCurrentValue(entry.getValue());
            }
        }
    }

    /**
     * Recursive depth-first traversal that fires accumulator rules on each node
     * and records the before/after values.
     */
    private void traverseNode(XPathNode node) throws SAXException {
        XPathNode copySource = node.getCopySource();
        if (copySource != null) {
            traverseCopiedNode(node, copySource);
            return;
        }

        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            String accName = entry.getKey();
            AccumulatorState state = entry.getValue();
            AccumulatorDefinition def = state.getDefinition();

            if (!(state.getCurrentValue() instanceof DeferredError)) {
                for (AccumulatorRule rule : def.getPreDescentRules()) {
                    if (matchesPattern(rule.getMatchPattern(), node)) {
                        XPathValue newValue = evaluateRule(state, rule, node);
                        newValue = coerceWithDeferral(newValue, def);
                        state.setCurrentValue(newValue);
                    }
                }
            }

            beforeValues.get(accName).put(node, state.getCurrentValue());
        }

        Iterator<XPathNode> children = node.getChildren();
        while (children.hasNext()) {
            traverseNode(children.next());
        }

        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            String accName = entry.getKey();
            AccumulatorState state = entry.getValue();
            AccumulatorDefinition def = state.getDefinition();

            if (!(state.getCurrentValue() instanceof DeferredError)) {
                for (AccumulatorRule rule : def.getPostDescentRules()) {
                    if (matchesPattern(rule.getMatchPattern(), node)) {
                        XPathValue newValue = evaluateRule(state, rule, node);
                        newValue = coerceWithDeferral(newValue, def);
                        state.setCurrentValue(newValue);
                    }
                }
            }

            afterValues.get(accName).put(node, state.getCurrentValue());
        }
    }

    /**
     * Traverses a copied node subtree, propagating accumulator values from
     * the original source nodes. Ensures the source document is traversed first.
     */
    private void traverseCopiedNode(XPathNode copiedNode, XPathNode sourceNode)
            throws SAXException {
        ensureDocumentTraversed(sourceNode);

        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            String accName = entry.getKey();
            AccumulatorState state = entry.getValue();
            Map<XPathNode, XPathValue> srcBefore = beforeValues.get(accName);
            if (srcBefore != null) {
                XPathValue val = srcBefore.get(sourceNode);
                if (val != null) {
                    beforeValues.get(accName).put(copiedNode, val);
                    state.setCurrentValue(val);
                }
            }
        }

        Iterator<XPathNode> copiedChildren = copiedNode.getChildren();
        Iterator<XPathNode> sourceChildren = sourceNode.getChildren();
        while (copiedChildren.hasNext() && sourceChildren.hasNext()) {
            XPathNode copiedChild = copiedChildren.next();
            XPathNode sourceChild = sourceChildren.next();
            traverseCopiedNode(copiedChild, sourceChild);
        }

        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            String accName = entry.getKey();
            AccumulatorState state = entry.getValue();
            Map<XPathNode, XPathValue> srcAfter = afterValues.get(accName);
            if (srcAfter != null) {
                XPathValue val = srcAfter.get(sourceNode);
                if (val != null) {
                    afterValues.get(accName).put(copiedNode, val);
                    state.setCurrentValue(val);
                }
            }
        }
    }

    /**
     * Finds the document root of a node by walking up the parent chain.
     */
    private XPathNode getDocumentRoot(XPathNode node) {
        XPathNode current = node;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current;
    }

    /**
     * Ensures the document containing the given node has been traversed.
     * If not yet traversed, performs a lazy traversal.
     */
    private void ensureDocumentTraversed(XPathNode node) {
        if (node == null) {
            return;
        }
        XPathNode root = getDocumentRoot(node);
        if (!traversedDocuments.contains(root)) {
            try {
                preTraverseDocument(root);
            } catch (SAXException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("XTDE3400")) {
                    throw new RuntimeException(msg, e);
                }
            }
        }
    }

    private XPathValue evaluateInitialValue(AccumulatorDefinition def) throws SAXException {
        try {
            XPathExpression initialExpr = def.getInitialValue();
            if (initialExpr != null) {
                XPathValue value = initialExpr.evaluate(transformContext);
                return coerceToAsType(value, def);
            }
        } catch (Exception e) {
            return new DeferredError(e);
        }
        return XPathString.of("");
    }

    /**
     * Notifies the manager of a startElement event (streaming mode).
     * In tree mode, this is a no-op since values are pre-computed.
     *
     * @param node the node being entered
     * @throws SAXException if rule evaluation fails
     */
    public void notifyStartElement(XPathNode node) throws SAXException {
        if (!initialized) {
            initialize();
        }

        for (AccumulatorState state : accumulators.values()) {
            AccumulatorDefinition def = state.getDefinition();
            state.push();

            if (!(state.getCurrentValue() instanceof DeferredError)) {
                for (AccumulatorRule rule : def.getPreDescentRules()) {
                    if (matchesPattern(rule.getMatchPattern(), node)) {
                        XPathValue newValue = evaluateRule(state, rule, node);
                        newValue = coerceWithDeferral(newValue, def);
                        state.setCurrentValue(newValue);
                    }
                }
            }
        }
    }

    /**
     * Notifies the manager of an endElement event (streaming mode).
     *
     * @param node the node being exited
     * @throws SAXException if rule evaluation fails
     */
    public void notifyEndElement(XPathNode node) throws SAXException {
        if (!initialized) {
            return;
        }

        for (AccumulatorState state : accumulators.values()) {
            AccumulatorDefinition def = state.getDefinition();

            if (!(state.getCurrentValue() instanceof DeferredError)) {
                for (AccumulatorRule rule : def.getPostDescentRules()) {
                    if (matchesPattern(rule.getMatchPattern(), node)) {
                        XPathValue newValue = evaluateRule(state, rule, node);
                        newValue = coerceWithDeferral(newValue, def);
                        state.setCurrentValue(newValue);
                    }
                }
            }

            state.pop();
        }
    }

    /**
     * Notifies the manager of a text (or other leaf) node (streaming mode).
     * Fires both pre-descent and post-descent rules since leaf nodes have no children.
     * No push/pop is needed because there is no nested element processing.
     *
     * @param node the text/leaf node
     * @throws SAXException if rule evaluation fails
     */
    public void notifyTextNode(XPathNode node) throws SAXException {
        if (!initialized) {
            initialize();
        }

        for (AccumulatorState state : accumulators.values()) {
            AccumulatorDefinition def = state.getDefinition();

            if (!(state.getCurrentValue() instanceof DeferredError)) {
                for (AccumulatorRule rule : def.getPreDescentRules()) {
                    boolean matches = matchesPattern(rule.getMatchPattern(), node);
                    if (matches) {
                        XPathValue newValue = evaluateRule(state, rule, node);
                        newValue = coerceWithDeferral(newValue, def);
                        state.setCurrentValue(newValue);
                    }
                }
            }

            if (!(state.getCurrentValue() instanceof DeferredError)) {
                for (AccumulatorRule rule : def.getPostDescentRules()) {
                    boolean matches = matchesPattern(rule.getMatchPattern(), node);
                    if (matches) {
                        XPathValue newValue = evaluateRule(state, rule, node);
                        newValue = coerceWithDeferral(newValue, def);
                        state.setCurrentValue(newValue);
                    }
                }
            }
        }
    }

    private boolean matchesPattern(Pattern pattern, XPathNode node) {
        if (pattern == null) {
            return false;
        }
        return pattern.matches(node, transformContext);
    }

    /**
     * Coerces a value to the accumulator's declared as type.
     * Untyped atomic values are promoted to the target type per XPath rules.
     */
    private XPathValue coerceToAsType(XPathValue value, AccumulatorDefinition def)
            throws SAXException {
        String asType = def.getAsType();
        if (asType == null || value == null || value instanceof DeferredError) {
            return value;
        }
        String baseType = asType.replaceAll("[*+?]", "").trim();
        boolean isNumericType = "xs:double".equals(baseType) || "xs:float".equals(baseType)
                || "xs:decimal".equals(baseType) || "xs:integer".equals(baseType)
                || "xs:long".equals(baseType) || "xs:int".equals(baseType)
                || "xs:short".equals(baseType) || "xs:byte".equals(baseType)
                || "xs:nonNegativeInteger".equals(baseType)
                || "xs:positiveInteger".equals(baseType)
                || "xs:nonPositiveInteger".equals(baseType)
                || "xs:negativeInteger".equals(baseType)
                || "xs:unsignedLong".equals(baseType)
                || "xs:unsignedInt".equals(baseType)
                || "xs:unsignedShort".equals(baseType)
                || "xs:unsignedByte".equals(baseType);
        boolean isIntegerSubtype = "xs:integer".equals(baseType)
                || "xs:long".equals(baseType) || "xs:int".equals(baseType)
                || "xs:short".equals(baseType) || "xs:byte".equals(baseType)
                || "xs:nonNegativeInteger".equals(baseType)
                || "xs:positiveInteger".equals(baseType)
                || "xs:nonPositiveInteger".equals(baseType)
                || "xs:negativeInteger".equals(baseType)
                || "xs:unsignedLong".equals(baseType)
                || "xs:unsignedInt".equals(baseType)
                || "xs:unsignedShort".equals(baseType)
                || "xs:unsignedByte".equals(baseType);

        if (value instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) value;
            List<XPathValue> coerced = new ArrayList<>();
            boolean changed = false;
            for (XPathValue item : seq) {
                XPathValue c = coerceSingleItem(item, isNumericType, isIntegerSubtype, baseType, def);
                if (c != item) {
                    changed = true;
                }
                coerced.add(c);
            }
            if (changed) {
                return new XPathSequence(coerced);
            }
            return value;
        }
        return coerceSingleItem(value, isNumericType, isIntegerSubtype, baseType, def);
    }

    private XPathValue coerceSingleItem(XPathValue item, boolean isNumericType,
            boolean isIntegerSubtype, String baseType, AccumulatorDefinition def)
            throws SAXException {
        if (item instanceof XPathNode || item instanceof XPathNodeSet) {
            if (isNumericType) {
                double d = item.asNumber();
                checkNumericCoercion(d, isIntegerSubtype, baseType, def);
                if (isIntegerSubtype) {
                    return XPathNumber.of((long) d);
                }
                return XPathNumber.of(d);
            }
            if ("xs:string".equals(baseType)) {
                return XPathString.of(item.asString());
            }
            return item;
        }
        if (item instanceof XPathUntypedAtomic) {
            if (isNumericType) {
                double d = item.asNumber();
                checkNumericCoercion(d, isIntegerSubtype, baseType, def);
                if (isIntegerSubtype) {
                    return XPathNumber.of((long) d);
                }
                return XPathNumber.of(d);
            }
            if ("xs:string".equals(baseType)) {
                return XPathString.of(item.asString());
            }
            return item;
        }
        if (isNumericType) {
            if (item instanceof XPathString) {
                throw new SAXException("XPTY0004: Cannot convert string to " + baseType +
                    " in accumulator " + def.getName());
            }
            double d = item.asNumber();
            checkNumericCoercion(d, isIntegerSubtype, baseType, def);
            if (isIntegerSubtype) {
                long lv = (long) d;
                if (lv != d) {
                    return XPathNumber.of(lv);
                }
            }
        }
        return item;
    }

    /**
     * Wraps coercion to catch errors and produce DeferredError values,
     * matching the spec requirement that accumulator errors are deferred.
     */
    private XPathValue coerceWithDeferral(XPathValue value, AccumulatorDefinition def) {
        if (value instanceof DeferredError) {
            return value;
        }
        try {
            return coerceToAsType(value, def);
        } catch (Exception e) {
            return new DeferredError(e);
        }
    }

    private void checkNumericCoercion(double d, boolean isIntegerSubtype,
            String baseType, AccumulatorDefinition def) throws SAXException {
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            throw new SAXException("FOAR0001: Cannot convert " + d + " to " + baseType +
                " in accumulator " + def.getName());
        }
    }

    private XPathValue evaluateRule(AccumulatorState state, AccumulatorRule rule,
                                     XPathNode node) throws SAXException {
        String accName = state.getDefinition().getName();
        if (!evaluatingAccumulators.add(accName)) {
            throw new SAXException("XTDE3400: Cyclic dependency detected " +
                "evaluating accumulator '" + accName + "'");
        }
        try {
            TransformContext ruleContext = transformContext.withContextNode(node);
            if (ruleContext instanceof BasicTransformContext) {
                ((BasicTransformContext) ruleContext).setAccumulatorManager(this);
            }
            ruleContext.getVariableScope().bind("value", state.getCurrentValue());

            XPathExpression newValueExpr = rule.getNewValue();
            if (newValueExpr != null) {
                return newValueExpr.evaluate(ruleContext);
            }

            XSLTNode body = rule.getBody();
            if (body != null) {
                return executeSequenceConstructor(body, ruleContext);
            }

            return state.getCurrentValue();
        } catch (SAXException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("XTDE3400")) {
                throw e;
            }
            return new DeferredError(e);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("XTDE3400")) {
                throw e;
            }
            return new DeferredError(e);
        } catch (Exception e) {
            return new DeferredError(e);
        } finally {
            evaluatingAccumulators.remove(accName);
        }
    }

    /**
     * Executes a sequence constructor body and returns its result as an XPathValue.
     */
    private XPathValue executeSequenceConstructor(XSLTNode body, TransformContext context)
            throws SAXException {
        SequenceBuilderOutputHandler seqBuilder = new SequenceBuilderOutputHandler();
        body.execute(context, seqBuilder);
        XPathValue result = seqBuilder.getSequence();
        if (result instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) result;
            if (seq.size() == 1) {
                return seq.iterator().next();
            }
        }
        return result;
    }

    /**
     * Returns the accumulator-before value for the named accumulator.
     * Lazily traverses the node's document if not yet traversed.
     *
     * @param name the accumulator name
     * @param node the context node
     * @return the before value, or null if accumulator not found
     */
    public XPathValue getAccumulatorBefore(String name, XPathNode node) {
        if (evaluatingAccumulators.contains(name)) {
            throw new RuntimeException("XTDE3400: Cyclic dependency detected " +
                "evaluating accumulator '" + name + "'");
        }
        if (node != null && !streamingMode) {
            ensureDocumentTraversed(node);
            Map<XPathNode, XPathValue> nodeMap = beforeValues.get(name);
            if (nodeMap != null) {
                XPathValue val = nodeMap.get(node);
                if (val != null) {
                    rethrowIfDeferred(val);
                    return val;
                }
            }
        }
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            XPathValue val = state.getBeforeValue();
            rethrowIfDeferred(val);
            return val;
        }
        return null;
    }

    /**
     * Returns the accumulator-after value for the named accumulator.
     * Lazily traverses the node's document if not yet traversed.
     *
     * @param name the accumulator name
     * @param node the context node
     * @return the after value, or null if accumulator not found
     */
    public XPathValue getAccumulatorAfter(String name, XPathNode node) {
        if (evaluatingAccumulators.contains(name)) {
            throw new RuntimeException("XTDE3400: Cyclic dependency detected " +
                "evaluating accumulator '" + name + "'");
        }
        if (node != null && !streamingMode) {
            ensureDocumentTraversed(node);
            Map<XPathNode, XPathValue> nodeMap = afterValues.get(name);
            if (nodeMap != null) {
                XPathValue val = nodeMap.get(node);
                if (val != null) {
                    rethrowIfDeferred(val);
                    return val;
                }
            }
        }
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            XPathValue val = state.getAfterValue();
            rethrowIfDeferred(val);
            return val;
        }
        return null;
    }

    /**
     * Re-throws a deferred error as a RuntimeException if the value is a DeferredError.
     */
    private void rethrowIfDeferred(XPathValue val) {
        if (val instanceof DeferredError) {
            Exception cause = ((DeferredError) val).cause;
            String msg = cause.getMessage();
            if (msg == null) {
                msg = cause.getClass().getName();
            }
            throw new RuntimeException(msg, cause);
        }
    }

    /**
     * Returns the accumulator-before value (no node context, for streaming).
     *
     * @param name the accumulator name
     * @return the before value, or null if accumulator not found
     */
    public XPathValue getAccumulatorBefore(String name) {
        return getAccumulatorBefore(name, null);
    }

    /**
     * Returns the accumulator-after value (no node context, for streaming).
     *
     * @param name the accumulator name
     * @return the after value, or null if accumulator not found
     */
    public XPathValue getAccumulatorAfter(String name) {
        return getAccumulatorAfter(name, null);
    }

    /**
     * Returns the current value for the named accumulator.
     *
     * @param name the accumulator name
     * @return the current value, or null if accumulator not found
     */
    public XPathValue getAccumulatorValue(String name) {
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            return state.getCurrentValue();
        }
        return null;
    }

    /**
     * Returns true if the named accumulator exists.
     *
     * @param name the accumulator name
     * @return true if the accumulator exists
     */
    public boolean hasAccumulator(String name) {
        return accumulators.containsKey(name);
    }

    /**
     * Returns true if the document containing the given node has been
     * pre-traversed for accumulator values.
     *
     * @param node a node in the document
     * @return true if pre-traversed
     */
    public boolean isPreTraversed(XPathNode node) {
        if (node == null) {
            return false;
        }
        XPathNode root = getDocumentRoot(node);
        return traversedDocuments.contains(root);
    }

    /**
     * Resets all accumulator states.
     * Called after document processing completes.
     *
     * @throws SAXException if reset fails
     */
    public void reset() throws SAXException {
        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            AccumulatorDefinition def = entry.getValue().getDefinition();
            XPathValue initialValue = evaluateInitialValue(def);
            entry.getValue().reset(initialValue);
        }
    }

    /**
     * Returns true if the manager has been initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Sets streaming mode. When true, accumulator-before/after return the
     * current flowing value without attempting tree traversal. This is
     * essential for streaming transforms where the document tree is not
     * fully materialized.
     *
     * @param streaming true to enable streaming mode
     */
    public void setStreamingMode(boolean streaming) {
        this.streamingMode = streaming;
    }

    /**
     * Returns the number of accumulators being managed.
     *
     * @return the accumulator count
     */
    public int getAccumulatorCount() {
        return accumulators.size();
    }

    @Override
    public String toString() {
        return "AccumulatorManager[accumulators=" + accumulators.size() +
               ", initialized=" + initialized +
               ", documents=" + traversedDocuments.size() + "]";
    }

}
