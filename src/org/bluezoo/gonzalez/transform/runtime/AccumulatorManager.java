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
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.xml.sax.SAXException;

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

    private final CompiledStylesheet stylesheet;
    private final TransformContext transformContext;
    private final Map<String, AccumulatorState> accumulators;
    private boolean initialized;

    /**
     * Pre-computed before/after values keyed by accumulator name then node
     * identity. Populated lazily as documents are encountered.
     */
    private final Map<String, Map<XPathNode, XPathValue>> beforeValues;
    private final Map<String, Map<XPathNode, XPathValue>> afterValues;

    /** Document roots that have been fully traversed (by identity). */
    private final Set<XPathNode> traversedDocuments;

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
        for (Map.Entry<String, AccumulatorState> entry : accumulators.entrySet()) {
            String accName = entry.getKey();
            AccumulatorState state = entry.getValue();
            AccumulatorDefinition def = state.getDefinition();

            for (AccumulatorRule rule : def.getPreDescentRules()) {
                if (matchesPattern(rule.getMatchPattern(), node)) {
                    XPathValue newValue = evaluateRule(state, rule, node);
                    state.setCurrentValue(newValue);
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

            for (AccumulatorRule rule : def.getPostDescentRules()) {
                if (matchesPattern(rule.getMatchPattern(), node)) {
                    XPathValue newValue = evaluateRule(state, rule, node);
                    state.setCurrentValue(newValue);
                }
            }

            afterValues.get(accName).put(node, state.getCurrentValue());
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
                // Traversal failed - already marked as traversed to prevent retries
            }
        }
    }

    private XPathValue evaluateInitialValue(AccumulatorDefinition def) throws SAXException {
        try {
            XPathExpression initialExpr = def.getInitialValue();
            if (initialExpr != null) {
                return initialExpr.evaluate(transformContext);
            }
        } catch (Exception e) {
            throw new SAXException("Error evaluating initial value for accumulator " +
                                   def.getName() + ": " + e.getMessage(), e);
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

            for (AccumulatorRule rule : def.getPreDescentRules()) {
                if (matchesPattern(rule.getMatchPattern(), node)) {
                    XPathValue newValue = evaluateRule(state, rule, node);
                    state.setCurrentValue(newValue);
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

            for (AccumulatorRule rule : def.getPostDescentRules()) {
                if (matchesPattern(rule.getMatchPattern(), node)) {
                    XPathValue newValue = evaluateRule(state, rule, node);
                    state.setCurrentValue(newValue);
                }
            }

            state.pop();
        }
    }

    private boolean matchesPattern(Pattern pattern, XPathNode node) {
        if (pattern == null) {
            return false;
        }
        return pattern.matches(node, transformContext);
    }

    private XPathValue evaluateRule(AccumulatorState state, AccumulatorRule rule,
                                     XPathNode node) throws SAXException {
        try {
            TransformContext ruleContext = transformContext.withContextNode(node);
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
        } catch (Exception e) {
            throw new SAXException("Error evaluating accumulator rule for " +
                                   state.getDefinition().getName() + ": " + e.getMessage(), e);
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
        if (node != null) {
            ensureDocumentTraversed(node);
            Map<XPathNode, XPathValue> nodeMap = beforeValues.get(name);
            if (nodeMap != null) {
                XPathValue val = nodeMap.get(node);
                if (val != null) {
                    return val;
                }
            }
        }
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            return state.getBeforeValue();
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
        if (node != null) {
            ensureDocumentTraversed(node);
            Map<XPathNode, XPathValue> nodeMap = afterValues.get(name);
            if (nodeMap != null) {
                XPathValue val = nodeMap.get(node);
                if (val != null) {
                    return val;
                }
            }
        }
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            return state.getAfterValue();
        }
        return null;
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
