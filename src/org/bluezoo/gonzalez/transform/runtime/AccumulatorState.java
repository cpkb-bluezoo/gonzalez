/*
 * AccumulatorState.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Runtime state for a single accumulator during transformation.
 *
 * <p>Each accumulator maintains a stack of values that tracks:
 * <ul>
 *   <li>The current value after pre-descent rules have fired</li>
 *   <li>Previous values at each ancestor level in the document</li>
 * </ul>
 *
 * <p>The stack is pushed on startElement and popped on endElement,
 * enabling accumulator-before() and accumulator-after() to return
 * the appropriate values at any point in the document traversal.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AccumulatorState {

    private final AccumulatorDefinition definition;
    private final Deque<XPathValue> valueStack;
    private XPathValue currentValue;

    /**
     * Creates a new accumulator state with the given initial value.
     *
     * @param definition the accumulator definition
     * @param initialValue the initial value
     */
    public AccumulatorState(AccumulatorDefinition definition, XPathValue initialValue) {
        this.definition = definition;
        this.valueStack = new ArrayDeque<>();
        this.currentValue = initialValue;
    }

    /**
     * Creates a copy of an existing accumulator state.
     * Used for xsl:fork to give each branch independent state.
     *
     * @param other the state to copy
     */
    public AccumulatorState(AccumulatorState other) {
        this.definition = other.definition;
        this.valueStack = new ArrayDeque<>(other.valueStack);
        this.currentValue = other.currentValue;
    }

    /**
     * Returns the accumulator definition.
     *
     * @return the definition
     */
    public AccumulatorDefinition getDefinition() {
        return definition;
    }

    /**
     * Returns the current accumulator value.
     * This is the value after the most recent rule firing.
     *
     * @return the current value
     */
    public XPathValue getCurrentValue() {
        return currentValue;
    }

    /**
     * Sets the current accumulator value.
     * Called when a rule fires and computes a new value.
     *
     * @param value the new value
     */
    public void setCurrentValue(XPathValue value) {
        this.currentValue = value;
    }

    /**
     * Returns the accumulator-before value.
     * This is the value before processing the current node's content.
     *
     * @return the before value
     */
    public XPathValue getBeforeValue() {
        // The before value is the current value after pre-descent rules
        return currentValue;
    }

    /**
     * Returns the accumulator-after value.
     * This is the value after processing the current node's content.
     *
     * @return the after value (same as current at this point)
     */
    public XPathValue getAfterValue() {
        // The after value is the current value after post-descent rules
        return currentValue;
    }

    /**
     * Pushes the current value onto the stack.
     * Called at startElement to save state before processing children.
     */
    public void push() {
        valueStack.push(currentValue);
    }

    /**
     * Pops a value from the stack.
     * Called at endElement after processing children.
     * Note: The current value is not restored from the stack because
     * post-descent rules may have updated it.
     *
     * @return the popped value (the value before processing children)
     */
    public XPathValue pop() {
        if (!valueStack.isEmpty()) {
            // Don't restore - post-descent rules may have updated currentValue
            return valueStack.pop();
        }
        return currentValue;
    }

    /**
     * Returns the depth of the value stack.
     *
     * @return the stack depth
     */
    public int getDepth() {
        return valueStack.size();
    }

    /**
     * Resets the accumulator state to its initial value.
     * Used when starting a new document.
     *
     * @param initialValue the initial value
     */
    public void reset(XPathValue initialValue) {
        valueStack.clear();
        currentValue = initialValue;
    }

    @Override
    public String toString() {
        return "AccumulatorState[" + definition.getName() + 
               ", value=" + currentValue + 
               ", depth=" + valueStack.size() + "]";
    }

}
