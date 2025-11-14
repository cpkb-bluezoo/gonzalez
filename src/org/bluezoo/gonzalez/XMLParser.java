/*
 * XMLParser.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * XML parser.
 * This is a consumer of a stream of tokens.
 * Each event informs the parser of a token type and the content of the
 * token.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class XMLParser implements TokenConsumer {

    enum State {
        INIT,                       // Initial state, expecting prolog or root element
        PROLOG,                     // In prolog, before root element
        ELEMENT_START,              // After LT, expecting element name
        ELEMENT_NAME,               // Read element name, expecting S/GT/END_EMPTY_ELEMENT/COLON
        ELEMENT_ATTRS,              // In element, expecting attributes or GT/END_EMPTY_ELEMENT
        ATTRIBUTE_NAME,             // Read attribute name, expecting EQ
        ATTRIBUTE_EQ,               // Read EQ, expecting quote
        ATTRIBUTE_VALUE_START,      // Read quote, expecting attribute value
        ATTRIBUTE_VALUE,            // Reading attribute value
        ELEMENT_CONTENT,            // Inside element, expecting content or end tag
        END_ELEMENT_START,          // After START_END_ELEMENT (</), expecting name
        END_ELEMENT_NAME,           // Read end tag name, expecting GT
        PI_TARGET,                  // After START_PI, expecting PI target
        PI_CONTENT,                 // After PI target, expecting PI content or END_PI
        COMMENT,                    // After START_COMMENT, expecting comment content
        CDATA_SECTION,              // After START_CDATA, expecting CDATA content
        AFTER_ROOT                  // After root element closed, expecting only whitespace/comments/PIs
    }

    private State state = State.INIT;
    private Locator locator;
    
    /**
     * SAX content handler for receiving document events.
     */
    private ContentHandler contentHandler;
    
    /**
     * SAX DTD handler for receiving DTD events.
     */
    private DTDHandler dtdHandler;
    
    /**
     * SAX lexical handler for receiving lexical events.
     */
    private LexicalHandler lexicalHandler;
    
    /**
     * SAX error handler for error reporting.
     */
    private ErrorHandler errorHandler;
    
    /**
     * Lazily-constructed DTD parser for processing DOCTYPE declarations.
     * Only allocated when a DOCTYPE is encountered.
     */
    private DTDParser dtdParser;
    
    /**
     * Attributes for the current element.
     */
    private SAXAttributes attributes;
    
    /**
     * Current element name being processed.
     */
    private String currentElementName;
    
    /**
     * Current attribute name being processed.
     */
    private String currentAttributeName;
    
    /**
     * Current attribute value being accumulated.
     */
    private StringBuilder currentAttributeValue;
    
    /**
     * Current attribute quote character (QUOT or APOS).
     */
    private Token currentAttributeQuote;
    
    /**
     * Current PI target.
     */
    private String currentPITarget;
    
    /**
     * Current PI data being accumulated.
     */
    private StringBuilder currentPIData;
    
    /**
     * Current comment text being accumulated.
     */
    private StringBuilder currentCommentText;
    
    /**
     * Current CDATA text being accumulated.
     */
    private StringBuilder currentCDATAText;
    
    /**
     * Whether startDocument has been called.
     */
    private boolean documentStarted;
    
    /**
     * Element depth (0 = outside root, 1 = root element, 2+ = nested).
     */
    private int elementDepth;

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    /**
     * Sets the content handler for receiving document events.
     * @param handler the content handler
     */
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler;
    }

    /**
     * Sets the DTD handler for receiving DTD events.
     * @param handler the DTD handler
     */
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    /**
     * Sets the lexical handler for receiving lexical events.
     * @param handler the lexical handler
     */
    public void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    /**
     * Sets the error handler for error reporting.
     * @param handler the error handler
     */
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        // Check if we should delegate to DTDParser
        if (dtdParser != null && dtdParser.canReceive(token)) {
            dtdParser.receive(token, data);
            return;
        }
        
        // If DTDParser was active and can no longer receive, clean up
        if (dtdParser != null && !dtdParser.canReceive(token)) {
            dtdParser = null; // Allow GC, DTD parsing complete
            state = State.PROLOG; // Back to prolog after DOCTYPE
        }
        
        // Handle token based on current state
        switch (state) {
            case INIT:
            case PROLOG:
                handleProlog(token, data);
                break;
                
            case ELEMENT_START:
                handleElementStart(token, data);
                break;
                
            case ELEMENT_NAME:
                handleElementName(token, data);
                break;
                
            case ELEMENT_ATTRS:
                handleElementAttrs(token, data);
                break;
                
            case ATTRIBUTE_NAME:
                handleAttributeName(token, data);
                break;
                
            case ATTRIBUTE_EQ:
                handleAttributeEq(token, data);
                break;
                
            case ATTRIBUTE_VALUE_START:
                handleAttributeValueStart(token, data);
                break;
                
            case ATTRIBUTE_VALUE:
                handleAttributeValue(token, data);
                break;
                
            case ELEMENT_CONTENT:
                handleElementContent(token, data);
                break;
                
            case END_ELEMENT_START:
                handleEndElementStart(token, data);
                break;
                
            case END_ELEMENT_NAME:
                handleEndElementName(token, data);
                break;
                
            case PI_TARGET:
                handlePITarget(token, data);
                break;
                
            case PI_CONTENT:
                handlePIContent(token, data);
                break;
                
            case COMMENT:
                handleComment(token, data);
                break;
                
            case CDATA_SECTION:
                handleCDATASection(token, data);
                break;
                
            case AFTER_ROOT:
                handleAfterRoot(token, data);
                break;
        }
    }
    
    /**
     * Handles tokens in the prolog (before root element).
     */
    private void handleProlog(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace in prolog, ignore
                break;
                
            case START_DOCTYPE:
                // Lazily construct DTDParser
                dtdParser = new DTDParser();
                dtdParser.setLocator(locator);
                dtdParser.setDTDHandler(dtdHandler);
                dtdParser.setLexicalHandler(lexicalHandler);
                dtdParser.setErrorHandler(errorHandler);
                break;
                
            case START_PI:
                // Processing instruction in prolog
                currentPIData = new StringBuilder();
                state = State.PI_TARGET;
                break;
                
            case START_COMMENT:
                // Comment in prolog
                currentCommentText = new StringBuilder();
                state = State.COMMENT;
                break;
                
            case LT:
                // Start of root element
                if (!documentStarted && contentHandler != null) {
                    contentHandler.setDocumentLocator(locator);
                    contentHandler.startDocument();
                    documentStarted = true;
                }
                state = State.ELEMENT_START;
                break;
                
            default:
                throw new SAXException("Unexpected token in prolog: " + token);
        }
    }
    
    /**
     * Handles tokens after LT (expecting element name).
     */
    private void handleElementStart(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentElementName = extractString(data);
            elementDepth++;
            state = State.ELEMENT_NAME;
        } else {
            throw new SAXException("Expected element name after '<', got: " + token);
        }
    }
    
    /**
     * Handles tokens after element name.
     */
    private void handleElementName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before attributes or GT
                // Initialize attributes object
                if (attributes == null) {
                    attributes = new SAXAttributes();
                } else {
                    attributes.clear();
                }
                state = State.ELEMENT_ATTRS;
                break;
                
            case GT:
                // End of start tag (no attributes)
                if (contentHandler != null) {
                    // Create empty attributes
                    if (attributes == null) {
                        attributes = new SAXAttributes();
                    } else {
                        attributes.clear();
                    }
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                }
                state = State.ELEMENT_CONTENT;
                break;
                
            case END_EMPTY_ELEMENT:
                // Empty element (no attributes)
                if (contentHandler != null) {
                    // Create empty attributes
                    if (attributes == null) {
                        attributes = new SAXAttributes();
                    } else {
                        attributes.clear();
                    }
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                    contentHandler.endElement("", currentElementName, currentElementName);
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
                throw new SAXException("Unexpected token after element name: " + token);
        }
    }
    
    /**
     * Handles tokens in element attributes section.
     */
    private void handleElementAttrs(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;
                
            case NAME:
                // Attribute name
                currentAttributeName = extractString(data);
                state = State.ATTRIBUTE_NAME;
                break;
                
            case GT:
                // End of start tag
                if (contentHandler != null) {
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                }
                state = State.ELEMENT_CONTENT;
                break;
                
            case END_EMPTY_ELEMENT:
                // Empty element
                if (contentHandler != null) {
                    contentHandler.startElement("", currentElementName, currentElementName, attributes);
                    contentHandler.endElement("", currentElementName, currentElementName);
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
                throw new SAXException("Unexpected token in element attributes: " + token);
        }
    }
    
    /**
     * Handles tokens after attribute name.
     */
    private void handleAttributeName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before =, ignore
                break;
                
            case EQ:
                // Equals sign
                state = State.ATTRIBUTE_EQ;
                break;
                
            default:
                throw new SAXException("Expected '=' after attribute name, got: " + token);
        }
    }
    
    /**
     * Handles tokens after attribute equals sign.
     */
    private void handleAttributeEq(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after =, ignore
                break;
                
            case QUOT:
            case APOS:
                // Start of attribute value
                currentAttributeQuote = token;
                currentAttributeValue = new StringBuilder();
                state = State.ATTRIBUTE_VALUE_START;
                break;
                
            default:
                throw new SAXException("Expected quote after '=', got: " + token);
        }
    }
    
    /**
     * Handles tokens at start of attribute value.
     */
    private void handleAttributeValueStart(Token token, CharBuffer data) throws SAXException {
        handleAttributeValue(token, data);
    }
    
    /**
     * Handles tokens inside attribute value.
     */
    private void handleAttributeValue(Token token, CharBuffer data) throws SAXException {
        if (token == currentAttributeQuote) {
            // End of attribute value
            // Add attribute to list
            attributes.addAttribute("", currentAttributeName, currentAttributeName, 
                                          "CDATA", currentAttributeValue.toString(), true);
            currentAttributeValue.setLength(0); // Reset for next attribute
            state = State.ELEMENT_ATTRS;
        } else if (token == Token.CDATA) {
            // Attribute value text
            currentAttributeValue.append(extractString(data));
        } else if (token == Token.ENTITYREF) {
            // Entity reference in attribute value
            currentAttributeValue.append(extractString(data));
        } else {
            throw new SAXException("Unexpected token in attribute value: " + token);
        }
    }
    
    /**
     * Handles tokens in element content.
     */
    private void handleElementContent(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
                // Character data
                if (contentHandler != null) {
                    String text = extractString(data);
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            case ENTITYREF:
                // Entity reference
                if (contentHandler != null) {
                    String text = extractString(data);
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            case LT:
                // Start of nested element
                state = State.ELEMENT_START;
                break;
                
            case START_END_ELEMENT:
                // End tag
                state = State.END_ELEMENT_START;
                break;
                
            case START_PI:
                // Processing instruction
                currentPIData = new StringBuilder();
                state = State.PI_TARGET;
                break;
                
            case START_COMMENT:
                // Comment
                currentCommentText = new StringBuilder();
                state = State.COMMENT;
                break;
                
            case START_CDATA:
                // CDATA section
                currentCDATAText = new StringBuilder();
                state = State.CDATA_SECTION;
                if (lexicalHandler != null) {
                    lexicalHandler.startCDATA();
                }
                break;
                
            case S:
                // Whitespace
                if (contentHandler != null) {
                    String text = extractString(data);
                    contentHandler.characters(text.toCharArray(), 0, text.length());
                }
                break;
                
            default:
                throw new SAXException("Unexpected token in element content: " + token);
        }
    }
    
    /**
     * Handles tokens after START_END_ELEMENT (</). 
     */
    private void handleEndElementStart(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentElementName = extractString(data);
            state = State.END_ELEMENT_NAME;
        } else {
            throw new SAXException("Expected element name after '</', got: " + token);
        }
    }
    
    /**
     * Handles tokens after end element name.
     */
    private void handleEndElementName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before >, ignore
                break;
                
            case GT:
                // End of end tag
                if (contentHandler != null) {
                    contentHandler.endElement("", currentElementName, currentElementName);
                }
                elementDepth--;
                if (elementDepth == 0) {
                    state = State.AFTER_ROOT;
                    if (contentHandler != null) {
                        contentHandler.endDocument();
                    }
                } else {
                    state = State.ELEMENT_CONTENT;
                }
                break;
                
            default:
                throw new SAXException("Expected '>' after end element name, got: " + token);
        }
    }
    
    /**
     * Handles PI target (after START_PI).
     */
    private void handlePITarget(Token token, CharBuffer data) throws SAXException {
        if (token == Token.NAME) {
            currentPITarget = extractString(data);
            state = State.PI_CONTENT;
        } else {
            throw new SAXException("Expected PI target after '<?', got: " + token);
        }
    }
    
    /**
     * Handles PI content.
     */
    private void handlePIContent(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
            case CDATA:
                // PI data
                if (data != null) {
                    currentPIData.append(extractString(data));
                }
                break;
                
            case END_PI:
                // End of PI
                if (contentHandler != null) {
                    contentHandler.processingInstruction(currentPITarget, currentPIData.toString());
                }
                // Return to appropriate state
                if (elementDepth > 0) {
                    state = State.ELEMENT_CONTENT;
                } else if (documentStarted) {
                    state = State.AFTER_ROOT;
                } else {
                    state = State.PROLOG;
                }
                break;
                
            default:
                // Other tokens are part of PI data
                if (data != null) {
                    currentPIData.append(extractString(data));
                }
                break;
        }
    }
    
    /**
     * Handles comment content.
     */
    private void handleComment(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
            case S:
                // Comment text
                if (data != null) {
                    currentCommentText.append(extractString(data));
                }
                break;
                
            case END_COMMENT:
                // End of comment
                if (lexicalHandler != null) {
                    String text = currentCommentText.toString();
                    lexicalHandler.comment(text.toCharArray(), 0, text.length());
                }
                // Return to appropriate state
                if (elementDepth > 0) {
                    state = State.ELEMENT_CONTENT;
                } else if (documentStarted) {
                    state = State.AFTER_ROOT;
                } else {
                    state = State.PROLOG;
                }
                break;
                
            default:
                // Other tokens might be part of comment (e.g., hyphens)
                break;
        }
    }
    
    /**
     * Handles CDATA section content.
     */
    private void handleCDATASection(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CDATA:
            case S:
                // CDATA text
                if (data != null) {
                    String text = extractString(data);
                    currentCDATAText.append(text);
                    if (contentHandler != null) {
                        contentHandler.characters(text.toCharArray(), 0, text.length());
                    }
                }
                break;
                
            case END_CDATA:
                // End of CDATA section
                if (lexicalHandler != null) {
                    lexicalHandler.endCDATA();
                }
                state = State.ELEMENT_CONTENT;
                break;
                
            default:
                // Other tokens might appear in CDATA (they're literal)
                break;
        }
    }
    
    /**
     * Handles tokens after root element closed.
     */
    private void handleAfterRoot(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace after root, ignore
                break;
                
            case START_PI:
                // PI after root
                currentPIData = new StringBuilder();
                state = State.PI_TARGET;
                break;
                
            case START_COMMENT:
                // Comment after root
                currentCommentText = new StringBuilder();
                state = State.COMMENT;
                break;
                
            default:
                throw new SAXException("Unexpected content after root element: " + token);
        }
    }
    
    /**
     * Extracts a string from a CharBuffer.
     * @param buffer the buffer containing the string
     * @return the extracted string, or empty string if buffer is null
     */
    private String extractString(CharBuffer buffer) {
        if (buffer == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            sb.append(buffer.get());
        }
        return sb.toString();
    }

}
