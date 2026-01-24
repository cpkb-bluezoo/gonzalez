/*
 * TemplateMatcher.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

/**
 * Matches nodes to template rules.
 *
 * <p>Template matching follows XSLT 1.0 conflict resolution rules:
 * <ol>
 *   <li>Higher import precedence wins</li>
 *   <li>Higher priority wins</li>
 *   <li>If still tied, it's an error (but we use last-declared)</li>
 * </ol>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TemplateMatcher {

    private final CompiledStylesheet stylesheet;
    private final Map<String, List<TemplateRule>> rulesByMode;

    /**
     * Creates a template matcher for the given stylesheet.
     *
     * @param stylesheet the compiled stylesheet
     */
    public TemplateMatcher(CompiledStylesheet stylesheet) {
        this.stylesheet = stylesheet;
        this.rulesByMode = indexRulesByMode();
    }

    private Map<String, List<TemplateRule>> indexRulesByMode() {
        Map<String, List<TemplateRule>> map = new HashMap<>();
        
        for (TemplateRule rule : stylesheet.getTemplateRules()) {
            if (rule.getMatchPattern() != null) {
                String mode = rule.getMode() != null ? rule.getMode() : "";
                List<TemplateRule> modeRules = map.get(mode);
                if (modeRules == null) {
                    modeRules = new ArrayList<>();
                    map.put(mode, modeRules);
                }
                modeRules.add(rule);
            }
        }
        
        // Sort each mode's rules by precedence (descending) then priority (descending)
        for (List<TemplateRule> rules : map.values()) {
            Collections.sort(rules, TEMPLATE_PRECEDENCE_COMPARATOR);
        }
        
        return map;
    }
    
    /**
     * Comparator for template rules: higher import precedence wins,
     * then higher priority wins.
     */
    private static final Comparator<TemplateRule> TEMPLATE_PRECEDENCE_COMPARATOR = 
        new Comparator<TemplateRule>() {
            @Override
            public int compare(TemplateRule a, TemplateRule b) {
                // First by import precedence (higher wins)
                int precDiff = b.getImportPrecedence() - a.getImportPrecedence();
                if (precDiff != 0) {
                    return precDiff;
                }
                // Then by priority (higher wins)
                return Double.compare(b.getPriority(), a.getPriority());
            }
        };

    /**
     * Finds the best matching template for a node.
     *
     * @param node the node to match
     * @param mode the current mode (null for default)
     * @param context the transformation context
     * @return the matching rule, or null if no match
     */
    public TemplateRule findMatch(XPathNode node, String mode, TransformContext context) {
        String modeKey = mode != null ? mode : "";
        List<TemplateRule> candidates = rulesByMode.get(modeKey);
        
        if (candidates == null || candidates.isEmpty()) {
            // Fall back to built-in rules
            return getBuiltInRule(node, mode);
        }
        
        // Rules are already sorted by precedence/priority
        for (TemplateRule rule : candidates) {
            if (rule.getMatchPattern().matches(node, context)) {
                return rule;
            }
        }
        
        // No match - use built-in
        return getBuiltInRule(node, mode);
    }

    /**
     * Returns the built-in template rule for a node.
     *
     * <p>Built-in rules are:
     * <ul>
     *   <li>For root and elements: apply-templates to children</li>
     *   <li>For text and attributes: copy the string value</li>
     *   <li>For comments and PIs: do nothing</li>
     * </ul>
     *
     * @param node the node
     * @param mode the current mode
     * @return the built-in rule
     */
    private TemplateRule getBuiltInRule(XPathNode node, String mode) {
        // Built-in rules apply regardless of mode
        if (node.isElement() || node.getNodeType() == NodeType.ROOT) {
            return BUILTIN_ELEMENT_RULE;
        }
        if (node.isText() || node.isAttribute()) {
            return BUILTIN_TEXT_RULE;
        }
        // Comments and PIs - do nothing
        return BUILTIN_EMPTY_RULE;
    }

    // Built-in template rules
    private static final TemplateRule BUILTIN_ELEMENT_RULE = createBuiltInRule("element-or-root");
    private static final TemplateRule BUILTIN_TEXT_RULE = createBuiltInRule("text-or-attribute");
    private static final TemplateRule BUILTIN_EMPTY_RULE = createBuiltInRule("empty");

    private static TemplateRule createBuiltInRule(String type) {
        // Built-in rules are created with placeholder bodies
        // The actual behavior is implemented in the transformer
        return new TemplateRule(null, "__builtin__" + type, null, 
            Double.NEGATIVE_INFINITY, -1, Collections.emptyList(), 
            SequenceNode.EMPTY);
    }

    /**
     * Returns true if the given rule is a built-in rule.
     *
     * @param rule the rule to check
     * @return true if built-in
     */
    public static boolean isBuiltIn(TemplateRule rule) {
        return rule.getName() != null && rule.getName().startsWith("__builtin__");
    }

    /**
     * Returns the type of built-in rule.
     *
     * @param rule the built-in rule
     * @return the type, or null if not built-in
     */
    public static String getBuiltInType(TemplateRule rule) {
        if (!isBuiltIn(rule)) return null;
        return rule.getName().substring("__builtin__".length());
    }

}
