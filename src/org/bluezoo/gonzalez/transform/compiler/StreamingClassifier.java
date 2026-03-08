/*
 * StreamingClassifier.java
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

import java.util.List;

import org.bluezoo.gonzalez.transform.compiler.StreamabilityAnalyzer.ExpressionStreamability;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.AccoladeArrayConstructorExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ArrayConstructorExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ContextItemExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.DynamicFunctionCallExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FilterExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.ForExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.IfExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.InlineFunctionExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.LetExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.LookupExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.MapConstructorExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.PathExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.QuantifiedExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.SequenceExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.TypeExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.VariableReference;

/**
 * Classifies XPath expressions and XSLT match patterns for streaming
 * compatibility by walking the AST.
 *
 * <p>This replaces the string-based heuristics in
 * {@link StreamabilityAnalyzer} with proper AST-based analysis.
 * The classifier determines how an expression interacts with the
 * streaming context:
 *
 * <ul>
 *   <li><b>MOTIONLESS</b> - literals, variables, constant functions</li>
 *   <li><b>CONSUMING</b> - forward-axis navigation only</li>
 *   <li><b>GROUNDED</b> - needs current subtree (reverse axes, last())</li>
 *   <li><b>FREE_RANGING</b> - needs full document (preceding::, key())</li>
 * </ul>
 *
 * <p>For compound expressions the classification is the maximum
 * (most restrictive) of all sub-expressions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class StreamingClassifier {

    private StreamingClassifier() {
    }

    /**
     * Classifies a compiled XPath expression.
     *
     * @param xpathExpr the compiled expression (may be null)
     * @return the streamability category
     */
    public static ExpressionStreamability classify(XPathExpression xpathExpr) {
        if (xpathExpr == null) {
            return ExpressionStreamability.MOTIONLESS;
        }
        return classify(xpathExpr.getCompiledExpr());
    }

    /**
     * Classifies an expression AST node.
     *
     * @param expr the expression (may be null)
     * @return the streamability category
     */
    public static ExpressionStreamability classify(Expr expr) {
        if (expr == null) {
            return ExpressionStreamability.MOTIONLESS;
        }

        if (expr instanceof Literal) {
            return ExpressionStreamability.MOTIONLESS;
        }
        if (expr instanceof VariableReference) {
            return ExpressionStreamability.MOTIONLESS;
        }
        if (expr instanceof ContextItemExpr) {
            return ExpressionStreamability.CONSUMING;
        }
        if (expr instanceof LocationPath) {
            return classifyLocationPath((LocationPath) expr);
        }
        if (expr instanceof PathExpr) {
            return classifyPathExpr((PathExpr) expr);
        }
        if (expr instanceof FilterExpr) {
            return classifyFilterExpr((FilterExpr) expr);
        }
        if (expr instanceof FunctionCall) {
            return classifyFunctionCall((FunctionCall) expr);
        }
        if (expr instanceof BinaryExpr) {
            return classifyBinaryExpr((BinaryExpr) expr);
        }
        if (expr instanceof UnaryExpr) {
            return classify(((UnaryExpr) expr).getOperand());
        }
        if (expr instanceof IfExpr) {
            return classifyIfExpr((IfExpr) expr);
        }
        if (expr instanceof ForExpr) {
            return classifyForExpr((ForExpr) expr);
        }
        if (expr instanceof LetExpr) {
            return classifyLetExpr((LetExpr) expr);
        }
        if (expr instanceof QuantifiedExpr) {
            return classifyQuantifiedExpr((QuantifiedExpr) expr);
        }
        if (expr instanceof SequenceExpr) {
            return classifySequenceExpr((SequenceExpr) expr);
        }
        if (expr instanceof TypeExpr) {
            return classify(((TypeExpr) expr).getOperand());
        }
        if (expr instanceof LookupExpr) {
            return classify(((LookupExpr) expr).getBase());
        }
        if (expr instanceof DynamicFunctionCallExpr) {
            return classifyDynamicCall((DynamicFunctionCallExpr) expr);
        }
        if (expr instanceof InlineFunctionExpr) {
            return classify(((InlineFunctionExpr) expr).getBody());
        }
        if (expr instanceof MapConstructorExpr) {
            return classifyMapConstructor((MapConstructorExpr) expr);
        }
        if (expr instanceof ArrayConstructorExpr) {
            return classifyList(((ArrayConstructorExpr) expr).getMemberExprs());
        }
        if (expr instanceof AccoladeArrayConstructorExpr) {
            return classify(
                ((AccoladeArrayConstructorExpr) expr).getBodyExpr());
        }

        // Unknown expression type — conservative default
        return ExpressionStreamability.CONSUMING;
    }

    /**
     * Classifies a match pattern for streaming compatibility.
     * Examines the pattern's axis references via the PatternStep
     * structures already built by {@link PatternParser}.
     *
     * @param pattern the match pattern (may be null)
     * @return the streamability category
     */
    public static ExpressionStreamability classifyPattern(Pattern pattern) {
        if (pattern == null) {
            return ExpressionStreamability.MOTIONLESS;
        }

        if (pattern instanceof PathPattern) {
            return classifyPathPattern((PathPattern) pattern);
        }
        if (pattern instanceof UnionPattern) {
            return classifyUnionPattern((UnionPattern) pattern);
        }
        if (pattern instanceof IntersectPattern) {
            IntersectPattern ip = (IntersectPattern) pattern;
            ExpressionStreamability left = classifyPattern(ip.getLeft());
            ExpressionStreamability right = classifyPattern(ip.getRight());
            return combine(left, right);
        }
        if (pattern instanceof ExceptPattern) {
            ExceptPattern ep = (ExceptPattern) pattern;
            ExpressionStreamability left = classifyPattern(ep.getLeft());
            ExpressionStreamability right = classifyPattern(ep.getRight());
            return combine(left, right);
        }
        if (pattern instanceof PredicatedPattern) {
            return classifyPattern(((PredicatedPattern) pattern).getInner());
        }
        if (pattern instanceof NameTestPattern) {
            return classifyNameTestPattern((NameTestPattern) pattern);
        }
        if (pattern instanceof IdPattern || pattern instanceof KeyPattern) {
            return ExpressionStreamability.FREE_RANGING;
        }

        return ExpressionStreamability.CONSUMING;
    }

    // ---- Location path classification ----

    private static ExpressionStreamability classifyLocationPath(
            LocationPath path) {
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        List<Step> steps = path.getSteps();

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            ExpressionStreamability stepClass = classifyStep(step);
            result = combine(result, stepClass);
            if (result == ExpressionStreamability.FREE_RANGING) {
                return result;
            }
        }

        return result;
    }

    private static ExpressionStreamability classifyStep(Step step) {
        Step.Axis axis = step.getAxis();
        ExpressionStreamability axisClass = classifyAxis(axis);

        // XSLT 3.0 streaming: a predicate containing last() on a
        // forward-axis step requires the total count of items, which
        // means the entire input must be buffered (FREE_RANGING).
        boolean forwardAxis = isForwardAxis(axis);
        List<Expr> predicates = step.getPredicates();
        if (predicates != null) {
            for (int i = 0; i < predicates.size(); i++) {
                Expr pred = predicates.get(i);
                ExpressionStreamability predClass = classify(pred);
                if (forwardAxis && containsLast(pred)) {
                    return ExpressionStreamability.FREE_RANGING;
                }
                axisClass = combine(axisClass, predClass);
            }
        }

        // Step expression (XPath 3.0 node test with expression)
        Expr stepExpr = step.getStepExpr();
        if (stepExpr != null) {
            axisClass = combine(axisClass, classify(stepExpr));
        }

        return axisClass;
    }

    private static boolean isForwardAxis(Step.Axis axis) {
        switch (axis) {
            case CHILD:
            case DESCENDANT:
            case DESCENDANT_OR_SELF:
            case FOLLOWING_SIBLING:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks whether an expression tree contains a call to last().
     * Used to detect predicates that require the total item count.
     */
    private static boolean containsLast(Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String name = fc.getLocalName();
            String ns = fc.getResolvedNamespaceURI();
            boolean core = (ns == null || ns.isEmpty() ||
                "http://www.w3.org/2005/xpath-functions".equals(ns));
            if (core && "last".equals(name)) {
                return true;
            }
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (containsLast(args.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return containsLast(be.getLeft()) ||
                   containsLast(be.getRight());
        }
        if (expr instanceof UnaryExpr) {
            return containsLast(((UnaryExpr) expr).getOperand());
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            if (containsLast(fe.getPrimary())) {
                return true;
            }
            List<Expr> preds = fe.getPredicates();
            for (int i = 0; i < preds.size(); i++) {
                if (containsLast(preds.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            return containsLast(ie.getCondition()) ||
                   containsLast(ie.getThenExpr()) ||
                   containsLast(ie.getElseExpr());
        }
        return false;
    }

    private static ExpressionStreamability classifyAxis(Step.Axis axis) {
        switch (axis) {
            case SELF:
            case ATTRIBUTE:
            case NAMESPACE:
                return ExpressionStreamability.MOTIONLESS;

            case CHILD:
            case DESCENDANT:
            case DESCENDANT_OR_SELF:
            case FOLLOWING_SIBLING:
                return ExpressionStreamability.CONSUMING;

            case PARENT:
            case ANCESTOR:
            case ANCESTOR_OR_SELF:
            case PRECEDING_SIBLING:
                return ExpressionStreamability.GROUNDED;

            case PRECEDING:
            case FOLLOWING:
                return ExpressionStreamability.FREE_RANGING;

            default:
                return ExpressionStreamability.CONSUMING;
        }
    }

    // ---- Path expression: filter/path ----

    private static ExpressionStreamability classifyPathExpr(PathExpr pe) {
        ExpressionStreamability filterClass = classify(pe.getFilter());
        ExpressionStreamability pathClass =
            classifyLocationPath(pe.getPath());
        return combine(filterClass, pathClass);
    }

    // ---- Filter expression: primary[pred] ----

    private static ExpressionStreamability classifyFilterExpr(FilterExpr fe) {
        ExpressionStreamability result = classify(fe.getPrimary());
        List<Expr> preds = fe.getPredicates();
        for (int i = 0; i < preds.size(); i++) {
            result = combine(result, classify(preds.get(i)));
        }
        return result;
    }

    // ---- Function calls ----

    private static ExpressionStreamability classifyFunctionCall(
            FunctionCall fc) {
        String name = fc.getLocalName();
        String nsUri = fc.getResolvedNamespaceURI();

        // Functions that require full document
        boolean isCoreFunction = (nsUri == null || nsUri.isEmpty() ||
            "http://www.w3.org/2005/xpath-functions".equals(nsUri));

        if (isCoreFunction) {
            if ("key".equals(name) ||
                "id".equals(name) || "idref".equals(name) ||
                "element-with-id".equals(name)) {
                // 3-arg form key(name, value, doc): if the document
                // argument is motionless (external document), classify
                // based on the first two arguments only — the lookup
                // is against the external document, not the stream.
                List<Expr> keyArgs = fc.getArguments();
                if (keyArgs != null && keyArgs.size() >= 3) {
                    ExpressionStreamability docArg =
                        classify(keyArgs.get(keyArgs.size() - 1));
                    if (docArg == ExpressionStreamability.MOTIONLESS) {
                        ExpressionStreamability kr =
                            ExpressionStreamability.MOTIONLESS;
                        for (int ki = 0; ki < keyArgs.size() - 1; ki++) {
                            kr = combine(kr, classify(keyArgs.get(ki)));
                        }
                        return kr;
                    }
                }
                return ExpressionStreamability.FREE_RANGING;
            }

            // doc(), document(), collection(), unparsed-text() access
            // external resources. When arguments are motionless (e.g.
            // literal URI), the call is motionless since it does not
            // depend on the streaming context.
            if ("doc".equals(name) || "document".equals(name) ||
                "collection".equals(name) ||
                "unparsed-text".equals(name)) {
                ExpressionStreamability argClass =
                    ExpressionStreamability.MOTIONLESS;
                List<Expr> args = fc.getArguments();
                for (int i = 0; i < args.size(); i++) {
                    argClass = combine(argClass, classify(args.get(i)));
                }
                return argClass;
            }

            // last() requires grounding (sibling count)
            if ("last".equals(name)) {
                return ExpressionStreamability.GROUNDED;
            }

            // position() is motionless (returns focus position, no node navigation)
            if ("position".equals(name)) {
                return ExpressionStreamability.MOTIONLESS;
            }

            // last() requires knowing context size (all siblings must be
            // read), making it at least grounded.
            if ("last".equals(name)) {
                return ExpressionStreamability.GROUNDED;
            }

            // root() is grounded (needs access to root)
            if ("root".equals(name)) {
                return ExpressionStreamability.GROUNDED;
            }

            // current-group(), current-grouping-key() are consuming
            if ("current-group".equals(name) ||
                "current-grouping-key".equals(name)) {
                return ExpressionStreamability.CONSUMING;
            }
        }

        // Classify based on arguments
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        List<Expr> args = fc.getArguments();
        for (int i = 0; i < args.size(); i++) {
            result = combine(result, classify(args.get(i)));
        }
        return result;
    }

    // ---- Binary expressions ----

    private static ExpressionStreamability classifyBinaryExpr(BinaryExpr be) {
        ExpressionStreamability left = classify(be.getLeft());
        ExpressionStreamability right = classify(be.getRight());
        return combine(left, right);
    }

    // ---- Control flow ----

    private static ExpressionStreamability classifyIfExpr(IfExpr ie) {
        ExpressionStreamability c = classify(ie.getCondition());
        ExpressionStreamability t = classify(ie.getThenExpr());
        ExpressionStreamability e = classify(ie.getElseExpr());
        return combine(combine(c, t), e);
    }

    private static ExpressionStreamability classifyForExpr(ForExpr fe) {
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        List<ForExpr.Binding> bindings = fe.getBindings();
        for (int i = 0; i < bindings.size(); i++) {
            result = combine(result, classify(bindings.get(i).getSequence()));
        }
        result = combine(result, classify(fe.getReturnExpr()));
        return result;
    }

    private static ExpressionStreamability classifyLetExpr(LetExpr le) {
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        List<LetExpr.Binding> bindings = le.getBindings();
        for (int i = 0; i < bindings.size(); i++) {
            result = combine(result, classify(bindings.get(i).getValue()));
        }
        result = combine(result, classify(le.getReturnExpr()));
        return result;
    }

    private static ExpressionStreamability classifyQuantifiedExpr(
            QuantifiedExpr qe) {
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        List<QuantifiedExpr.Binding> bindings = qe.getBindings();
        for (int i = 0; i < bindings.size(); i++) {
            result = combine(result, classify(bindings.get(i).getSequence()));
        }
        result = combine(result, classify(qe.getSatisfiesExpr()));
        return result;
    }

    // ---- Sequence / collection expressions ----

    private static ExpressionStreamability classifySequenceExpr(
            SequenceExpr se) {
        return classifyList(se.getItems());
    }

    private static ExpressionStreamability classifyDynamicCall(
            DynamicFunctionCallExpr dfc) {
        ExpressionStreamability result = classify(dfc.getBase());
        List<Expr> args = dfc.getArguments();
        for (int i = 0; i < args.size(); i++) {
            result = combine(result, classify(args.get(i)));
        }
        return result;
    }

    private static ExpressionStreamability classifyMapConstructor(
            MapConstructorExpr mc) {
        ExpressionStreamability result = classifyList(mc.getKeyExprs());
        result = combine(result, classifyList(mc.getValueExprs()));
        return result;
    }

    private static ExpressionStreamability classifyList(List<Expr> exprs) {
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        for (int i = 0; i < exprs.size(); i++) {
            result = combine(result, classify(exprs.get(i)));
            if (result == ExpressionStreamability.FREE_RANGING) {
                return result;
            }
        }
        return result;
    }

    // ---- Pattern classification ----

    private static ExpressionStreamability classifyPathPattern(
            PathPattern pp) {
        PatternStep[] steps = pp.getSteps();
        if (steps == null) {
            return ExpressionStreamability.CONSUMING;
        }

        ExpressionStreamability result = ExpressionStreamability.CONSUMING;
        for (int i = 0; i < steps.length; i++) {
            Step.Axis axis = steps[i].axis;
            ExpressionStreamability axisClass = classifyAxis(axis);
            result = combine(result, axisClass);
        }
        return result;
    }

    private static ExpressionStreamability classifyUnionPattern(
            UnionPattern up) {
        Pattern[] alts = up.getAlternatives();
        ExpressionStreamability result = ExpressionStreamability.MOTIONLESS;
        for (int i = 0; i < alts.length; i++) {
            result = combine(result, classifyPattern(alts[i]));
        }
        return result;
    }

    private static ExpressionStreamability classifyNameTestPattern(
            NameTestPattern ntp) {
        // Simple name test is always consuming (forward axis)
        return ExpressionStreamability.CONSUMING;
    }

    // ---- Combine utility ----

    private static ExpressionStreamability combine(
            ExpressionStreamability a, ExpressionStreamability b) {
        return a.ordinal() > b.ordinal() ? a : b;
    }

    // ---- Expression inspection utilities ----

    /**
     * Counts how many times a named function is called within an expression
     * tree. Walks the entire AST recursively.
     *
     * @param expr the expression to inspect (may be null)
     * @param functionName the local name of the function to count
     * @return the number of calls found
     */
    public static int countFunctionCalls(Expr expr, String functionName) {
        if (expr == null) {
            return 0;
        }
        int count = 0;
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            if (functionName.equals(fc.getLocalName())) {
                count++;
            }
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                count += countFunctionCalls(args.get(i), functionName);
            }
            return count;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            count += countFunctionCalls(be.getLeft(), functionName);
            count += countFunctionCalls(be.getRight(), functionName);
            return count;
        }
        if (expr instanceof UnaryExpr) {
            return countFunctionCalls(((UnaryExpr) expr).getOperand(),
                                     functionName);
        }
        if (expr instanceof PathExpr) {
            PathExpr pe = (PathExpr) expr;
            count += countFunctionCalls(pe.getFilter(), functionName);
            count += countFunctionCalls(pe.getPath(), functionName);
            return count;
        }
        if (expr instanceof FilterExpr) {
            FilterExpr fe = (FilterExpr) expr;
            count += countFunctionCalls(fe.getPrimary(), functionName);
            List<Expr> predicates = fe.getPredicates();
            for (int i = 0; i < predicates.size(); i++) {
                count += countFunctionCalls(predicates.get(i), functionName);
            }
            return count;
        }
        if (expr instanceof LocationPath) {
            LocationPath lp = (LocationPath) expr;
            List<Step> steps = lp.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                List<Expr> predicates = step.getPredicates();
                if (predicates != null) {
                    for (int j = 0; j < predicates.size(); j++) {
                        count += countFunctionCalls(predicates.get(j),
                                                    functionName);
                    }
                }
            }
            return count;
        }
        if (expr instanceof SequenceExpr) {
            List<Expr> items = ((SequenceExpr) expr).getItems();
            for (int i = 0; i < items.size(); i++) {
                count += countFunctionCalls(items.get(i), functionName);
            }
            return count;
        }
        if (expr instanceof IfExpr) {
            IfExpr ie = (IfExpr) expr;
            count += countFunctionCalls(ie.getCondition(), functionName);
            count += countFunctionCalls(ie.getThenExpr(), functionName);
            count += countFunctionCalls(ie.getElseExpr(), functionName);
            return count;
        }
        if (expr instanceof ForExpr) {
            ForExpr fe = (ForExpr) expr;
            List<ForExpr.Binding> bindings = fe.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                count += countFunctionCalls(bindings.get(i).getSequence(),
                                            functionName);
            }
            count += countFunctionCalls(fe.getReturnExpr(), functionName);
            return count;
        }
        if (expr instanceof LetExpr) {
            LetExpr le = (LetExpr) expr;
            List<LetExpr.Binding> bindings = le.getBindings();
            for (int i = 0; i < bindings.size(); i++) {
                count += countFunctionCalls(bindings.get(i).getValue(),
                                            functionName);
            }
            count += countFunctionCalls(le.getReturnExpr(), functionName);
            return count;
        }
        return count;
    }

    /**
     * Counts function calls in a compiled XPath expression.
     *
     * @param xpathExpr the compiled expression (may be null)
     * @param functionName the local name of the function to count
     * @return the number of calls found
     */
    public static int countFunctionCalls(XPathExpression xpathExpr,
                                         String functionName) {
        if (xpathExpr == null) {
            return 0;
        }
        return countFunctionCalls(xpathExpr.getCompiledExpr(), functionName);
    }

    /**
     * Checks whether a compiled XPath expression uses a descendant or
     * descendant-or-self axis, indicating a crawling posture.
     *
     * @param xpathExpr the compiled expression (may be null)
     * @return true if the expression contains a descendant axis
     */
    public static boolean containsDescendantAxis(XPathExpression xpathExpr) {
        if (xpathExpr == null) {
            return false;
        }
        return containsDescendantAxis(xpathExpr.getCompiledExpr());
    }

    /**
     * Checks whether an expression uses a descendant or descendant-or-self
     * axis anywhere in its tree.
     */
    static boolean containsDescendantAxis(Expr expr) {
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
            return containsDescendantAxis(pe.getFilter())
                || containsDescendantAxis(pe.getPath());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return containsDescendantAxis(be.getLeft())
                || containsDescendantAxis(be.getRight());
        }
        if (expr instanceof FilterExpr) {
            return containsDescendantAxis(((FilterExpr) expr).getPrimary());
        }
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (containsDescendantAxis(args.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts how many consuming sub-expressions exist at the top level of
     * a binary expression. Used to detect multiple consuming operands
     * (e.g. {@code TITLE||PRICE}).
     *
     * @param xpathExpr the compiled expression (may be null)
     * @return the number of consuming operands
     */
    public static int countConsumingOperands(XPathExpression xpathExpr) {
        if (xpathExpr == null) {
            return 0;
        }
        return countConsumingOperands(xpathExpr.getCompiledExpr());
    }

    static int countConsumingOperands(Expr expr) {
        if (expr == null) {
            return 0;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return countConsumingOperands(be.getLeft())
                 + countConsumingOperands(be.getRight());
        }
        ExpressionStreamability es = classify(expr);
        if (es == ExpressionStreamability.CONSUMING
                || es == ExpressionStreamability.GROUNDED
                || es == ExpressionStreamability.FREE_RANGING) {
            return 1;
        }
        return 0;
    }

}
