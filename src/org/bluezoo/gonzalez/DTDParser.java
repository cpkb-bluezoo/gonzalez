/*
 * DTDParser.java
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
import java.util.HashMap;
import java.util.Map;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * DTD parser.
 * <p>
 * This is a consumer of tokens within a DOCTYPE declaration. It is lazily
 * constructed by the XMLParser when a DOCTYPE token is encountered, and
 * receives tokens until the final GT of the doctypedecl production is
 * detected.
 * <p>
 * The DTDParser builds internal structures for element declarations, attribute
 * list declarations, entity declarations, and notation declarations. These
 * structures are only created when a DOCTYPE is present, avoiding memory
 * allocation for the majority of documents that don't include a DTD.
 * <p>
 * The parser delegates to SAX2 handlers (DTDHandler, LexicalHandler) to
 * report DTD events as they are encountered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DTDParser implements TokenConsumer {

    /**
     * Parsing states within the DOCTYPE declaration.
     */
    private enum State {
        INITIAL,                // Just started, expecting DOCTYPE name
        AFTER_NAME,             // Read name, expecting SYSTEM/PUBLIC/[/GT
        AFTER_SYSTEM_PUBLIC,    // Read SYSTEM/PUBLIC, expecting quoted string
        AFTER_PUBLIC_ID,        // Read public ID, expecting system ID
        AFTER_EXTERNAL_ID,      // Read external ID, expecting [/GT
        IN_INTERNAL_SUBSET,     // Inside [ ... ], processing declarations
        AFTER_INTERNAL_SUBSET,  // Read ], expecting GT
        DONE                    // Read final GT, done processing
    }

    private State state = State.INITIAL;
    private Locator locator;
    private DTDHandler dtdHandler;
    private LexicalHandler lexicalHandler;
    private ErrorHandler errorHandler;

    /**
     * The DOCTYPE name (root element name).
     */
    private String doctypeName;

    /**
     * The public identifier for the external DTD subset.
     */
    private String publicId;

    /**
     * The system identifier for the external DTD subset.
     */
    private String systemId;

    /**
     * Element declarations: element name -> ElementDeclaration.
     * Uses HashMap for O(1) lookup during validation.
     */
    private Map<String, ElementDeclaration> elementDecls;

    /**
     * Attribute declarations: "elementName:attributeName" -> AttributeDeclaration.
     * Composite key allows efficient lookup of specific attribute for element.
     */
    private Map<String, AttributeDeclaration> attributeDecls;

    /**
     * Depth tracking for nested structures (e.g., conditional sections).
     */
    private int nestingDepth = 0;

    /**
     * Constructs a new DTDParser.
     */
    public DTDParser() {
    }

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
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
     * Sets the error handler for reporting errors.
     * @param handler the error handler
     */
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    /**
     * Checks if this parser can receive more tokens.
     * <p>
     * This method is called by the XMLParser before delegating each token.
     * It returns true while the DTDParser is still processing tokens inside
     * the doctypedecl production, and false when the final GT is detected.
     * <p>
     * When this returns false, the XMLParser knows to stop delegating tokens
     * to the DTDParser and resume normal parsing.
     *
     * @param token the token that would be received
     * @return true if this parser can receive the token, false otherwise
     */
    public boolean canReceive(Token token) {
        return state != State.DONE;
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        if (state == State.DONE) {
            throw new IllegalStateException("DTDParser has finished processing");
        }

        switch (state) {
            case INITIAL:
                handleInitial(token, data);
                break;

            case AFTER_NAME:
                handleAfterName(token, data);
                break;

            case AFTER_SYSTEM_PUBLIC:
                handleAfterSystemPublic(token, data);
                break;

            case AFTER_PUBLIC_ID:
                handleAfterPublicId(token, data);
                break;

            case AFTER_EXTERNAL_ID:
                handleAfterExternalId(token, data);
                break;

            case IN_INTERNAL_SUBSET:
                handleInInternalSubset(token, data);
                break;

            case AFTER_INTERNAL_SUBSET:
                handleAfterInternalSubset(token, data);
                break;
        }
    }

    /**
     * Handles tokens in the INITIAL state (expecting DOCTYPE name).
     */
    private void handleInitial(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace before name, ignore
                break;

            case NAME:
                // This is the DOCTYPE name (root element name)
                doctypeName = extractString(data);
                state = State.AFTER_NAME;
                break;

            default:
                throw new SAXException("Expected name after <!DOCTYPE, got: " + token);
        }
    }

    /**
     * Handles tokens after reading the DOCTYPE name.
     */
    private void handleAfterName(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case SYSTEM:
                // SYSTEM external ID
                state = State.AFTER_SYSTEM_PUBLIC;
                break;

            case PUBLIC:
                // PUBLIC external ID
                state = State.AFTER_SYSTEM_PUBLIC;
                break;

            case OPEN_BRACKET:
                // Internal subset starts
                state = State.IN_INTERNAL_SUBSET;
                nestingDepth = 1;
                // Report start of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                break;

            case GT:
                // End of DOCTYPE (no external ID, no internal subset)
                state = State.DONE;
                // Report start and end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXException("Unexpected token after DOCTYPE name: " + token);
        }
    }

    /**
     * Handles tokens after SYSTEM or PUBLIC keyword.
     */
    private void handleAfterSystemPublic(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case QUOT:
            case APOS:
                // Start of quoted string, ignore quote
                break;

            case CDATA:
                // This is the ID string
                if (publicId == null) {
                    // First string after PUBLIC is the public ID
                    publicId = extractString(data);
                    state = State.AFTER_PUBLIC_ID;
                } else {
                    // Second string is the system ID
                    systemId = extractString(data);
                    state = State.AFTER_EXTERNAL_ID;
                }
                break;

            default:
                throw new SAXException("Expected quoted string for external ID, got: " + token);
        }
    }

    /**
     * Handles tokens after reading the public ID.
     */
    private void handleAfterPublicId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case QUOT:
            case APOS:
                // Quote closing previous string or opening next, ignore
                break;

            case CDATA:
                // This is the system ID
                systemId = extractString(data);
                state = State.AFTER_EXTERNAL_ID;
                break;

            default:
                throw new SAXException("Expected system ID after public ID, got: " + token);
        }
    }

    /**
     * Handles tokens after reading the external ID.
     */
    private void handleAfterExternalId(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case QUOT:
            case APOS:
                // Quote closing system ID, ignore
                break;

            case OPEN_BRACKET:
                // Internal subset starts
                state = State.IN_INTERNAL_SUBSET;
                nestingDepth = 1;
                // Report start of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                }
                break;

            case GT:
                // End of DOCTYPE (external ID, no internal subset)
                state = State.DONE;
                // Report start and end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.startDTD(doctypeName, publicId, systemId);
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXException("Unexpected token after external ID: " + token);
        }
    }

    /**
     * Handles tokens within the internal subset (between [ and ]).
     */
    private void handleInInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case CLOSE_BRACKET:
                // End of internal subset
                nestingDepth--;
                if (nestingDepth == 0) {
                    state = State.AFTER_INTERNAL_SUBSET;
                }
                break;

            case OPEN_BRACKET:
                // Nested bracket (e.g., in conditional sections)
                nestingDepth++;
                break;

            case START_ELEMENTDECL:
                // TODO: Handle element declarations
                break;

            case START_ATTLISTDECL:
                // TODO: Handle attribute list declarations
                break;

            case START_ENTITYDECL:
                // TODO: Handle entity declarations
                break;

            case START_NOTATIONDECL:
                // TODO: Handle notation declarations
                break;

            case START_COMMENT:
                // TODO: Handle comments
                break;

            case START_PI:
                // TODO: Handle processing instructions
                break;

            case START_CONDITIONAL:
                // TODO: Handle conditional sections
                break;

            default:
                // Other tokens within declarations, ignore for now
                break;
        }
    }

    /**
     * Handles tokens after the internal subset closing bracket.
     */
    private void handleAfterInternalSubset(Token token, CharBuffer data) throws SAXException {
        switch (token) {
            case S:
                // Whitespace, ignore
                break;

            case GT:
                // End of DOCTYPE
                state = State.DONE;
                // Report end of DTD
                if (lexicalHandler != null) {
                    lexicalHandler.endDTD();
                }
                break;

            default:
                throw new SAXException("Expected GT after internal subset, got: " + token);
        }
    }

    /**
     * Extracts a string from a CharBuffer.
     * @param buffer the buffer containing the string
     * @return the extracted string
     */
    private String extractString(CharBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            sb.append(buffer.get());
        }
        return sb.toString();
    }

    /**
     * Gets the DOCTYPE name (root element name).
     * @return the DOCTYPE name, or null if not yet parsed
     */
    public String getDoctypeName() {
        return doctypeName;
    }

    /**
     * Gets the public identifier for the external DTD subset.
     * @return the public ID, or null if not specified
     */
    public String getPublicId() {
        return publicId;
    }

    /**
     * Gets the system identifier for the external DTD subset.
     * @return the system ID, or null if not specified
     */
    public String getSystemId() {
        return systemId;
    }

    /**
     * Gets the element declaration for a given element name.
     *
     * @param elementName the element name
     * @return the ElementDeclaration, or null if not declared
     */
    public ElementDeclaration getElementDeclaration(String elementName) {
        return elementDecls != null ? elementDecls.get(elementName) : null;
    }

    /**
     * Gets the attribute declaration for a specific attribute on an element.
     *
     * @param elementName the element name
     * @param attributeName the attribute name
     * @return the AttributeDeclaration, or null if not declared
     */
    public AttributeDeclaration getAttributeDeclaration(String elementName, String attributeName) {
        if (attributeDecls == null) {
            return null;
        }
        String key = elementName + ":" + attributeName;
        return attributeDecls.get(key);
    }

    /**
     * Stores an element declaration.
     * Called internally when parsing <!ELEMENT declarations.
     *
     * @param decl the element declaration to store
     */
    private void addElementDeclaration(ElementDeclaration decl) {
        if (elementDecls == null) {
            elementDecls = new HashMap<>();
        }
        elementDecls.put(decl.name, decl);
    }

    /**
     * Stores an attribute declaration.
     * Called internally when parsing <!ATTLIST declarations.
     *
     * @param elementName the element this attribute belongs to
     * @param decl the attribute declaration to store
     */
    private void addAttributeDeclaration(String elementName, AttributeDeclaration decl) {
        if (attributeDecls == null) {
            attributeDecls = new HashMap<>();
        }
        String key = elementName + ":" + decl.name;
        attributeDecls.put(key, decl);
    }

}

