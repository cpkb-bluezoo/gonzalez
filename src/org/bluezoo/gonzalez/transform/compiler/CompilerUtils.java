/*
 * CompilerUtils.java
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ast.FallbackNode;
import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.OnEmptyNode;
import org.bluezoo.gonzalez.transform.ast.OnNonEmptyNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;

/**
 * Shared validation and utility methods for the XSLT compiler.
 *
 * @author Chris Burdess
 */
final class CompilerUtils {

    private CompilerUtils() {
    }

    /** XSLT namespace URI. */
    static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";

    /** XML Schema namespace URI. */
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    /**
     * Allowed attributes for XSLT elements (XTSE0090 validation).
     * Maps element local name to set of allowed attribute local names.
     */
    static final Map<String, Set<String>> ALLOWED_ATTRIBUTES;
    static final Set<String> STANDARD_ATTRIBUTES;

    static {
        Map<String, Set<String>> attrs = new HashMap<String, Set<String>>();
        Set<String> standard = Collections.emptySet();

        try {
            InputStream is = CompilerUtils.class.getResourceAsStream("/META-INF/xslt-attributes.properties");
            if (is != null) {
                try {
                    Properties props = new Properties();
                    props.load(is);

                    for (String key : props.stringPropertyNames()) {
                        String value = props.getProperty(key);
                        Set<String> attrSet = new HashSet<String>();
                        if (value != null && !value.trim().isEmpty()) {
                            String[] parts = value.split(",");
                            for (int i = 0; i < parts.length; i++) {
                                String trimmed = parts[i].trim();
                                if (!trimmed.isEmpty()) {
                                    attrSet.add(trimmed);
                                }
                            }
                        }
                        if ("_standard".equals(key)) {
                            standard = Collections.unmodifiableSet(attrSet);
                        } else {
                            attrs.put(key, Collections.unmodifiableSet(attrSet));
                        }
                    }
                } finally {
                    is.close();
                }
            }
        } catch (IOException e) {
            // Properties file not found - attribute validation will be skipped
        }

        STANDARD_ATTRIBUTES = standard;
        ALLOWED_ATTRIBUTES = Collections.unmodifiableMap(attrs);
    }

    static void validateAllowedAttributes(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx) throws SAXException {
        Set<String> allowed = ALLOWED_ATTRIBUTES.get(ctx.localName);
        if (allowed == null) {
            return;
        }

        for (Map.Entry<String, String> attr : ctx.attributes.entrySet()) {
            String attrName = attr.getKey();
            String attrValue = attr.getValue();

            if (attrName.startsWith("xml:")) {
                continue;
            }

            int colonPos = attrName.indexOf(':');
            String localAttrName = attrName;
            if (colonPos > 0) {
                String prefix = attrName.substring(0, colonPos);
                localAttrName = attrName.substring(colonPos + 1);

                String attrNs = ctx.namespaceBindings.get(prefix);
                if (XSLT_NS.equals(attrNs)) {
                    if (compiler.isElementForwardCompatible(ctx)) {
                        continue;
                    }
                    throw new SAXException("XTSE0090: Attribute '" + attrName +
                        "' in the XSLT namespace is not allowed on xsl:" + ctx.localName);
                }

                continue;
            }

            if (!allowed.contains(localAttrName) && !STANDARD_ATTRIBUTES.contains(localAttrName)) {
                if (compiler.isElementForwardCompatible(ctx)) {
                    continue;
                }
                throw new SAXException("XTSE0090: Unknown attribute '" + attrName +
                    "' on xsl:" + ctx.localName);
            }

            if (isQNameAttribute(localAttrName, ctx.localName) && attrValue != null && !attrValue.isEmpty()) {
                if (!attrValue.contains("{") || attrValue.startsWith("Q{")) {
                    XSLTSchemaValidator.validateQName(localAttrName, attrValue);
                }
            }
        }
    }

    static boolean isQNameAttribute(String attrName, String elementName) {
        if ("name".equals(attrName)) {
            return !"package".equals(elementName) && !"use-package".equals(elementName);
        }
        return "mode".equals(attrName);
    }

