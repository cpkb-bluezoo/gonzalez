/*
 * AbstractPattern.java
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.BinaryExpr;
import org.bluezoo.gonzalez.transform.xpath.expr.Expr;
import org.bluezoo.gonzalez.transform.xpath.expr.FunctionCall;
import org.bluezoo.gonzalez.transform.xpath.expr.Literal;
import org.bluezoo.gonzalez.transform.xpath.expr.LocationPath;
import org.bluezoo.gonzalez.transform.xpath.expr.Operator;
import org.bluezoo.gonzalez.transform.xpath.expr.Step;
import org.bluezoo.gonzalez.transform.xpath.expr.UnaryExpr;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * Base class for all pattern implementations.
 * Holds the original pattern string, an optional predicate, and provides
 * shared predicate evaluation logic.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
abstract class AbstractPattern implements Pattern {

    private static final double POSITION_COMPARISON_TOLERANCE = 0.0001;
    static final Map<String, XPathExpression> xpathCache = new HashMap<>();
    /** Cache: whether a predicate expression needs sibling position/size. */
    private static final Map<String, Boolean> needsPositionCache = new HashMap<>();
    /** Cache: simple @attr='literal' fingerprints extracted from predicates. */
    private static final Map<String, SimpleAttrEquality> simpleAttrEqualityCache =
        new HashMap<>();
    private static final SimpleAttrEquality NO_SIMPLE_ATTR_EQUALITY =
        new SimpleAttrEquality("", "", "");

    protected final String patternStr;
    protected final String predicateStr;
    private SimpleAttrEquality cachedSimpleAttrEquality;
    private boolean simpleAttrEqualityResolved;

    AbstractPattern(String patternStr, String predicateStr) {
        this.patternStr = patternStr;
        this.predicateStr = predicateStr;
    }

    String getPredicateStr() {
        return predicateStr;
    }

    @Override
    public SimpleAttrEquality getSimpleAttrEquality() {
        if (!simpleAttrEqualityResolved) {
            cachedSimpleAttrEquality = analyzeSimpleAttrEquality(predicateStr);
            simpleAttrEqualityResolved = true;
        }
        return cachedSimpleAttrEquality;
    }

    /**
     * Subclass-specific match logic (without predicate evaluation).
     * Called by {@link #matchesWithTarget} before applying the predicate.
     */
    abstract boolean matchesBase(XPathNode node, TransformContext context,
                                 XPathNode targetNode);

    @Override
    public boolean matches(XPathNode node, TransformContext context) {
        return matchesWithTarget(node, context, node);
    }

    boolean matchesWithTarget(XPathNode node, TransformContext context,
                              XPathNode targetNode) {
        if (!matchesBase(node, context, targetNode)) {
            return false;
        }
        if (predicateStr == null) {
            return true;
        }
        return evaluatePredicate(node, context, targetNode);
    }

    protected boolean evaluatePredicate(XPathNode node,
                                        TransformContext context,
                                        XPathNode targetNode) {
        // Fast path: @attr = 'literal' — avoid XPath context / expression eval.
        SimpleAttrEquality attrEq = getSimpleAttrEquality();
        if (attrEq != null) {
            XPathNode attr = node.getAttribute(attrEq.getNamespaceURI(),
                attrEq.getLocalName());
            if (attr == null) {
                return false;
            }
            return attrEq.getValue().equals(attr.getStringValue());
        }

        try {
            XPathExpression predExpr = xpathCache.get(predicateStr);
            if (predExpr == null) {
                predExpr = XPathExpression.compile(predicateStr, null);
                xpathCache.put(predicateStr, predExpr);
            }

            int position = 1;
            int size = 1;

            // Only walk siblings when the predicate needs position()/last()
            // or is a numeric predicate (XPath: [n] means position()=n).
            // Scanning all matching siblings is O(n) per attempt and was the
            // many-templates-1.0 cliff (item[@id='k'] under a 5k-child parent).
            if (predicateNeedsContextPosition(predicateStr, predExpr)) {
                XPathNode parent = node.getParent();
                if (parent != null) {
                    Iterator<XPathNode> siblings = parent.getChildren();
                    boolean foundNode = false;
                    size = 0;
                    position = 1;
                    while (siblings.hasNext()) {
                        XPathNode sibling = siblings.next();
                        if (matchesBase(sibling, context, targetNode)) {
                            size++;
                            if (!foundNode) {
                                if (sibling == node) {
                                    foundNode = true;
                                    position = size;
                                }
                            }
                        }
                    }
                }
            }

            TransformContext predContext;
            if (context instanceof BasicTransformContext) {
                BasicTransformContext btc = (BasicTransformContext) context;
                predContext = btc.withContextAndCurrentNodes(node, targetNode)
                    .withPositionAndSize(position, size);
            } else {
                predContext = context.withContextNode(node)
                    .withPositionAndSize(position, size);
            }

            XPathValue result = predExpr.evaluate(predContext);

            if (result.getType() == XPathValue.Type.NUMBER) {
                double d = result.asNumber();
                return !Double.isNaN(d) &&
                       Math.abs(d - position) < POSITION_COMPARISON_TOLERANCE;
            } else {
                return result.asBoolean();
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause.getMessage() != null &&
                    cause.getMessage().contains("XTDE0640")) {
                    throw new RuntimeException(cause.getMessage(), cause);
                }
                cause = cause.getCause();
            }
            return false;
        }
    }

    /**
     * Analyzes a predicate string for a simple {@code @attr = 'literal'} form.
     *
     * @param pred the predicate text, or null
     * @return the equality fingerprint, or null if not simple
     */
    static SimpleAttrEquality analyzeSimpleAttrEquality(String pred) {
        if (pred == null || pred.isEmpty()) {
            return null;
        }
        SimpleAttrEquality cached = simpleAttrEqualityCache.get(pred);
        if (cached != null) {
            return cached == NO_SIMPLE_ATTR_EQUALITY ? null : cached;
        }
        SimpleAttrEquality result = null;
        try {
            XPathExpression predExpr = xpathCache.get(pred);
            if (predExpr == null) {
                predExpr = XPathExpression.compile(pred, null);
                xpathCache.put(pred, predExpr);
            }
            result = extractSimpleAttrEquality(predExpr.getCompiledExpr());
        } catch (Exception e) {
            result = null;
        }
        simpleAttrEqualityCache.put(pred,
            result == null ? NO_SIMPLE_ATTR_EQUALITY : result);
        return result;
    }

    private static SimpleAttrEquality extractSimpleAttrEquality(Expr expr) {
        if (!(expr instanceof BinaryExpr)) {
            return null;
        }
        BinaryExpr be = (BinaryExpr) expr;
        Operator op = be.getOperator();
        if (op != Operator.EQUALS && op != Operator.VALUE_EQUALS) {
            return null;
        }

        Expr left = be.getLeft();
        Expr right = be.getRight();
        String literal = stringLiteralValue(right);
        Expr pathExpr = left;
        if (literal == null) {
            literal = stringLiteralValue(left);
            pathExpr = right;
        }
        if (literal == null) {
            return null;
        }

        String[] attr = extractAttributeName(pathExpr);
        if (attr == null) {
            return null;
        }
        return new SimpleAttrEquality(attr[0], attr[1], literal);
    }

    private static String stringLiteralValue(Expr expr) {
        if (!(expr instanceof Literal)) {
            return null;
        }
        XPathValue value = ((Literal) expr).getValue();
        if (value instanceof XPathString) {
            return ((XPathString) value).getValue();
        }
        return null;
    }

    /**
     * Returns {namespaceURI, localName} for a relative {@code @name} path,
     * or null if the expression is not a single attribute name step.
     */
    private static String[] extractAttributeName(Expr expr) {
        if (!(expr instanceof LocationPath)) {
            return null;
        }
        LocationPath path = (LocationPath) expr;
        if (path.isAbsolute()) {
            return null;
        }
        List<Step> steps = path.getSteps();
        if (steps.size() != 1) {
            return null;
        }
        Step step = steps.get(0);
        if (step.getAxis() != Step.Axis.ATTRIBUTE || step.hasPredicates()) {
            return null;
        }
        Step.NodeTestType test = step.getNodeTestType();
        String local = step.getLocalName();
        if (local == null) {
            return null;
        }
        if (test == Step.NodeTestType.NAME) {
            return new String[] { "", local };
        }
        if (test == Step.NodeTestType.QNAME) {
            String ns = step.getNamespaceURI();
            return new String[] { ns == null ? "" : ns, local };
        }
        // *:local — index by local name with empty ns key would be wrong;
        // skip indexing for any-namespace attribute tests.
        return null;
    }

    /**
     * Returns true if evaluating this predicate requires accurate context
     * position/size among matching siblings.
     */
    private static boolean predicateNeedsContextPosition(String predicateStr,
            XPathExpression predExpr) {
        Boolean cached = needsPositionCache.get(predicateStr);
        if (cached != null) {
            return cached.booleanValue();
        }
        Expr expr = predExpr != null ? predExpr.getCompiledExpr() : null;
        boolean needs = expr == null || needsContextPosition(expr);
        needsPositionCache.put(predicateStr, Boolean.valueOf(needs));
        return needs;
    }

    /**
     * True when the predicate result is numeric (XPath {@code [n]} means
     * {@code position()=n}) or the expression references {@code position()} /
     * {@code last()}.
     */
    private static boolean needsContextPosition(Expr expr) {
        if (expr == null) {
            return true;
        }
        if (isNumericResult(expr)) {
            return true;
        }
        return referencesPositionOrLast(expr);
    }

    private static boolean isNumericResult(Expr expr) {
        if (expr instanceof Literal) {
            return ((Literal) expr).getValue() instanceof XPathNumber;
        }
        if (expr instanceof UnaryExpr) {
            return true;
        }
        if (expr instanceof FunctionCall) {
            return isNumericFunction(((FunctionCall) expr).getLocalName());
        }
        if (expr instanceof BinaryExpr) {
            Operator op = ((BinaryExpr) expr).getOperator();
            return isArithmeticOperator(op);
        }
        return false;
    }

    private static boolean isArithmeticOperator(Operator op) {
        return op == Operator.PLUS || op == Operator.MINUS
                || op == Operator.MULTIPLY || op == Operator.DIV
                || op == Operator.IDIV || op == Operator.MOD
                || op == Operator.TO;
    }

    private static boolean isNumericFunction(String name) {
        if (name == null) {
            return false;
        }
        return "position".equals(name)
                || "last".equals(name)
                || "count".equals(name)
                || "sum".equals(name)
                || "number".equals(name)
                || "string-length".equals(name)
                || "floor".equals(name)
                || "ceiling".equals(name)
                || "round".equals(name)
                || "abs".equals(name)
                || "avg".equals(name)
                || "min".equals(name)
                || "max".equals(name);
    }

    private static boolean referencesPositionOrLast(Expr expr) {
        if (expr instanceof FunctionCall) {
            FunctionCall fc = (FunctionCall) expr;
            String name = fc.getLocalName();
            if ("position".equals(name) || "last".equals(name)) {
                return true;
            }
            List<Expr> args = fc.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (referencesPositionOrLast(args.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) expr;
            return referencesPositionOrLast(be.getLeft())
                    || referencesPositionOrLast(be.getRight());
        }
        if (expr instanceof UnaryExpr) {
            return referencesPositionOrLast(((UnaryExpr) expr).getOperand());
        }
        return false;
    }

    @Override
    public String toString() {
        return patternStr;
    }
}
