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
                uri = collector.getContentAsString(" ").trim();
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
        // xs:anyURI allows IRI references, so be lenient.
        // Only reject strings with characters clearly invalid in IRIs
        // (e.g., fragment-only strings with multiple # delimiters).
        try {
            new URI(s);
            return true;
        } catch (Exception e) {
            // java.net.URI is stricter than xs:anyURI/IRI.
            // Accept strings that look like real namespace URIs
            // (contain a scheme or look like a relative reference).
            int colonPos = s.indexOf(':');
            if (colonPos > 0) {
                return true;
            }
            return false;
        }
    }

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
