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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * AnalyzeStringNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class AnalyzeStringNode extends XSLTInstruction implements ExpressionHolder {
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
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            double xsltVersion = context.getXsltVersion();
            double processorVersion = context.getProcessorVersion();
            
            // Evaluate and validate the select expression
            XPathValue selectResult = selectExpr.evaluate(context);
            String input = validateAndGetInput(selectResult, xsltVersion);
            
            // Get the regex pattern
            String regex = regexAvt.evaluate(context);
            
            // Get and validate flags
            boolean literalFlag = false;
            String flags = "";
            if (flagsAvt != null) {
                flags = flagsAvt.evaluate(context);
            }
            int patternFlags = parseFlags(flags, processorVersion);
            literalFlag = flags.contains("q");
            
            // XSLT 2.0: validate regex restrictions
            if (processorVersion < 3.0) {
                validateRegexXslt20(regex);
            }
            
            // In literal mode (q flag), escape the regex
            String effectiveRegex = literalFlag ? java.util.regex.Pattern.quote(regex) : regex;
            
            // Compile the regex (cached for repeated calls)
            java.util.regex.Pattern pattern;
            if (effectiveRegex.equals(lastRegex) && patternFlags == lastFlags) {
                pattern = lastPattern;
            } else {
                pattern = java.util.regex.Pattern.compile(effectiveRegex, patternFlags);
                lastRegex = effectiveRegex;
                lastFlags = patternFlags;
                lastPattern = pattern;
            }
            java.util.regex.Matcher matcher = pattern.matcher(input);
            
            // XSLT 2.0: zero-length regex matches are a dynamic error
            if (processorVersion < 3.0 && matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    throw new SAXException("XTDE1150: The regex in " +
                        "xsl:analyze-string matches a zero-length string");
                }
                matcher.reset();
            }
            
            int lastEnd = 0;
            while (matcher.find()) {
                // Handle zero-length matches in XSLT 3.0
                if (matcher.start() == matcher.end()) {
                    continue;
                }
                
                // Non-matching part before this match
                if (lastEnd < matcher.start() && nonMatchingContent != null) {
                    String nonMatch = input.substring(lastEnd, matcher.start());
                    executeWithStringContext(nonMatchingContent, nonMatch, null, context, output);
                }
                
                // Matching part
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
            throw new SAXException("FORX0002: Invalid regular expression: " + e.getMessage(), e);
        }
    }
    
    private String validateAndGetInput(XPathValue selectResult, double xsltVersion) 
            throws SAXException {
        if (selectResult == null) {
            if (xsltVersion < 3.0) {
                throw new SAXException("XPTY0004: The select expression of " +
                    "xsl:analyze-string must return a string value, got empty sequence");
            }
            return "";
        }
        
        // Check for sequences
        if (selectResult instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) selectResult;
            int size = seq.size();
            if (size == 0) {
                if (xsltVersion < 3.0) {
                    throw new SAXException("XPTY0004: The select expression of " +
                        "xsl:analyze-string must return a string value, got empty sequence");
                }
                return "";
            }
            if (size > 1) {
                throw new SAXException("XPTY0004: The select expression of " +
                    "xsl:analyze-string must return a single string, got sequence of " +
                    size + " items");
            }
            selectResult = seq.iterator().next();
        }
        
        // Check type: must be a string or node (which can be atomized to string)
        if (selectResult instanceof XPathNumber) {
            throw new SAXException("XPTY0004: The select expression of " +
                "xsl:analyze-string requires xs:string, got numeric value");
        }
        
        return selectResult.asString();
    }
    
    private int parseFlags(String flags, double xsltVersion) throws SAXException {
        int patternFlags = 0;
        for (int i = 0; i < flags.length(); i++) {
            char c = flags.charAt(i);
            switch (c) {
                case 'i':
                    patternFlags |= java.util.regex.Pattern.CASE_INSENSITIVE;
                    break;
                case 'm':
                    patternFlags |= java.util.regex.Pattern.MULTILINE;
                    break;
                case 's':
                    patternFlags |= java.util.regex.Pattern.DOTALL;
                    break;
                case 'x':
                    patternFlags |= java.util.regex.Pattern.COMMENTS;
                    break;
                case 'q':
                    if (xsltVersion < 3.0) {
                        throw new SAXException("XTDE1145: Flag 'q' (literal) " +
                            "is not allowed in XSLT " + xsltVersion);
                    }
                    break;
                default:
                    throw new SAXException("XTDE1145: Invalid flag character '" + 
                        c + "' in xsl:analyze-string flags");
            }
        }
        return patternFlags;
    }
    
    private void validateRegexXslt20(String regex) throws SAXException {
        if (regex.isEmpty()) {
            throw new SAXException("XTDE1150: The regex in " +
                "xsl:analyze-string must not be empty (matches zero-length string)");
        }
        // Check for non-capturing groups (?:...) - not allowed in XSLT 2.0
        int idx = 0;
        while (idx < regex.length()) {
            char c = regex.charAt(idx);
            if (c == '\\') {
                idx += 2;
                continue;
            }
            if (c == '(' && idx + 2 < regex.length() 
                    && regex.charAt(idx + 1) == '?' && regex.charAt(idx + 2) == ':') {
                throw new SAXException("XTDE1140: Non-capturing groups (?:...) " +
                    "are not allowed in XSLT 2.0 regular expressions");
            }
            idx++;
        }
    }
    
    private void executeWithStringContext(XSLTNode content, String contextString,
                                          java.util.regex.Matcher matcher,
                                          TransformContext context, OutputHandler output) 
            throws SAXException {
        // Per XSLT spec, the context item within matching-substring and
        // non-matching-substring is a string (xs:string), not a node.
        // Path expressions on a string context must raise XPTY0020.
        XPathString strItem = new XPathString(contextString);
        TransformContext strContext;
        if (context instanceof BasicTransformContext) {
            BasicTransformContext btc = (BasicTransformContext) context;
            strContext = btc.withContextItem(strItem);
            ((BasicTransformContext) strContext).setXsltCurrentItem(strItem);
        } else {
            strContext = context;
        }
        // Set the regex matcher for regex-group() function access
        if (matcher != null) {
            strContext = strContext.withRegexMatcher(matcher);
        }
        content.execute(strContext, output);
    }
}
