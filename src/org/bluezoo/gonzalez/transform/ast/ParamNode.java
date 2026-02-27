/*
 * ParamNode.java
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

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;

/**
 * ParamNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ParamNode extends XSLTInstruction {
    private final String namespaceURI;
    private final String localName;
    private final XPathExpression selectExpr;
    private final SequenceNode content;
    private final String asType; // XSLT 2.0 type annotation
    private final boolean tunnel; // XSLT 2.0 tunnel parameter
    private final boolean required; // XSLT 2.0 required parameter
    
    public ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, SequenceNode content) {
        this(namespaceURI, localName, selectExpr, content, null, false, false);
    }
    
    public ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, 
              SequenceNode content, String asType) {
        this(namespaceURI, localName, selectExpr, content, asType, false, false);
    }
    
    public ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, 
              SequenceNode content, String asType, boolean tunnel) {
        this(namespaceURI, localName, selectExpr, content, asType, tunnel, false);
    }
    
    public ParamNode(String namespaceURI, String localName, XPathExpression selectExpr, 
              SequenceNode content, String asType, boolean tunnel, boolean required) {
        this.namespaceURI = namespaceURI;
        this.localName = localName;
        this.selectExpr = selectExpr;
        this.content = content;
        this.asType = asType;
        this.tunnel = tunnel;
        this.required = required;
    }
    
    public String getNamespaceURI() { return namespaceURI; }
    public String getLocalName() { return localName; }
    public String getName() { return localName; }  // For compatibility
    public XPathExpression getSelectExpr() { return selectExpr; }
    public SequenceNode getContent() { return content; }
    public String getAs() { return asType; }
    public boolean isTunnel() { return tunnel; }
    public boolean isRequired() { return required; }
    
    @Override public String getInstructionName() { return "param"; }
    
    @Override
    public void execute(TransformContext context, 
                       OutputHandler output) throws SAXException {
        // Params are handled by template instantiation
    }
}
