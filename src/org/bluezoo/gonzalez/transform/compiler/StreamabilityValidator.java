/*
 * StreamabilityValidator.java
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

import org.bluezoo.gonzalez.transform.ast.CatchNode;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.ast.ChooseNode;
import org.bluezoo.gonzalez.transform.ast.CopyNode;
import org.bluezoo.gonzalez.transform.ast.CopyOfNode;
import org.bluezoo.gonzalez.transform.ast.ElementNode;
import org.bluezoo.gonzalez.transform.ast.ForEachGroupNode;
import org.bluezoo.gonzalez.transform.ast.ForEachNode;
import org.bluezoo.gonzalez.transform.ast.ForkNode;
import org.bluezoo.gonzalez.transform.ast.IfNode;
import org.bluezoo.gonzalez.transform.ast.IterateNode;
import org.bluezoo.gonzalez.transform.ast.LiteralResultElement;
import org.bluezoo.gonzalez.transform.ast.MapConstructionNode;
import org.bluezoo.gonzalez.transform.ast.MapEntryNode;
import org.bluezoo.gonzalez.transform.ast.ResultDocumentNode;
import org.bluezoo.gonzalez.transform.ast.SequenceNode;
import org.bluezoo.gonzalez.transform.ast.SequenceOutputNode;
import org.bluezoo.gonzalez.transform.ast.SourceDocumentNode;
import org.bluezoo.gonzalez.transform.ast.TryNode;
import org.bluezoo.gonzalez.transform.ast.ValueOfNode;
import org.bluezoo.gonzalez.transform.ast.VariableNode;
import org.bluezoo.gonzalez.transform.ast.WhenNode;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.ast.ApplyTemplatesNode;
import org.bluezoo.gonzalez.transform.ast.AttributeNode;
import org.bluezoo.gonzalez.transform.ast.NextMatchNode;
import org.bluezoo.gonzalez.transform.ast.NumberNode;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ContextItemExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FilterExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.IfExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.PathExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.SequenceExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.VariableReference;
import org.bluezoo.gonzalez.transform.xpath.expr.ArrayConstructorExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ForExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LookupExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LetExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.TypeExpr;
import org.bluezoo.gonzalez.transform.xpath.type.SequenceType;

/**
 * Validates streamability constraints for XSLT templates and instructions.
 * Extracted from StylesheetCompiler for separation of concerns.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class StreamabilityValidator {

    private StreamabilityValidator() {
    }

    /**
     * Convenience method that validates all streamability constraints for
     * xsl:source-document body. Calls validateForkStreamability,
     * validateStreamableFunctionCallSites, validateStreamingForEachGroup,
     * validateStreamingLoopBodies, and validateStreamingMapEntries.
     */
    static void validateSourceDocumentStreamability(XSLTNode body,
            CompiledStylesheet.Builder builder) throws SAXException {
        if (body == null) {
            return;
        }
        validateForkStreamability(body, 0);
        validateStreamableFunctionCallSites(body, 0, builder);
        validateStreamingForEachGroup(body, 0);
        validateStreamingLoopBodies(body, 0);
        validateStreamingMapEntries(body, 0);
        validateMixedPostureExpressions(body, 0);
    }



    /**
     * XTSE3430: Validates that all templates in streamable modes are streamable.
     * Checks for FREE_RANGING expressions, crawling (descendant axis) patterns,
     * xsl:number, non-motionless match patterns, and backwards-compatible instructions.
     *
     * <p>When {@code fallbackEnabled} is true, modes that fail validation are
     * collected and returned instead of throwing, enabling streaming fallback
     * (§19.10). When false, throws on the first failure (strict mode).
     *
     * @param fallbackEnabled true to collect failures instead of throwing
     * @return list of mode keys that failed (empty if fallback disabled)
     */
    static List<String> validateStreamableModes(CompiledStylesheet stylesheet,
                                          StreamabilityAnalyzer.StylesheetStreamability analysis,
                                          StreamabilityAnalyzer analyzer,
                                          boolean fallbackEnabled)
            throws SAXException {
        Map<String, ModeDeclaration> modes = stylesheet.getModeDeclarations();
        Map<TemplateRule, StreamabilityAnalyzer.TemplateStreamability> templateAnalysis =
            analysis.getTemplateAnalysis();
        List<String> failedModes = new ArrayList<>();

        for (Map.Entry<String, ModeDeclaration> modeEntry : modes.entrySet()) {
            ModeDeclaration mode = modeEntry.getValue();
            if (!mode.isStreamable()) {
                continue;
            }
            String modeKey = modeEntry.getKey();
            String modeName = mode.getName();

            try {
                validateSingleStreamableMode(modeName, modeKey,
                    templateAnalysis);
            } catch (SAXException e) {
                if (fallbackEnabled) {
                    failedModes.add(modeKey);
                } else {
                    throw e;
                }
            }
        }
        return failedModes;
    }

    /**
     * Validates a single streamable mode. Throws SAXException if any
     * template in the mode is non-streamable.
     */
    private static void validateSingleStreamableMode(String modeName,
            String modeKey,
            Map<TemplateRule, StreamabilityAnalyzer.TemplateStreamability> templateAnalysis)
            throws SAXException {
        for (Map.Entry<TemplateRule, StreamabilityAnalyzer.TemplateStreamability> entry
                : templateAnalysis.entrySet()) {
            TemplateRule template = entry.getKey();
            String templateMode = template.getMode();

            boolean inThisMode;
            if (modeName == null || "#default".equals(modeName)) {
                inThisMode = (templateMode == null || "#default".equals(templateMode));
            } else {
                inThisMode = modeName.equals(templateMode);
            }

            if (!inThisMode) {
                continue;
            }

            StreamabilityAnalyzer.TemplateStreamability ts = entry.getValue();
            if (ts.requiresDocumentBuffering()) {
                throwStreamabilityError(template, modeName,
                    ts.getBufferingReasons());
            }

            XSLTNode body = template.getBody();

            if (body != null) {
                int cgCalls = countCurrentGroupCalls(body, 0);
                if (cgCalls > 0) {
                    throw new SAXException("XTSE3430: Template in " +
                        "streamable mode uses current-group() which " +
                        "is not available in this context");
                }
            }

            if (body != null) {
                validateForkStreamability(body, 0);
            }

            if (body != null) {
                if (containsCrawlingInstruction(body, 0)) {
                    throw new SAXException(
                        "XTSE3430: Template in streamable mode " +
                        "uses a crawling instruction (descendant " +
                        "axis in apply-templates or for-each)");
                }
            }

            if (body != null) {
                if (containsNumberInstruction(body, 0)) {
                    throw new SAXException(
                        "XTSE3430: xsl:number in streamable mode " +
                        "is not streamable (requires sibling access)");
                }
            }

            if (body != null) {
                validateStreamingForEachGroup(body, 0);
            }

            Pattern matchPat = template.getMatchPattern();
            if (matchPat != null) {
                if (hasNonMotionlessPredicate(matchPat)) {
                    throw new SAXException(
                        "XTSE3430: Template in streamable mode '" +
                        modeName + "' has non-motionless match " +
                        "pattern '" + matchPat + "'");
                }
            }

            if (body != null) {
                if (containsBackwardsCompatInstruction(body, 0)) {
                    throw new SAXException(
                        "XTSE3430: Template in streamable mode " +
                        "contains an instruction in backwards-" +
                        "compatible mode (version=\"1.0\"), which " +
                        "is roaming and free-ranging");
                }
            }

            if (body != null) {
                if (hasPreDescentAccumulatorAfter(body, 0,
                        false, false)) {
                    throw new SAXException(
                        "XTSE3430: Template in streamable mode " +
                        "uses accumulator-after() in a " +
                        "pre-descent context");
                }
            }

            if (body != null) {
                boolean matchesAttributes = false;
                if (matchPat != null) {
                    String patStr = matchPat.toString();
                    matchesAttributes = patStr.contains("@")
                        || patStr.contains("attribute::");
                }
                if (!matchesAttributes
                        && hasClimbingAccumulatorCall(body, 0)) {
                    throw new SAXException(
                        "XTSE3430: Template in streamable mode " +
                        "uses accumulator function with " +
                        "parent/ancestor axis navigation");
                }
            }
        }
    }

    /**
     * Throws a formatted XTSE3430 error for a non-streamable template.
     */
    static void throwStreamabilityError(TemplateRule template,
                                                 String modeName,
                                                 List<String> reasons)
            throws SAXException {
        StringBuilder sb = new StringBuilder();
        sb.append("XTSE3430: Template");
        String matchStr = template.getMatchPattern() != null
            ? template.getMatchPattern().toString() : null;
        if (matchStr != null) {
            sb.append(" matching '");
            sb.append(matchStr);
            sb.append("'");
        }
        String tName = template.getName();
        if (tName != null) {
            sb.append(" named '");
            sb.append(tName);
            sb.append("'");
        }
        sb.append(" in streamable mode");
        if (modeName != null && !"#default".equals(modeName)) {
            sb.append(" '");
            sb.append(modeName);
            sb.append("'");
        }
        sb.append(" is not streamable");
        if (!reasons.isEmpty()) {
            sb.append(": ");
            sb.append(reasons.get(0));
        }
        throw new SAXException(sb.toString());
    }

    /**
     * Checks whether accumulator-after() is used in a pre-descent
     * position within a streaming template body.
     *
     * <p>Three contexts are tracked:
     * <ul>
     *   <li>{@code skipChecks}: post-descent — nothing to check</li>
     *   <li>{@code checkExpressions}: pre-descent subtree — check
     *       both ExpressionHolder expressions and LRE AVTs</li>
     *   <li>Neither flag: default — check only LRE AVTs (no explicit
     *       consuming instruction is present, but consuming
     *       expressions may advance the stream)</li>
     * </ul>
     *
     * @param skipChecks true when a consuming instruction has been
     *     seen in an ancestor sequence (post-descent)
     * @param checkExpressions true when the node is before a
     *     consuming instruction in some ancestor sequence
     */
    static boolean hasPreDescentAccumulatorAfter(XSLTNode node,
                                                         int depth,
                                                         boolean skipChecks,
                                                         boolean checkExpressions) {
        if (node == null || depth > 50 || skipChecks) {
            return false;
        }
        if (checkExpressions && node instanceof ExpressionHolder
                && !(node instanceof AttributeNode)) {
            if (expressionHolderHasAccumulatorAfter(
                    (ExpressionHolder) node)) {
                return true;
            }
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children == null) {
                return false;
            }
            int consumingIdx = -1;
            for (int i = 0; i < children.size(); i++) {
                if (isChildConsumingInstruction(children.get(i))) {
                    consumingIdx = i;
                    break;
                }
            }
            for (int i = 0; i < children.size(); i++) {
                XSLTNode child = children.get(i);
                if (isChildConsumingInstruction(child)) {
                    continue;
                }
                if (child instanceof AttributeNode) {
                    continue;
                }
                boolean childSkip;
                boolean childCheckExpr;
                if (consumingIdx >= 0) {
                    childSkip = (i >= consumingIdx);
                    childCheckExpr = (i < consumingIdx);
                } else {
                    childSkip = skipChecks;
                    childCheckExpr = checkExpressions;
                }
                if (hasPreDescentAccumulatorAfter(child, depth + 1,
                        childSkip, childCheckExpr)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof CopyNode) {
            return hasPreDescentAccumulatorAfter(
                ((CopyNode) node).getContent(), depth + 1,
                skipChecks, checkExpressions);
        }
        if (node instanceof ElementNode) {
            return hasPreDescentAccumulatorAfter(
                ((ElementNode) node).getContent(), depth + 1,
                skipChecks, checkExpressions);
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            if (lreAvtsHaveAccumulatorAfter(lre)) {
                return true;
            }
            return hasPreDescentAccumulatorAfter(
                lre.getContent(), depth + 1,
                skipChecks, checkExpressions);
        }
        return false;
    }

    /**
     * Checks whether an instruction is a consuming instruction that
     * processes child elements (not just attributes).
     */
    static boolean isChildConsumingInstruction(XSLTNode node) {
        if (node instanceof ApplyTemplatesNode) {
            ApplyTemplatesNode at = (ApplyTemplatesNode) node;
            List<XPathExpression> exprs = at.getExpressions();
            if (exprs.isEmpty()) {
                return true;
            }
            XPathExpression selectExpr = exprs.get(0);
            StreamabilityAnalyzer.ExpressionStreamability es =
                StreamingClassifier.classify(selectExpr);
            return es != StreamabilityAnalyzer.ExpressionStreamability
                .MOTIONLESS;
        }
        if (node instanceof NextMatchNode) {
            return true;
        }
        if (node instanceof ForEachNode) {
            ForEachNode fe = (ForEachNode) node;
            List<XPathExpression> exprs = fe.getExpressions();
            if (!exprs.isEmpty()) {
                StreamabilityAnalyzer.ExpressionStreamability es =
                    StreamingClassifier.classify(exprs.get(0));
                return es != StreamabilityAnalyzer
                    .ExpressionStreamability.MOTIONLESS;
            }
            return true;
        }
        return false;
    }

    /**
     * Checks whether an ExpressionHolder's direct expressions contain
     * accumulator-after() calls.
     */
    static boolean expressionHolderHasAccumulatorAfter(
            ExpressionHolder holder) {
        List<XPathExpression> exprs = holder.getExpressions();
        if (exprs == null) {
            return false;
        }
        for (int i = 0; i < exprs.size(); i++) {
            int calls = StreamingClassifier.countFunctionCalls(
                exprs.get(i), "accumulator-after");
            if (calls > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a LiteralResultElement's AVTs contain
     * accumulator-after() calls.
     */
    static boolean lreAvtsHaveAccumulatorAfter(
            LiteralResultElement lre) {
        Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
        if (attrs == null) {
            return false;
        }
        List<XPathExpression> avtExprs = new ArrayList<XPathExpression>();
        for (AttributeValueTemplate avt : attrs.values()) {
            avt.collectExpressions(avtExprs);
        }
        for (int i = 0; i < avtExprs.size(); i++) {
            int calls = StreamingClassifier.countFunctionCalls(
                avtExprs.get(i), "accumulator-after");
            if (calls > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a template body contains a climbing axis (parent,
     * ancestor) combined with an accumulator function call, which is
     * not streamable.
     */
    static boolean hasClimbingAccumulatorCall(XSLTNode node,
                                                      int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe == null) {
                        continue;
                    }
                    Expr compiled = xpe.getCompiledExpr();
                    if (exprHasClimbingAccumulatorCall(compiled)) {
                        return true;
                    }
                    if (exprStringHasClimbingAccumulator(xpe)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasClimbingAccumulatorCall(children.get(i),
                                                   depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ElementNode) {
            return hasClimbingAccumulatorCall(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            return hasClimbingAccumulatorCall(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof CopyNode) {
            return hasClimbingAccumulatorCall(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof IfNode) {
            return hasClimbingAccumulatorCall(
                ((IfNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * String-based check for context item (.) in an accumulator
     * select expression. Detects patterns like "$value, ." where
     * the context node is captured in a sequence — this is not
     * grounded for element matches in streaming.
     */
    static boolean accSelectStringCapturesContext(
            XPathExpression xpe) {
        String s = xpe.getExpressionString();
        if (s == null) {
            return false;
        }
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) != '.') {
                continue;
            }
            if (i + 1 < len && s.charAt(i + 1) == '.') {
                i++;
                continue;
            }
            if (i > 0 && s.charAt(i - 1) == '.') {
                continue;
            }
            boolean prevOk = (i == 0);
            if (!prevOk) {
                char prev = s.charAt(i - 1);
                prevOk = (prev == ',' || prev == '(' || prev == ' '
                    || prev == '\t' || prev == '\n' || prev == '\r');
            }
            boolean nextOk = (i + 1 >= len);
            if (!nextOk) {
                char next = s.charAt(i + 1);
                nextOk = (next == ',' || next == ')' || next == ' '
                    || next == '\t' || next == '\n' || next == '\r');
            }
            if (prevOk && nextOk) {
                return true;
            }
        }
        return false;
    }

    /**
     * String-based fallback for detecting climbing axis combined with
     * accumulator function calls. Handles cases where the expression
     * tree structure differs from what the AST-based check expects.
     */
    static boolean exprStringHasClimbingAccumulator(
            XPathExpression xpe) {
        String s = xpe.getExpressionString();
        if (s == null) {
            return false;
        }
        boolean hasClimb = s.contains("../") || s.contains("parent::")
            || s.contains("ancestor::");
        if (!hasClimb) {
            return false;
        }
        return s.contains("accumulator-after")
            || s.contains("accumulator-before");
    }

    /**
     * Checks whether an expression contains both a climbing axis step
     * and an accumulator function call. The combination is not
     * streamable because climbing requires ancestor access which is
     * incompatible with forward-only streaming.
     */
    static boolean exprHasClimbingAccumulatorCall(Expr expr) {
        if (expr == null) {
            return false;
        }
        boolean hasClimbing = exprContainsClimbingAxis(expr);
        if (!hasClimbing) {
            return false;
        }
        int accCalls =
            StreamingClassifier.countFunctionCalls(
                expr, "accumulator-after")
            + StreamingClassifier.countFunctionCalls(
                expr, "accumulator-before");
        return accCalls > 0;
    }

    /**
     * Checks whether an expression contains a climbing axis step
     * (parent, ancestor, ancestor-or-self).
     */
    static boolean exprContainsClimbingAxis(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT
                        || axis == Step.Axis.ANCESTOR
                        || axis == Step.Axis.ANCESTOR_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return exprContainsClimbingAxis(pe.getFilter())
                || exprContainsClimbingAxis(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return exprContainsClimbingAxis(be.getLeft())
                || exprContainsClimbingAxis(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (exprContainsClimbingAxis(args.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether an XSLT node tree contains an xsl:number instruction.
     */
    static boolean containsNumberInstruction(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof NumberNode) {
            return true;
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsNumberInstruction(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (containsNumberInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (containsNumberInstruction(body, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (containsNumberInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                XSLTNode wContent = when.getContent();
                if (containsNumberInstruction(wContent, depth + 1)) {
                    return true;
                }
            }
            XSLTNode ow = choose.getOtherwise();
            if (containsNumberInstruction(ow, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (containsNumberInstruction(content, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a match pattern has a predicate that is not motionless.
     * Patterns with positional, last(), or string-value-dependent predicates
     * are not motionless and thus not valid in streamable modes.
     * Type checks and ancestor attribute access are considered motionless.
     */
    static boolean hasNonMotionlessPredicate(Pattern pat) {
        if (pat == null) {
            return false;
        }
        if (pat instanceof PredicatedPattern) {
            PredicatedPattern pp = (PredicatedPattern) pat;
            String predStr = pp.getPredicateStr();
            if (predStr != null && isNonMotionlessPredicateStr(predStr)) {
                return true;
            }
            return hasNonMotionlessPredicate(pp.getInner());
        }
        if (pat instanceof UnionPattern) {
            UnionPattern up = (UnionPattern) pat;
            Pattern[] alts = up.getAlternatives();
            for (int i = 0; i < alts.length; i++) {
                if (hasNonMotionlessPredicate(alts[i])) {
                    return true;
                }
            }
            return false;
        }
        if (pat instanceof IntersectPattern) {
            IntersectPattern ip = (IntersectPattern) pat;
            return hasNonMotionlessPredicate(ip.getLeft())
                || hasNonMotionlessPredicate(ip.getRight());
        }
        if (pat instanceof ExceptPattern) {
            ExceptPattern ep = (ExceptPattern) pat;
            return hasNonMotionlessPredicate(ep.getLeft())
                || hasNonMotionlessPredicate(ep.getRight());
        }

        if (pat instanceof AtomicPattern) {
            AtomicPattern ap = (AtomicPattern) pat;
            List<String> preds = ap.getPredicates();
            if (preds != null) {
                for (int i = 0; i < preds.size(); i++) {
                    String p = preds.get(i);
                    boolean nm = isNonMotionlessPredicateStr(p);
                    if (nm) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (pat instanceof AbstractPattern) {
            AbstractPattern ap = (AbstractPattern) pat;
            String predStr = ap.getPredicateStr();
            if (predStr != null && isNonMotionlessPredicateStr(predStr)) {
                return true;
            }
        }

        if (pat instanceof PathPattern) {
            PatternStep[] steps = ((PathPattern) pat).getSteps();
            if (steps != null) {
                for (int i = 0; i < steps.length; i++) {
                    String stepPred = steps[i].predicateStr;
                    if (stepPred != null) {
                        boolean atomicNode = isAtomicContentNodeTest(
                            steps[i].nodeTest);
                        if (isNonMotionlessPredicateStr(stepPred,
                                atomicNode)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks whether a predicate string represents a non-motionless
     * predicate for streaming pattern matching.
     */
    static boolean isNonMotionlessPredicateStr(String predStr) {
        return isNonMotionlessPredicateStr(predStr, false);
    }

    /**
     * Checks whether a predicate string represents a non-motionless
     * predicate for streaming pattern matching.
     *
     * @param predStr the predicate expression string
     * @param atomicContentNode true if the step matches text, comment, PI,
     *        or attribute nodes whose string value requires no descendant
     *        traversal
     */
    static boolean isNonMotionlessPredicateStr(String predStr,
            boolean atomicContentNode) {
        if (predStr == null) {
            return false;
        }
        String trimmed = predStr.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        // Numeric literal → positional predicate → non-motionless
        try {
            Double.parseDouble(trimmed);
            return true;
        } catch (NumberFormatException e) {
            // continue
        }

        try {
            XPathExpression predExpr = XPathExpression.compile(trimmed);
            Expr compiled = predExpr.getCompiledExpr();

            // Type checks (instance of, treat as) are motionless
            if (compiled instanceof TypeExpr) {
                return false;
            }

            // For text, comment, PI, and attribute nodes, accessing the
            // string value of self (.) is motionless because these nodes
            // have no descendant content to traverse
            if (!atomicContentNode && accessesStringValueOfSelf(compiled)) {
                return true;
            }

            StreamabilityAnalyzer.ExpressionStreamability es =
                StreamingClassifier.classify(predExpr);

            if (es == StreamabilityAnalyzer.ExpressionStreamability.MOTIONLESS) {
                return false;
            }

            // GROUNDED from ancestor/parent attribute access is motionless
            // in streaming (ancestor stack is maintained)
            if (es == StreamabilityAnalyzer.ExpressionStreamability.GROUNDED) {
                if (isAncestorAttributeOnly(compiled)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Checks whether a compiled expression accesses the string value
     * of the context item (self::node() / "."). In a streaming match
     * pattern predicate, this is non-motionless because atomizing the
     * matched node requires consuming its descendant text content.
     */
    static boolean accessesStringValueOfSelf(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String name = fc.getLocalName();
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                Expr arg = args.get(i);
                if (isSelfReference(arg) && atomizesArgument(name)) {
                    return true;
                }
                if (accessesStringValueOfSelf(arg)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            if (isSelfReference(be.getLeft()) ||
                isSelfReference(be.getRight())) {
                return true;
            }
            return accessesStringValueOfSelf(be.getLeft()) ||
                   accessesStringValueOfSelf(be.getRight());
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            return accessesStringValueOfSelf(fe.getPrimary());
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return accessesStringValueOfSelf(pe.getFilter()) ||
                   accessesStringValueOfSelf(pe.getPath());
        }
        return false;
    }

    /**
     * Returns true if the expression is a reference to the context item
     * ({@code .} or {@code self::node()}).
     */
    static boolean isSelfReference(Expr expr) {
        if (expr instanceof ContextItemExpr) {
            return true;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            if (steps.size() == 1) {
                Step step = steps.get(0);
                if (step.getAxis() == Step.Axis.SELF) {
                    return true;
                }
            }
        }
        // current() in a pattern predicate refers to the context item
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            if ("current".equals(fc.getLocalName())) {
                List<Expr> args = fc.getArguments();
                if (args == null || args.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the node test matches a node type whose string value
     * is intrinsic (no descendant traversal required): text, comment,
     * processing-instruction, or attribute nodes.
     */
    static boolean isAtomicContentNodeTest(NodeTest test) {
        return test instanceof TextTest
            || test instanceof CommentTest
            || test instanceof PITest
            || test instanceof AttributeTest;
    }

    /**
     * Returns true if the named function atomizes (accesses string/numeric
     * value of) its arguments. Functions that only inspect node identity
     * or structural properties return false.
     */
    static boolean atomizesArgument(String functionName) {
        if ("name".equals(functionName) ||
            "local-name".equals(functionName) ||
            "namespace-uri".equals(functionName) ||
            "node-name".equals(functionName) ||
            "nilled".equals(functionName) ||
            "count".equals(functionName) ||
            "empty".equals(functionName) ||
            "exists".equals(functionName) ||
            "boolean".equals(functionName) ||
            "not".equals(functionName) ||
            "generate-id".equals(functionName) ||
            "has-children".equals(functionName) ||
            "deep-equal".equals(functionName)) {
            return false;
        }
        return true;
    }

    /**
     * Checks whether a GROUNDED expression only accesses ancestor attributes,
     * which are available in a streaming context (maintained on a stack).
     * Returns true only if the expression actually uses a parent/ancestor
     * axis to reach attributes, and has no other source of grounded-ness
     * (like last(), root(), etc.).
     */
    static boolean isAncestorAttributeOnly(Expr expr) {
        if (expr == null) {
            return false;
        }
        return hasAncestorAxisStep(expr)
            && allPathsUseAncestorAttributeOnly(expr);
    }

    static boolean hasAncestorAxisStep(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT || axis == Step.Axis.ANCESTOR
                        || axis == Step.Axis.ANCESTOR_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return hasAncestorAxisStep(pe.getFilter())
                || hasAncestorAxisStep(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return hasAncestorAxisStep(be.getLeft())
                || hasAncestorAxisStep(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (hasAncestorAxisStep(args.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            return hasAncestorAxisStep(fe.getPrimary());
        }
        return false;
    }

    static boolean allPathsUseAncestorAttributeOnly(Expr expr) {
        if (expr == null) {
            return true;
        }
        if (expr instanceof Literal) {
            return true;
        }
        if (expr instanceof VariableReference) {
            return true;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT || axis == Step.Axis.ANCESTOR
                        || axis == Step.Axis.ANCESTOR_OR_SELF
                        || axis == Step.Axis.ATTRIBUTE
                        || axis == Step.Axis.SELF
                        || axis == Step.Axis.NAMESPACE) {
                    continue;
                }
                return false;
            }
            return true;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return allPathsUseAncestorAttributeOnly(pe.getFilter())
                && allPathsUseAncestorAttributeOnly(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return allPathsUseAncestorAttributeOnly(be.getLeft())
                && allPathsUseAncestorAttributeOnly(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (!allPathsUseAncestorAttributeOnly(args.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            if (!allPathsUseAncestorAttributeOnly(fe.getPrimary())) {
                return false;
            }
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                if (!allPathsUseAncestorAttributeOnly(preds.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    /**
     * Checks whether an accumulator-rule select expression navigates
     * to child or descendant content, which is not motionless.
     */
    static boolean accRuleSelectHasChildNavigation(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.CHILD
                        || axis == Step.Axis.DESCENDANT
                        || axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return accRuleSelectHasChildNavigation(pe.getFilter())
                || accRuleSelectHasChildNavigation(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return accRuleSelectHasChildNavigation(be.getLeft())
                || accRuleSelectHasChildNavigation(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (accRuleSelectHasChildNavigation(args.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof SequenceExpr) {
            List<Expr> items = ((SequenceExpr) expr).getItems();
            for (int i = 0; i < items.size(); i++) {
                if (accRuleSelectHasChildNavigation(items.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            return accRuleSelectHasChildNavigation(ie.getCondition())
                || accRuleSelectHasChildNavigation(ie.getThenExpr())
                || accRuleSelectHasChildNavigation(ie.getElseExpr());
        }
        if (expr instanceof UnaryExpr) {
            return accRuleSelectHasChildNavigation(
                ((UnaryExpr) expr).getOperand());
        }
        return false;
    }

    /**
     * Checks whether an accumulator-rule select expression captures the
     * context node (.) directly in a sequence, which is not grounded
     * in streaming (the node is transient).
     */
    static boolean accRuleSelectCapturesContextNode(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ContextItemExpr) {
            return true;
        }
        if (expr instanceof SequenceExpr) {
            List<Expr> items = ((SequenceExpr) expr).getItems();
            for (int i = 0; i < items.size(); i++) {
                if (accRuleSelectCapturesContextNode(items.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return accRuleSelectCapturesContextNode(be.getLeft())
                || accRuleSelectCapturesContextNode(be.getRight());
        }
        if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            return accRuleSelectCapturesContextNode(ie.getCondition())
                || accRuleSelectCapturesContextNode(ie.getThenExpr())
                || accRuleSelectCapturesContextNode(ie.getElseExpr());
        }
        return false;
    }

    /**
     * Checks whether a match pattern matches only leaf nodes (text,
     * comment, processing-instruction, attribute) whose string value
     * does not require descendant traversal.
     */
    static boolean isLeafNodeMatch(Pattern pattern) {
        if (pattern == null) {
            return false;
        }
        String patStr = pattern.toString();
        if (patStr == null) {
            return false;
        }
        String trimmed = patStr.trim();
        return trimmed.endsWith("text()")
            || trimmed.endsWith("comment()")
            || trimmed.contains("processing-instruction")
            || trimmed.startsWith("@")
            || trimmed.startsWith("attribute::");
    }

    /**
     * Checks whether an XSLT node tree contains a crawling instruction:
     * apply-templates or for-each with a descendant-axis select expression
     * not wrapped in outermost()/innermost(). Also detects FLWOR
     * expressions with descendant-axis bindings.
     */
    static boolean containsCrawlingInstruction(XSLTNode node,
                                                        int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ApplyTemplatesNode) {
            ApplyTemplatesNode at = (ApplyTemplatesNode) node;
            List<XPathExpression> exprs = at.getExpressions();
            if (exprs != null && !exprs.isEmpty()) {
                XPathExpression selectExpr = exprs.get(0);
                if (isDirectCrawlingSelect(selectExpr)) {
                    return true;
                }
            }
        }
        if (node instanceof ForEachNode) {
            ForEachNode fe = (ForEachNode) node;
            List<XPathExpression> exprs = fe.getExpressions();
            if (exprs != null && !exprs.isEmpty()) {
                XPathExpression selectExpr = exprs.get(0);
                if (isDirectCrawlingSelect(selectExpr)) {
                    XSLTNode body = fe.getBody();
                    if (bodyHasConsumingExpression(body)) {
                        return true;
                    }
                }
            }
        }
        // Check ExpressionHolder for FLWOR with descendant axis in bindings
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null && containsCrawlingFlwor(
                            xpe.getCompiledExpr())) {
                        return true;
                    }
                }
            }
        }
        // Recurse into children
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsCrawlingInstruction(children.get(i),
                                                    depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (containsCrawlingInstruction(when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (containsCrawlingInstruction(choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof VariableNode) {
            XSLTNode content =
                ((VariableNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content =
                ((LiteralResultElement) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (containsCrawlingInstruction(body, depth + 1)) {
                return true;
            }
        }
        if (node instanceof AttributeNode) {
            XSLTNode content =
                ((AttributeNode) node).getContent();
            if (containsCrawlingInstruction(content, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a select expression directly uses descendant axis,
     * not wrapped inside outermost()/innermost() or aggregate functions.
     */
    static boolean isDirectCrawlingSelect(XPathExpression xpathExpr) {
        if (xpathExpr == null) {
            return false;
        }
        return isDirectCrawling(xpathExpr.getCompiledExpr());
    }

    static boolean isDirectCrawling(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.DESCENDANT
                        || axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return isDirectCrawling(pe.getFilter())
                || isDirectCrawling(pe.getPath());
        }
        if (expr instanceof FilterExpr) {
            return isDirectCrawling(((FilterExpr) expr).getPrimary());
        }
        if (expr instanceof ContextItemExpr) {
            return false;
        }
        // Do NOT recurse into FunctionCall arguments —
        // outermost(.//x), count(.//x), etc. are safe
        return false;
    }

    /**
     * Checks whether a for-each body has consuming expressions
     * (child axis navigation), which combined with a crawling select
     * creates a non-streamable pattern.
     */
    static boolean bodyHasConsumingExpression(XSLTNode body) {
        return bodyHasConsumingExpr(body, 0);
    }

    /**
     * Like bodyHasConsumingExpression but only triggers for forward-consuming
     * expressions (CONSUMING or FREE_RANGING), not GROUNDED. Used for
     * crawling for-each validation where ancestor access is acceptable.
     */
    static boolean bodyHasForwardConsumingExpression(XSLTNode body) {
        return bodyHasForwardConsumingExpr(body, 0);
    }

    static boolean bodyHasForwardConsumingExpr(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null) {
                        StreamabilityAnalyzer.ExpressionStreamability es =
                            StreamingClassifier.classify(xpe);
                        if (es == StreamabilityAnalyzer.ExpressionStreamability.CONSUMING
                                || es == StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING) {
                            return true;
                        }
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (bodyHasForwardConsumingExpr(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            if (bodyHasForwardConsumingExpr(
                    ((LiteralResultElement) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            if (bodyHasForwardConsumingExpr(
                    ((ElementNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof CopyNode) {
            if (bodyHasForwardConsumingExpr(
                    ((CopyNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            if (bodyHasForwardConsumingExpr(
                    ((IfNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (bodyHasForwardConsumingExpr(when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (bodyHasForwardConsumingExpr(choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof AttributeNode) {
            if (bodyHasForwardConsumingExpr(
                    ((AttributeNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            if (bodyHasForwardConsumingExpr(
                    ((ForEachNode) node).getBody(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean bodyHasConsumingExpr(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null) {
                        StreamabilityAnalyzer.ExpressionStreamability es =
                            StreamingClassifier.classify(xpe);
                        if (es.ordinal() >
                                StreamabilityAnalyzer.ExpressionStreamability.MOTIONLESS.ordinal()) {
                            return true;
                        }
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (bodyHasConsumingExpr(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content = ((LiteralResultElement) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (bodyHasConsumingExpr(when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (bodyHasConsumingExpr(choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof AttributeNode) {
            XSLTNode content =
                ((AttributeNode) node).getContent();
            if (bodyHasConsumingExpr(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (bodyHasConsumingExpr(body, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a for-each body accesses the content of the context
     * item via xsl:value-of, xsl:copy-of, or xsl:copy. These instructions
     * implicitly atomize or deep-copy the context node, making the body
     * consuming even though "." classifies as MOTIONLESS (self axis).
     */
    /**
     * Returns true if the for-each body contains an expression where
     * current() is used in a content-consuming context — for example,
     * string(current()) in a predicate. In streaming mode, current()
     * refers to the for-each context item which is a streamed node;
     * computing its string value is consuming and conflicts with
     * child navigation in the same expression.
     */
    static boolean bodyHasConsumingCurrentCall(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null) {
                        Expr compiled = xpe.getCompiledExpr();
                        if (exprHasConsumingCurrent(compiled)) {
                            return true;
                        }
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (bodyHasConsumingCurrentCall(
                            children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            if (bodyHasConsumingCurrentCall(
                    ((LiteralResultElement) node).getContent(),
                    depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            if (bodyHasConsumingCurrentCall(
                    ((ElementNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            if (bodyHasConsumingCurrentCall(
                    ((IfNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (bodyHasConsumingCurrentCall(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (bodyHasConsumingCurrentCall(
                    choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the expression contains current() as an argument
     * to a content-consuming function (string, data) or as an operand
     * in a comparison within a predicate of a consuming step.
     */
    private static boolean exprHasConsumingCurrent(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            for (Step step : lp.getSteps()) {
                List<Expr> preds = step.getPredicates();
                if (preds != null) {
                    for (int i = 0; i < preds.size(); i++) {
                        if (predicateConsumesCurrentContent(preds.get(i))) {
                            return true;
                        }
                    }
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            if (exprHasConsumingCurrent(pe.getFilter())) {
                return true;
            }
            if (exprHasConsumingCurrent(pe.getPath())) {
                return true;
            }
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            if (exprHasConsumingCurrent(fe.getPrimary())) {
                return true;
            }
            List<Expr> preds = fe.getPredicates();
            if (preds != null) {
                for (int i = 0; i < preds.size(); i++) {
                    if (predicateConsumesCurrentContent(preds.get(i))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if this predicate expression contains current()
     * used in a way that requires the string value of the for-each
     * context item — specifically, current() wrapped in string() or
     * data(), or current() as a bare comparison operand (which
     * triggers implicit atomization of the element node).
     */
    private static boolean predicateConsumesCurrentContent(Expr pred) {
        if (pred == null) {
            return false;
        }
        if (pred instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) pred;
            String name = fc.getLocalName();
            if ("string".equals(name) || "data".equals(name)
                    || "normalize-space".equals(name)
                    || "string-length".equals(name)
                    || "number".equals(name)) {
                List<Expr> args = fc.getArguments();
                if (args != null) {
                    for (int i = 0; i < args.size(); i++) {
                        if (isCurrentCall(args.get(i))) {
                            return true;
                        }
                    }
                }
            }
            List<Expr> args = fc.getArguments();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    if (predicateConsumesCurrentContent(args.get(i))) {
                        return true;
                    }
                }
            }
        }
        if (pred instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) pred;
            Operator op = be.getOperator();
            if (op == Operator.EQUALS || op == Operator.NOT_EQUALS
                    || op == Operator.LESS_THAN
                    || op == Operator.GREATER_THAN
                    || op == Operator.LESS_THAN_OR_EQUAL
                    || op == Operator.GREATER_THAN_OR_EQUAL
                    || op == Operator.VALUE_EQUALS
                    || op == Operator.VALUE_NOT_EQUALS
                    || op == Operator.VALUE_LESS_THAN
                    || op == Operator.VALUE_GREATER_THAN
                    || op == Operator.VALUE_LESS_THAN_OR_EQUAL
                    || op == Operator.VALUE_GREATER_THAN_OR_EQUAL) {
                if (isCurrentCall(be.getLeft())
                        || isCurrentCall(be.getRight())) {
                    return true;
                }
            }
            if (predicateConsumesCurrentContent(be.getLeft())) {
                return true;
            }
            if (predicateConsumesCurrentContent(be.getRight())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCurrentCall(Expr expr) {
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            if ("current".equals(fc.getLocalName())) {
                List<Expr> args = fc.getArguments();
                return args == null || args.isEmpty();
            }
        }
        return false;
    }

    static boolean bodyConsumesContextItemContent(XSLTNode node,
                                                   int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ValueOfNode) {
            List<XPathExpression> exprs =
                ((ValueOfNode) node).getExpressions();
            if (exprs != null && !exprs.isEmpty()) {
                XPathExpression selExpr = exprs.get(0);
                if (selExpr != null) {
                    Expr compiled = selExpr.getCompiledExpr();
                    if (isSelfReference(compiled)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyOfNode) {
            List<XPathExpression> exprs =
                ((CopyOfNode) node).getExpressions();
            if (exprs != null && !exprs.isEmpty()) {
                XPathExpression selExpr = exprs.get(0);
                if (selExpr != null) {
                    Expr compiled = selExpr.getCompiledExpr();
                    if (isSelfReference(compiled)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            return true;
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (bodyConsumesContextItemContent(
                            children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            if (bodyConsumesContextItemContent(
                    ((LiteralResultElement) node).getContent(),
                    depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            if (bodyConsumesContextItemContent(
                    ((ElementNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            if (bodyConsumesContextItemContent(
                    ((IfNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (bodyConsumesContextItemContent(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            if (bodyConsumesContextItemContent(
                    choose.getOtherwise(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof AttributeNode) {
            if (bodyConsumesContextItemContent(
                    ((AttributeNode) node).getContent(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether an XPath expression tree contains a FLWOR for-expression
     * with descendant axis in its binding. Such expressions create crawling
     * patterns: for $x in .//y return $x/z
     */
    static boolean containsCrawlingFlwor(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ForExpr) {
            ForExpr fe = (ForExpr) expr;
            List<ForExpr.Binding> bindings = fe.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                Expr seq = bindings.get(i).getSequence();
                if (StreamingClassifier.containsDescendantAxis(seq)) {
                    return true;
                }
            }
        }
        // Recurse into sub-expressions
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return containsCrawlingFlwor(be.getLeft())
                || containsCrawlingFlwor(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (containsCrawlingFlwor(args.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            if (containsCrawlingFlwor(fe.getPrimary())) {
                return true;
            }
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                if (containsCrawlingFlwor(preds.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return containsCrawlingFlwor(pe.getFilter())
                || containsCrawlingFlwor(pe.getPath());
        }
        return false;
    }

    /**
     * Checks whether an XSLT node tree contains any instruction compiled
     * in backwards-compatible mode (version="1.0"). Per XSLT 3.0 section
     * 3.9.1, such instructions are roaming and free-ranging.
     */
    static boolean containsBackwardsCompatInstruction(XSLTNode node,
                                                               int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ApplyTemplatesNode) {
            if (((ApplyTemplatesNode) node).isBackwardsCompatible()) {
                return true;
            }
        }
        if (node instanceof SequenceNode) {
            SequenceNode seq = (SequenceNode) node;
            List<XSLTNode> children = seq.getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsBackwardsCompatInstruction(children.get(i),
                                                           depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            if (containsBackwardsCompatInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            if (containsBackwardsCompatInstruction(body, depth + 1)) {
                return true;
            }
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            if (containsBackwardsCompatInstruction(content, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                XSLTNode wContent = when.getContent();
                if (containsBackwardsCompatInstruction(wContent, depth + 1)) {
                    return true;
                }
            }
            XSLTNode ow = choose.getOtherwise();
            if (containsBackwardsCompatInstruction(ow, depth + 1)) {
                return true;
            }
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            if (containsBackwardsCompatInstruction(content, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * XTSE3430: Validates xsl:fork streamability constraints within a
     * streaming context body. Walks the AST looking for ForkNode instances
     * and checks each branch for streaming violations.
     *
     * @param node the AST node to validate
     * @param depth recursion depth guard
     * @throws SAXException if a streamability violation is found
     */
    static void validateForkStreamability(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ForkNode) {
            ForkNode fork = (ForkNode) node;
            validateForkBranches(fork);
        }

        // Recurse into child nodes
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateForkStreamability(children.get(i), depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            validateForkStreamability(((ForEachNode) node).getBody(),
                                     depth + 1);
        }
        if (node instanceof IfNode) {
            validateForkStreamability(((IfNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateForkStreamability(when, depth + 1);
            }
            validateForkStreamability(choose.getOtherwise(), depth + 1);
        }
        if (node instanceof WhenNode) {
            validateForkStreamability(((WhenNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof VariableNode) {
            validateForkStreamability(((VariableNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof CopyNode) {
            validateForkStreamability(((CopyNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateForkStreamability(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            validateForkStreamability(((ElementNode) node).getContent(),
                                     depth + 1);
        }
        if (node instanceof TryNode) {
            TryNode tryNode = (TryNode) node;
            validateForkStreamability(tryNode.getTryContent(), depth + 1);
            for (CatchNode catchNode : tryNode.getCatchBlocks()) {
                validateForkStreamability(catchNode, depth + 1);
            }
        }
        if (node instanceof CatchNode) {
            validateForkStreamability(((CatchNode) node).getContent(),
                                     depth + 1);
        }
    }

    /**
     * Validates the branches of a ForkNode for streaming compliance.
     * Checks for:
     * <ul>
     *   <li>Fork prongs returning streamed nodes (si-fork-901)</li>
     *   <li>Fork prongs with multiple consuming operands (si-fork-902)</li>
     *   <li>For-each-group in fork with streaming violations</li>
     * </ul>
     */
    static void validateForkBranches(ForkNode fork) throws SAXException {
        List<ForkNode.ForkBranch> branches = fork.getBranches();

        // Count branches that output streamed nodes directly.
        // Multiple consuming branches are fine (that's the purpose of fork),
        // but multiple branches that RETURN raw streamed nodes are not.
        int streamedNodeBranches = 0;
        for (int i = 0; i < branches.size(); i++) {
            ForkNode.ForkBranch branch = branches.get(i);
            XSLTNode content = branch.getContent();
            if (content == null) {
                continue;
            }

            if (branchReturnsStreamedNodes(content)) {
                streamedNodeBranches++;
            }

            // Check for multiple consuming operands in a single
            // expression (si-fork-902)
            validateBranchOperands(content);

            // Check for ForEachGroupNode within fork branches
            validateForkForEachGroup(content, 0);
        }

        if (streamedNodeBranches > 1) {
            throw new SAXException("XTSE3430: xsl:fork is not streamable:" +
                " multiple prongs return streamed nodes");
        }
    }

    /**
     * Returns true if a fork branch outputs streamed nodes directly.
     * This happens when a SequenceOutputNode has a select expression
     * that evaluates to nodes (LocationPath/PathExpr) and is consuming.
     * Expressions wrapped in grounding functions (string(), number(),
     * sum(), etc.) return atomic values and are fine.
     */
    static boolean branchReturnsStreamedNodes(XSLTNode node) {
        if (node instanceof SequenceOutputNode) {
            return isStreamedNodeReturning((SequenceOutputNode) node);
        }
        return false;
    }

    /**
     * Returns true if a SequenceOutputNode returns consuming streamed
     * nodes. The expression returns nodes (not grounded values) when its
     * top-level is a LocationPath, PathExpr, or ContextItemExpr.
     */
    static boolean isStreamedNodeReturning(SequenceOutputNode son) {
        List<XPathExpression> exprs = son.getExpressions();
        for (int i = 0; i < exprs.size(); i++) {
            XPathExpression xpe = exprs.get(i);
            Expr compiled = xpe.getCompiledExpr();
            if (compiled instanceof LocationPath
                    || compiled instanceof PathExpr
                    || compiled instanceof ContextItemExpr) {
                StreamabilityAnalyzer.ExpressionStreamability es =
                    StreamingClassifier.classify(xpe);
                if (es == StreamabilityAnalyzer.ExpressionStreamability
                        .CONSUMING
                        || es == StreamabilityAnalyzer.ExpressionStreamability
                        .GROUNDED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks for multiple consuming operands within a single expression
     * in a fork branch (si-fork-902: {@code TITLE||PRICE}).
     */
    static void validateBranchOperands(XSLTNode node) throws SAXException {
        List<XSLTNode> toCheck = new ArrayList<XSLTNode>();
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                toCheck.addAll(children);
            }
        } else {
            toCheck.add(node);
        }

        for (int i = 0; i < toCheck.size(); i++) {
            XSLTNode child = toCheck.get(i);
            if (child instanceof SequenceOutputNode) {
                List<XPathExpression> exprs =
                    ((ExpressionHolder) child).getExpressions();
                for (int j = 0; j < exprs.size(); j++) {
                    int ops = StreamingClassifier.countConsumingOperands(
                        exprs.get(j));
                    if (ops > 1) {
                        throw new SAXException("XTSE3430: xsl:fork " +
                            "prong is not streamable: expression has " +
                            "multiple consuming operands");
                    }
                }
            }
        }
    }

    /**
     * Validates xsl:for-each-group within a fork branch for streaming
     * constraints (si-fork-951 through si-fork-957).
     */
    static void validateForkForEachGroup(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ForEachGroupNode) {
            ForEachGroupNode feg = (ForEachGroupNode) node;

            // (si-fork-955, 956, 957): Crawling population (descendant axis)
            // This also catches non-motionless group-by when the population
            // is crawling, since descendant-axis select implies the group-by
            // operates on streamed nodes.
            XPathExpression selectExpr = feg.getSelect();
            if (StreamingClassifier.containsDescendantAxis(selectExpr)) {
                throw new SAXException("XTSE3430: xsl:for-each-group in " +
                    "xsl:fork is not streamable: population uses " +
                    "descendant axis (crawling)");
            }

            // (si-fork-953): Sorted groups
            List<SortSpec> sorts = feg.getSorts();
            if (sorts != null && !sorts.isEmpty()) {
                throw new SAXException("XTSE3430: xsl:for-each-group in " +
                    "xsl:fork is not streamable: sorted groups require " +
                    "buffering");
            }

            // Check the body for current-group() usage violations
            validateForkGroupBody(feg.getBody(), 0);
        }

        // Recurse into child nodes to find nested ForEachGroupNode
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateForkForEachGroup(children.get(i), depth + 1);
                }
            }
        }
    }

    /**
     * Validates the body of a for-each-group within a fork for
     * current-group() usage violations.
     *
     * <p>In a streaming fork, current-group() may only be consumed once.
     * Multiple uses, mixing with context-item consumption, or use in
     * higher-order operands all violate streamability.
     */
    static void validateForkGroupBody(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        int totalCurrentGroupCalls =
            countCurrentGroupCalls(node, 0);

        // (si-fork-951, 952, 954): Multiple current-group() usage
        if (totalCurrentGroupCalls > 1) {
            throw new SAXException("XTSE3430: xsl:for-each-group in " +
                "xsl:fork is not streamable: current-group() is used " +
                "more than once");
        }

        // (si-fork-954): current-group() mixed with context-item
        // down-selection. If there is one current-group() call AND a
        // consuming expression that does not involve current-group(),
        // both compete for the streamed input.
        if (totalCurrentGroupCalls >= 1) {
            boolean hasOtherConsuming =
                hasNonGroupConsuming(node, 0);
            if (hasOtherConsuming) {
                throw new SAXException("XTSE3430: xsl:for-each-group " +
                    "in xsl:fork is not streamable: current-group() " +
                    "mixed with context item down-selection");
            }
        }

        // (si-fork-952): current-group() with multiple consuming
        // down-selections, e.g. current-group()/(AUTHOR||TITLE)
        if (hasMultipleConsumingFromGroup(node, 0)) {
            throw new SAXException("XTSE3430: xsl:for-each-group in " +
                "xsl:fork is not streamable: multiple down-selections " +
                "from current-group()");
        }

        // (si-fork-956): current-group() in higher-order operand
        // (predicate or nested for-each body)
        if (hasCurrentGroupInHigherOrder(node, 0)) {
            throw new SAXException("XTSE3430: xsl:for-each-group in " +
                "xsl:fork is not streamable: current-group() used in " +
                "higher-order operand");
        }
    }

    /**
     * XTSE3430: Validates that xsl:map instructions in streaming contexts
     * do not have multiple map entries with consuming select expressions.
     */
    static void validateStreamingMapConstruction(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        if (node instanceof MapConstructionNode) {
            MapConstructionNode mapNode = (MapConstructionNode) node;
            XSLTNode content = mapNode.getContent();
            if (content instanceof SequenceNode) {
                List<XSLTNode> children =
                    ((SequenceNode) content).getChildren();
                int consumingEntries = 0;
                for (XSLTNode child : children) {
                    if (child instanceof MapEntryNode) {
                        MapEntryNode entry = (MapEntryNode) child;
                        List<XPathExpression> exprs = entry.getExpressions();
                        for (XPathExpression expr : exprs) {
                            StreamabilityAnalyzer.ExpressionStreamability es =
                                StreamingClassifier.classify(expr);
                            if (es == StreamabilityAnalyzer.ExpressionStreamability.CONSUMING ||
                                es == StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING) {
                                consumingEntries++;
                                break;
                            }
                        }
                    } else {
                        // Non-map-entry child (e.g. xsl:if) breaks
                        // implicit fork — map is non-streamable
                        consumingEntries += 2;
                        break;
                    }
                }
                if (consumingEntries > 1) {
                    throw new SAXException("XTSE3430: xsl:map in " +
                        "streaming context has multiple map entries " +
                        "with consuming expressions");
                }
            }
        }
        // Recurse into children
        if (node instanceof SequenceNode) {
            for (XSLTNode child : ((SequenceNode) node).getChildren()) {
                validateStreamingMapConstruction(child, depth + 1);
            }
        }
        if (node instanceof IfNode) {
            validateStreamingMapConstruction(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ForEachNode) {
            validateStreamingMapConstruction(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof VariableNode) {
            XSLTNode varContent = ((VariableNode) node).getContent();
            if (varContent != null) {
                validateStreamingMapConstruction(varContent, depth + 1);
            }
        }
    }

    static void validateStreamingForEachGroup(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }

        if (node instanceof ForEachGroupNode) {
            ForEachGroupNode feg = (ForEachGroupNode) node;
            boolean groundedPopulation = isGroundedPopulation(feg);

            // Only validate patterns/keys when population is NOT
            // grounded (copy-of makes items fully available)
            if (!groundedPopulation) {
                // Non-motionless pattern for group-starting-with
                // when population is child elements (not text nodes)
                Pattern startPat = feg.getGroupStartingPattern();
                if (startPat != null) {
                    String patStr = startPat.toString();
                    boolean matchesText = patStr.startsWith("text()");
                    if (!matchesText) {
                        if (hasNonMotionlessPredicate(startPat)) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group group-starting-with " +
                                "pattern '" + patStr +
                                "' is not motionless");
                        }
                        // position()/last() in pattern predicates are
                        // non-streamable (require sibling counting)
                        if (patternUsesPosition(startPat)) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group group-starting-with " +
                                "pattern '" + patStr +
                                "' uses position()/last() which " +
                                "requires sibling counting");
                        }
                    }
                }

                // Non-motionless key for group-adjacent (only flag
                // path expressions with slash, e.g. "PRICE/text()")
                XPathExpression adjExpr = feg.getGroupAdjacentExpr();
                if (adjExpr != null) {
                    String keyStr = adjExpr.getExpressionString();
                    if (keyStr != null && keyStr.contains("/")) {
                        if (!keyStr.trim().startsWith("@")) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group group-adjacent " +
                                "key '" + keyStr + "' is not motionless");
                        }
                    }
                }
            }

            // Body checks
            XSLTNode body = feg.getBody();
            if (body != null) {
                // Two current-group() in same expression
                // (only when population is NOT grounded)
                if (!groundedPopulation) {
                    if (hasMultipleCurrentGroupInSameExpr(body, 0)) {
                        throw new SAXException("XTSE3430: Streaming " +
                            "xsl:for-each-group body references " +
                            "current-group() multiple times in the " +
                            "same expression");
                    }
                }

                // current-group() in nested source-document or
                // context-changing xsl:copy (only when population is
                // NOT grounded, since grounded items are safe anywhere)
                if (!groundedPopulation) {
                    if (containsCurrentGroupRef(body, 0)) {
                        if (hasCurrentGroupInNestedContext(body, 0)) {
                            throw new SAXException("XTSE3430: Streaming " +
                                "xsl:for-each-group uses " +
                                "current-group() inside a nested " +
                                "streaming or context-changing " +
                                "instruction");
                        }
                    }
                }
            }
        }

        // Recurse into child nodes
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateStreamingForEachGroup(children.get(i),
                        depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            validateStreamingForEachGroup(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            validateStreamingForEachGroup(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateStreamingForEachGroup(
                    when.getContent(), depth + 1);
            }
            validateStreamingForEachGroup(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateStreamingForEachGroup(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof CopyNode) {
            validateStreamingForEachGroup(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            validateStreamingForEachGroup(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            validateStreamingForEachGroup(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ResultDocumentNode) {
            validateStreamingForEachGroup(
                ((ResultDocumentNode) node).getContent(), depth + 1);
        }
    }

    /**
     * XTSE3430: Validates streaming constraints for xsl:iterate and
     * xsl:for-each inside xsl:source-document streamable bodies.
     */
    static void validateStreamingLoopBodies(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        if (node instanceof IterateNode) {
            IterateNode iterate = (IterateNode) node;
            XSLTNode iterBody = iterate.getBody();
            if (iterBody != null) {
                checkStreamingLoopBodyStreamability(iterBody, 0, true);
            }
        }
        if (node instanceof ForEachNode) {
            ForEachNode forEach = (ForEachNode) node;
            XSLTNode feBody = forEach.getBody();
            if (feBody != null) {
                checkStreamingLoopBodyStreamability(feBody, 0, false);
                List<XPathExpression> feExprs = forEach.getExpressions();
                if (feExprs != null && !feExprs.isEmpty()) {
                    XPathExpression selectExpr = feExprs.get(0);
                    boolean crawling = isDirectCrawlingSelect(selectExpr);
                    boolean consuming =
                        bodyHasForwardConsumingExpression(feBody)
                        || bodyConsumesContextItemContent(feBody, 0);
                    if (crawling && consuming) {
                        throw new SAXException(
                            "XTSE3430: xsl:for-each in streaming"
                            + " context has crawling select with"
                            + " consuming body");
                    }
                }
                if (feExprs != null && !feExprs.isEmpty()) {
                    XPathExpression selForCurrent = feExprs.get(0);
                    Expr selCompiled = selForCurrent != null
                        ? selForCurrent.getCompiledExpr() : null;
                    StreamabilityAnalyzer.ExpressionStreamability selClass =
                        StreamingClassifier.classify(selCompiled);
                    boolean selectIsConsuming =
                        selClass == StreamabilityAnalyzer
                            .ExpressionStreamability.CONSUMING
                        || selClass == StreamabilityAnalyzer
                            .ExpressionStreamability.FREE_RANGING;
                    if (selectIsConsuming
                            && bodyHasConsumingCurrentCall(feBody, 0)) {
                        throw new SAXException(
                            "XTSE3430: xsl:for-each in streaming"
                            + " context uses current() in a"
                            + " content-consuming context");
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                List<String> contextCapturingVars =
                    new ArrayList<String>();
                for (int i = 0; i < children.size(); i++) {
                    XSLTNode child = children.get(i);
                    if (child instanceof VariableNode) {
                        VariableNode vn = (VariableNode) child;
                        if (variableCapturesContext(vn)) {
                            contextCapturingVars.add(vn.getName());
                        }
                    }
                    if (!contextCapturingVars.isEmpty()) {
                        XSLTNode loopBody = null;
                        String loopName = null;
                        if (child instanceof IterateNode) {
                            loopBody =
                                ((IterateNode) child).getBody();
                            loopName = "xsl:iterate";
                        } else if (child instanceof ForEachNode) {
                            loopBody =
                                ((ForEachNode) child).getBody();
                            loopName = "xsl:for-each";
                        }
                        if (loopBody != null
                                && iterateBodyNavigatesFromVars(
                                    loopBody,
                                    contextCapturingVars, 0)) {
                            throw new SAXException(
                                "XTSE3430: Streaming " + loopName +
                                " body navigates from a variable " +
                                "that captures the streaming " +
                                "context node");
                        }
                    }
                    validateStreamingLoopBodies(child, depth + 1);
                }
            }
            return;
        }
        if (node instanceof CopyNode) {
            validateStreamingLoopBodies(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            validateStreamingLoopBodies(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateStreamingLoopBodies(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof IfNode) {
            validateStreamingLoopBodies(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateStreamingLoopBodies(
                    when.getContent(), depth + 1);
            }
            validateStreamingLoopBodies(
                choose.getOtherwise(), depth + 1);
        }
    }

    /**
     * Validates xsl:map streaming constraints in a streamable body.
     */
    static void validateStreamingMapEntries(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        if (node instanceof MapConstructionNode) {
            MapConstructionNode map = (MapConstructionNode) node;
            SequenceNode content = map.getContent();
            if (content != null && content.getChildren() != null) {
                boolean hasNonEntry = false;
                for (XSLTNode child : content.getChildren()) {
                    if (!(child instanceof MapEntryNode)) {
                        hasNonEntry = true;
                    }
                }
                if (hasNonEntry) {
                    boolean anyEntryConsumes = false;
                    for (XSLTNode child : content.getChildren()) {
                        if (child instanceof MapEntryNode) {
                            MapEntryNode entry = (MapEntryNode) child;
                            XPathExpression sel = entry.getSelectExpr();
                            if (sel != null) {
                                Expr compiled = sel.getCompiledExpr();
                                StreamabilityAnalyzer.ExpressionStreamability sc =
                                    StreamingClassifier.classify(compiled);
                                if (sc == StreamabilityAnalyzer.ExpressionStreamability.CONSUMING ||
                                    sc == StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING) {
                                    anyEntryConsumes = true;
                                }
                            }
                        }
                    }
                    if (anyEntryConsumes) {
                        throw new SAXException(
                            "XTSE3430: xsl:map in streaming context " +
                            "has non-xsl:map-entry children — " +
                            "implicit fork not available");
                    }
                }
            }
        }
        if (node instanceof MapEntryNode) {
            MapEntryNode entry = (MapEntryNode) node;
            XPathExpression sel = entry.getSelectExpr();
            XPathExpression key = entry.getKeyExpr();
            if (sel != null) {
                Expr selCompiled = sel.getCompiledExpr();
                StreamabilityAnalyzer.ExpressionStreamability selClass =
                    StreamingClassifier.classify(selCompiled);
                boolean selConsuming =
                    selClass == StreamabilityAnalyzer.ExpressionStreamability.CONSUMING
                    || selClass == StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING;
                if (selConsuming && StreamingClassifier.isBareNodePath(selCompiled)) {
                    throw new SAXException(
                        "XTSE3430: xsl:map-entry select '" +
                        sel + "' produces streaming nodes " +
                        "that cannot be stored in a map");
                }
                if (key != null && selConsuming) {
                    Expr keyCompiled = key.getCompiledExpr();
                    StreamabilityAnalyzer.ExpressionStreamability keyClass =
                        StreamingClassifier.classify(keyCompiled);
                    boolean keyConsuming =
                        keyClass == StreamabilityAnalyzer.ExpressionStreamability.CONSUMING
                        || keyClass == StreamabilityAnalyzer.ExpressionStreamability.FREE_RANGING;
                    if (keyConsuming) {
                        throw new SAXException(
                            "XTSE3430: xsl:map-entry key and " +
                            "select both consume the stream");
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateStreamingMapEntries(children.get(i), depth + 1);
                }
            }
            return;
        }
        if (node instanceof MapConstructionNode) {
            validateStreamingMapEntries(
                ((MapConstructionNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            validateStreamingMapEntries(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ForEachNode) {
            validateStreamingMapEntries(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            validateStreamingMapEntries(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateStreamingMapEntries(when, depth + 1);
            }
            validateStreamingMapEntries(choose.getOtherwise(), depth + 1);
        }
        if (node instanceof WhenNode) {
            validateStreamingMapEntries(
                ((WhenNode) node).getContent(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateStreamingMapEntries(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ForkNode) {
            ForkNode fork = (ForkNode) node;
            List<ForkNode.ForkBranch> branches = fork.getBranches();
            if (branches != null) {
                for (int i = 0; i < branches.size(); i++) {
                    validateStreamingMapEntries(
                        branches.get(i).getContent(), depth + 1);
                }
            }
        }
    }

    /**
     * Detects non-streamable expression patterns within streaming contexts:
     * mixed-posture path expressions (grounded variable + crawling descendant),
     * union-of-striding-paths (crawling per spec), treat-as with
     * document-node(element(...)), and array constructors with mixed posture.
     */
    static void validateMixedPostureExpressions(XSLTNode node, int depth)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null) {
                        Expr compiled = xpe.getCompiledExpr();
                        checkExprForMixedPosture(compiled);
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateMixedPostureExpressions(
                        children.get(i), depth + 1);
                }
            }
            return;
        }
        if (node instanceof IfNode) {
            validateMixedPostureExpressions(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateMixedPostureExpressions(
                    when.getContent(), depth + 1);
            }
            validateMixedPostureExpressions(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            validateMixedPostureExpressions(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            validateMixedPostureExpressions(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof ForEachNode) {
            validateMixedPostureExpressions(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof ForkNode) {
            ForkNode fork = (ForkNode) node;
            List<ForkNode.ForkBranch> branches = fork.getBranches();
            if (branches != null) {
                for (int i = 0; i < branches.size(); i++) {
                    validateMixedPostureExpressions(
                        branches.get(i).getContent(), depth + 1);
                }
            }
        }
        if (node instanceof WhenNode) {
            validateMixedPostureExpressions(
                ((WhenNode) node).getContent(), depth + 1);
        }
        if (node instanceof CopyNode) {
            validateMixedPostureExpressions(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            validateMixedPostureExpressions(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof IterateNode) {
            validateMixedPostureExpressions(
                ((IterateNode) node).getBody(), depth + 1);
        }
    }

    /**
     * Checks a compiled expression for non-streamable mixed-posture patterns.
     */
    private static void checkExprForMixedPosture(Expr expr)
            throws SAXException {
        if (expr == null) {
            return;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            Expr filter = pe.getFilter();
            Expr unwrapped = unwrapFilter(filter);
            if (isMixedPostureGroup(unwrapped)) {
                throw new SAXException(
                    "XTSE3430: Expression is not streamable: " +
                    "path from mixed-posture expression " +
                    "(grounded and crawling operands)");
            }
            if (isStridingUnion(unwrapped)) {
                throw new SAXException(
                    "XTSE3430: Expression is not streamable: " +
                    "union of striding expressions is crawling");
            }
            if (isArrayWithMixedPosture(unwrapped)) {
                throw new SAXException(
                    "XTSE3430: Expression is not streamable: " +
                    "array with mixed-posture members " +
                    "(grounded and crawling operands)");
            }
        }
        if (expr instanceof TypeExpr) {
            TypeExpr te = (TypeExpr) expr;
            if (te.getKind() == TypeExpr.Kind.TREAT_AS) {
                SequenceType target = te.getTargetType();
                if (target != null &&
                    target.getItemKind() ==
                        SequenceType.ItemKind.DOCUMENT_NODE &&
                    target.getLocalName() != null) {
                    throw new SAXException(
                        "XTSE3430: Expression is not streamable: " +
                        "treat as document-node(element(...)) " +
                        "requires structural inspection");
                }
            }
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                checkExprForMixedPosture(args.get(i));
            }
        }
        if (expr instanceof PathExpr) {
            checkExprForMixedPosture(((PathExpr) expr).getFilter());
        }
    }

    private static Expr unwrapFilter(Expr expr) {
        if (expr instanceof FilterExpr) {
            return unwrapFilter(((FilterExpr) expr).getPrimary());
        }
        if (expr instanceof LookupExpr) {
            return unwrapFilter(((LookupExpr) expr).getBase());
        }
        return expr;
    }

    /**
     * Checks if an expression is a comma or union that mixes a grounded
     * variable reference with a crawling descendant-axis expression.
     */
    private static boolean isMixedPostureGroup(Expr expr) {
        if (expr instanceof SequenceExpr) {
            SequenceExpr se = (SequenceExpr) expr;
            List<Expr> items = se.getItems();
            boolean hasVariable = false;
            boolean hasDescendant = false;
            for (int i = 0; i < items.size(); i++) {
                Expr item = items.get(i);
                if (containsVariableRef(item)) {
                    hasVariable = true;
                }
                if (StreamingClassifier.containsDescendantAxis(item)) {
                    hasDescendant = true;
                }
            }
            return hasVariable && hasDescendant;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            Operator op = be.getOperator();
            if (op == Operator.UNION) {
                boolean leftVar = containsVariableRef(be.getLeft());
                boolean rightVar = containsVariableRef(be.getRight());
                boolean leftDesc =
                    StreamingClassifier.containsDescendantAxis(be.getLeft());
                boolean rightDesc =
                    StreamingClassifier.containsDescendantAxis(be.getRight());
                return (leftVar && rightDesc) || (rightVar && leftDesc);
            }
        }
        return false;
    }

    /**
     * Checks if an expression is a union of absolute (striding) paths
     * from the root, which per spec becomes crawling.
     */
    private static boolean isStridingUnion(Expr expr) {
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            if (be.getOperator() == Operator.UNION) {
                boolean leftAbs = isAbsoluteChildPath(be.getLeft());
                boolean rightAbs = isAbsoluteChildPath(be.getRight());
                return leftAbs && rightAbs;
            }
        }
        return false;
    }

    /**
     * Checks if an expression is an absolute path using only child axis
     * (striding), e.g. /BOOKLIST/ITEM.
     */
    private static boolean isAbsoluteChildPath(Expr expr) {
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            if (!lp.isAbsolute()) {
                return false;
            }
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis != Step.Axis.CHILD) {
                    return false;
                }
            }
            return steps.size() > 0;
        }
        return false;
    }

    /**
     * Checks if an expression is an array constructor that mixes
     * grounded variables with descendant-axis expressions.
     */
    private static boolean isArrayWithMixedPosture(Expr expr) {
        if (expr instanceof ArrayConstructorExpr) {
            ArrayConstructorExpr ace = (ArrayConstructorExpr) expr;
            List<Expr> members = ace.getMemberExprs();
            boolean hasVariable = false;
            boolean hasDescendant = false;
            for (int i = 0; i < members.size(); i++) {
                Expr member = members.get(i);
                if (containsVariableRef(member)) {
                    hasVariable = true;
                }
                if (StreamingClassifier.containsDescendantAxis(member)) {
                    hasDescendant = true;
                }
            }
            return hasVariable && hasDescendant;
        }
        return false;
    }

    private static boolean containsVariableRef(Expr expr) {
        if (expr instanceof VariableReference) {
            return true;
        }
        if (expr instanceof FilterExpr) {
            return containsVariableRef(
                ((FilterExpr) expr).getPrimary());
        }
        if (expr instanceof PathExpr) {
            return containsVariableRef(((PathExpr) expr).getFilter());
        }
        return false;
    }

    /**
     * Validates streaming constraints within an xsl:iterate or
     * xsl:for-each body inside streaming contexts.
     */
    static void checkStreamingLoopBodyStreamability(XSLTNode node,
                                                     int depth,
                                                     boolean isIterateBody)
            throws SAXException {
        checkStreamingLoopBodyImpl(node, depth, isIterateBody, false);
    }

    private static void checkStreamingLoopBodyImpl(XSLTNode node,
                                                    int depth,
                                                    boolean isIterateBody,
                                                    boolean insideElement)
            throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        // XTSE3430: xsl:sequence select="." returns a streaming node.
        // Only problematic when NOT inside element construction (where
        // the node content would be serialized into the element).
        if (!insideElement && node instanceof SequenceOutputNode) {
            SequenceOutputNode seqOut = (SequenceOutputNode) node;
            List<XPathExpression> exprs = seqOut.getExpressions();
            if (!exprs.isEmpty()) {
                XPathExpression selExpr = exprs.get(0);
                if (selExpr != null) {
                    Expr compiled = selExpr.getCompiledExpr();
                    if (compiled != null && isContextItemExpr(compiled)) {
                        String loopType = isIterateBody
                            ? "xsl:iterate" : "xsl:for-each";
                        throw new SAXException(
                            "XTSE3430: xsl:sequence in streaming " +
                            loopType + " body returns the " +
                            "streamed context node");
                    }
                }
            }
        }
        if (node instanceof ExpressionHolder
                && !(node instanceof IterateNode)) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe == null) {
                        continue;
                    }
                    Expr compiled = xpe.getCompiledExpr();
                    if (compiled != null
                            && hasMultipleConsumingOperands(compiled)) {
                        throw new SAXException(
                            "XTSE3430: Expression in streaming " +
                            "loop body has multiple " +
                            "consuming sub-expressions");
                    }
                    if (isIterateBody && compiled != null
                            && exprCapturesContextNode(compiled)) {
                        throw new SAXException(
                            "XTSE3430: Expression in streaming " +
                            "xsl:iterate body captures the " +
                            "context item as a node (use " +
                            "copy-of() instead)");
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    checkStreamingLoopBodyImpl(
                        children.get(i), depth + 1, isIterateBody,
                        insideElement);
                }
            }
            return;
        }
        if (node instanceof LiteralResultElement) {
            checkStreamingLoopBodyImpl(
                ((LiteralResultElement) node).getContent(), depth + 1,
                isIterateBody, true);
        }
        if (node instanceof CopyNode) {
            checkStreamingLoopBodyImpl(
                ((CopyNode) node).getContent(), depth + 1,
                isIterateBody, true);
        }
        if (node instanceof ElementNode) {
            checkStreamingLoopBodyImpl(
                ((ElementNode) node).getContent(), depth + 1,
                isIterateBody, true);
        }
        if (node instanceof IfNode) {
            checkStreamingLoopBodyImpl(
                ((IfNode) node).getContent(), depth + 1,
                isIterateBody, insideElement);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                checkStreamingLoopBodyImpl(
                    when.getContent(), depth + 1, isIterateBody,
                    insideElement);
            }
            checkStreamingLoopBodyImpl(
                choose.getOtherwise(), depth + 1, isIterateBody,
                insideElement);
        }
        if (node instanceof VariableNode) {
            checkStreamingLoopBodyImpl(
                ((VariableNode) node).getContent(), depth + 1,
                isIterateBody, insideElement);
        }
    }

    /**
     * Checks whether an expression has multiple independent consuming
     * sub-expressions at the BinaryExpr level.
     */
    static boolean hasMultipleConsumingOperands(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            Operator op = be.getOperator();
            boolean forkable = (op == Operator.UNION
                || op == Operator.INTERSECT
                || op == Operator.EXCEPT);
            if (!forkable) {
                StreamabilityAnalyzer.ExpressionStreamability left =
                    StreamingClassifier.classify(be.getLeft());
                StreamabilityAnalyzer.ExpressionStreamability right =
                    StreamingClassifier.classify(be.getRight());
                boolean leftConsuming =
                    (left == StreamabilityAnalyzer
                        .ExpressionStreamability.CONSUMING
                    || left == StreamabilityAnalyzer
                        .ExpressionStreamability.FREE_RANGING);
                boolean rightConsuming =
                    (right == StreamabilityAnalyzer
                        .ExpressionStreamability.CONSUMING
                    || right == StreamabilityAnalyzer
                        .ExpressionStreamability.FREE_RANGING);
                if (leftConsuming && rightConsuming) {
                    return true;
                }
            }
            return hasMultipleConsumingOperands(be.getLeft())
                || hasMultipleConsumingOperands(be.getRight());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (hasMultipleConsumingOperands(args.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return hasMultipleConsumingOperands(pe.getFilter())
                || hasMultipleConsumingOperands(pe.getPath());
        }
        return false;
    }

    /**
     * Checks whether an expression captures the context item (.) as
     * a node reference rather than extracting a scalar from it.
     */
    static boolean exprCapturesContextNode(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof SequenceExpr) {
            SequenceExpr se = (SequenceExpr) expr;
            List<Expr> items = se.getItems();
            for (int i = 0; i < items.size(); i++) {
                if (isContextItemExpr(items.get(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            if (isContextItemExpr(ie.getThenExpr())
                    || isContextItemExpr(ie.getElseExpr())) {
                return true;
            }
            return exprCapturesContextNode(ie.getThenExpr())
                || exprCapturesContextNode(ie.getElseExpr());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return exprCapturesContextNode(be.getLeft())
                || exprCapturesContextNode(be.getRight());
        }
        return false;
    }

    /**
     * Checks if an expression uses a descendant or descendant-or-self
     * axis, making it a "crawling" expression.
     */
    static boolean hasCrawlingAxis(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            if (steps != null) {
                for (int i = 0; i < steps.size(); i++) {
                    Step.Axis axis = steps.get(i).getAxis();
                    if (axis == Step.Axis.DESCENDANT
                        || axis == Step.Axis.DESCENDANT_OR_SELF) {
                        return true;
                    }
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return hasCrawlingAxis(pe.getFilter())
                || hasCrawlingAxis(pe.getPath());
        }
        return false;
    }

    static boolean isContextItemExpr(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ContextItemExpr) {
            return true;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            if (!lp.isAbsolute()) {
                List<Step> steps = lp.getSteps();
                if (steps != null && steps.size() == 1) {
                    Step step = steps.get(0);
                    if (step.getAxis() == Step.Axis.SELF) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a variable captures the context item (.) directly
     * as its select expression.
     */
    static boolean variableCapturesContext(VariableNode vn) {
        List<XPathExpression> exprs = vn.getExpressions();
        if (exprs == null || exprs.isEmpty()) {
            return false;
        }
        XPathExpression selExpr = exprs.get(0);
        if (selExpr == null) {
            return false;
        }
        Expr compiled = selExpr.getCompiledExpr();
        return isContextItemExpr(compiled);
    }

    /**
     * Checks if an iterate body navigates downward from any of the
     * given variable names.
     */
    static boolean iterateBodyNavigatesFromVars(
            XSLTNode node, List<String> varNames, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe == null) {
                        continue;
                    }
                    Expr compiled = xpe.getCompiledExpr();
                    if (compiled != null
                            && exprNavigatesFromVars(
                                compiled, varNames)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (iterateBodyNavigatesFromVars(
                            children.get(i), varNames, depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            return iterateBodyNavigatesFromVars(
                ((LiteralResultElement) node).getContent(),
                varNames, depth + 1);
        }
        if (node instanceof CopyNode) {
            return iterateBodyNavigatesFromVars(
                ((CopyNode) node).getContent(),
                varNames, depth + 1);
        }
        if (node instanceof ElementNode) {
            return iterateBodyNavigatesFromVars(
                ((ElementNode) node).getContent(),
                varNames, depth + 1);
        }
        if (node instanceof IfNode) {
            return iterateBodyNavigatesFromVars(
                ((IfNode) node).getContent(),
                varNames, depth + 1);
        }
        return false;
    }

    /**
     * Checks if an expression navigates downward from any of the
     * named variables (e.g. $var/child::*).
     */
    static boolean exprNavigatesFromVars(Expr expr,
                                                  List<String> varNames) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            Expr filter = pe.getFilter();
            if (filter instanceof FilterExpr) {
                Expr primary = ((FilterExpr) filter).getPrimary();
                if (primary instanceof VariableReference) {
                    String vName =
                        ((VariableReference) primary).getLocalName();
                    if (varNames.contains(vName)) {
                        return true;
                    }
                }
            }
            if (filter instanceof VariableReference) {
                String vName =
                    ((VariableReference) filter).getLocalName();
                if (varNames.contains(vName)) {
                    return true;
                }
            }
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return exprNavigatesFromVars(be.getLeft(), varNames)
                || exprNavigatesFromVars(be.getRight(), varNames);
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (exprNavigatesFromVars(args.get(i), varNames)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a pattern uses position() or last() in a predicate.
     */
    static boolean patternUsesPosition(Pattern pat) {
        if (pat == null) {
            return false;
        }
        String patStr = pat.toString();
        return patStr.contains("position()") ||
            patStr.contains("last()");
    }

    /**
     * Checks if the population of a for-each-group is grounded
     * (uses copy-of() or similar).
     */
    static boolean isGroundedPopulation(ForEachGroupNode feg) {
        XPathExpression sel = feg.getSelect();
        if (sel == null) {
            return false;
        }
        String s = sel.getExpressionString();
        return s != null && s.contains("copy-of(");
    }

    /**
     * Checks if any single expression in the body has 2+ current-group()
     * calls where at least one is not grounded (not inside copy-of).
     */
    static boolean hasMultipleCurrentGroupInSameExpr(XSLTNode node,
            int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            for (XPathExpression expr : exprs) {
                if (expr != null) {
                    String s = expr.getExpressionString();
                    if (s != null) {
                        int total = countOccurrences(s,
                            "current-group()");
                        if (total > 1) {
                            if (!allCurrentGroupGrounded(s)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasMultipleCurrentGroupInSameExpr(
                            children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ForEachNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (hasMultipleCurrentGroupInSameExpr(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            return hasMultipleCurrentGroupInSameExpr(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            return hasMultipleCurrentGroupInSameExpr(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof CopyNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return hasMultipleCurrentGroupInSameExpr(
                ((VariableNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks if all current-group() references in an expression are
     * inside a copy-of() call (making them grounded).
     */
    static boolean allCurrentGroupGrounded(String expr) {
        String target = "current-group()";
        int idx = expr.indexOf(target);
        while (idx >= 0) {
            if (!isInsideCopyOf(expr, idx)) {
                return false;
            }
            idx = expr.indexOf(target, idx + target.length());
        }
        return true;
    }

    /**
     * Checks if a position in the expression is inside a copy-of() call.
     */
    static boolean isInsideCopyOf(String expr, int pos) {
        int parenDepth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') {
                parenDepth++;
            } else if (c == '(') {
                if (parenDepth > 0) {
                    parenDepth--;
                } else {
                    String before = expr.substring(
                        Math.max(0, i - 7), i).trim();
                    if (before.endsWith("copy-of")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Counts non-overlapping occurrences of a substring.
     */
    static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = str.indexOf(sub);
        while (idx >= 0) {
            count++;
            idx = str.indexOf(sub, idx + sub.length());
        }
        return count;
    }

    /**
     * Checks if any expression in the subtree references current-group().
     */
    static boolean containsCurrentGroupRef(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    int calls = StreamingClassifier.countFunctionCalls(
                        exprs.get(i), "current-group");
                    if (calls > 0) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
            if (attrs != null) {
                List<XPathExpression> avtExprs =
                    new ArrayList<XPathExpression>();
                for (AttributeValueTemplate avt : attrs.values()) {
                    avt.collectExpressions(avtExprs);
                }
                for (int i = 0; i < avtExprs.size(); i++) {
                    int calls = StreamingClassifier.countFunctionCalls(
                        avtExprs.get(i), "current-group");
                    if (calls > 0) {
                        return true;
                    }
                }
            }
            if (containsCurrentGroupRef(lre.getContent(), depth + 1)) {
                return true;
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children =
                ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (containsCurrentGroupRef(children.get(i),
                            depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ForEachNode) {
            return containsCurrentGroupRef(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            return containsCurrentGroupRef(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (containsCurrentGroupRef(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            return containsCurrentGroupRef(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof CopyNode) {
            return containsCurrentGroupRef(
                ((CopyNode) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return containsCurrentGroupRef(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return containsCurrentGroupRef(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ResultDocumentNode) {
            return containsCurrentGroupRef(
                ((ResultDocumentNode) node).getContent(), depth + 1);
        }
        if (node instanceof SourceDocumentNode) {
            return containsCurrentGroupRef(
                ((SourceDocumentNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks if a for-each-group body has current-group() inside a nested
     * xsl:source-document or xsl:copy instruction (higher-order context).
     */
    static boolean hasCurrentGroupInNestedContext(XSLTNode node,
            int depth) {
        if (node == null || depth > 50) {
            return false;
        }

        if (node instanceof SourceDocumentNode) {
            return containsCurrentGroupRef(
                ((SourceDocumentNode) node).getContent(), 0);
        }

        if (node instanceof CopyNode) {
            CopyNode copy = (CopyNode) node;
            List<XPathExpression> copyExprs = copy.getExpressions();
            boolean hasSelect = !copyExprs.isEmpty();
            if (hasSelect) {
                return containsCurrentGroupRef(copy.getContent(), 0);
            }
            return hasCurrentGroupInNestedContext(
                copy.getContent(), depth + 1);
        }

        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasCurrentGroupInNestedContext(children.get(i),
                            depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ForEachNode) {
            return hasCurrentGroupInNestedContext(
                ((ForEachNode) node).getBody(), depth + 1);
        }
        if (node instanceof IfNode) {
            return hasCurrentGroupInNestedContext(
                ((IfNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (hasCurrentGroupInNestedContext(
                        when.getContent(), depth + 1)) {
                    return true;
                }
            }
            return hasCurrentGroupInNestedContext(
                choose.getOtherwise(), depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            return hasCurrentGroupInNestedContext(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return hasCurrentGroupInNestedContext(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return hasCurrentGroupInNestedContext(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof ResultDocumentNode) {
            XSLTNode rdContent =
                ((ResultDocumentNode) node).getContent();
            return hasCurrentGroupInNestedContext(rdContent, depth + 1);
        }
        return false;
    }

    /**
     * XTSE3430: Validates that calls to user functions with declared
     * streamability within a streaming body have compliant arguments.
     */
    static void validateStreamableFunctionCallSites(XSLTNode node,
            int depth, CompiledStylesheet.Builder builder) throws SAXException {
        if (node == null || depth > 50) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    XPathExpression xpe = exprs.get(i);
                    if (xpe != null && xpe.getCompiledExpr() != null) {
                        checkExprForStreamableFunctionCalls(
                            xpe.getCompiledExpr(), builder);
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    validateStreamableFunctionCallSites(
                        children.get(i), depth + 1, builder);
                }
            }
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            validateStreamableFunctionCallSites(body, depth + 1, builder);
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1, builder);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                validateStreamableFunctionCallSites(when.getContent(), depth + 1, builder);
            }
            validateStreamableFunctionCallSites(choose.getOtherwise(), depth + 1, builder);
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1, builder);
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1, builder);
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content =
                ((LiteralResultElement) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1, builder);
        }
        if (node instanceof VariableNode) {
            XSLTNode content =
                ((VariableNode) node).getContent();
            validateStreamableFunctionCallSites(content, depth + 1, builder);
        }
    }

    /**
     * Walks an expression tree looking for calls to streamable user
     * functions, validating their arguments for streaming compliance.
     */
    static void checkExprForStreamableFunctionCalls(Expr expr,
            CompiledStylesheet.Builder builder) throws SAXException {
        if (expr == null) {
            return;
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String nsUri = fc.getResolvedNamespaceURI();
            String localName = fc.getLocalName();
            if (nsUri != null && !nsUri.isEmpty()) {
                List<Expr> args = fc.getArguments();
                int arity = args.size();
                if (builder.hasUserFunction(nsUri, localName)) {
                    UserFunction uf = findUserFunction(nsUri, localName,
                        arity, builder);
                    if (uf != null && uf.getStreamability() != null &&
                            !"unclassified".equals(uf.getStreamability())) {
                        validateStreamableFunctionArgs(fc, uf);
                    }
                }
            }
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                checkExprForStreamableFunctionCalls(args.get(i), builder);
            }
            return;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            checkExprForStreamableFunctionCalls(be.getLeft(), builder);
            checkExprForStreamableFunctionCalls(be.getRight(), builder);
        } else if (expr instanceof UnaryExpr) {
            checkExprForStreamableFunctionCalls(
                ((UnaryExpr) expr).getOperand(), builder);
        } else if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            checkExprForStreamableFunctionCalls(pe.getFilter(), builder);
            checkExprForStreamableFunctionCalls(pe.getPath(), builder);
        } else if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            checkExprForStreamableFunctionCalls(fe.getPrimary(), builder);
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                checkExprForStreamableFunctionCalls(preds.get(i), builder);
            }
        } else if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            checkExprForStreamableFunctionCalls(ie.getCondition(), builder);
            checkExprForStreamableFunctionCalls(ie.getThenExpr(), builder);
            checkExprForStreamableFunctionCalls(ie.getElseExpr(), builder);
        } else if (expr instanceof SequenceExpr) {
            List<Expr> items = ((SequenceExpr) expr).getItems();
            for (int i = 0; i < items.size(); i++) {
                checkExprForStreamableFunctionCalls(items.get(i), builder);
            }
        } else if (expr instanceof ForExpr) {
            ForExpr fe = (ForExpr) expr;
            List<ForExpr.Binding> bindings = fe.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                checkExprForStreamableFunctionCalls(
                    bindings.get(i).getSequence(), builder);
            }
            checkExprForStreamableFunctionCalls(fe.getReturnExpr(), builder);
        } else if (expr instanceof LetExpr) {
            LetExpr le = (LetExpr) expr;
            List<LetExpr.Binding> bindings = le.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                checkExprForStreamableFunctionCalls(
                    bindings.get(i).getValue(), builder);
            }
            checkExprForStreamableFunctionCalls(le.getReturnExpr(), builder);
        }
    }

    /**
     * Finds a UserFunction by namespace, local name, and arity.
     */
    static UserFunction findUserFunction(String nsUri, String localName,
            int arity, CompiledStylesheet.Builder builder) {
        String key = nsUri + "#" + localName + "#" + arity;
        Map<String, UserFunction> functions = builder.getUserFunctions();
        return functions.get(key);
    }

    /**
     * Validates that the arguments to a streamable function call comply
     * with streaming rules.
     */
    static void validateStreamableFunctionArgs(FunctionCall fc,
            UserFunction uf) throws SAXException {
        List<Expr> args = fc.getArguments();
        String funcName = fc.getPrefix() != null
            ? fc.getPrefix() + ":" + fc.getLocalName()
            : fc.getLocalName();

        if (!args.isEmpty()) {
            Expr firstArg = args.get(0);
            if (containsClimbingAxis(firstArg)) {
                throw new SAXException("XTSE3430: Call to " +
                    "streamable function '" + funcName +
                    "' has a climbing first argument (parent/ancestor " +
                    "axis) in streaming context");
            }
            if (containsCrawlingAxis(firstArg)) {
                throw new SAXException("XTSE3430: Call to " +
                    "streamable function '" + funcName +
                    "' has a crawling argument (descendant axis) " +
                    "in streaming context");
            }
        }

        for (int i = 1; i < args.size(); i++) {
            Expr arg = args.get(i);
            if (isConsumingExpression(arg)) {
                throw new SAXException("XTSE3430: Call to " +
                    "streamable function '" + funcName +
                    "' has a consuming argument in position " + (i + 1) +
                    " in streaming context");
            }
        }
    }

    /**
     * Checks if an expression contains a parent or ancestor axis step.
     */
    static boolean containsClimbingAxis(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.PARENT ||
                        axis == Step.Axis.ANCESTOR ||
                        axis == Step.Axis.ANCESTOR_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            boolean result = containsClimbingAxis(pe.getFilter());
            if (!result) {
                result = containsClimbingAxis(pe.getPath());
            }
            return result;
        }
        return false;
    }

    /**
     * Checks if an expression contains a descendant or descendant-or-self
     * axis step.
     */
    static boolean containsCrawlingAxis(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.DESCENDANT ||
                        axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            boolean result = containsCrawlingAxis(pe.getFilter());
            if (!result) {
                result = containsCrawlingAxis(pe.getPath());
            }
            return result;
        }
        return false;
    }

    /**
     * Checks if an expression is a consuming expression.
     */
    static boolean isConsumingExpression(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step.Axis axis = steps.get(i).getAxis();
                if (axis == Step.Axis.CHILD ||
                        axis == Step.Axis.DESCENDANT ||
                        axis == Step.Axis.DESCENDANT_OR_SELF) {
                    return true;
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            boolean result = isConsumingExpression(pe.getFilter());
            if (!result) {
                result = isConsumingExpression(pe.getPath());
            }
            return result;
        }
        return false;
    }

    /**
     * Counts the total number of current-group() calls in all expressions
     * within a node tree.
     */
    static int countCurrentGroupCalls(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return 0;
        }
        int count = 0;
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    count += StreamingClassifier.countFunctionCalls(
                        exprs.get(i), "current-group");
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    count += countCurrentGroupCalls(children.get(i),
                                                    depth + 1);
                }
            }
        }
        if (node instanceof ForEachNode) {
            count += countCurrentGroupCalls(((ForEachNode) node).getBody(),
                                            depth + 1);
        }
        if (node instanceof IfNode) {
            count += countCurrentGroupCalls(((IfNode) node).getContent(),
                                            depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
            if (attrs != null) {
                List<XPathExpression> avtExprs =
                    new ArrayList<XPathExpression>();
                for (AttributeValueTemplate avt : attrs.values()) {
                    avt.collectExpressions(avtExprs);
                }
                for (int i = 0; i < avtExprs.size(); i++) {
                    count += StreamingClassifier.countFunctionCalls(
                        avtExprs.get(i), "current-group");
                }
            }
            count += countCurrentGroupCalls(lre.getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            count += countCurrentGroupCalls(
                ((ElementNode) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            count += countCurrentGroupCalls(
                ((VariableNode) node).getContent(), depth + 1);
        }
        if (node instanceof TryNode) {
            TryNode tryNode = (TryNode) node;
            count += countCurrentGroupCalls(tryNode.getTryContent(),
                                            depth + 1);
            for (CatchNode catchNode : tryNode.getCatchBlocks()) {
                count += countCurrentGroupCalls(catchNode, depth + 1);
            }
        }
        if (node instanceof CatchNode) {
            count += countCurrentGroupCalls(
                ((CatchNode) node).getContent(), depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                count += countCurrentGroupCalls(when, depth + 1);
            }
            count += countCurrentGroupCalls(choose.getOtherwise(),
                                            depth + 1);
        }
        if (node instanceof WhenNode) {
            count += countCurrentGroupCalls(
                ((WhenNode) node).getContent(), depth + 1);
        }
        return count;
    }

    /**
     * Checks whether the node tree contains consuming expressions that
     * navigate axis steps independently of current-group().
     */
    static boolean hasNonGroupConsuming(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    if (isAxisConsumingWithoutGroup(exprs.get(i))) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasNonGroupConsuming(children.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            LiteralResultElement lre = (LiteralResultElement) node;
            Map<String, AttributeValueTemplate> attrs = lre.getAttributes();
            if (attrs != null) {
                List<XPathExpression> avtExprs =
                    new ArrayList<XPathExpression>();
                for (AttributeValueTemplate avt : attrs.values()) {
                    avt.collectExpressions(avtExprs);
                }
                for (int i = 0; i < avtExprs.size(); i++) {
                    if (isAxisConsumingWithoutGroup(avtExprs.get(i))) {
                        return true;
                    }
                }
            }
            return hasNonGroupConsuming(lre.getContent(), depth + 1);
        }
        if (node instanceof ElementNode) {
            return hasNonGroupConsuming(
                ((ElementNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Returns true if the expression navigates child/descendant axes
     * (consuming) without going through current-group().
     */
    static boolean isAxisConsumingWithoutGroup(XPathExpression expr) {
        int cgCalls = StreamingClassifier.countFunctionCalls(
            expr, "current-group");
        if (cgCalls > 0) {
            return false;
        }
        Expr compiled = expr.getCompiledExpr();
        if (compiled instanceof LocationPath
                || compiled instanceof PathExpr
                || compiled instanceof ContextItemExpr) {
            StreamabilityAnalyzer.ExpressionStreamability es =
                StreamingClassifier.classify(expr);
            return es == StreamabilityAnalyzer.ExpressionStreamability
                    .CONSUMING;
        }
        return false;
    }

    /**
     * Detects expressions like {@code current-group()/(AUTHOR||TITLE)}
     * where the path from current-group() has multiple consuming
     * down-selections.
     */
    static boolean hasMultipleConsumingFromGroup(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    if (hasMultiConsumingGroupPath(
                            exprs.get(i).getCompiledExpr())) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasMultipleConsumingFromGroup(children.get(i),
                                                      depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            return hasMultipleConsumingFromGroup(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks if an expression is a path from current-group() that has
     * multiple consuming operands in its relative path.
     */
    static boolean hasMultiConsumingGroupPath(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            int cgCalls = StreamingClassifier.countFunctionCalls(
                pe.getFilter(), "current-group");
            if (cgCalls > 0) {
                LocationPath path = pe.getPath();
                if (path != null) {
                    List<Step> steps = path.getSteps();
                    for (int i = 0; i < steps.size(); i++) {
                        Step step = steps.get(i);
                        Expr stepExpr = step.getStepExpr();
                        if (stepExpr != null) {
                            int ops = StreamingClassifier
                                .countConsumingOperands(stepExpr);
                            if (ops > 1) {
                                return true;
                            }
                        }
                    }
                    int ops = StreamingClassifier.countConsumingOperands(
                        path);
                    if (ops > 1) {
                        return true;
                    }
                }
            }
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return hasMultiConsumingGroupPath(be.getLeft())
                || hasMultiConsumingGroupPath(be.getRight());
        }
        return false;
    }

    /**
     * Detects current-group() used in a higher-order context.
     */
    static boolean hasCurrentGroupInHigherOrder(XSLTNode node, int depth) {
        if (node == null || depth > 50) {
            return false;
        }

        if (node instanceof ForEachNode) {
            int cgInBody = countCurrentGroupCalls(
                ((ForEachNode) node).getBody(), 0);
            if (cgInBody > 0) {
                return true;
            }
        }

        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            if (exprs != null) {
                for (int i = 0; i < exprs.size(); i++) {
                    if (hasCurrentGroupWithPredicate(
                            exprs.get(i).getCompiledExpr())) {
                        return true;
                    }
                }
            }
        }

        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (hasCurrentGroupInHigherOrder(children.get(i),
                                                     depth + 1)) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof LiteralResultElement) {
            return hasCurrentGroupInHigherOrder(
                ((LiteralResultElement) node).getContent(), depth + 1);
        }
        if (node instanceof VariableNode) {
            return hasCurrentGroupInHigherOrder(
                ((VariableNode) node).getContent(), depth + 1);
        }
        return false;
    }

    /**
     * Checks whether an expression tree contains current-group() used
     * with a predicate, e.g. {@code current-group()[$p]}.
     */
    static boolean hasCurrentGroupWithPredicate(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            Expr primary = fe.getPrimary();
            if (primary instanceof FunctionCall) {
                FunctionCall fc = (FunctionCall) primary;
                if ("current-group".equals(fc.getLocalName())) {
                    List<Expr> predicates = fe.getPredicates();
                    if (predicates != null && !predicates.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            return hasCurrentGroupWithPredicate(pe.getFilter())
                || hasCurrentGroupWithPredicate(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return hasCurrentGroupWithPredicate(be.getLeft())
                || hasCurrentGroupWithPredicate(be.getRight());
        }
        return false;
    }

    /**
     * Checks if an XPath expression string contains a descendant or
     * descendant-or-self axis (indicating a crawling/non-motionless expression).
     */
    static boolean containsDescendantAxis(String expr) {
        if (expr == null) {
            return false;
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int len = expr.length();
        for (int i = 0; i < len; i++) {
            char c = expr.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '/' && i + 1 < len && expr.charAt(i + 1) == '/') {
                    return true;
                }
                if (c == 'd' && expr.startsWith("descendant::", i)) {
                    return true;
                }
                if (c == 'd' && expr.startsWith("descendant-or-self::", i)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * XTSE3430: Validates constraints on a shallow/deep-descent
     * streamable function declaration.
     */
    static void validateDescentFunction(
            List<UserFunction.FunctionParameter> params,
            List<XSLTNode> bodyNodes, String funcName,
            boolean isDeepDescent) throws SAXException {
        UserFunction.FunctionParameter firstParam = params.get(0);
        String firstParamType = firstParam.getAsType();
        if (!isSingleNodeType(firstParamType)) {
            throw new SAXException("XTSE3430: First parameter of " +
                "shallow-descent function '" + funcName + "' must be " +
                "declared as a single node type (e.g. node(), element())");
        }

        for (XSLTNode node : bodyNodes) {
            validateDescentFunctionBody(node, funcName,
                firstParam.getName(), 0, isDeepDescent);
        }
    }

    /**
     * Recursively checks a shallow-descent function body for streaming
     * violations.
     */
    static void validateDescentFunctionBody(XSLTNode node, String funcName,
            String firstParamName, int depth,
            boolean isDeepDescent) throws SAXException {
        if (node == null || depth > 30) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            for (XPathExpression expr : exprs) {
                String exprStr = expr.toString();

                if (!isDeepDescent && containsDescendantAxis(exprStr)) {
                    throw new SAXException("XTSE3430: Body of " +
                        "shallow-descent function '" + funcName +
                        "' uses descendant axis (crawling expression)");
                }

                if (returnsAtomicValue(exprStr, firstParamName)) {
                    throw new SAXException("XTSE3430: Body of " +
                        "shallow-descent function '" + funcName +
                        "' returns an atomic value (non-striding result)");
                }

                if (hasNonConsumingAccess(exprStr, firstParamName)) {
                    throw new SAXException("XTSE3430: Body of " +
                        "shallow-descent function '" + funcName +
                        "' has a non-consuming access pattern");
                }
            }
        }
        if (node instanceof SequenceNode) {
            for (XSLTNode child : ((SequenceNode) node).getChildren()) {
                validateDescentFunctionBody(child, funcName,
                    firstParamName, depth + 1, isDeepDescent);
            }
        }
    }

    /**
     * XTSE3430: Validates constraints on an absorbing streamable function.
     */
    static void validateAbsorbingFunction(
            List<UserFunction.FunctionParameter> params,
            List<XSLTNode> bodyNodes, String funcName) throws SAXException {
        UserFunction.FunctionParameter firstParam = params.get(0);
        String paramName = firstParam.getName();
        String paramType = firstParam.getAsType();

        List<String> exprStrings = new ArrayList<String>();
        collectExpressionStrings(bodyNodes, exprStrings, 0);

        for (String exprStr : exprStrings) {
            if (containsPathFunctionCall(exprStr)) {
                throw new SAXException("XTSE3430: Body of absorbing " +
                    "function '" + funcName + "' uses the path() " +
                    "function which requires ancestor access");
            }
        }

        if (hasNonGroundedSequenceReturn(bodyNodes, paramName, 0)) {
            throw new SAXException("XTSE3430: Body of absorbing " +
                "function '" + funcName + "' returns parameter '$" +
                paramName + "' directly (non-grounded return)");
        }

        boolean isSequenceParam = paramType != null &&
            (paramType.trim().endsWith("*") || paramType.trim().endsWith("+"));
        if (isSequenceParam) {
            int totalRefs = 0;
            for (String exprStr : exprStrings) {
                totalRefs += countParamReferences(exprStr, paramName);
            }
            if (totalRefs > 1) {
                throw new SAXException("XTSE3430: Body of absorbing " +
                    "function '" + funcName + "' has multiple " +
                    "references to sequence parameter '$" + paramName +
                    "' (stream cannot be rewound)");
            }

            for (String exprStr : exprStrings) {
                if (hasLoopBasedPredicateAccess(exprStr, paramName)) {
                    throw new SAXException("XTSE3430: Body of absorbing " +
                        "function '" + funcName + "' has a consuming " +
                        "reference to parameter '$" + paramName +
                        "' in a loop");
                }
            }
        }
    }

    /**
     * XTSE3430: Validates constraints on ascent, inspection, and filter
     * streamable functions.
     */
    static void validateMotionlessFunction(
            List<UserFunction.FunctionParameter> params,
            List<XSLTNode> bodyNodes, String funcName,
            String streamability) throws SAXException {
        UserFunction.FunctionParameter firstParam = params.get(0);
        String paramName = firstParam.getName();
        String paramType = firstParam.getAsType();

        if (paramType != null) {
            String t = paramType.trim();
            if (t.endsWith("*") || t.endsWith("+")) {
                throw new SAXException("XTSE3430: First parameter of " +
                    streamability + " function '" + funcName +
                    "' must not be a sequence type (found '" +
                    paramType + "')");
            }
        }

        List<String> exprStrings = new ArrayList<String>();
        collectExpressionStrings(bodyNodes, exprStrings, 0);

        for (String exprStr : exprStrings) {
            if (bodyConsumesParam(exprStr, paramName)) {
                throw new SAXException("XTSE3430: Body of " +
                    streamability + " function '" + funcName +
                    "' consumes parameter '$" + paramName + "'");
            }
        }

        if ("filter".equals(streamability)) {
            for (String exprStr : exprStrings) {
                if (hasClimbingFromParam(exprStr, paramName)) {
                    throw new SAXException("XTSE3430: Body of " +
                        streamability + " function '" + funcName +
                        "' uses climbing axis from parameter '$" +
                        paramName + "'");
                }
            }
        }
    }

    /**
     * XTSE3430: Validates that an ascent or inspection function does not
     * return the streaming parameter node directly when the body lacks
     * the appropriate navigation level. For inspection functions, bare
     * param return is always invalid. For ascent functions, bare param
     * return is invalid only when the body lacks ascent-level navigation
     * (ancestor/parent axis) from the parameter.
     */
    static void validateFunctionResultGrounded(
            List<UserFunction.FunctionParameter> params,
            List<XSLTNode> bodyNodes, String funcName,
            String streamability) throws SAXException {
        UserFunction.FunctionParameter firstParam = params.get(0);
        String paramName = firstParam.getName();

        List<String> exprStrings = new ArrayList<String>();
        collectExpressionStrings(bodyNodes, exprStrings, 0);

        boolean hasBareReturn = false;
        for (int i = 0; i < exprStrings.size(); i++) {
            String exprStr = exprStrings.get(i);
            if (bodyReturnsBareParam(exprStr, paramName)) {
                hasBareReturn = true;
                break;
            }
        }
        if (!hasBareReturn) {
            return;
        }

        if ("inspection".equals(streamability)) {
            throw new SAXException("XTSE3430: Body of " +
                streamability + " function '" + funcName +
                "' can return streaming parameter '$" + paramName +
                "' directly (result must be grounded)");
        }

        if ("ascent".equals(streamability)) {
            boolean hasAscentNav = false;
            for (int i = 0; i < exprStrings.size(); i++) {
                String exprStr = exprStrings.get(i);
                if (bodyHasAscentNavigation(exprStr, paramName)) {
                    hasAscentNav = true;
                    break;
                }
            }
            if (!hasAscentNav) {
                throw new SAXException("XTSE3430: Body of " +
                    streamability + " function '" + funcName +
                    "' can return streaming parameter '$" + paramName +
                    "' directly without ascent navigation");
            }
        }
    }

    /**
     * Returns true if the expression contains ascent-level navigation
     * from the parameter (ancestor, parent, or ancestor-or-self axis).
     */
    static boolean bodyHasAscentNavigation(String expr, String paramName) {
        String varRef = "$" + paramName;
        int idx = expr.indexOf(varRef);
        while (idx >= 0) {
            int afterRef = idx + varRef.length();
            if (afterRef < expr.length()) {
                char next = expr.charAt(afterRef);
                if (Character.isLetterOrDigit(next) || next == '-'
                        || next == '_') {
                    idx = expr.indexOf(varRef, afterRef);
                    continue;
                }
            }
            if (afterRef < expr.length()
                    && expr.charAt(afterRef) == '/') {
                String rest = expr.substring(afterRef + 1);
                if (rest.startsWith("ancestor::")
                        || rest.startsWith("ancestor-or-self::")
                        || rest.startsWith("parent::")
                        || rest.startsWith("..")) {
                    return true;
                }
            }
            idx = expr.indexOf(varRef, afterRef);
        }
        return false;
    }

    /**
     * Returns true if the expression contains a bare reference to the
     * streaming parameter that could be returned as the function result
     * (not followed by a navigation step, operator, or keyword, and not
     * inside a function call).
     */
    static boolean bodyReturnsBareParam(String expr, String paramName) {
        String varRef = "$" + paramName;
        int idx = expr.indexOf(varRef);
        while (idx >= 0) {
            int afterRef = idx + varRef.length();
            if (afterRef < expr.length()) {
                char next = expr.charAt(afterRef);
                if (Character.isLetterOrDigit(next) || next == '-'
                        || next == '_') {
                    idx = expr.indexOf(varRef, afterRef);
                    continue;
                }
            }
            if (afterRef < expr.length()) {
                char next = expr.charAt(afterRef);
                if (next == '/') {
                    idx = expr.indexOf(varRef, afterRef);
                    continue;
                }
            }
            if (followedByOperatorOrKeyword(expr, afterRef)) {
                idx = expr.indexOf(varRef, afterRef);
                continue;
            }
            boolean isFunctionArg = false;
            if (idx > 0) {
                int scanBack = idx - 1;
                while (scanBack >= 0 && expr.charAt(scanBack) == ' ') {
                    scanBack--;
                }
                if (scanBack >= 0) {
                    char prevChar = expr.charAt(scanBack);
                    if (prevChar == ',') {
                        isFunctionArg = true;
                    } else if (prevChar == '(') {
                        int nameEnd = scanBack - 1;
                        while (nameEnd >= 0
                                && expr.charAt(nameEnd) == ' ') {
                            nameEnd--;
                        }
                        if (nameEnd >= 0) {
                            char nc = expr.charAt(nameEnd);
                            if (Character.isLetterOrDigit(nc)
                                    || nc == '-' || nc == '_'
                                    || nc == ':') {
                                isFunctionArg = true;
                            }
                        }
                    }
                }
            }
            if (!isFunctionArg) {
                return true;
            }
            idx = expr.indexOf(varRef, afterRef);
        }
        return false;
    }

    private static final String[] XPATH_KEYWORDS = {
        "instance ", "treat ", "cast ", "castable ",
        "and ", "and)", "or ", "or)",
        "eq ", "ne ", "lt ", "gt ", "le ", "ge ", "is ",
        "div ", "mod ",
        "union ", "intersect ", "except "
    };

    /**
     * Returns true if the expression at the given position is followed
     * (after optional whitespace) by an XPath operator or keyword,
     * meaning the variable reference is part of a larger expression.
     */
    private static boolean followedByOperatorOrKeyword(String expr,
            int pos) {
        int scan = pos;
        while (scan < expr.length() && expr.charAt(scan) == ' ') {
            scan++;
        }
        if (scan >= expr.length()) {
            return false;
        }
        char nc = expr.charAt(scan);
        if (nc == '=' || nc == '!' || nc == '<' || nc == '>'
                || nc == '+' || nc == '-' || nc == '*' || nc == '|'
                || nc == '[') {
            return true;
        }
        String rest = expr.substring(scan);
        for (int i = 0; i < XPATH_KEYWORDS.length; i++) {
            if (rest.startsWith(XPATH_KEYWORDS[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the expression consumes the first parameter's
     * string/text value (non-motionless access).
     */
    static boolean bodyConsumesParam(String expr, String paramName) {
        String varRef = "$" + paramName;
        int idx = expr.indexOf(varRef);
        while (idx >= 0) {
            int afterRef = idx + varRef.length();
            if (afterRef < expr.length()) {
                char next = expr.charAt(afterRef);
                if (Character.isLetterOrDigit(next) || next == '-'
                        || next == '_') {
                    idx = expr.indexOf(varRef, afterRef);
                    continue;
                }
            }
            if (idx >= 5) {
                String before = expr.substring(0, idx).trim();
                boolean isConsumingFn = before.endsWith("string(")
                        || before.endsWith("data(")
                        || before.endsWith("string-join(")
                        || before.endsWith("normalize-space(");
                if (isConsumingFn) {
                    boolean directRef = true;
                    if (afterRef < expr.length()) {
                        char nc = expr.charAt(afterRef);
                        if (nc == '/' || nc == '@') {
                            directRef = false;
                        }
                    }
                    if (directRef) {
                        return true;
                    }
                }
            }
            if (afterRef < expr.length()
                    && expr.charAt(afterRef) == '[') {
                String predicate = expr.substring(afterRef);
                int closeIdx = predicate.indexOf(']');
                if (closeIdx > 0) {
                    String predBody = predicate.substring(1, closeIdx)
                        .trim();
                    if (predBody.startsWith(". ") ||
                        predBody.startsWith(".(") ||
                        predBody.equals(".")) {
                        return true;
                    }
                }
            }
            idx = expr.indexOf(varRef, afterRef);
        }
        return false;
    }

    /**
     * Returns true if the expression navigates from the first parameter
     * using a climbing axis (parent, ancestor).
     */
    static boolean hasClimbingFromParam(String expr, String paramName) {
        String varRef = "$" + paramName;
        int idx = expr.indexOf(varRef);
        while (idx >= 0) {
            int afterRef = idx + varRef.length();
            if (afterRef < expr.length()) {
                char next = expr.charAt(afterRef);
                if (Character.isLetterOrDigit(next) || next == '-'
                        || next == '_') {
                    idx = expr.indexOf(varRef, afterRef);
                    continue;
                }
            }
            if (afterRef < expr.length()
                    && expr.charAt(afterRef) == '/') {
                String rest = expr.substring(afterRef + 1).trim();
                if (rest.startsWith("..") || rest.startsWith("parent::")
                        || rest.startsWith("ancestor::")) {
                    return true;
                }
            }
            idx = expr.indexOf(varRef, afterRef);
        }
        return false;
    }

    /**
     * Collects all expression strings from a list of XSLT nodes.
     */
    static void collectExpressionStrings(List<XSLTNode> nodes,
            List<String> result, int depth) {
        if (depth > 30) {
            return;
        }
        for (XSLTNode node : nodes) {
            collectNodeExpressionStrings(node, result, depth);
        }
    }

    /**
     * Collects expression strings from a single XSLT node and its
     * children.
     */
    static void collectNodeExpressionStrings(XSLTNode node,
            List<String> result, int depth) {
        if (node == null || depth > 30) {
            return;
        }
        if (node instanceof ExpressionHolder) {
            List<XPathExpression> exprs =
                ((ExpressionHolder) node).getExpressions();
            for (XPathExpression expr : exprs) {
                if (expr != null) {
                    result.add(expr.getExpressionString());
                }
            }
        }
        if (node instanceof SequenceNode) {
            List<XSLTNode> children = ((SequenceNode) node).getChildren();
            for (int i = 0; i < children.size(); i++) {
                collectNodeExpressionStrings(children.get(i), result,
                    depth + 1);
            }
        }
        if (node instanceof CopyNode) {
            XSLTNode content =
                ((CopyNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof ElementNode) {
            XSLTNode content =
                ((ElementNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof LiteralResultElement) {
            XSLTNode content =
                ((LiteralResultElement) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof ForEachNode) {
            XSLTNode body =
                ((ForEachNode) node).getBody();
            collectNodeExpressionStrings(body, result, depth + 1);
        }
        if (node instanceof IfNode) {
            XSLTNode content =
                ((IfNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                collectNodeExpressionStrings(when.getContent(), result,
                    depth + 1);
            }
            collectNodeExpressionStrings(choose.getOtherwise(), result,
                depth + 1);
        }
        if (node instanceof VariableNode) {
            XSLTNode content =
                ((VariableNode) node).getContent();
            collectNodeExpressionStrings(content, result, depth + 1);
        }
    }

    /**
     * Checks if an expression string calls the path() function.
     */
    static boolean containsPathFunctionCall(String expr) {
        if (expr == null) {
            return false;
        }
        int idx = expr.indexOf("path(");
        while (idx >= 0) {
            if (idx == 0 || !Character.isLetterOrDigit(expr.charAt(idx - 1))) {
                return true;
            }
            idx = expr.indexOf("path(", idx + 1);
        }
        return false;
    }

    /**
     * Checks the function body for xsl:sequence instructions that can
     * return the streaming parameter node directly (non-grounded).
     */
    static boolean hasNonGroundedSequenceReturn(List<XSLTNode> nodes,
            String paramName, int depth) {
        if (depth > 30) {
            return false;
        }
        for (XSLTNode node : nodes) {
            if (node instanceof SequenceOutputNode) {
                List<XPathExpression> exprs =
                    ((ExpressionHolder) node).getExpressions();
                for (XPathExpression expr : exprs) {
                    if (expr != null) {
                        String exprStr = expr.getExpressionString();
                        if (hasNonGroundedReturn(exprStr, paramName)) {
                            return true;
                        }
                    }
                }
            }
            if (node instanceof SequenceNode) {
                boolean result = hasNonGroundedSequenceReturn(
                    ((SequenceNode) node).getChildren(), paramName,
                    depth + 1);
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if an expression string can return the parameter directly.
     */
    static boolean hasNonGroundedReturn(String expr, String paramName) {
        String paramRef = "$" + paramName;
        String trimmed = expr.trim();
        while (trimmed.endsWith(")")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.endsWith("else " + paramRef)) {
            return true;
        }
        if (trimmed.equals(paramRef)) {
            return true;
        }
        return false;
    }

    /**
     * Counts the number of references to a parameter in an expression.
     */
    static int countParamReferences(String expr, String paramName) {
        String paramRef = "$" + paramName;
        int count = 0;
        int idx = expr.indexOf(paramRef);
        while (idx >= 0) {
            int afterIdx = idx + paramRef.length();
            if (afterIdx >= expr.length() ||
                    (!Character.isLetterOrDigit(expr.charAt(afterIdx)) &&
                     expr.charAt(afterIdx) != '_' &&
                     expr.charAt(afterIdx) != '-')) {
                count++;
            }
            idx = expr.indexOf(paramRef, idx + 1);
        }
        return count;
    }

    /**
     * Checks if the parameter is accessed with a variable-based predicate
     * inside a loop construct.
     */
    static boolean hasLoopBasedPredicateAccess(String expr, String paramName) {
        String paramRef = "$" + paramName;
        boolean hasForLoop = expr.contains("for $") && expr.contains("return");
        if (!hasForLoop) {
            return false;
        }
        int idx = expr.indexOf(paramRef);
        while (idx >= 0) {
            int afterIdx = idx + paramRef.length();
            if (afterIdx < expr.length() && expr.charAt(afterIdx) == '[') {
                int closeBracket = expr.indexOf(']', afterIdx);
                if (closeBracket > afterIdx) {
                    String predicate = expr.substring(afterIdx + 1, closeBracket);
                    if (predicate.contains("$")) {
                        return true;
                    }
                }
            }
            idx = expr.indexOf(paramRef, idx + 1);
        }
        return false;
    }

    /**
     * Returns true if the type string represents a single node type.
     */
    static boolean isSingleNodeType(String typeStr) {
        if (typeStr == null) {
            return false;
        }
        String t = typeStr.trim();
        if (t.endsWith("*") || t.endsWith("+") || t.endsWith("?")) {
            return false;
        }
        return t.startsWith("node(") || t.startsWith("element(") ||
            t.startsWith("document-node(") || t.startsWith("text(") ||
            t.startsWith("comment(") || t.startsWith("attribute(") ||
            t.startsWith("processing-instruction(") ||
            t.startsWith("namespace-node(");
    }

    /**
     * Heuristic check for expressions that return atomic values rather
     * than nodes.
     */
    static boolean returnsAtomicValue(String expr, String paramName) {
        String paramRef = "$" + paramName;
        String[] atomicFunctions = {
            "sum(", "count(", "avg(", "min(", "max(",
            "string-length(", "number("
        };
        for (String func : atomicFunctions) {
            String pattern = func + paramRef;
            int idx = expr.indexOf(pattern);
            if (idx >= 0) {
                int afterIdx = idx + pattern.length();
                if (afterIdx >= expr.length()) {
                    return true;
                }
                char ch = expr.charAt(afterIdx);
                if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if an expression contains a non-consuming access pattern.
     */
    static boolean hasNonConsumingAccess(String expr, String paramName) {
        String paramRef = "$" + paramName;
        int bangIdx = expr.indexOf('!');
        if (bangIdx < 0) {
            return false;
        }
        String rightSide = expr.substring(bangIdx + 1).trim();
        if (rightSide.startsWith(paramRef)) {
            return true;
        }
        return false;
    }
}
