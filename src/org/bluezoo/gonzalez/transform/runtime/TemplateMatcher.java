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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.ModeDeclaration;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.compiler.SimpleAttrEquality;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

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
 * <p>Rules are indexed by mode, then by matchable node type and local name so
 * that only plausible candidates are considered for each node.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class TemplateMatcher {

    private final CompiledStylesheet stylesheet;
    private final Map<String, ModeIndex> rulesByMode;
    private final List<TemplateRule> allModeRules;

    /**
     * Creates a template matcher for the given stylesheet.
     *
     * @param stylesheet the compiled stylesheet
     */
    public TemplateMatcher(CompiledStylesheet stylesheet) {
        this.stylesheet = stylesheet;
        this.allModeRules = new ArrayList<>();
        if (stylesheet != null) {
            this.rulesByMode = indexRulesByMode();
        } else {
            this.rulesByMode = new HashMap<>();
        }
    }

    private Map<String, ModeIndex> indexRulesByMode() {
        Map<String, ModeIndex> map = new HashMap<>();

        Set<String> allModeKeys = new HashSet<>();
        allModeKeys.add("");

        for (String declaredMode : stylesheet.getModeDeclarations().keySet()) {
            allModeKeys.add(normalizeModeKey(declaredMode));
        }

        for (TemplateRule rule : stylesheet.getTemplateRules()) {
            if (rule.getMatchPattern() != null) {
                String mode = rule.getMode();
                if ("#all".equals(mode)) {
                    allModeRules.add(rule);
                } else {
                    String key = normalizeModeKey(mode);
                    allModeKeys.add(key);
                    ModeIndex index = map.get(key);
                    if (index == null) {
                        index = new ModeIndex();
                        map.put(key, index);
                    }
                    index.add(rule);
                }
            }
        }

        if (!allModeRules.isEmpty()) {
            Collections.sort(allModeRules, TEMPLATE_PRECEDENCE_COMPARATOR);
            for (String key : allModeKeys) {
                ModeIndex index = map.get(key);
                if (index == null) {
                    index = new ModeIndex();
                    map.put(key, index);
                }
                for (int i = 0; i < allModeRules.size(); i++) {
                    index.add(allModeRules.get(i));
                }
            }
        }

        for (ModeIndex index : map.values()) {
            index.seal();
        }

        return map;
    }

    /**
     * Returns the candidate index for a mode, creating one on the fly for
     * previously-unseen modes that have #all rules.
     */
    private ModeIndex getModeIndex(String modeKey) {
        ModeIndex index = rulesByMode.get(modeKey);
        if (index != null) {
            return index;
        }
        if (!allModeRules.isEmpty()) {
            ModeIndex newIndex = new ModeIndex();
            for (int i = 0; i < allModeRules.size(); i++) {
                newIndex.add(allModeRules.get(i));
            }
            newIndex.seal();
            rulesByMode.put(modeKey, newIndex);
            return newIndex;
        }
        return null;
    }

    /**
     * Comparator for template rules: higher import precedence wins,
     * then higher priority wins. When tied, later-declared wins (higher index).
     */
    private static final Comparator<TemplateRule> TEMPLATE_PRECEDENCE_COMPARATOR =
        new Comparator<TemplateRule>() {
            @Override
            public int compare(TemplateRule a, TemplateRule b) {
                int precDiff = b.getImportPrecedence() - a.getImportPrecedence();
                if (precDiff != 0) {
                    return precDiff;
                }
                int priDiff = Double.compare(b.getPriority(), a.getPriority());
                if (priDiff != 0) {
                    return priDiff;
                }
                return b.getDeclarationIndex() - a.getDeclarationIndex();
            }
        };

    /**
     * Finds the best matching template for a node.
     *
     * @param node the node to match
     * @param mode the current mode (null for default)
     * @param context the transformation context
     * @return the matching rule, or null if no match
     * @throws RuntimeException if on-multiple-match="fail" and multiple templates match
     */
    public TemplateRule findMatch(XPathNode node, String mode, TransformContext context) {
        ModeIndex index = getModeIndex(normalizeModeKey(mode));

        if (index == null || index.all.isEmpty()) {
            return getBuiltInRule(node, mode);
        }

        ModeDeclaration modeDecl = stylesheet.getModeDeclaration(mode);
        boolean failOnMultiple = modeDecl != null &&
            modeDecl.getOnMultipleMatch() == ModeDeclaration.OnMultipleMatch.FAIL;

        CandidateLists candidates = index.candidatesFor(node);
        TemplateRule firstMatch = null;
        int matchCount = 0;

        CandidateCursor cursor = new CandidateCursor(candidates);
        while (cursor.hasNext()) {
            TemplateRule rule = cursor.next();
            if (rule.getMatchPattern().matches(node, context)) {
                if (firstMatch == null) {
                    firstMatch = rule;
                    matchCount = 1;
                    if (!failOnMultiple) {
                        return firstMatch;
                    }
                } else if (failOnMultiple) {
                    if (rule.getImportPrecedence() == firstMatch.getImportPrecedence() &&
                        rule.getPriority() == firstMatch.getPriority()) {
                        if (rule.getBody() != firstMatch.getBody()) {
                            matchCount++;
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        if (firstMatch != null) {
            if (failOnMultiple && matchCount > 1) {
                throw new RuntimeException("XTDE0540: Multiple templates match node " +
                    describeNode(node) + " with the same import precedence and priority");
            }
            return firstMatch;
        }

        return getBuiltInRule(node, mode);
    }

    /**
     * Finds the best matching template for an atomic value (XSLT 3.0).
     * Atomic value patterns use the syntax ".[ predicate ]".
     *
     * @param value the atomic value to match
     * @param mode the current mode (null for default)
     * @param context the transformation context
     * @return the matching rule, or null if no match
     * @throws RuntimeException if on-multiple-match="fail" and ambiguous
     */
    public TemplateRule findMatchForAtomicValue(XPathValue value,
                                                 String mode, TransformContext context) {
        ModeIndex index = getModeIndex(normalizeModeKey(mode));

        if (index == null || index.all.isEmpty()) {
            return null;
        }

        ModeDeclaration modeDecl = stylesheet.getModeDeclaration(mode);
        boolean failOnMultiple = modeDecl != null &&
            modeDecl.getOnMultipleMatch() == ModeDeclaration.OnMultipleMatch.FAIL;

        TemplateRule firstMatch = null;
        int matchCount = 0;

        for (TemplateRule rule : index.all) {
            if (rule.getMatchPattern().canMatchAtomicValues() &&
                rule.getMatchPattern().matchesAtomicValue(value, context)) {
                if (firstMatch == null) {
                    firstMatch = rule;
                    matchCount = 1;
                    if (!failOnMultiple) {
                        return firstMatch;
                    }
                } else if (failOnMultiple) {
                    if (rule.getImportPrecedence() == firstMatch.getImportPrecedence() &&
                        rule.getPriority() == firstMatch.getPriority()) {
                        if (rule.getBody() != firstMatch.getBody()) {
                            matchCount++;
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        if (firstMatch != null && failOnMultiple && matchCount > 1) {
            throw new RuntimeException(
                "XTDE0540: Ambiguous rule match for atomic value " +
                "with the same import precedence and priority");
        }

        return firstMatch;
    }

    /**
     * Finds the next matching template for an atomic value after the current rule (XSLT 3.0).
     *
     * @param value the atomic value to match
     * @param mode the current mode (null for default)
     * @param currentRule the currently executing template rule
     * @param context the transformation context
     * @return the next matching rule, or null if no more matches
     */
    public TemplateRule findNextMatchForAtomicValue(
            XPathValue value,
            String mode, TemplateRule currentRule, TransformContext context) {
        ModeIndex index = getModeIndex(normalizeModeKey(mode));

        if (index == null || index.all.isEmpty()) {
            return null;
        }

        boolean foundCurrent = false;
        for (TemplateRule rule : index.all) {
            if (foundCurrent) {
                if (rule.getMatchPattern().canMatchAtomicValues()
                        && rule.getMatchPattern().matchesAtomicValue(value, context)) {
                    return rule;
                }
            } else if (rule == currentRule || isSameRule(rule, currentRule)) {
                foundCurrent = true;
            }
        }

        return null;
    }

    /**
     * Returns a brief description of a node for error messages.
     */
    private static String describeNode(XPathNode node) {
        if (node == null) {
            return "null";
        }
        NodeType type = node.getNodeType();
        if (type == NodeType.ELEMENT) {
            String ns = node.getNamespaceURI();
            String local = node.getLocalName();
            if (ns != null && !ns.isEmpty()) {
                return "{" + ns + "}" + local;
            }
            return local;
        }
        return type.toString().toLowerCase();
    }

    /**
     * Returns the built-in template rule for a node.
     *
     * <p>XSLT 3.0 built-in behavior is determined by xsl:mode/@on-no-match.
     * Default behaviors (XSLT 1.0/2.0):
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
        ModeDeclaration modeDecl = stylesheet.getModeDeclaration(mode);
        if (modeDecl != null) {
            return getBuiltInRuleForMode(node, modeDecl);
        }

        if (node.isElement() || node.getNodeType() == NodeType.ROOT) {
            return BUILTIN_ELEMENT_RULE;
        }
        if (node.isText() || node.isAttribute()) {
            return BUILTIN_TEXT_RULE;
        }
        return BUILTIN_EMPTY_RULE;
    }

    /**
     * Returns the built-in rule based on the mode's on-no-match setting.
     */
    private TemplateRule getBuiltInRuleForMode(XPathNode node, ModeDeclaration modeDecl) {
        ModeDeclaration.OnNoMatch onNoMatch = modeDecl.getOnNoMatch();

        if (node.getNodeType() == NodeType.ROOT) {
            return BUILTIN_SHALLOW_SKIP_RULE;
        }

        if (modeDecl.isTyped() && node.isElement()) {
            return BUILTIN_TYPED_FAIL_RULE;
        }

        switch (onNoMatch) {
            case SHALLOW_COPY:
                return BUILTIN_SHALLOW_COPY_RULE;

            case DEEP_COPY:
                return BUILTIN_DEEP_COPY_RULE;

            case TEXT_ONLY_COPY:
                if (node.isElement()) {
                    return BUILTIN_ELEMENT_RULE;
                }
                if (node.isText() || node.isAttribute()) {
                    return BUILTIN_TEXT_RULE;
                }
                return BUILTIN_EMPTY_RULE;

            case SHALLOW_SKIP:
                return BUILTIN_SHALLOW_SKIP_RULE;

            case DEEP_SKIP:
                return BUILTIN_EMPTY_RULE;

            case FAIL:
                return BUILTIN_FAIL_RULE;

            default:
                if (node.isElement()) {
                    return BUILTIN_ELEMENT_RULE;
                }
                if (node.isText() || node.isAttribute()) {
                    return BUILTIN_TEXT_RULE;
                }
                return BUILTIN_EMPTY_RULE;
        }
    }

    // Built-in template rules
    private static final TemplateRule BUILTIN_ELEMENT_RULE = createBuiltInRule("element-or-root");
    private static final TemplateRule BUILTIN_TEXT_RULE = createBuiltInRule("text-or-attribute");
    private static final TemplateRule BUILTIN_EMPTY_RULE = createBuiltInRule("empty");
    private static final TemplateRule BUILTIN_SHALLOW_COPY_RULE = createBuiltInRule("shallow-copy");
    private static final TemplateRule BUILTIN_DEEP_COPY_RULE = createBuiltInRule("deep-copy");
    private static final TemplateRule BUILTIN_SHALLOW_SKIP_RULE = createBuiltInRule("shallow-skip");
    private static final TemplateRule BUILTIN_FAIL_RULE = createBuiltInRule("fail");
    private static final TemplateRule BUILTIN_TYPED_FAIL_RULE = createBuiltInRule("typed-fail");

    /**
     * Normalizes a mode name to the key used in the rulesByMode map.
     * #unnamed and #default are aliases for the unnamed mode (empty string).
     */
    private static String normalizeModeKey(String mode) {
        if (mode == null || "#default".equals(mode) || "#unnamed".equals(mode)) {
            return "";
        }
        return mode;
    }

    private static TemplateRule createBuiltInRule(String type) {
        return new TemplateRule(null, "__builtin__" + type, null,
            Double.NEGATIVE_INFINITY, -1, Collections.emptyList(),
            SequenceNode.EMPTY);
    }

    /**
     * Returns true if the given rule is a built-in template rule.
     * Built-in rules are used when no explicit template matches.
     *
     * @param rule the rule to check
     * @return true if the rule is a built-in rule
     */
    public static boolean isBuiltIn(TemplateRule rule) {
        return rule.getName() != null && rule.getName().startsWith("__builtin__");
    }

    /**
     * Returns the type of built-in rule.
     * Types include: "element-or-root", "text-or-attribute", "empty",
     * "shallow-copy", "deep-copy", "shallow-skip", "fail".
     *
     * @param rule the built-in rule
     * @return the type string, or null if not a built-in rule
     */
    public static String getBuiltInType(TemplateRule rule) {
        if (!isBuiltIn(rule)) return null;
        return rule.getName().substring("__builtin__".length());
    }

    /**
     * Finds the next matching template after the current one.
     *
     * <p>This is used by xsl:next-match to invoke the next template
     * in precedence/priority order that matches the current node.
     *
     * @param node the node to match
     * @param mode the current mode (null for default)
     * @param currentRule the currently executing template rule
     * @param context the transformation context
     * @return the next matching rule, or null if no more matches
     */
    public TemplateRule findNextMatch(XPathNode node, String mode,
                                       TemplateRule currentRule, TransformContext context) {
        ModeIndex index = getModeIndex(normalizeModeKey(mode));

        if (index == null || index.all.isEmpty()) {
            return null;
        }

        boolean foundCurrent = false;
        CandidateCursor cursor = new CandidateCursor(index.candidatesFor(node));
        while (cursor.hasNext()) {
            TemplateRule rule = cursor.next();
            if (foundCurrent) {
                if (rule.getMatchPattern().matches(node, context)) {
                    return rule;
                }
            } else if (rule == currentRule || isSameRule(rule, currentRule)) {
                foundCurrent = true;
            }
        }

        if (foundCurrent) {
            return getBuiltInRule(node, mode);
        }

        return null;
    }

    /**
     * Checks whether two template rules represent the same template by comparing
     * their match pattern string, priority, import precedence, and declaration index.
     */
    private boolean isSameRule(TemplateRule a, TemplateRule b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getImportPrecedence() != b.getImportPrecedence()) {
            return false;
        }
        if (a.getPriority() != b.getPriority()) {
            return false;
        }
        if (a.getDeclarationIndex() != b.getDeclarationIndex()) {
            return false;
        }
        return true;
    }

    /**
     * Finds the highest-precedence matching template from imported stylesheets.
     *
     * <p>This is used by xsl:apply-imports to invoke template rules from
     * stylesheets that were imported by the stylesheet containing the
     * currently executing template. Only templates with LOWER import precedence
     * than the current template are considered.
     *
     * @param node the node to match
     * @param mode the current mode (null for default)
     * @param currentRule the currently executing template rule
     * @param context the transformation context
     * @return the matching rule from imports, or null if no import matches
     */
    public TemplateRule findImportMatch(XPathNode node, String mode,
                                         TemplateRule currentRule, TransformContext context) {
        if (currentRule == null) {
            return null;
        }

        ModeIndex index = getModeIndex(normalizeModeKey(mode));

        if (index == null || index.all.isEmpty()) {
            return getBuiltInRule(node, mode);
        }

        int currentPrecedence = currentRule.getImportPrecedence();
        int minPrecedence = currentRule.getMinImportPrecedence();

        CandidateCursor cursor = new CandidateCursor(index.candidatesFor(node));
        while (cursor.hasNext()) {
            TemplateRule rule = cursor.next();
            int rulePrec = rule.getImportPrecedence();
            if (rulePrec < currentPrecedence
                    && (minPrecedence < 0 || rulePrec >= minPrecedence)) {
                if (rule.getMatchPattern().matches(node, context)) {
                    return rule;
                }
            }
        }

        return getBuiltInRule(node, mode);
    }

    /**
     * Finds a matching template from imported stylesheets for an atomic value.
     * Used by xsl:apply-imports when the context item is an atomic value.
     */
    public TemplateRule findImportMatchForAtomicValue(XPathValue value,
            String mode, TemplateRule currentRule, TransformContext context) {
        if (currentRule == null) {
            return null;
        }

        ModeIndex index = getModeIndex(normalizeModeKey(mode));

        if (index == null || index.all.isEmpty()) {
            return null;
        }

        int currentPrecedence = currentRule.getImportPrecedence();
        int minPrecedence = currentRule.getMinImportPrecedence();

        for (TemplateRule rule : index.all) {
            int rulePrec = rule.getImportPrecedence();
            if (rulePrec < currentPrecedence
                    && (minPrecedence < 0 || rulePrec >= minPrecedence)) {
                if (rule.getMatchPattern().canMatchAtomicValues()
                        && rule.getMatchPattern().matchesAtomicValue(value, context)) {
                    return rule;
                }
            }
        }

        return null;
    }

    /**
     * Per-mode index of template rules by node type and local name.
     */
    private static final class ModeIndex {
        final List<TemplateRule> all = new ArrayList<>();
        final List<TemplateRule> anyType = new ArrayList<>();
        final Map<NodeType, TypeIndex> byType = new HashMap<>();

        void add(TemplateRule rule) {
            all.add(rule);
            Pattern pattern = rule.getMatchPattern();
            NodeType type = pattern.getMatchableNodeType();
            if (type == null) {
                anyType.add(rule);
                return;
            }
            TypeIndex typeIndex = byType.get(type);
            if (typeIndex == null) {
                typeIndex = new TypeIndex();
                byType.put(type, typeIndex);
            }
            String localName = pattern.getMatchableLocalName();
            if (localName == null) {
                typeIndex.anyName.add(rule);
            } else {
                NameBucket bucket = typeIndex.byLocalName.get(localName);
                if (bucket == null) {
                    bucket = new NameBucket();
                    typeIndex.byLocalName.put(localName, bucket);
                }
                SimpleAttrEquality attrEq = pattern.getSimpleAttrEquality();
                if (attrEq != null) {
                    String key = attrEq.indexKey();
                    List<TemplateRule> keyed = bucket.byAttrEq.get(key);
                    if (keyed == null) {
                        keyed = new ArrayList<>();
                        bucket.byAttrEq.put(key, keyed);
                    }
                    keyed.add(rule);
                } else {
                    bucket.residual.add(rule);
                }
            }
        }

        void seal() {
            Collections.sort(all, TEMPLATE_PRECEDENCE_COMPARATOR);
            Collections.sort(anyType, TEMPLATE_PRECEDENCE_COMPARATOR);
            for (TypeIndex typeIndex : byType.values()) {
                Collections.sort(typeIndex.anyName, TEMPLATE_PRECEDENCE_COMPARATOR);
                for (NameBucket bucket : typeIndex.byLocalName.values()) {
                    Collections.sort(bucket.residual, TEMPLATE_PRECEDENCE_COMPARATOR);
                    for (List<TemplateRule> keyed : bucket.byAttrEq.values()) {
                        Collections.sort(keyed, TEMPLATE_PRECEDENCE_COMPARATOR);
                    }
                }
            }
        }

        CandidateLists candidatesFor(XPathNode node) {
            NodeType nodeType = node.getNodeType();
            TypeIndex typeIndex = byType.get(nodeType);
            List<TemplateRule> attrHits = null;
            List<TemplateRule> residual = Collections.emptyList();
            List<TemplateRule> anyName = Collections.emptyList();
            if (typeIndex != null) {
                anyName = typeIndex.anyName;
                String localName = node.getLocalName();
                if (localName != null) {
                    NameBucket bucket = typeIndex.byLocalName.get(localName);
                    if (bucket != null) {
                        residual = bucket.residual;
                        if (!bucket.byAttrEq.isEmpty()) {
                            attrHits = collectAttrEqualityHits(bucket, node);
                        }
                    }
                }
            }
            return new CandidateLists(attrHits, residual, anyName, anyType);
        }

        private static List<TemplateRule> collectAttrEqualityHits(
                NameBucket bucket, XPathNode node) {
            List<TemplateRule> hits = null;
            Iterator<XPathNode> attrs = node.getAttributes();
            while (attrs.hasNext()) {
                XPathNode attr = attrs.next();
                String key = SimpleAttrEquality.indexKey(
                    attr.getNamespaceURI(), attr.getLocalName(),
                    attr.getStringValue());
                List<TemplateRule> keyed = bucket.byAttrEq.get(key);
                if (keyed != null) {
                    if (hits == null) {
                        hits = new ArrayList<>(keyed.size());
                    }
                    hits.addAll(keyed);
                }
            }
            if (hits != null && hits.size() > 1) {
                Collections.sort(hits, TEMPLATE_PRECEDENCE_COMPARATOR);
            }
            return hits;
        }
    }

    private static final class TypeIndex {
        final Map<String, NameBucket> byLocalName = new HashMap<>();
        final List<TemplateRule> anyName = new ArrayList<>();
    }

    /**
     * Per-local-name rules: simple {@code @attr='v'} templates are keyed;
     * everything else stays in {@code residual}.
     */
    private static final class NameBucket {
        final Map<String, List<TemplateRule>> byAttrEq = new HashMap<>();
        final List<TemplateRule> residual = new ArrayList<>();
    }

    /**
     * Up to four precedence-sorted candidate lists to merge for a node.
     */
    private static final class CandidateLists {
        final List<TemplateRule> attrHits;
        final List<TemplateRule> residual;
        final List<TemplateRule> anyName;
        final List<TemplateRule> anyType;

        CandidateLists(List<TemplateRule> attrHits, List<TemplateRule> residual,
                       List<TemplateRule> anyName, List<TemplateRule> anyType) {
            this.attrHits = attrHits;
            this.residual = residual;
            this.anyName = anyName;
            this.anyType = anyType;
        }
    }

    /**
     * Merges precedence-sorted candidate lists in conflict-resolution order.
     */
    private static final class CandidateCursor {
        private final List<TemplateRule>[] lists;
        private final int[] indexes;

        @SuppressWarnings("unchecked")
        CandidateCursor(CandidateLists candidates) {
            this.lists = new List[] {
                candidates.attrHits,
                candidates.residual,
                candidates.anyName,
                candidates.anyType
            };
            this.indexes = new int[lists.length];
        }

        boolean hasNext() {
            for (int i = 0; i < lists.length; i++) {
                List<TemplateRule> list = lists[i];
                if (list != null && indexes[i] < list.size()) {
                    return true;
                }
            }
            return false;
        }

        TemplateRule next() {
            TemplateRule best = null;
            int bestList = -1;
            for (int i = 0; i < lists.length; i++) {
                List<TemplateRule> list = lists[i];
                if (list != null && indexes[i] < list.size()) {
                    TemplateRule r = list.get(indexes[i]);
                    if (best == null
                            || TEMPLATE_PRECEDENCE_COMPARATOR.compare(r, best) < 0) {
                        best = r;
                        bestList = i;
                    }
                }
            }
            indexes[bestList]++;
            return best;
        }
    }

}
