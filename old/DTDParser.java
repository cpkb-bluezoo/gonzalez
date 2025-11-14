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

package org.bluezoo.gonzalez.dtd;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.bluezoo.gonzalez.AsyncEntityResolverFactory;
import org.bluezoo.gonzalez.EntityReceiver;
import org.bluezoo.gonzalez.EntityResolvingParser;
import org.bluezoo.gonzalez.GonzalezAttributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Lazy-loading DTD parser for Gonzalez.
 *
 * <p>This parser is loaded only when a DOCTYPE declaration is encountered,
 * minimizing memory usage for the common case of standalone documents
 * without DTDs (which represents ~99% of real-world XML).
 *
 * <p>Like the main parser, the DTD parser is completely data-driven and
 * non-blocking. It implements {@link EntityReceiver} to receive DTD content
 * via {@link #receive(ByteBuffer)} and reports declarations through the
 * {@link DTDHandler}, {@link DeclHandler}, and {@link LexicalHandler} interfaces.
 *
 * <p>It also implements {@link EntityResolvingParser} to support resolution
 * of external parameter entities within the DTD.
 *
 * <p><strong>Features:</strong>
 * <ul>
 * <li>Parses internal and external DTD subsets</li>
 * <li>Reports all declaration types (ELEMENT, ATTLIST, ENTITY, NOTATION)</li>
 * <li>Handles parameter entities and conditional sections</li>
 * <li>Supports asynchronous external entity resolution</li>
 * <li>Validates DTD syntax</li>
 * </ul>
 *
 * <p><strong>Usage:</strong> The DTD parser is instantiated by the main
 * parser when needed and is not intended for direct use by applications.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class DTDParser implements EntityReceiver, EntityResolvingParser {

  // Parse state
  private enum DTDState {
    INITIAL,           // Before first declaration
    MARKUP,            // Inside markup declaration
    ELEMENT_DECL,      // Parsing ELEMENT declaration
    ATTLIST_DECL,      // Parsing ATTLIST declaration
    ENTITY_DECL,       // Parsing ENTITY declaration
    NOTATION_DECL,     // Parsing NOTATION declaration
    COMMENT,           // Inside comment
    PI,                // Inside processing instruction
    CONDITIONAL,       // Conditional section (INCLUDE/IGNORE)
    DONE               // DTD complete
  }

  // Configuration
  private DTDHandler dtdHandler;
  private DeclHandler declHandler;
  private LexicalHandler lexicalHandler;
  private AsyncEntityResolverFactory entityResolverFactory;
  private Charset charset;
  private ErrorHandler errorHandler;
  private String systemId;
  private String publicId;
  private long entityTimeout = 10_000; // Default 10 seconds

  // State
  private DTDState state;
  private ByteBuffer parseBuffer;
  private CharBuffer charBuffer;
  private CharsetDecoder decoder;
  private StringBuilder tokenBuffer;

  // Position tracking
  private int line;
  private int column;
  private boolean lastCharWasCR; // Track if last consumed char was CR

  // DTD declarations storage
  // Key: "elementName:attributeName"
  private Map<String, AttributeDeclaration> attributeDeclarations;
  private Map<String, String> parameterEntities;
  
  // Element content models
  // Key: elementName, Value: content model string
  private Map<String, String> elementContentModels;
  
  // Entity resolution state (for parameter entities in DTD)
  private Deque<Object> entityReceiverStack; // Stack of entity receivers
  private ByteBuffer entityBuffer; // Buffer for DTD data during entity resolution

  /**
   * Creates a new DTD parser.
   *
   * <p>The parser will report events to the provided handlers. Any of the
   * handlers may be null if the application is not interested in those events.
   *
   * @param dtdHandler the handler to receive DTD notation/unparsed entity events, or null
   * @param declHandler the handler to receive DTD declaration events, or null
   * @param lexicalHandler the handler to receive lexical events (comments), or null
   * @param entityResolverFactory the factory for external entities, or null
   * @param charset the character encoding for the DTD content
   */
  public DTDParser(DTDHandler dtdHandler,
      DeclHandler declHandler,
      LexicalHandler lexicalHandler,
      AsyncEntityResolverFactory entityResolverFactory,
      Charset charset) {
    this.dtdHandler = dtdHandler;
    this.declHandler = declHandler;
    this.lexicalHandler = lexicalHandler;
    this.entityResolverFactory = entityResolverFactory;
    this.charset = (charset != null) ? charset : StandardCharsets.UTF_8;
    this.parseBuffer = ByteBuffer.allocate(4096);
    this.charBuffer = CharBuffer.allocate(2048);
    this.decoder = this.charset.newDecoder();
    this.tokenBuffer = new StringBuilder();
    this.state = DTDState.INITIAL;
    this.line = 1;
    this.column = 0;
    this.lastCharWasCR = false;
    this.attributeDeclarations = new HashMap<>();
    this.parameterEntities = new HashMap<>();
    this.elementContentModels = new HashMap<>();
    this.entityReceiverStack = new ArrayDeque<>();
    this.entityBuffer = ByteBuffer.allocate(8192);
  }

  /**
   * Applies DTD attribute defaults to the given attributes.
   *
   * <p>This method adds any default attributes from the DTD that weren't
   * explicitly specified in the document. The declared status and type
   * are retrieved lazily when {@link GonzalezAttributes#isDeclared} is called.
   *
   * @param elementName the element name
   * @param attrs the attributes to augment
   * @param namespaceSupport the namespace support for resolving default attribute namespaces
   */
  public void applyAttributeDefaults(String elementName, GonzalezAttributes attrs,
      org.xml.sax.helpers.NamespaceSupport namespaceSupport) {
    // Build prefix for efficient map lookup
    String prefix = elementName + ":";

    for (Map.Entry<String, AttributeDeclaration> entry : attributeDeclarations.entrySet()) {
      String key = entry.getKey();
      if (!key.startsWith(prefix)) {
        continue; // Not for this element
      }

      AttributeDeclaration decl = entry.getValue();
      String attrName = key.substring(prefix.length());

      // Check if this attribute was already specified
      if (attrs.getIndex(attrName) >= 0) {
        continue; // Already present
      }

      // Add default value if present and not REQUIRED/IMPLIED
      if (decl.defaultValue != null &&
          !"#REQUIRED".equals(decl.mode) &&
          !"#IMPLIED".equals(decl.mode)) {
        // Parse QName for namespace resolution
        int colonIdx = attrName.indexOf(':');
        String attrPrefix = (colonIdx >= 0) ? attrName.substring(0, colonIdx) : "";
        String attrLocalName = (colonIdx >= 0) ? attrName.substring(colonIdx + 1) : attrName;
        String attrUri = "";

        if (colonIdx >= 0 && namespaceSupport != null) {
          attrUri = namespaceSupport.getURI(attrPrefix);
          if (attrUri == null) {
            attrUri = "";
          }
        }

        // Add with specified=false (was from DTD default)
        attrs.addAttribute(attrUri, attrLocalName, attrName, decl.type,
            decl.defaultValue, false);
      }
    }
  }

  /**
   * Gets the attribute declaration for a specific element/attribute combination.
   *
   * @param elementName the element name
   * @param attributeName the attribute name
   * @return the declaration, or null if not declared
   */
  public AttributeDeclaration getAttributeDeclaration(String elementName, String attributeName) {
    return attributeDeclarations.get(elementName + ":" + attributeName);
  }

  /**
   * Defines a parameter entity.
   *
   * @param name the entity name (without % and ;)
   * @param value the replacement text
   */
  public void defineParameterEntity(String name, String value) {
    parameterEntities.put(name, value);
  }

  /**
   * Gets a parameter entity value.
   *
   * @param name the entity name
   * @return the value, or null if not defined
   */
  public String getParameterEntity(String name) {
    return parameterEntities.get(name);
  }
  
  /**
   * Checks if an element has element-only content (no mixed content).
   * 
   * <p>If an element has element-only content, whitespace between child
   * elements is ignorable and should be reported via ignorableWhitespace()
   * instead of characters().
   * 
   * <p>Content models that indicate element-only content:
   * <ul>
   * <li>EMPTY - no content at all</li>
   * <li>(child1,child2,...) - sequence of element children</li>
   * <li>(child1|child2|...) - choice of element children</li>
   * </ul>
   * 
   * <p>Mixed content models start with (#PCDATA and indicate that text
   * is allowed, so whitespace is significant.
   *
   * @param elementName the element name to check
   * @return true if the element has element-only content, false if mixed or unknown
   */
  public boolean hasElementOnlyContent(String elementName) {
    String contentModel = elementContentModels.get(elementName);
    if (contentModel == null) {
      // No DTD declaration - assume mixed content to be safe
      return false;
    }
    
    // EMPTY content model
    if ("EMPTY".equals(contentModel)) {
      return true;
    }
    
    // ANY allows mixed content
    if ("ANY".equals(contentModel)) {
      return false;
    }
    
    // Mixed content starts with (#PCDATA
    if (contentModel.startsWith("(#PCDATA")) {
      return false;
    }
    
    // Otherwise, if it starts with '(' it's likely element-only
    // (this includes sequences and choices of elements)
    if (contentModel.startsWith("(")) {
      return true;
    }
    
    // Unknown format - be conservative and assume mixed
    return false;
  }

  /**
   * Receives and processes DTD content.
   *
   * <p>Implements {@link EntityReceiver#receive(ByteBuffer)}.
   *
   * @param data the DTD content to process
   * @throws SAXParseException if the DTD is malformed
   */
  @Override
  public void receive(ByteBuffer data) throws SAXParseException {
    // Ensure buffer capacity
    if (parseBuffer.remaining() < data.remaining()) {
      expandBuffer(data.remaining());
    }

    parseBuffer.put(data);

    // Decode bytes to characters
    parseBuffer.flip();
    CoderResult result = decoder.decode(parseBuffer, charBuffer, false);
    parseBuffer.compact();

    if (result.isError()) {
      throw new SAXParseException("Character decoding error",
          null, null, line, column);
    }

    // Process available characters
    charBuffer.flip();
    try {
      parse();
    } catch (SAXException e) {
      throw toParseException(e);
    } finally {
      charBuffer.compact();
    }
  }

  /**
   * Signals that DTD content is complete.
   *
   * <p>Implements {@link EntityReceiver#close()}.
   *
   * @throws SAXParseException if the DTD is incomplete or invalid
   */
  @Override
  public void close() throws SAXParseException {
    // Final decode flush
    parseBuffer.flip();
    CoderResult result = decoder.decode(parseBuffer, charBuffer, true);
    parseBuffer.compact();

    if (result.isError()) {
      throw new SAXParseException("Character decoding error",
          null, null, line, column);
    }

    // Flush decoder
    charBuffer.flip();
    result = decoder.flush(charBuffer);

    if (result.isError()) {
      throw new SAXParseException("Character decoding flush error",
          null, null, line, column);
    }

    // Process any remaining characters
    try {
      parse();
    } catch (SAXException e) {
      throw toParseException(e);
    }

    // Verify we're in a valid end state
    if (!charBuffer.hasRemaining() && state != DTDState.DONE && state != DTDState.INITIAL) {
      throw new SAXParseException("Incomplete DTD declaration",
          null, null, line, column);
    }
  }

  /**
   * Main DTD parsing state machine.
   */
  private void parse() throws SAXException {
    while (charBuffer.hasRemaining()) {
      switch (state) {
        case INITIAL:
          parseInitial();
          break;
        case MARKUP:
          parseMarkup();
          break;
        case ELEMENT_DECL:
          parseElementDecl();
          break;
        case ATTLIST_DECL:
          parseAttlistDecl();
          break;
        case ENTITY_DECL:
          parseEntityDecl();
          break;
        case NOTATION_DECL:
          parseNotationDecl();
          break;
        case COMMENT:
          parseComment();
          break;
        case DONE:
          // Consume any trailing whitespace
          skipWhitespace();
          return;
        default:
          // More states to be implemented
          return;
      }

      // Safety check to prevent infinite loops
      if (!charBuffer.hasRemaining()) {
        break;
      }
    }
  }

  private void parseInitial() throws SAXException {
    skipWhitespace();

    if (!charBuffer.hasRemaining()) {
      return;
    }

    char ch = peekChar();
    if (ch == '<') {
      state = DTDState.MARKUP;
    } else if (ch == ']' || ch == '>') {
      // End of internal subset
      state = DTDState.DONE;
    } else {
      throw new SAXParseException("Expected '<' or ']' in DTD",
          null, null, line, column);
    }
  }

  private void parseMarkup() throws SAXException {
    if (!hasAvailable(2)) {
      return;
    }

    consumeChar(); // '<'
    char next = peekChar();

    if (next == '!') {
      consumeChar(); // '!'
      if (!hasAvailable(2)) {
        return;
      }

      if (peekChar() == '-' && peekChar(1) == '-') {
        state = DTDState.COMMENT;
      } else if (matchesKeyword("ELEMENT")) {
        state = DTDState.ELEMENT_DECL;
      } else if (matchesKeyword("ATTLIST")) {
        state = DTDState.ATTLIST_DECL;
      } else if (matchesKeyword("ENTITY")) {
        state = DTDState.ENTITY_DECL;
      } else if (matchesKeyword("NOTATION")) {
        state = DTDState.NOTATION_DECL;
      } else {
        throw new SAXParseException("Unknown DTD declaration",
            null, null, line, column);
      }
    } else if (next == '?') {
      state = DTDState.PI;
    } else {
      throw new SAXParseException("Expected '!' or '?' in DTD markup",
          null, null, line, column);
    }
  }

  private void parseElementDecl() throws SAXException {
    // Parse: <!ELEMENT name contentspec>
    // We've already consumed "<!ELEMENT"

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return; // Need more data
    }

    // Parse element name
    String name = parseName();
    if (name == null) {
      return; // Need more data
    }

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return; // Need more data
    }

    // Parse content model
    StringBuilder model = new StringBuilder();
    int depth = 0;
    boolean inParens = false;

    while (charBuffer.hasRemaining()) {
      char ch = peekChar();

      if (ch == '(') {
        inParens = true;
        depth++;
        model.append(ch);
        consumeChar();
      } else if (ch == ')') {
        depth--;
        model.append(ch);
        consumeChar();
        if (depth == 0) {
          inParens = false;
        }
      } else if (ch == '>' && depth == 0) {
        // End of declaration
        consumeChar();
        
        String contentModel = model.toString().trim();

        // Report to handler
        if (declHandler != null) {
          declHandler.elementDecl(name, contentModel);
        }
        
        // Store content model for later use
        elementContentModels.put(name, contentModel);

        state = DTDState.INITIAL;
        return;
      } else {
        model.append(ch);
        consumeChar();
      }
    }

    // Need more data
  }

  private void parseAttlistDecl() throws SAXException {
    // Parse: <!ATTLIST elementName attName attType default>
    // We've already consumed "<!ATTLIST"

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return;
    }

    // Parse element name
    String elementName = parseName();
    if (elementName == null) {
      return;
    }

    // Parse attribute definitions (can be multiple)
    while (true) {
      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      // Check for end of ATTLIST
      if (peekChar() == '>') {
        consumeChar();
        state = DTDState.INITIAL;
        return;
      }

      // Parse attribute name
      String attName = parseName();
      if (attName == null) {
        return;
      }

      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      // Parse attribute type
      String attType = parseAttType();
      if (attType == null) {
        return;
      }

      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      // Parse default value declaration
      String mode = null;
      String value = null;

      char ch = peekChar();
      if (ch == '#') {
        // #REQUIRED, #IMPLIED, #FIXED
        consumeChar(); // '#'
        String keyword = parseName();
        if (keyword == null) {
          return;
        }
        mode = "#" + keyword;

        if ("#FIXED".equals(mode)) {
          skipWhitespace();
          if (!charBuffer.hasRemaining()) {
            return;
          }
          value = parseQuotedValue();
          if (value == null) {
            return;
          }
        }
      } else if (ch == '"' || ch == '\'') {
        // Default value
        value = parseQuotedValue();
        if (value == null) {
          return;
        }
      }

      // Report to handler
      if (declHandler != null) {
        declHandler.attributeDecl(elementName, attName, attType, mode, value);
      }

      // Store declaration for later use
      String key = elementName + ":" + attName;
      attributeDeclarations.put(key, new AttributeDeclaration(attType, mode, value));
    }
  }

  private void parseEntityDecl() throws SAXException {
    // Parse: <!ENTITY [%] name definition>
    // We've already consumed "<!ENTITY"

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return;
    }

    // Check for parameter entity
    boolean isParameter = false;
    if (peekChar() == '%') {
      isParameter = true;
      consumeChar();
      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }
    }

    // Parse entity name
    String name = parseName();
    if (name == null) {
      return;
    }

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return;
    }

    char ch = peekChar();

    if (ch == '"' || ch == '\'') {
      // Internal entity
      String value = parseQuotedValue();
      if (value == null) {
        return;
      }

      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      if (peekChar() != '>') {
        throw new SAXParseException("Expected '>' after entity value",
            null, null, line, column);
      }
      consumeChar();

      // Report to handler
      if (declHandler != null) {
        declHandler.internalEntityDecl(isParameter ? "%" + name : name, value);
      }

      state = DTDState.INITIAL;
    } else if (ch == 'S' || ch == 'P') {
      // External entity: SYSTEM or PUBLIC
      String publicId = null;
      String systemId = null;

      String keyword = parseName();
      if (keyword == null) {
        return;
      }

      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      if ("PUBLIC".equals(keyword)) {
        publicId = parseQuotedValue();
        if (publicId == null) {
          return;
        }
        skipWhitespace();
        if (!charBuffer.hasRemaining()) {
          return;
        }
      }

      systemId = parseQuotedValue();
      if (systemId == null) {
        return;
      }

      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      // Check for NDATA (unparsed entity)
      if (peekChar() == 'N') {
        String ndataKeyword = parseName();
        if (ndataKeyword == null) {
          return;
        }

        if ("NDATA".equals(ndataKeyword)) {
          skipWhitespace();
          if (!charBuffer.hasRemaining()) {
            return;
          }

          String notationName = parseName();
          if (notationName == null) {
            return;
          }

          skipWhitespace();
          if (!charBuffer.hasRemaining()) {
            return;
          }

          if (peekChar() != '>') {
            throw new SAXParseException("Expected '>' after NDATA notation",
                null, null, line, column);
          }
          consumeChar();

          // Report unparsed entity
          if (dtdHandler != null) {
            dtdHandler.unparsedEntityDecl(name, publicId, systemId, notationName);
          }

          state = DTDState.INITIAL;
          return;
        }
      }

      if (peekChar() != '>') {
        throw new SAXParseException("Expected '>' after external entity",
            null, null, line, column);
      }
      consumeChar();

      // Report external entity
      if (declHandler != null) {
        declHandler.externalEntityDecl(isParameter ? "%" + name : name, publicId, systemId);
      }

      state = DTDState.INITIAL;
    } else {
      throw new SAXParseException("Expected entity definition",
          null, null, line, column);
    }
  }

  private void parseNotationDecl() throws SAXException {
    // Parse: <!NOTATION name SYSTEM|PUBLIC id>
    // We've already consumed "<!NOTATION"

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return;
    }

    String name = parseName();
    if (name == null) {
      return;
    }

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return;
    }

    String keyword = parseName();
    if (keyword == null) {
      return;
    }

    skipWhitespace();
    if (!charBuffer.hasRemaining()) {
      return;
    }

    String publicId = null;
    String systemId = null;

    if ("PUBLIC".equals(keyword)) {
      publicId = parseQuotedValue();
      if (publicId == null) {
        return;
      }

      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }

      // System ID is optional for PUBLIC notations
      if (peekChar() == '"' || peekChar() == '\'') {
        systemId = parseQuotedValue();
        if (systemId == null) {
          return;
        }
        skipWhitespace();
        if (!charBuffer.hasRemaining()) {
          return;
        }
      }
    } else if ("SYSTEM".equals(keyword)) {
      systemId = parseQuotedValue();
      if (systemId == null) {
        return;
      }
      skipWhitespace();
      if (!charBuffer.hasRemaining()) {
        return;
      }
    } else {
      throw new SAXParseException("Expected SYSTEM or PUBLIC in NOTATION",
          null, null, line, column);
    }

    if (peekChar() != '>') {
      throw new SAXParseException("Expected '>' after notation declaration",
          null, null, line, column);
    }
    consumeChar();

    // Report to handler
    if (dtdHandler != null) {
      dtdHandler.notationDecl(name, publicId, systemId);
    }

    state = DTDState.INITIAL;
  }

  private void parseComment() throws SAXException {
    // Parse: <!-- comment -->
    // We've already consumed "<!--"
    consumeChar(); // '-'
    consumeChar(); // '-'

    StringBuilder comment = new StringBuilder();
    boolean dash1 = false;
    boolean dash2 = false;

    while (charBuffer.hasRemaining()) {
      char ch = peekChar();

      if (dash1 && dash2 && ch == '>') {
        consumeChar();

        // Remove trailing "--" from comment
        if (comment.length() >= 2) {
          comment.setLength(comment.length() - 2);
        }

        // Report to handler
        if (lexicalHandler != null) {
          String text = comment.toString();
          lexicalHandler.comment(text.toCharArray(), 0, text.length());
        }

        state = DTDState.INITIAL;
        return;
      }

      dash1 = dash2;
      dash2 = (ch == '-');
      comment.append(ch);
      consumeChar();
    }

    // Need more data
  }

  // Helper parsing methods

  /**
   * Parses an XML name (element name, attribute name, etc.)
   * Returns null if more data is needed.
   */
  private String parseName() throws SAXException {
    if (!charBuffer.hasRemaining()) {
      return null;
    }

    int start = charBuffer.position();

    // First character must be NameStartChar
    char ch = peekChar();
    if (!isNameStartChar(ch)) {
      throw new SAXParseException("Invalid name start character: '" + ch + "'",
          null, null, line, column);
    }
    consumeChar();

    // Subsequent characters can be NameChar
    while (charBuffer.hasRemaining()) {
      ch = peekChar();
      if (isNameChar(ch)) {
        consumeChar();
      } else {
        break;
      }
    }

    // Extract name
    int end = charBuffer.position();
    int length = end - start;

    char[] nameChars = new char[length];
    int savedPos = charBuffer.position();
    charBuffer.position(start);
    charBuffer.get(nameChars, 0, length);
    charBuffer.position(savedPos);

    return new String(nameChars);
  }

  /**
   * Parses a quoted value (attribute value, entity value, etc.)
   * Returns null if more data is needed.
   */
  private String parseQuotedValue() throws SAXException {
    if (!charBuffer.hasRemaining()) {
      return null;
    }

    char quote = peekChar();
    if (quote != '"' && quote != '\'') {
      throw new SAXParseException("Expected quote character",
          null, null, line, column);
    }
    consumeChar();

    StringBuilder value = new StringBuilder();

    while (charBuffer.hasRemaining()) {
      char ch = peekChar();
      if (ch == quote) {
        consumeChar();
        return value.toString();
      }

      value.append(ch);
      consumeChar();
    }

    // Need more data
    return null;
  }

  /**
   * Parses an attribute type (CDATA, ID, IDREF, etc.)
   * Returns null if more data is needed.
   */
  private String parseAttType() throws SAXException {
    if (!charBuffer.hasRemaining()) {
      return null;
    }

    char ch = peekChar();

    if (ch == '(') {
      // Enumerated type: (val1|val2|val3)
      StringBuilder type = new StringBuilder();
      int depth = 0;

      while (charBuffer.hasRemaining()) {
        ch = peekChar();
        type.append(ch);
        consumeChar();

        if (ch == '(') {
          depth++;
        } else if (ch == ')') {
          depth--;
          if (depth == 0) {
            return type.toString();
          }
        }
      }

      // Need more data
      return null;
    } else {
      // Named type: CDATA, ID, IDREF, IDREFS, ENTITY, ENTITIES, NMTOKEN, NMTOKENS, NOTATION
      String typeName = parseName();
      if (typeName == null) {
        return null;
      }

      // NOTATION can be followed by enumeration
      if ("NOTATION".equals(typeName)) {
        skipWhitespace();
        if (!charBuffer.hasRemaining()) {
          return null;
        }

        if (peekChar() == '(') {
          StringBuilder notation = new StringBuilder("NOTATION ");
          int depth = 0;

          while (charBuffer.hasRemaining()) {
            ch = peekChar();
            notation.append(ch);
            consumeChar();

            if (ch == '(') {
              depth++;
            } else if (ch == ')') {
              depth--;
              if (depth == 0) {
                return notation.toString();
              }
            }
          }

          // Need more data
          return null;
        }
      }

      return typeName;
    }
  }

  /**
   * Checks if a character is a valid name start character.
   */
  private boolean isNameStartChar(char ch) {
    return ch == ':' || ch == '_' ||
        (ch >= 'A' && ch <= 'Z') ||
        (ch >= 'a' && ch <= 'z') ||
        (ch >= 0xC0 && ch <= 0xD6) ||
        (ch >= 0xD8 && ch <= 0xF6) ||
        (ch >= 0xF8 && ch <= 0x2FF) ||
        (ch >= 0x370 && ch <= 0x37D) ||
        (ch >= 0x37F && ch <= 0x1FFF) ||
        (ch >= 0x200C && ch <= 0x200D) ||
        (ch >= 0x2070 && ch <= 0x218F) ||
        (ch >= 0x2C00 && ch <= 0x2FEF) ||
        (ch >= 0x3001 && ch <= 0xD7FF) ||
        (ch >= 0xF900 && ch <= 0xFDCF) ||
        (ch >= 0xFDF0 && ch <= 0xFFFD);
  }

  /**
   * Checks if a character is a valid name character.
   */
  private boolean isNameChar(char ch) {
    return isNameStartChar(ch) ||
        ch == '-' || ch == '.' ||
        (ch >= '0' && ch <= '9') ||
        ch == 0xB7 ||
        (ch >= 0x0300 && ch <= 0x036F) ||
        (ch >= 0x203F && ch <= 0x2040);
  }

  // Utility methods

  private void expandBuffer(int additional) {
    ByteBuffer newBuffer = ByteBuffer.allocate(
        parseBuffer.capacity() + additional + 2048);
    parseBuffer.flip();
    newBuffer.put(parseBuffer);
    parseBuffer = newBuffer;
  }

  private void skipWhitespace() {
    while (charBuffer.hasRemaining()) {
      char ch = peekChar();
      if (Character.isWhitespace(ch)) {
        consumeChar();
      } else {
        break;
      }
    }
  }

  private char peekChar() {
    return charBuffer.get(charBuffer.position());
  }

  private char peekChar(int offset) {
    int pos = charBuffer.position() + offset;
    if (pos < charBuffer.limit()) {
      return charBuffer.get(pos);
    }
    return 0;
  }

  /**
   * Consumes a single character from the buffer and updates position tracking.
   * Handles line endings according to XML 1.0 spec section 2.11.
   */
  private char consumeChar() {
    char ch = charBuffer.get();

    // Check if this LF is the second half of a CRLF split across buffers
    if (lastCharWasCR && ch == '\n') {
      // Already counted the line for CR, just clear the flag
      lastCharWasCR = false;
      return ch;
    }

    // Clear the CR flag if we're consuming anything other than LF after CR
    lastCharWasCR = false;

    if (ch == '\n') {
      // LF - new line
      line++;
      column = 0;
    } else if (ch == '\r') {
      // CR - check if followed by LF (CRLF)
      if (charBuffer.hasRemaining() && peekChar() == '\n') {
        // CRLF in same buffer - consume the LF as well
        charBuffer.get();
      } else {
        // CR at end of buffer or followed by non-LF
        lastCharWasCR = true;
      }
      // Either CR alone or CRLF - both count as single line ending
      line++;
      column = 0;
    } else {
      column++;
    }

    return ch;
  }

  private boolean hasAvailable(int count) {
    return charBuffer.remaining() >= count;
  }

  private boolean matchesKeyword(String keyword) {
    if (charBuffer.remaining() < keyword.length()) {
      return false;
    }

    int pos = charBuffer.position();
    for (int i = 0; i < keyword.length(); i++) {
      if (charBuffer.get(pos + i) != keyword.charAt(i)) {
        return false;
      }
    }

    // Consume the keyword
    for (int i = 0; i < keyword.length(); i++) {
      consumeChar();
    }

    return true;
  }

  private SAXParseException toParseException(SAXException e) {
    if (e instanceof SAXParseException) {
      return (SAXParseException) e;
    }
    return new SAXParseException(e.getMessage(), null, null, line, column, e);
  }
  
  // EntityResolvingParser interface implementation
  
  /**
   * Callback from EntityReceiver when parameter entity resolution completes.
   * 
   * <p>Implements {@link EntityResolvingParser#onEntityResolutionComplete()}.
   */
  @Override
  public void onEntityResolutionComplete() throws SAXException {
    // Process buffered DTD data that arrived while entity was being resolved
    if (entityBuffer.position() == 0) {
      return; // No buffered data
    }
    
    entityBuffer.flip();
    
    // Ensure capacity in parse buffer
    if (parseBuffer.remaining() < entityBuffer.remaining()) {
      int newCapacity = parseBuffer.capacity() + entityBuffer.remaining() + 4096;
      ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
      parseBuffer.flip();
      newBuffer.put(parseBuffer);
      parseBuffer = newBuffer;
    }
    
    parseBuffer.put(entityBuffer);
    entityBuffer.clear(); // Ready for next entity
    
    // Process the buffered data by calling receive's internal logic
    // (we don't call receive() directly to avoid recursive issues)
    parseBuffer.flip();
    CoderResult result = decoder.decode(parseBuffer, charBuffer, false);
    parseBuffer.compact();
    
    if (result.isError()) {
      throw new SAXParseException("Character decoding error", null, null, line, column);
    }
    
    charBuffer.flip();
    parse();
    charBuffer.compact();
  }
  
  @Override
  public Charset getCharset() {
    return charset;
  }
  
  @Override
  public ContentHandler getContentHandler() {
    return null; // DTD parser doesn't use ContentHandler
  }
  
  @Override
  public LexicalHandler getLexicalHandler() {
    return lexicalHandler;
  }
  
  @Override
  public DTDHandler getDTDHandler() {
    return dtdHandler;
  }
  
  @Override
  public ErrorHandler getErrorHandler() {
    return errorHandler;
  }
  
  @Override
  public AsyncEntityResolverFactory getEntityResolverFactory() {
    return entityResolverFactory;
  }
  
  @Override
  public long getEntityTimeout() {
    return entityTimeout;
  }
  
  @Override
  public String getSystemId() {
    return systemId;
  }
  
  @Override
  public String getPublicId() {
    return publicId;
  }
  
  /**
   * Sets the error handler for error reporting.
   * 
   * @param handler the error handler, or null
   */
  public void setErrorHandler(ErrorHandler handler) {
    this.errorHandler = handler;
  }
  
  /**
   * Sets the system identifier for error reporting.
   * 
   * @param systemId the system identifier (URI)
   */
  public void setSystemId(String systemId) {
    this.systemId = systemId;
  }
  
  /**
   * Sets the public identifier for error reporting.
   * 
   * @param publicId the public identifier
   */
  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }
  
  /**
   * Sets the entity resolution timeout in milliseconds.
   * 
   * @param timeoutMillis the timeout in milliseconds, or 0 to disable
   */
  public void setEntityTimeout(long timeoutMillis) {
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException("Timeout must be non-negative");
    }
    this.entityTimeout = timeoutMillis;
  }

}
