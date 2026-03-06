/*
 * TemplateRule.java
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

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode.StreamingCapability;
import org.bluezoo.gonzalez.transform.runtime.BufferingStrategy;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A compiled XSLT template rule.
 *
 * <p>Template rules define how source nodes are transformed. Each rule has:
 * <ul>
 *   <li>A match pattern (which nodes it applies to)</li>
 *   <li>Optional name (for xsl:call-template)</li>
 *   <li>Optional mode (for modal processing)</li>
 *   <li>Priority (for conflict resolution)</li>
 *   <li>Template body (the transformation instructions)</li>
 *   <li>Parameters (for xsl:with-param)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TemplateRule {

    private final Pattern matchPattern;
    private final String name;
    private final String mode;
    private final double priority;
    private final int importPrecedence;
    private final int declarationIndex;
    private final List<TemplateParameter> parameters;
    private final XSLTNode body;
    private final String asType;  // XSLT 2.0+ return type declaration
    private SequenceType parsedAsType;  // Pre-parsed from asType with namespace resolution
    private final ComponentVisibility visibility;  // XSLT 3.0 package visibility
    private volatile BufferingStrategy bufferingStrategy;  // set by StreamabilityAnalyzer

    /**
     * Creates a template rule.
     *
     * @param matchPattern the match pattern (null for named-only templates)
     * @param name the template name (null for match-only templates)
     * @param mode the mode (null for default mode)
     * @param priority the priority
     * @param importPrecedence the import precedence
     * @param parameters the template parameters
     * @param body the template body
     */
    public TemplateRule(Pattern matchPattern, String name, String mode,
                        double priority, int importPrecedence,
                        List<TemplateParameter> parameters, XSLTNode body) {
        this(matchPattern, name, mode, priority, importPrecedence, 0, parameters, body, null);
    }

    /**
     * Creates a template rule with declaration index.
     *
     * @param matchPattern the match pattern (null for named-only templates)
     * @param name the template name (null for match-only templates)
     * @param mode the mode (null for default mode)
     * @param priority the priority
     * @param importPrecedence the import precedence
     * @param declarationIndex the declaration order index (later = higher)
     * @param parameters the template parameters
     * @param body the template body
     */
    public TemplateRule(Pattern matchPattern, String name, String mode,
                        double priority, int importPrecedence, int declarationIndex,
                        List<TemplateParameter> parameters, XSLTNode body) {
        this(matchPattern, name, mode, priority, importPrecedence, declarationIndex, parameters, body, null);
    }

    /**
     * Creates a template rule with declaration index and return type.
     *
     * @param matchPattern the match pattern (null for named-only templates)
     * @param name the template name (null for match-only templates)
     * @param mode the mode (null for default mode)
     * @param priority the priority
     * @param importPrecedence the import precedence
     * @param declarationIndex the declaration order index (later = higher)
     * @param parameters the template parameters
     * @param body the template body
     * @param asType the return type declaration (XSLT 2.0+), or null
     */
    public TemplateRule(Pattern matchPattern, String name, String mode,
                        double priority, int importPrecedence, int declarationIndex,
                        List<TemplateParameter> parameters, XSLTNode body, String asType) {
        this(matchPattern, name, mode, priority, importPrecedence, declarationIndex,
             parameters, body, asType, ComponentVisibility.PUBLIC);
    }

    /**
     * Creates a template rule with all options including visibility.
     *
     * @param matchPattern the match pattern (null for named-only templates)
     * @param name the template name (null for match-only templates)
     * @param mode the mode (null for default mode)
     * @param priority the priority
     * @param importPrecedence the import precedence
     * @param declarationIndex the declaration order index (later = higher)
     * @param parameters the template parameters
     * @param body the template body
     * @param asType the return type declaration (XSLT 2.0+), or null
     * @param visibility the package visibility (XSLT 3.0)
     */
    public TemplateRule(Pattern matchPattern, String name, String mode,
                        double priority, int importPrecedence, int declarationIndex,
                        List<TemplateParameter> parameters, XSLTNode body, String asType,
                        ComponentVisibility visibility) {
        this.matchPattern = matchPattern;
        this.name = name;
        this.mode = mode;
        this.priority = priority;
        this.importPrecedence = importPrecedence;
        this.declarationIndex = declarationIndex;
        this.parameters = parameters != null ? 
            Collections.unmodifiableList(new ArrayList<>(parameters)) : 
            Collections.emptyList();
        this.body = body != null ? body : SequenceNode.EMPTY;
        this.asType = asType;
        this.visibility = visibility != null ? visibility : ComponentVisibility.PUBLIC;
    }

    /**
     * Returns the match pattern.
     *
     * @return the pattern, or null for named-only templates
     */
    public Pattern getMatchPattern() {
        return matchPattern;
    }

    /**
     * Returns the template name.
     *
     * @return the name, or null for match-only templates
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the mode.
     *
     * @return the mode, or null for default mode
     */
    public String getMode() {
        return mode;
    }

    /**
     * Returns the priority.
     *
     * @return the priority
     */
    public double getPriority() {
        return priority;
    }

    /**
     * Returns the import precedence.
     *
     * @return the import precedence
     */
    public int getImportPrecedence() {
        return importPrecedence;
    }

    /**
     * Returns the declaration index (order in which templates were declared).
     * Higher index means later in the stylesheet.
     *
     * @return the declaration index
     */
    public int getDeclarationIndex() {
        return declarationIndex;
    }

    /**
     * Returns the parameters.
     *
     * @return the parameters (immutable)
     */
    public List<TemplateParameter> getParameters() {
        return parameters;
    }

    /**
     * Returns the template body.
     *
     * @return the body
     */
    public XSLTNode getBody() {
        return body;
    }

    /**
     * Returns the declared return type (XSLT 2.0+).
     *
     * @return the return type string (e.g., "xs:boolean"), or null if not declared
     */
    public String getAsType() {
        return asType;
    }

    /**
     * Returns the pre-parsed SequenceType for the return type declaration.
     * This is resolved at compile time with namespace bindings available.
     *
     * @return the parsed SequenceType, or null if not declared or not parseable
     */
    public SequenceType getParsedAsType() {
        return parsedAsType;
    }

    /**
     * Sets the pre-parsed SequenceType (called at compile time).
     *
     * @param parsedAsType the parsed SequenceType
     */
    public void setParsedAsType(SequenceType parsedAsType) {
        this.parsedAsType = parsedAsType;
    }

    /**
     * Returns the package visibility (XSLT 3.0).
     *
     * @return the visibility, never null (defaults to PUBLIC)
     */
    public ComponentVisibility getVisibility() {
        return visibility;
    }

    /**
     * Creates a copy of this template rule with a different visibility.
     *
     * @param newVisibility the new visibility
     * @return a new TemplateRule with the specified visibility
     */
    public TemplateRule withVisibility(ComponentVisibility newVisibility) {
        TemplateRule copy = new TemplateRule(matchPattern, name, mode, priority, importPrecedence,
                               declarationIndex, parameters, body, asType, newVisibility);
        copy.parsedAsType = this.parsedAsType;
        return copy;
    }

    /**
     * Returns the streaming capability.
     *
     * @return the capability
     */
    public StreamingCapability getStreamingCapability() {
        return body.getStreamingCapability();
    }

    /**
     * Returns the buffering strategy determined by the streaming classifier.
     * Returns {@link BufferingStrategy#NONE} if not yet analyzed.
     *
     * @return the buffering strategy
     */
    public BufferingStrategy getBufferingStrategy() {
        BufferingStrategy bs = bufferingStrategy;
        return bs != null ? bs : BufferingStrategy.NONE;
    }

    /**
     * Sets the buffering strategy (called by StreamabilityAnalyzer during
     * stylesheet compilation).
     *
     * @param strategy the computed buffering strategy
     */
    public void setBufferingStrategy(BufferingStrategy strategy) {
        this.bufferingStrategy = strategy;
    }

    /**
     * Returns true if this is a match template.
     *
     * @return true if has match pattern
     */
    public boolean isMatchTemplate() {
        return matchPattern != null;
    }

    /**
     * Returns true if this is a named template.
     *
     * @return true if has name
     */
    public boolean isNamedTemplate() {
        return name != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TemplateRule[");
        if (name != null) {
            sb.append("name=");
            sb.append(name);
        }
        if (matchPattern != null) {
            if (name != null) {
                sb.append(", ");
            }
            sb.append("match=");
            sb.append(matchPattern);
        }
        if (mode != null) {
            sb.append(", mode=");
            sb.append(mode);
        }
        sb.append("]");
        return sb.toString();
    }

}
