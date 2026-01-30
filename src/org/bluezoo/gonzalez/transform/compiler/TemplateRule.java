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
import org.bluezoo.gonzalez.transform.ast.XSLTNode.StreamingCapability;

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
    private final SequenceNode body;

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
                        List<TemplateParameter> parameters, SequenceNode body) {
        this(matchPattern, name, mode, priority, importPrecedence, 0, parameters, body);
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
                        List<TemplateParameter> parameters, SequenceNode body) {
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
    public SequenceNode getBody() {
        return body;
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
            sb.append("name=").append(name);
        }
        if (matchPattern != null) {
            if (name != null) {
                sb.append(", ");
            }
            sb.append("match=").append(matchPattern);
        }
        if (mode != null) {
            sb.append(", mode=").append(mode);
        }
        sb.append("]");
        return sb.toString();
    }

}
