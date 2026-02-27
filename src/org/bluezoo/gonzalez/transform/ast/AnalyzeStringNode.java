/*
 * AnalyzeStringNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import java.util.Set;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;
import java.util.regex.PatternSyntaxException;

/**
 * AnalyzeStringNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class AnalyzeStringNode extends XSLTInstruction {
    private final XPathExpression selectExpr;
    private final AttributeValueTemplate regexAvt;
    private final AttributeValueTemplate flagsAvt;
    private final XSLTNode matchingContent;
    private final XSLTNode nonMatchingContent;
    private String lastRegex;
    private int lastFlags;
    private java.util.regex.Pattern lastPattern;
    
    public AnalyzeStringNode(XPathExpression selectExpr, AttributeValueTemplate regexAvt,
                     AttributeValueTemplate flagsAvt, XSLTNode matchingContent,
                     XSLTNode nonMatchingContent) {
        this.selectExpr = selectExpr;
        this.regexAvt = regexAvt;
        this.flagsAvt = flagsAvt;
        this.matchingContent = matchingContent;
        this.nonMatchingContent = nonMatchingContent;
    }
    
    @Override public String getInstructionName() { return "analyze-string"; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            // Get the input string
            XPathValue selectResult = selectExpr.evaluate(context);
            String input = selectResult.asString();
            
            // Get the regex pattern
            String regex = regexAvt.evaluate(context);
            
            // Get flags (optional)
            int patternFlags = 0;
            if (flagsAvt != null) {
                String flags = flagsAvt.evaluate(context);
                if (flags.contains("i")) patternFlags |= java.util.regex.Pattern.CASE_INSENSITIVE;
                if (flags.contains("m")) patternFlags |= java.util.regex.Pattern.MULTILINE;
                if (flags.contains("s")) patternFlags |= java.util.regex.Pattern.DOTALL;
                if (flags.contains("x")) patternFlags |= java.util.regex.Pattern.COMMENTS;
            }
            
            // Compile the regex (cached for repeated calls)
            java.util.regex.Pattern pattern;
            if (regex.equals(lastRegex) && patternFlags == lastFlags) {
                pattern = lastPattern;
            } else {
                pattern = java.util.regex.Pattern.compile(regex, patternFlags);
                lastRegex = regex;
                lastFlags = patternFlags;
                lastPattern = pattern;
            }
            java.util.regex.Matcher matcher = pattern.matcher(input);
            
            int lastEnd = 0;
            while (matcher.find()) {
                // Non-matching part before this match
                if (lastEnd < matcher.start() && nonMatchingContent != null) {
                    String nonMatch = input.substring(lastEnd, matcher.start());
                    executeWithStringContext(nonMatchingContent, nonMatch, null, context, output);
                }
                
                // Matching part - pass the matcher for regex-group() access
                if (matchingContent != null) {
                    String match = matcher.group();
                    executeWithStringContext(matchingContent, match, matcher, context, output);
                }
                
                lastEnd = matcher.end();
            }
            
            // Non-matching part after last match
            if (lastEnd < input.length() && nonMatchingContent != null) {
                String nonMatch = input.substring(lastEnd);
                executeWithStringContext(nonMatchingContent, nonMatch, null, context, output);
            }
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:analyze-string select: " + e.getMessage(), e);
        } catch (PatternSyntaxException e) {
            throw new SAXException("Invalid regex in xsl:analyze-string: " + e.getMessage(), e);
        }
    }
    
    private void executeWithStringContext(XSLTNode content, String contextString,
                                          java.util.regex.Matcher matcher,
                                          TransformContext context, OutputHandler output) 
            throws SAXException {
        // Create a context where the context node has the string as its value
        // For xsl:analyze-string, the context item is the matched/non-matched string
        // We create a text node wrapper for this
        XPathNode textNode = new StringContextNode(contextString);
        TransformContext strContext = context.withContextNode(textNode);
        // Set the regex matcher for regex-group() function access
        if (matcher != null) {
            strContext = strContext.withRegexMatcher(matcher);
        }
        content.execute(strContext, output);
    }
}
