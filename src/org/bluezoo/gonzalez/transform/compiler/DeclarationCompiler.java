/*
 * DeclarationCompiler.java
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
 * GNU Lesser General Public License for details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.transform.compiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.transform.TransformerConfigurationException;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.schema.xsd.XSDSchema;
import org.bluezoo.gonzalez.schema.xsd.XSDSchemaParser;
import org.bluezoo.gonzalez.transform.ast.AccumulatorRuleNode;
import org.bluezoo.gonzalez.transform.ast.AttributeNode;
import org.bluezoo.gonzalez.transform.ast.CollationScopeNode;
import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.OutputCharacterNode;
import org.bluezoo.gonzalez.transform.ast.ParamNode;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.SortSpecNode;
import org.bluezoo.gonzalez.transform.ast.WithParamNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.StaticTypeContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Extracts declaration processing methods from StylesheetCompiler.
 * All methods are static and take StylesheetCompiler as the first parameter.
 *
 * @author Chris Burdess
 */
final class DeclarationCompiler {

    private DeclarationCompiler() {
    }

    static void processCharacterMap(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String rawName = ctx.attributes.get("name");
        if (rawName == null || rawName.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:character-map requires name attribute");
        }

        // XTSE0080: Check for reserved namespace in character-map name
        if (rawName.contains(":")) {
            int colonIdx = rawName.indexOf(':');
            String prefix = rawName.substring(0, colonIdx);
            String nsUri = ctx.namespaceBindings.get(prefix);
            if (nsUri == null) {
                nsUri = compiler.lookupNamespaceUri(prefix);
            }
            if (nsUri != null && compiler.isReservedNamespace(nsUri)) {
                throw new SAXException("XTSE0080: Reserved namespace '" + nsUri +
                    "' cannot be used in the character-map name '" + rawName + "'");
            }
        }

        String name = compiler.expandQName(rawName.trim());

        // XTSE1580 only applies at the same import precedence.
        // Allow redefinition: last definition wins within same stylesheet module,
        // and mergeNonTemplates handles "first wins" for imports.

        String useCharacterMaps = ctx.attributes.get("use-character-maps");

        CompiledStylesheet.CharacterMap charMap = new CompiledStylesheet.CharacterMap(name);

        // Process use-character-maps references (expand QNames)
        if (useCharacterMaps != null && !useCharacterMaps.isEmpty()) {
            String[] refs = useCharacterMaps.split("\\s+");
            for (String ref : refs) {
                if (!ref.isEmpty()) {
                    String expandedRef = compiler.expandQName(ref.trim());
                    charMap.addUseCharacterMap(expandedRef);
                }
            }
        }

        // Process xsl:output-character children
        for (XSLTNode child : ctx.children) {
            if (child instanceof OutputCharacterNode) {
                OutputCharacterNode ocn = (OutputCharacterNode) child;
                charMap.addMapping(ocn.getCodePoint(), ocn.getString());
            }
        }

        compiler.builder.addCharacterMap(charMap);
    }

    static XSLTNode compileOutputCharacter(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String characterAttr = ctx.attributes.get("character");
        String stringAttr = ctx.attributes.get("string");

        if (characterAttr == null || characterAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:output-character requires character attribute");
        }

        // Handle both BMP characters (length 1) and supplementary characters (length 2 as surrogate pair)
        int codePoint;
        if (characterAttr.length() == 1) {
            codePoint = characterAttr.charAt(0);
        } else if (characterAttr.length() == 2 &&
                   Character.isHighSurrogate(characterAttr.charAt(0)) &&
                   Character.isLowSurrogate(characterAttr.charAt(1))) {
            // Supplementary character represented as surrogate pair
            codePoint = Character.toCodePoint(characterAttr.charAt(0), characterAttr.charAt(1));
        } else {
            throw new SAXException("XTSE0020: xsl:output-character character must be a single character");
        }

        if (stringAttr == null) {
            throw new SAXException("XTSE0010: xsl:output-character requires string attribute");
        }

        return new OutputCharacterNode(codePoint, stringAttr);
    }

    static void processAccumulator(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = compiler.resolveStaticShadowAttribute(ctx, "name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:accumulator requires name attribute");
        }
        compiler.ensurePrecedenceAssigned();


        String initialValueStr = compiler.resolveStaticShadowAttribute(ctx, "initial-value");
        if (initialValueStr == null) {
            throw new SAXException("xsl:accumulator requires initial-value attribute");
        }

        XPathExpression initialValue = compiler.compileExpression(initialValueStr);
        String asType = ctx.attributes.get("as");
        String streamableAttr = compiler.resolveStaticShadowAttribute(ctx, "streamable");
        if (streamableAttr != null && !streamableAttr.isEmpty()) {
            compiler.validateYesOrNo("xsl:accumulator", "streamable", streamableAttr);
        }
        boolean streamable;
        boolean explicitlyStreamable = false;
        if (streamableAttr == null) {
            streamable = true;
        } else {
            streamable = compiler.parseYesOrNo(streamableAttr);
            explicitlyStreamable = streamable;
        }

        String expandedName = compiler.expandQName(name.trim());

        AccumulatorDefinition.Builder accBuilder = new AccumulatorDefinition.Builder()
            .name(name)
            .expandedName(expandedName)
            .initialValue(initialValue)
            .streamable(streamable)
            .asType(asType)
            .importPrecedence(compiler.importPrecedence);

        // Process accumulator-rule children
        int ruleCount = 0;
        for (XSLTNode child : ctx.children) {
            if (child instanceof AccumulatorRuleNode) {
                AccumulatorRuleNode ruleNode = (AccumulatorRuleNode) child;
                accBuilder.addRule(ruleNode.toRule());
                ruleCount++;
            }
        }

        // XTSE0010: accumulator must have at least one accumulator-rule
        if (ruleCount == 0) {
            throw new SAXException("XTSE0010: xsl:accumulator must have at least one xsl:accumulator-rule child");
        }

        // XTSE3430: streamable accumulator rules must be motionless.
        // With streaming fallback (§19.10), fall back to non-streamable.
        if (explicitlyStreamable) {
            try {
                validateAccumulatorStreamability(name, ctx.children);
            } catch (SAXException e) {
                if (compiler.streamingFallback) {
                    accBuilder.streamable(false);
                } else {
                    throw e;
                }
            }
        }

