/*
 * AccumulatorDefinition.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * XSLT 3.0 accumulator definition.
 *
 * <p>An accumulator maintains state during streaming transformation. It consists of:
 * <ul>
 *   <li>A name for referencing the accumulator</li>
 *   <li>An initial value expression</li>
 *   <li>A set of rules that fire on matching nodes</li>
 * </ul>
 *
 * <p>Accumulator rules have a phase:
 * <ul>
 *   <li><b>PRE_DESCENT</b> - fires when entering a matching node (startElement)</li>
 *   <li><b>POST_DESCENT</b> - fires when leaving a matching node (endElement)</li>
 * </ul>
 *
 * <p>The accumulator value at any point can be retrieved using:
 * <ul>
 *   <li>accumulator-before() - value before processing current node</li>
 *   <li>accumulator-after() - value after processing current node's descendants</li>
 * </ul>
 *
 * <p>Accumulators are a key mechanism for maintaining state in streaming
 * transformations without requiring document buffering.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AccumulatorDefinition {

    /**
     * Phase when accumulator rules fire.
     */
    public enum Phase {
        /** Fire when entering a node (on startElement). */
        PRE_DESCENT,
        /** Fire when leaving a node (on endElement). */
        POST_DESCENT
    }

    /**
     * A single accumulator rule.
     */
    public static final class AccumulatorRule {
        private final Pattern matchPattern;
        private final Phase phase;
        private final XPathExpression newValue;
        private final XSLTNode body;

        /**
         * Creates a new accumulator rule with a select expression.
         *
         * @param matchPattern pattern determining which nodes trigger this rule
         * @param phase when the rule fires (pre-descent or post-descent)
         * @param newValue expression computing the new accumulator value
         */
        public AccumulatorRule(Pattern matchPattern, Phase phase, XPathExpression newValue) {
            this.matchPattern = matchPattern;
            this.phase = phase;
            this.newValue = newValue;
            this.body = null;
        }

        /**
         * Creates a new accumulator rule with a sequence constructor body.
         *
         * @param matchPattern pattern determining which nodes trigger this rule
         * @param phase when the rule fires (pre-descent or post-descent)
         * @param newValue optional select expression (null if body is used)
         * @param body the sequence constructor body
         */
        public AccumulatorRule(Pattern matchPattern, Phase phase,
                               XPathExpression newValue, XSLTNode body) {
            this.matchPattern = matchPattern;
            this.phase = phase;
            this.newValue = newValue;
            this.body = body;
        }

        /**
         * Returns the match pattern.
         *
         * @return the pattern
         */
        public Pattern getMatchPattern() {
            return matchPattern;
        }

        /**
         * Returns the phase when this rule fires.
         *
         * @return the phase
         */
        public Phase getPhase() {
            return phase;
        }

        /**
         * Returns the expression computing the new value.
         *
         * @return the new value expression, or null if body is used
         */
        public XPathExpression getNewValue() {
            return newValue;
        }

        /**
         * Returns the sequence constructor body, if any.
         *
         * @return the body, or null if select expression is used
         */
        public XSLTNode getBody() {
            return body;
        }

        @Override
        public String toString() {
            return "AccumulatorRule[match=" + matchPattern + ", phase=" + phase + "]";
        }
    }

    private final String name;
    private final String expandedName;
    private final XPathExpression initialValue;
    private final List<AccumulatorRule> rules;
    private final boolean streamable;
    private final String asType;
    private final int importPrecedence;

    /**
     * Creates a new accumulator definition.
     *
     * @param name the accumulator name
     * @param initialValue expression for the initial value
     * @param rules the accumulator rules
     * @param streamable whether the accumulator is streamable
     * @param asType optional type declaration
     */
    public AccumulatorDefinition(String name, XPathExpression initialValue,
                                  List<AccumulatorRule> rules, boolean streamable,
                                  String asType) {
        this(name, null, initialValue, rules, streamable, asType, -1);
    }

    public AccumulatorDefinition(String name, XPathExpression initialValue,
                                  List<AccumulatorRule> rules, boolean streamable,
                                  String asType, int importPrecedence) {
        this(name, null, initialValue, rules, streamable, asType, importPrecedence);
    }

    public AccumulatorDefinition(String name, String expandedName,
                                  XPathExpression initialValue,
                                  List<AccumulatorRule> rules, boolean streamable,
                                  String asType, int importPrecedence) {
        this.name = name;
        this.expandedName = (expandedName != null) ? expandedName : name;
        this.initialValue = initialValue;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.streamable = streamable;
        this.importPrecedence = importPrecedence;
        this.asType = asType;
    }

    /**
     * Returns the accumulator name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the namespace-expanded name for duplicate detection.
     */
    public String getExpandedName() {
        return expandedName;
    }

    public int getImportPrecedence() {
        return importPrecedence;
    }

    /**
     * Returns the initial value expression.
     *
     * @return the initial value expression
     */
    public XPathExpression getInitialValue() {
        return initialValue;
    }

    /**
     * Returns the accumulator rules.
     *
     * @return unmodifiable list of rules
     */
    public List<AccumulatorRule> getRules() {
        return rules;
    }

    /**
     * Returns true if this accumulator is declared as streamable.
     * In XSLT 3.0, streamable="yes" is the default for accumulators.
     *
     * @return true if streamable
     */
    public boolean isStreamable() {
        return streamable;
    }

    /**
     * Returns the declared type (as attribute), if any.
     * Without schema support, this is informational only.
     *
     * @return the type declaration, or null
     */
    public String getAsType() {
        return asType;
    }

    /**
     * Returns rules that fire in the pre-descent phase.
     *
     * @return pre-descent rules
     */
    public List<AccumulatorRule> getPreDescentRules() {
        List<AccumulatorRule> result = new ArrayList<>();
        for (AccumulatorRule rule : rules) {
            if (rule.getPhase() == Phase.PRE_DESCENT) {
                result.add(rule);
            }
        }
        return result;
    }

    /**
     * Returns rules that fire in the post-descent phase.
     *
     * @return post-descent rules
     */
    public List<AccumulatorRule> getPostDescentRules() {
        List<AccumulatorRule> result = new ArrayList<>();
        for (AccumulatorRule rule : rules) {
            if (rule.getPhase() == Phase.POST_DESCENT) {
                result.add(rule);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "AccumulatorDefinition[name=" + name + ", rules=" + rules.size() + "]";
    }

    /**
     * Builder for constructing AccumulatorDefinition instances.
     *
     * <p>Provides a fluent API for creating accumulator definitions.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public static class Builder {
        private String name;
        private String expandedName;
        private XPathExpression initialValue;
        private final List<AccumulatorRule> rules = new ArrayList<>();
        private boolean streamable = true;
        private String asType;
        private int importPrecedence = -1;

        /**
         * Sets the accumulator name.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the namespace-expanded name for duplicate detection.
         *
         * @param expandedName the expanded name in Clark notation
         * @return this builder
         */
        public Builder expandedName(String expandedName) {
            this.expandedName = expandedName;
            return this;
        }

        /**
         * Sets the initial value expression.
         *
         * @param initialValue the initial value expression
         * @return this builder
         */
        public Builder initialValue(XPathExpression initialValue) {
            this.initialValue = initialValue;
            return this;
        }

        /**
         * Adds an accumulator rule.
         *
         * @param rule the rule
         * @return this builder
         */
        public Builder addRule(AccumulatorRule rule) {
            this.rules.add(rule);
            return this;
        }

        /**
         * Adds an accumulator rule with the given parameters.
         *
         * @param match the match pattern
         * @param phase the phase when the rule fires
         * @param newValue the expression computing the new value
         * @return this builder
         */
        public Builder addRule(Pattern match, Phase phase, XPathExpression newValue) {
            this.rules.add(new AccumulatorRule(match, phase, newValue));
            return this;
        }

        /**
         * Sets whether the accumulator is streamable.
         *
         * @param streamable true if streamable
         * @return this builder
         */
        public Builder streamable(boolean streamable) {
            this.streamable = streamable;
            return this;
        }

        /**
         * Sets the type declaration.
         *
         * @param asType the type string
         * @return this builder
         */
        public Builder asType(String asType) {
            this.asType = asType;
            return this;
        }

        public Builder importPrecedence(int importPrecedence) {
            this.importPrecedence = importPrecedence;
            return this;
        }

        /**
         * Builds the AccumulatorDefinition.
         *
         * @return the accumulator definition
         * @throws IllegalStateException if name or initialValue is missing
         */
        public AccumulatorDefinition build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalStateException("Accumulator name is required");
            }
            if (initialValue == null) {
                throw new IllegalStateException("Initial value is required");
            }
            return new AccumulatorDefinition(name, expandedName, initialValue, rules,
                streamable, asType, importPrecedence);
        }
    }

}