    static void validateDistinctChars(String formatName, String... chars) throws SAXException {
        String[] names = {"decimal-separator", "grouping-separator", "percent", "per-mille", "zero-digit", "digit", "pattern-separator"};
        for (int i = 0; i < chars.length; i++) {
            for (int j = i + 1; j < chars.length; j++) {
                if (chars[i] != null && chars[j] != null && chars[i].equals(chars[j])) {
                    throw new SAXException("XTSE1300: In decimal-format" +
                        (formatName != null ? " '" + formatName + "'" : "") +
                        ", " + names[i] + " and " + names[j] + " must have distinct values (both are '" + chars[i] + "')");
                }
            }
        }
    }

    static void validateSingleChar(String attrName, String value) throws SAXException {
        if (value != null && value.codePointCount(0, value.length()) > 1) {
            throw new SAXException("XTSE0020: " + attrName + " must be a single character, got: '" + value + "'");
        }
    }

    static void validateDecimal(String value, String context) throws SAXException {
        if (value == null || value.isEmpty()) {
            return;
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '+' || c == '-') {
                if (i != 0) {
                    throw new SAXException("XTSE0530: " + context + " is not a valid xs:decimal: " + value);
                }
            } else if (c == '.') {
                // decimal point is allowed
            } else if (c >= '0' && c <= '9') {
                // digits are allowed
            } else {
                throw new SAXException("XTSE0530: " + context + " is not a valid xs:decimal: " + value);
            }
        }
    }

    static boolean isSequenceConstructorElement(String localName) {
        if ("template".equals(localName)) {
            return true;
        }
        if ("if".equals(localName)) {
            return true;
        }
        if ("when".equals(localName)) {
            return true;
        }
        if ("otherwise".equals(localName)) {
            return true;
        }
        if ("for-each".equals(localName)) {
            return true;
        }
        if ("for-each-group".equals(localName)) {
            return true;
        }
        if ("function".equals(localName)) {
            return true;
        }
        if ("matching-substring".equals(localName)) {
            return true;
        }
        if ("non-matching-substring".equals(localName)) {
            return true;
        }
        if ("variable".equals(localName)) {
            return true;
        }
        if ("param".equals(localName)) {
            return true;
        }
        if ("with-param".equals(localName)) {
            return true;
        }
        if ("element".equals(localName)) {
            return true;
        }
        if ("attribute".equals(localName)) {
            return true;
        }
        if ("comment".equals(localName)) {
            return true;
        }
        if ("processing-instruction".equals(localName)) {
            return true;
        }
        if ("namespace".equals(localName)) {
            return true;
        }
        if ("text".equals(localName)) {
            return true;
        }
        if ("copy".equals(localName)) {
            return true;
        }
        if ("fallback".equals(localName)) {
            return true;
        }
        if ("message".equals(localName)) {
            return true;
        }
        if ("result-document".equals(localName)) {
            return true;
        }
        if ("catch".equals(localName)) {
            return true;
        }
        if ("iterate".equals(localName)) {
            return true;
        }
        if ("on-completion".equals(localName)) {
            return true;
        }
        if ("try".equals(localName)) {
            return true;
        }
        if ("fork".equals(localName)) {
            return true;
        }
        if ("merge-action".equals(localName)) {
            return true;
        }
        if ("document".equals(localName)) {
            return true;
        }
        if ("sequence".equals(localName)) {
            return true;
        }
        if ("perform-sort".equals(localName)) {
            return true;
        }
        if ("sort".equals(localName)) {
            return true;
        }
        if ("where-populated".equals(localName)) {
            return true;
        }
        if ("on-empty".equals(localName)) {
            return true;
        }
        if ("on-non-empty".equals(localName)) {
            return true;
        }
        return false;
    }

    static boolean isEmptyRequiredElement(String localName) {
        if ("strip-space".equals(localName)) {
            return true;
        }
        if ("preserve-space".equals(localName)) {
            return true;
        }
        if ("import".equals(localName)) {
            return true;
        }
        if ("include".equals(localName)) {
            return true;
        }
        if ("output".equals(localName)) {
            return true;
        }
        if ("key".equals(localName)) {
            return true;
        }
        if ("decimal-format".equals(localName)) {
            return true;
        }
        if ("namespace-alias".equals(localName)) {
            return true;
        }
        if ("import-schema".equals(localName)) {
            return true;
        }
        if ("expose".equals(localName)) {
            return true;
        }
        if ("accept".equals(localName)) {
            return true;
        }
        return false;
    }

    static boolean hasRestrictedContentModel(String localName) {
        if ("apply-imports".equals(localName)) {
            return true;
        }
        if ("apply-templates".equals(localName)) {
            return true;
        }
        if ("call-template".equals(localName)) {
            return true;
        }
        if ("choose".equals(localName)) {
            return true;
        }
        if ("stylesheet".equals(localName)) {
            return true;
        }
        if ("transform".equals(localName)) {
            return true;
        }
        if ("package".equals(localName)) {
            return true;
        }
        if ("number".equals(localName)) {
            return true;
        }
        if ("sort".equals(localName)) {
            return true;
        }
        if ("merge".equals(localName)) {
            return true;
        }
        if ("merge-source".equals(localName)) {
            return true;
        }
        if ("accumulator".equals(localName)) {
            return true;
        }
        return false;
    }

    static boolean isElementWithOwnVersionAttr(String localName) {
        if ("output".equals(localName)) {
            return true;
        }
        if ("stylesheet".equals(localName)) {
            return true;
        }
        if ("transform".equals(localName)) {
            return true;
        }
        if ("package".equals(localName)) {
            return true;
        }
        return false;
    }

    static void checkNotReservedExtensionNamespace(String nsUri) throws SAXException {
        if (XSLT_NS.equals(nsUri)) {
            throw new SAXException("XTSE0085: The XSLT namespace cannot be used " +
                "as an extension namespace URI");
        }
        if (XSD_NAMESPACE.equals(nsUri)) {
            throw new SAXException("XTSE0085: The XML Schema namespace cannot be used " +
                "as an extension namespace URI");
        }
        if ("http://www.w3.org/XML/1998/namespace".equals(nsUri)) {
            throw new SAXException("XTSE0085: The XML namespace cannot be used " +
                "as an extension namespace URI");
        }
    }

    static void validateNotReservedNamespace(String elementName, String attrName, String qname,
                                              Map<String, String> namespaceBindings) throws SAXException {
        if (qname == null) {
            return;
        }

        int colonPos = qname.indexOf(':');
        if (colonPos > 0) {
            String prefix = qname.substring(0, colonPos);
            String localName = qname.substring(colonPos + 1);
            String namespaceURI = namespaceBindings.get(prefix);

            if (XSLT_NS.equals(namespaceURI)) {
                if ("initial-template".equals(localName)) {
                    return;
                }
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName +
                                      " cannot use the XSLT namespace");
            }
            if ("http://www.w3.org/1999/XSL/Format".equals(namespaceURI)) {
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName +
                                      " cannot use the XSL-FO namespace");
            }
            if ("http://www.w3.org/XML/1998/namespace".equals(namespaceURI)) {
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName +
                                      " cannot use the XML namespace");
            }
            if ("http://www.w3.org/2001/XMLSchema".equals(namespaceURI)) {
                throw new SAXException("XTSE0080: The " + attrName + " attribute on " + elementName +
                                      " cannot use the XML Schema namespace");
            }
        }
    }

    static void processExcludeResultPrefixes(StylesheetCompiler compiler, String excludePrefixes,
            Map<String, String> namespaces, StylesheetCompiler.ElementContext ctx) throws SAXException {
        String[] prefixes = excludePrefixes.trim().split("\\s+");
        for (int i = 0; i < prefixes.length; i++) {
            String prefix = prefixes[i];
            if (prefix.isEmpty()) {
                continue;
            }
            if ("#all".equals(prefix)) {
                for (Map.Entry<String, String> ns : namespaces.entrySet()) {
                    if (!XSLT_NS.equals(ns.getValue())) {
                        String uri = ns.getValue();
                        if (!compiler.excludedNamespaceURIs.contains(uri)) {
                            compiler.excludedNamespaceURIs.add(uri);
                            ctx.excludedByThisElement.add(uri);
                        }
                    }
                }
            } else if ("#default".equals(prefix)) {
                String defaultNs = namespaces.get("");
                if (defaultNs != null && !defaultNs.isEmpty()) {
                    if (!compiler.excludedNamespaceURIs.contains(defaultNs)) {
                        compiler.excludedNamespaceURIs.add(defaultNs);
                        ctx.excludedByThisElement.add(defaultNs);
                    }
                } else {
                    throw new SAXException("XTSE0809: #default used in " +
                        "exclude-result-prefixes but no default namespace is declared");
                }
            } else {
                String nsUri = namespaces.get(prefix);
                if (nsUri == null || nsUri.isEmpty()) {
                    throw new SAXException("XTSE0808: No namespace binding in scope for prefix '" + prefix + "' in exclude-result-prefixes");
                }
                if (!compiler.excludedNamespaceURIs.contains(nsUri)) {
                    compiler.excludedNamespaceURIs.add(nsUri);
                    ctx.excludedByThisElement.add(nsUri);
                }
            }
        }
    }

    static boolean shouldPreserveWhitespace(StylesheetCompiler compiler) {
        if (!compiler.elementStack.isEmpty()) {
            StylesheetCompiler.ElementContext current = compiler.elementStack.peek();
            if (XSLT_NS.equals(current.namespaceURI) && "text".equals(current.localName)) {
                return true;
            }
        }

        for (StylesheetCompiler.ElementContext ctx : compiler.elementStack) {
            String xmlSpace = ctx.attributes.get("xml:space");
            if ("preserve".equals(xmlSpace)) {
                return true;
            } else if ("default".equals(xmlSpace)) {
                return false;
            }
        }

        return false;
    }

    static void validateResultDocumentBoolean(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx, String attrName) throws SAXException {
        String value = ctx.attributes.get(attrName);
        if (value == null || value.isEmpty()) {
            return;
        }
        boolean isAvt = value.contains("{") && value.contains("}");
        if (!isAvt) {
            compiler.validateYesOrNo("xsl:result-document", attrName, value);
        }
    }

    static void validateNotTopLevel(StylesheetCompiler compiler, String elementName) throws SAXException {
        if (compiler.isTopLevel()) {
            throw new SAXException("XTSE0010: xsl:" + elementName +
                " is not allowed at the top level of a stylesheet");
        }
    }

    static void validateBreakNextIterationPosition(StylesheetCompiler compiler, StylesheetCompiler.ElementContext ctx)
            throws SAXException {
        java.util.List<XSLTNode> children = ctx.children;
        if (children.isEmpty()) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            XSLTNode child = children.get(i);
            boolean isBreak = (child instanceof org.bluezoo.gonzalez.transform.ast.BreakNode);
            boolean isNextIteration = (child instanceof org.bluezoo.gonzalez.transform.ast.NextIterationNode);
            if (!isBreak && !isNextIteration) {
                continue;
            }
            for (int j = i + 1; j < children.size(); j++) {
                XSLTNode next = children.get(j);
                if (next instanceof LiteralText) {
                    continue;
                }
                if (next instanceof FallbackNode) {
                    continue;
                }
                if (next instanceof org.bluezoo.gonzalez.transform.ast.CatchNode) {
                    continue;
                }
                if (next instanceof org.bluezoo.gonzalez.transform.ast.OnCompletionNode) {
                    continue;
                }
                String instruction = isBreak ?
                    "xsl:break" : "xsl:next-iteration";
                throw new SAXException("XTSE3120: " + instruction +
                    " must be the last instruction in its " +
                    "sequence constructor");
            }
        }
    }

    static void validateDirectChildOfIterate(StylesheetCompiler compiler, String instruction) throws SAXException {
        if (!compiler.elementStack.isEmpty()) {
            StylesheetCompiler.ElementContext parent = compiler.elementStack.peek();
            if (XSLT_NS.equals(parent.namespaceURI) && "iterate".equals(parent.localName)) {
                return;
            }
        }
        throw new SAXException("XTSE0010: " + instruction +
            " must be a direct child of xsl:iterate");
    }

    static void validateLexicallyInIterate(StylesheetCompiler compiler, String instruction) throws SAXException {
        for (StylesheetCompiler.ElementContext ancestor : compiler.elementStack) {
            if (XSLT_NS.equals(ancestor.namespaceURI)) {
                String name = ancestor.localName;
                if ("iterate".equals(name)) {
                    return;
                }
                if ("if".equals(name) || "choose".equals(name)
                    || "when".equals(name) || "otherwise".equals(name)
                    || "try".equals(name) || "catch".equals(name)
                    || "where-populated".equals(name)) {
                    continue;
                }
                throw new SAXException("XTSE3120: " + instruction +
                    " is not allowed here; it must be lexically within xsl:iterate");
            } else {
                throw new SAXException("XTSE3120: " + instruction +
                    " is not allowed inside a literal result element within xsl:iterate");
            }
        }
        throw new SAXException("XTSE3120: " + instruction +
            " is not allowed outside xsl:iterate");
    }

    static void validateOnEmptyOrdering(StylesheetCompiler compiler, StylesheetCompiler.ElementContext parentCtx,
                                          XSLTNode newNode) throws SAXException {
        boolean parentHasOnEmpty = false;
        for (int i = 0; i < parentCtx.children.size(); i++) {
            if (parentCtx.children.get(i) instanceof OnEmptyNode) {
                parentHasOnEmpty = true;
                break;
            }
        }
        if (parentHasOnEmpty) {
            if (!(newNode instanceof OnEmptyNode)) {
                throw new SAXException("XTSE0010: xsl:on-empty must be the "
                    + "last instruction in a sequence constructor");
            }
        }
        if (newNode instanceof OnNonEmptyNode) {
            if (parentHasOnEmpty) {
                throw new SAXException("XTSE0010: xsl:on-non-empty must come "
                    + "before xsl:on-empty in a sequence constructor");
            }
        }
    }

    static void flushCharacters(StylesheetCompiler compiler) throws SAXException {
        if (compiler.characterBuffer.length() > 0) {
            String text = compiler.characterBuffer.toString();
            compiler.characterBuffer.setLength(0);

            if (compiler.elementStack.isEmpty()) {
                if (!compiler.isWhitespace(text)) {
                    throw new SAXException("XTSE0120: Text is not allowed at the top level of a stylesheet");
                }
                return;
            }

            StylesheetCompiler.ElementContext ctx = compiler.elementStack.peek();

            if (compiler.isTopLevel() && !compiler.isWhitespace(text)) {
                throw new SAXException("XTSE0010: Text content is not allowed at the top level of a stylesheet");
            }

            if (compiler.isWhitespace(text) && XSLT_NS.equals(ctx.namespaceURI)
                    && !isSequenceConstructorElement(ctx.localName)) {
                String xmlSpace = ctx.attributes.get("xml:space");
                if (!"preserve".equals(xmlSpace) || !isEmptyRequiredElement(ctx.localName)) {
                    return;
                }
            }

            if (!compiler.isWhitespace(text) || shouldPreserveWhitespace(compiler)) {
                for (int i = 0; i < ctx.children.size(); i++) {
                    if (ctx.children.get(i) instanceof OnEmptyNode) {
                        throw new SAXException("XTSE0010: xsl:on-empty must be the "
                            + "last instruction in a sequence constructor");
                    }
                }
                if (ctx.expandText) {
                    try {
                        XSLTNode tvtNode = PatternValidator.parseTextValueTemplate(compiler, text, ctx);
                        if (tvtNode != null) {
                            ctx.children.add(tvtNode);
                        }
                    } catch (SAXException e) {
                        throw e;
                    }
                } else {
                    ctx.children.add(new LiteralText(text));
                }
            }
        }
    }
}
