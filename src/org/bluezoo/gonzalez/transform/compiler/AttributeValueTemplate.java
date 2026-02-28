/*
 * AttributeValueTemplate.java
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

import org.bluezoo.gonzalez.transform.ast.XSLTNode.StreamingCapability;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.XPathParser;
import org.bluezoo.gonzalez.transform.xpath.XPathSyntaxException;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Attribute Value Template (AVT) in XSLT.
 *
 * <p>AVTs allow XPath expressions to be embedded in attribute values using
 * accolades. For example: {@code class="{$type}-item"} where {@code {$type}}
 * is evaluated as an XPath expression and concatenated with "-item".
 *
 * <p>AVT syntax:
 * <ul>
 *   <li>{@code {expr}} - XPath expression, result converted to string</li>
 *   <li>{@code {{} - literal left accolade</li>
 *   <li>{@code }}} - literal right accolade</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AttributeValueTemplate {

    /**
     * A part of an AVT - either literal text or an XPath expression.
     */
    private static abstract class Part {
        abstract String evaluate(TransformContext context) throws XPathException;
        abstract StreamingCapability getStreamingCapability();
    }

    private static final class LiteralPart extends Part {
        final String text;

        LiteralPart(String text) {
            this.text = text;
        }

        @Override
        String evaluate(TransformContext context) {
            return text;
        }

        @Override
        StreamingCapability getStreamingCapability() {
            return StreamingCapability.FULL;
        }
    }

    private static final class ExpressionPart extends Part {
        final XPathExpression expression;

        ExpressionPart(XPathExpression expression) {
            this.expression = expression;
        }

        @Override
        String evaluate(TransformContext context) throws XPathException {
            XPathValue result = expression.evaluate(context);
            if (result == null) {
                return "";
            }
            return result.asString();
        }

        @Override
        StreamingCapability getStreamingCapability() {
            // Would need expression analysis for accurate capability
            return StreamingCapability.GROUNDED;
        }
    }

    private final String originalValue;
    private final List<Part> parts;
    private final boolean isStatic;
    private final StreamingCapability streamingCapability;

    /**
     * Private constructor - use parse().
     */
    private AttributeValueTemplate(String originalValue, List<Part> parts) {
        this.originalValue = originalValue;
        this.parts = parts;
        this.isStatic = parts.size() == 1 && parts.get(0) instanceof LiteralPart;
        this.streamingCapability = computeStreamingCapability();
    }

    /**
     * Parses an attribute value template.
     *
     * @param value the attribute value string
     * @return the parsed AVT
     * @throws XPathSyntaxException if an embedded expression is invalid
     */
    public static AttributeValueTemplate parse(String value) throws XPathSyntaxException {
        return parse(value, null);
    }

    /**
     * Parses an attribute value template with namespace resolution.
     *
     * @param value the attribute value string
     * @param namespaceResolver resolver for namespace prefixes in embedded expressions
     * @return the parsed AVT
     * @throws XPathSyntaxException if an embedded expression is invalid
     */
    public static AttributeValueTemplate parse(String value,
            XPathParser.NamespaceResolver namespaceResolver) throws XPathSyntaxException {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        int len = value.length();

        while (i < len) {
            char c = value.charAt(i);

            if (c == '{') {
                if (i + 1 < len && value.charAt(i + 1) == '{') {
                    // Escaped left accolade
                    literal.append('{');
                    i += 2;
                } else {
                    // Start of expression
                    if (literal.length() > 0) {
                        parts.add(new LiteralPart(literal.toString()));
                        literal.setLength(0);
                    }

                    // Find matching close accolade
                    int start = i + 1;
                    int depth = 1;
                    i = start;
                    while (i < len && depth > 0) {
                        char ec = value.charAt(i);
                        if (ec == '{') {
                            depth++;
                        } else if (ec == '}') {
                            depth--;
                        } else if (ec == '\'' || ec == '"') {
                            // Skip string literals
                            char quote = ec;
                            i++;
                            while (i < len && value.charAt(i) != quote) {
                                i++;
                            }
                        }
                        if (depth > 0) {
                            i++;
                        }
                    }

                    if (depth != 0) {
                        throw new XPathSyntaxException("Unclosed '{' in attribute value template");
                    }

                    String exprStr = value.substring(start, i);
                    XPathExpression expr = XPathExpression.compile(exprStr, namespaceResolver);
                    parts.add(new ExpressionPart(expr));
                    i++; // Skip closing accolade
                }
            } else if (c == '}') {
                if (i + 1 < len && value.charAt(i + 1) == '}') {
                    // Escaped right accolade
                    literal.append('}');
                    i += 2;
                } else {
                    throw new XPathSyntaxException("Unescaped '}' in attribute value template");
                }
            } else {
                literal.append(c);
                i++;
            }
        }

        if (literal.length() > 0) {
            parts.add(new LiteralPart(literal.toString()));
        }

        // Handle empty AVT
        if (parts.isEmpty()) {
            parts.add(new LiteralPart(""));
        }

        return new AttributeValueTemplate(value, parts);
    }

    /**
     * Creates a static (non-templated) AVT.
     *
     * @param value the literal value
     * @return the AVT
     */
    public static AttributeValueTemplate literal(String value) {
        List<Part> parts = new ArrayList<>(1);
        parts.add(new LiteralPart(value));
        return new AttributeValueTemplate(value, parts);
    }

    /**
     * Evaluates this AVT in the given context.
     *
     * @param context the transformation context
     * @return the evaluated string value
     * @throws XPathException if expression evaluation fails
     */
    public String evaluate(TransformContext context) throws XPathException {
        if (isStatic) {
            return ((LiteralPart) parts.get(0)).text;
        }

        StringBuilder result = new StringBuilder();
        for (Part part : parts) {
            result.append(part.evaluate(context));
        }
        return result.toString();
    }

    /**
     * Returns the streaming capability of this AVT.
     *
     * @return the streaming capability
     */
    public StreamingCapability getStreamingCapability() {
        return streamingCapability;
    }

    private StreamingCapability computeStreamingCapability() {
        StreamingCapability result = StreamingCapability.FULL;
        for (Part part : parts) {
            StreamingCapability partCap = part.getStreamingCapability();
            if (partCap.ordinal() > result.ordinal()) {
                result = partCap;
            }
        }
        return result;
    }

    /**
     * Returns true if this AVT is static (no embedded expressions).
     *
     * @return true if static
     */
    public boolean isStatic() {
        return isStatic;
    }

    /**
     * Returns the original attribute value string.
     *
     * @return the original value
     */
    public String getOriginalValue() {
        return originalValue;
    }

    @Override
    public String toString() {
        return "AVT[" + originalValue + "]";
    }

}
