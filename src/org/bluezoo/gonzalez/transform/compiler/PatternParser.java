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

import org.bluezoo.gonzalez.transform.xpath.XPathLexer;
import org.bluezoo.gonzalez.transform.xpath.XPathToken;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;

/**
 * Token-based parser for XSLT match-pattern strings.
 *
 * <p>Uses {@link XPathLexer} for proper tokenization, consuming tokens
 * in a single forward pass. The resulting {@link Pattern} objects are
 * fully pre-parsed for efficient matching at runtime.
 *
 * <p>All lexical analysis (comments, string literals, operator
 * disambiguation) is handled by the lexer. The parser only deals with
 * grammar-level structure: unions, paths, steps, predicates, and
 * function patterns.
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
        return parse(patternStr, 3.0);
    }

    static Pattern parse(String patternStr, double version) {
        String trimmed = patternStr.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                "XTSE0340: Empty pattern");
        }

        String prepared = prepareForLexer(trimmed);
        XPathLexer lexer = new XPathLexer(prepared);

        if (lexer.current() == XPathToken.EOF) {
            throw new IllegalArgumentException(
                "XTSE0340: Empty pattern: " + patternStr);
        }

        Pattern result = parseUnion(lexer, patternStr, prepared, version);

        if (lexer.current() != XPathToken.EOF) {
            throw new IllegalArgumentException(
                "XTSE0340: Unexpected token '" + lexer.value() +
                "' in pattern: " + patternStr);
        }
        return result;
    }

    // ---- Precedence levels: union < intersect < except ----

    private static Pattern parseUnion(XPathLexer lexer, String patternStr,
                                       String prepared, double version) {
        return parseUnion(lexer, patternStr, prepared, version, false);
    }

    private static Pattern parseUnion(XPathLexer lexer, String patternStr,
                                       String prepared, double version,
                                       boolean preserveSteps) {
        List<Pattern> alternatives = new ArrayList<>();
        alternatives.add(
            parseIntersect(lexer, patternStr, prepared, version,
                           preserveSteps));

        while (lexer.current() == XPathToken.PIPE ||
               lexer.current() == XPathToken.UNION) {
            if (lexer.current() == XPathToken.UNION && version < 3.0) {
                break;
            }
            lexer.advance();
            alternatives.add(
                parseIntersect(lexer, patternStr, prepared, version,
                               preserveSteps));
        }

        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }

        for (int i = 0; i < alternatives.size(); i++) {
            if (alternatives.get(i) instanceof AtomicPattern) {
                throw new IllegalArgumentException(
                    "XTSE0340: Predicate pattern cannot be combined " +
                    "with '|' union operator: " + patternStr);
            }
        }

        Pattern[] arr = alternatives.toArray(new Pattern[0]);
        return new UnionPattern(patternStr, arr);
    }

    private static Pattern parseIntersect(XPathLexer lexer, String patternStr,
                                           String prepared, double version,
                                           boolean preserveSteps) {
        Pattern left = parseExcept(lexer, patternStr, prepared, version,
                                   preserveSteps);

        while (version >= 3.0 && lexer.current() == XPathToken.INTERSECT) {
            lexer.advance();
            Pattern right = parseExcept(lexer, patternStr, prepared, version,
                                        preserveSteps);
            left = new IntersectPattern(patternStr, left, right);
        }
        return left;
    }

    private static Pattern parseExcept(XPathLexer lexer, String patternStr,
                                        String prepared, double version,
                                        boolean preserveSteps) {
        Pattern left = parseSingle(lexer, patternStr, prepared, version,
                                   preserveSteps);

        while (version >= 3.0 && lexer.current() == XPathToken.EXCEPT) {
            lexer.advance();
            Pattern right = parseSingle(lexer, patternStr, prepared, version,
                                        preserveSteps);
            left = new ExceptPattern(patternStr, left, right);
        }
        return left;
    }

    // ---- Single pattern (one path/function/variable/etc.) ----

    private static Pattern parseSingle(XPathLexer lexer, String patternStr,
                                        String prepared, double version) {
        return parseSingle(lexer, patternStr, prepared, version, false);
    }

    private static Pattern parseSingle(XPathLexer lexer, String patternStr,
                                        String prepared, double version,
                                        boolean preserveSteps) {
        XPathToken token = lexer.current();

        // XTSE0340: bare numeric literal is not a valid pattern
        if (token == XPathToken.NUMBER_LITERAL) {
            XPathToken next = lexer.peek();
            if (next == XPathToken.EOF || next == XPathToken.PIPE ||
                next == XPathToken.UNION || next == XPathToken.EXCEPT ||
                next == XPathToken.INTERSECT) {
                throw new IllegalArgumentException(
                    "XTSE0340: numeric literal is not a valid pattern: " +
                    patternStr);
            }
            // XTSE0340: arithmetic (e.g. 1 + 2)
            if (next == XPathToken.PLUS) {
                throw new IllegalArgumentException(
                    "XTSE0340: expression is not a valid pattern: " +
                    patternStr);
            }
        }

        // XTSE0340: arithmetic starting with +
        if (token == XPathToken.PLUS) {
            throw new IllegalArgumentException(
                "XTSE0340: expression is not a valid pattern: " +
                patternStr);
        }

        // Atomic/context-item pattern: . or .[pred] (XSLT 3.0, also 2.0 fwd-compat)
        if (token == XPathToken.DOT && version >= 2.0) {
            lexer.advance(); // consume .
            if (lexer.current() == XPathToken.LBRACKET) {
                List<String> preds = extractPredicateList(lexer, prepared);
                return new AtomicPattern(patternStr, preds);
            }
            return new AtomicPattern(patternStr, null);
        }

        // Variable reference pattern: $var (XSLT 3.0)
        if (token == XPathToken.DOLLAR) {
            if (version < 3.0) {
                throw new IllegalArgumentException(
                    "XTSE0340: Variable references are not allowed in XSLT " +
                    version + " patterns: " + patternStr);
            }
            return parseVariablePattern(lexer, patternStr, prepared, version);
        }

        // Root or absolute path
        if (token == XPathToken.SLASH) {
            lexer.advance();
            if (isPatternEnd(lexer)) {
                return RootPattern.INSTANCE;
            }
            return buildAbsolutePath(lexer, patternStr, prepared, version,
                                     false);
        }

        // Descendant-or-self path: //rest
        if (token == XPathToken.DOUBLE_SLASH) {
            lexer.advance();
            return buildAbsolutePath(lexer, patternStr, prepared, version,
                                     true);
        }

        // Parenthesized pattern: (pattern) (XSLT 3.0)
        if (token == XPathToken.LPAREN) {
            if (version < 3.0) {
                throw new IllegalArgumentException(
                    "XTSE0340: Parenthesized patterns are not allowed " +
                    "in XSLT " + version + " patterns: " + patternStr);
            }
            return parseParenthesized(lexer, patternStr, prepared, version);
        }

        // document-node() pattern
        if (token == XPathToken.DOCUMENT_NODE) {
            return parseDocumentNode(lexer, patternStr, prepared, version);
        }

        // XTSE0340: namespace axis not allowed in pre-3.0 patterns
        if (token == XPathToken.AXIS_NAMESPACE) {
            if (version < 3.0) {
                throw new IllegalArgumentException(
                    "XTSE0340: namespace axis is not allowed in patterns: " +
                    patternStr);
            }
            lexer.advance(); // consume namespace::
            XPathToken nameToken = lexer.current();
            if (nameToken == XPathToken.STAR) {
                lexer.advance(); // consume *
                return new NameTestPattern(patternStr, null,
                                           AnyNodeTest.INSTANCE, -0.5);
            }
            if (nameToken == XPathToken.NCNAME || isXPathKeyword(nameToken)) {
                String nsName = lexer.value();
                lexer.advance(); // consume name
                return new NameTestPattern(patternStr, null,
                                           new ElementTest(null, nsName, null),
                                           -0.5);
            }
            // Fallback: treat as wildcard
            return new NameTestPattern(patternStr, null,
                                       AnyNodeTest.INSTANCE, -0.5);
        }

        // Function patterns: id(), key(), doc(), document(), element-with-id()
        if (token == XPathToken.NCNAME || isXPathKeyword(token)) {
            String name = lexer.value();
            XPathToken next = lexer.peek();
            if (next == XPathToken.LPAREN) {
                if ("id".equals(name)) {
                    return parseIdPattern(lexer, patternStr, prepared,
                                          version);
                }
                if ("element-with-id".equals(name)) {
                    return parseElementWithIdPattern(lexer, patternStr,
                                                     prepared, version);
                }
                if ("key".equals(name)) {
                    return parseKeyPattern(lexer, patternStr, prepared,
                                           version);
                }
                if ("root".equals(name)) {
                    return parseRootFuncPattern(lexer, patternStr,
                                                prepared, version);
                }
                if ("doc".equals(name) || "document".equals(name)) {
                    if (version < 3.0) {
                        throw new IllegalArgumentException(
                            "XTSE0340: " + name +
                            "() is not allowed in XSLT " + version +
                            " patterns: " + patternStr);
                    }
                    return parseDocFuncPattern(lexer, patternStr, prepared,
                                               version);
                }
            }
        }

        // Path pattern (single step or multi-step)
        return parsePathPatternInner(lexer, patternStr, prepared, version,
                                     preserveSteps, false);
    }

    // ---- Path pattern: step / step // step ----

    private static Pattern parsePathPattern(XPathLexer lexer,
                                             String patternStr,
                                             String prepared,
                                             double version) {
        return parsePathPatternInner(lexer, patternStr, prepared, version,
                                     false, false);
    }

    /**
     * Core relative path pattern parser.
     *
     * @param preserveSteps if true, single-step patterns are returned as
     *        PathPattern rather than collapsing to NameTestPattern (used
     *        inside parenthesized groups to preserve axis info)
     * @param isAbsolute whether this path starts from the document root
     */
    private static Pattern parsePathPatternInner(XPathLexer lexer,
            String patternStr, String prepared, double version,
            boolean preserveSteps, boolean isAbsolute) {
        // Parenthesized group at the start of a relative path: (a|b)/...
        if (lexer.current() == XPathToken.LPAREN && version >= 3.0) {
            List<PatternStep> emptyPrefix = new ArrayList<>();
            return expandParenthesizedStep(lexer, patternStr, prepared,
                version, emptyPrefix, Step.Axis.CHILD, isAbsolute);
        }

        List<PatternStep> steps = new ArrayList<>();
        steps.add(parseStep(lexer, patternStr, prepared, Step.Axis.CHILD));

        boolean hasPath = false;
        while (lexer.current() == XPathToken.SLASH ||
               lexer.current() == XPathToken.DOUBLE_SLASH) {
            hasPath = true;
            Step.Axis nextAxis;
            if (lexer.current() == XPathToken.DOUBLE_SLASH) {
                nextAxis = Step.Axis.DESCENDANT;
            } else {
                nextAxis = Step.Axis.CHILD;
            }
            lexer.advance();

            // Parenthesized group in step position: x/(a|b)/...
            if (lexer.current() == XPathToken.LPAREN && version >= 3.0) {
                return expandParenthesizedStep(lexer, patternStr, prepared,
                    version, steps, nextAxis, isAbsolute);
            }

            steps.add(parseStep(lexer, patternStr, prepared, nextAxis));
        }

        if (!hasPath && steps.size() == 1 && !preserveSteps) {
            PatternStep only = steps.get(0);
            double priority = computePriority(only);
            return new NameTestPattern(patternStr, only.predicateStr,
                                       only.nodeTest, priority);
        }

        PatternStep[] arr = steps.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, null, arr, isAbsolute);
    }

    private static Pattern buildAbsolutePath(XPathLexer lexer,
                                              String patternStr,
                                              String prepared,
                                              double version,
                                              boolean isDoubleSlash) {
        List<PatternStep> steps = new ArrayList<>();
        Step.Axis firstAxis = isDoubleSlash ?
            Step.Axis.DESCENDANT : Step.Axis.CHILD;

        // First step might be parenthesized: //(a|b)
        if (lexer.current() == XPathToken.LPAREN && version >= 3.0) {
            return expandParenthesizedStep(lexer, patternStr, prepared,
                version, steps, firstAxis, true);
        }

        steps.add(parseStep(lexer, patternStr, prepared, firstAxis));

        while (lexer.current() == XPathToken.SLASH ||
               lexer.current() == XPathToken.DOUBLE_SLASH) {
            Step.Axis nextAxis;
            if (lexer.current() == XPathToken.DOUBLE_SLASH) {
                nextAxis = Step.Axis.DESCENDANT;
            } else {
                nextAxis = Step.Axis.CHILD;
            }
            lexer.advance();

            // Parenthesized group: /x/(a|b)/...
            if (lexer.current() == XPathToken.LPAREN && version >= 3.0) {
                return expandParenthesizedStep(lexer, patternStr, prepared,
                    version, steps, nextAxis, true);
            }

            steps.add(parseStep(lexer, patternStr, prepared, nextAxis));
        }

        if (isDoubleSlash && steps.size() == 1) {
            PatternStep only = steps.get(0);
            NodeTest nt = only.nodeTest;
            String pred = only.predicateStr;
            return new NameTestPattern(patternStr, pred, nt, 0.5, true);
        }

        PatternStep[] arr = steps.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, null, arr, true);
    }

    /**
     * Handles a parenthesized group in step position within a path pattern.
     * Expands patterns like {@code x/(a|b)/text()} into a union of
     * complete paths: {@code x/a/text() | x/b/text()}.
     *
     * @param prefixSteps steps parsed before the parenthesized group
     * @param parenAxis the axis inherited from the preceding / or //
     * @param isAbsolute whether the path is absolute (starts with /)
     */
    private static Pattern expandParenthesizedStep(XPathLexer lexer,
            String patternStr, String prepared, double version,
            List<PatternStep> prefixSteps, Step.Axis parenAxis,
            boolean isAbsolute) {
        // Parse the parenthesized inner pattern, preserving step axes
        lexer.advance(); // consume (
        Pattern inner = parseUnion(lexer, patternStr, prepared, version,
                                   true);
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
        }

        // Optional predicates after the parenthesized group
        String parenPred = null;
        if (lexer.current() == XPathToken.LBRACKET) {
            parenPred = extractAllPredicates(lexer, prepared);
        }

        // Parse any suffix steps (e.g. /text() after (a|b))
        List<PatternStep> suffixSteps = new ArrayList<>();
        while (lexer.current() == XPathToken.SLASH ||
               lexer.current() == XPathToken.DOUBLE_SLASH) {
            Step.Axis nextAxis;
            if (lexer.current() == XPathToken.DOUBLE_SLASH) {
                nextAxis = Step.Axis.DESCENDANT;
            } else {
                nextAxis = Step.Axis.CHILD;
            }
            lexer.advance();
            suffixSteps.add(parseStep(lexer, patternStr, prepared, nextAxis));
        }

        // Apply predicates to the inner pattern if any
        if (parenPred != null) {
            inner = new PredicatedPattern(patternStr, parenPred, inner);
        }

        // Distribute prefix and suffix across inner alternatives
        return distributePathSteps(patternStr, inner, prefixSteps,
            suffixSteps, parenAxis, isAbsolute);
    }

    /**
     * Distributes prefix/suffix path steps across a parenthesized pattern.
     * For union patterns, creates expanded alternatives. For except and
     * intersect, creates a contextual pattern that evaluates both arms
     * relative to the same ancestor (simple expansion is incorrect for
     * these operators).
     */
    private static Pattern distributePathSteps(String patternStr,
            Pattern inner, List<PatternStep> prefix,
            List<PatternStep> suffix, Step.Axis parenAxis,
            boolean isAbsolute) {
        // For union patterns, expand each alternative
        if (inner instanceof UnionPattern) {
            Pattern[] alts = ((UnionPattern) inner).getAlternatives();
            Pattern[] expanded = new Pattern[alts.length];
            for (int i = 0; i < alts.length; i++) {
                expanded[i] = wrapAlternativeInPath(patternStr, alts[i],
                    prefix, suffix, parenAxis, isAbsolute);
            }
            return new UnionPattern(patternStr, expanded);
        }

        // For except/intersect, both arms must be evaluated relative
        // to the same ancestor context. Create a ParenStepPathPattern.
        if (inner instanceof ExceptPattern) {
            ExceptPattern ep = (ExceptPattern) inner;
            return buildParenStepPattern(patternStr, prefix, suffix,
                ep.getLeft(), ep.getRight(), true, parenAxis, isAbsolute);
        }

        if (inner instanceof IntersectPattern) {
            IntersectPattern ip = (IntersectPattern) inner;
            return buildParenStepPattern(patternStr, prefix, suffix,
                ip.getLeft(), ip.getRight(), false, parenAxis, isAbsolute);
        }

        // Single pattern: wrap into a path
        return wrapAlternativeInPath(patternStr, inner, prefix, suffix,
            parenAxis, isAbsolute);
    }

    /**
     * Builds a ParenStepPathPattern for except/intersect within a path.
     */
    private static Pattern buildParenStepPattern(String patternStr,
            List<PatternStep> prefix, List<PatternStep> suffix,
            Pattern leftArm, Pattern rightArm, boolean isExcept,
            Step.Axis parenAxis, boolean isAbsolute) {
        // Extract a single step from each arm
        List<PatternStep> leftSteps = extractSteps(leftArm, parenAxis);
        List<PatternStep> rightSteps = extractSteps(rightArm, parenAxis);

        PatternStep leftStep;
        if (!leftSteps.isEmpty()) {
            leftStep = leftSteps.get(leftSteps.size() - 1);
        } else {
            leftStep = new PatternStep(ChildAxisAnyNodeTest.INSTANCE,
                                        parenAxis, null);
        }

        PatternStep rightStep;
        if (!rightSteps.isEmpty()) {
            rightStep = rightSteps.get(rightSteps.size() - 1);
        } else {
            rightStep = new PatternStep(ChildAxisAnyNodeTest.INSTANCE,
                                         parenAxis, null);
        }

        PatternStep[] prefixArr = prefix.toArray(new PatternStep[0]);
        PatternStep[] suffixArr = suffix.toArray(new PatternStep[0]);

        return new ParenStepPathPattern(patternStr, prefixArr, suffixArr,
            leftStep, rightStep, isExcept, parenAxis, isAbsolute);
    }

    /**
     * Wraps a single alternative pattern into a full path by combining
     * prefix steps, the alternative's own steps, and suffix steps.
     */
    private static Pattern wrapAlternativeInPath(String patternStr,
            Pattern alt, List<PatternStep> prefix,
            List<PatternStep> suffix, Step.Axis parenAxis,
            boolean isAbsolute) {
        // Extract steps from the alternative
        List<PatternStep> altSteps = extractSteps(alt, parenAxis);

        // Build combined path: prefix + altSteps + suffix
        List<PatternStep> combined = new ArrayList<>();
        combined.addAll(prefix);
        combined.addAll(altSteps);
        combined.addAll(suffix);

        if (combined.size() == 1 && !isAbsolute) {
            PatternStep only = combined.get(0);
            double priority = computePriority(only);
            return new NameTestPattern(patternStr, only.predicateStr,
                                       only.nodeTest, priority);
        }

        PatternStep[] arr = combined.toArray(new PatternStep[0]);
        return new PathPattern(patternStr, null, arr, isAbsolute);
    }

    /**
     * Extracts PatternStep(s) from a pattern for path combination.
     * A NameTestPattern produces one step; a PathPattern produces its
     * steps array; other types get wrapped as a single step.
     */
    private static List<PatternStep> extractSteps(Pattern pat,
            Step.Axis defaultAxis) {
        List<PatternStep> result = new ArrayList<>();

        if (pat instanceof NameTestPattern) {
            NameTestPattern ntp = (NameTestPattern) pat;
            result.add(new PatternStep(ntp.getNodeTest(), defaultAxis,
                                       ntp.getPredicateStr()));
            return result;
        }

        if (pat instanceof PathPattern) {
            PatternStep[] steps = ((PathPattern) pat).getSteps();
            for (int i = 0; i < steps.length; i++) {
                PatternStep s = steps[i];
                // For the first step, use the inherited axis from the
                // parenthesized context (e.g. // before the paren)
                if (i == 0 && s.axis == Step.Axis.CHILD) {
                    result.add(new PatternStep(s.nodeTest, defaultAxis,
                                               s.predicateStr));
                } else {
                    result.add(s);
                }
            }
            return result;
        }

        if (pat instanceof PredicatedPattern) {
            PredicatedPattern pp = (PredicatedPattern) pat;
            List<PatternStep> innerSteps = extractSteps(pp.getInner(),
                                                         defaultAxis);
            // Apply the predicate to the last step
            if (!innerSteps.isEmpty()) {
                int lastIdx = innerSteps.size() - 1;
                PatternStep last = innerSteps.get(lastIdx);
                String combinedPred = last.predicateStr;
                String ppPred = pp.getPredicateStr();
                if (combinedPred != null && ppPred != null) {
                    combinedPred = "(" + combinedPred + ") and (" +
                                   ppPred + ")";
                } else if (ppPred != null) {
                    combinedPred = ppPred;
                }
                innerSteps.set(lastIdx, new PatternStep(last.nodeTest,
                    last.axis, combinedPred));
            }
            return innerSteps;
        }

        // Fallback: wrap the pattern's toString as a single element step
        NodeTest nt = new ElementTest(null, null, null);
        result.add(new PatternStep(nt, defaultAxis, null));
        return result;
    }

    // ---- Step parsing: axis + node-test + predicates ----

    private static PatternStep parseStep(XPathLexer lexer, String patternStr,
                                          String prepared,
                                          Step.Axis defaultAxis) {
        Step.Axis axis = defaultAxis;
        XPathToken token = lexer.current();
        boolean explicitChild = false;
        boolean explicitDescendant = false;

        // Explicit axis: child::, descendant::, self::, attribute::
        // child:: and self:: should not override the context axis from
        // / or // — they only add a node test constraint. For example,
        // //child::foo and //self::baz should keep DESCENDANT from //.
        if (token.isAxis()) {
            Step.Axis explicitAxis = mapAxis(token, patternStr);
            if (explicitAxis == Step.Axis.CHILD) {
                explicitChild = true;
            } else if (explicitAxis == Step.Axis.SELF) {
                // self:: keeps the path axis; it's just a node test filter
            } else {
                axis = explicitAxis;
                if (explicitAxis == Step.Axis.DESCENDANT ||
                    explicitAxis == Step.Axis.DESCENDANT_OR_SELF) {
                    explicitDescendant = true;
                }
            }
            lexer.advance();
        } else if (token == XPathToken.AT) {
            axis = Step.Axis.ATTRIBUTE;
            lexer.advance();
        }

        NodeTest nt = parseNodeTest(lexer, patternStr, axis);

        // Impossible axis+kind-test combinations
        if (explicitChild && nt instanceof AttributeTest) {
            // child::attribute() — attributes are not on the child axis
            nt = NeverMatchTest.INSTANCE;
        }
        if (axis == Step.Axis.ATTRIBUTE) {
            // attribute::element(), attribute::text(), etc. can never match
            if (nt instanceof ElementTest || nt instanceof TextTest ||
                nt instanceof CommentTest || nt instanceof PITest) {
                nt = NeverMatchTest.INSTANCE;
            }
            if (nt == ChildAxisAnyNodeTest.INSTANCE) {
                nt = new AttributeTest(null, null, null);
            }
        }

        String predStr = null;
        if (lexer.current() == XPathToken.LBRACKET) {
            predStr = extractAllPredicates(lexer, prepared);
        }

        return new PatternStep(nt, axis, predStr, explicitDescendant);
    }

    private static Step.Axis mapAxis(XPathToken token, String patternStr) {
        switch (token) {
            case AXIS_CHILD:
                return Step.Axis.CHILD;
            case AXIS_DESCENDANT:
                return Step.Axis.DESCENDANT;
            case AXIS_DESCENDANT_OR_SELF:
                return Step.Axis.DESCENDANT_OR_SELF;
            case AXIS_SELF:
                return Step.Axis.SELF;
            case AXIS_ATTRIBUTE:
                return Step.Axis.ATTRIBUTE;
            case AXIS_NAMESPACE:
                return Step.Axis.NAMESPACE;
            case AXIS_ANCESTOR:
            case AXIS_ANCESTOR_OR_SELF:
            case AXIS_PARENT:
            case AXIS_PRECEDING:
            case AXIS_PRECEDING_SIBLING:
            case AXIS_FOLLOWING:
            case AXIS_FOLLOWING_SIBLING:
                throw new IllegalArgumentException(
                    "XTSE0340: " + token.name().substring(5).toLowerCase()
                        .replace('_', '-') +
                    " axis is not allowed in patterns: " + patternStr);
            default:
                return Step.Axis.CHILD;
        }
    }

    // ---- Node test parsing ----

    private static NodeTest parseNodeTest(XPathLexer lexer, String patternStr,
                                           Step.Axis axis) {
        XPathToken token = lexer.current();
        boolean isAttr = (axis == Step.Axis.ATTRIBUTE);

        // Wildcard: *
        if (isStar(token)) {
            lexer.advance();
            // *:local  (any-namespace wildcard)
            if (lexer.current() == XPathToken.COLON) {
                lexer.advance();
                if (lexer.current() == XPathToken.NCNAME) {
                    String local = lexer.value();
                    lexer.advance();
                    if (isAttr) {
                        return new AttributeTest(null, local, null);
                    }
                    return new ElementTest(null, local, null);
                }
            }
            if (isAttr) {
                return new AttributeTest(null, null, null);
            }
            return new ElementTest(null, null, null);
        }

        // node() kind test
        if (token == XPathToken.NODE_TYPE_NODE) {
            lexer.advance();
            lexer.advance(); // (
            lexer.advance(); // )
            if (isAttr) {
                return new AttributeTest(null, null, null);
            }
            return ChildAxisAnyNodeTest.INSTANCE;
        }

        // text() kind test
        if (token == XPathToken.NODE_TYPE_TEXT) {
            lexer.advance();
            lexer.advance(); // (
            lexer.advance(); // )
            return TextTest.INSTANCE;
        }

        // comment() kind test
        if (token == XPathToken.NODE_TYPE_COMMENT) {
            lexer.advance();
            lexer.advance(); // (
            lexer.advance(); // )
            return CommentTest.INSTANCE;
        }

        // processing-instruction() kind test
        if (token == XPathToken.NODE_TYPE_PI) {
            lexer.advance();
            lexer.advance(); // (
            if (lexer.current() == XPathToken.RPAREN) {
                lexer.advance();
                return PITest.ANY;
            }
            // processing-instruction('target') or processing-instruction(name)
            String target;
            if (lexer.current() == XPathToken.STRING_LITERAL) {
                target = lexer.value();
            } else {
                target = lexer.value();
            }
            lexer.advance();
            lexer.advance(); // )
            if (target.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                    "XTSE0340: processing-instruction name must not " +
                    "contain a colon: " + target);
            }
            return new PITest(target);
        }

        // element() kind test
        if (token == XPathToken.ELEMENT) {
            lexer.advance(); // consume "element"
            return parseElementKindTest(lexer);
        }

        // attribute() kind test
        if (token == XPathToken.ATTRIBUTE) {
            lexer.advance(); // consume "attribute"
            return parseAttributeKindTest(lexer);
        }

        // schema-element() and schema-attribute()
        if (token == XPathToken.SCHEMA_ELEMENT) {
            lexer.advance();
            return parseSchemaElementKindTest(lexer);
        }
        if (token == XPathToken.SCHEMA_ATTRIBUTE) {
            lexer.advance();
            return parseSchemaAttributeKindTest(lexer);
        }

        // namespace-node() kind test
        if (token == XPathToken.NAMESPACE_NODE) {
            lexer.advance();
            lexer.advance(); // (
            lexer.advance(); // )
            return AnyNodeTest.INSTANCE;
        }

        // URIQualifiedName: Q{uri}local or {uri}local (after prepareForLexer)
        if (token == XPathToken.URIQNAME) {
            return parseURIQName(lexer, isAttr);
        }

        // NCName or prefix:local
        // XPath keywords (in, if, then, etc.) can be element names in patterns
        if (token == XPathToken.NCNAME || isXPathKeyword(token)) {
            String name = lexer.value();
            lexer.advance();

            // prefix:local or prefix:*
            if (lexer.current() == XPathToken.COLON) {
                lexer.advance();
                if (isStar(lexer.current())) {
                    lexer.advance();
                    // prefix:* — namespace wildcard (prefix unresolved here)
                    if (isAttr) {
                        return new AttributeTest(null, null, null);
                    }
                    return new ElementTest(null, null, null);
                }
                if (lexer.current() == XPathToken.NCNAME ||
                    isXPathKeyword(lexer.current())) {
                    String local = lexer.value();
                    lexer.advance();
                    if (isAttr) {
                        return new AttributeTest(null, local, null);
                    }
                    return new ElementTest(null, local, null);
                }
                // Bare prefix: (unusual, treat as name)
                if (isAttr) {
                    return new AttributeTest("", name, null);
                }
                return new ElementTest("", name, null);
            }

            // Simple unprefixed name
            if (isAttr) {
                return new AttributeTest("", name, null);
            }
            return new ElementTest("", name, null);
        }

        // document-node() in name-test position (unusual)
        if (token == XPathToken.DOCUMENT_NODE) {
            lexer.advance();
            lexer.advance(); // (
            lexer.advance(); // )
            return AnyNodeTest.INSTANCE;
        }

        throw new IllegalArgumentException(
            "XTSE0340: Expected node test but found '" + lexer.value() +
            "' in pattern: " + patternStr);
    }

    private static NodeTest parseURIQName(XPathLexer lexer, boolean isAttr) {
        String clarkName = lexer.value();
        lexer.advance();

        int closeBrace = clarkName.indexOf('}');
        if (closeBrace < 0) {
            if (isAttr) {
                return new AttributeTest("", clarkName, null);
            }
            return new ElementTest("", clarkName, null);
        }

        String uri = clarkName.substring(1, closeBrace);
        String local = clarkName.substring(closeBrace + 1);

        if (local.isEmpty()) {
            // {uri} followed by * → namespace wildcard
            if (isStar(lexer.current())) {
                lexer.advance();
                if (isAttr) {
                    return new AttributeTest(uri, null, null);
                }
                return new ElementTest(uri, null, null);
            }
            // {uri} followed by name
            if (lexer.current() == XPathToken.NCNAME) {
                local = lexer.value();
                lexer.advance();
            }
        }

        if (isAttr) {
            return new AttributeTest(uri, local, null);
        }
        return new ElementTest(uri, local, null);
    }

    // ---- Kind test parsing: element(...), attribute(...) ----

    private static NodeTest parseElementKindTest(XPathLexer lexer) {
        lexer.advance(); // consume (
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            return new ElementTest(null, null, null);
        }
        return parseKindTestArgs(lexer, false);
    }

    private static NodeTest parseAttributeKindTest(XPathLexer lexer) {
        lexer.advance(); // consume (
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            return new AttributeTest(null, null, null);
        }
        return parseKindTestArgs(lexer, true);
    }

    private static NodeTest parseSchemaElementKindTest(XPathLexer lexer) {
        lexer.advance(); // consume (
        // schema-element(name)
        String nsUri = null;
        String localName = null;
        if (lexer.current() != XPathToken.RPAREN) {
            String[] parts = readQualifiedName(lexer);
            nsUri = parts[0];
            localName = parts[1];
        }
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
        }
        return new ElementTest(nsUri, localName, null);
    }

    private static NodeTest parseSchemaAttributeKindTest(XPathLexer lexer) {
        lexer.advance(); // consume (
        String nsUri = null;
        String localName = null;
        if (lexer.current() != XPathToken.RPAREN) {
            String[] parts = readQualifiedName(lexer);
            nsUri = parts[0];
            localName = parts[1];
        }
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
        }
        return new AttributeTest(nsUri, localName, null);
    }

    private static NodeTest parseKindTestArgs(XPathLexer lexer,
                                               boolean isAttr) {
        // Read element/attribute name
        if (isStar(lexer.current())) {
            lexer.advance();
            // element(*) or element(*, type)
            if (lexer.current() == XPathToken.COMMA) {
                lexer.advance();
                String[] typeParts = readQualifiedName(lexer);
                if (lexer.current() == XPathToken.RPAREN) {
                    lexer.advance();
                }
                if (isAttr) {
                    return new AttributeTest(null, null,
                        NodeTest.parseResolvedName(
                            formatClark(typeParts[0], typeParts[1])));
                }
                return new ElementTest(null, null,
                    NodeTest.parseResolvedName(
                        formatClark(typeParts[0], typeParts[1])));
            }
            if (lexer.current() == XPathToken.RPAREN) {
                lexer.advance();
            }
            if (isAttr) {
                return new AttributeTest(null, null, null);
            }
            return new ElementTest(null, null, null);
        }

        String[] nameParts = readQualifiedName(lexer);
        String nsUri = nameParts[0];
        String localName = nameParts[1];

        // Check for type: element(name, type)
        if (lexer.current() == XPathToken.COMMA) {
            lexer.advance();
            String[] typeParts = readQualifiedName(lexer);
            if (lexer.current() == XPathToken.RPAREN) {
                lexer.advance();
            }
            if (isAttr) {
                return new AttributeTest(nsUri, localName,
                    NodeTest.parseResolvedName(
                        formatClark(typeParts[0], typeParts[1])));
            }
            return new ElementTest(nsUri, localName,
                NodeTest.parseResolvedName(
                    formatClark(typeParts[0], typeParts[1])));
        }

        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
        }
        if (isAttr) {
            return new AttributeTest(nsUri, localName, null);
        }
        return new ElementTest(nsUri, localName, null);
    }

    /**
     * Reads a qualified name from the token stream. Returns [nsUri, local]
     * where nsUri may be null or empty.
     */
    private static String[] readQualifiedName(XPathLexer lexer) {
        if (lexer.current() == XPathToken.URIQNAME) {
            String clark = lexer.value();
            lexer.advance();
            int close = clark.indexOf('}');
            if (close > 0) {
                String uri = clark.substring(1, close);
                String local = clark.substring(close + 1);
                return new String[] { uri, local };
            }
            return new String[] { "", clark };
        }
        if (lexer.current() == XPathToken.NCNAME) {
            String name = lexer.value();
            lexer.advance();
            if (lexer.current() == XPathToken.COLON &&
                lexer.peek() == XPathToken.NCNAME) {
                lexer.advance();
                String local = lexer.value();
                lexer.advance();
                return new String[] { null, local };
            }
            return new String[] { "", name };
        }
        // Fallback for unexpected tokens
        String val = lexer.value();
        lexer.advance();
        return new String[] { "", val };
    }

    private static String formatClark(String uri, String local) {
        if (uri != null && !uri.isEmpty()) {
            return "{" + uri + "}" + local;
        }
        return local;
    }

    // ---- Predicate extraction ----

    /**
     * Extracts the content of one or more consecutive predicates as a
     * single combined expression string. Multiple predicates
     * {@code [a][b]} become {@code (a) and (b)}.
     */
    private static String extractAllPredicates(XPathLexer lexer,
                                                String prepared) {
        List<String> preds = new ArrayList<>();
        while (lexer.current() == XPathToken.LBRACKET) {
            preds.add(extractPredicateContent(lexer, prepared));
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

    /**
     * Extracts all predicates as individual strings without joining.
     * Used by AtomicPattern to evaluate each predicate independently
     * (preserving numeric predicate semantics).
     */
    private static List<String> extractPredicateList(XPathLexer lexer,
                                                     String prepared) {
        List<String> preds = new ArrayList<>();
        while (lexer.current() == XPathToken.LBRACKET) {
            preds.add(extractPredicateContent(lexer, prepared));
        }
        return preds;
    }

    /**
     * Extracts the content between [ and matching ]. Advances lexer past
     * the closing bracket.
     */
    private static String extractPredicateContent(XPathLexer lexer,
                                                   String prepared) {
        // lexer.current() == LBRACKET
        int startPos = lexer.getPosition(); // position right after [
        lexer.advance(); // consume [

        // The content starts at the current token's position
        int contentStart = lexer.tokenStart();

        int depth = 1;
        while (depth > 0 && lexer.current() != XPathToken.EOF) {
            if (lexer.current() == XPathToken.LBRACKET) {
                depth++;
            } else if (lexer.current() == XPathToken.RBRACKET) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            lexer.advance();
        }

        int contentEnd = lexer.tokenStart();
        if (lexer.current() == XPathToken.RBRACKET) {
            lexer.advance(); // consume ]
        }

        return prepared.substring(contentStart, contentEnd).trim();
    }

    // ---- Function patterns ----

    private static Pattern parseVariablePattern(XPathLexer lexer,
                                                 String patternStr,
                                                 String prepared,
                                                 double version) {
        lexer.advance(); // consume $
        String varName = lexer.value();
        lexer.advance(); // consume variable name

        String predStr = null;
        if (lexer.current() == XPathToken.LBRACKET) {
            predStr = extractAllPredicates(lexer, prepared);
        }

        Step.Axis trailing = parseTrailingAxis(lexer);
        Pattern base;
        if (trailing == null) {
            base = new VariablePattern(patternStr, varName, null, null);
        } else {
            Pattern trailingPattern = parsePathPattern(lexer, patternStr,
                                                        prepared, version);
            base = new VariablePattern(patternStr, varName, trailingPattern,
                                       trailing);
        }

        if (predStr != null) {
            return new PredicatedPattern(patternStr, predStr, base);
        }
        return base;
    }

    private static Pattern parseRootFuncPattern(XPathLexer lexer,
                                                String patternStr,
                                                String prepared,
                                                double version) {
        lexer.advance(); // consume "root"
        lexer.advance(); // consume (
        if (lexer.current() != XPathToken.RPAREN) {
            // Skip optional argument
            int depth = 1;
            while (depth > 0 && lexer.current() != XPathToken.EOF) {
                if (lexer.current() == XPathToken.LPAREN) {
                    depth++;
                } else if (lexer.current() == XPathToken.RPAREN) {
                    depth--;
                }
                if (depth > 0) {
                    lexer.advance();
                }
            }
        }
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance(); // consume )
        }

        String predStr = null;
        if (lexer.current() == XPathToken.LBRACKET) {
            predStr = extractAllPredicates(lexer, prepared);
        }

        if (lexer.current() == XPathToken.DOUBLE_SLASH) {
            lexer.advance();
            return buildAbsolutePath(lexer, patternStr, prepared,
                                     version, true);
        }
        if (lexer.current() == XPathToken.SLASH) {
            lexer.advance();
            if (isPatternEnd(lexer)) {
                Pattern base = new DocumentNodePattern(patternStr);
                if (predStr != null) {
                    return new PredicatedPattern(patternStr, predStr, base);
                }
                return base;
            }
            return buildAbsolutePath(lexer, patternStr, prepared,
                                     version, false);
        }

        Pattern base = new DocumentNodePattern(patternStr);
        if (predStr != null) {
            return new PredicatedPattern(patternStr, predStr, base);
        }
        return base;
    }

    private static Pattern parseDocFuncPattern(XPathLexer lexer,
                                                String patternStr,
                                                String prepared,
                                                double version) {
        // Extract the full function call as a string for evaluation
        int funcStart = lexer.tokenStart();
        lexer.advance(); // consume function name
        lexer.advance(); // consume (
        int depth = 1;
        while (depth > 0 && lexer.current() != XPathToken.EOF) {
            if (lexer.current() == XPathToken.LPAREN) {
                depth++;
            } else if (lexer.current() == XPathToken.RPAREN) {
                depth--;
            }
            if (depth > 0) {
                lexer.advance();
            }
        }
        int funcEnd = lexer.getPosition();
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance(); // consume )
        }
        String docExpr = prepared.substring(funcStart, funcEnd);

        Step.Axis trailing = parseTrailingAxis(lexer);
        if (trailing == null) {
            return new DocPattern(patternStr, docExpr, null, null);
        }
        Pattern trailingPattern = parsePathPattern(lexer, patternStr,
                                                    prepared, version);
        return new DocPattern(patternStr, docExpr, trailingPattern, trailing);
    }

    private static Pattern parseIdPattern(XPathLexer lexer,
                                           String patternStr,
                                           String prepared,
                                           double version) {
        lexer.advance(); // consume "id"
        lexer.advance(); // consume (

        List<String> args = parseFuncArgs(lexer);
        if (args.isEmpty()) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String idArg = args.get(0).trim();
        String docVarName = null;
        if (args.size() > 1) {
            if (version < 3.0) {
                throw new IllegalArgumentException(
                    "XTSE0340: 2-argument id() in patterns requires " +
                    "XSLT 3.0: " + patternStr);
            }
            String docArg = args.get(1).trim();
            if (docArg.startsWith("$")) {
                docVarName = docArg.substring(1);
            }
        }

        Step.Axis trailing = parseTrailingAxis(lexer);

        if (idArg.startsWith("$")) {
            String varName = idArg.substring(1);
            if (trailing == null) {
                return new IdPattern(patternStr, varName, docVarName,
                                     null, null);
            }
            Pattern trailingPat = parsePathPattern(lexer, patternStr,
                                                    prepared, version);
            return new IdPattern(patternStr, varName, docVarName,
                                 trailingPat, trailing);
        }

        idArg = stripQuotes(idArg);
        String[] ids = splitWhitespace(idArg);
        if (trailing == null) {
            return new IdPattern(patternStr, ids, docVarName, null, null);
        }
        Pattern trailingPat = parsePathPattern(lexer, patternStr, prepared,
                                                version);
        return new IdPattern(patternStr, ids, docVarName, trailingPat,
                             trailing);
    }

    private static Pattern parseElementWithIdPattern(XPathLexer lexer,
                                                      String patternStr,
                                                      String prepared,
                                                      double version) {
        lexer.advance(); // consume "element-with-id"
        lexer.advance(); // consume (

        List<String> args = parseFuncArgs(lexer);
        if (args.isEmpty()) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String idArg = stripQuotes(args.get(0).trim());
        String[] ids = splitWhitespace(idArg);

        String docVarName = null;
        if (args.size() > 1) {
            String docArg = args.get(1).trim();
            if (docArg.startsWith("$")) {
                docVarName = docArg.substring(1);
            }
        }

        Step.Axis trailing = parseTrailingAxis(lexer);
        if (trailing == null) {
            return new ElementWithIdPattern(patternStr, ids, docVarName,
                                            null, null);
        }
        Pattern trailingPat = parsePathPattern(lexer, patternStr, prepared,
                                                version);
        return new ElementWithIdPattern(patternStr, ids, docVarName,
                                        trailingPat, trailing);
    }

    private static Pattern parseKeyPattern(XPathLexer lexer,
                                            String patternStr,
                                            String prepared,
                                            double version) {
        lexer.advance(); // consume "key"
        lexer.advance(); // consume (

        List<String> args = parseFuncArgs(lexer);
        if (args.size() < 2) {
            return new NameTestPattern(patternStr, null,
                NeverMatchTest.INSTANCE, -0.5);
        }

        String keyName = stripQuotes(args.get(0).trim());
        String keyValueArg = args.get(1).trim();

        boolean isVariable = keyValueArg.startsWith("$");
        String keyValueExpr;
        if (isVariable) {
            keyValueExpr = keyValueArg.substring(1);
        } else {
            keyValueExpr = stripQuotes(keyValueArg);
        }

        Step.Axis trailing = parseTrailingAxis(lexer);
        if (trailing == null) {
            return new KeyPattern(patternStr, keyName, keyValueExpr,
                                  isVariable, null, null);
        }
        Pattern trailingPat = parsePathPattern(lexer, patternStr, prepared,
                                                version);
        return new KeyPattern(patternStr, keyName, keyValueExpr, isVariable,
                              trailingPat, trailing);
    }

    /**
     * Parses function arguments between ( and ), returning each as a raw
     * string. The opening ( must already be consumed.
     */
    private static List<String> parseFuncArgs(XPathLexer lexer) {
        List<String> args = new ArrayList<>();
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
            return args;
        }

        StringBuilder current = new StringBuilder();
        int depth = 0;
        while (lexer.current() != XPathToken.EOF) {
            if (lexer.current() == XPathToken.LPAREN) {
                depth++;
                current.append("(");
                lexer.advance();
            } else if (lexer.current() == XPathToken.RPAREN) {
                if (depth == 0) {
                    args.add(current.toString());
                    lexer.advance();
                    break;
                }
                depth--;
                current.append(")");
                lexer.advance();
            } else if (lexer.current() == XPathToken.COMMA && depth == 0) {
                args.add(current.toString());
                current.setLength(0);
                lexer.advance();
            } else {
                current.append(lexer.value());
                lexer.advance();
            }
        }
        return args;
    }

    // ---- document-node() pattern ----

    private static Pattern parseDocumentNode(XPathLexer lexer,
                                              String patternStr,
                                              String prepared,
                                              double version) {
        lexer.advance(); // consume "document-node"
        lexer.advance(); // consume (
        // May have element() or schema-element() inside
        if (lexer.current() != XPathToken.RPAREN) {
            // Skip the inner kind test
            int depth = 1;
            while (depth > 0 && lexer.current() != XPathToken.EOF) {
                if (lexer.current() == XPathToken.LPAREN) {
                    depth++;
                } else if (lexer.current() == XPathToken.RPAREN) {
                    depth--;
                }
                if (depth > 0) {
                    lexer.advance();
                }
            }
        }
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance(); // consume )
        }

        if (lexer.current() == XPathToken.DOUBLE_SLASH) {
            lexer.advance();
            return buildAbsolutePath(lexer, patternStr, prepared, version,
                                     true);
        }
        if (lexer.current() == XPathToken.SLASH) {
            lexer.advance();
            if (isPatternEnd(lexer)) {
                return new DocumentNodePattern(patternStr);
            }
            return buildAbsolutePath(lexer, patternStr, prepared, version,
                                     false);
        }

        return new DocumentNodePattern(patternStr);
    }

    // ---- Parenthesized pattern ----

    private static Pattern parseParenthesized(XPathLexer lexer,
                                               String patternStr,
                                               String prepared,
                                               double version) {
        lexer.advance(); // consume (
        Pattern inner = parseUnion(lexer, patternStr, prepared, version);
        if (lexer.current() == XPathToken.RPAREN) {
            lexer.advance();
        }

        if (inner instanceof AtomicPattern) {
            throw new IllegalArgumentException(
                "XTSE0340: Predicate pattern cannot be " +
                "parenthesized: " + patternStr);
        }

        // Check for predicates after parenthesized pattern
        if (lexer.current() == XPathToken.LBRACKET) {
            String predStr = extractAllPredicates(lexer, prepared);
            return new PredicatedPattern(patternStr, predStr, inner);
        }
        return inner;
    }

    // ---- Trailing axis after function patterns ----

    /**
     * Checks if the current token is / or // and returns the
     * corresponding trailing axis. Returns null if no path follows.
     */
    private static Step.Axis parseTrailingAxis(XPathLexer lexer) {
        if (lexer.current() == XPathToken.DOUBLE_SLASH) {
            lexer.advance();
            return Step.Axis.DESCENDANT;
        }
        if (lexer.current() == XPathToken.SLASH) {
            lexer.advance();
            return Step.Axis.CHILD;
        }
        return null;
    }

    // ---- Priority computation ----

    private static double computePriority(PatternStep step) {
        NodeTest nt = step.nodeTest;

        if (nt instanceof ChildAxisAnyNodeTest || nt == AnyNodeTest.INSTANCE) {
            return -0.5;
        }
        if (nt instanceof TextTest || nt instanceof CommentTest) {
            return -0.5;
        }
        if (nt instanceof PITest) {
            PITest pi = (PITest) nt;
            if (pi == PITest.ANY) {
                return -0.5;
            }
            return 0.0;
        }
        if (nt instanceof ElementTest) {
            ElementTest et = (ElementTest) nt;
            if (et.getLocalName() == null && et.getNamespaceURI() == null) {
                return -0.5;
            }
            if (et.getLocalName() == null && et.getNamespaceURI() != null) {
                return -0.25;
            }
            return 0.0;
        }
        if (nt instanceof AttributeTest) {
            AttributeTest at = (AttributeTest) nt;
            if (at.getLocalName() == null && at.getNamespaceURI() == null) {
                return -0.5;
            }
            if (at.getLocalName() == null && at.getNamespaceURI() != null) {
                return -0.25;
            }
            return 0.0;
        }
        if (nt instanceof NeverMatchTest) {
            return -0.5;
        }
        return 0.0;
    }

    // ---- Utility methods ----

    private static boolean isPatternEnd(XPathLexer lexer) {
        XPathToken t = lexer.current();
        return t == XPathToken.EOF || t == XPathToken.PIPE ||
               t == XPathToken.UNION || t == XPathToken.EXCEPT ||
               t == XPathToken.INTERSECT || t == XPathToken.RPAREN;
    }

    /**
     * Returns true if the token is an XPath keyword that could also be
     * an element name in a pattern. The lexer may tokenize names like
     * 'in', 'if', 'for' as keywords instead of NCNAME depending on context.
     */
    private static boolean isXPathKeyword(XPathToken token) {
        switch (token) {
            case IN:
            case IF:
            case THEN:
            case ELSE:
            case FOR:
            case LET:
            case RETURN:
            case SOME:
            case EVERY:
            case SATISFIES:
            case OF:
            case AS:
            case AND:
            case OR:
            case DIV:
            case MOD:
            case IDIV:
            case TO:
            case EQ:
            case NE:
            case LT:
            case LE:
            case GT:
            case GE:
            case IS:
            case INSTANCE:
            case CAST:
            case CASTABLE:
            case TREAT:
                return true;
            default:
                return false;
        }
    }

    /**
     * In pattern context, both STAR and STAR_MULTIPLY represent a wildcard.
     * The lexer may produce STAR_MULTIPLY when '*' follows a complete
     * expression token (like URIQNAME), but in patterns it is always
     * a node-test wildcard.
     */
    private static boolean isStar(XPathToken token) {
        return token == XPathToken.STAR || token == XPathToken.STAR_MULTIPLY;
    }

    /**
     * Converts Clark notation {@code {uri}local} to
     * {@code Q{uri}local} so the XPath lexer can tokenize it as a
     * URIQualifiedName. Only converts at the top level (outside
     * predicates and string literals).
     */
    static String prepareForLexer(String s) {
        int idx = s.indexOf('{');
        if (idx < 0) {
            return s;
        }

        StringBuilder sb = new StringBuilder(s.length() + 10);
        int bracketDepth = 0;
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!inQuote && (c == '\'' || c == '"')) {
                inQuote = true;
                quoteChar = c;
                sb.append(c);
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
                sb.append(c);
            } else if (!inQuote && c == '[') {
                bracketDepth++;
                sb.append(c);
            } else if (!inQuote && c == ']') {
                bracketDepth--;
                sb.append(c);
            } else if (!inQuote && bracketDepth == 0 && c == '{') {
                if (i > 0 && s.charAt(i - 1) == 'Q') {
                    sb.append(c);
                } else {
                    sb.append("Q");
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') ||
                (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
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
}
