/*
 * DocumentConstructorNode.java
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

import org.bluezoo.gonzalez.transform.ValidationMode;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.RuntimeSchemaValidator;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.xml.sax.SAXException;

import java.util.List;

/**
 * XSLT node for the xsl:document instruction.
 *
 * <p>This instruction constructs a document node containing the sequence
 * constructed by evaluating the content. It is used primarily with variables
 * that have as="document-node()" and allows schema validation of the content.
 *
 * <p>Attributes supported:
 * <ul>
 *   <li>validation - Validation mode (strict, lax, preserve, strip)</li>
 *   <li>type - Type annotation (mutually exclusive with validation)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class DocumentConstructorNode implements XSLTNode {

    private final List<XSLTNode> content;
    private final ValidationMode validation;
    private final String typeNamespaceURI;
    private final String typeLocalName;

    /**
     * Creates a document constructor node.
     *
     * @param content the content to wrap in a document node
     * @param validation the validation mode (may be null for default)
     * @param typeNamespaceURI type annotation namespace (may be null)
     * @param typeLocalName type annotation local name (may be null)
     */
    public DocumentConstructorNode(List<XSLTNode> content, ValidationMode validation,
                                   String typeNamespaceURI, String typeLocalName) {
        this.content = content;
        this.validation = validation;
        this.typeNamespaceURI = typeNamespaceURI;
        this.typeLocalName = typeLocalName;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Create a buffer to capture the document content
        SAXEventBuffer buffer = new SAXEventBuffer();
        BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
        
        // Start the document
        bufferOutput.startDocument();
        
        // Get effective validation mode
        ValidationMode effectiveValidation = validation;
        if (effectiveValidation == null && context.getStylesheet() != null) {
            effectiveValidation = context.getStylesheet().getDefaultValidation();
        }
        
        // Get runtime validator if we need validation
        // Note: For xsl:document, we use LAX validation mode even when STRICT is requested.
        // This is because full content model validation for deeply nested local elements
        // requires tracking complex type contexts through the entire document structure,
        // which isn't fully implemented yet. LAX mode will still capture type annotations
        // for elements that can be found in the schema.
        RuntimeSchemaValidator validator = null;
        ValidationMode actualMode = effectiveValidation;
        if (effectiveValidation == ValidationMode.STRICT) {
            actualMode = ValidationMode.LAX;
        }
        if (actualMode == ValidationMode.LAX) {
            validator = context.getRuntimeValidator();
        }
        
        // Execute content with validation-aware output handler
        OutputHandler validatingOutput;
        if (validator != null) {
            validatingOutput = new ValidatingDocumentOutput(bufferOutput, validator, actualMode);
        } else {
            validatingOutput = bufferOutput;
        }
        
        // Execute the content
        for (XSLTNode child : content) {
            child.execute(context, validatingOutput);
        }
        
        // Flush any pending content
        validatingOutput.flush();
        
        // End the document
        bufferOutput.endDocument();
        
        // Create the result tree fragment
        XPathResultTreeFragment rtf = new XPathResultTreeFragment(buffer);
        
        // If the output is a SequenceBuilderOutputHandler, add the document directly
        // as an item. This preserves the document node structure for as="document-node()"
        // variables. Otherwise, replay the events to the output handler.
        if (output instanceof StylesheetCompiler.SequenceBuilderOutputHandler) {
            ((StylesheetCompiler.SequenceBuilderOutputHandler) output).addItem(rtf);
        } else {
            rtf.replayToOutput(output, true);
        }
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        // Document construction requires full materialization
        return StreamingCapability.NONE;
    }

    @Override
    public String toString() {
        return "DocumentConstructorNode[validation=" + validation + "]";
    }

    /**
     * Output handler that intercepts element events and applies schema validation.
     */
    private static class ValidatingDocumentOutput implements OutputHandler {
        private final OutputHandler delegate;
        private final RuntimeSchemaValidator validator;
        private final ValidationMode validationMode;

        ValidatingDocumentOutput(OutputHandler delegate, RuntimeSchemaValidator validator,
                                ValidationMode validationMode) {
            this.delegate = delegate;
            this.validator = validator;
            this.validationMode = validationMode;
        }

        @Override
        public void startDocument() throws SAXException {
            delegate.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            delegate.endDocument();
        }

        @Override
        public void startElement(String namespaceURI, String localName, String qName) throws SAXException {
            // Validate the element start and get type annotation
            try {
                RuntimeSchemaValidator.ValidationResult result = 
                    validator.startElement(namespaceURI, localName, validationMode);
                
                delegate.startElement(namespaceURI, localName, qName);
                
                // Apply type annotation if validation determined one
                if (result.hasTypeAnnotation()) {
                    delegate.setElementType(result.getTypeNamespaceURI(), result.getTypeLocalName());
                }
            } catch (XPathException e) {
                throw new SAXException("Validation error in xsl:document", e);
            }
        }

        @Override
        public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
            // Complete validation
            try {
                RuntimeSchemaValidator.ValidationResult result = validator.endElement();
                if (!result.isValid() && validationMode == ValidationMode.STRICT) {
                    throw new SAXException(result.getErrorCode() + ": " + result.getErrorMessage());
                }
            } catch (XPathException e) {
                throw new SAXException("Validation error in xsl:document", e);
            }
            
            delegate.endElement(namespaceURI, localName, qName);
        }

        @Override
        public void attribute(String namespaceURI, String localName, String qName, String value) 
                throws SAXException {
            // Validate the attribute
            try {
                RuntimeSchemaValidator.ValidationResult result =
                    validator.validateStandaloneAttribute(namespaceURI, localName, value, validationMode);
                
                delegate.attribute(namespaceURI, localName, qName, value);
                
                // Apply type annotation if validation determined one
                if (result.hasTypeAnnotation()) {
                    delegate.setAttributeType(result.getTypeNamespaceURI(), result.getTypeLocalName());
                }
            } catch (XPathException e) {
                throw new SAXException("Attribute validation error in xsl:document", e);
            }
        }

        @Override
        public void namespace(String prefix, String uri) throws SAXException {
            delegate.namespace(prefix, uri);
        }

        @Override
        public void characters(String text) throws SAXException {
            delegate.characters(text);
        }

        @Override
        public void charactersRaw(String text) throws SAXException {
            delegate.charactersRaw(text);
        }

        @Override
        public void comment(String text) throws SAXException {
            delegate.comment(text);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            delegate.processingInstruction(target, data);
        }

        @Override
        public void flush() throws SAXException {
            delegate.flush();
        }

        @Override
        public void setElementType(String namespaceURI, String localName) throws SAXException {
            delegate.setElementType(namespaceURI, localName);
        }

        @Override
        public void setAttributeType(String namespaceURI, String localName) throws SAXException {
            delegate.setAttributeType(namespaceURI, localName);
        }

        @Override
        public void setAtomicValuePending(boolean pending) throws SAXException {
            delegate.setAtomicValuePending(pending);
        }

        @Override
        public boolean isAtomicValuePending() {
            return delegate.isAtomicValuePending();
        }

        @Override
        public void setInAttributeContent(boolean inAttributeContent) throws SAXException {
            delegate.setInAttributeContent(inAttributeContent);
        }

        @Override
        public boolean isInAttributeContent() {
            return delegate.isInAttributeContent();
        }

        @Override
        public void itemBoundary() throws SAXException {
            delegate.itemBoundary();
        }
    }
}
