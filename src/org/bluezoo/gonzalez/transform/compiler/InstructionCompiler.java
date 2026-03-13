/*
 * InstructionCompiler.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.QName;
import org.bluezoo.gonzalez.transform.ast.AnalyzeStringNode;
import org.bluezoo.gonzalez.transform.ast.ApplyImportsNode;
import org.bluezoo.gonzalez.transform.ast.ApplyTemplatesNode;
import org.bluezoo.gonzalez.transform.ast.AssertNode;
import org.bluezoo.gonzalez.transform.ast.AttributeNode;
import org.bluezoo.gonzalez.transform.ast.BreakNode;
import org.bluezoo.gonzalez.transform.ast.CallTemplateNode;
import org.bluezoo.gonzalez.transform.ast.CatchNode;
import org.bluezoo.gonzalez.transform.ast.ChooseNode;
import org.bluezoo.gonzalez.transform.ast.CollationScopeNode;
import org.bluezoo.gonzalez.transform.ast.CommentNode;
import org.bluezoo.gonzalez.transform.ast.CopyNode;
import org.bluezoo.gonzalez.transform.ast.CopyOfNode;
import org.bluezoo.gonzalez.transform.ast.DocumentConstructorNode;
import org.bluezoo.gonzalez.transform.ast.DynamicValueOfNode;
import org.bluezoo.gonzalez.transform.ast.ElementNode;
import org.bluezoo.gonzalez.transform.ast.EvaluateNode;
import org.bluezoo.gonzalez.transform.ast.FallbackNode;
import org.bluezoo.gonzalez.transform.ast.ForEachGroupNode;
import org.bluezoo.gonzalez.transform.ast.ForEachNode;
import org.bluezoo.gonzalez.transform.ast.ForkNode;
import org.bluezoo.gonzalez.transform.ast.IfNode;
import org.bluezoo.gonzalez.transform.ast.IterateNode;
import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.MapEntryNode;
import org.bluezoo.gonzalez.transform.ast.MergeNode;
import org.bluezoo.gonzalez.transform.ast.MessageNode;
import org.bluezoo.gonzalez.transform.ast.NamespaceInstructionNode;
import org.bluezoo.gonzalez.transform.ast.NextIterationNode;
import org.bluezoo.gonzalez.transform.ast.NextMatchNode;
import org.bluezoo.gonzalez.transform.ast.NumberNode;
import org.bluezoo.gonzalez.transform.ast.OnEmptyNode;
import org.bluezoo.gonzalez.transform.ast.OnNonEmptyNode;
import org.bluezoo.gonzalez.transform.ast.OtherwiseNode;
import org.bluezoo.gonzalez.transform.ast.ParamNode;
import org.bluezoo.gonzalez.transform.ast.PerformSortNode;
import org.bluezoo.gonzalez.transform.ast.ProcessingInstructionNode;
import org.bluezoo.gonzalez.transform.ast.ResultDocumentNode;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.SequenceOutputNode;
import org.bluezoo.gonzalez.transform.ast.SortSpecNode;
import org.bluezoo.gonzalez.transform.ast.SourceDocumentNode;
import org.bluezoo.gonzalez.transform.ast.StreamNode;
import org.bluezoo.gonzalez.transform.ast.TryNode;
import org.bluezoo.gonzalez.transform.ast.ValueOfContentNode;
import org.bluezoo.gonzalez.transform.ast.ValueOfNode;
import org.bluezoo.gonzalez.transform.ast.VariableNode;
import org.bluezoo.gonzalez.transform.ast.WhenNode;
import org.bluezoo.gonzalez.transform.ast.WherePopulatedNode;
import org.bluezoo.gonzalez.transform.ast.WithParamNode;
import org.bluezoo.gonzalez.transform.ast.XSLTInstruction;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Compiles XSLT instruction elements. Extracted from StylesheetCompiler.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class InstructionCompiler {

    private InstructionCompiler() {
    }

    static XSLTNode compileValueOf(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        String doeAttr = ctx.attributes.get("disable-output-escaping");
        compiler.validateYesOrNo("xsl:value-of", "disable-output-escaping", doeAttr);
        boolean disableEscaping = "yes".equals(doeAttr);
        String separatorStr = ctx.attributes.get("separator");
        AttributeValueTemplate separatorAvt = null;
        if (separatorStr != null) {
            try {
                separatorAvt = AttributeValueTemplate.parse(separatorStr, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in separator: " + e.getMessage(), e);
            }
        }
        double effectiveVer = ctx.effectiveVersion > 0 ? ctx.effectiveVersion : compiler.getEffectiveVersion();
        boolean xslt2Plus = effectiveVer >= 2.0;
        boolean stylesheetIs2Plus = compiler.stylesheetVersion >= 2.0;

        if (ctx.hasShadowAttribute("select")) {
            String shadowSelect = ctx.shadowAttributes.get("select");
            AttributeValueTemplate selectAvt = compiler.parseAvt(shadowSelect);
            return new DynamicValueOfNode(
                selectAvt, disableEscaping, separatorStr);
        }

        if (select != null) {
            if (!ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE0870: xsl:value-of must not have both " +
                    "a select attribute and content");
            }
            return new ValueOfNode(compiler.compileExpression(select), disableEscaping,
                separatorAvt, xslt2Plus);
        }

        if (stylesheetIs2Plus && !ctx.children.isEmpty()) {
            List<XSLTNode> contentNodes = compiler.filterFallback(ctx.children);
            if (!contentNodes.isEmpty()) {
                XSLTNode content;
                if (contentNodes.size() == 1) {
                    content = contentNodes.get(0);
                } else {
                    content = new SequenceNode(contentNodes);
                }
                return new ValueOfContentNode(content, disableEscaping, separatorStr);
            }
        }

        if (compiler.forwardCompatible && !ctx.children.isEmpty()) {
            List<XSLTNode> contentNodes = compiler.filterFallback(ctx.children);
            if (!contentNodes.isEmpty()) {
                XSLTNode content;
                if (contentNodes.size() == 1) {
                    content = contentNodes.get(0);
                } else {
                    content = new SequenceNode(contentNodes);
                }
                return new ValueOfContentNode(content, disableEscaping, separatorStr);
            }
        }

        if (compiler.maxProcessorVersion < 3.0) {
            throw new SAXException("XTSE0870: xsl:value-of must have either " +
                "a select attribute or a sequence constructor");
        }
        return new ValueOfNode(compiler.compileExpression("''"), disableEscaping,
            separatorAvt, true);
    }

    static XSLTNode compileText(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String doeTextAttr = ctx.attributes.get("disable-output-escaping");
        compiler.validateYesOrNo("xsl:text", "disable-output-escaping", doeTextAttr);
        boolean disableEscaping = "yes".equals(doeTextAttr);

        boolean hasTVT = false;
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText) {
                continue;
            }
            if (child instanceof ValueOfNode) {
                hasTVT = true;
                continue;
            }
            if (child instanceof SequenceNode) {
                hasTVT = true;
                continue;
            }
            throw new SAXException("XTSE0010: The content of xsl:text must be text only; " +
                "XSLT instructions are not allowed inside xsl:text");
        }

        if (hasTVT) {
            XSLTNode contentNode;
            if (ctx.children.size() == 1) {
                contentNode = ctx.children.get(0);
            } else {
                contentNode = new SequenceNode(new ArrayList<>(ctx.children));
            }
            return new ValueOfContentNode(contentNode, disableEscaping, null);
        }

        StringBuilder text = new StringBuilder();
        for (XSLTNode child : ctx.children) {
            LiteralText lt = (LiteralText) child;
            if (lt.isFromXslText() && compiler.stylesheetVersion >= 2.0) {
                throw new SAXException("XTSE0010: Nested xsl:text elements are not allowed");
            }
            text.append(lt.getText());
        }
        return new LiteralText(text.toString(), disableEscaping, true);
    }

    static XSLTNode compileElement2(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String namespace = ctx.attributes.get("namespace");
        String useAttrSetsRaw = ctx.attributes.get("use-attribute-sets");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        String inheritNsValue = ctx.attributes.get("inherit-namespaces");

        String useAttrSets = compiler.expandAttributeSetNames(useAttrSetsRaw, ctx.namespaceBindings);

        if (useAttrSets != null && !useAttrSets.isEmpty()) {
            compiler.builder.registerAttributeSetReferences(useAttrSets);
        }

        if (name == null || name.isEmpty()) {
            throw new SAXException("xsl:element requires a non-empty name attribute");
        }

        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:element cannot have both type and validation attributes");
        }

        if (typeValue != null && !typeValue.isEmpty()) {
            throw new SAXException("XTSE1660: xsl:element/@type requires a schema-aware processor");
        }

        AttributeValueTemplate nameAvt = compiler.parseAvt(name);
        AttributeValueTemplate nsAvt = namespace != null ? compiler.parseAvt(namespace) : null;

        ValidationMode validation = compiler.parseValidationMode(validationValue, "xsl:element");

        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:element/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }

        String defaultNs = ctx.namespaceBindings.get("");
        Map<String, String> nsBindings = new HashMap<>(ctx.namespaceBindings);

        boolean inheritNs = true;
        if (inheritNsValue != null && !inheritNsValue.isEmpty()) {
            compiler.validateYesOrNo("xsl:element", "inherit-namespaces", inheritNsValue);
            inheritNs = compiler.parseYesOrNo(inheritNsValue);
        }

        return new ElementNode(nameAvt, nsAvt, useAttrSets, new SequenceNode(ctx.children),
                               defaultNs, nsBindings, typeNamespaceURI, typeLocalName, validation,
                               null, null, inheritNs);
    }

    static XSLTNode compileAttribute(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: Required attribute 'name' is missing on xsl:attribute");
        }
        String namespace = ctx.attributes.get("namespace");
        String select = ctx.attributes.get("select");
        String separatorRaw = ctx.attributes.get("separator");
        AttributeValueTemplate separatorAttrAvt = null;
        if (separatorRaw != null) {
            try {
                separatorAttrAvt = AttributeValueTemplate.parse(separatorRaw, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid AVT in separator: " + e.getMessage(), e);
            }
        }
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");

        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:attribute cannot have both type and validation attributes");
        }

        AttributeValueTemplate nameAvt = compiler.parseAvt(name);
        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        AttributeValueTemplate nsAvt = namespace != null ? compiler.parseAvt(namespace) : null;

        ValidationMode validation = compiler.parseValidationMode(validationValue, "xsl:attribute");

        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:attribute/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }

        Map<String, String> nsBindings = new HashMap<>(ctx.namespaceBindings);

        if (selectExpr != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0840: xsl:attribute must not have both " +
                "a select attribute and content");
        }
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);

        return new AttributeNode(nameAvt, nsAvt, selectExpr, separatorAttrAvt, content, nsBindings,
                                 typeNamespaceURI, typeLocalName, validation);
    }

    static XSLTNode compileNamespace(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");

        AttributeValueTemplate nameAvt = compiler.parseAvt(name != null ? name : "");
        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        if (selectExpr != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0910: xsl:namespace must not have both " +
                "a select attribute and content");
        }

        return new NamespaceInstructionNode(nameAvt, selectExpr, new SequenceNode(ctx.children));
    }

    static XSLTNode compileComment(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0940: xsl:comment must not have both " +
                "a select attribute and content");
        }
        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        return new CommentNode(selectExpr, content);
    }

    static XSLTNode compilePI(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");

        AttributeValueTemplate nameAvt = compiler.parseAvt(name);
        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        if (selectExpr != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0880: xsl:processing-instruction must not have both " +
                "a select attribute and content");
        }
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);

        return new ProcessingInstructionNode(nameAvt, selectExpr, content);
    }

    static XSLTNode compileCopy(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectValue = ctx.attributes.get("select");
        if (selectValue == null) {
            for (StylesheetCompiler.ElementContext ancestor : compiler.elementStack) {
                String ancestorLocal = ancestor.localName;
                if (!StylesheetCompiler.XSLT_NS.equals(ancestor.namespaceURI)) {
                    continue;
                }
                if ("for-each".equals(ancestorLocal) ||
                        "for-each-group".equals(ancestorLocal) ||
                        "iterate".equals(ancestorLocal)) {
                    break;
                }
                if ("function".equals(ancestorLocal)) {
                    throw new SAXException("XTTE0945: xsl:copy with no select attribute " +
                        "is not allowed inside xsl:function (no context item)");
                }
            }
        }

        String useAttrSetsRaw = ctx.attributes.get("use-attribute-sets");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        String inheritNamespacesValue = ctx.attributes.get("inherit-namespaces");
        String copyNamespacesValue = ctx.attributes.get("copy-namespaces");

        XPathExpression selectExpr = selectValue != null ? compiler.compileExpression(selectValue) : null;

        String useAttrSets = compiler.expandAttributeSetNames(useAttrSetsRaw, ctx.namespaceBindings);

        if (useAttrSets != null && !useAttrSets.isEmpty()) {
            compiler.builder.registerAttributeSetReferences(useAttrSets);
        }

        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:copy cannot have both type and validation attributes");
        }

        ValidationMode validation = compiler.parseValidationMode(validationValue, "xsl:copy");

        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:copy/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }

        String shadowInheritNamespaces = ctx.shadowAttributes.get("inherit-namespaces");
        AttributeValueTemplate inheritNamespacesAvt = null;

        boolean inheritNs = true;
        if (shadowInheritNamespaces != null) {
            inheritNamespacesAvt = compiler.parseAvt(shadowInheritNamespaces);
        } else if (inheritNamespacesValue != null && !inheritNamespacesValue.isEmpty()) {
            compiler.validateYesOrNo("xsl:copy", "inherit-namespaces", inheritNamespacesValue);
            inheritNs = compiler.parseYesOrNo(inheritNamespacesValue);
        }

        String shadowCopyNamespaces = ctx.shadowAttributes.get("copy-namespaces");
        AttributeValueTemplate copyNamespacesAvt = null;

        boolean copyNs = true;
        if (shadowCopyNamespaces != null) {
            copyNamespacesAvt = compiler.parseAvt(shadowCopyNamespaces);
        } else if (copyNamespacesValue != null && !copyNamespacesValue.isEmpty()) {
            compiler.validateYesOrNo("xsl:copy", "copy-namespaces", copyNamespacesValue);
            copyNs = compiler.parseYesOrNo(copyNamespacesValue);
        }

        XSLTNode onEmptyNode = null;
        List<XSLTNode> regularContent = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof org.bluezoo.gonzalez.transform.ast.OnEmptyNode) {
                onEmptyNode = child;
            } else {
                regularContent.add(child);
            }
        }

        return new CopyNode(selectExpr, useAttrSets, new SequenceNode(regularContent),
                           typeNamespaceURI, typeLocalName, validation,
                           inheritNs, inheritNamespacesAvt, copyNs, copyNamespacesAvt, onEmptyNode);
    }

    static XSLTNode compileCopyOf(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");
        String copyNamespaces = ctx.attributes.get("copy-namespaces");
        String copyAccumulatorsAttr = compiler.resolveStaticShadowAttribute(ctx, "copy-accumulators");

        if (select == null) {
            throw new SAXException("xsl:copy-of requires select attribute");
        }

        if (!ctx.children.isEmpty()) {
            throw new SAXException("xsl:copy-of must be empty; content is not allowed");
        }

        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:copy-of cannot have both type and validation attributes");
        }

        ValidationMode validation;
        try {
            validation = compiler.parseValidationMode(validationValue, "xsl:copy-of");
        } catch (SAXException e) {
            if (compiler.stylesheetVersion >= 3.0
                    && "strict".equals(validationValue != null ? validationValue.trim() : null)) {
                validation = ValidationMode.STRIP;
            } else {
                throw e;
            }
        }

        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:copy-of/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }

        String shadowCopyNamespaces = ctx.shadowAttributes.get("copy-namespaces");
        AttributeValueTemplate copyNamespacesAvt = null;

        boolean copyNs = true;
        if (shadowCopyNamespaces != null) {
            copyNamespacesAvt = compiler.parseAvt(shadowCopyNamespaces);
        } else if (copyNamespaces != null && !copyNamespaces.isEmpty()) {
            compiler.validateYesOrNo("xsl:copy-of", "copy-namespaces", copyNamespaces);
            copyNs = compiler.parseYesOrNo(copyNamespaces);
        }

        boolean copyAccumulators = compiler.parseYesOrNo(copyAccumulatorsAttr);

        return new CopyOfNode(compiler.compileExpression(select), typeNamespaceURI, typeLocalName,
                              validation, copyNs, copyNamespacesAvt, copyAccumulators);
    }

    static XSLTNode compileSequence(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        boolean xslt3Plus = compiler.maxProcessorVersion >= 3.0;
        if (select != null) {
            if (!ctx.children.isEmpty() && compiler.hasNonFallbackContent(ctx.children)) {
                throw new SAXException("XTSE3185: xsl:sequence must not have both " +
                    "a select attribute and children other than xsl:fallback");
            }
            return new SequenceOutputNode(compiler.compileExpression(select));
        } else if (xslt3Plus) {
            return new SequenceNode(new ArrayList<>(ctx.children));
        } else {
            boolean hasContent = !ctx.children.isEmpty() && compiler.hasNonFallbackContent(ctx.children);
            if (hasContent) {
                throw new SAXException("XTSE0010: xsl:sequence must not have content " +
                    "in XSLT 2.0 (select attribute is required)");
            }
            throw new SAXException("XTSE0010: xsl:sequence requires a select attribute " +
                "in XSLT 2.0");
        }
    }

    static XSLTNode compileApplyTemplates(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        if (compiler.isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:apply-templates is not allowed at the top level");
        }

        String select = ctx.attributes.get("select");
        String mode = ctx.attributes.get("mode");

        compiler.validateNotAVT("xsl:apply-templates", "mode", mode);

        if (mode == null || "#default".equals(mode)) {
            String effectiveDefaultMode = compiler.getEffectiveDefaultMode();
            if (effectiveDefaultMode != null && !"#unnamed".equals(effectiveDefaultMode)) {
                mode = effectiveDefaultMode;
            } else {
                mode = null;
            }
        }

        String expandedMode = compiler.expandModeQName(mode);

        if (mode != null && !"#current".equals(mode)) {
            compiler.usedModeNames.add(expandedMode != null ? expandedMode : "#default");
        } else if (mode == null) {
            compiler.usedModeNames.add("#default");
        }

        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;

        List<SortSpec> sorts = new ArrayList<>();
        List<WithParamNode> params = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();

        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText && compiler.isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof SortSpecNode) {
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (child instanceof WithParamNode) {
                WithParamNode param = (WithParamNode) child;
                String paramName = param.getName();
                if (!seenParamNames.add(paramName)) {
                    throw new SAXException("XTSE0670: Duplicate parameter name '" + paramName +
                        "' in xsl:apply-templates");
                }
                params.add(param);
            } else {
                throw new SAXException("XTSE0010: Only xsl:sort and xsl:with-param are allowed in xsl:apply-templates");
            }
        }

        validateSortStable(sorts);
        boolean backCompat = ctx.effectiveVersion > 0
            && ctx.effectiveVersion < 2.0;
        return new ApplyTemplatesNode(selectExpr, expandedMode, sorts, params,
                                      backCompat);
    }

    static XSLTNode compileCallTemplate(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        if (compiler.isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:call-template is not allowed at the top level");
        }

        String name = ctx.attributes.get("name");
        if (name == null) {
            throw new SAXException("XTSE0010: xsl:call-template requires name attribute");
        }

        String expandedName = compiler.expandQName(name);

        List<WithParamNode> params = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText && compiler.isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof WithParamNode) {
                WithParamNode param = (WithParamNode) child;
                String paramName = param.getName();
                if (!seenParamNames.add(paramName)) {
                    throw new SAXException("XTSE0670: Duplicate parameter name '" + paramName +
                        "' in xsl:call-template");
                }
                params.add(param);
            } else {
                throw new SAXException("XTSE0010: Only xsl:with-param is allowed in xsl:call-template");
            }
        }

        return new CallTemplateNode(expandedName, params);
    }

    static XSLTNode compileApplyImports(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        if (compiler.isInsideOverride()) {
            throw new SAXException("XTSE3460: xsl:apply-imports is not allowed in a " +
                "template declared within xsl:override (use xsl:next-match instead)");
        }
        List<WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof LiteralText && compiler.isWhitespace(((LiteralText) child).getText())) {
                continue;
            }
            if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            } else {
                throw new SAXException("XTSE0010: Only xsl:with-param is allowed in xsl:apply-imports");
            }
        }
        return new ApplyImportsNode(params);
    }

    static XSLTNode compileNextMatch(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        List<WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                params.add((WithParamNode) child);
            } else if (child instanceof FallbackNode) {
            }
        }
        return new NextMatchNode(params);
    }

    static XSLTNode compileForEach(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select == null) {
            throw new SAXException("XTSE0010: xsl:for-each requires select attribute");
        }

        int lastSortIndex = -1;
        for (int i = 0; i < ctx.children.size(); i++) {
            if (ctx.children.get(i) instanceof SortSpecNode) {
                lastSortIndex = i;
            }
        }

        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonSort = false;

        for (int i = 0; i < ctx.children.size(); i++) {
            XSLTNode child = ctx.children.get(i);

            boolean isStrippableWhitespace = child instanceof LiteralText &&
                compiler.isWhitespace(((LiteralText) child).getText()) &&
                !((LiteralText) child).isFromXslText();

            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (i > lastSortIndex) {
                bodyNodes.add(child);
                if (!isStrippableWhitespace) {
                    foundNonSort = true;
                }
            } else if (!isStrippableWhitespace) {
                if (lastSortIndex >= 0) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each");
                }
                foundNonSort = true;
                bodyNodes.add(child);
            }
        }

        validateSortStable(sorts);
        return new ForEachNode(compiler.compileExpression(select), sorts, new SequenceNode(bodyNodes));
    }

    static XSLTNode compileStream(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String href = ctx.attributes.get("href");
        if (href == null || href.isEmpty()) {
            throw new SAXException("xsl:stream requires href attribute");
        }

        XSLTNode body;
        if (ctx.children.isEmpty()) {
            body = new SequenceNode(new ArrayList<>());
        } else if (ctx.children.size() == 1) {
            body = ctx.children.get(0);
        } else {
            body = new SequenceNode(new ArrayList<>(ctx.children));
        }

        return new StreamNode(href, body);
    }

    static XSLTNode compileMapEntry(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String keyAttr = ctx.attributes.get("key");
        if (keyAttr == null || keyAttr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:map-entry requires a 'key' attribute");
        }
        XPathExpression keyExpr = compiler.compileExpression(keyAttr);
        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectAttr != null && !selectAttr.isEmpty()) {
            selectExpr = compiler.compileExpression(selectAttr);
        }
        boolean hasContent = ctx.children != null && !ctx.children.isEmpty();
        if (selectExpr != null && hasContent) {
            throw new SAXException("XTSE3280: xsl:map-entry must not have both select attribute and content");
        }
        SequenceNode content = new SequenceNode(ctx.children);
        return new MapEntryNode(keyExpr, selectExpr, content);
    }

    static XSLTNode compileDocumentConstructor(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String validationValue = ctx.attributes.get("validation");
        ValidationMode validation = compiler.parseValidationMode(validationValue, "xsl:document");

        String typeValue = ctx.attributes.get("type");
        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            if (validationValue != null) {
                throw new SAXException("XTSE1505: xsl:document cannot have both type and validation attributes");
            }
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String prefix = typeValue.substring(0, colonPos);
                typeNamespaceURI = ctx.namespaceBindings.get(prefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Undeclared prefix in type attribute: " + prefix);
                }
                typeLocalName = typeValue.substring(colonPos + 1);
            } else {
                if (typeValue.startsWith("{")) {
                    int closePos = typeValue.indexOf('}');
                    if (closePos > 0) {
                        typeNamespaceURI = typeValue.substring(1, closePos);
                        typeLocalName = typeValue.substring(closePos + 1);
                    }
                } else {
                    typeLocalName = typeValue;
                }
            }
        }

        return new DocumentConstructorNode(ctx.children, validation, typeNamespaceURI, typeLocalName);
    }

    static void collectAttributeSetReferences(
            XSLTNode node, List<String> refs, int depth) {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof CopyNode) {
            String uas = ((CopyNode) node).getUseAttributeSetsString();
            if (uas != null && !uas.isEmpty()) {
                StringTokenizer st = new StringTokenizer(uas);
                while (st.hasMoreTokens()) {
                    refs.add(st.nextToken());
                }
            }
            collectAttributeSetReferences(
                ((CopyNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof ElementNode) {
            String uas = ((ElementNode) node).getUseAttributeSetsString();
            if (uas != null && !uas.isEmpty()) {
                StringTokenizer st = new StringTokenizer(uas);
                while (st.hasMoreTokens()) {
                    refs.add(st.nextToken());
                }
            }
            collectAttributeSetReferences(
                ((ElementNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            List<String> uas =
                ((LiteralResultElement) node).getUseAttributeSets();
            if (uas != null) {
                refs.addAll(uas);
            }
            collectAttributeSetReferences(
                ((LiteralResultElement) node).getContent(), refs,
                depth + 1);
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    collectAttributeSetReferences(
                        children.get(i), refs, depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            collectAttributeSetReferences(
                ((ForEachNode) node).getBody(), refs, depth + 1);
        }
        if (node instanceof ForEachGroupNode) {
            collectAttributeSetReferences(
                ((ForEachGroupNode) node).getBody(), refs, depth + 1);
        }
        if (node instanceof IfNode) {
            collectAttributeSetReferences(
                ((IfNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                collectAttributeSetReferences(when, refs, depth + 1);
            }
            collectAttributeSetReferences(
                choose.getOtherwise(), refs, depth + 1);
        }
        if (node instanceof WhenNode) {
            collectAttributeSetReferences(
                ((WhenNode) node).getContent(), refs, depth + 1);
        }
        if (node instanceof ForkNode) {
            ForkNode fork = (ForkNode) node;
            List<ForkNode.ForkBranch> branches = fork.getBranches();
            for (int i = 0; i < branches.size(); i++) {
                ForkNode.ForkBranch branch = branches.get(i);
                if (branch.getContent() != null) {
                    collectAttributeSetReferences(
                        branch.getContent(), refs, depth + 1);
                }
            }
        }
    }

    static XSLTNode compileSourceDocument(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String hrefStr = ctx.attributes.get("href");
        if (hrefStr == null || hrefStr.isEmpty()) {
            throw new SAXException("XTSE3085: xsl:source-document requires href attribute");
        }

        AttributeValueTemplate hrefAvt = compiler.parseAvt(hrefStr);

        String streamableStr = ctx.attributes.get("streamable");
        if (streamableStr == null) {
            streamableStr = ctx.attributes.get("_streamable");
        }
        boolean streamable = !"no".equals(streamableStr) && !"false".equals(streamableStr);

        String validation = ctx.attributes.get("validation");
        if (validation != null && !validation.isEmpty()) {
            validation = validation.trim();
            if (!validation.equals("strict") && !validation.equals("lax")
                    && !validation.equals("preserve") && !validation.equals("strip")) {
                throw new SAXException("Invalid validation value on xsl:source-document: " + validation);
            }
        }

        String typeStr = ctx.attributes.get("type");
        if (typeStr != null && validation != null) {
            throw new SAXException("XTSE1505: xsl:source-document cannot have both type and validation attributes");
        }

        String useAccumulators = ctx.attributes.get("use-accumulators");

        List<XSLTNode> bodyNodes = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (!(child.toString().contains("fallback"))) {
                bodyNodes.add(child);
            }
        }

        XSLTNode body;
        if (bodyNodes.isEmpty()) {
            body = null;
        } else if (bodyNodes.size() == 1) {
            body = bodyNodes.get(0);
        } else {
            body = new SequenceNode(bodyNodes);
        }

        if (streamable && body != null) {
            StreamabilityAnalyzer bodyAnalyzer = new StreamabilityAnalyzer();
            List<String> reasons = new ArrayList<String>();
            StreamabilityAnalyzer.ExpressionStreamability bodyStreamability =
                bodyAnalyzer.analyzeNode(body, reasons);
            boolean freeRanging = (bodyStreamability ==
                StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING);
            if (freeRanging) {
                StringBuilder sb = new StringBuilder();
                sb.append("XTSE3430: Body of xsl:source-document");
                sb.append(" with streamable='yes' is not streamable");
                if (!reasons.isEmpty()) {
                    sb.append(": ");
                    sb.append(reasons.get(0));
                }
                throw new SAXException(sb.toString());
            }

            List<XPathExpression> bodyExprs = bodyAnalyzer.collectBodyExpressions(body);
            for (int i = 0; i < bodyExprs.size(); i++) {
                if (StreamingClassifier.hasNonForkableMultiConsuming(bodyExprs.get(i))) {
                    throw new SAXException("XTSE3430: Body of xsl:source-document"
                        + " with streamable='yes' is not streamable: expression has"
                        + " multiple consuming operands in a non-forkable operator");
                }
            }

            List<String> attrSetRefs = new ArrayList<String>();
            collectAttributeSetReferences(body, attrSetRefs, 0);
            for (int i = 0; i < attrSetRefs.size(); i++) {
                String refName = attrSetRefs.get(i);
                AttributeSet attrSet = compiler.builder.getAttributeSet(refName);
                if (attrSet != null && !attrSet.isStreamable()) {
                    throw new SAXException(
                        "XTSE3430: Attribute set '" + refName +
                        "' used in xsl:source-document streamable='yes'" +
                        " body is not declared streamable");
                }
            }

            StreamabilityValidator.validateSourceDocumentStreamability(body, compiler.builder);

        }

        SourceDocumentNode result = new SourceDocumentNode(hrefAvt, streamable, validation, useAccumulators, body);
        if (ctx.baseURI != null) {
            result.setStaticBaseURI(ctx.baseURI);
        }
        return result;
    }

    static XSLTNode compileMerge(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        List<MergeNode.MergeSource> sources = new ArrayList<>();
        XSLTNode action = null;
        int actionCount = 0;
        boolean seenAction = false;
        boolean seenFallbackBeforeAction = false;

        for (XSLTNode child : ctx.children) {
            String childStr = child.toString();
            if (childStr.contains("MergeSourceHolder")) {
                MergeSourceHolder holder = (MergeSourceHolder) child;
                sources.add(holder.source);
            } else if (childStr.contains("MergeActionHolder")) {
                MergeActionHolder holder = (MergeActionHolder) child;
                action = holder.content;
                actionCount++;
                seenAction = true;
            } else if (child instanceof FallbackNode) {
                if (!seenAction) {
                    seenFallbackBeforeAction = true;
                }
            }
        }

        if (actionCount > 1) {
            throw new SAXException("XTSE0010: xsl:merge must not have more than one xsl:merge-action");
        }
        if (sources.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:merge requires at least one xsl:merge-source");
        }
        if (action == null) {
            throw new SAXException("XTSE0010: xsl:merge requires xsl:merge-action");
        }

        if (seenFallbackBeforeAction) {
            throw new SAXException("XTSE0010: xsl:fallback in xsl:merge must follow xsl:merge-action");
        }

        Set<String> sourceNames = new HashSet<>();
        for (MergeNode.MergeSource source : sources) {
            String srcName = source.name;
            if (srcName != null && !sourceNames.add(srcName)) {
                throw new SAXException("XTSE3190: Duplicate xsl:merge-source name '" +
                    srcName + "'");
            }
        }

        if (sources.size() > 1) {
            int firstKeyCount = sources.get(0).keys.size();
            for (int i = 1; i < sources.size(); i++) {
                int keyCount = sources.get(i).keys.size();
                if (keyCount != firstKeyCount) {
                    throw new SAXException("XTSE2200: All xsl:merge-source elements must have " +
                        "the same number of xsl:merge-key children");
                }
            }
        }

        return new MergeNode(sources, action, ctx.baseURI);
    }

    static XSLTNode compileMergeSource(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");

        String selectStr = compiler.resolveStaticShadowAttribute(ctx, "select");

        String forEachItemStr = ctx.attributes.get("for-each-item");
        String forEachSourceStr = ctx.attributes.get("for-each-source");
        String sortBeforeMergeStr = compiler.resolveStaticShadowAttribute(ctx, "sort-before-merge");

        String streamableStr = compiler.resolveStaticShadowAttribute(ctx, "streamable");
        String useAccumulatorsStr = ctx.attributes.get("use-accumulators");

        if (streamableStr != null) {
            streamableStr = streamableStr.trim();
        }
        if (sortBeforeMergeStr != null) {
            sortBeforeMergeStr = sortBeforeMergeStr.trim();
        }

        if (streamableStr != null && !streamableStr.isEmpty()) {
            if (!"yes".equals(streamableStr) && !"no".equals(streamableStr)
                && !"true".equals(streamableStr) && !"false".equals(streamableStr)
                && !"1".equals(streamableStr) && !"0".equals(streamableStr)) {
                throw new SAXException("XTSE0020: Invalid value for streamable attribute: '"
                    + streamableStr + "' (must be yes/no/true/false)");
            }
        }

        if (sortBeforeMergeStr != null && !sortBeforeMergeStr.isEmpty()) {
            if (!"yes".equals(sortBeforeMergeStr) && !"no".equals(sortBeforeMergeStr)
                && !"true".equals(sortBeforeMergeStr) && !"false".equals(sortBeforeMergeStr)
                && !"1".equals(sortBeforeMergeStr) && !"0".equals(sortBeforeMergeStr)) {
                throw new SAXException("XTSE0020: Invalid value for sort-before-merge attribute: '"
                    + sortBeforeMergeStr + "' (must be yes/no/true/false)");
            }
        }

        boolean sortBeforeMerge = "yes".equals(sortBeforeMergeStr) || "true".equals(sortBeforeMergeStr)
            || "1".equals(sortBeforeMergeStr);
        boolean streamable = "yes".equals(streamableStr) || "true".equals(streamableStr);

        if (!compiler.elementStack.isEmpty()) {
            StylesheetCompiler.ElementContext parent = compiler.elementStack.peek();
            if (!"merge".equals(parent.localName) || !StylesheetCompiler.XSLT_NS.equals(parent.namespaceURI)) {
                throw new SAXException("XTSE0010: xsl:merge-source is not allowed here " +
                    "(must be a child of xsl:merge)");
            }
        } else {
            throw new SAXException("XTSE0010: xsl:merge-source is not allowed at the top level");
        }

        if (selectStr == null && forEachItemStr == null && forEachSourceStr == null) {
            throw new SAXException("XTSE0010: xsl:merge-source must have a select attribute, " +
                "for-each-item attribute, or for-each-source attribute");
        }

        if (forEachItemStr != null && forEachSourceStr != null) {
            throw new SAXException("XTSE3195: xsl:merge-source must not have both " +
                "for-each-item and for-each-source attributes");
        }

        if (streamable && sortBeforeMerge) {
            if (compiler.streamingFallback) {
                streamable = false;
            } else {
                throw new SAXException("XTSE3430: xsl:merge-source with streamable='yes' " +
                    "must not have sort-before-merge='yes'");
            }
        }

        if (streamable && selectStr != null
                && StreamabilityValidator.containsDescendantAxis(selectStr)) {
            if (compiler.streamingFallback) {
                streamable = false;
            } else {
                throw new SAXException("XTSE3430: xsl:merge-source with streamable='yes' " +
                    "must not have a crawling select expression");
            }
        }

        String validationStr = ctx.attributes.get("validation");
        String typeStr = ctx.attributes.get("type");
        if (typeStr != null
                || (validationStr != null && !"strip".equals(validationStr))) {
            throw new SAXException("XTSE1650: xsl:merge-source attributes 'validation' and 'type' " +
                "require a schema-aware XSLT processor");
        }

        XPathExpression select = selectStr != null ? compiler.compileExpression(selectStr) : null;
        XPathExpression forEachItem = forEachItemStr != null ? compiler.compileExpression(forEachItemStr) : null;
        XPathExpression forEachSource = forEachSourceStr != null ? compiler.compileExpression(forEachSourceStr) : null;

        List<MergeNode.MergeKey> keys = new ArrayList<>();
        boolean hasNonKeyContent = false;
        for (XSLTNode child : ctx.children) {
            String childStr = child.toString();
            if (childStr.contains("MergeKeyHolder")) {
                MergeKeyHolder holder = (MergeKeyHolder) child;
                keys.add(holder.key);
            } else if (compiler.hasNonWhitespaceContent(Collections.singletonList(child))) {
                hasNonKeyContent = true;
            }
        }

        if (selectStr != null && hasNonKeyContent) {
            throw new SAXException("XTSE0010: xsl:merge-source with select attribute " +
                "must not have content other than xsl:merge-key");
        }

        MergeNode.MergeSource source = new MergeNode.MergeSource(
            name, select, forEachItem, forEachSource, sortBeforeMerge, streamable, keys,
            useAccumulatorsStr
        );

        return new MergeSourceHolder(source);
    }

    static XSLTNode compileMergeKey(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");

        XPathExpression select = null;
        XSLTNode body = null;

        if (selectStr != null && !selectStr.isEmpty()) {
            if (!ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE3200: xsl:merge-key with select attribute " +
                    "must not have non-empty content");
            }
            select = compiler.compileExpression(selectStr);
        } else {
            if (ctx.children.isEmpty()) {
                throw new SAXException("XTSE0010: xsl:merge-key requires either select attribute or content");
            }
            if (ctx.children.size() == 1) {
                body = ctx.children.get(0);
            } else {
                body = new SequenceNode(new ArrayList<>(ctx.children));
            }
        }

        String orderStr = ctx.attributes.get("order");
        String langStr = ctx.attributes.get("lang");
        String collationStr = ctx.attributes.get("collation");
        String dataTypeStr = ctx.attributes.get("data-type");

        AttributeValueTemplate orderAvt = orderStr != null ? compiler.parseAvt(orderStr) : null;
        AttributeValueTemplate langAvt = langStr != null ? compiler.parseAvt(langStr) : null;
        AttributeValueTemplate collationAvt = collationStr != null ? compiler.parseAvt(collationStr) : null;
        AttributeValueTemplate dataTypeAvt = dataTypeStr != null ? compiler.parseAvt(dataTypeStr) : null;

        MergeNode.MergeKey key = new MergeNode.MergeKey(select, body, orderAvt, langAvt, collationAvt, dataTypeAvt);
        return new MergeKeyHolder(key);
    }

    static XSLTNode compileMergeAction(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        XSLTNode content;
        if (ctx.children.isEmpty()) {
            content = null;
        } else if (ctx.children.size() == 1) {
            content = ctx.children.get(0);
        } else {
            content = new SequenceNode(new ArrayList<>(ctx.children));
        }
        return new MergeActionHolder(content);
    }

    private static class MergeSourceHolder implements XSLTNode {
        final MergeNode.MergeSource source;
        MergeSourceHolder(MergeNode.MergeSource source) { this.source = source; }
        @Override public void execute(TransformContext ctx, OutputHandler out) {}
        @Override public String toString() { return "MergeSourceHolder"; }
    }

    private static class MergeKeyHolder implements XSLTNode {
        final MergeNode.MergeKey key;
        MergeKeyHolder(MergeNode.MergeKey key) { this.key = key; }
        @Override public void execute(TransformContext ctx, OutputHandler out) {}
        @Override public String toString() { return "MergeKeyHolder"; }
    }

    private static class MergeActionHolder implements XSLTNode {
        final XSLTNode content;
        MergeActionHolder(XSLTNode content) { this.content = content; }
        @Override public void execute(TransformContext ctx, OutputHandler out) {}
        @Override public String toString() { return "MergeActionHolder"; }
    }

    static XSLTNode compileContextItem(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        StylesheetCompiler.ElementContext parent = compiler.elementStack.peek();
        if (parent == null || !"template".equals(parent.localName) ||
                !StylesheetCompiler.XSLT_NS.equals(parent.namespaceURI)) {
            throw new SAXException("XTSE0010: xsl:context-item is only allowed as a " +
                "direct child of xsl:template");
        }

        String selectAttr = ctx.attributes.get("select");
        if (selectAttr != null) {
            throw new SAXException("XTSE0090: xsl:context-item does not allow a 'select' attribute");
        }

        String asType = ctx.attributes.get("as");
        String use = ctx.attributes.get("use");
        if (use != null) {
            use = use.trim();
        }

        if (use != null && !use.isEmpty()) {
            if (!use.equals("required") && !use.equals("optional") && !use.equals("absent")) {
                throw new SAXException("XTSE0020: Invalid value for 'use' attribute: " + use);
            }
        }

        if ("absent".equals(use) && asType != null && !asType.trim().isEmpty()) {
            throw new SAXException("XTSE3088: xsl:context-item cannot specify both use=\"absent\" and an 'as' type");
        }

        if ("absent".equals(use) && compiler.currentTemplateHasMatch && !compiler.currentTemplateHasName) {
            throw new SAXException("XTSE0020: use=\"absent\" is not allowed in a "
                + "template rule that has a match attribute but no name attribute");
        }

        SequenceType parsedType = null;
        if (asType != null && !asType.trim().isEmpty()) {
            final Map<String, String> nsBindings = ctx.namespaceBindings;
            parsedType = SequenceType.parse(asType.trim(), new java.util.function.Function<String, String>() {
                @Override
                public String apply(String prefix) {
                    return nsBindings.get(prefix);
                }
            });

            if (parsedType != null) {
                if (parsedType.getOccurrence() != SequenceType.Occurrence.ONE) {
                    throw new SAXException("XTSE0020: Occurrence indicator not allowed in xsl:context-item/@as: " + asType);
                }

                if (parsedType.getItemKind() == SequenceType.ItemKind.ATOMIC) {
                    String ns = parsedType.getNamespaceURI();
                    if (ns != null && !SequenceType.XS_NAMESPACE.equals(ns)) {
                        throw new SAXException("XTSE0020: Unknown atomic type '" + asType.trim()
                            + "' in xsl:context-item/@as (XPST0051: schema type not imported)");
                    }
                }

                if (parsedType.getTypeName() != null) {
                    String ns = parsedType.getTypeName().getURI();
                    if (ns != null && !SequenceType.XS_NAMESPACE.equals(ns)) {
                        throw new SAXException("XTSE0020: Unknown schema type '" + asType.trim()
                            + "' in xsl:context-item/@as (XPST0008: schema type not available)");
                    }
                }
            }
        }

        return new ContextItemDeclaration(asType, use, parsedType);
    }

    public static class ContextItemDeclaration implements XSLTNode {
        final String asType;
        final String use;
        final SequenceType parsedType;

        ContextItemDeclaration(String asType, String use, SequenceType parsedType) {
            this.asType = asType;
            this.use = use;
            this.parsedType = parsedType;
        }

        @Override
        public void execute(TransformContext ctx, OutputHandler out) throws org.xml.sax.SAXException {
            XPathNode contextNode = ctx.getContextNode();
            XPathValue contextItem = ctx.getContextItem();

            boolean hasContextItem = (contextNode != null || contextItem != null);
            if ("required".equals(use) && !hasContextItem) {
                throw new org.xml.sax.SAXException("XTTE3090: Context item is required but none was supplied");
            }

            if ("absent".equals(use)) {
                if (ctx instanceof BasicTransformContext) {
                    ((BasicTransformContext) ctx).setContextItemUndefined(true);
                }
                return;
            }

            if (parsedType != null) {
                XPathValue itemToCheck = contextItem;
                if (itemToCheck == null && contextNode != null
                        && !"optional".equals(use)) {
                    List<XPathNode> nodes = new ArrayList<XPathNode>();
                    nodes.add(contextNode);
                    itemToCheck = new XPathNodeSet(nodes);
                }
                if (itemToCheck != null && !parsedType.matches(itemToCheck)) {
                    throw new org.xml.sax.SAXException(
                        "XTTE0590: Context item does not match declared type '" + asType + "'");
                }
            }
        }

        @Override
        public String toString() {
            return "ContextItemDeclaration[as=" + asType + ", use=" + use + "]";
        }
    }

    static void processGlobalContextItem(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String asType = ctx.attributes.get("as");
        String use = ctx.attributes.get("use");
        if (use != null) {
            use = use.trim();
        }

        if (use != null && !use.isEmpty()) {
            if (!use.equals("required") && !use.equals("optional") && !use.equals("absent")) {
                throw new SAXException("XTSE0020: Invalid value for 'use' attribute: " + use);
            }
        }

        if ("absent".equals(use) && asType != null && !asType.isEmpty()) {
            throw new SAXException("XTSE3089: The 'as' attribute is not " +
                "allowed on xsl:global-context-item when use='absent'");
        }

        compiler.builder.setGlobalContextItemType(asType);
        compiler.builder.setGlobalContextItemUse(use);
    }

    static Set<String> findEnclosingIterateParamNames(StylesheetCompiler compiler) {
        for (StylesheetCompiler.ElementContext ancestor : compiler.elementStack) {
            if (StylesheetCompiler.XSLT_NS.equals(ancestor.namespaceURI)
                    && "iterate".equals(ancestor.localName)) {
                Set<String> names = new HashSet<>();
                for (XSLTNode child : ancestor.children) {
                    if (child instanceof ParamNode) {
                        names.add(((ParamNode) child).getName());
                    }
                }
                return names;
            }
        }
        return null;
    }

    static XSLTNode compileIterate(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("xsl:iterate requires select attribute");
        }

        XPathExpression select = compiler.compileExpression(selectStr);

        List<IterateNode.IterateParam> params = new ArrayList<>();
        Set<String> paramNames = new HashSet<>();
        XSLTNode onCompletion = null;
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean seenBody = false;

        for (int i = 0; i < ctx.childElementNameList.size(); i++) {
            String childName = ctx.childElementNameList.get(i);
            if ("on-completion".equals(childName) && seenBody) {
                throw new SAXException("XTSE0010: xsl:on-completion must appear " +
                    "before the body of xsl:iterate");
            }
            if (!"param".equals(childName) && !"on-completion".equals(childName)
                    && !"fallback".equals(childName)) {
                seenBody = true;
            }
        }

        for (XSLTNode child : ctx.children) {
            if (child instanceof ParamNode) {
                ParamNode pn = (ParamNode) child;
                String pname = pn.getName();
                if (!paramNames.add(pname)) {
                    throw new SAXException("XTSE0580: Duplicate parameter name '" +
                        pname + "' in xsl:iterate");
                }
                boolean hasDefault = (pn.getSelectExpr() != null) ||
                    (pn.getContent() != null && !pn.getContent().isEmpty());
                if (!hasDefault && pn.getAs() != null) {
                    String asStr = pn.getAs().trim();
                    boolean allowsEmpty = asStr.endsWith("?") ||
                        asStr.endsWith("*") ||
                        "empty-sequence()".equals(asStr);
                    if (!allowsEmpty) {
                        throw new SAXException("XTSE3520: Parameter '" + pname +
                            "' in xsl:iterate is implicitly mandatory " +
                            "(has as=\"" + pn.getAs() + "\" but no default value)");
                    }
                }
                params.add(new IterateNode.IterateParam(pname, pn.getSelectExpr(), pn.getAs()));
            } else if (child instanceof org.bluezoo.gonzalez.transform.ast.OnCompletionNode) {
                onCompletion = child;
            } else if (child instanceof FallbackNode) {
            } else {
                bodyNodes.add(child);
            }
        }

        validateBreakTailPosition(bodyNodes);

        XSLTNode body = bodyNodes.isEmpty() ? null :
            (bodyNodes.size() == 1 ? bodyNodes.get(0) : new SequenceNode(bodyNodes));

        return new IterateNode(select, params, body, onCompletion);
    }

    static void validateBreakTailPosition(List<XSLTNode> nodes) throws SAXException {
        for (int i = 0; i < nodes.size(); i++) {
            XSLTNode node = nodes.get(i);
            boolean isTail = (i == nodes.size() - 1);

            if (node instanceof BreakNode || node instanceof NextIterationNode) {
                if (!isTail) {
                    throw new SAXException("XTSE3120: xsl:break and " +
                        "xsl:next-iteration must appear in tail position " +
                        "within xsl:iterate");
                }
            } else if (isTail) {
                validateBreakTailInContainer(node);
            } else {
                if (containsBreakOrNextIteration(node)) {
                    throw new SAXException("XTSE3120: xsl:break and " +
                        "xsl:next-iteration must appear in tail position " +
                        "within xsl:iterate");
                }
            }
        }
    }

    static void validateBreakTailInContainer(XSLTNode node) throws SAXException {
        if (node instanceof IfNode) {
            IfNode ifNode = (IfNode) node;
            SequenceNode content = ifNode.getContent();
            if (content != null) {
                validateBreakTailPosition(content.getChildren());
            }
        } else if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                SequenceNode content = when.getContent();
                if (content != null) {
                    validateBreakTailPosition(content.getChildren());
                }
            }
            SequenceNode otherwise = choose.getOtherwise();
            if (otherwise != null) {
                validateBreakTailPosition(otherwise.getChildren());
            }
        } else if (node instanceof SequenceNode) {
            validateBreakTailPosition(((SequenceNode) node).getChildren());
        } else {
            if (containsBreakOrNextIteration(node)) {
                throw new SAXException("XTSE3120: xsl:break and " +
                    "xsl:next-iteration must appear in tail position " +
                    "within xsl:iterate");
            }
        }
    }

    static boolean containsBreakOrNextIteration(XSLTNode node) {
        if (node instanceof BreakNode || node instanceof NextIterationNode) {
            return true;
        }
        if (node instanceof IfNode) {
            SequenceNode content = ((IfNode) node).getContent();
            if (content != null) {
                for (XSLTNode child : content.getChildren()) {
                    if (containsBreakOrNextIteration(child)) {
                        return true;
                    }
                }
            }
        } else if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                SequenceNode content = when.getContent();
                if (content != null) {
                    for (XSLTNode child : content.getChildren()) {
                        if (containsBreakOrNextIteration(child)) {
                            return true;
                        }
                    }
                }
            }
            SequenceNode otherwise = choose.getOtherwise();
            if (otherwise != null) {
                for (XSLTNode child : otherwise.getChildren()) {
                    if (containsBreakOrNextIteration(child)) {
                        return true;
                    }
                }
            }
        } else if (node instanceof SequenceNode) {
            for (XSLTNode child : ((SequenceNode) node).getChildren()) {
                if (containsBreakOrNextIteration(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    static XSLTNode compileNextIteration(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        List<NextIterationNode.ParamValue> params = new ArrayList<>();
        Set<String> withParamNames = new HashSet<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                WithParamNode wp = (WithParamNode) child;
                String wpName = wp.getName();
                if (!withParamNames.add(wpName)) {
                    throw new SAXException("XTSE0670: Duplicate xsl:with-param name '" +
                        wpName + "' in xsl:next-iteration");
                }
                params.add(new NextIterationNode.ParamValue(
                    wpName, wp.getSelectExpr(), wp.getContent()));
            }
        }

        Set<String> iterateParamNames = findEnclosingIterateParamNames(compiler);
        if (iterateParamNames != null) {
            for (String wpName : withParamNames) {
                if (!iterateParamNames.contains(wpName)) {
                    throw new SAXException("XTSE3130: Parameter '" + wpName +
                        "' in xsl:next-iteration is not declared in the enclosing xsl:iterate");
                }
            }
        }

        return new NextIterationNode(params);
    }

    static XSLTNode compileBreak(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");
        if (select != null && !ctx.children.isEmpty()) {
            throw new SAXException("XTSE3125: xsl:break must not have both " +
                "a select attribute and children");
        }
        if (select != null) {
            XPathExpression selectExpr = compiler.compileExpression(select);
            return new BreakNode(selectExpr);
        }
        XSLTNode content = null;
        if (!ctx.children.isEmpty()) {
            content = ctx.children.size() == 1 ? ctx.children.get(0)
                : new SequenceNode(new ArrayList<>(ctx.children));
        }
        return new BreakNode(content);
    }

    static XSLTNode compileFork(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        List<ForkNode.ForkBranch> branches = new ArrayList<>();

        for (XSLTNode child : ctx.children) {
            branches.add(new ForkNode.ForkBranch(child));
        }

        return new ForkNode(branches);
    }

    static XSLTNode compileForEachGroup(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("xsl:for-each-group requires select attribute");
        }

        String groupBy = ctx.attributes.get("group-by");
        String groupAdjacent = ctx.attributes.get("group-adjacent");
        String groupStartingWith = ctx.attributes.get("group-starting-with");
        String groupEndingWith = ctx.attributes.get("group-ending-with");
        String collationStr = ctx.attributes.get("collation");

        int groupingAttrs = 0;
        if (groupBy != null && !groupBy.isEmpty()) groupingAttrs++;
        if (groupAdjacent != null && !groupAdjacent.isEmpty()) groupingAttrs++;
        if (groupStartingWith != null && !groupStartingWith.isEmpty()) groupingAttrs++;
        if (groupEndingWith != null && !groupEndingWith.isEmpty()) groupingAttrs++;
        if (groupingAttrs > 1) {
            throw new SAXException("XTSE1080: xsl:for-each-group must have exactly one of group-by, group-adjacent, group-starting-with, or group-ending-with");
        }

        boolean hasGroupBy = groupBy != null && !groupBy.isEmpty();
        boolean hasGroupAdjacent = groupAdjacent != null && !groupAdjacent.isEmpty();
        if (collationStr != null && !collationStr.isEmpty() && !hasGroupBy && !hasGroupAdjacent) {
            throw new SAXException("XTSE1090: collation attribute on xsl:for-each-group " +
                "is only allowed when group-by or group-adjacent is specified");
        }
        String compositeStr = ctx.attributes.get("composite");
        if (compositeStr != null && !hasGroupBy && !hasGroupAdjacent) {
            throw new SAXException("XTSE1090: composite attribute on xsl:for-each-group " +
                "is only allowed when group-by or group-adjacent is specified");
        }

        XPathExpression select = compiler.compileExpression(selectStr);

        int lastSortIndex = -1;
        for (int i = 0; i < ctx.children.size(); i++) {
            if (ctx.children.get(i) instanceof SortSpecNode) {
                lastSortIndex = i;
            }
        }

        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> bodyNodes = new ArrayList<>();
        boolean foundNonSort = false;

        for (int i = 0; i < ctx.children.size(); i++) {
            XSLTNode child = ctx.children.get(i);

            boolean isStrippableWhitespace = child instanceof LiteralText &&
                compiler.isWhitespace(((LiteralText) child).getText()) &&
                !((LiteralText) child).isFromXslText();

            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each-group");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (i > lastSortIndex) {
                bodyNodes.add(child);
                if (!isStrippableWhitespace) {
                    foundNonSort = true;
                }
            } else if (!isStrippableWhitespace) {
                if (lastSortIndex >= 0) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:for-each-group");
                }
                foundNonSort = true;
                bodyNodes.add(child);
            }
        }

        XSLTNode body = bodyNodes.isEmpty() ? null : new SequenceNode(bodyNodes);

        AttributeValueTemplate collationAvt = null;
        if (collationStr != null && !collationStr.isEmpty()) {
            collationAvt = compiler.parseAvt(collationStr);
        }
        compiler.validateYesOrNo("xsl:for-each-group", "composite", compositeStr);
        boolean isComposite = "yes".equals(compositeStr) || "1".equals(compositeStr) || "true".equals(compositeStr);
        validateSortStable(sorts);

        if (groupBy != null && !groupBy.isEmpty()) {
            XPathExpression groupByExpr = compiler.compileExpression(groupBy);
            return ForEachGroupNode.groupBy(select, groupByExpr, body, collationAvt, sorts, isComposite);
        } else if (groupAdjacent != null && !groupAdjacent.isEmpty()) {
            XPathExpression groupAdjacentExpr = compiler.compileExpression(groupAdjacent);
            return ForEachGroupNode.groupAdjacent(select, groupAdjacentExpr, body, collationAvt, sorts, isComposite);
        } else if (groupStartingWith != null && !groupStartingWith.isEmpty()) {
            Pattern pattern = compiler.compilePattern(groupStartingWith);
            return ForEachGroupNode.groupStartingWith(select, pattern, body, sorts);
        } else if (groupEndingWith != null && !groupEndingWith.isEmpty()) {
            Pattern pattern = compiler.compilePattern(groupEndingWith);
            return ForEachGroupNode.groupEndingWith(select, pattern, body, sorts);
        } else {
            throw new SAXException("xsl:for-each-group requires group-by, group-adjacent, group-starting-with, or group-ending-with attribute");
        }
    }

    static XSLTNode compilePerformSort(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String select = ctx.attributes.get("select");

        List<SortSpec> sorts = new ArrayList<>();
        List<XSLTNode> contentNodes = new ArrayList<>();

        int lastSortIndex = -1;
        for (int i = 0; i < ctx.children.size(); i++) {
            if (ctx.children.get(i) instanceof SortSpecNode) {
                lastSortIndex = i;
            }
        }

        boolean foundNonSort = false;
        for (int i = 0; i < ctx.children.size(); i++) {
            XSLTNode child = ctx.children.get(i);

            boolean isStrippableWhitespace = child instanceof LiteralText &&
                compiler.isWhitespace(((LiteralText) child).getText()) &&
                !((LiteralText) child).isFromXslText();

            if (child instanceof SortSpecNode) {
                if (foundNonSort) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:perform-sort");
                }
                sorts.add(((SortSpecNode) child).getSortSpec());
            } else if (i > lastSortIndex) {
                contentNodes.add(child);
                if (!isStrippableWhitespace) {
                    foundNonSort = true;
                }
            } else if (!isStrippableWhitespace) {
                if (lastSortIndex >= 0) {
                    throw new SAXException("XTSE0010: xsl:sort must come before other content in xsl:perform-sort");
                }
                foundNonSort = true;
                contentNodes.add(child);
            }
        }

        if (sorts.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:perform-sort requires at least one xsl:sort child");
        }
        validateSortStable(sorts);

        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        XSLTNode content = contentNodes.isEmpty() ? null : new SequenceNode(contentNodes);

        if (selectExpr != null && content != null) {
            for (XSLTNode node : contentNodes) {
                if (node instanceof LiteralText && compiler.isWhitespace(((LiteralText) node).getText())) {
                    continue;
                }
                if (!(node instanceof FallbackNode)) {
                    throw new SAXException("XTSE1040: xsl:perform-sort with select attribute " +
                        "must not contain content other than xsl:sort and xsl:fallback");
                }
            }
        }

        if (selectExpr == null && content == null) {
            selectExpr = compiler.compileExpression("()");
        }

        return new PerformSortNode(selectExpr, sorts, content);
    }

    static XSLTNode compileEvaluate(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String xpathStr = ctx.attributes.get("xpath");
        if (xpathStr == null || xpathStr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:evaluate requires xpath attribute");
        }

        String contextItemStr = ctx.attributes.get("context-item");
        String baseUriStr = ctx.attributes.get("base-uri");
        String namespaceContextStr = ctx.attributes.get("namespace-context");
        String withParamsStr = ctx.attributes.get("with-params");
        String asType = ctx.attributes.get("as");

        XPathExpression xpathExpr = compiler.compileExpression(xpathStr);
        XPathExpression contextItemExpr = contextItemStr != null ? compiler.compileExpression(contextItemStr) : null;
        AttributeValueTemplate baseUriAvt = baseUriStr != null ? compiler.parseAvt(baseUriStr) : null;
        XPathExpression namespaceContextExpr = namespaceContextStr != null ?
            compiler.compileExpression(namespaceContextStr) : null;
        XPathExpression withParamsExpr = withParamsStr != null ? compiler.compileExpression(withParamsStr) : null;

        List<EvaluateNode.WithParamNode> params = new ArrayList<>();
        for (XSLTNode child : ctx.children) {
            if (child instanceof WithParamNode) {
                WithParamNode wp = (WithParamNode) child;
                params.add(new EvaluateNode.WithParamNode(
                    wp.getNamespaceURI(), wp.getLocalName(),
                    wp.getSelectExpr(), wp.getContent(),
                    wp.getAsType()));
            }
        }

        return new EvaluateNode(xpathExpr, contextItemExpr, baseUriAvt,
            namespaceContextExpr, withParamsExpr, asType, params);
    }

    static XSLTNode compileTry(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectStr != null && !selectStr.isEmpty()) {
            if (compiler.hasNonCatchOrFallbackContent(ctx.children)) {
                throw new SAXException("XTSE3140: xsl:try with select attribute must not " +
                    "have children other than xsl:catch and xsl:fallback");
            }
            selectExpr = compiler.compileExpression(selectStr);
        }

        List<XSLTNode> tryContent = new ArrayList<>();
        List<CatchNode> catchBlocks = new ArrayList<>();

        for (XSLTNode child : ctx.children) {
            if (child instanceof CatchNode) {
                catchBlocks.add((CatchNode) child);
            } else if (child instanceof FallbackNode) {
            } else {
                tryContent.add(child);
            }
        }

        XSLTNode body = null;
        if (selectExpr != null) {
            body = new SelectExprNode(selectExpr);
        } else if (!tryContent.isEmpty()) {
            body = new SequenceNode(tryContent);
        }
        String rollbackOutput = ctx.attributes.get("rollback-output");
        boolean rollback = !"no".equals(rollbackOutput);
        return new TryNode(body, catchBlocks, rollback);
    }

    private static class SelectExprNode extends XSLTInstruction {
        private final XPathExpression expr;

        SelectExprNode(XPathExpression expr) {
            this.expr = expr;
        }

        @Override public String getInstructionName() { return "select-expr"; }

        @Override
        public void execute(TransformContext context, OutputHandler output) throws SAXException {
            try {
                XPathValue result = expr.evaluate(context);
                if (result == null) {
                    return;
                }
                if (output instanceof SequenceBuilderOutputHandler) {
                    SequenceBuilderOutputHandler seqBuilder =
                        (SequenceBuilderOutputHandler) output;
                    if (!seqBuilder.isInsideElement()) {
                        if (result instanceof XPathSequence) {
                            for (XPathValue item : (XPathSequence) result) {
                                seqBuilder.addItem(item);
                            }
                        } else if (result instanceof XPathNodeSet) {
                            for (XPathNode node : ((XPathNodeSet) result).getNodes()) {
                                seqBuilder.addItem(
                                    new XPathNodeSet(Collections.singletonList(node)));
                            }
                        } else {
                            seqBuilder.addItem(result);
                        }
                        return;
                    }
                }
                output.atomicValue(result);
            } catch (XPathException e) {
                throw new SAXException(e.getMessage(), e);
            }
        }
    }

    static XSLTNode compileAssert(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String testStr = ctx.attributes.get("test");
        if (testStr == null || testStr.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:assert requires test attribute");
        }

        String errorCodeStr = ctx.attributes.get("error-code");
        XPathExpression testExpr = compiler.compileExpression(testStr);
        XSLTNode messageContent = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);

        return new AssertNode(testExpr, errorCodeStr, messageContent);
    }

    static XSLTNode compileOnEmpty(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectValue = ctx.attributes.get("select");

        if (selectValue != null) {
            XPathExpression selectExpr = compiler.compileExpression(selectValue);
            return new OnEmptyNode(selectExpr);
        } else {
            XSLTNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
            return new OnEmptyNode(content);
        }
    }

    static XSLTNode compileOnNonEmpty(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectValue = ctx.attributes.get("select");
        if (selectValue != null) {
            XPathExpression selectExpr = compiler.compileExpression(selectValue);
            return new OnNonEmptyNode(selectExpr);
        } else {
            XSLTNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
            return new OnNonEmptyNode(content);
        }
    }

    static XSLTNode compileWherePopulated(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        XSLTNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);
        return new WherePopulatedNode(content);
    }

    static XSLTNode compileAnalyzeString(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectStr = ctx.attributes.get("select");
        String regexStr = ctx.attributes.get("regex");
        String flagsStr = ctx.attributes.get("flags");

        if (selectStr == null || selectStr.isEmpty()) {
            throw new SAXException("xsl:analyze-string requires select attribute");
        }
        if (regexStr == null) {
            throw new SAXException("xsl:analyze-string requires regex attribute");
        }
        if (regexStr.isEmpty() && compiler.stylesheetVersion < 3.0) {
            throw new SAXException("XTDE1150: The regex attribute of xsl:analyze-string must not " +
                "be a zero-length string");
        }

        boolean hasMatching = ctx.childElementNames.contains("matching-substring");
        boolean hasNonMatching = ctx.childElementNames.contains("non-matching-substring");
        if (!hasMatching && !hasNonMatching) {
            throw new SAXException("XTSE1130: xsl:analyze-string must contain at least one of " +
                "xsl:matching-substring or xsl:non-matching-substring");
        }

        validateAnalyzeStringChildOrder(ctx);

        XPathExpression select = compiler.compileExpression(selectStr);
        AttributeValueTemplate regexAvt = compiler.parseAvt(regexStr);
        AttributeValueTemplate flagsAvt = flagsStr != null ? compiler.parseAvt(flagsStr) : null;

        XSLTNode matchingContent = null;
        XSLTNode nonMatchingContent = null;

        int childIdx = 0;
        for (int i = 0; i < ctx.childElementNameList.size(); i++) {
            String childName = ctx.childElementNameList.get(i);
            if ("matching-substring".equals(childName)) {
                if (childIdx < ctx.children.size()) {
                    matchingContent = ctx.children.get(childIdx);
                }
                childIdx++;
            } else if ("non-matching-substring".equals(childName)) {
                if (childIdx < ctx.children.size()) {
                    nonMatchingContent = ctx.children.get(childIdx);
                }
                childIdx++;
            } else if ("fallback".equals(childName)) {
                childIdx++;
            }
        }

        return new AnalyzeStringNode(select, regexAvt, flagsAvt,
            matchingContent, nonMatchingContent);
    }

    static void validateAnalyzeStringChildOrder(StylesheetCompiler.ElementContext ctx) throws SAXException {
        boolean seenNonMatching = false;
        boolean seenFallback = false;
        for (String childName : ctx.childElementNameList) {
            if ("matching-substring".equals(childName)) {
                if (seenNonMatching) {
                    throw new SAXException("XTSE0010: xsl:matching-substring must precede " +
                        "xsl:non-matching-substring in xsl:analyze-string");
                }
                if (seenFallback) {
                    throw new SAXException("XTSE0010: xsl:matching-substring must precede " +
                        "xsl:fallback in xsl:analyze-string");
                }
            } else if ("non-matching-substring".equals(childName)) {
                seenNonMatching = true;
                if (seenFallback) {
                    throw new SAXException("XTSE0010: xsl:non-matching-substring must precede " +
                        "xsl:fallback in xsl:analyze-string");
                }
            } else if ("fallback".equals(childName)) {
                seenFallback = true;
            }
        }
    }

    static XSLTNode compileResultDocument(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String href = compiler.resolveStaticShadowAttribute(ctx, "href");
        AttributeValueTemplate hrefAvt = null;
        if (href != null && !href.isEmpty()) {
            hrefAvt = compiler.parseAvt(href);
        }

        String formatStr = ctx.attributes.get("format");
        AttributeValueTemplate formatAvt = null;
        Map<String, String> formatNsBindings = null;
        if (formatStr != null && !formatStr.isEmpty()) {
            boolean isAvt = formatStr.contains("{") && formatStr.contains("}");
            if (!isAvt) {
                String expandedFormat = compiler.expandQName(formatStr.trim());
                if (!compiler.builder.hasOutputDefinition(expandedFormat)) {
                    throw new SAXException("XTSE1460: The format attribute of " +
                        "xsl:result-document references an unknown output definition '" +
                        formatStr + "'");
                }
            }
            formatAvt = compiler.parseAvt(formatStr);
            formatNsBindings = new HashMap<>(ctx.namespaceBindings);
        }
        String method = ctx.attributes.get("method");
        String encoding = ctx.attributes.get("encoding");
        String indent = ctx.attributes.get("indent");
        String typeValue = ctx.attributes.get("type");
        String validationValue = ctx.attributes.get("validation");

        CompilerUtils.validateResultDocumentBoolean(compiler, ctx, "undeclare-prefixes");
        CompilerUtils.validateResultDocumentBoolean(compiler, ctx, "indent");
        CompilerUtils.validateResultDocumentBoolean(compiler, ctx, "omit-xml-declaration");
        CompilerUtils.validateResultDocumentBoolean(compiler, ctx, "byte-order-mark");
        CompilerUtils.validateResultDocumentBoolean(compiler, ctx, "escape-uri-attributes");
        CompilerUtils.validateResultDocumentBoolean(compiler, ctx, "include-content-type");

        String standalone = ctx.attributes.get("standalone");
        if (standalone != null && !standalone.isEmpty()) {
            boolean isAvt = standalone.contains("{") && standalone.contains("}");
            if (!isAvt) {
                String trimmed = standalone.trim();
                if (!"yes".equals(trimmed) && !"no".equals(trimmed) && !"true".equals(trimmed)
                    && !"false".equals(trimmed) && !"1".equals(trimmed) && !"0".equals(trimmed)
                    && !"omit".equals(trimmed)) {
                    throw new SAXException("XTSE0020: Invalid value for standalone attribute on " +
                        "xsl:result-document: must be yes, no, true, false, 1, 0, or omit, got '" +
                        standalone + "'");
                }
            }
        }

        String htmlVersion = ctx.attributes.get("html-version");
        if (htmlVersion != null && !htmlVersion.isEmpty()) {
            boolean isAvt = htmlVersion.contains("{") && htmlVersion.contains("}");
            if (!isAvt) {
                try {
                    Double.parseDouble(htmlVersion.trim());
                } catch (NumberFormatException e) {
                    throw new SAXException("XTSE0020: Invalid value for html-version attribute on " +
                        "xsl:result-document: must be a number, got '" + htmlVersion + "'");
                }
            }
        }

        if (typeValue != null && validationValue != null) {
            throw new SAXException("xsl:result-document cannot have both type and validation attributes");
        }

        ValidationMode validation = compiler.parseValidationMode(validationValue, "xsl:result-document");

        String typeNamespaceURI = null;
        String typeLocalName = null;
        if (typeValue != null && !typeValue.isEmpty()) {
            int colonPos = typeValue.indexOf(':');
            if (colonPos > 0) {
                String typePrefix = typeValue.substring(0, colonPos);
                typeLocalName = typeValue.substring(colonPos + 1);
                typeNamespaceURI = ctx.namespaceBindings.get(typePrefix);
                if (typeNamespaceURI == null) {
                    throw new SAXException("Unknown namespace prefix in xsl:result-document/@type: " + typePrefix);
                }
            } else {
                typeLocalName = typeValue;
                typeNamespaceURI = "http://www.w3.org/2001/XMLSchema";
            }
        }

        XSLTNode content = ctx.children.isEmpty() ? null :
            (ctx.children.size() == 1 ? ctx.children.get(0)
                : new SequenceNode(new ArrayList<>(ctx.children)));

        ResultDocumentNode node = new ResultDocumentNode(hrefAvt, formatAvt, formatNsBindings,
                                      method, encoding, indent, content,
                                      typeNamespaceURI, typeLocalName, validation);
        node.setSourceLocation(ctx.baseURI, ctx.lineNumber, ctx.columnNumber);
        return node;
    }

    static XSLTNode compileIf(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String test = ctx.attributes.get("test");
        if (test == null) {
            throw new SAXException("xsl:if requires test attribute");
        }
        return new IfNode(compiler.compileExpression(test), new SequenceNode(ctx.children));
    }

    static XSLTNode compileChoose(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        List<WhenNode> whens = new ArrayList<>();
        SequenceNode otherwise = null;
        boolean sawOtherwise = false;

        for (XSLTNode child : ctx.children) {
            XSLTNode unwrapped = child;
            if (child instanceof CollationScopeNode) {
                unwrapped = ((CollationScopeNode) child).getBody();
            }
            if (unwrapped instanceof WhenNode) {
                if (sawOtherwise) {
                    throw new SAXException("XTSE0010: xsl:when must come before xsl:otherwise in xsl:choose");
                }
                if (child instanceof CollationScopeNode) {
                    WhenNode when = (WhenNode) unwrapped;
                    CollationScopeNode cs = (CollationScopeNode) child;
                    List<XSLTNode> wrappedChildren = new ArrayList<>();
                    wrappedChildren.add(new CollationScopeNode(
                        cs.getCollationUri(), when.getContent()));
                    whens.add(new WhenNode(when.getTestExpr(), new SequenceNode(wrappedChildren)));
                } else {
                    whens.add((WhenNode) unwrapped);
                }
            } else if (unwrapped instanceof OtherwiseNode) {
                if (sawOtherwise) {
                    throw new SAXException("XTSE0010: xsl:choose must have at most one xsl:otherwise");
                }
                sawOtherwise = true;
                otherwise = ((OtherwiseNode) unwrapped).getContent();
            } else if (child instanceof LiteralText) {
                String text = ((LiteralText) child).getText();
                if (text != null && !text.trim().isEmpty()) {
                    throw new SAXException("XTSE0010: Text content is not allowed in xsl:choose");
                }
            } else {
                throw new SAXException("XTSE0010: Only xsl:when and xsl:otherwise are allowed in xsl:choose");
            }
        }

        if (whens.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:choose must have at least one xsl:when element");
        }

        return new ChooseNode(whens, otherwise);
    }

    static XSLTNode compileWhen(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String test = ctx.attributes.get("test");
        if (test == null) {
            throw new SAXException("xsl:when requires test attribute");
        }
        return new WhenNode(compiler.compileExpression(test), new SequenceNode(ctx.children));
    }

    static XSLTNode compileOtherwise(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) {
        return new OtherwiseNode(new SequenceNode(ctx.children));
    }

    static XSLTNode compileWithParam(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String name = ctx.attributes.get("name");
        String select = ctx.attributes.get("select");
        String asType = ctx.attributes.get("as");
        String tunnelAttr = ctx.attributes.get("tunnel");
        compiler.validateYesOrNo("xsl:with-param", "tunnel", tunnelAttr);
        boolean tunnel = compiler.parseYesOrNo(tunnelAttr);

        QName paramName = compiler.parseQName(name, ctx.namespaceBindings);

        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);

        if (selectExpr != null && content != null && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0620: xsl:with-param must not have both " +
                "a select attribute and non-empty content");
        }

        return new WithParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, tunnel, asType);
    }

    static XSLTNode compileSort(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String selectAttr = ctx.attributes.get("select");
        if (selectAttr != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE1015: xsl:sort must not have both " +
                "a select attribute and content");
        }
        XPathExpression selectExpr;
        XSLTNode sortBody = null;
        if (selectAttr != null && !selectAttr.isEmpty()) {
            selectExpr = compiler.compileExpression(selectAttr);
        } else if (!ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            selectExpr = null;
            sortBody = new SequenceNode(new ArrayList<>(ctx.children));
        } else {
            selectExpr = compiler.compileExpression(".");
        }

        String dataType = ctx.attributes.get("data-type");
        String order = ctx.attributes.get("order");
        String caseOrder = ctx.attributes.get("case-order");
        String lang = ctx.attributes.get("lang");
        String collation = ctx.attributes.get("collation");
        String stable = ctx.attributes.get("stable");
        if (stable != null && !stable.isEmpty()) {
            String stableTrimmed = stable.trim();
            if (!stableTrimmed.isEmpty() && !stableTrimmed.contains("{")) {
                if (!"yes".equals(stableTrimmed) && !"no".equals(stableTrimmed) &&
                        !"true".equals(stableTrimmed) && !"false".equals(stableTrimmed) &&
                        !"1".equals(stableTrimmed) && !"0".equals(stableTrimmed)) {
                    throw new SAXException("XTSE0020: Invalid value for 'stable' attribute " +
                        "on xsl:sort: must be yes or no, got '" + stable + "'");
                }
            }
        }

        AttributeValueTemplate dataTypeAvt = dataType != null ? compiler.parseAvt(dataType) : null;
        AttributeValueTemplate orderAvt = order != null ? compiler.parseAvt(order) : null;
        AttributeValueTemplate caseOrderAvt = caseOrder != null ? compiler.parseAvt(caseOrder) : null;
        AttributeValueTemplate langAvt = lang != null ? compiler.parseAvt(lang) : null;
        AttributeValueTemplate collationAvt = collation != null ? compiler.parseAvt(collation) : null;

        SortSpec spec = new SortSpec(selectExpr, sortBody, dataTypeAvt, orderAvt, caseOrderAvt, langAvt, collationAvt);
        spec.setHasStable(stable != null);
        return new SortSpecNode(spec);
    }

    static void validateSortStable(List<SortSpec> sorts) throws SAXException {
        for (int i = 1; i < sorts.size(); i++) {
            if (sorts.get(i).hasStable()) {
                throw new SAXException("XTSE1017: The stable attribute is only " +
                    "allowed on the first xsl:sort element");
            }
        }
    }

    static XSLTNode compileNumber(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String valueAttr = ctx.attributes.get("value");
        XPathExpression valueExpr = null;

        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;

        String level = ctx.attributes.get("level");

        String countAttr = ctx.attributes.get("count");
        Pattern countPattern = null;

        String fromAttr = ctx.attributes.get("from");
        Pattern fromPattern = null;

        if (valueAttr != null) {
            if (selectAttr != null || level != null || countAttr != null || fromAttr != null) {
                throw new SAXException("XTSE0975: xsl:number value attribute cannot be " +
                    "used with select, level, count, or from attributes");
            }
            valueExpr = compiler.compileExpression(valueAttr);
        }

        if (selectAttr != null) {
            selectExpr = compiler.compileExpression(selectAttr);
        }

        if (level == null) {
            level = "single";
        }

        if (countAttr != null) {
            countPattern = compiler.compilePattern(countAttr);
        }

        if (fromAttr != null) {
            fromPattern = compiler.compilePattern(fromAttr);
        }

        String formatAttr = ctx.attributes.get("format");
        AttributeValueTemplate formatAVT;
        try {
            if (formatAttr == null) {
                formatAVT = AttributeValueTemplate.literal("1");
            } else {
                formatAVT = AttributeValueTemplate.parse(formatAttr, compiler,
                        compiler.buildStaticTypeContext());
            }
        } catch (XPathSyntaxException e) {
            throw new SAXException("Invalid format AVT: " + e.getMessage(), e);
        }

        String groupingSepAttr = ctx.attributes.get("grouping-separator");
        AttributeValueTemplate groupingSepAVT = null;
        if (groupingSepAttr != null) {
            try {
                groupingSepAVT = AttributeValueTemplate.parse(groupingSepAttr, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid grouping-separator AVT: " + e.getMessage(), e);
            }
        }
        String groupingSizeAttr = ctx.attributes.get("grouping-size");
        AttributeValueTemplate groupingSizeAVT = null;
        if (groupingSizeAttr != null) {
            try {
                groupingSizeAVT = AttributeValueTemplate.parse(groupingSizeAttr, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid grouping-size AVT: " + e.getMessage(), e);
            }
        }

        String langAttr = ctx.attributes.get("lang");
        AttributeValueTemplate langAVT = null;
        if (langAttr != null) {
            try {
                langAVT = AttributeValueTemplate.parse(langAttr, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid lang AVT: " + e.getMessage(), e);
            }
        }
        String letterValue = ctx.attributes.get("letter-value");
        String ordinalAttr = ctx.attributes.get("ordinal");
        AttributeValueTemplate ordinalAVT = null;
        if (ordinalAttr != null) {
            try {
                ordinalAVT = AttributeValueTemplate.parse(ordinalAttr, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid ordinal AVT: " + e.getMessage(), e);
            }
        }

        String startAtAttr = ctx.attributes.get("start-at");
        AttributeValueTemplate startAtAVT = null;
        if (startAtAttr != null) {
            try {
                startAtAVT = AttributeValueTemplate.parse(startAtAttr, compiler,
                        compiler.buildStaticTypeContext());
            } catch (XPathSyntaxException e) {
                throw new SAXException("Invalid start-at AVT: " + e.getMessage(), e);
            }
        }

        boolean backwardsCompatible = compiler.getEffectiveVersion() < 2.0;
        return new NumberNode(valueExpr, selectExpr, level, countPattern, fromPattern,
                             formatAVT, groupingSepAVT, groupingSizeAVT, langAVT, letterValue,
                             ordinalAVT, startAtAVT, backwardsCompatible);
    }

    static XSLTNode compileMessage(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String terminateValue = ctx.attributes.get("terminate");
        boolean terminateStatic = false;
        AttributeValueTemplate terminateAvt = null;

        if (terminateValue != null) {
            if (terminateValue.contains("{") && terminateValue.contains("}")) {
                terminateAvt = compiler.parseAvt(terminateValue);
            } else {
                compiler.validateYesOrNo("xsl:message", "terminate", terminateValue);
                terminateStatic = compiler.parseYesOrNo(terminateValue);
            }
        }

        String selectAttr = ctx.attributes.get("select");
        XPathExpression selectExpr = null;
        if (selectAttr != null) {
            selectExpr = compiler.compileExpression(selectAttr);
        }

        String errorCode = ctx.attributes.get("error-code");

        return new MessageNode(new SequenceNode(ctx.children), selectExpr, terminateStatic, terminateAvt, errorCode);
    }

    static XSLTNode compileFallback(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) {
        return new FallbackNode(new SequenceNode(ctx.children));
    }

    static XSLTNode compileVariable(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx, boolean isTopLevel) throws SAXException {
        if (isTopLevel) {
            compiler.importsAllowed = false;
            compiler.ensurePrecedenceAssigned();
        }

        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:variable requires name attribute");
        }

        String select = compiler.resolveStaticShadowAttribute(ctx, "select");
        String staticAttr = ctx.attributes.get("static");
        String asType = ctx.attributes.get("as");
        String visibilityAttr = ctx.attributes.get("visibility");
        compiler.validateYesOrNo("xsl:variable", "static", staticAttr);

        QName varName = compiler.parseQName(name, ctx.namespaceBindings, true);

        boolean isStatic = DeclarationCompiler.isStaticValue(staticAttr);

        if (isStatic && visibilityAttr != null) {
            throw new SAXException("XTSE0020: static='yes' must not be combined " +
                "with visibility attribute on xsl:variable");
        }

        if (isStatic && isTopLevel) {
            if (select != null && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
                throw new SAXException("XTSE0620: static xsl:variable must not have both " +
                    "a select attribute and non-empty content");
            }
            XPathValue staticValue = compiler.evaluateStaticExpression(select, varName.getLocalName(), ctx.baseURI);
            compiler.checkStaticDeclarationConflict(varName.getLocalName(), false,
                staticValue != null ? staticValue.asString() : null, compiler.importPrecedence);
            compiler.staticVariables.put(varName.getLocalName(), staticValue);
            try {
                compiler.builder.addGlobalVariable(new GlobalVariable(varName, false, staticValue, compiler.importPrecedence));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }

        compiler.validateFunctionTypeAs(asType);

        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);

        if (selectExpr != null && content != null && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0620: xsl:variable must not have both " +
                "a select attribute and non-empty content");
        }

        if (isTopLevel) {
            if (selectExpr != null) {
                compiler.checkSelfReference(selectExpr, varName.getLocalName());
            }
            GlobalVariable gv = new GlobalVariable(varName, false, selectExpr, content,
                compiler.importPrecedence, asType, ComponentVisibility.PUBLIC, false, ctx.baseURI);
            try {
                compiler.builder.addGlobalVariable(gv);
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            if (compiler.isInsideOverride()) {
                if (compiler.pendingOverrideDeclarations == null) {
                    compiler.pendingOverrideDeclarations = new ArrayList<OverrideDeclaration>();
                }
                compiler.pendingOverrideDeclarations.add(
                    OverrideDeclaration.forVariable(gv));
            }
            return null;
        }

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
        return new VariableNode(varName.getURI(), varName.getLocalName(), selectExpr, content, asType, parsedAsType);
    }

    static XSLTNode compileParam(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx, boolean isTopLevel) throws SAXException {
        if (isTopLevel) {
            compiler.importsAllowed = false;
            compiler.ensurePrecedenceAssigned();
        }

        if (!isTopLevel) {
            StylesheetCompiler.ElementContext parent = compiler.elementStack.isEmpty() ? null : compiler.elementStack.peek();
            if (parent == null || !StylesheetCompiler.XSLT_NS.equals(parent.namespaceURI) ||
                !("template".equals(parent.localName) ||
                  "function".equals(parent.localName) ||
                  "iterate".equals(parent.localName))) {
                throw new SAXException("XTSE0010: xsl:param is only allowed at the top level, " +
                    "or as a direct child of xsl:template, xsl:function, or xsl:iterate");
            }
        }

        String name = ctx.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new SAXException("XTSE0010: xsl:param requires name attribute");
        }

        String select = ctx.attributes.get("select");
        String asType = ctx.attributes.get("as");
        String staticAttr = ctx.attributes.get("static");
        String tunnelAttr = ctx.attributes.get("tunnel");
        String requiredAttr = ctx.attributes.get("required");
        compiler.validateYesOrNo("xsl:param", "tunnel", tunnelAttr);
        compiler.validateYesOrNo("xsl:param", "required", requiredAttr);
        compiler.validateYesOrNo("xsl:param", "static", staticAttr);
        boolean tunnel = compiler.parseYesOrNo(tunnelAttr);
        boolean required = compiler.parseYesOrNo(requiredAttr);

        if (tunnel && isTopLevel) {
            throw new SAXException("XTSE0020: tunnel='yes' is not allowed on a global parameter");
        }

        if (tunnel) {
            StylesheetCompiler.ElementContext parent = compiler.elementStack.isEmpty() ? null : compiler.elementStack.peek();
            if (parent != null && StylesheetCompiler.XSLT_NS.equals(parent.namespaceURI) && "function".equals(parent.localName)) {
                throw new SAXException("XTSE0020: tunnel='yes' is not allowed on a function parameter");
            }
        }

        if (requiredAttr != null && compiler.maxProcessorVersion < 3.0) {
            StylesheetCompiler.ElementContext parent = compiler.elementStack.isEmpty() ? null : compiler.elementStack.peek();
            if (parent != null && StylesheetCompiler.XSLT_NS.equals(parent.namespaceURI)
                    && "function".equals(parent.localName)) {
                throw new SAXException("XTSE0090: required attribute is not allowed " +
                    "on xsl:param inside xsl:function in XSLT " + compiler.maxProcessorVersion);
            }
        }

        if (requiredAttr != null && !required) {
            StylesheetCompiler.ElementContext parent = compiler.elementStack.isEmpty() ? null : compiler.elementStack.peek();
            if (parent != null && StylesheetCompiler.XSLT_NS.equals(parent.namespaceURI)
                    && "function".equals(parent.localName)) {
                throw new SAXException("XTSE0020: required='no' " +
                    "is not allowed on xsl:param inside xsl:function");
            }
        }

        if (required && select != null) {
            throw new SAXException("XTSE0010: A required parameter must not have a select attribute: " + name);
        }
        if (required && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0010: A required parameter must not have content: " + name);
        }

        boolean isStaticCheck = DeclarationCompiler.isStaticValue(staticAttr);
        if (isStaticCheck && !ctx.children.isEmpty() && compiler.hasNonWhitespaceContent(ctx.children)) {
            throw new SAXException("XTSE0010: A static parameter must not have content (use select attribute): " + name);
        }

        if (isStaticCheck && !isTopLevel) {
            throw new SAXException("XTSE0020: static='yes' is not allowed on " +
                "a non-global parameter: " + name);
        }

        QName paramName = compiler.parseQName(name, ctx.namespaceBindings, true);

        boolean isStatic = DeclarationCompiler.isStaticValue(staticAttr);

        if (isStatic && isTopLevel) {
            String overrideSelect = compiler.staticParameterOverrides.get(paramName.getLocalName());
            if (overrideSelect == null) {
                overrideSelect = compiler.staticParameterOverrides.get(name);
            }
            String effectiveSelect = overrideSelect != null ? overrideSelect : select;
            if (required && effectiveSelect == null) {
                throw new SAXException("XTDE0050: Required static parameter $" +
                    paramName.getLocalName() + " has no value");
            }
            if (effectiveSelect == null && asType != null) {
                String trimmedType = asType.trim();
                boolean optional = trimmedType.endsWith("?") || trimmedType.endsWith("*");
                if (!optional) {
                    throw new SAXException("XTDE0700: Static parameter $" +
                        paramName.getLocalName() + " has type " + asType +
                        " but no value was supplied");
                }
            }
            XPathValue staticValue = compiler.evaluateStaticExpression(effectiveSelect,
                    paramName.getLocalName(), ctx.baseURI);
            if (asType != null && staticValue != null && overrideSelect != null) {
                validateStaticParamType(staticValue, asType, paramName.getLocalName());
            }
            compiler.checkStaticDeclarationConflict(paramName.getLocalName(), true,
                staticValue != null ? staticValue.asString() : null, compiler.importPrecedence);
            compiler.staticVariables.put(paramName.getLocalName(), staticValue);
            try {
                compiler.builder.addGlobalVariable(new GlobalVariable(paramName, true, staticValue, compiler.importPrecedence));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }

        compiler.validateFunctionTypeAs(asType);

        XPathExpression selectExpr = select != null ? compiler.compileExpression(select) : null;
        SequenceNode content = ctx.children.isEmpty() ? null : new SequenceNode(ctx.children);

        if (isTopLevel) {
            try {
                compiler.builder.addGlobalVariable(new GlobalVariable(paramName, true, selectExpr, content,
                    compiler.importPrecedence, asType, ComponentVisibility.PUBLIC, required, ctx.baseURI));
            } catch (javax.xml.transform.TransformerConfigurationException e) {
                throw new SAXException(e.getMessage(), e);
            }
            return null;
        }

        return new ParamNode(paramName.getURI(), paramName.getLocalName(), selectExpr, content, asType, tunnel, required);
    }

    /**
     * XTTE0590: validates that a supplied static parameter value is compatible
     * with the declared type. Only simple atomic types are checked.
     */
    private static void validateStaticParamType(XPathValue value, String asType,
                                                String paramName) throws SAXException {
        String trimmed = asType.trim();
        boolean allowsEmpty = trimmed.endsWith("?") || trimmed.endsWith("*");
        String baseType = trimmed;
        if (trimmed.endsWith("?") || trimmed.endsWith("*") || trimmed.endsWith("+")) {
            baseType = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        boolean isEmpty = value.isSequence()
                && value instanceof org.bluezoo.gonzalez.transform.xpath.type.XPathSequence
                && ((org.bluezoo.gonzalez.transform.xpath.type.XPathSequence) value).isEmpty();
        if (isEmpty) {
            if (allowsEmpty) {
                return;
            }
            throw new SAXException("XTTE0590: Supplied value of static parameter $"
                + paramName + " is empty sequence but declared type " + asType
                + " does not allow empty");
        }
        boolean compatible = true;
        if ("xs:integer".equals(baseType) || "xs:int".equals(baseType)
                || "xs:long".equals(baseType) || "xs:short".equals(baseType)) {
            compatible = value.getType() == XPathValue.Type.NUMBER;
        } else if ("xs:string".equals(baseType)) {
            compatible = value.getType() == XPathValue.Type.STRING;
        } else if ("xs:boolean".equals(baseType)) {
            compatible = value.getType() == XPathValue.Type.BOOLEAN;
        } else if ("xs:double".equals(baseType) || "xs:float".equals(baseType)
                || "xs:decimal".equals(baseType)) {
            compatible = value.getType() == XPathValue.Type.NUMBER;
        }
        if (!compatible) {
            throw new SAXException("XTTE0590: Supplied value of static parameter $"
                + paramName + " (type " + value.getType()
                + ") is not compatible with declared type " + asType);
        }
    }

    static XSLTNode compileUnknownXSLTInstruction(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx,
            List<XSLTNode> fallbackNodes) throws SAXException {
        boolean effectiveFC = compiler.forwardCompatible || compiler.isElementForwardCompatible(ctx)
            || (compiler.maxProcessorVersion > 0 && compiler.stylesheetVersion > compiler.maxProcessorVersion);
        if (!effectiveFC && compiler.maxProcessorVersion > 0) {
            double elemVer = ctx.effectiveVersion > 0
                ? ctx.effectiveVersion : compiler.getEffectiveVersion();
            if (elemVer > compiler.maxProcessorVersion) {
                effectiveFC = true;
            }
        }
        if (effectiveFC) {
            if (!fallbackNodes.isEmpty()) {
                return new SequenceNode(fallbackNodes);
            }
            final String unknownName = ctx.localName;
            return new XSLTNode() {
                @Override
                public void execute(TransformContext context,
                        OutputHandler output) throws SAXException {
                    throw new SAXException("XTDE1450: Unknown XSLT instruction " +
                        "xsl:" + unknownName + " (no xsl:fallback)");
                }
            };
        }
        throw new SAXException("XTSE0010: Unknown XSLT element: xsl:" + ctx.localName);
    }
}
