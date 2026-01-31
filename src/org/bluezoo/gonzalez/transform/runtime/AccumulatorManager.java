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

import org.bluezoo.gonzalez.transform.compiler.AccumulatorDefinition;
import org.bluezoo.gonzalez.transform.compiler.AccumulatorDefinition.AccumulatorRule;
import org.bluezoo.gonzalez.transform.compiler.AccumulatorDefinition.Phase;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.xml.sax.SAXException;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages accumulator state during streaming transformation.
 *
 * <p>The AccumulatorManager is the central component for accumulator evaluation.
 * It maintains the state for all accumulators defined in the stylesheet and
 * coordinates rule firing at the appropriate times during document traversal.
 *
 * <p>Usage pattern:
 * <pre>
 * AccumulatorManager mgr = new AccumulatorManager(stylesheet, context);
 * mgr.initialize();
 * 
 * // On startElement:
 * mgr.notifyStartElement(node);
 * 
 * // During processing, access values:
 * XPathValue before = mgr.getAccumulatorBefore("my-acc");
 * XPathValue after = mgr.getAccumulatorAfter("my-acc");
 * 
 * // On endElement:
 * mgr.notifyEndElement(node);
 * 
 * // After document:
 * mgr.reset();
 * </pre>
 *
 * <p>The manager fires accumulator rules in the following order:
 * <ul>
 *   <li>On startElement: pre-descent rules for matching accumulators</li>
 *   <li>On endElement: post-descent rules for matching accumulators</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AccumulatorManager {

    private final CompiledStylesheet stylesheet;
    private final TransformContext transformContext;
    private final Map<String, AccumulatorState> accumulators;
    private boolean initialized;

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
    }

    /**
     * Creates a copy of an existing AccumulatorManager with independent state.
     * Used for xsl:fork to give each branch its own accumulator state.
     * The accumulator states are deep-copied so modifications don't affect the original.
     *
     * @param other the manager to copy
     * @param newContext the transformation context for the new manager
     */
    public AccumulatorManager(AccumulatorManager other, TransformContext newContext) {
        this.stylesheet = other.stylesheet;
        this.transformContext = newContext;
        this.accumulators = new HashMap<>();
        this.initialized = other.initialized;
        
        // Deep copy all accumulator states
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
            
            // Evaluate initial value
            XPathValue initialValue = evaluateInitialValue(def);
            
            AccumulatorState state = new AccumulatorState(def, initialValue);
            accumulators.put(entry.getKey(), state);
        }
        
        initialized = true;
    }

    /**
     * Evaluates the initial value expression for an accumulator.
     *
     * @param def the accumulator definition
     * @return the initial value, or empty string if no initial value expression
     * @throws SAXException if evaluation fails
     */
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
        // Default to empty string if no initial value
        return XPathString.of("");
    }

    /**
     * Notifies the manager of a startElement event.
     * Fires pre-descent rules for matching accumulators and pushes state.
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
            
            // Push current value before processing
            state.push();
            
            // Fire pre-descent rules
            for (AccumulatorRule rule : def.getPreDescentRules()) {
                if (matchesPattern(rule.getMatchPattern(), node)) {
                    XPathValue newValue = evaluateRule(state, rule, node);
                    state.setCurrentValue(newValue);
                }
            }
        }
    }

    /**
     * Notifies the manager of an endElement event.
     * Fires post-descent rules for matching accumulators and pops state.
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
            
            // Fire post-descent rules
            for (AccumulatorRule rule : def.getPostDescentRules()) {
                if (matchesPattern(rule.getMatchPattern(), node)) {
                    XPathValue newValue = evaluateRule(state, rule, node);
                    state.setCurrentValue(newValue);
                }
            }
            
            // Pop state (but keep the updated current value)
            state.pop();
        }
    }

    /**
     * Tests if a pattern matches a node.
     *
     * @param pattern the pattern to test
     * @param node the node to match
     * @return true if the pattern matches the node
     */
    private boolean matchesPattern(Pattern pattern, XPathNode node) {
        if (pattern == null) {
            return false;
        }
        return pattern.matches(node, transformContext);
    }

    /**
     * Evaluates an accumulator rule to compute the new value.
     *
     * @param state the accumulator state
     * @param rule the rule to evaluate
     * @param node the node triggering the rule
     * @return the new accumulator value
     * @throws SAXException if evaluation fails
     */
    private XPathValue evaluateRule(AccumulatorState state, AccumulatorRule rule,
                                     XPathNode node) throws SAXException {
        try {
            // Create a context with $value bound to current accumulator value
            TransformContext ruleContext = transformContext.withContextNode(node);
            ruleContext.getVariableScope().bind("value", state.getCurrentValue());
            
            XPathExpression newValueExpr = rule.getNewValue();
            if (newValueExpr != null) {
                return newValueExpr.evaluate(ruleContext);
            }
            
            // No new value expression - keep current value
            return state.getCurrentValue();
        } catch (Exception e) {
            throw new SAXException("Error evaluating accumulator rule for " + 
                                   state.getDefinition().getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the accumulator-before value for the named accumulator.
     * This is the value before processing the current node.
     *
     * @param name the accumulator name
     * @return the before value, or null if accumulator not found
     */
    public XPathValue getAccumulatorBefore(String name) {
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            return state.getBeforeValue();
        }
        return null;
    }

    /**
     * Returns the accumulator-after value for the named accumulator.
     * This is the value after processing the current node.
     *
     * @param name the accumulator name
     * @return the after value, or null if accumulator not found
     */
    public XPathValue getAccumulatorAfter(String name) {
        AccumulatorState state = accumulators.get(name);
        if (state != null) {
            return state.getAfterValue();
        }
        return null;
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
               ", initialized=" + initialized + "]";
    }

}