        compiler.builder.addAccumulator(accBuilder.build());
    }

    /**
     * Validates that a streamable accumulator's rules are motionless.
     * Throws SAXException if any rule violates streamability constraints.
     */
    private static void validateAccumulatorStreamability(String name,
            List<XSLTNode> children) throws SAXException {
        for (int i = 0; i < children.size(); i++) {
            XSLTNode child = children.get(i);
            if (child instanceof AccumulatorRuleNode) {
                AccumulatorRuleNode ruleNode = (AccumulatorRuleNode) child;
                Pattern rulePat = ruleNode.getPattern();
                if (rulePat != null
                        && StreamabilityValidator.hasNonMotionlessPredicate(rulePat)) {
                    throw new SAXException(
                        "XTSE3430: Streamable accumulator '" + name +
                        "' has non-motionless rule match pattern '" +
                        rulePat + "'");
                }
                if (!StreamabilityValidator.isLeafNodeMatch(rulePat)) {
                    List<XPathExpression> exprs = ruleNode.getExpressions();
                    if (!exprs.isEmpty()) {
                        XPathExpression selectExpr = exprs.get(0);
                        if (selectExpr != null) {
                            StreamabilityAnalyzer.ExpressionStreamability
                                es = StreamingClassifier.classify(selectExpr);
                            if (es == StreamabilityAnalyzer
                                    .ExpressionStreamability.CONSUMING
                                || es == StreamabilityAnalyzer
                                    .ExpressionStreamability.FREE_RANGING) {
                                throw new SAXException(
                                    "XTSE3430: Streamable accumulator '"
                                    + name +
                                    "' rule select is not motionless");
                            }
                            Expr compiled = selectExpr.getCompiledExpr();
                            if (compiled != null
                                    && StreamabilityValidator
                                        .accRuleSelectCapturesContextNode(
                                            compiled)) {
                                throw new SAXException(
                                    "XTSE3430: Streamable accumulator '"
                                    + name + "' rule select is not "
                                    + "grounded (captures streamed node)");
                            }
                            if (StreamabilityValidator
                                    .accSelectStringCapturesContext(
                                        selectExpr)) {
                                throw new SAXException(
                                    "XTSE3430: Streamable accumulator '"
                                    + name + "' rule select is not "
                                    + "grounded (captures streamed node)");
                            }
                        }
                    }
                }
            }
        }
    }

    static void processModeDeclaration(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:mode must be empty
        compiler.validateEmptyElement(ctx, "xsl:mode");

        String name = ctx.attributes.get("name");
        String streamableAttr = compiler.resolveStaticShadowAttribute(ctx, "streamable");
        String onNoMatchAttr = ctx.attributes.get("on-no-match");
        String onMultipleMatchAttr = ctx.attributes.get("on-multiple-match");
        String visibilityAttr = ctx.attributes.get("visibility");
        String useAccumulators = ctx.attributes.get("use-accumulators");
        String typedAttr = ctx.attributes.get("typed");
        String warningOnNoMatch = ctx.attributes.get("warning-on-no-match");
        String warningOnMultipleMatch = ctx.attributes.get("warning-on-multiple-match");

        // XTSE0020: validate boolean attributes
        compiler.validateYesOrNo("xsl:mode", "streamable", streamableAttr);
        compiler.validateYesOrNo("xsl:mode", "typed", typedAttr);
        compiler.validateYesOrNo("xsl:mode", "warning-on-no-match", warningOnNoMatch);
        compiler.validateYesOrNo("xsl:mode", "warning-on-multiple-match", warningOnMultipleMatch);

        // Expand prefixed name to Clark notation for consistent key matching
        String expandedName = null;
        if (name != null) {
            expandedName = compiler.expandModeQName(name, true);
        }

        // XTSE0020: unnamed mode cannot be public or final
        if (name == null && visibilityAttr != null) {
            String vis = visibilityAttr.trim();
            if ("public".equals(vis) || "final".equals(vis)) {
                throw new SAXException("XTSE0020: The unnamed mode cannot have visibility='" +
                    vis + "'");
            }
        }

        // XTSE0545: detect conflicting mode declarations at same import precedence
        String modeKey = expandedName != null ? expandedName : "#default";
        checkModeConflict(compiler, modeKey, "on-no-match", onNoMatchAttr);
        checkModeConflict(compiler, modeKey, "on-multiple-match", onMultipleMatchAttr);
        checkModeConflict(compiler, modeKey, "visibility", visibilityAttr);
        checkModeConflict(compiler, modeKey, "streamable", streamableAttr);
        if (useAccumulators != null) {
            checkModeConflict(compiler, modeKey, "use-accumulators",
                expandAccumulatorNames(compiler, useAccumulators));
        }

        boolean isStreamable = "yes".equals(streamableAttr)
            || "true".equals(streamableAttr)
            || "1".equals(streamableAttr);
        ModeDeclaration.Builder modeBuilder = new ModeDeclaration.Builder()
            .name(expandedName)
            .onNoMatch(onNoMatchAttr)
            .onMultipleMatch(onMultipleMatchAttr)
            .visibility(visibilityAttr)
            .useAccumulators(useAccumulators)
            .typed("yes".equals(typedAttr))
            .warning("yes".equals(warningOnNoMatch));
        if (streamableAttr != null) {
            modeBuilder.streamable(isStreamable);
        }
        if (useAccumulators != null) {
            modeBuilder.expandedUseAccumulators(expandAccumulatorNames(compiler, useAccumulators));
        }

        compiler.builder.addModeDeclaration(modeBuilder.build());
    }

    static String expandAccumulatorNames(StylesheetCompiler compiler, String names) throws SAXException {
        String trimmed = names.trim();
        if ("#all".equals(trimmed)) {
            return trimmed;
        }
        String[] tokens = trimmed.split("\\s+");
        TreeSet<String> expanded = new TreeSet<String>();
        for (String token : tokens) {
            expanded.add(compiler.expandQName(token));
        }
        StringBuilder sb = new StringBuilder();
        for (String name : expanded) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    static void checkModeConflict(StylesheetCompiler compiler, String modeKey, String attrName,
                                    String attrValue) throws SAXException {
        if (attrValue == null) {
            return;
        }
        String trackKey = modeKey + "|" + attrName;
        String existing = compiler.modeAttributeValues.get(trackKey);
        if (existing != null && !existing.equals(attrValue)) {
            String message = "XTSE0545: Conflicting values for " + attrName +
                " on mode '" + modeKey + "': '" + existing + "' vs '" + attrValue + "'";
            compiler.builder.recordModeConflict(modeKey, attrName, message);
        }
        compiler.modeAttributeValues.put(trackKey, attrValue);
    }

    static void processFunctionElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        if (!compiler.isTopLevel() && !compiler.isInsideOverride()) {
            throw new SAXException("XTSE0010: xsl:function must be a top-level element");
        }
        compiler.importsAllowed = false;
        compiler.ensurePrecedenceAssigned();

        // Check shadow attribute first (_name="{...}"), then regular name
        String name = compiler.resolveStaticShadowAttribute(ctx, "name");
        if (name == null || name.isEmpty()) {
            name = ctx.attributes.get("name");
        }
        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:function requires name attribute");
        }

        // Parse function name - must be in a namespace
        // XTSE0080: Check for reserved namespace
        QName funcName = compiler.parseQName(name, ctx.namespaceBindings, true);
        if (funcName.getURI().isEmpty()) {
            throw new SAXException("xsl:function name must be in a namespace: " + name);
        }
        String namespaceURI = funcName.getURI();
        String localName = funcName.getLocalName();

        String asType = ctx.attributes.get("as"); // Optional return type
        String visibilityAttr = compiler.resolveStaticShadowAttribute(ctx, "visibility");
        String cacheAttr = ctx.attributes.get("cache");
        String overrideExtAttr = ctx.attributes.get("override-extension-function");
        String overrideAttr = ctx.attributes.get("override");
        String newEachTimeAttr = ctx.attributes.get("new-each-time");
        String identitySensitiveAttr = ctx.attributes.get("identity-sensitive");
        compiler.validateYesOrNo("xsl:function", "cache", cacheAttr);
        compiler.validateYesOrNo("xsl:function", "override-extension-function", overrideExtAttr);
        compiler.validateYesOrNo("xsl:function", "override", overrideAttr);
        // new-each-time allows yes/no/true/false/1/0/maybe
        if (newEachTimeAttr != null) {
            String net = newEachTimeAttr.trim();
            if (!"yes".equals(net) && !"no".equals(net) && !"true".equals(net) &&
                    !"false".equals(net) && !"1".equals(net) && !"0".equals(net) &&
                    !"maybe".equals(net)) {
                throw new SAXException("XTSE0020: Invalid value for new-each-time attribute " +
                    "on xsl:function: must be yes, no, maybe, true, false, 1, or 0, got '" +
                    newEachTimeAttr + "'");
            }
        }
        compiler.validateYesOrNo("xsl:function", "identity-sensitive", identitySensitiveAttr);
        if (overrideAttr != null && overrideExtAttr != null) {
            boolean overrideVal = compiler.parseYesOrNo(overrideAttr);
            boolean overrideExtVal = compiler.parseYesOrNo(overrideExtAttr);
            if (overrideVal != overrideExtVal) {
                throw new SAXException("XTSE0020: xsl:function has conflicting values for " +
                    "'override' and 'override-extension-function' attributes");
            }
        }
        boolean cached = "yes".equals(cacheAttr) || "true".equals(cacheAttr);
        ComponentVisibility funcVisibility = ComponentVisibility.PRIVATE;
        if (visibilityAttr != null && !visibilityAttr.isEmpty()) {
            try {
                funcVisibility = ComponentVisibility.valueOf(visibilityAttr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new SAXException("XTSE0020: Invalid visibility value for xsl:function: " +
                    visibilityAttr);
            }
        }

        // Extract parameters from children
        List<UserFunction.FunctionParameter> params = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();

        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                String paramKey = StylesheetCompiler.expandedParamName(pn.getNamespaceURI(), pn.getLocalName());
                if (!seenParamNames.add(paramKey)) {
                    throw new SAXException("XTSE0580: Duplicate parameter name '" + pn.getName() + "' in xsl:function");
                }
                if (pn.getSelectExpr() != null || pn.getContent() != null) {
                    throw new SAXException("XTSE0760: xsl:param in xsl:function must not " +
                        "have a default value (select attribute or content): " + pn.getName());
                }
                String paramAs = pn.getAs(); // Type annotation if any
                params.add(new UserFunction.FunctionParameter(pn.getNamespaceURI(), pn.getLocalName(), paramAs));
            } else if (child instanceof InstructionCompiler.ContextItemDeclaration) {
                throw new SAXException("XTSE0010: xsl:context-item is not allowed " +
                    "in xsl:function");
            } else {
                bodyNodes.add(child);
            }
        }

        SequenceNode body = new SequenceNode(bodyNodes);

        // XSLT 3.0: Validate streamability attribute.
        // On validation failure, fall back to unclassified (§19.10).
        String streamabilityAttr = ctx.attributes.get("streamability");
        String streamability = null;
        if (streamabilityAttr != null && !streamabilityAttr.isEmpty()) {
            streamability = streamabilityAttr.trim();
            if (!"unclassified".equals(streamability)) {
                // XTSE3155: Streamable function must have at least one parameter
                if (params.isEmpty()) {
                    throw new SAXException("XTSE3155: xsl:function with " +
                        "streamability='" + streamability + "' must have " +
                        "at least one parameter");
                }
                if ("shallow-descent".equals(streamability) ||
                        "deep-descent".equals(streamability)) {
                    boolean isDeepDescent = "deep-descent".equals(streamability);
                    try {
                        StreamabilityValidator.validateDescentFunction(params, bodyNodes, name, isDeepDescent);
                    } catch (SAXException e) {
                        if (compiler.streamingFallback) {
                            streamability = null;
                        } else {
                            throw e;
                        }
                    }
                }
                if ("absorbing".equals(streamability)) {
                    try {
                        StreamabilityValidator.validateAbsorbingFunction(params, bodyNodes, name);
                    } catch (SAXException e) {
                        if (compiler.streamingFallback) {
                            streamability = null;
                        } else {
                            throw e;
                        }
                    }
                }
                if (streamability != null &&
                        ("ascent".equals(streamability) ||
                        "inspection".equals(streamability) ||
                        "filter".equals(streamability))) {
                    try {
                        StreamabilityValidator.validateMotionlessFunction(params, bodyNodes,
                            name, streamability);
                    } catch (SAXException e) {
                        if (compiler.streamingFallback) {
                            streamability = null;
                        } else {
                            throw e;
                        }
                    }
                }
            }
        }

        // Ascent/inspection functions must not return the streaming
        // parameter directly. This check runs outside the fallback
        // try-catch because the user explicitly declared the function's
        // streamability category.
        String sAttr = (streamabilityAttr != null)
            ? streamabilityAttr.trim() : null;
        if (("ascent".equals(sAttr) || "inspection".equals(sAttr))
                && !params.isEmpty()) {
            StreamabilityValidator.validateFunctionResultGrounded(
                params, bodyNodes, name, sAttr);
        }

        UserFunction function = new UserFunction(
            namespaceURI, localName, params, body, asType, compiler.importPrecedence, cached, funcVisibility);
        if (streamability != null) {
            function.setStreamability(streamability);
        }
        try {
            compiler.builder.addUserFunction(function);
        } catch (javax.xml.transform.TransformerConfigurationException e) {
            throw new SAXException(e.getMessage(), e);
        }

        if (compiler.isInsideOverride()) {
            if (compiler.pendingOverrideDeclarations == null) {
                compiler.pendingOverrideDeclarations = new ArrayList<OverrideDeclaration>();
            }
            compiler.pendingOverrideDeclarations.add(
                OverrideDeclaration.forFunction(function));
        }
    }

    static XSLTNode compileAccumulatorRule(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String matchStr = ctx.attributes.get("match");
        if (matchStr == null || matchStr.isEmpty()) {
            throw new SAXException("xsl:accumulator-rule requires match attribute");
        }

        String selectStr = ctx.attributes.get("select");
        String phaseStr = ctx.attributes.get("phase");

        // Default phase is pre-descent (start) per XSLT 3.0 spec
        AccumulatorDefinition.Phase phase = AccumulatorDefinition.Phase.PRE_DESCENT;
        if ("end".equals(phaseStr)) {
            phase = AccumulatorDefinition.Phase.POST_DESCENT;
        }

        Pattern pattern = compiler.compilePattern(matchStr);
        XPathExpression newValue = selectStr != null ? compiler.compileExpression(selectStr) : null;

        if (newValue == null && !ctx.children.isEmpty()) {
            XSLTNode body = new SequenceNode(ctx.children);
            return new AccumulatorRuleNode(pattern, phase, body);
        }

        if (newValue == null) {
            throw new SAXException("xsl:accumulator-rule requires select attribute or content");
        }

        return new AccumulatorRuleNode(pattern, phase, newValue);
    }

    static XSLTNode compileLiteralResultElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // Use the original prefix from the stylesheet source
        // This preserves the author's intent (unprefixed vs prefixed)
        String prefix = ctx.originalPrefix;
        String localName = ctx.localName;

        // Extract xsl:use-attribute-sets before processing other attributes
        String useAttrSetsValue = ctx.attributes.get("xsl:use-attribute-sets");
        List<String> useAttributeSets = new ArrayList<>();
        if (useAttrSetsValue != null) {
            for (String setName : compiler.splitOnWhitespace(useAttrSetsValue)) {
                // Expand attribute set name using namespace bindings
                String expandedName = compiler.expandAttributeSetName(setName.trim(), ctx.namespaceBindings);
                useAttributeSets.add(expandedName);
                // Register for validation (XTSE0710)
                compiler.builder.registerAttributeSetReferences(expandedName);
            }
        }

        // Extract xsl:exclude-result-prefixes for this element
        String excludePrefixesValue = ctx.attributes.get("xsl:exclude-result-prefixes");
        Set<String> localExcludedURIs = new HashSet<>(compiler.excludedNamespaceURIs);
        // Extension namespaces are automatically excluded
        localExcludedURIs.addAll(compiler.extensionNamespaceURIs);

        if (excludePrefixesValue != null && !excludePrefixesValue.isEmpty()) {
            String[] prefixes = excludePrefixesValue.trim().split("\\s+");
            for (String p : prefixes) {
                if (p.isEmpty()) {
                    continue;
                }
                if ("#all".equals(p)) {
                    for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
                        if (!StylesheetCompiler.XSLT_NS.equals(ns.getValue())) {
                            localExcludedURIs.add(ns.getValue());
                        }
                    }
                } else if ("#default".equals(p)) {
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null) {
                        localExcludedURIs.add(defaultNs);
                    }
                } else {
                    String uri = ctx.namespaceBindings.get(p);
                    if (uri != null) {
                        localExcludedURIs.add(uri);
                    }
                }
            }
        }

        // Handle xsl:extension-element-prefixes on LRE (local extension declaration)
        String extensionPrefixesValue = ctx.attributes.get("xsl:extension-element-prefixes");
        if (extensionPrefixesValue != null && !extensionPrefixesValue.isEmpty()) {
            String[] prefixes = extensionPrefixesValue.split("\\s+");
            for (String p : prefixes) {
                if ("#default".equals(p)) {
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null) {
                        localExcludedURIs.add(defaultNs);
                    }
                } else {
                    String uri = ctx.namespaceBindings.get(p);
                    if (uri != null) {
                        localExcludedURIs.add(uri);
                    }
                }
            }
        }

        // XTSE1660: Non-schema-aware processor must reject any xsl:type attribute
        String typeValue = ctx.attributes.get("xsl:type");
        if (typeValue != null && !typeValue.isEmpty()) {
            throw new SAXException("XTSE1660: xsl:type='" + typeValue +
                "' requires a schema-aware processor");
        }

        // XTSE1660: Non-schema-aware processor must reject certain xsl:validation values
        String validationValue = ctx.attributes.get("xsl:validation");
        if (validationValue != null && !validationValue.isEmpty()) {
            compiler.parseValidationMode(validationValue.trim(), "literal result element");
        }

        // Compile attributes as AVTs
        // Check if we're in backward-compat mode (version 1.0)
        double lreEffectiveVer = ctx.effectiveVersion > 0 ? ctx.effectiveVersion : compiler.getEffectiveVersion();
        boolean lreBackwardsCompat = lreEffectiveVer < 2.0;
        Map<String, AttributeValueTemplate> avts = new LinkedHashMap<>();
        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String attrName = attr.getKey();
            String value = attr.getValue();

            // Validate and skip xsl: attributes on literal result elements
            if (attrName.startsWith("xsl:")) {
                String xslAttr = attrName.substring(4);
                if (!StylesheetCompiler.isKnownLREAttribute(xslAttr)) {
                    if (compiler.isElementForwardCompatible(ctx)) {
                        continue;
                    }
                    throw new SAXException("XTSE0805: Unknown XSLT attribute '" + attrName +
                        "' on literal result element");
                }
                continue;
            }

            try {
                StaticTypeContext typeCtx = compiler.buildStaticTypeContext(lreEffectiveVer);
                AttributeValueTemplate avt = AttributeValueTemplate.parse(value, compiler, typeCtx);
                if (lreBackwardsCompat) {
                    avt.setBackwardsCompatible(true);
                }
                avts.put(attrName, avt);
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in attribute " + attrName + ": " + e.getMessage(), e);
            }
        }

        // Build content - keep xsl:on-empty and xsl:on-non-empty in place
        // SequenceNode now handles them with proper two-phase execution
        SequenceNode content = new SequenceNode(ctx.children);

        // Per XSLT 1.0 section 7.1.1: Copy namespace nodes except XSLT namespace.
        // Also exclude namespaces listed in exclude-result-prefixes (both global and local).
        // BUT: Can't exclude a namespace that's actually used by the element or its attributes.
        // Output ALL in-scope namespaces - SAXOutputHandler will deduplicate inherited ones.
        // Namespace aliasing is applied at runtime by LiteralResultElement.

        // Collect attribute prefixes (these must always have namespace declarations)
        Set<String> attributePrefixes = new HashSet<>();
        for (String attrName : ctx.attributes.keySet()) {
            if (attrName.startsWith("xsl:")) continue;
            int colon = attrName.indexOf(':');
            if (colon > 0) {
                attributePrefixes.add(attrName.substring(0, colon));
            }
        }

        Map<String, String> outputNamespaces = new LinkedHashMap<>();
        for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
            String nsPrefix = ns.getKey();
            String nsUri = ns.getValue();
            if (StylesheetCompiler.XSLT_NS.equals(nsUri)) {
                continue;
            }
            // Attribute prefixes must always be declared
            if (attributePrefixes.contains(nsPrefix)) {
                outputNamespaces.put(nsPrefix, nsUri);
                continue;
            }
            // Exclude if in excluded set (by URI)
            if (localExcludedURIs.contains(nsUri)) {
                continue;
            }
            outputNamespaces.put(nsPrefix, nsUri);
        }

        // Parse xsl:inherit-namespaces (XSLT 2.0+)
        boolean inheritNs = true;
        String inheritNsValue = ctx.attributes.get("xsl:inherit-namespaces");
        if (inheritNsValue != null && !inheritNsValue.isEmpty()) {
            compiler.validateYesOrNo("literal result element", "xsl:inherit-namespaces", inheritNsValue);
            inheritNs = compiler.parseYesOrNo(inheritNsValue);
        }

        // xsl:type is rejected above (XTSE1660), so pass null for type info
        // on-empty/on-non-empty are now handled by SequenceNode's two-phase execution
        return new LiteralResultElement(ctx.namespaceURI, localName, prefix,
            avts, outputNamespaces, useAttributeSets, null, null, content,
            null, null, inheritNs);
    }

    static void processStylesheetElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // Parse version attribute - REQUIRED per XSLT spec (XTSE0010)
        // Check shadow attribute (_version="{AVT}") first, then regular attribute
        String versionAttr = compiler.resolveStaticShadowAttribute(ctx, "version");
        if (versionAttr == null || versionAttr.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'version' is missing on xsl:" + ctx.localName);
        }

        // XTSE0110: version must be a valid xs:decimal (no scientific notation)
        String trimmedVersion = versionAttr.trim();
        if (trimmedVersion.toLowerCase().contains("e") || trimmedVersion.toLowerCase().contains("inf") ||
            trimmedVersion.toLowerCase().contains("nan")) {
            throw new SAXException("XTSE0110: version attribute must be a valid xs:decimal, got: " + versionAttr);
        }

        try {
            compiler.stylesheetVersion = Double.parseDouble(versionAttr);
            // Forward-compatible mode: enabled when version > max supported (3.0)
            // Per XSLT spec, processor must run in forward-compatible mode when
            // version is higher than what it implements
            compiler.forwardCompatible = compiler.stylesheetVersion > 3.0;
            // Store version in compiled stylesheet
            compiler.builder.setVersion(compiler.stylesheetVersion);
            compiler.builder.setProcessorVersion(compiler.maxProcessorVersion);
        } catch (NumberFormatException e) {
            throw new SAXException("XTSE0110: Invalid version attribute value: " + versionAttr);
        }

        // xsl:package is an XSLT 3.0 construct. The version attribute
        // controls backwards-compatible behavior within the package.
        // Only reject xsl:package when the processor version is < 3.0.
        if ("package".equals(ctx.localName) && compiler.maxProcessorVersion < 3.0) {
            throw new SAXException("XTSE0010: xsl:package is only allowed " +
                "in XSLT 3.0 or later (version=" + versionAttr + ")");
        }

        // XSLT 3.0 package attributes (only for xsl:package)
        if ("package".equals(ctx.localName)) {
            // name attribute - the package URI (optional but recommended)
            String nameAttr = ctx.attributes.get("name");
            if (nameAttr != null && !nameAttr.isEmpty()) {
                compiler.packageName = nameAttr.trim();
            }

            // package-version attribute (optional, defaults to "0.0" per spec)
            // Check shadow attribute (_package-version="{AVT}") first
            String versionAttrPkg;
            if (ctx.shadowAttributes.containsKey("package-version")) {
                try {
                    versionAttrPkg = compiler.resolveStaticShadowAttribute(ctx, "package-version");
                } catch (SAXException e) {
                    // Check root cause: NullPointerException means the shadow attribute
                    // uses functions not available in static context (e.g., doc(''))
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    if (root instanceof NullPointerException) {
                        versionAttrPkg = ctx.attributes.get("package-version");
                    } else {
                        throw e;
                    }
                }
            } else {
                versionAttrPkg = ctx.attributes.get("package-version");
            }
            if (versionAttrPkg != null) {
                String trimmedPkgVer = versionAttrPkg.trim();
                if (trimmedPkgVer.isEmpty()) {
                    throw new SAXException("XTSE0020: package-version must not be empty");
                }
                validatePackageVersion(trimmedPkgVer);
                compiler.packageVersion = trimmedPkgVer;
            } else {
                // XSLT 3.0 Section 3.5: absent package-version defaults to 1
                compiler.packageVersion = "1";
            }

            // declared-modes attribute (XSLT 3.0)
            // When "yes" (default for xsl:package), all modes must be explicitly declared
            // with xsl:mode. Using an undeclared mode is XTSE3085.
            String declaredModesAttr = ctx.attributes.get("declared-modes");
            if (declaredModesAttr != null) {
                String trimmed = declaredModesAttr.trim();
                compiler.declaredModesEnabled = "yes".equals(trimmed) || "1".equals(trimmed)
                    || "true".equals(trimmed);
            } else {
                compiler.declaredModesEnabled = true;
            }

        }

        // XTSE0265: track input-type-annotations for cross-module conflict detection
        String inputTypeAnnotations = ctx.attributes.get("input-type-annotations");
        if (inputTypeAnnotations != null && !inputTypeAnnotations.isEmpty()
                && !"unspecified".equals(inputTypeAnnotations)) {
            compiler.builder.setInputTypeAnnotations(inputTypeAnnotations);
        }

        // Parse exclude-result-prefixes attribute
        String excludePrefixes = ctx.attributes.get("exclude-result-prefixes");
        if (excludePrefixes != null && !excludePrefixes.isEmpty()) {
            String[] prefixes = excludePrefixes.split("\\s+");
            for (String prefix : prefixes) {
                if ("#all".equals(prefix)) {
                    // XSLT 2.0: exclude all namespaces in scope
                    for (Map.Entry<String, String> ns : ctx.namespaceBindings.entrySet()) {
                        if (!StylesheetCompiler.XSLT_NS.equals(ns.getValue())) {
                            compiler.excludedNamespaceURIs.add(ns.getValue());
                            compiler.builder.addExcludedNamespaceURI(ns.getValue());
                        }
                    }
                } else if ("#default".equals(prefix)) {
                    // Exclude the default namespace
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null && !defaultNs.isEmpty()) {
                        compiler.excludedNamespaceURIs.add(defaultNs);
                        compiler.builder.addExcludedNamespaceURI(defaultNs);
                    }
                } else {
                    // Regular prefix - look up its namespace URI and exclude it
                    String uri = ctx.namespaceBindings.get(prefix);
                    if (uri != null && !uri.isEmpty()) {
                        compiler.excludedNamespaceURIs.add(uri);
                        compiler.builder.addExcludedNamespaceURI(uri);
                    }
                }
            }
        }

        // Parse extension-element-prefixes attribute
        // Extension namespaces are automatically excluded from output
        String extensionPrefixes = ctx.attributes.get("extension-element-prefixes");
        if (extensionPrefixes != null && !extensionPrefixes.isEmpty()) {
            String[] prefixes = extensionPrefixes.split("\\s+");
            for (String prefix : prefixes) {
                if ("#default".equals(prefix)) {
                    String defaultNs = ctx.namespaceBindings.get("");
                    if (defaultNs != null && !defaultNs.isEmpty()) {
                        compiler.extensionNamespaceURIs.add(defaultNs);
                    }
                } else {
                    String uri = ctx.namespaceBindings.get(prefix);
                    if (uri != null && !uri.isEmpty()) {
                        compiler.extensionNamespaceURIs.add(uri);
                    }
                }
            }
        }

        // Parse default-validation attribute (XSLT 2.0+)
        String defaultValidationAttr = ctx.attributes.get("default-validation");
        if (defaultValidationAttr != null && !defaultValidationAttr.isEmpty()) {
            ValidationMode parsedDefault = compiler.parseValidationMode(
                defaultValidationAttr, "xsl:stylesheet/@default-validation");
            if (parsedDefault != null) {
                compiler.defaultValidation = parsedDefault;
            }
            compiler.builder.setDefaultValidation(compiler.defaultValidation);
        }

        // Parse default-collation attribute (XSLT 2.0+)
        String defaultCollationAttr = ctx.attributes.get("default-collation");
        if (defaultCollationAttr != null && !defaultCollationAttr.isEmpty()) {
            // The default-collation attribute is a whitespace-separated list of collation URIs
            // The processor uses the first URI it recognizes
            String[] collations = defaultCollationAttr.trim().split("\\s+");
            String selectedCollation = null;
            for (String collUri : collations) {
                if (Collation.isRecognized(collUri)) {
                    selectedCollation = collUri;
                    break;
                }
            }
            if (selectedCollation != null) {
                compiler.builder.setDefaultCollation(selectedCollation);
            } else {
                throw new SAXException("XTSE0125: No recognized collation URI " +
                    "in default-collation: " + defaultCollationAttr);
            }
        }

        // Store default-mode in compiled stylesheet for initial mode resolution
        String defaultModeAttrValue = ctx.attributes.get("default-mode");
        if (defaultModeAttrValue != null && !defaultModeAttrValue.isEmpty()) {
            String trimmed = defaultModeAttrValue.trim();
            if (!"#unnamed".equals(trimmed)) {
                String expanded = compiler.expandModeQName(trimmed, false);
                compiler.builder.setDefaultMode(expanded);
            }
        }
        // Process children which add themselves to builder
    }

    static void processImport(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // In XSLT 1.0/2.0, imports must appear before other top-level elements.
        // XSLT 3.0 removed this restriction (section 3.10.2).
        if (!compiler.importsAllowed && compiler.stylesheetVersion < 3.0) {
            throw new SAXException("xsl:import must appear before all other " +
                "elements in the stylesheet (except other xsl:import elements)");
        }

        // XTSE0260: xsl:import must be empty
        compiler.validateEmptyElement(ctx, "xsl:import");

        String href = compiler.resolveStaticShadowAttribute(ctx, "href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:import requires href attribute");
        }

        if (compiler.resolver == null) {
            throw new SAXException("xsl:import not supported: no StylesheetResolver configured");
        }

        try {
            // XSLT 1.0 import precedence rules (using global counter in resolver):
            // - Imports are processed recursively in tree traversal order
            // - Each stylesheet gets its precedence when its first non-import element is seen
            // - This ensures: D < B < E < C < A for the tree A→[B→D, C→E]
            // The imported stylesheet will assign its own precedence from the global counter
            // Pass -1 to indicate the imported stylesheet should assign its own precedence
            CompiledStylesheet imported = compiler.resolver.resolve(href, compiler.getEffectiveBaseUri(), true, -1);
            if (imported != null) {
                // Track the minimum precedence from imported modules for apply-imports scoping
                for (TemplateRule tr : imported.getTemplateRules()) {
                    int prec = tr.getImportPrecedence();
                    if (compiler.minImportedPrecedence < 0 || prec < compiler.minImportedPrecedence) {
                        compiler.minImportedPrecedence = prec;
                    }
                }
                // XSLT 3.0 allows imports after other declarations. In that case,
                // the imported module has a higher counter value but should have
                // LOWER effective precedence. Defer replacement so finalizePrecedence
                // can promote the importing module's declarations to win.
                boolean lateImport = compiler.precedenceAssigned;
                compiler.builder.merge(imported, true, lateImport);
                // Propagate static variables from imported module into parent scope
                for (GlobalVariable gv : imported.getGlobalVariables()) {
                    if (gv.isStatic()) {
                        String ln = gv.getLocalName();
                        if (!compiler.staticVariables.containsKey(ln)) {
                            compiler.staticVariables.put(ln, gv.getStaticValue());
                        }
                        // Register for XTSE3450 conflict detection (only if not already
                        // registered at a higher precedence from the importing stylesheet)
                        if (!compiler.staticDeclarationInfo.containsKey(ln)) {
                            String selStr = gv.getStaticValue() != null
                                ? gv.getStaticValue().asString() : null;
                            compiler.staticDeclarationInfo.put(ln, new Object[]{
                                Boolean.valueOf(gv.isParam()), selStr,
                                Integer.valueOf(gv.getImportPrecedence())});
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new SAXException("Failed to import stylesheet: " + href, e);
        }
    }

    static void processInclude(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // XTSE0010: xsl:include must be top-level
        if (!compiler.isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:include is only allowed at the top level");
        }

        // XTSE0260: xsl:include must be empty
        compiler.validateEmptyElement(ctx, "xsl:include");

        // Include is allowed anywhere in top-level, but once we see a non-import
        // element, no more imports are allowed
        compiler.importsAllowed = false;

        String href = compiler.resolveStaticShadowAttribute(ctx, "href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:include requires href attribute");
        }

        if (compiler.resolver == null) {
            throw new SAXException("xsl:include not supported: no StylesheetResolver configured");
        }

        try {
            // Share static variables with included module (XSLT 3.0 §3.8)
            compiler.resolver.setSharedStaticVariables(compiler.staticVariables);

            // For includes: compile the included stylesheet (which may have imports
            // that need lower precedence). The included stylesheet uses -1 to assign
            // its own precedence after its imports.
            CompiledStylesheet included = compiler.resolver.resolve(href, compiler.getEffectiveBaseUri(), false, -1);

            // Don't assign our precedence here - wait until getCompiledStylesheet()
            // to ensure our precedence is higher than ALL imports (including those
            // in subsequent includes).

            if (included != null) {
                // Merge the included stylesheet's templates, marking them for later
                // precedence update. Templates from the included stylesheet (not its
                // imports) will be updated to our precedence in getCompiledStylesheet().
                compiler.builder.mergeIncludePending(included);

                // XTSE0265: check for deferred input-type-annotations conflict
                String itaConflict = compiler.builder.getInputTypeAnnotationsConflict();
                if (itaConflict != null) {
                    throw new SAXException(itaConflict);
                }
            }
        } catch (IOException e) {
            throw new SAXException("Failed to include stylesheet: " + href, e);
        }
    }

    static void processUsePackage(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;
        compiler.ensurePrecedenceAssigned();

        // Get required name attribute
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:use-package requires name attribute");
        }

        // Get optional package-version attribute (default: "*" matches any version)
        String versionConstraint = ctx.attributes.get("package-version");
        if (versionConstraint == null || versionConstraint.isEmpty()) {
            versionConstraint = "*";
        }

        // Check if we have a package resolver
        if (compiler.packageResolver == null) {
            throw new SAXException("XTSE3020: xsl:use-package not supported: no PackageResolver configured");
        }

        try {
            // Resolve the package
            CompiledPackage pkg = compiler.packageResolver.resolve(name, versionConstraint, compiler.baseUri);

            if (pkg == null) {
                throw new SAXException("XTSE3020: Cannot resolve package: " + name);
            }

            // Process child elements (xsl:accept and xsl:override)
            List<AcceptDeclaration> accepts = new ArrayList<>();
            List<OverrideDeclaration> overrides = new ArrayList<>();

            for (XSLTNode child : ctx.children) {
                if (child instanceof AcceptDeclarationNode) {
                    accepts.add(((AcceptDeclarationNode) child).getDeclaration());
                } else if (child instanceof OverrideDeclarationNode) {
                    overrides.addAll(((OverrideDeclarationNode) child).getDeclarations());
                }
            }

            // Create the dependency record
            CompiledPackage.PackageDependency dependency =
                new CompiledPackage.PackageDependency(name, versionConstraint, pkg, accepts, overrides);
            compiler.packageDependencies.add(dependency);

            // Merge the package's public components into our stylesheet
            mergePackageComponents(compiler, pkg, accepts, overrides);

        } catch (SAXException e) {
            throw e;
        } catch (Exception e) {
            throw new SAXException("XTSE3020: Failed to resolve package " + name + ": " + e.getMessage(), e);
        }
    }

    static void processExpose(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // xsl:expose must be empty
        compiler.validateEmptyElement(ctx, "xsl:expose");

        // Get required attributes
        String componentAttr = ctx.attributes.get("component");
        if (componentAttr == null || componentAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:expose requires component attribute");
        }

        String namesAttr = ctx.attributes.get("names");
        if (namesAttr == null || namesAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:expose requires names attribute");
        }

        String visibilityAttr = ctx.attributes.get("visibility");
        if (visibilityAttr == null || visibilityAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:expose requires visibility attribute");
        }

        // Parse component type
        AcceptDeclaration.ComponentType componentType =
            AcceptDeclaration.ComponentType.parse(componentAttr);
        if (componentType == null) {
            throw new SAXException("XTSE0020: Invalid component type: " + componentAttr);
        }

        // Parse visibility
        ComponentVisibility visibility;
        try {
            visibility = ComponentVisibility.parse(visibilityAttr);
            if (visibility == null) {
                throw new SAXException("XTSE0020: Invalid visibility: " + visibilityAttr);
            }
        } catch (IllegalArgumentException e) {
            throw new SAXException("XTSE0020: Invalid visibility: " + visibilityAttr);
        }

        // XTSE3000: Cannot expose as hidden (use xsl:accept for that)
        if (visibility == ComponentVisibility.HIDDEN) {
            throw new SAXException("XTSE3000: Cannot use visibility='hidden' in xsl:expose");
        }

        // Create an expose declaration and store it for later processing
        ExposeDeclaration expose = new ExposeDeclaration(componentType, namesAttr, visibility);
        compiler.exposeDeclarations.add(expose);
    }

    static class ExposeDeclaration {
        final AcceptDeclaration.ComponentType componentType;
        final String namesPattern;
        final ComponentVisibility visibility;

        ExposeDeclaration(AcceptDeclaration.ComponentType componentType,
                         String namesPattern, ComponentVisibility visibility) {
            this.componentType = componentType;
            this.namesPattern = namesPattern;
            this.visibility = visibility;
        }

        boolean matches(AcceptDeclaration.ComponentType type, String name) {
            if (componentType != AcceptDeclaration.ComponentType.ALL &&
                componentType != type) {
                return false;
            }
            if ("*".equals(namesPattern)) {
                return true;
            }
            // Simple pattern matching
            String[] patterns = namesPattern.split("\\s+");
            for (String pattern : patterns) {
                if (matchesPattern(name, pattern)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesPattern(String name, String pattern) {
            if (pattern.equals(name)) {
                return true;
            }
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                return name.startsWith(prefix);
            }
            if (pattern.startsWith("*")) {
                String suffix = pattern.substring(1);
                return name.endsWith(suffix);
            }
            return false;
        }
    }

    static XSLTNode compileAccept(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // xsl:accept must be empty
        compiler.validateEmptyElement(ctx, "xsl:accept");

        // Get required component attribute
        String componentAttr = ctx.attributes.get("component");
        if (componentAttr == null || componentAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:accept requires component attribute");
        }

        // Get required names attribute
        String namesAttr = ctx.attributes.get("names");
        if (namesAttr == null || namesAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:accept requires names attribute");
        }

        // Get required visibility attribute
        String visibilityAttr = ctx.attributes.get("visibility");
        if (visibilityAttr == null || visibilityAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:accept requires visibility attribute");
        }

        // Parse component type
        AcceptDeclaration.ComponentType componentType =
            AcceptDeclaration.ComponentType.parse(componentAttr);
        if (componentType == null) {
            throw new SAXException("XTSE0020: Invalid component type in xsl:accept: " + componentAttr);
        }

        // Parse visibility
        ComponentVisibility visibility;
        try {
            visibility = ComponentVisibility.parse(visibilityAttr);
            if (visibility == null) {
                throw new SAXException("XTSE0020: Invalid visibility in xsl:accept: " + visibilityAttr);
            }
        } catch (IllegalArgumentException e) {
            throw new SAXException("XTSE0020: Invalid visibility in xsl:accept: " + visibilityAttr);
        }

        // Create the accept declaration
        AcceptDeclaration declaration = new AcceptDeclaration(componentType, namesAttr, visibility);
        return new AcceptDeclarationNode(declaration);
    }

    static class AcceptDeclarationNode implements XSLTNode {
        private final AcceptDeclaration declaration;

        AcceptDeclarationNode(AcceptDeclaration declaration) {
            this.declaration = declaration;
        }

        AcceptDeclaration getDeclaration() {
            return declaration;
        }

        @Override
        public void execute(TransformContext context, OutputHandler output) {
            // Accept declarations don't execute at runtime
        }

        @Override
        public XSLTNode.StreamingCapability getStreamingCapability() {
            return XSLTNode.StreamingCapability.NONE;
        }
    }

    static XSLTNode compileOverride(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        // Collect the override declarations that were accumulated during
        // child element compilation (processTemplateElement checks
        // isInsideOverride() and adds to pendingOverrideDeclarations).
        List<OverrideDeclaration> declarations;
        if (compiler.pendingOverrideDeclarations != null && !compiler.pendingOverrideDeclarations.isEmpty()) {
            declarations = new ArrayList<>(compiler.pendingOverrideDeclarations);
            compiler.pendingOverrideDeclarations = null;
        } else {
            declarations = new ArrayList<>();
        }
        return new OverrideDeclarationNode(declarations);
    }

    static class OverrideDeclarationNode implements XSLTNode {
        private final List<OverrideDeclaration> declarations;

        OverrideDeclarationNode(List<OverrideDeclaration> declarations) {
            this.declarations = declarations;
        }

        List<OverrideDeclaration> getDeclarations() {
            return declarations;
        }

        @Override
        public void execute(TransformContext context, OutputHandler output) {
            // Override declarations don't execute at runtime
        }

        @Override
        public XSLTNode.StreamingCapability getStreamingCapability() {
            return XSLTNode.StreamingCapability.NONE;
        }
    }

    static void mergePackageComponents(StylesheetCompiler compiler, CompiledPackage pkg,
                                        List<AcceptDeclaration> accepts,
                                        List<OverrideDeclaration> overrides)
            throws SAXException, TransformerConfigurationException {

        compiler.ensurePrecedenceAssigned();

        int maxPkgPrec = 0;
        for (TemplateRule t : pkg.getStylesheet().getTemplateRules()) {
            int prec = t.getImportPrecedence();
            if (prec > maxPkgPrec) {
                maxPkgPrec = prec;
            }
        }
        int pkgPrecOffset = compiler.importPrecedence - maxPkgPrec - 1;

        Set<String> overriddenTemplates = new HashSet<String>();
        Set<String> overriddenFunctions = new HashSet<String>();
        Set<String> overriddenVariables = new HashSet<String>();
        Set<String> overriddenAttributeSets = new HashSet<String>();
        boolean addedXslOriginal = false;

        for (OverrideDeclaration override : overrides) {
            switch (override.getType()) {
                case TEMPLATE:
                    overriddenTemplates.add(override.getOriginalComponentKey());
                    break;
                case FUNCTION:
                    overriddenFunctions.add(override.getOriginalComponentKey());
                    UserFunction overrideFunc = override.getOverrideFunction();
                    if (overrideFunc != null) {
                        overriddenFunctions.add(overrideFunc.getKey());
                    }
                    break;
                case VARIABLE:
                case PARAM:
                    overriddenVariables.add(override.getOriginalComponentKey());
                    break;
                case ATTRIBUTE_SET:
                    overriddenAttributeSets.add(override.getOriginalComponentKey());
                    break;
            }
        }

        Map<String, TemplateRule> allNamedTemplates = new HashMap<String, TemplateRule>();
        for (TemplateRule t : pkg.getStylesheet().getTemplateRules()) {
            if (t.getName() != null) {
                allNamedTemplates.put(t.getName(), t);
            }
            if (t.getMatchPattern() != null) {
                String tMode = t.getMode() != null ? t.getMode() : "#default";
                String patternKey = t.getMatchPattern().toString() + "#" + tMode;
                allNamedTemplates.put(patternKey, t);
            }
        }

        Set<String> newOverrideTemplates = new HashSet<String>();
        if (!overriddenTemplates.isEmpty()) {
            for (String overriddenKey : overriddenTemplates) {
                TemplateRule orig = allNamedTemplates.get(overriddenKey);
                if (orig == null) {
                    boolean isNamedKey = !overriddenKey.contains("#");
                    if (isNamedKey) {
                        throw new SAXException("XTSE3058: Override template '" +
                            overriddenKey +
                            "' does not match any component in package '" +
                            pkg.getPackageName() + "'");
                    }
                    newOverrideTemplates.add(overriddenKey);
                    continue;
                }
                String acceptName = orig.getName() != null
                        ? orig.getName() : overriddenKey;
                ComponentVisibility vis = getEffectiveTemplateVisibility(
                        pkg, orig, accepts, acceptName);
                if (!vis.isAccessible()) {
                    throw new SAXException("XTSE3060: Cannot override template '" +
                        overriddenKey + "' because its visibility is " + vis);
                }
                for (OverrideDeclaration od : overrides) {
                    if (od.getType() == OverrideDeclaration.OverrideType.TEMPLATE
                            && overriddenKey.equals(od.getOriginalComponentKey())) {
                        TemplateRule overrideRule = od.getOverrideTemplate();
                        if (overrideRule != null && orig.getAsType() != null
                                && overrideRule.getAsType() != null) {
                            String origAs = orig.getAsType().trim();
                            String overrideAs = overrideRule.getAsType().trim();
                            if (!origAs.equals(overrideAs)) {
                                throw new SAXException("XTSE3070: Override template '" +
                                    overriddenKey +
                                    "' has incompatible return type '" +
                                    overrideAs + "' (original is '" + origAs + "')");
                            }
                        }
                    }
                }
            }
        }
        overriddenTemplates.removeAll(newOverrideTemplates);

        Set<String> allComponentNames = new HashSet<String>(allNamedTemplates.keySet());
        for (UserFunction f : pkg.getStylesheet().getUserFunctions().values()) {
            allComponentNames.add("{" + f.getNamespaceURI() + "}" + f.getLocalName());
        }
        for (ModeDeclaration m : pkg.getStylesheet().getModeDeclarations().values()) {
            String modeName = m.getName() != null ? m.getName() : "#default";
            allComponentNames.add(modeName);
        }

        for (AcceptDeclaration accept : accepts) {
            String namesAttr = accept.getNamesPattern();
            if (namesAttr == null || "*".equals(namesAttr.trim())) {
                continue;
            }
            String[] names = namesAttr.trim().split("\\s+");
            boolean anyMatched = false;
            for (String name : names) {
                if ("*".equals(name) || name.endsWith(":*")) {
                    anyMatched = true;
                    continue;
                }
                if (name.startsWith("*:")) {
                    String localPart = name.substring(2);
                    for (String compName : allComponentNames) {
                        int braceClose = compName.lastIndexOf('}');
                        String compLocal = braceClose >= 0
                                ? compName.substring(braceClose + 1)
                                : compName;
                        if (localPart.equals(compLocal)) {
                            anyMatched = true;
                            break;
                        }
                    }
                    continue;
                }
                boolean found = allComponentNames.contains(name);
                if (found) {
                    anyMatched = true;
                    TemplateRule tmpl = allNamedTemplates.get(name);
                    if (tmpl != null) {
                        ComponentVisibility origVis = tmpl.getVisibility();
                        ComponentVisibility acceptVis = accept.getVisibility();
                        if (origVis == ComponentVisibility.PRIVATE
                                && acceptVis != ComponentVisibility.HIDDEN) {
                            throw new SAXException("XTSE3040: Cannot accept private " +
                                "template '" + name + "' with visibility '" + acceptVis + "'");
                        }
                    }
                    if (overriddenTemplates.contains(name)
                            || overriddenFunctions.contains(name)) {
                        throw new SAXException("XTSE3051: xsl:accept name '" + name +
                            "' matches a component declared in xsl:override");
                    }
                }
            }
            if (!anyMatched) {
                throw new SAXException("XTSE3030: xsl:accept matches no components " +
                    "in package '" + pkg.getPackageName() + "' with names '" + namesAttr + "'");
            }
        }

        CompiledStylesheet pkgStylesheet = pkg.getStylesheet();
        for (TemplateRule template : pkgStylesheet.getTemplateRules()) {
            String key = getTemplateKey(template);
            String acceptName = template.getName() != null
                    ? template.getName() : key;
            ComponentVisibility pkgVis = pkg.getTemplateVisibility(template);
            if (!pkgVis.isAccessible()) {
                continue;
            }
            ComponentVisibility vis = getEffectiveTemplateVisibility(
                    pkg, template, accepts, acceptName);
            if (vis == ComponentVisibility.HIDDEN) {
                continue;
            }
            String xslOriginal = "{" + StylesheetCompiler.XSLT_NS + "}original";
            if (xslOriginal.equals(template.getName())) {
                continue;
            }
            boolean isOverridden = overriddenTemplates.contains(key);
            if (!isOverridden && template.getMatchPattern() != null) {
                String tMode = template.getMode() != null
                        ? template.getMode() : "#default";
                String patternKey = template.getMatchPattern().toString()
                        + "#" + tMode;
                isOverridden = overriddenTemplates.contains(patternKey);
            }
            if (!isOverridden && template.getName() != null
                    && template.getMode() != null) {
                String nameMode = template.getName() + "#" + template.getMode();
                isOverridden = overriddenTemplates.contains(nameMode);
            }
            if (isOverridden) {
                String origName = "{" + StylesheetCompiler.XSLT_NS + "}original";
                boolean alreadyOriginal = origName.equals(template.getName());
                int remappedPrec = pkgPrecOffset + template.getImportPrecedence();
                if (!addedXslOriginal && !alreadyOriginal) {
                    TemplateRule origTemplate = new TemplateRule(
                        null, origName,
                        template.getMode(), template.getPriority(),
                        remappedPrec,
                        template.getDeclarationIndex(),
                        template.getParameters(),
                        template.getBody(),
                        template.getAsType(),
                        template.getVisibility());
                    origTemplate.setEffectiveVersion(template.getEffectiveVersion());
                    origTemplate.setDefiningStylesheet(pkgStylesheet);
                    compiler.builder.addTemplateRule(origTemplate);
                    addedXslOriginal = true;
                }
                if (template.getMatchPattern() != null) {
                    TemplateRule matchable = template.withImportPrecedence(remappedPrec)
                            .withVisibility(vis);
                    matchable.setDefiningStylesheet(pkgStylesheet);
                    compiler.builder.addTemplateRule(matchable);
                }
                continue;
            }
            if (vis != ComponentVisibility.HIDDEN) {
                int remappedPrec = pkgPrecOffset + template.getImportPrecedence();
                TemplateRule imported = template.withImportPrecedence(remappedPrec)
                        .withVisibility(vis);
                imported.setDefiningStylesheet(pkgStylesheet);
                compiler.builder.addTemplateRule(imported);
            }
        }

        for (UserFunction function : pkgStylesheet.getUserFunctions().values()) {
            String key = function.getKey();
            if (overriddenFunctions.contains(key)) {
                try {
                    UserFunction origFunc = new UserFunction(
                        StylesheetCompiler.XSLT_NS, "original",
                        function.getParameters(), function.getBody(),
                        function.getAsType(), function.getImportPrecedence(),
                        function.isCached(), function.getVisibility());
                    origFunc.setDefiningStylesheet(pkgStylesheet);
                    compiler.builder.addUserFunction(origFunc);
                } catch (javax.xml.transform.TransformerConfigurationException e) {
                    throw new SAXException(e.getMessage(), e);
                }
                continue;
            }
            String funcName = "{" + function.getNamespaceURI() + "}" + function.getLocalName();
            ComponentVisibility funcPkgVis = pkg.getFunctionVisibility(function);
            if (!funcPkgVis.isAccessible()) {
                continue;
            }
            ComponentVisibility vis = funcPkgVis;
            for (AcceptDeclaration accept : accepts) {
                if (accept.matchesType(AcceptDeclaration.ComponentType.FUNCTION)
                        && accept.matchesName(funcName)) {
                    vis = accept.getVisibility();
                    break;
                }
            }
            if (vis == ComponentVisibility.HIDDEN) {
                continue;
            }
            try {
                UserFunction imported = function.withVisibility(vis);
                imported.setDefiningStylesheet(pkgStylesheet);
                compiler.builder.addUserFunction(imported);
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
        }

        for (GlobalVariable variable : pkg.getPublicVariables()) {
            String key = variable.getExpandedName();
            if (overriddenVariables.contains(key)) {
                continue;
            }
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.VARIABLE, key);
            if (vis != ComponentVisibility.HIDDEN) {
                compiler.builder.addGlobalVariable(variable.withVisibility(vis));
            }
        }

        for (AttributeSet attrSet : pkg.getPublicAttributeSets()) {
            String key = attrSet.getName();
            if (overriddenAttributeSets.contains(key)) {
                String origName = "{" + StylesheetCompiler.XSLT_NS + "}original";
                AttributeSet origAttrSet = new AttributeSet(origName,
                    attrSet.getUseAttributeSets(), attrSet.getAttributes(),
                    attrSet.getVisibility(), attrSet.isStreamable());
                compiler.builder.addAttributeSet(origAttrSet);
                continue;
            }
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.ATTRIBUTE_SET, key);
            if (vis != ComponentVisibility.HIDDEN) {
                compiler.builder.addAttributeSet(attrSet.withVisibility(vis));
            }
        }

        for (ModeDeclaration mode : pkg.getPublicModes()) {
            String key = mode.getName() != null ? mode.getName() : "#default";
            ComponentVisibility vis = getAcceptedVisibility(
                accepts, AcceptDeclaration.ComponentType.MODE, key);
            if (vis != ComponentVisibility.HIDDEN) {
                compiler.builder.addModeDeclaration(mode.withComponentVisibility(vis));
            }
        }
    }

    static ComponentVisibility getAcceptedVisibility(List<AcceptDeclaration> accepts,
                                                      AcceptDeclaration.ComponentType type,
                                                      String componentName) {
        for (AcceptDeclaration accept : accepts) {
            if (accept.matchesType(type) && accept.matchesName(componentName)) {
                return accept.getVisibility();
            }
        }
        return ComponentVisibility.PUBLIC;
    }

    static boolean isExplicitlyAccepted(AcceptDeclaration.ComponentType type,
            String name, List<AcceptDeclaration> accepts) {
        for (AcceptDeclaration accept : accepts) {
            if (accept.matchesType(type) && accept.matchesName(name)) {
                return true;
            }
        }
        return false;
    }

    static ComponentVisibility getEffectiveTemplateVisibility(
            CompiledPackage pkg, TemplateRule template,
            List<AcceptDeclaration> accepts, String acceptName) {
        for (AcceptDeclaration accept : accepts) {
            if (accept.matchesType(AcceptDeclaration.ComponentType.TEMPLATE)
                    && accept.matchesName(acceptName)) {
                return accept.getVisibility();
            }
        }
        return pkg.getTemplateVisibility(template);
    }

    static String getTemplateKey(TemplateRule template) {
        if (template.getName() != null) {
            return template.getName();
        } else if (template.getMatchPattern() != null) {
            String mode = template.getMode() != null ? template.getMode() : "#default";
            return template.getMatchPattern().toString() + "#" + mode;
        }
        return "unknown";
    }

    static void processImportSchema(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;
        compiler.ensurePrecedenceAssigned();

        compiler.inImportSchema = false;

        String namespace = ctx.attributes.get("namespace");
        String schemaLocation = ctx.attributes.get("schema-location");

        if (schemaLocation == null || schemaLocation.isEmpty()) {
            compiler.importSchemaNamespace = null;
            return;
        }

        try {
            String resolvedLocation = compiler.resolveUri(schemaLocation);
            XSDSchema schema = XSDSchemaParser.parse(resolvedLocation);

            if (namespace != null && !namespace.isEmpty()) {
                String schemaTargetNs = schema.getTargetNamespace();
                if (schemaTargetNs != null && !namespace.equals(schemaTargetNs)) {
                    throw new SAXException("Schema target namespace '" + schemaTargetNs +
                        "' does not match declared namespace '" + namespace + "'");
                }
            }

            compiler.builder.addImportedSchema(schema);

        } catch (IOException e) {
            // Schema not found - silently continue (best effort for Basic processor)
        } catch (Exception e) {
            // Schema parse error - silently continue
        } finally {
            compiler.importSchemaNamespace = null;
        }
    }

    /**
     * Checks whether a string contains an AVT (Attribute Value Template).
     * Returns true if the string has a '{...}' pattern that is not an
     * escaped '{{', not an EQName ('Q{uri}'), and not inside a string
     * literal.
     */
    static boolean containsAVT(String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < len && s.charAt(i) != quote) {
                    i++;
                }
                continue;
            }
            if (c == '{') {
                if (i > 0 && s.charAt(i - 1) == 'Q') {
                    continue;
                }
                if (i + 1 < len && s.charAt(i + 1) == '{') {
                    i++;
                    continue;
                }
                int close = s.indexOf('}', i + 1);
                if (close > i) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isStaticValue(String value) {
        if (value == null) {
            return false;
        }
        value = value.trim();
        return "yes".equals(value) || "true".equals(value) || "1".equals(value);
    }

    static void validatePackageVersion(String version) throws SAXException {
        int len = version.length();
        int i = 0;

        if (i >= len || version.charAt(i) < '0' || version.charAt(i) > '9') {
            throw new SAXException("XTSE0020: Invalid package-version '" + version +
                "': must start with a digit");
        }

        while (i < len && version.charAt(i) >= '0' && version.charAt(i) <= '9') {
            i++;
        }

        while (i < len && version.charAt(i) == '.') {
            i++;
            if (i >= len || version.charAt(i) < '0' || version.charAt(i) > '9') {
                throw new SAXException("XTSE0020: Invalid package-version '" + version +
                    "': digit expected after '.'");
            }
            while (i < len && version.charAt(i) >= '0' && version.charAt(i) <= '9') {
                i++;
            }
        }

        while (i < len && version.charAt(i) == '-') {
            i++;
            if (i >= len) {
                throw new SAXException("XTSE0020: Invalid package-version '" + version +
                    "': name expected after '-'");
            }
            int cp = Character.codePointAt(version, i);
            if (!isNCNameStartCodePoint(cp)) {
                throw new SAXException("XTSE0020: Invalid package-version '" + version +
                    "': invalid name start character after '-'");
            }
            i += Character.charCount(cp);
            while (i < len) {
                cp = Character.codePointAt(version, i);
                if (cp == '-') {
                    break;
                }
                if (!isNCNameCodePoint(cp)) {
                    throw new SAXException("XTSE0020: Invalid package-version '" + version +
                        "': invalid character in name part");
                }
                i += Character.charCount(cp);
            }
        }

        if (i < len) {
            throw new SAXException("XTSE0020: Invalid package-version '" + version +
                "': unexpected character at position " + i);
        }
    }

    private static boolean isNCNameStartCodePoint(int cp) {
        return (cp >= 'A' && cp <= 'Z') || cp == '_' || (cp >= 'a' && cp <= 'z') ||
               (cp >= 0xC0 && cp <= 0xD6) || (cp >= 0xD8 && cp <= 0xF6) ||
               (cp >= 0xF8 && cp <= 0x2FF) || (cp >= 0x370 && cp <= 0x37D) ||
               (cp >= 0x37F && cp <= 0x1FFF) || (cp >= 0x200C && cp <= 0x200D) ||
               (cp >= 0x2070 && cp <= 0x218F) || (cp >= 0x2C00 && cp <= 0x2FEF) ||
               (cp >= 0x3001 && cp <= 0xD7FF) || (cp >= 0xF900 && cp <= 0xFDCF) ||
               (cp >= 0xFDF0 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0xEFFFF);
    }

    private static boolean isNCNameCodePoint(int cp) {
        return isNCNameStartCodePoint(cp) || cp == '-' || cp == '.' ||
               (cp >= '0' && cp <= '9') || cp == 0xB7 ||
               (cp >= 0x0300 && cp <= 0x036F) || (cp >= 0x203F && cp <= 0x2040);
    }

    static void processOutputElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;

        String method = ctx.attributes.get("method");
        if (method != null) {
            method = method.trim();
            if (!method.isEmpty()) {
                int colonIdx = method.indexOf(':');
                if (colonIdx < 0) {
                    if (!"xml".equals(method) && !"html".equals(method) &&
                            !"xhtml".equals(method) && !"text".equals(method) &&
                            !"json".equals(method) && !"adaptive".equals(method)) {
                        throw new SAXException("XTSE1570: Invalid output method '" + method +
                            "'. Must be xml, html, xhtml, text, or a prefixed QName");
                    }
                } else {
                    String prefix = method.substring(0, colonIdx);
                    String localPart = method.substring(colonIdx + 1);
                    if (prefix.isEmpty() || localPart.isEmpty() || localPart.contains(":")) {
                        throw new SAXException("XTSE1570: Invalid output method '" + method +
                            "'. Not a valid QName");
                    }
                }
            }
        }

        String rawName = ctx.attributes.get("name");
        String name = null;
        if (rawName != null && !rawName.isEmpty()) {
            name = compiler.expandQName(rawName.trim());
        }

        compiler.validateOutputBoolean(ctx, "byte-order-mark");
        compiler.validateOutputBoolean(ctx, "escape-uri-attributes");
        compiler.validateOutputBoolean(ctx, "include-content-type");
        compiler.validateOutputBoolean(ctx, "indent");
        compiler.validateOutputBoolean(ctx, "omit-xml-declaration");
        compiler.validateOutputBoolean(ctx, "undeclare-prefixes");

        String standalone = ctx.attributes.get("standalone");
        if (standalone != null && !standalone.isEmpty()) {
            String trimmed = standalone.trim();
            if (!trimmed.isEmpty() && !"yes".equals(trimmed) && !"no".equals(trimmed) && !"omit".equals(trimmed)) {
                if (compiler.stylesheetVersion < 3.0 || (!"true".equals(trimmed) && !"false".equals(trimmed) &&
                        !"1".equals(trimmed) && !"0".equals(trimmed))) {
                    throw new SAXException("XTSE0020: Invalid value for standalone attribute on xsl:output: got '" +
                        standalone + "'");
                }
            }
        }

        String htmlVersion = ctx.attributes.get("html-version");
        if (htmlVersion != null && !htmlVersion.isEmpty()) {
            String hv = htmlVersion.trim();
            if (!hv.isEmpty()) {
                try {
                    Double.parseDouble(hv);
                } catch (NumberFormatException e) {
                    throw new SAXException("XTSE0020: Invalid value for html-version on xsl:output: '" +
                        htmlVersion + "' is not a valid number");
                }
            }
        }

        String doctypePublic = ctx.attributes.get("doctype-public");
        if (doctypePublic != null && !doctypePublic.isEmpty()) {
            for (int i = 0; i < doctypePublic.length(); i++) {
                char c = doctypePublic.charAt(i);
                if (!compiler.isPubidChar(c)) {
                    throw new SAXException("SEPM0016: Invalid character in doctype-public: '" +
                        doctypePublic + "' (character '" + c + "' at position " + i + ")");
                }
            }
        }

        String[] attrNames = {"method", "version", "encoding", "indent",
            "omit-xml-declaration", "standalone", "doctype-public", "doctype-system",
            "media-type", "byte-order-mark", "normalization-form",
            "parameter-document", "html-version"};
        for (String attrName : attrNames) {
            String val = ctx.attributes.get(attrName);
            if (val != null) {
                compiler.builder.mergeOutputAttribute(name, attrName, val);
            }
        }
        if (name != null && !name.isEmpty()) {
            compiler.builder.registerOutputDefinition(name);
        }

        OutputProperties props = new OutputProperties();

        String paramDoc = ctx.attributes.get("parameter-document");
        if (paramDoc != null && !paramDoc.isEmpty()) {
            applyParameterDocument(compiler, ctx, props, paramDoc);
        }

        if (method != null) {
            props.setMethod(method);
        }
        String version = ctx.attributes.get("version");
        if (version != null) {
            props.setVersion(version);
        }
        String encoding = ctx.attributes.get("encoding");
        if (encoding != null) {
            props.setEncoding(encoding);
        }
        String indent = ctx.attributes.get("indent");
        if ("yes".equals(indent)) {
            props.setIndent(true);
        }
        String omitDecl = ctx.attributes.get("omit-xml-declaration");
        if ("yes".equals(omitDecl)) {
            props.setOmitXmlDeclaration(true);
        }
        String allowDupNames = ctx.attributes.get("allow-duplicate-names");
        if ("yes".equals(allowDupNames) || "true".equals(allowDupNames)
                || "1".equals(allowDupNames)) {
            props.setAllowDuplicateNames(true);
        }
        String useCharacterMaps = ctx.attributes.get("use-character-maps");
        if (useCharacterMaps != null && !useCharacterMaps.isEmpty()) {
            String[] mapNames = useCharacterMaps.split("\\s+");
            for (String mapName : mapNames) {
                if (!mapName.isEmpty()) {
                    String expandedMap = compiler.expandQName(mapName.trim());
                    props.addUseCharacterMap(expandedMap);
                }
            }
        }

        compiler.builder.setOutputProperties(props);
    }

    private static final String PARAM_DOC_CHARMAP_NAME =
        "\0__parameter-document-charmap__";

    /**
     * Parses a serialization parameter document and applies its settings
     * to the given OutputProperties as defaults.
     */
    private static void applyParameterDocument(StylesheetCompiler compiler,
            StylesheetCompiler.ElementContext ctx, OutputProperties props,
            String paramDocHref) throws SAXException {
        String baseURI = ctx.baseURI;
        String resolvedURI;
        if (baseURI != null && !baseURI.isEmpty()) {
            try {
                java.net.URI base = new java.net.URI(baseURI);
                java.net.URI resolved = base.resolve(paramDocHref);
                resolvedURI = resolved.toString();
            } catch (java.net.URISyntaxException e) {
                resolvedURI = paramDocHref;
            }
        } else {
            resolvedURI = paramDocHref;
        }

        try {
            javax.xml.parsers.DocumentBuilderFactory dbf =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc;
            if (resolvedURI.startsWith("file:")) {
                doc = db.parse(resolvedURI);
            } else {
                doc = db.parse(new org.xml.sax.InputSource(resolvedURI));
            }
            String SER_NS = "http://www.w3.org/2010/xslt-xquery-serialization";
            org.w3c.dom.Element root = doc.getDocumentElement();
            org.w3c.dom.NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                    continue;
                }
                org.w3c.dom.Element elem = (org.w3c.dom.Element) child;
                String ns = elem.getNamespaceURI();
                if (!SER_NS.equals(ns)) {
                    continue;
                }
                String localName = elem.getLocalName();
                String value = elem.getAttribute("value");
                if ("method".equals(localName)) {
                    props.setMethod(value);
                } else if ("version".equals(localName)) {
                    props.setVersion(value);
                } else if ("encoding".equals(localName)) {
                    props.setEncoding(value);
                } else if ("indent".equals(localName)) {
                    if ("yes".equals(value)) {
                        props.setIndent(true);
                    }
                } else if ("omit-xml-declaration".equals(localName)) {
                    if ("yes".equals(value)) {
                        props.setOmitXmlDeclaration(true);
                    }
                } else if ("allow-duplicate-names".equals(localName)) {
                    if ("yes".equals(value) || "true".equals(value)
                            || "1".equals(value)) {
                        props.setAllowDuplicateNames(true);
                    }
                } else if ("media-type".equals(localName)) {
                    props.setMediaType(value);
                } else if ("use-character-maps".equals(localName)) {
                    applyParameterDocumentCharacterMaps(
                        compiler, props, elem, SER_NS);
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new SAXException("Error parsing parameter document: "
                + e.getMessage(), e);
        } catch (IOException e) {
            throw new SAXException("Error reading parameter document '"
                + resolvedURI + "': " + e.getMessage(), e);
        }
    }

    /**
     * Processes use-character-maps from a serialization parameter document.
     * Creates a synthetic named character map and references it in the
     * output properties.
     */
    private static void applyParameterDocumentCharacterMaps(
            StylesheetCompiler compiler, OutputProperties props,
            org.w3c.dom.Element useCharMapsElem, String serNS)
            throws SAXException {
        Map<Integer, String> mappings = new LinkedHashMap<Integer, String>();
        org.w3c.dom.NodeList children = useCharMapsElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            org.w3c.dom.Element elem = (org.w3c.dom.Element) child;
            if (!serNS.equals(elem.getNamespaceURI())) {
                continue;
            }
            if (!"character-map".equals(elem.getLocalName())) {
                continue;
            }
            String character = elem.getAttribute("character");
            String mapString = elem.getAttribute("map-string");
            if (character != null && !character.isEmpty()
                    && mapString != null) {
                int codePoint = character.codePointAt(0);
                mappings.put(codePoint, mapString);
            }
        }
        if (!mappings.isEmpty()) {
            CompiledStylesheet.CharacterMap syntheticMap =
                new CompiledStylesheet.CharacterMap(PARAM_DOC_CHARMAP_NAME,
                    mappings, new ArrayList<String>());
            compiler.builder.addCharacterMap(syntheticMap);
            props.addUseCharacterMap(PARAM_DOC_CHARMAP_NAME);
        }
    }

    static void processKeyElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        if (!compiler.isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:key is only allowed at the top level");
        }

        compiler.importsAllowed = false;
        String name = ctx.attributes.get("name");
        String match = ctx.attributes.get("match");
        String use = ctx.attributes.get("use");
        String collation = ctx.attributes.get("collation");

        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:key requires name attribute");
        }
        if (match == null || match.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:key requires match attribute");
        }
        if (use == null && ctx.children.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:key requires use attribute or content");
        }
        if (use != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE1205: xsl:key must not have both a use attribute and content");
        }

        if (collation != null && !collation.isEmpty()) {
            if (!Collation.isRecognized(collation)) {
                throw new SAXException("XTSE1210: Unknown collation URI on xsl:key: " + collation);
            }
        }

        QName keyName = compiler.parseQName(name, ctx.namespaceBindings, true);

        Pattern pattern = compiler.compilePattern(match);
        XPathExpression useExpr = null;
        SequenceNode content = null;
        if (use != null) {
            useExpr = compiler.compileExpression(use);
        } else {
            content = new SequenceNode(ctx.children);
        }

        String compositeStr = ctx.attributes.get("composite");
        compiler.validateYesOrNo("xsl:key", "composite", compositeStr);
        boolean composite = "yes".equals(compositeStr) || "1".equals(compositeStr)
                || "true".equals(compositeStr);

        String keyCollation = null;
        if (collation != null && !collation.isEmpty()) {
            keyCollation = collation;
        } else if (ctx.defaultCollation != null) {
            keyCollation = ctx.defaultCollation;
        } else if (compiler.builder.getDefaultCollation() != null) {
            keyCollation = compiler.builder.getDefaultCollation();
        }
        compiler.builder.addKeyDefinition(new KeyDefinition(keyName, pattern, useExpr, content,
                composite, keyCollation));
    }

    static void processAttributeSetElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;
        String name = ctx.attributes.get("name");

        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'name' is missing on xsl:attribute-set");
        }

        String streamableAttr = ctx.attributes.get("streamable");
        if (streamableAttr != null && !streamableAttr.isEmpty()) {
            compiler.validateYesOrNo("xsl:attribute-set", "streamable", streamableAttr);
        }

        String useAttrSets = ctx.attributes.get("use-attribute-sets");

        String expandedName = compiler.expandAttributeSetName(name, ctx.namespaceBindings, true);

        List<String> useSets = new ArrayList<>();
        if (useAttrSets != null) {
            for (String s : compiler.splitOnWhitespace(useAttrSets)) {
                useSets.add(compiler.expandAttributeSetName(s.trim(), ctx.namespaceBindings));
            }
        }

        List<XSLTNode> attrNodes = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (text != null && !text.trim().isEmpty()) {
                    throw new SAXException("XTSE0010: Text content is not allowed in xsl:attribute-set");
                }
            } else if (!(child instanceof AttributeNode)) {
                throw new SAXException("XTSE0010: Only xsl:attribute is allowed in xsl:attribute-set");
            } else {
                attrNodes.add(child);
            }
        }

        SequenceNode attrs = new SequenceNode(attrNodes);
        String streamableTrimmed = streamableAttr != null ? streamableAttr.trim() : null;
        boolean isStreamable = "yes".equals(streamableTrimmed) ||
            "true".equals(streamableTrimmed) || "1".equals(streamableTrimmed);

        if (isStreamable) {
            StreamabilityAnalyzer attrAnalyzer = new StreamabilityAnalyzer();
            List<String> reasons = new ArrayList<String>();
            StreamabilityAnalyzer.ExpressionStreamability bodyClass =
                attrAnalyzer.analyzeNode(attrs, reasons);
            if (bodyClass != StreamabilityAnalyzer.ExpressionStreamability
                    .MOTIONLESS) {
                StringBuilder sb = new StringBuilder();
                sb.append("XTSE3430: Attribute set '");
                sb.append(name);
                sb.append("' is declared streamable but its body is not");
                if (!reasons.isEmpty()) {
                    sb.append(": ");
                    sb.append(reasons.get(0));
                }
                throw new SAXException(sb.toString());
            }
        }

        AttributeSet attrSet = new AttributeSet(expandedName, useSets,
                                                   attrs, ComponentVisibility.PUBLIC,
                                                   isStreamable);
        compiler.builder.addAttributeSet(attrSet);

        if (compiler.isInsideOverride()) {
            if (compiler.pendingOverrideDeclarations == null) {
                compiler.pendingOverrideDeclarations = new ArrayList<OverrideDeclaration>();
            }
            compiler.pendingOverrideDeclarations.add(
                OverrideDeclaration.forAttributeSet(attrSet));
        }
    }

    static void processStripSpace(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;

        compiler.validateEmptyElement(ctx, "xsl:strip-space");

        String elements = ctx.attributes.get("elements");
        if (elements == null) {
            throw new SAXException("XTSE0010: Required attribute 'elements' is missing on xsl:strip-space");
        }
        for (String e : compiler.splitOnWhitespace(elements)) {
            String resolved = compiler.resolveElementNameToUri(e, ctx.namespaceBindings);
            compiler.builder.addStripSpaceElement(resolved);
        }
    }

    static void processPreserveSpace(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;

        compiler.validateEmptyElement(ctx, "xsl:preserve-space");
        String elements = ctx.attributes.get("elements");
        if (elements == null) {
            throw new SAXException("XTSE0010: Required attribute 'elements' is missing on xsl:preserve-space");
        }
        for (String e : compiler.splitOnWhitespace(elements)) {
            String resolved = compiler.resolveElementNameToUri(e, ctx.namespaceBindings);
            compiler.builder.addPreserveSpaceElement(resolved);
        }
    }

    static void processNamespaceAlias(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;
        String stylesheetPrefix = ctx.attributes.get("stylesheet-prefix");
        String resultPrefix = ctx.attributes.get("result-prefix");

        if (stylesheetPrefix == null) {
            throw new SAXException("XTSE0010: xsl:namespace-alias requires stylesheet-prefix attribute");
        }
        if (resultPrefix == null) {
            throw new SAXException("XTSE0010: xsl:namespace-alias requires result-prefix attribute");
        }

        if ("#default".equals(stylesheetPrefix)) {
            stylesheetPrefix = "";
        }
        if ("#default".equals(resultPrefix)) {
            resultPrefix = "";
        }

        String stylesheetUri = ctx.namespaceBindings.get(stylesheetPrefix);
        String resultUri = ctx.namespaceBindings.get(resultPrefix);

        if (stylesheetUri == null) {
            stylesheetUri = compiler.lookupNamespaceUri(stylesheetPrefix);
        }
        if (resultUri == null) {
            resultUri = compiler.lookupNamespaceUri(resultPrefix);
        }

        if (stylesheetUri == null && !stylesheetPrefix.isEmpty()) {
            throw new SAXException("XTSE0812: No namespace binding for prefix '" +
                stylesheetPrefix + "' in xsl:namespace-alias/@stylesheet-prefix");
        }
        if (resultUri == null && !resultPrefix.isEmpty()) {
            throw new SAXException("XTSE0812: No namespace binding for prefix '" +
                resultPrefix + "' in xsl:namespace-alias/@result-prefix");
        }

        if (stylesheetUri == null) {
            stylesheetUri = "";
        }
        if (resultUri == null) {
            resultUri = "";
        }

        if (stylesheetUri.equals(resultUri)) {
            throw new SAXException("XTSE0010: stylesheet-prefix and result-prefix must not " +
                "map to the same namespace URI (" + stylesheetUri + ")");
        }

        compiler.ensurePrecedenceAssigned();
        CompiledStylesheet.NamespaceAlias existing = compiler.builder.getNamespaceAlias(stylesheetUri);
        if (existing != null && !existing.resultUri.equals(resultUri)) {
            if (existing.importPrecedence == compiler.importPrecedence) {
                throw new SAXException("XTSE0810: Conflicting namespace-alias " +
                    "declarations for namespace '" + stylesheetUri +
                    "': result URIs '" + existing.resultUri + "' and '" + resultUri + "'");
            }
            if (existing.importPrecedence < compiler.importPrecedence) {
                compiler.builder.addNamespaceAlias(stylesheetUri, resultUri, resultPrefix, compiler.importPrecedence);
            }
        } else {
            compiler.builder.addNamespaceAlias(stylesheetUri, resultUri, resultPrefix, compiler.importPrecedence);
        }
    }

    /**
     * Processes an xsl:decimal-format declaration.
     */
    static void processDecimalFormat(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String decimalSeparator = ctx.attributes.get("decimal-separator");
        String groupingSeparator = ctx.attributes.get("grouping-separator");
        String infinity = ctx.attributes.get("infinity");
        String minusSign = ctx.attributes.get("minus-sign");
        String nan = ctx.attributes.get("NaN");
        String percent = ctx.attributes.get("percent");
        String perMille = ctx.attributes.get("per-mille");
        String zeroDigit = ctx.attributes.get("zero-digit");
        String digit = ctx.attributes.get("digit");
        String patternSeparator = ctx.attributes.get("pattern-separator");
        String exponentSeparator = ctx.attributes.get("exponent-separator");

        if (exponentSeparator != null && compiler.maxProcessorVersion < 3.0) {
            throw new SAXException("XTSE0090: Attribute 'exponent-separator' " +
                "on xsl:decimal-format is not allowed in XSLT " + compiler.maxProcessorVersion);
        }

        compiler.validateNotAVT("xsl:decimal-format", "name", name);

        if (name != null && name.contains(":")) {
            int colonIdx = name.indexOf(':');
            String prefix = name.substring(0, colonIdx);
            String localName = name.substring(colonIdx + 1);
            String nsUri = ctx.namespaceBindings.get(prefix);
            if (nsUri == null) {
                nsUri = compiler.lookupNamespaceUri(prefix);
            }
            if (nsUri != null && compiler.isReservedNamespace(nsUri)) {
                throw new SAXException("XTSE0080: Reserved namespace '" + nsUri +
                    "' cannot be used in the decimal-format name '" + name + "'");
            }
            if (nsUri != null) {
                name = "{" + nsUri + "}" + localName;
            }
        }

        CompilerUtils.validateSingleChar("decimal-separator", decimalSeparator);
        CompilerUtils.validateSingleChar("grouping-separator", groupingSeparator);
        CompilerUtils.validateSingleChar("minus-sign", minusSign);
        CompilerUtils.validateSingleChar("percent", percent);
        CompilerUtils.validateSingleChar("per-mille", perMille);
        CompilerUtils.validateSingleChar("zero-digit", zeroDigit);
        CompilerUtils.validateSingleChar("digit", digit);
        CompilerUtils.validateSingleChar("pattern-separator", patternSeparator);

        if (zeroDigit != null && zeroDigit.length() == 1) {
            int numericValue = Character.getNumericValue(zeroDigit.charAt(0));
            if (numericValue != 0) {
                throw new SAXException("XTSE1295: zero-digit character '" + zeroDigit +
                    "' does not have numeric value zero");
            }
        }

        compiler.builder.addDecimalFormat(name, decimalSeparator, groupingSeparator,
            infinity, minusSign, nan, percent, perMille, zeroDigit, digit, patternSeparator,
            exponentSeparator);
    }

    static void processTemplateElement(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        compiler.importsAllowed = false;
        String match = compiler.resolveStaticShadowAttribute(ctx, "match");
        if (match != null && containsAVT(match)) {
            throw new SAXException(
                "XTSE0340: Attribute value template is not " +
                "allowed in the match attribute of xsl:template");
        }
        String name = ctx.attributes.get("name");
        String mode = ctx.attributes.get("mode");
        String priorityStr = ctx.attributes.get("priority");
        String asType = ctx.attributes.get("as");

        SequenceType parsedAsType = null;
        if (asType != null && !asType.trim().isEmpty()) {
            final Map<String, String> nsBindings = ctx.namespaceBindings;
            parsedAsType = SequenceType.parse(asType.trim(), new java.util.function.Function<String, String>() {
                @Override
                public String apply(String prefix) {
                    return nsBindings.get(prefix);
                }
            });
        }

        boolean savedHasMatch = compiler.currentTemplateHasMatch;
        compiler.currentTemplateHasMatch = (match != null);

        String visibilityAttr = compiler.resolveStaticShadowAttribute(ctx, "visibility");
        ComponentVisibility visibility;
        if (compiler.isInsideOverride()) {
            visibility = ComponentVisibility.PUBLIC;
        } else if (compiler.packageName != null) {
            visibility = ComponentVisibility.PRIVATE;
        } else {
            visibility = ComponentVisibility.PUBLIC;
        }
        if (visibilityAttr != null && !visibilityAttr.isEmpty()) {
            try {
                visibility = ComponentVisibility.parse(visibilityAttr);
            } catch (IllegalArgumentException e) {
                throw new SAXException("XTSE0020: Invalid visibility value: " + visibilityAttr);
            }
        }

        if (mode == null && match != null) {
            String effectiveDefault = ctx.defaultMode;
            if (effectiveDefault != null && !"#unnamed".equals(effectiveDefault)) {
                mode = effectiveDefault;
            }
        }

        if (visibilityAttr == null && compiler.packageName != null && !compiler.isInsideOverride()
                && match != null) {
            String effectiveMode = mode;
            if (effectiveMode == null) {
                effectiveMode = ctx.defaultMode;
            }
            if (effectiveMode != null) {
                ModeDeclaration modeDecl = compiler.builder.getModeDeclaration(effectiveMode);
                if (modeDecl != null) {
                    ComponentVisibility modeVis = modeDecl.getComponentVisibility();
                    if (modeVis != null) {
                        visibility = modeVis;
                    }
                }
            }
        }

        if (match == null && name == null) {
            throw new SAXException("XTSE0500: xsl:template must have a match attribute or a name attribute, or both");
        }

        if (match == null) {
            if (mode != null) {
                throw new SAXException("XTSE0500: xsl:template with no match attribute cannot have a mode attribute");
            }
            if (priorityStr != null) {
                throw new SAXException("XTSE0500: xsl:template with no match attribute cannot have a priority attribute");
            }
        }

        if (mode != null) {
            String trimmedMode = mode.trim();
            if (trimmedMode.isEmpty()) {
                throw new SAXException("XTSE0550: mode attribute must not be empty");
            }
            String[] modeTokens = trimmedMode.split("\\s+");
            Set<String> seenModes = new HashSet<String>();
            boolean hasAll = false;
            for (int i = 0; i < modeTokens.length; i++) {
                String token = modeTokens[i];
                if (token.equals("#all")) {
                    hasAll = true;
                }
                if (!seenModes.add(token)) {
                    throw new SAXException("XTSE0550: mode attribute contains duplicate token '" + token + "'");
                }
            }
            if (hasAll && modeTokens.length > 1) {
                throw new SAXException("XTSE0550: #all cannot appear together with other mode values");
            }
        }

        List<String> expandedModes = new ArrayList<String>();
        if (mode != null) {
            String trimmedMode = mode.trim();
            int start = 0;
            int len = trimmedMode.length();
            while (start < len) {
                while (start < len && trimmedMode.charAt(start) <= ' ') {
                    start++;
                }
                if (start >= len) {
                    break;
                }
                int end = start;
                while (end < len && trimmedMode.charAt(end) > ' ') {
                    end++;
                }
                String token = trimmedMode.substring(start, end);
                String expandedToken = compiler.expandModeQName(token, true);
                expandedModes.add(expandedToken);
                if (!"#all".equals(token)) {
                    compiler.usedModeNames.add(expandedToken != null ? expandedToken : "#default");
                }
                start = end;
            }
        } else {
            expandedModes.add(null);
            if (match != null) {
                compiler.usedModeNames.add("#default");
            }
        }

        String expandedName = compiler.expandQName(name, true);

        Pattern pattern = null;
        if (match != null) {
            pattern = PatternValidator.compilePattern(compiler, match);
        }

        double priority = pattern != null ? pattern.getDefaultPriority() : 0.0;
        if (priorityStr != null) {
            CompilerUtils.validateDecimal(priorityStr, "xsl:template priority");
            try {
                priority = Double.parseDouble(priorityStr);
            } catch (NumberFormatException e) {
                throw new SAXException("XTSE0530: Invalid priority: " + priorityStr);
            }
        }

        List<TemplateParameter> params = new ArrayList<TemplateParameter>();
        List<XSLTNode> bodyNodes = new ArrayList<XSLTNode>();
        boolean foundNonParam = false;
        boolean foundContextItem = false;
        Set<String> seenParamNames = new HashSet<String>();

        for (XSLTNode child : ctx.children) {
            boolean isWhitespaceText = child instanceof LiteralText &&
                compiler.isWhitespace(((LiteralText) child).getText());

            if (child instanceof ParamNode) {
                if (foundNonParam) {
                    throw new SAXException("XTSE0010: xsl:param must come before any other content in template");
                }
                ParamNode pn = (ParamNode) child;
                String paramKey = StylesheetCompiler.expandedParamName(pn.getNamespaceURI(), pn.getLocalName());
                if (!seenParamNames.add(paramKey)) {
                    throw new SAXException("XTSE0580: Duplicate parameter name '" + pn.getName() + "' in xsl:template");
                }
                params.add(new TemplateParameter(pn.getNamespaceURI(), pn.getLocalName(), pn.getSelectExpr(), pn.getContent(), pn.isTunnel(), pn.isRequired(), pn.getAs()));
            } else if (child instanceof WithParamNode) {
                throw new SAXException("XTSE0010: xsl:with-param is not allowed directly in xsl:template");
            } else if (child instanceof SortSpecNode) {
                throw new SAXException("XTSE0010: xsl:sort is not allowed directly in xsl:template");
            } else if (child instanceof InstructionCompiler.ContextItemDeclaration) {
                if (foundNonParam || !params.isEmpty()) {
                    throw new SAXException("XTSE0010: xsl:context-item must appear before " +
                        "xsl:param and any other content in xsl:template");
                }
                if (foundContextItem) {
                    throw new SAXException("XTSE0010: Duplicate xsl:context-item in xsl:template");
                }
                foundContextItem = true;
                bodyNodes.add(child);
            } else {
                if (!isWhitespaceText) {
                    foundNonParam = true;
                }
                bodyNodes.add(child);
            }
        }

        XSLTNode body = new SequenceNode(bodyNodes);

        if (ctx.defaultCollation != null) {
            String parentCollation = compiler.builder.getDefaultCollation();
            if (!ctx.defaultCollation.equals(parentCollation)) {
                body = new CollationScopeNode(ctx.defaultCollation, body);
            }
        }

        double templateVersion = ctx.effectiveVersion > 0
            ? ctx.effectiveVersion : compiler.getEffectiveVersion();

        boolean inOverride = compiler.isInsideOverride();
        if (inOverride && compiler.pendingOverrideDeclarations == null) {
            compiler.pendingOverrideDeclarations = new ArrayList<OverrideDeclaration>();
        }

        if (inOverride && match != null) {
            if (mode != null) {
                String trimmedMode = mode.trim();
                String[] modeTokens = trimmedMode.split("\\s+");
                for (int i = 0; i < modeTokens.length; i++) {
                    String token = modeTokens[i];
                    if ("#all".equals(token) || "#unnamed".equals(token)) {
                        throw new SAXException("XTSE3440: Template rule in xsl:override " +
                            "must not use mode='" + token + "'");
                    }
                    if ("#default".equals(token)) {
                        String effectiveDefault = ctx.defaultMode;
                        if (effectiveDefault == null || "#unnamed".equals(effectiveDefault)) {
                            throw new SAXException("XTSE3440: Template rule in xsl:override " +
                                "must not use mode='#default' when the default mode is unnamed");
                        }
                    }
                }
            } else {
                String effectiveDefault = ctx.defaultMode;
                if (effectiveDefault == null || "#unnamed".equals(effectiveDefault)) {
                    throw new SAXException("XTSE3440: Template rule in xsl:override " +
                        "must not omit the mode attribute when the default mode is unnamed");
                }
            }
        }

        if (pattern instanceof UnionPattern && priorityStr == null) {
            Pattern[] alternatives =
                ((UnionPattern) pattern).getAlternatives();
            for (int mi = 0; mi < expandedModes.size(); mi++) {
                String expandedMode = expandedModes.get(mi);
                for (int pi = 0; pi < alternatives.length; pi++) {
                    Pattern p = alternatives[pi];
                    double rulePriority = p.getDefaultPriority();
                    String ruleName = (pi == 0) ? expandedName : null;
                    TemplateRule rule = new TemplateRule(p, ruleName,
                        expandedMode, rulePriority, compiler.importPrecedence,
                        compiler.nextTemplateIndex(), params, body, asType,
                        visibility);
                    rule.setParsedAsType(parsedAsType);
                    rule.setEffectiveVersion(templateVersion);
                    compiler.builder.addTemplateRule(rule);
                    if (inOverride && pi == 0) {
                        compiler.pendingOverrideDeclarations.add(
                            OverrideDeclaration.forTemplate(rule));
                    }
                }
            }
        } else {
            for (int i = 0; i < expandedModes.size(); i++) {
                String expandedMode = expandedModes.get(i);
                TemplateRule rule = new TemplateRule(pattern, expandedName,
                    expandedMode, priority, compiler.importPrecedence,
                    compiler.nextTemplateIndex(), params, body, asType, visibility);
                rule.setParsedAsType(parsedAsType);
                rule.setEffectiveVersion(templateVersion);
                compiler.builder.addTemplateRule(rule);
                if (inOverride) {
                    compiler.pendingOverrideDeclarations.add(
                        OverrideDeclaration.forTemplate(rule));
                }
            }
        }
        compiler.currentTemplateHasMatch = savedHasMatch;
    }

    /**
     * XTSE3085: Validates that all used modes are explicitly declared when declared-modes is enabled.
     */
    static void validateDeclaredModes(StylesheetCompiler compiler, CompiledStylesheet stylesheet)
            throws SAXException {
        if (!compiler.declaredModesEnabled) {
            return;
        }
        Map<String, ModeDeclaration> declaredModes = stylesheet.getModeDeclarations();
        for (String usedMode : compiler.usedModeNames) {
            if (!declaredModes.containsKey(usedMode)) {
                String displayName = "#default".equals(usedMode) ? "the unnamed mode" : "mode '" + usedMode + "'";
                throw new SAXException("XTSE3085: " + displayName
                    + " is used but not declared (declared-modes is enabled)");
            }
        }
    }

    /**
     * XTSE3080: An executable (top-level) package must not contain abstract components.
     * Abstract components can only appear in library packages intended to be used
     * via xsl:use-package with overrides.
     */
    static void validateNoAbstractComponents(StylesheetCompiler compiler, CompiledStylesheet stylesheet)
            throws SAXException {
        if (!compiler.isPrincipalStylesheet) {
            return;
        }
        for (TemplateRule rule : stylesheet.getTemplateRules()) {
            if (rule.getVisibility() == ComponentVisibility.ABSTRACT) {
                String name = rule.getName();
                if (name == null) {
                    name = "(match template)";
                }
                throw new SAXException("XTSE3080: Abstract template '" + name +
                    "' found in executable package");
            }
        }
        for (UserFunction func : stylesheet.getUserFunctions().values()) {
            if (func.getVisibility() == ComponentVisibility.ABSTRACT) {
                throw new SAXException("XTSE3080: Abstract function '" +
                    func.getLocalName() + "' found in executable package");
            }
        }
    }

    /**
     * XTSE3010/XTSE3020: Validates xsl:expose declarations against the stylesheet components.
     *
     * <p>XTSE3010: The exposed visibility must be compatible with the component's
     * potential visibility (e.g., cannot make a private component public).
     *
     * <p>XTSE3020: A non-wildcard name in xsl:expose must match at least one component.
     */
    static void validateExposeDeclarations(StylesheetCompiler compiler, CompiledStylesheet stylesheet)
            throws SAXException {
        if (compiler.exposeDeclarations.isEmpty()) {
            return;
        }

        Set<String> templateNames = new HashSet<>();
        for (TemplateRule rule : stylesheet.getTemplateRules()) {
            if (rule.getName() != null) {
                templateNames.add(rule.getName());
            }
        }

        Set<String> functionNames = new HashSet<>(stylesheet.getUserFunctions().keySet());

        for (ExposeDeclaration expose : compiler.exposeDeclarations) {
            String namesPattern = expose.namesPattern;
            boolean isWildcard = "*".equals(namesPattern);

            if (!isWildcard) {
                boolean matched = false;
                String[] names = namesPattern.trim().split("\\s+");
                for (String name : names) {
                    if ("*".equals(name)) {
                        matched = true;
                        continue;
                    }
                    boolean nameMatched = false;
                    if (expose.componentType == AcceptDeclaration.ComponentType.TEMPLATE
                            || expose.componentType == AcceptDeclaration.ComponentType.ALL) {
                        if (templateNames.contains(name)) {
                            nameMatched = true;
                        }
                    }
                    if (expose.componentType == AcceptDeclaration.ComponentType.FUNCTION
                            || expose.componentType == AcceptDeclaration.ComponentType.ALL) {
                        if (functionNames.contains(name)) {
                            nameMatched = true;
                        }
                    }
                    if (nameMatched) {
                        matched = true;
                        validateExposeCompatibility(stylesheet, expose.componentType,
                            name, expose.visibility);
                    }
                }
                if (!matched) {
                    throw new SAXException("XTSE3020: xsl:expose matches no " +
                        expose.componentType + " components with names '" + namesPattern + "'");
                }
            }
        }
    }

    /**
     * XTSE3010: Validates that the exposed visibility is compatible with the
     * component's declared (potential) visibility.
     */
    static void validateExposeCompatibility(CompiledStylesheet stylesheet,
            AcceptDeclaration.ComponentType componentType, String name,
            ComponentVisibility exposed) throws SAXException {
        ComponentVisibility declared = null;
        if (componentType == AcceptDeclaration.ComponentType.TEMPLATE
                || componentType == AcceptDeclaration.ComponentType.ALL) {
            for (TemplateRule rule : stylesheet.getTemplateRules()) {
                if (name.equals(rule.getName())) {
                    declared = rule.getVisibility();
                    break;
                }
            }
        }
        if (declared == null) {
            return;
        }
        if (declared == ComponentVisibility.PRIVATE && exposed == ComponentVisibility.PUBLIC) {
            throw new SAXException("XTSE3010: Cannot expose private template '" + name +
                "' as public");
        }
        if (declared == ComponentVisibility.PRIVATE && exposed == ComponentVisibility.FINAL) {
            throw new SAXException("XTSE3010: Cannot expose private template '" + name +
                "' as final");
        }
        if (declared == ComponentVisibility.PRIVATE && exposed == ComponentVisibility.ABSTRACT) {
            throw new SAXException("XTSE3010: Cannot expose private template '" + name +
                "' as abstract");
        }
    }

    /**
     * Validates that all abstract components from used packages are properly overridden.
     *
     * <p>Per XSLT 3.0, a package with unimplemented abstract components cannot be
     * used directly as a stylesheet (XTSE3010).
     *
     * @param pkg the compiled package to validate
     * @throws SAXException if abstract components are not overridden
     */
    static void validateAbstractComponents(CompiledPackage pkg) throws SAXException {
        for (CompiledPackage.PackageDependency dep : pkg.getDependencies()) {
            CompiledPackage usedPkg = dep.getResolvedPackage();
            if (usedPkg == null) {
                continue;
            }

            List<OverrideDeclaration> overrides = dep.getOverrideDeclarations();
            Set<String> overriddenKeys = new HashSet<>();
            for (OverrideDeclaration override : overrides) {
                overriddenKeys.add(override.getOriginalComponentKey());
            }

            for (TemplateRule template : usedPkg.getAbstractTemplates()) {
                String key = template.getName() != null ? template.getName() :
                    (template.getMatchPattern() != null ? template.getMatchPattern().toString() : "");
                if (!overriddenKeys.contains(key)) {
                    throw new SAXException("XTSE3010: Abstract template '" + key +
                        "' from package '" + usedPkg.getPackageName() +
                        "' must be overridden");
                }
            }

            for (UserFunction function : usedPkg.getAbstractFunctions()) {
                String key = function.getKey();
                if (!overriddenKeys.contains(key)) {
                    throw new SAXException("XTSE3010: Abstract function '" +
                        function.getNamespaceURI() + ":" + function.getLocalName() +
                        "' from package '" + usedPkg.getPackageName() +
                        "' must be overridden");
                }
            }

            for (GlobalVariable variable : usedPkg.getAbstractVariables()) {
                String key = variable.getExpandedName();
                if (!overriddenKeys.contains(key)) {
                    throw new SAXException("XTSE3010: Abstract " +
                        (variable.isParam() ? "parameter" : "variable") + " '" + key +
                        "' from package '" + usedPkg.getPackageName() +
                        "' must be overridden");
                }
            }
        }
    }
}

