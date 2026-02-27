/*
 * PatternParser.java
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

/**
 * Static factory that parses an XSLT match-pattern string once and returns
 * the appropriate {@link Pattern} subclass with all components pre-parsed.
 *
 * <p>All string analysis happens here at compile time; the resulting
 * pattern objects perform no string parsing at match time.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class PatternParser {

    private PatternParser() {
    }

    /**
     * Parses a pattern string into a structured Pattern.
     * The string should already have namespace prefixes resolved by
     * {@code StylesheetCompiler.resolvePatternNamespaces()}.
     *
     * @param patternStr the pattern string
     * @return a Pattern that matches equivalently to the old SimplePattern
     */
    static Pattern parse(String patternStr) {
        String trimmed = patternStr.trim();

        // XSLT 3.0 atomic value pattern: .[ predicate ]
        if (trimmed.startsWith(".") && trimmed.length() > 1) {
            String afterDot = trimmed.substring(1).trim();
            if (afterDot.startsWith("[") && afterDot.endsWith("]")) {
                String pred = afterDot.substring(1,
                    afterDot.length() - 1).trim();
                return new AtomicPattern(patternStr, pred);
            }
        }

        // XTSE0340: namespace axis not allowed in patterns
        if (trimmed.contains("namespace::")) {
            throw new IllegalArgumentException("XTSE0340: namespace axis is not allowed in patterns: " + patternStr);
        }

        // XTSE0340: arithmetic expressions are not valid patterns
        if (isArithmeticExpression(trimmed)) {
            throw new IllegalArgumentException("XTSE0340: expression is not a valid pattern: " + patternStr);
        }

        // XTSE0340: a bare numeric literal is not a valid pattern
        if (isNumericLiteral(trimmed)) {
            throw new IllegalArgumentException("XTSE0340: numeric literal is not a valid pattern: " + patternStr);
        }

        // Normalize axis syntax
        String step1 = patternStr.replace("child::", "");
        String normalized = step1.replace("attribute::", "@");

        // Check for except (highest precedence of set operators)
        int exceptIdx = findKeywordOutsideBrackets(normalized,
            " except ", true);
        if (exceptIdx > 0) {
            String leftPart = normalized.substring(0, exceptIdx).trim();
            String rightPart = normalized.substring(exceptIdx + 8).trim();
            Pattern left = parse(leftPart);
            Pattern right = parse(rightPart);
            return new ExceptPattern(patternStr, left, right);
        }

        // Check for intersect
        int intersectIdx = findKeywordOutsideBrackets(normalized,
            " intersect ", true);
        if (intersectIdx > 0) {
            String leftPart = normalized.substring(0, intersectIdx).trim();
            String rightPart = normalized.substring(
                intersectIdx + 11).trim();
            Pattern left = parse(leftPart);
            Pattern right = parse(rightPart);
            return new IntersectPattern(patternStr, left, right);
        }

        // Check for "union" keyword
        int unionKeyIdx = findKeywordOutsideBrackets(normalized, " union ");
        if (unionKeyIdx > 0) {
            String leftPart = normalized.substring(0, unionKeyIdx).trim();
            String rightPart = normalized.substring(unionKeyIdx + 7).trim();
            Pattern left = parse(leftPart);
            Pattern right = parse(rightPart);
            return new UnionPattern(patternStr,
                new Pattern[] { left, right });
        }

        // Check for | union
        if (hasTopLevelUnion(normalized)) {
            String[] parts = splitUnion(normalized);
            Pattern[] alternatives = new Pattern[parts.length];
            for (int i = 0; i < parts.length; i++) {
                alternatives[i] = parse(parts[i].trim());
            }
            return new UnionPattern(patternStr, alternatives);
        }

        // Variable reference patterns (XSLT 3.0): $x, $x/path, $x//path
        if (normalized.startsWith("$")) {
            return parseVariablePattern(patternStr, normalized);
        }

        // doc() or document() function patterns
        if (normalized.startsWith("doc(") ||
            normalized.startsWith("document(")) {
            return parseDocPattern(patternStr, normalized);
        }

        // id() function patterns
        if (normalized.startsWith("id(")) {
            return parseIdPattern(patternStr, normalized);
        }

        // element-with-id() function patterns
        if (normalized.startsWith("element-with-id(")) {
            return parseElementWithIdPattern(patternStr, normalized);
        }

        // key() function patterns
        if (normalized.startsWith("key(")) {
            return parseKeyPattern(patternStr, normalized);
        }

        // Extract predicates from the normalized pattern for base matching
        String basePattern;
        String predicateStr;

        if (hasTopLevelUnion(normalized)) {
            basePattern = normalized;
            predicateStr = null;
        } else {
            int[] bracketRange = findPredicateRange(normalized);
            if (bracketRange != null) {
                int firstBracketStart = bracketRange[0];
                int lastBracketEnd = bracketRange[1];

                String afterFirst = normalized.substring(lastBracketEnd + 1);
                while (afterFirst.startsWith("[")) {
                    int[] nextRange = findPredicateRange(afterFirst);
                    if (nextRange != null) {
                        lastBracketEnd = lastBracketEnd + 1 + nextRange[1];
                        afterFirst = normalized.substring(
                            lastBracketEnd + 1);
                    } else {
                        break;
                    }
                }

                String afterAllPredicates = normalized.substring(
                    lastBracketEnd + 1);
                if (afterAllPredicates.isEmpty()) {
                    basePattern = normalized.substring(0,
                        firstBracketStart);
                    String allPreds = normalized.substring(
                        firstBracketStart, lastBracketEnd + 1);
                    predicateStr = combinePredicates(allPreds);
                } else {
                    basePattern = normalized;
                    predicateStr = null;
                }
            } else {
                basePattern = normalized;
                predicateStr = null;
            }
        }

        // Parenthesized pattern: (foo|bar)
        if (basePattern.startsWith("(") && basePattern.endsWith(")")) {
            String inner = basePattern.substring(1,
                basePattern.length() - 1);
            String[] parts = splitUnion(inner);
            Pattern[] alternatives = new Pattern[parts.length];
            for (int i = 0; i < parts.length; i++) {
                alternatives[i] = parse(parts[i].trim());
            }
            if (predicateStr != null) {
                // Wrap union with predicate (unusual but possible)
                return new UnionPattern(patternStr, alternatives);
            }
            return new UnionPattern(patternStr, alternatives);
        }

        // Root pattern: /
        if ("/".equals(basePattern)) {
            return RootPattern.INSTANCE;
        }

        // document-node() pattern
        if (basePattern.equals("document-node()") ||
            basePattern.startsWith("document-node(")) {
            return new DocumentNodePattern(patternStr);
        }

        // Patterns starting with //
        if (basePattern.startsWith("//")) {
            String rest = basePattern.substring(2);
            return parsePathFromDoubleSlash(patternStr, predicateStr, rest);
        }

        // Absolute patterns starting with /
        if (basePattern.startsWith("/") && !basePattern.startsWith("//")) {
            return parseAbsolutePath(patternStr, predicateStr,
                basePattern.substring(1));
        }

        // Descendant-or-self in the middle: ancestor//descendant
        int doubleSlash = findDoubleSlashOutsideBraces(basePattern);
        if (doubleSlash > 0) {
            return parseDescendantPath(patternStr, predicateStr,
                basePattern, doubleSlash);
        }

        // Step pattern: parent/child
        int slash = findLastSlashOutsideBraces(basePattern);
        if (slash > 0) {
            return parseStepPath(patternStr, predicateStr,
                basePattern, slash);
        }

        // Simple name/kind tests - delegate to NodeTest
        return parseSimpleTest(patternStr, predicateStr, basePattern);
    }

    // ---- Path pattern builders ----

    private static Pattern parsePathFromDoubleSlash(String patternStr,
                                                     String predicateStr,
                                                     String rest) {
        // //rest where rest may contain further path steps
        if (rest.contains("/")) {
            // //a/b treated as path pattern with descendant axis
            return parseRelativePath(patternStr, predicateStr, rest,
                PatternStep.AXIS_DESCENDANT);
        }
        // Simple //name - always priority 0.5 because it's a path pattern
        NodeTest nt;
        if ("node()".equals(rest)) {
            nt = ChildAxisAnyNodeTest.INSTANCE;
        } else {
            nt = NodeTest.parse(rest);
        }
        return new NameTestPattern(patternStr, predicateStr, nt, 0.5);
    }

    private static Pattern parseAbsolutePath(String patternStr,
                                              String predicateStr,
                                              String rest) {
        // Build a PathPattern with isAbsolute=true
        List<PatternStep> steps = new ArrayList<>();
        parseSteps(rest, steps);
        PatternStep[] arr = steps.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, predicateStr, arr, true);
    }

    private static Pattern parseDescendantPath(String patternStr,
                                                String predicateStr,
                                                String basePattern,
                                                int doubleSlashIdx) {
        String ancestorPart = basePattern.substring(0, doubleSlashIdx);
        String descendantPart = basePattern.substring(doubleSlashIdx + 2);

        List<PatternStep> steps = new ArrayList<>();

        // Parse ancestor steps
        parseSteps(ancestorPart, steps);

        // The next step after the ancestor has descendant axis
        List<PatternStep> descSteps = new ArrayList<>();
        parseSteps(descendantPart, descSteps);
        if (!descSteps.isEmpty()) {
            // Mark the first descendant step with DESCENDANT axis
            PatternStep first = descSteps.get(0);
            descSteps.set(0, new PatternStep(first.nodeTest,
                PatternStep.AXIS_DESCENDANT, first.predicateStr));
        }
        steps.addAll(descSteps);

        PatternStep[] arr = steps.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, predicateStr, arr, false);
    }

    private static Pattern parseStepPath(String patternStr,
                                          String predicateStr,
                                          String basePattern,
                                          int slashIdx) {
        String parentPart = basePattern.substring(0, slashIdx);
        String childPart = basePattern.substring(slashIdx + 1);

        // Check for explicit axis in childPart: doc/descendant::foo
        int axisIdx = childPart.indexOf("::");
        if (axisIdx > 0) {
            String axisName = childPart.substring(0, axisIdx);
            String axisNodeTest = childPart.substring(axisIdx + 2);

            int childAxis = PatternStep.AXIS_CHILD;
            if ("descendant".equals(axisName)) {
                childAxis = PatternStep.AXIS_DESCENDANT;
            } else if ("descendant-or-self".equals(axisName)) {
                childAxis = PatternStep.AXIS_DESCENDANT_OR_SELF;
            } else if ("self".equals(axisName)) {
                childAxis = PatternStep.AXIS_SELF;
            } else if ("attribute".equals(axisName)) {
                childAxis = PatternStep.AXIS_ATTRIBUTE;
            }

            List<PatternStep> steps = new ArrayList<>();
            parseSteps(parentPart, steps);
            // Extract predicate from axisNodeTest if present
            String stepPred = null;
            String testOnly = axisNodeTest;
            int[] predRange = findPredicateRange(axisNodeTest);
            if (predRange != null) {
                String afterPred = axisNodeTest.substring(
                    predRange[1] + 1);
                if (afterPred.isEmpty()) {
                    testOnly = axisNodeTest.substring(0, predRange[0]);
                    stepPred = axisNodeTest.substring(
                        predRange[0] + 1, predRange[1]);
                }
            }
            NodeTest nt = NodeTest.parse(testOnly);
            steps.add(new PatternStep(nt, childAxis, stepPred));

            PatternStep[] arr = steps.toArray(new PatternStep[0]);
            return new PathPattern(patternStr, predicateStr, arr, false);
        }

        // Normal parent/child
        List<PatternStep> steps = new ArrayList<>();
        parseSteps(parentPart, steps);

        // Parse child steps (may have predicates)
        List<PatternStep> childSteps = new ArrayList<>();
        parseSteps(childPart, childSteps);
        steps.addAll(childSteps);

        PatternStep[] arr = steps.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, predicateStr, arr, false);
    }

    private static Pattern parseRelativePath(String patternStr,
                                              String predicateStr,
                                              String rest,
                                              int firstAxis) {
        List<PatternStep> steps = new ArrayList<>();
        parseSteps(rest, steps);
        if (!steps.isEmpty() && firstAxis != PatternStep.AXIS_CHILD) {
            PatternStep first = steps.get(0);
            steps.set(0, new PatternStep(first.nodeTest, firstAxis,
                first.predicateStr));
        }
        PatternStep[] arr = steps.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, predicateStr, arr, false);
    }

    /**
     * Parses a path string like "a/b/c" or "a[pred]/b" into steps.
     * Handles predicates within individual steps and // operators.
     */
    private static void parseSteps(String path, List<PatternStep> steps) {
        String[] rawSteps = splitPathSteps(path);
        for (int i = 0; i < rawSteps.length; i++) {
            String raw = rawSteps[i];
            if (raw.isEmpty()) {
                // Empty step from // - next step gets descendant axis
                // The step after an empty step should have descendant axis
                continue;
            }

            int axis = PatternStep.AXIS_CHILD;
            // Check if previous step was empty (from //) meaning this is
            // descendant
            if (i > 0 && rawSteps[i - 1].isEmpty()) {
                axis = PatternStep.AXIS_DESCENDANT;
            }

            // Extract per-step predicate
            String stepPred = null;
            String testOnly = raw;
            int[] predRange = findPredicateRange(raw);
            if (predRange != null) {
                String afterPred = raw.substring(predRange[1] + 1);
                if (afterPred.isEmpty() || afterPred.startsWith("[")) {
                    testOnly = raw.substring(0, predRange[0]);
                    // Combine multiple predicates
                    int lastEnd = predRange[1];
                    while (afterPred.startsWith("[")) {
                        int[] nextRange = findPredicateRange(afterPred);
                        if (nextRange != null) {
                            lastEnd = lastEnd + 1 + nextRange[1];
                            afterPred = raw.substring(lastEnd + 1);
                        } else {
                            break;
                        }
                    }
                    String allPreds = raw.substring(predRange[0],
                        lastEnd + 1);
                    stepPred = combinePredicates(allPreds);
                }
            }

            // Check for explicit axis in step
            int axisIdx = testOnly.indexOf("::");
            if (axisIdx > 0) {
                String axisName = testOnly.substring(0, axisIdx);
                testOnly = testOnly.substring(axisIdx + 2);
                if ("descendant".equals(axisName)) {
                    axis = PatternStep.AXIS_DESCENDANT;
                } else if ("descendant-or-self".equals(axisName)) {
                    axis = PatternStep.AXIS_DESCENDANT_OR_SELF;
                } else if ("self".equals(axisName)) {
                    axis = PatternStep.AXIS_SELF;
                } else if ("attribute".equals(axisName)) {
                    axis = PatternStep.AXIS_ATTRIBUTE;
                }
            }

            // XTSE0340: numeric literals are not valid pattern steps
            if (!testOnly.isEmpty() && isNumericLiteral(testOnly)) {
                throw new IllegalArgumentException(
                    "XTSE0340: Invalid pattern step '" + testOnly + "' - numeric literal is not a valid node test");
            }

            NodeTest nt;
            if ("node()".equals(testOnly) &&
                axis != PatternStep.AXIS_ATTRIBUTE) {
                nt = ChildAxisAnyNodeTest.INSTANCE;
            } else {
                nt = NodeTest.parse(testOnly);
            }
            steps.add(new PatternStep(nt, axis, stepPred));
        }
    }

    private static Pattern parseSimpleTest(String patternStr,
                                            String predicateStr,
                                            String basePattern) {
        NodeTest nt;
        if ("node()".equals(basePattern)) {
            nt = ChildAxisAnyNodeTest.INSTANCE;
        } else {
            nt = NodeTest.parse(basePattern);
        }
        double priority = computeNameTestPriority(basePattern);
        return new NameTestPattern(patternStr, predicateStr, nt, priority);
    }

    // ---- Function pattern parsers ----

    private static Pattern parseVariablePattern(String patternStr,
                                                 String normalized) {
        int varEnd = 1;
        while (varEnd < normalized.length()) {
            char c = normalized.charAt(varEnd);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' &&
                c != '.') {
                break;
            }
            varEnd++;
        }

        String varName = normalized.substring(1, varEnd);
        String rest = normalized.substring(varEnd);

        if (rest.isEmpty()) {
            return new VariablePattern(patternStr, varName, null,
                VariablePattern.AXIS_NONE);
        }
        if (rest.startsWith("//")) {
            Pattern trailing = parse(rest.substring(2));
            return new VariablePattern(patternStr, varName, trailing,
                VariablePattern.AXIS_DESCENDANT);
        }
        if (rest.startsWith("/")) {
            Pattern trailing = parse(rest.substring(1));
            return new VariablePattern(patternStr, varName, trailing,
                VariablePattern.AXIS_CHILD);
        }
        return new VariablePattern(patternStr, varName, null,
            VariablePattern.AXIS_NONE);
    }

    private static Pattern parseDocPattern(String patternStr,
                                            String normalized) {
        int parenEnd = findMatchingParen(normalized,
            normalized.indexOf('('));
        if (parenEnd < 0) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String funcCall = normalized.substring(0, parenEnd + 1);
        String rest = normalized.substring(parenEnd + 1);

        if (rest.isEmpty()) {
            return new DocPattern(patternStr, funcCall, null,
                DocPattern.AXIS_NONE);
        }
        if (rest.startsWith("//")) {
            Pattern trailing = parse(rest.substring(2));
            return new DocPattern(patternStr, funcCall, trailing,
                DocPattern.AXIS_DESCENDANT);
        }
        if (rest.startsWith("/")) {
            Pattern trailing = parse(rest.substring(1));
            return new DocPattern(patternStr, funcCall, trailing,
                DocPattern.AXIS_CHILD);
        }
        return new DocPattern(patternStr, funcCall, null,
            DocPattern.AXIS_NONE);
    }

    private static Pattern parseIdPattern(String patternStr,
                                           String normalized) {
        int parenStart = normalized.indexOf('(');
        int parenEnd = findMatchingParen(normalized, parenStart);
        if (parenStart < 0 || parenEnd < 0) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String args = normalized.substring(parenStart + 1, parenEnd).trim();
        String rest = normalized.substring(parenEnd + 1);

        String[] argParts = splitArgs(args);
        if (argParts.length == 0) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String idArg = argParts[0].trim();
        idArg = stripQuotes(idArg);
        String[] ids = splitWhitespace(idArg);

        String docVarName = null;
        if (argParts.length > 1) {
            String docArg = argParts[1].trim();
            if (docArg.startsWith("$")) {
                docVarName = docArg.substring(1);
            }
        }

        if (rest.isEmpty()) {
            return new IdPattern(patternStr, ids, docVarName, null,
                IdPattern.AXIS_NONE);
        }
        if (rest.startsWith("//")) {
            Pattern trailing = parse(rest.substring(2));
            return new IdPattern(patternStr, ids, docVarName, trailing,
                IdPattern.AXIS_DESCENDANT);
        }
        if (rest.startsWith("/")) {
            Pattern trailing = parse(rest.substring(1));
            return new IdPattern(patternStr, ids, docVarName, trailing,
                IdPattern.AXIS_CHILD);
        }
        return new IdPattern(patternStr, ids, docVarName, null,
            IdPattern.AXIS_NONE);
    }

    private static Pattern parseElementWithIdPattern(String patternStr,
                                                      String normalized) {
        int parenStart = normalized.indexOf('(');
        int parenEnd = findMatchingParen(normalized, parenStart);
        if (parenStart < 0 || parenEnd < 0) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String args = normalized.substring(parenStart + 1, parenEnd).trim();
        String rest = normalized.substring(parenEnd + 1);

        String[] argParts = splitArgs(args);
        if (argParts.length == 0) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String idArg = argParts[0].trim();
        idArg = stripQuotes(idArg);
        String[] ids = splitWhitespace(idArg);

        String docVarName = null;
        if (argParts.length > 1) {
            String docArg = argParts[1].trim();
            if (docArg.startsWith("$")) {
                docVarName = docArg.substring(1);
            }
        }

        if (rest.isEmpty()) {
            return new ElementWithIdPattern(patternStr, ids, docVarName,
                null, ElementWithIdPattern.AXIS_NONE);
        }
        if (rest.startsWith("//")) {
            Pattern trailing = parse(rest.substring(2));
            return new ElementWithIdPattern(patternStr, ids, docVarName,
                trailing, ElementWithIdPattern.AXIS_DESCENDANT);
        }
        if (rest.startsWith("/")) {
            Pattern trailing = parse(rest.substring(1));
            return new ElementWithIdPattern(patternStr, ids, docVarName,
                trailing, ElementWithIdPattern.AXIS_CHILD);
        }
        return new ElementWithIdPattern(patternStr, ids, docVarName,
            null, ElementWithIdPattern.AXIS_NONE);
    }

    private static Pattern parseKeyPattern(String patternStr,
                                            String normalized) {
        int parenStart = normalized.indexOf('(');
        int parenEnd = findMatchingParen(normalized, parenStart);
        if (parenStart < 0 || parenEnd < 0) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String args = normalized.substring(parenStart + 1, parenEnd).trim();
        String rest = normalized.substring(parenEnd + 1);

        String[] argParts = splitArgs(args);
        if (argParts.length < 2) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String keyName = stripQuotes(argParts[0].trim());
        String keyValueArg = argParts[1].trim();

        boolean isVariable = keyValueArg.startsWith("$");
        String keyValueExpr;
        if (isVariable) {
            keyValueExpr = keyValueArg.substring(1);
        } else {
            keyValueExpr = stripQuotes(keyValueArg);
        }

        if (rest.isEmpty()) {
            return new KeyPattern(patternStr, keyName, keyValueExpr,
                isVariable, null, KeyPattern.AXIS_NONE);
        }
        if (rest.startsWith("//")) {
            Pattern trailing = parse(rest.substring(2));
            return new KeyPattern(patternStr, keyName, keyValueExpr,
                isVariable, trailing, KeyPattern.AXIS_DESCENDANT);
        }
        if (rest.startsWith("/")) {
            Pattern trailing = parse(rest.substring(1));
            return new KeyPattern(patternStr, keyName, keyValueExpr,
                isVariable, trailing, KeyPattern.AXIS_CHILD);
        }
        return new KeyPattern(patternStr, keyName, keyValueExpr,
            isVariable, null, KeyPattern.AXIS_NONE);
    }

    // ---- Priority computation ----

    private static double computeNameTestPriority(String test) {
        if ("*".equals(test) || "node()".equals(test) ||
            "@*".equals(test) || "text()".equals(test) ||
            "comment()".equals(test) ||
            "processing-instruction()".equals(test) ||
            "element()".equals(test) || "attribute()".equals(test) ||
            "element(*)".equals(test) || "attribute(*)".equals(test) ||
            "document-node()".equals(test)) {
            return -0.5;
        }
        if (test.startsWith("element(") && test.endsWith(")")) {
            String inner = test.substring(8, test.length() - 1).trim();
            if (inner.isEmpty() || "*".equals(inner)) {
                return -0.5;
            }
            return 0.0;
        }
        if (test.startsWith("attribute(") && test.endsWith(")")) {
            String inner = test.substring(10, test.length() - 1).trim();
            if (inner.isEmpty() || "*".equals(inner)) {
                return -0.5;
            }
            return 0.0;
        }
        if (test.contains(":*")) {
            return -0.25;
        }
        return 0.0;
    }

    // ---- Parsing utility methods ----

    static boolean hasTopLevelUnion(String pattern) {
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '[' || c == '(') {
                    depth++;
                } else if (c == ']' || c == ')') {
                    depth--;
                } else if (c == '|' && depth == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    static String[] splitUnion(String pattern) {
        List<String> parts = new ArrayList<>();
        int bracketDepth = 0;
        int parenDepth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '[') {
                    bracketDepth++;
                } else if (c == ']') {
                    bracketDepth--;
                } else if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                } else if (c == '|' && bracketDepth == 0 &&
                           parenDepth == 0) {
                    parts.add(pattern.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(pattern.substring(start));
        return parts.toArray(new String[0]);
    }

    static int findKeywordOutsideBrackets(String pattern, String keyword) {
        return findKeywordOutsideBrackets(pattern, keyword, false);
    }

    static int findKeywordOutsideBrackets(String pattern, String keyword,
                                           boolean findLast) {
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int lastFound = -1;

        for (int i = 0; i <= pattern.length() - keyword.length(); i++) {
            char c = pattern.charAt(i);

            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '[' || c == '(') {
                    bracketDepth++;
                } else if (c == ']' || c == ')') {
                    bracketDepth--;
                } else if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                } else if (bracketDepth == 0 && braceDepth == 0) {
                    if (pattern.regionMatches(i, keyword, 0,
                                              keyword.length())) {
                        if (!findLast) {
                            return i;
                        }
                        lastFound = i;
                    }
                }
            }
        }
        return findLast ? lastFound : -1;
    }

    static int findDoubleSlashOutsideBraces(String pattern) {
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < pattern.length() - 1; i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                } else if (c == '[' || c == '(') {
                    bracketDepth++;
                } else if (c == ']' || c == ')') {
                    bracketDepth--;
                } else if (braceDepth == 0 && bracketDepth == 0 &&
                           c == '/' && pattern.charAt(i + 1) == '/') {
                    return i;
                }
            }
        }
        return -1;
    }

    static int findLastSlashOutsideBraces(String pattern) {
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int lastSlash = -1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                } else if (c == '[' || c == '(') {
                    bracketDepth++;
                } else if (c == ']' || c == ')') {
                    bracketDepth--;
                } else if (braceDepth == 0 && bracketDepth == 0 &&
                           c == '/') {
                    lastSlash = i;
                }
            }
        }
        return lastSlash;
    }

    static int[] findPredicateRange(String pattern) {
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = -1;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '[') {
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        return new int[] { start, i };
                    }
                }
            }
        }
        return null;
    }

    static int findMatchingParen(String str, int openPos) {
        if (openPos < 0) {
            return -1;
        }
        int depth = 1;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = openPos + 1; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    static String[] splitArgs(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(args.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(args.substring(start));
        return parts.toArray(new String[0]);
    }

    static String[] splitPathSteps(String path) {
        List<String> steps = new ArrayList<>();
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                } else if (c == '[' || c == '(') {
                    bracketDepth++;
                } else if (c == ']' || c == ')') {
                    bracketDepth--;
                } else if (c == '/' && braceDepth == 0 &&
                           bracketDepth == 0) {
                    steps.add(path.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start < path.length()) {
            steps.add(path.substring(start));
        }
        return steps.toArray(new String[0]);
    }

    static String combinePredicates(String allPredicates) {
        List<String> preds = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < allPredicates.length(); i++) {
            char c = allPredicates.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote) {
                if (c == '[') {
                    if (depth == 0) {
                        start = i + 1;
                    }
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        preds.add(allPredicates.substring(start, i));
                        start = -1;
                    }
                }
            }
        }

        if (preds.size() == 1) {
            return preds.get(0);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < preds.size(); i++) {
            if (i > 0) {
                sb.append(" and ");
            }
            sb.append("(");
            sb.append(preds.get(i));
            sb.append(")");
        }
        return sb.toString();
    }

    private static String stripQuotes(String s) {
        if ((s.startsWith("'") && s.endsWith("'")) ||
            (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String[] splitWhitespace(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (i > start) {
                    parts.add(trimmed.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < trimmed.length()) {
            parts.add(trimmed.substring(start));
        }
        return parts.toArray(new String[0]);
    }

    /**
     * Checks if a string is a numeric literal (integer or decimal).
     */
    static boolean isNumericLiteral(String s) {
        if (s.isEmpty()) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasDot = false;
        for (int i = 0; i < s.length(); i++) {
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
     * Checks if a pattern string looks like an arithmetic expression rather
     * than a valid pattern. A '+' between two operands indicates arithmetic;
     * hyphens in names like 'processing-instruction' are not arithmetic.
     */
    static boolean isArithmeticExpression(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (depth == 0 && c == '+' && i > 0) {
                return true;
            }
        }
        return false;
    }
}
