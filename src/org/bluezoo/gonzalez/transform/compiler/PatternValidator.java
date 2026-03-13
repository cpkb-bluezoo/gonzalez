/*
 * PatternValidator.java
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
import java.util.List;
import java.util.Map;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.ast.LiteralText;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.ValueOfNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * Validates and compiles XSLT match patterns, and provides related
 * pattern/text parsing utilities.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class PatternValidator {

    private PatternValidator() {
    }

    static Pattern compilePattern(StylesheetCompiler compiler, String pattern)
            throws SAXException {
        validatePattern(compiler, pattern);

        if (pattern != null && pattern.contains("current-group()")) {
            throw new SAXException("XTSE1060: current-group() is not allowed in a pattern");
        }
        if (pattern != null && pattern.contains("current-grouping-key()")) {
            throw new SAXException("XTSE1070: current-grouping-key() is not allowed in a pattern");
        }
        if (pattern != null && pattern.contains("current-merge-group()")) {
            throw new SAXException("XTSE3470: current-merge-group() is not " +
                "allowed in a pattern");
        }
        if (pattern != null && pattern.contains("current-merge-key()")) {
            throw new SAXException("XTSE3500: current-merge-key() is not " +
                "allowed in a pattern");
        }

        String resolvedPattern = resolvePatternNamespaces(compiler, pattern);
        try {
            double patternVersion = 3.0;
            if (compiler.maxProcessorVersion > 0 && compiler.maxProcessorVersion < patternVersion) {
                patternVersion = compiler.maxProcessorVersion;
            }
            Pattern result = PatternParser.parse(resolvedPattern, patternVersion);
            deferPatternPredicateValidation(compiler, result, pattern);
            return result;
        } catch (IllegalArgumentException e) {
            throw new SAXException(e.getMessage());
        }
    }

    static void deferPatternPredicateValidation(StylesheetCompiler compiler,
            Pattern pat, String patternStr)
            throws SAXException {
        List<String> preds = new ArrayList<String>();
        collectPredicates(pat, preds);
        for (int i = 0; i < preds.size(); i++) {
            String pred = preds.get(i);
            List<String[]> funcRefs = resolveFunctionReferences(compiler, pred);
            for (int j = 0; j < funcRefs.size(); j++) {
                String[] ref = funcRefs.get(j);
                compiler.getDeferredPatternValidations().add(new String[]{ref[0], ref[1], patternStr});
            }
        }
    }

    static List<String[]> resolveFunctionReferences(StylesheetCompiler compiler, String pred)
            throws SAXException {
        List<String[]> refs = new ArrayList<String[]>();
        int len = pred.length();
        int i = 0;
        while (i < len) {
            char c = pred.charAt(i);
            if (c == '\'' || c == '"') {
                i++;
                while (i < len && pred.charAt(i) != c) {
                    i++;
                }
                i++;
                continue;
            }
            if (c == ':' && i + 1 < len && pred.charAt(i + 1) != ':') {
                int j = i + 1;
                while (j < len && Character.isWhitespace(pred.charAt(j))) {
                    j++;
                }
                if (j < len && Character.isLetter(pred.charAt(j))) {
                    int localStart = j;
                    int k = j;
                    while (k < len && (Character.isLetterOrDigit(pred.charAt(k)) ||
                           pred.charAt(k) == '-' || pred.charAt(k) == '_')) {
                        k++;
                    }
                    String localName = pred.substring(localStart, k);
                    while (k < len && Character.isWhitespace(pred.charAt(k))) {
                        k++;
                    }
                    if (k < len && pred.charAt(k) == '(') {
                        int prefStart = i - 1;
                        while (prefStart >= 0 &&
                               (Character.isLetterOrDigit(pred.charAt(prefStart)) ||
                                pred.charAt(prefStart) == '-' ||
                                pred.charAt(prefStart) == '_')) {
                            prefStart--;
                        }
                        prefStart++;
                        String prefix = pred.substring(prefStart, i);
                        if (!prefix.isEmpty() &&
                            !"xs".equals(prefix) &&
                            !"fn".equals(prefix) &&
                            !"math".equals(prefix) &&
                            !"map".equals(prefix) &&
                            !"array".equals(prefix)) {
                            String nsUri = compiler.lookupNamespaceUri(prefix);
                            if (nsUri == null) {
                                throw new SAXException(
                                    "XPST0017: Unknown namespace prefix '" + prefix +
                                    "' in pattern function call");
                            }
                            refs.add(new String[]{nsUri, localName});
                        }
                    }
                }
            }
            i++;
        }
        return refs;
    }

    static void validateDeferredPatternPredicates(StylesheetCompiler compiler) throws SAXException {
        List<String[]> validations = compiler.getDeferredPatternValidations();
        for (int i = 0; i < validations.size(); i++) {
            String[] entry = validations.get(i);
            String nsUri = entry[0];
            String localName = entry[1];
            String patternStr = entry[2];
            if (!isKnownFunction(compiler, nsUri, localName)) {
                throw new SAXException(
                    "XPST0017: Unknown function in pattern: " +
                    patternStr);
            }
        }
        validations.clear();
    }

    static String findUndeclaredFunction(StylesheetCompiler compiler, String pred) {
        int len = pred.length();
        int i = 0;
        while (i < len) {
            char c = pred.charAt(i);
            if (c == '\'' || c == '"') {
                i++;
                while (i < len && pred.charAt(i) != c) {
                    i++;
                }
                i++;
                continue;
            }
            if (c == ':' && i + 1 < len && pred.charAt(i + 1) != ':') {
                int j = i + 1;
                while (j < len && Character.isWhitespace(pred.charAt(j))) {
                    j++;
                }
                if (j < len && Character.isLetter(pred.charAt(j))) {
                    int localStart = j;
                    int k = j;
                    while (k < len && (Character.isLetterOrDigit(pred.charAt(k)) ||
                           pred.charAt(k) == '-' || pred.charAt(k) == '_')) {
                        k++;
                    }
                    String localName = pred.substring(localStart, k);
                    while (k < len && Character.isWhitespace(pred.charAt(k))) {
                        k++;
                    }
                    if (k < len && pred.charAt(k) == '(') {
                        int prefStart = i - 1;
                        while (prefStart >= 0 &&
                               (Character.isLetterOrDigit(pred.charAt(prefStart)) ||
                                pred.charAt(prefStart) == '-' ||
                                pred.charAt(prefStart) == '_')) {
                            prefStart--;
                        }
                        prefStart++;
                        String prefix = pred.substring(prefStart, i);
                        if (!prefix.isEmpty() &&
                            !"xs".equals(prefix) &&
                            !"fn".equals(prefix) &&
                            !"math".equals(prefix) &&
                            !"map".equals(prefix) &&
                            !"array".equals(prefix)) {
                            String nsUri = compiler.lookupNamespaceUri(prefix);
                            if (nsUri == null) {
                                return prefix + ":" + localName;
                            }
                            if (!isKnownFunction(compiler, nsUri, localName)) {
                                return prefix + ":" + localName;
                            }
                        }
                    }
                }
            }
            i++;
        }
        return null;
    }

    static boolean isKnownFunction(StylesheetCompiler compiler, String nsUri, String localName) {
        return compiler.builder.hasUserFunction(nsUri, localName);
    }

    static void collectPredicates(Pattern pat, List<String> preds) {
        if (pat instanceof AbstractPattern) {
            String predStr = ((AbstractPattern) pat).getPredicateStr();
            if (predStr != null) {
                preds.add(predStr);
            }
        }
        if (pat instanceof AtomicPattern) {
            List<String> atomicPreds =
                ((AtomicPattern) pat).getPredicates();
            preds.addAll(atomicPreds);
        }
        if (pat instanceof UnionPattern) {
            Pattern[] alts = ((UnionPattern) pat).getAlternatives();
            for (int i = 0; i < alts.length; i++) {
                collectPredicates(alts[i], preds);
            }
        }
        if (pat instanceof PredicatedPattern) {
            collectPredicates(((PredicatedPattern) pat).getInner(), preds);
        }
        if (pat instanceof IntersectPattern) {
            collectPredicates(((IntersectPattern) pat).getLeft(), preds);
            collectPredicates(((IntersectPattern) pat).getRight(), preds);
        }
        if (pat instanceof ExceptPattern) {
            collectPredicates(((ExceptPattern) pat).getLeft(), preds);
            collectPredicates(((ExceptPattern) pat).getRight(), preds);
        }
    }

    static void validatePattern(StylesheetCompiler compiler, String pattern) throws SAXException {
        String trimmed = pattern.trim();

        if (trimmed.startsWith("/[") || trimmed.contains("|/[")) {
            throw new SAXException("XPST0003: Pattern '/[predicate]' is not valid - root node cannot have a predicate");
        }

        if (trimmed.equals("/..") || trimmed.startsWith("/../") ||
            trimmed.contains("|/..") || trimmed.contains("| /..")) {
            throw new SAXException("XPST0003: Pattern '/..' is not valid - root node has no parent");
        }

        if (hasDisallowedPatternFunction(trimmed)) {
            throw new SAXException("XPST0017: Function not allowed at the start of a pattern");
        }

        if (hasFunctionAfterRoot(trimmed)) {
            throw new SAXException("XPST0017: Function call not allowed after '/' in pattern");
        }

        if (hasKeyWithNonLiteralArg(trimmed)) {
            throw new SAXException("XPST0017: key() in patterns must have literal arguments");
        }
    }

    static boolean hasDisallowedPatternFunction(String pattern) {
        String[] allowedFunctions = {"id(", "key(", "doc(", "root(", "element-with-id("};

        String[] nodeTests = {
            "element(", "attribute(", "document-node(", "schema-element(",
            "schema-attribute(", "processing-instruction(", "comment(",
            "text(", "node(", "namespace-node(", "function("
        };

        String[] segments = splitPatternByUnion(pattern);
        for (int segIdx = 0; segIdx < segments.length; segIdx++) {
            String seg = segments[segIdx].trim();
            int parenIdx = seg.indexOf('(');
            if (parenIdx > 0 && parenIdx < seg.length() - 1) {
                String possibleFunc = seg.substring(0, parenIdx + 1);
                if (seg.startsWith("/")) {
                    continue;
                }

                if (Character.isLetter(seg.charAt(0)) || seg.charAt(0) == '_') {
                    boolean isNodeTest = false;
                    for (int t = 0; t < nodeTests.length; t++) {
                        String test = nodeTests[t];
                        if (possibleFunc.equals(test)) {
                            isNodeTest = true;
                            break;
                        }
                    }
                    if (isNodeTest) {
                        continue;
                    }

                    boolean allowed = false;
                    for (int a = 0; a < allowedFunctions.length; a++) {
                        String allowedFunc = allowedFunctions[a];
                        if (possibleFunc.equals(allowedFunc) || possibleFunc.endsWith(":" + allowedFunc)) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed && !possibleFunc.contains("::")) {
                        if (isLikelyFunction(seg)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    static boolean isLikelyFunction(String s) {
        int parenIdx = s.indexOf('(');
        if (parenIdx <= 0) {
            return false;
        }
        String name = s.substring(0, parenIdx);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != ':') {
                return false;
            }
        }
        if (name.endsWith(":") && parenIdx == name.length()) {
            return false;
        }
        return true;
    }

    static boolean hasFunctionAfterRoot(String pattern) {
        String[] nodeTests = {
            "element", "attribute", "document-node", "schema-element",
            "schema-attribute", "processing-instruction", "comment",
            "text", "node", "namespace-node", "function"
        };

        String[] segments = splitPatternByUnion(pattern);
        for (int segIdx = 0; segIdx < segments.length; segIdx++) {
            String seg = segments[segIdx].trim();
            if (seg.startsWith("/") && !seg.startsWith("//") && seg.length() > 1) {
                String afterSlash = seg.substring(1).trim();
                if (afterSlash.length() > 0 && (Character.isLetter(afterSlash.charAt(0)) || afterSlash.charAt(0) == '_')) {
                    int parenIdx = afterSlash.indexOf('(');
                    if (parenIdx > 0) {
                        String funcName = afterSlash.substring(0, parenIdx);
                        if (!funcName.contains("::") && !funcName.contains("/")) {
                            boolean isNodeTest = false;
                            for (int t = 0; t < nodeTests.length; t++) {
                                String test = nodeTests[t];
                                if (funcName.equals(test)) {
                                    isNodeTest = true;
                                    break;
                                }
                            }
                            if (!isNodeTest) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    static boolean containsVariableRef(String pattern) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '\'' || c == '"')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote && c == '$') {
                return true;
            }
        }
        return false;
    }

    static boolean hasKeyWithNonLiteralArg(String pattern) {
        int idx = 0;
        while ((idx = pattern.indexOf("key(", idx)) != -1) {
            int depth = 0;
            for (int i = 0; i < idx; i++) {
                char c = pattern.charAt(i);
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                }
            }
            if (depth == 0) {
                int start = idx + 4;
                int parenDepth = 1;
                int commaPos = -1;
                int endPos = -1;

                for (int i = start; i < pattern.length() && parenDepth > 0; i++) {
                    char c = pattern.charAt(i);
                    if (c == '(') {
                        parenDepth++;
                    } else if (c == ')') {
                        parenDepth--;
                        if (parenDepth == 0) {
                            endPos = i;
                        }
                    } else if (c == ',' && parenDepth == 1 && commaPos == -1) {
                        commaPos = i;
                    }
                }

                if (commaPos > 0 && endPos > commaPos) {
                    String secondArg = pattern.substring(commaPos + 1, endPos).trim();
                    if (!isLiteralOrVariable(secondArg)) {
                        return true;
                    }
                }
            }
            idx++;
        }
        return false;
    }

    private static boolean isLiteralOrVariable(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return false;
        }
        if (v.startsWith("$") && v.length() > 1 && isValidQName(v.substring(1))) {
            return true;
        }
        if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
            return true;
        }
        try {
            Double.parseDouble(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isValidQName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.' && c != ':') {
                return false;
            }
        }
        return true;
    }

    private static String[] splitPatternByUnion(String pattern) {
        List<String> parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '|' && depth == 0) {
                parts.add(pattern.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(pattern.substring(start));
        return parts.toArray(new String[0]);
    }

    static String resolvePatternNamespaces(StylesheetCompiler compiler, String pattern)
            throws SAXException {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = pattern.length();
        boolean inAttributeContext = false;

        while (i < len) {
            char c = pattern.charAt(i);

            if (c == '/') {
                result.append(c);
                i++;
                inAttributeContext = false;
                continue;
            }

            if (c == '[') {
                int depth = 1;
                result.append(c);
                i++;
                while (i < len && depth > 0) {
                    c = pattern.charAt(i);
                    if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                    }
                    result.append(c);
                    i++;
                }
                inAttributeContext = false;
                continue;
            }

            if (c == '(') {
                String preceding = getPrecedingToken(result);
                boolean isKindTest = "element".equals(preceding) ||
                    "attribute".equals(preceding) ||
                    "schema-element".equals(preceding) ||
                    "schema-attribute".equals(preceding);

                if (isKindTest) {
                    int depth = 1;
                    result.append(c);
                    i++;
                    while (i < len && depth > 0) {
                        c = pattern.charAt(i);
                        if (c == '(') {
                            depth++;
                            result.append(c);
                            i++;
                        } else if (c == ')') {
                            depth--;
                            result.append(c);
                            i++;
                        } else if (depth == 1 && Character.isLetter(c)) {
                            int argStart = i;
                            while (i < len && pattern.charAt(i) != ',' &&
                                   pattern.charAt(i) != ')' &&
                                   !Character.isWhitespace(pattern.charAt(i))) {
                                i++;
                            }
                            String arg = pattern.substring(argStart, i).trim();
                            if (!"*".equals(arg)) {
                                try {
                                    arg = resolvePatternToken(compiler, arg, false);
                                } catch (SAXException e) {
                                }
                            }
                            result.append(arg);
                        } else {
                            result.append(c);
                            i++;
                        }
                    }
                } else {
                    int depth = 1;
                    result.append(c);
                    i++;
                    while (i < len && depth > 0) {
                        c = pattern.charAt(i);
                        if (c == '(') {
                            depth++;
                        } else if (c == ')') {
                            depth--;
                        }
                        result.append(c);
                        i++;
                    }
                }
                inAttributeContext = false;
                continue;
            }

            if (c == '|') {
                result.append(c);
                i++;
                inAttributeContext = false;
                continue;
            }

            if (Character.isWhitespace(c)) {
                result.append(c);
                i++;
                continue;
            }

            if (c == '@') {
                result.append(c);
                i++;
                inAttributeContext = true;
                continue;
            }

            int start = i;
            while (i < len) {
                c = pattern.charAt(i);
                if (c == 'Q' && i + 1 < len && pattern.charAt(i + 1) == '{') {
                    i += 2;
                    while (i < len && pattern.charAt(i) != '}') {
                        i++;
                    }
                    if (i < len) {
                        i++;
                    }
                    continue;
                }
                if (c == '/' || c == '[' || c == '(' || c == '|' || c == ')' || c == ']' ||
                    Character.isWhitespace(c)) {
                    break;
                }
                i++;
            }

            if (i > start) {
                String token = pattern.substring(start, i);
                boolean followedByParen = (i < len && pattern.charAt(i) == '(');
                if (followedByParen) {
                    result.append(token);
                } else {
                    String resolved = resolvePatternToken(compiler, token, inAttributeContext);
                    result.append(resolved);
                }
                inAttributeContext = false;
            }
        }

        return result.toString();
    }

    static String resolvePatternToken(StylesheetCompiler compiler, String token, boolean isAttribute)
            throws SAXException {
        int axisPos = token.indexOf("::");
        if (axisPos > 0) {
            String axis = token.substring(0, axisPos);
            String nameTest = token.substring(axisPos + 2);
            boolean attrAxis = "attribute".equals(axis);
            String resolvedNameTest = resolvePatternToken(compiler, nameTest, attrAxis);
            return axis + "::" + resolvedNameTest;
        }

        if ("*".equals(token)) {
            return token;
        }

        if (token.startsWith("*:")) {
            return token;
        }

        if (token.startsWith("Q{")) {
            int closeBrace = token.indexOf('}');
            if (closeBrace >= 2) {
                String uri = token.substring(2, closeBrace);
                String local = token.substring(closeBrace + 1);
                return "{" + uri + "}" + local;
            }
        }

        int colon = token.indexOf(':');
        if (colon > 0) {
            String prefix = token.substring(0, colon);
            String local = token.substring(colon + 1);

            String uri = compiler.resolveNamespaceForPrefix(prefix);
            if (uri != null) {
                return "{" + uri + "}" + local;
            }
            throw new SAXException("XTSE0280: Namespace prefix '" + prefix +
                "' in pattern '" + token + "' is not declared");
        }

        if (!isAttribute && !token.endsWith("()") && !token.contains("(")) {
            String defaultNs = compiler.getDefaultElementNamespace();
            if (defaultNs != null && !defaultNs.isEmpty()) {
                return "{" + defaultNs + "}" + token;
            }
        }
        return token;
    }

    private static String getPrecedingToken(StringBuilder sb) {
        int end = sb.length();
        if (end == 0) {
            return "";
        }
        int i = end - 1;
        while (i >= 0 && (Character.isLetterOrDigit(sb.charAt(i)) || sb.charAt(i) == '-')) {
            i--;
        }
        return sb.substring(i + 1, end);
    }

    static XSLTNode parseTextValueTemplate(StylesheetCompiler compiler, String text,
            StylesheetCompiler.ElementContext ctx) throws SAXException {
        List<XSLTNode> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '{') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                    literal.append('{');
                    i += 2;
                } else {
                    if (literal.length() > 0) {
                        nodes.add(new LiteralText(literal.toString()));
                        literal.setLength(0);
                    }

                    int start = i + 1;
                    int braceCount = 1;
                    i++;
                    boolean inString = false;
                    char stringDelim = 0;
                    boolean inComment = false;

                    while (i < text.length() && braceCount > 0) {
                        c = text.charAt(i);

                        if (!inString && !inComment && c == '(' && i + 1 < text.length() && text.charAt(i + 1) == ':') {
                            inComment = true;
                            i += 2;
                            continue;
                        }
                        if (inComment && c == ':' && i + 1 < text.length() && text.charAt(i + 1) == ')') {
                            inComment = false;
                            i += 2;
                            continue;
                        }

                        if (inComment) {
                            i++;
                            continue;
                        }

                        if (!inString && (c == '\'' || c == '"')) {
                            inString = true;
                            stringDelim = c;
                        } else if (inString && c == stringDelim) {
                            if (i + 1 < text.length() && text.charAt(i + 1) == stringDelim) {
                                i++;
                            } else {
                                inString = false;
                            }
                        }

                        if (!inString && !inComment) {
                            if (c == '{') {
                                braceCount++;
                            } else if (c == '}') {
                                braceCount--;
                            } else if (c == '<' && i + 1 < text.length()) {
                                char next = text.charAt(i + 1);
                                if (Character.isLetter(next) || next == '/' || next == '!' || next == '?') {
                                    throw new SAXException("XTSE0350: Element constructors are not allowed in text value templates");
                                }
                            }
                        }
                        i++;
                    }

                    if (braceCount != 0) {
                        throw new SAXException("XPST0003: Unmatched '{' in text value template");
                    }

                    String xpathExpr = text.substring(start, i - 1).trim();
                    if (xpathExpr.isEmpty() || AttributeValueTemplate.isEmptyExpression(xpathExpr)) {
                        continue;
                    }

                    try {
                        XPathExpression expr = compiler.compileExpression(xpathExpr);
                        nodes.add(new ValueOfNode(expr, false, " ", true));
                    } catch (SAXException e) {
                        throw new SAXException("XPST0003: Invalid XPath expression in text value template: " +
                                              e.getMessage(), e);
                    }
                }
            } else if (c == '}') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '}') {
                    literal.append('}');
                    i += 2;
                } else {
                    throw new SAXException("XPST0003: Unmatched '}' in text value template");
                }
            } else {
                literal.append(c);
                i++;
            }
        }

        if (literal.length() > 0) {
            nodes.add(new LiteralText(literal.toString()));
        }

        if (nodes.isEmpty()) {
            return null;
        } else if (nodes.size() == 1) {
            return nodes.get(0);
        } else {
            return new SequenceNode(nodes);
        }
    }

    static List<String> splitOnWhitespace(String s) {
        List<String> result = new ArrayList<>();
        if (s == null) {
            return result;
        }
        int len = s.length();
        int start = -1;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean isWhitespace = (c == ' ' || c == '\t' || c == '\n' || c == '\r');
            if (isWhitespace) {
                if (start >= 0) {
                    result.add(s.substring(start, i));
                    start = -1;
                }
            } else {
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (start >= 0) {
            result.add(s.substring(start));
        }
        return result;
    }

    public static String findOrGeneratePrefix(String namespace, Map<String, String> bindings) {
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            if (namespace.equals(entry.getValue()) && !entry.getKey().isEmpty()) {
                return entry.getKey();
            }
        }
        for (int i = 0; ; i++) {
            String candidate = "ns" + i;
            if (!bindings.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    public static String toCanonicalLexical(String typeName, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        try {
            if ("integer".equals(typeName) || "nonPositiveInteger".equals(typeName) ||
                "negativeInteger".equals(typeName) || "nonNegativeInteger".equals(typeName) ||
                "positiveInteger".equals(typeName) || "long".equals(typeName) ||
                "int".equals(typeName) || "short".equals(typeName) || "byte".equals(typeName) ||
                "unsignedLong".equals(typeName) || "unsignedInt".equals(typeName) ||
                "unsignedShort".equals(typeName) || "unsignedByte".equals(typeName)) {
                String trimmed = value.trim();
                boolean negative = trimmed.startsWith("-");
                boolean positive = trimmed.startsWith("+");
                if (negative || positive) {
                    trimmed = trimmed.substring(1);
                }
                int i = 0;
                while (i < trimmed.length() - 1 && trimmed.charAt(i) == '0') {
                    i++;
                }
                trimmed = trimmed.substring(i);
                if (negative && !"0".equals(trimmed)) {
                    return "-" + trimmed;
                }
                return trimmed;
            } else if ("decimal".equals(typeName)) {
                String trimmed = value.trim();
                if (trimmed.contains(".")) {
                    while (trimmed.endsWith("0") && !trimmed.endsWith(".0")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                    if (trimmed.endsWith(".")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                }
                return trimmed;
            } else if ("boolean".equals(typeName)) {
                String trimmed = value.trim();
                if ("1".equals(trimmed)) {
                    return "true";
                } else if ("0".equals(trimmed)) {
                    return "false";
                }
                return trimmed;
            } else if ("float".equals(typeName) || "double".equals(typeName)) {
                String trimmed = value.trim();
                if ("INF".equalsIgnoreCase(trimmed) || "+INF".equalsIgnoreCase(trimmed)) {
                    return "INF";
                } else if ("-INF".equalsIgnoreCase(trimmed)) {
                    return "-INF";
                } else if ("NaN".equalsIgnoreCase(trimmed)) {
                    return "NaN";
                }
                double d = Double.parseDouble(trimmed);
                if (d == (long) d) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            } else {
                return value.trim();
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    static boolean isValidXsDecimal(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int start = 0;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            start = 1;
        }
        if (start >= s.length()) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasDot = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c == '.' && !hasDot) {
                hasDot = true;
            } else {
                return false;
            }
        }
        return hasDigit;
    }

    /**
     * Checks if a pattern starts with parentheses at the top level (not inside predicates/functions).
     */
    static boolean hasTopLevelParentheses(String pattern) {
        String trimmed = pattern.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '(') {
            return false;
        }

        // XPath comments (:...:) are not parenthesized patterns
        if (trimmed.length() > 1 && trimmed.charAt(1) == ':') {
            return false;
        }

        // Find the matching closing paren
        int depth = 1;
        int i = 1;
        while (i < trimmed.length() && depth > 0) {
            char c = trimmed.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }

        // If the closing paren is at the end (or followed only by predicates), it's a top-level paren
        if (depth == 0) {
            String rest = trimmed.substring(i).trim();
            return rest.isEmpty() || rest.startsWith("[") || rest.startsWith("/");
        }
        return false;
    }

    /**
     * Checks if a pattern contains a variable reference ($var) as a path start (not in predicate or function args).
     *
     * <p>Valid uses of $var in patterns:
     * <ul>
     *   <li>key('name', $var) - variable as function argument</li>
     *   <li>foo[$var = 1] - variable inside predicate</li>
     * </ul>
     *
     * <p>Invalid in XSLT 2.0:
     * <ul>
     *   <li>$var//foo - variable as path start</li>
     *   <li>$var - variable as entire pattern</li>
     * </ul>
     */
    static boolean hasPatternVariableReference(String pattern) {
        int bracketDepth = 0;  // [] depth
        int parenDepth = 0;    // () depth
        int i = 0;
        int len = pattern.length();

        while (i < len) {
            char c = pattern.charAt(i);
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth--;
            } else if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
            } else if (c == '$' && bracketDepth == 0 && parenDepth == 0) {
                // Variable reference outside predicate and outside function arguments
                // Check if it's at the start of a pattern segment (after | or /)
                if (i == 0) {
                    return true;
                }
                char prev = pattern.charAt(i - 1);
                if (prev == '|' || prev == '/' || Character.isWhitespace(prev)) {
                    return true;
                }
            }
            i++;
        }
        return false;
    }

    /**
     * Checks if a pattern uses doc() function (not inside predicate).
     */
    static boolean hasDocFunctionInPattern(String pattern) {
        int depth = 0;
        int i = 0;
        int len = pattern.length();

        while (i < len) {
            char c = pattern.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (depth == 0 && i + 4 <= len) {
                String sub = pattern.substring(i, i + 4);
                if (sub.equals("doc(")) {
                    // Check if it's at the start of a pattern segment
                    if (i == 0) {
                        return true;
                    }
                    char prev = pattern.charAt(i - 1);
                    if (prev == '|' || prev == '/' || Character.isWhitespace(prev)) {
                        return true;
                    }
                }
            }
            i++;
        }
        return false;
    }

    static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }
}
