/*
 * ElementDeclParser.java
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
import java.util.ArrayDeque;
import java.util.Deque;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!ELEMENT declarations within a DTD.
 * 
 * <p>This class is responsible for parsing a single &lt;!ELEMENT declaration
 * from start to finish. It maintains its own state machine and temporary
 * structures for building the content model. When parsing is complete (&gt;
 * token received), it adds the completed {@link ElementDeclaration} to the
 * parent {@link DTDParser}'s {@code elementDecls} map.
 * 
 * <p>Parsing states:
 * <ul>
 * <li>EXPECT_NAME: Waiting for element name</li>
 * <li>AFTER_NAME: After element name, expecting whitespace</li>
 * <li>EXPECT_CONTENTSPEC: Expecting EMPTY, ANY, or content model</li>
 * <li>IN_CONTENT_MODEL: Inside parenthesized content model</li>
 * <li>AFTER_CONTENTSPEC: After content spec, expecting whitespace or &gt;</li>
 * <li>EXPECT_GT: Expecting &gt; to close declaration</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ElementDeclParser {
    
    /**
     * Sub-states for parsing &lt;!ELEMENT declarations.
     * Tracks position within the element declaration to enforce well-formedness.
     */
    private enum State {
        EXPECT_NAME,           // After <!ELEMENT, expecting element name
        AFTER_NAME,            // After element name, expecting whitespace
        EXPECT_CONTENTSPEC,    // After whitespace, expecting EMPTY|ANY|(
        IN_CONTENT_MODEL,      // Inside ( ... ), building content model
        AFTER_CONTENTSPEC,     // After content spec, expecting whitespace or >
        EXPECT_GT              // Expecting > to close declaration
    }
    
    /** Reference to parent DTD parser */
    private final DTDParser dtdParser;
    
    /** Locator for error reporting */
    private final Locator locator;
    
    /** Current state */
    private State state = State.EXPECT_NAME;
    
    /** Element declaration being built */
    private final ElementDeclaration elementDecl = new ElementDeclaration();
    
    /** Stack for building nested content models */
    private final Deque<ElementDeclaration.ContentModel> contentModelStack = new ArrayDeque<>();
    
    /** Track parenthesis nesting depth in content models */
    private int contentModelDepth = 0;
    
    /** Track whitespace before occurrence indicators */
    private boolean sawWhitespaceInContentModel = false;
    
    /**
     * Creates an element declaration parser.
     * 
     * @param dtdParser the parent DTD parser
     * @param locator the locator for error reporting
     */
    ElementDeclParser(DTDParser dtdParser, Locator locator) {
        this.dtdParser = dtdParser;
        this.locator = locator;
    }
    
    /**
     * Processes a token for this element declaration.
     * 
     * @param token the token type
     * @param data the character data for the token
     * @return true if declaration is complete (GT received), false otherwise
     * @throws SAXException if parsing fails
     */
    boolean receive(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_NAME:
                return handleExpectName(token, data);
                
            case AFTER_NAME:
                return handleAfterName(token, data);
                
            case EXPECT_CONTENTSPEC:
                return handleExpectContentspec(token, data);
                
            case IN_CONTENT_MODEL:
                return handleInContentModel(token, data);
                
            case AFTER_CONTENTSPEC:
                return handleAfterContentspec(token, data);
                
            case EXPECT_GT:
                return handleExpectGT(token, data);
                
            default:
                throw new SAXParseException("Unknown state in ElementDeclParser: " + state, locator);
        }
    }
    
    private boolean handleExpectName(Token token, CharBuffer data) throws SAXException {
        // Must see NAME token for element name (whitespace allowed first)
        if (token == Token.NAME) {
            elementDecl.name = data.toString();
            state = State.AFTER_NAME;
        } else if (token == Token.S) {
            // Skip whitespace before element name
        } else {
            throw new SAXParseException("Expected element name after <!ELEMENT, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleAfterName(Token token, CharBuffer data) throws SAXException {
        // Must see whitespace after element name
        if (token == Token.S) {
            state = State.EXPECT_CONTENTSPEC;
        } else {
            throw new SAXParseException("Expected whitespace after element name in <!ELEMENT, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleExpectContentspec(Token token, CharBuffer data) throws SAXException {
        // Expecting EMPTY | ANY | content model
        switch (token) {
            case S:
                // Skip extra whitespace
                break;
                
            case EMPTY:
                elementDecl.contentType = ElementDeclaration.ContentType.EMPTY;
                state = State.AFTER_CONTENTSPEC;
                break;
                
            case ANY:
                elementDecl.contentType = ElementDeclaration.ContentType.ANY;
                state = State.AFTER_CONTENTSPEC;
                break;
                
            case OPEN_PAREN:
                // Start content model group
                ElementDeclaration.ContentModel group = new ElementDeclaration.ContentModel();
                // Don't know if it's SEQUENCE or CHOICE yet - will determine from first separator
                contentModelStack.push(group);
                contentModelDepth = 1;
                state = State.IN_CONTENT_MODEL;
                break;
                
            case PARAMETERENTITYREF:
                // Parameter entity expansion in element content specification
                // Example: <!ELEMENT doc %content-model;>
                String refName = data.toString();
                dtdParser.expandParameterEntityInline(refName);
                // After expansion, stay in EXPECT_CONTENTSPEC to process the expanded tokens
                break;
                
            default:
                throw new SAXParseException("Expected content specification (EMPTY, ANY, or '(') in <!ELEMENT, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleInContentModel(Token token, CharBuffer data) throws SAXException {
        // Inside ( ... ), building content model
        switch (token) {
            case S:
                // Whitespace in content model - track it for occurrence indicator validation
                sawWhitespaceInContentModel = true;
                break;
                
            case NAME:
                String nameStr = data.toString();
                // Handle PCDATA special case (tokenizer may emit as NAME)
                if (nameStr.equals("PCDATA")) {
                    // Create #PCDATA leaf node
                    ElementDeclaration.ContentModel leaf = new ElementDeclaration.ContentModel();
                    leaf.type = ElementDeclaration.ContentModel.NodeType.PCDATA;
                    leaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                    contentModelStack.peek().addChild(leaf);
                } else {
                    // Create element name leaf node
                    ElementDeclaration.ContentModel leaf = new ElementDeclaration.ContentModel();
                    leaf.type = ElementDeclaration.ContentModel.NodeType.ELEMENT;
                    leaf.elementName = nameStr;
                    leaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                    contentModelStack.peek().addChild(leaf);
                }
                // Reset whitespace flag after adding element
                sawWhitespaceInContentModel = false;
                break;
                
            case PCDATA:
                // Create #PCDATA leaf node
                ElementDeclaration.ContentModel pcdataLeaf = new ElementDeclaration.ContentModel();
                pcdataLeaf.type = ElementDeclaration.ContentModel.NodeType.PCDATA;
                pcdataLeaf.occurrence = ElementDeclaration.ContentModel.Occurrence.ONCE;
                contentModelStack.peek().addChild(pcdataLeaf);
                // Reset whitespace flag after adding PCDATA
                sawWhitespaceInContentModel = false;
                break;
                
            case OPEN_PAREN:
                // Nested group
                ElementDeclaration.ContentModel nestedGroup = new ElementDeclaration.ContentModel();
                contentModelStack.push(nestedGroup);
                contentModelDepth++;
                break;
                
            case CLOSE_PAREN:
                contentModelDepth--;
                if (contentModelDepth == 0) {
                    // Exited the top-level content model
                    ElementDeclaration.ContentModel root = contentModelStack.pop();
                    
                    // Validate that content model is not empty
                    if (root.children == null || root.children.isEmpty()) {
                        throw new SAXParseException("Empty content model () is not allowed in <!ELEMENT", locator);
                    }
                    
                    // Set default type if not already set (single element case like "(a)")
                    if (root.type == null) {
                        // Single child or no separator - treat as sequence
                        root.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                    }
                    
                    elementDecl.contentModel = root;
                    
                    // Determine content type (validation happens later in finish())
                    if (containsPCDATA(root)) {
                        elementDecl.contentType = ElementDeclaration.ContentType.MIXED;
                    } else {
                        elementDecl.contentType = ElementDeclaration.ContentType.ELEMENT;
                    }
                    
                    // Reset whitespace flag after closing paren
                    sawWhitespaceInContentModel = false;
                    state = State.AFTER_CONTENTSPEC;
                } else if (contentModelDepth < 0) {
                    throw new SAXParseException("Unmatched closing parenthesis in <!ELEMENT content model", locator);
                } else {
                    // Finished a nested group - pop it and add to parent
                    ElementDeclaration.ContentModel completed = contentModelStack.pop();
                    
                    // Set default type if not already set (single element case)
                    if (completed.type == null) {
                        completed.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                    }
                    
                    contentModelStack.peek().addChild(completed);
                    // Reset whitespace flag after closing paren
                    sawWhitespaceInContentModel = false;
                }
                break;

            case PIPE:
                // Set group type to CHOICE if not already set
                ElementDeclaration.ContentModel current = contentModelStack.peek();
                if (current.type == null) {
                    current.type = ElementDeclaration.ContentModel.NodeType.CHOICE;
                } else if (current.type != ElementDeclaration.ContentModel.NodeType.CHOICE) {
                    throw new SAXParseException("Cannot mix ',' and '|' at same level in content model", locator);
                }
                break;
                
            case COMMA:
                // Set group type to SEQUENCE if not already set
                ElementDeclaration.ContentModel currentSeq = contentModelStack.peek();
                if (currentSeq.type == null) {
                    currentSeq.type = ElementDeclaration.ContentModel.NodeType.SEQUENCE;
                } else if (currentSeq.type != ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                    throw new SAXParseException("Cannot mix ',' and '|' at same level in content model", locator);
                }
                break;
                
            case QUERY:
            case PLUS:
            case STAR:
                // Occurrence indicator must immediately follow element or ) with no whitespace
                if (sawWhitespaceInContentModel) {
                    throw new SAXParseException(
                        "Whitespace not allowed before occurrence indicator in content model", locator);
                }
                
                // Apply occurrence indicator to last child added
                ElementDeclaration.ContentModel parent = contentModelStack.peek();
                if (parent.children != null && !parent.children.isEmpty()) {
                    ElementDeclaration.ContentModel lastChild = parent.children.get(parent.children.size() - 1);
                    
                    // Check for double occurrence indicator (e.g., *?)
                    if (lastChild.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE) {
                        throw new SAXParseException(
                            "Multiple occurrence indicators not allowed in content model", locator);
                    }
                    
                    lastChild.occurrence = token == Token.QUERY ? ElementDeclaration.ContentModel.Occurrence.OPTIONAL :
                                          token == Token.PLUS ? ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE :
                                          ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                }
                break;
                
            case HASH:
                // Hash by itself (followed by PCDATA token or NAME("PCDATA"))
                // Just ignore - will be handled by PCDATA or NAME token
                break;
                
            default:
                throw new SAXParseException("Unexpected token in <!ELEMENT content model: " + token, locator);
        }
        return false;
    }
    
    private boolean handleAfterContentspec(Token token, CharBuffer data) throws SAXException {
        // After content spec, expecting optional whitespace then GT
        switch (token) {
            case S:
                // Optional whitespace before GT
                state = State.EXPECT_GT;
                break;
                
            case GT:
                // Save and return true (complete)
                finish();
                return true;
                
            case QUERY:
            case PLUS:
            case STAR:
                // Occurrence indicator after content spec - apply to root
                if (elementDecl.contentModel != null) {
                    elementDecl.contentModel.occurrence = 
                        token == Token.QUERY ? ElementDeclaration.ContentModel.Occurrence.OPTIONAL :
                        token == Token.PLUS ? ElementDeclaration.ContentModel.Occurrence.ONE_OR_MORE :
                        ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE;
                }
                // Stay in AFTER_CONTENTSPEC to allow optional whitespace before GT
                break;
                
            default:
                throw new SAXParseException("Expected '>' to close <!ELEMENT declaration, got: " + token, locator);
        }
        return false;
    }
    
    private boolean handleExpectGT(Token token, CharBuffer data) throws SAXException {
        // Must see GT to close declaration
        if (token == Token.GT) {
            finish();
            return true;
        } else if (token == Token.S) {
            // Allow extra whitespace
        } else {
            throw new SAXParseException("Expected '>' to close <!ELEMENT declaration, got: " + token, locator);
        }
        return false;
    }
    
    /**
     * Helper to check if a content model contains #PCDATA.
     */
    private boolean containsPCDATA(ElementDeclaration.ContentModel model) {
        if (model.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
            return true;
        }
        if (model.children != null) {
            for (ElementDeclaration.ContentModel child : model.children) {
                if (containsPCDATA(child)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Validates mixed content constraints per XML spec:
     * 1. If content model contains #PCDATA, it must be a CHOICE group at top level (or just #PCDATA alone)
     * 2. #PCDATA must be first in the choice
     * 3. The occurrence indicator on #PCDATA must be * or none (not + or ?)
     * 4. No nested groups allowed in mixed content
     */
    private void validateMixedContent(ElementDeclaration.ContentModel model) throws SAXException {
        // Special case: (#PCDATA) alone is valid - could be SEQUENCE with single PCDATA child
        if (model.children != null && model.children.size() == 1) {
            ElementDeclaration.ContentModel child = model.children.get(0);
            if (child.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
                // This is (#PCDATA) which is valid
                // Check occurrence on the PCDATA itself
                if (child.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    child.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    throw new SAXParseException(
                        "Mixed content with #PCDATA can only have * or no occurrence indicator", locator);
                }
                // Check occurrence on the group
                if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                    model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                    throw new SAXParseException(
                        "Mixed content can only have * or no occurrence indicator", locator);
                }
                return; // Valid
            }
            // If single child that is a group, check for unnecessary nesting
            if (child.type == ElementDeclaration.ContentModel.NodeType.CHOICE ||
                child.type == ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                if (child.children != null && child.children.size() == 1 &&
                    child.children.get(0).type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
                    // This is ((#PCDATA)) - unnecessary nesting
                    throw new SAXParseException(
                        "Unnecessary nested parentheses in mixed content", locator);
                }
            }
        }
        
        // If the model itself is just PCDATA (not wrapped), check occurrence
        if (model.type == ElementDeclaration.ContentModel.NodeType.PCDATA) {
            if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                throw new SAXParseException(
                    "Mixed content with #PCDATA can only have * or no occurrence indicator", locator);
            }
            return; // Valid
        }
        
        // Mixed content with multiple elements must be a CHOICE at top level
        if (model.type != ElementDeclaration.ContentModel.NodeType.CHOICE) {
            // Only error if there are multiple children or if first child is not PCDATA alone
            if (model.children != null && model.children.size() > 1) {
                throw new SAXParseException(
                    "Mixed content must use | (choice), not , (sequence)", locator);
            }
        }
        
        // If CHOICE, #PCDATA must be first child
        if (model.type == ElementDeclaration.ContentModel.NodeType.CHOICE) {
            if (model.children != null && !model.children.isEmpty()) {
                ElementDeclaration.ContentModel first = model.children.get(0);
                if (first.type != ElementDeclaration.ContentModel.NodeType.PCDATA) {
                    throw new SAXParseException(
                        "#PCDATA must be first in mixed content choice", locator);
                }
            }
            
            // Check for nested groups (not allowed in mixed content)
            if (model.children != null) {
                for (ElementDeclaration.ContentModel child : model.children) {
                    if (child.type == ElementDeclaration.ContentModel.NodeType.CHOICE ||
                        child.type == ElementDeclaration.ContentModel.NodeType.SEQUENCE) {
                        throw new SAXParseException(
                            "Nested groups not allowed in mixed content", locator);
                    }
                    // Element names in mixed content cannot have occurrence indicators
                    if (child.type == ElementDeclaration.ContentModel.NodeType.ELEMENT &&
                        child.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE) {
                        throw new SAXParseException(
                            "Element names in mixed content cannot have occurrence indicators", locator);
                    }
                }
            }
            
            // Occurrence indicator on choice must be * or none
            if (model.occurrence != ElementDeclaration.ContentModel.Occurrence.ONCE &&
                model.occurrence != ElementDeclaration.ContentModel.Occurrence.ZERO_OR_MORE) {
                throw new SAXParseException(
                    "Mixed content can only have * or no occurrence indicator", locator);
            }
        }
    }
    
    /**
     * Completes parsing and adds the element declaration to the parent DTD parser.
     * Called when the &lt;!ELEMENT declaration is complete (&gt; encountered).
     */
    private void finish() throws SAXException {
        if (elementDecl.name == null) {
            throw new SAXParseException("No element name in <!ELEMENT declaration", locator);
        }
        
        if (elementDecl.contentType == null) {
            throw new SAXParseException("No content specification in <!ELEMENT declaration", locator);
        }
        
        // Validate mixed content constraints (after occurrence indicators have been applied)
        if (elementDecl.contentType == ElementDeclaration.ContentType.MIXED &&
            elementDecl.contentModel != null) {
            validateMixedContent(elementDecl.contentModel);
        }
        
        // Add to parent DTD parser's element declarations
        dtdParser.addElementDeclaration(elementDecl);
    }
    
}

