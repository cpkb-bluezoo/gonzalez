/*
 * NamespaceInstructionNode.java
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate;
import org.bluezoo.gonzalez.transform.compiler.ExpressionHolder;
import org.bluezoo.gonzalez.transform.runtime.ItemCollectorOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * NamespaceInstructionNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class NamespaceInstructionNode extends XSLTInstruction implements ExpressionHolder {
    private final AttributeValueTemplate nameAvt;
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    
    public NamespaceInstructionNode(AttributeValueTemplate nameAvt, XPathExpression selectExpr, 
                             SequenceNode content) {
        this.nameAvt = nameAvt;
        this.selectExpr = selectExpr;
        this.content = content;
    }
    
    @Override public String getInstructionName() { return "namespace"; }

    @Override
    public List<XPathExpression> getExpressions() {
        List<XPathExpression> exprs = new ArrayList<XPathExpression>();
        if (selectExpr != null) {
            exprs.add(selectExpr);
        }
        return exprs;
    }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        try {
            // Get the prefix (name attribute)
            String prefix = nameAvt.evaluate(context);
            
            // Get the namespace URI from select or content
            String uri;
            if (selectExpr != null) {
                uri = selectExpr.evaluate(context).asString();
            } else if (content != null) {
                ItemCollectorOutputHandler collector = new ItemCollectorOutputHandler();
                content.execute(context, collector);
                uri = collector.getContentAsString(" ");
            } else {
                uri = "";
            }
            
            // XTDE0920: name must be a zero-length string, an NCName, and not "xmlns"
            if ("xmlns".equals(prefix)) {
                throw new SAXException("XTDE0920: xsl:namespace name must not be 'xmlns'");
            }
            if (prefix != null && !prefix.isEmpty() && !isNCName(prefix)) {
                throw new SAXException("XTDE0920: xsl:namespace name '" + prefix +
                    "' is not a valid NCName");
            }
            
            // XTDE0905: namespace URI must be valid xs:anyURI and not the xmlns namespace
            if ("http://www.w3.org/2000/xmlns/".equals(uri)) {
                throw new SAXException("XTDE0905: namespace URI must not be the xmlns namespace");
            }
            if (!uri.isEmpty() && !isValidUri(uri)) {
                throw new SAXException("XTDE0905: namespace URI '" + uri +
                    "' is not a valid xs:anyURI");
            }
            
            // XTDE0930: for a non-empty prefix, URI must not be empty
            if (prefix != null && !prefix.isEmpty() && uri.isEmpty()) {
                throw new SAXException("XTDE0930: xsl:namespace with non-empty name '" +
                    prefix + "' must not produce a zero-length namespace URI");
            }
            
            // XTDE0925: xml prefix and http://www.w3.org/XML/1998/namespace must be bound exclusively to each other
            String xmlNamespaceUri = "http://www.w3.org/XML/1998/namespace";
            if ("xml".equals(prefix) && !xmlNamespaceUri.equals(uri)) {
                throw new SAXException("XTDE0925: xsl:namespace: prefix 'xml' must be bound to http://www.w3.org/XML/1998/namespace");
            }
            if (xmlNamespaceUri.equals(uri) && !"xml".equals(prefix)) {
                throw new SAXException("XTDE0925: xsl:namespace: namespace URI http://www.w3.org/XML/1998/namespace must be bound to prefix 'xml'");
            }
            
            output.namespace(prefix != null ? prefix : "", uri);
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:namespace", e);
        }
    }

    private static boolean isValidUri(String s) {
        if (s == null) {
            return false;
        }
        try {
            new URI(s);
            return true;
        } catch (Exception e) {
            // java.net.URI is stricter than xs:anyURI. Per XSD 1.0,
            // xs:anyURI allows IRI characters that are implicitly
            // percent-encoded when converting to a URI (XLink §5.4).
            // Encode non-URI characters and re-validate structure.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (isUriChar(c)) {
                    sb.append(c);
                } else {
                    appendPercentEncoded(sb, c);
                }
            }
            try {
                String encoded = sb.toString();
                new URI(encoded);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /**
     * Tests whether a character belongs to the URI character set
     * (unreserved, reserved, or percent-sign) per RFC 3986.
     */
    private static boolean isUriChar(char c) {
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        if (c >= 'a' && c <= 'z') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        switch (c) {
            // unreserved
            case '-': case '.': case '_': case '~':
            // gen-delims
            case ':': case '/': case '?': case '#':
            case '[': case ']': case '@':
            // sub-delims
            case '!': case '$': case '&': case '\'':
            case '(': case ')': case '*': case '+':
            case ',': case ';': case '=':
            // percent-encoding
            case '%':
                return true;
            default:
                return false;
        }
    }

    /**
     * Appends the percent-encoded UTF-8 bytes of a character.
     */
    private static void appendPercentEncoded(StringBuilder sb, char c) {
        if (c <= 0x7F) {
            sb.append('%');
            sb.append(HEX_DIGITS[(c >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[c & 0x0F]);
        } else if (c <= 0x7FF) {
            int b1 = 0xC0 | (c >> 6);
            int b2 = 0x80 | (c & 0x3F);
            sb.append('%');
            sb.append(HEX_DIGITS[(b1 >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[b1 & 0x0F]);
            sb.append('%');
            sb.append(HEX_DIGITS[(b2 >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[b2 & 0x0F]);
        } else {
            int b1 = 0xE0 | (c >> 12);
            int b2 = 0x80 | ((c >> 6) & 0x3F);
            int b3 = 0x80 | (c & 0x3F);
            sb.append('%');
            sb.append(HEX_DIGITS[(b1 >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[b1 & 0x0F]);
            sb.append('%');
            sb.append(HEX_DIGITS[(b2 >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[b2 & 0x0F]);
            sb.append('%');
            sb.append(HEX_DIGITS[(b3 >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[b3 & 0x0F]);
        }
    }

    private static final char[] HEX_DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private static boolean isNCName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }
}
