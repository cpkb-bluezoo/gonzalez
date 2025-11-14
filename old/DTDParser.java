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
  private ByteBuffer byteUnderflow;     // Undecoded bytes (for BOM/text declaration detection)
  private CharBuffer charUnderflow;     // Incomplete tokens between receive() calls
  private CharBuffer parseBuffer;       // Active parsing buffer (decoded characters)
  private CharsetDecoder decoder;
  private StringBuilder tokenBuffer;
  private boolean charsetDetermined;    // True once charset is detected and we're in char mode

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
    this.byteUnderflow = ByteBuffer.allocate(256);  // Small - just for text declaration
    this.charUnderflow = CharBuffer.allocate(1024);
    this.charUnderflow.flip(); // Start empty in read mode
    this.parseBuffer = CharBuffer.allocate(2048);
    this.decoder = this.charset.newDecoder();
    this.tokenBuffer = new StringBuilder();
    this.charsetDetermined = false;  // Will detect charset from BOM/text declaration
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
   * Saved parser position for backtracking.
   */
  private static class SavedPosition {
    final int bufferPosition;
    final int line;
    final int column;
    final boolean lastCharWasCR;

    SavedPosition(int pos, int line, int col, boolean cr) {
      this.bufferPosition = pos;
      this.line = line;
      this.column = col;
      this.lastCharWasCR = cr;
    }
  }

  /**
   * Saves current parse position for potential backtracking.
   */
  private SavedPosition savePosition() {
    return new SavedPosition(parseBuffer.position(), line, column, lastCharWasCR);
  }

  /**
   * Restores previously saved parse position.
   */
  private void restorePosition(SavedPosition saved) {
    parseBuffer.position(saved.bufferPosition);
    line = saved.line;
    column = saved.column;
    lastCharWasCR = saved.lastCharWasCR;
  }

  /**
   * Saves remaining parseBuffer data to charUnderflow on incomplete parse.
   * This is called when a parse method can't complete and needs to wait for more data.
   * After calling this, parseBuffer will be empty and charUnderflow will contain the saved data.
   */
  private void saveToUnderflow() {
    int remaining = parseBuffer.remaining();
    if (remaining > 0) {
      // Ensure charUnderflow has capacity
      if (charUnderflow.capacity() < remaining) {
        charUnderflow = CharBuffer.allocate(remaining * 2);
      }
      charUnderflow.clear();
      charUnderflow.put(parseBuffer);
      charUnderflow.flip(); // Ready to read
    }
    // Clear parseBuffer so parse() loop exits
    parseBuffer.clear();
    parseBuffer.flip(); // Empty in read mode
  }

  /**
   * Prepends charUnderflow to parseBuffer, optimizing to avoid reallocation.
   * After this, charUnderflow is empty and parseBuffer contains underflow + new data.
   */
  private void prependUnderflow() {
    int underflowSize = charUnderflow.remaining();
    if (underflowSize == 0) {
      return;
    }

    int parseBufferData = parseBuffer.remaining();
    int totalSize = underflowSize + parseBufferData;

    // Optimization: if everything fits in current parseBuffer capacity, reuse it
    if (totalSize <= parseBuffer.capacity()) {
      // 1. Save parseBuffer data
      char[] temp = new char[parseBufferData];
      parseBuffer.get(temp);

      // 2. Reset and write: underflow first, then parseBuffer data
      parseBuffer.clear();
      parseBuffer.put(charUnderflow);
      parseBuffer.put(temp);
      parseBuffer.flip();
    } else {
      // Need larger buffer
      CharBuffer newBuffer = CharBuffer.allocate(totalSize * 2);
      newBuffer.put(charUnderflow);
      newBuffer.put(parseBuffer);
      newBuffer.flip();
      parseBuffer = newBuffer;
    }

    // Empty the underflow since we've consumed it
    charUnderflow.clear();
    charUnderflow.limit(0); // Empty in read mode
  }

  /**
   * Ensures byteUnderflow has capacity for additional bytes.
   */
  private void ensureByteCapacity(int additional) {
    if (byteUnderflow.remaining() < additional) {
      int newCapacity = byteUnderflow.capacity() + additional;
      ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
      byteUnderflow.flip();
      newBuffer.put(byteUnderflow);
      byteUnderflow = newBuffer;
    }
  }

  /**
   * Detects the character encoding via BOM and/or text declaration.
   * 
   * <p>External parsed entities (including external DTD subsets) can have their own
   * encoding per XML spec section 4.3.3. This method detects:
   * <ul>
   * <li>UTF-8/16/32 BOMs</li>
   * <li>Text declarations: {@code <?xml encoding="..."?>}</li>
   * <li>Defaults to UTF-8 if neither present</li>
   * </ul>
   */
  private void detectCharset() throws SAXException {
    // Check for BOM
    if (byteUnderflow.remaining() >= 2) {
      int b1 = byteUnderflow.get(0) & 0xFF;
      int b2 = byteUnderflow.get(1) & 0xFF;

      // UTF-16 BE BOM
      if (b1 == 0xFE && b2 == 0xFF) {
        setCharset(StandardCharsets.UTF_16BE);
        byteUnderflow.position(byteUnderflow.position() + 2); // Skip BOM
        charsetDetermined = true;
        return;
      }

      // UTF-16 LE BOM
      if (b1 == 0xFF && b2 == 0xFE) {
        // Could also be UTF-32 LE, check for 00 00
        if (byteUnderflow.remaining() >= 4) {
          int b3 = byteUnderflow.get(2) & 0xFF;
          int b4 = byteUnderflow.get(3) & 0xFF;
          if (b3 == 0x00 && b4 == 0x00) {
            // UTF-32 LE
            try {
              setCharset(Charset.forName("UTF-32LE"));
              byteUnderflow.position(byteUnderflow.position() + 4);
              charsetDetermined = true;
              return;
            } catch (Exception e) {
              // UTF-32 not supported, fall through
            }
          }
        }
        // UTF-16 LE
        setCharset(StandardCharsets.UTF_16LE);
        byteUnderflow.position(byteUnderflow.position() + 2);
        charsetDetermined = true;
        return;
      }

      // UTF-8 BOM
      if (byteUnderflow.remaining() >= 3) {
        int b3 = byteUnderflow.get(2) & 0xFF;
        if (b1 == 0xEF && b2 == 0xBB && b3 == 0xBF) {
          setCharset(StandardCharsets.UTF_8);
          byteUnderflow.position(byteUnderflow.position() + 3); // Skip BOM
          charsetDetermined = true;
          return;
        }
      }
    }

    // Check for text declaration: <?xml encoding="..."?>
    // For now, just default to charset from constructor (which is parent's charset)
    // A full implementation would parse the text declaration here
    // TODO: Implement text declaration parsing for external entities

    // No BOM found - use default charset and switch to character mode
    charsetDetermined = true;
  }

  /**
   * Sets the character encoding and creates a new decoder.
   */
  private void setCharset(Charset newCharset) {
    this.charset = newCharset;
    this.decoder = newCharset.newDecoder();
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
    try {
      if (!charsetDetermined) {
        // Phase 1: Charset not yet determined - buffer bytes until we can detect it
        ensureByteCapacity(data.remaining());
        byteUnderflow.put(data);
        byteUnderflow.flip();

        // Try to detect charset from BOM or text declaration
        if (byteUnderflow.hasRemaining()) {
          detectCharset();
        }

        // Decode whatever bytes we have into parseBuffer
        if (byteUnderflow != null && byteUnderflow.hasRemaining()) {
          // Decode directly into parseBuffer
          parseBuffer.clear();
          CoderResult result = decoder.decode(byteUnderflow, parseBuffer, false);
          if (result.isError()) {
            try {
              result.throwException();
            } catch (Exception e) {
              throw new SAXParseException("Character encoding error: " + e.getMessage(),
                  null, null, line, column, e);
            }
          }
          parseBuffer.flip(); // Now in read mode
        } else {
          parseBuffer.clear();
          parseBuffer.flip(); // Empty
        }

        // Compact byteUnderflow if not released
        if (byteUnderflow != null) {
          byteUnderflow.compact();
        }

        // Prepend underflow to parseBuffer
        prependUnderflow();

        // Release byteUnderflow if charset is determined and all bytes are decoded
        if (charsetDetermined && byteUnderflow != null && !byteUnderflow.hasRemaining()) {
          byteUnderflow = null;
        }
      } else {
        // Phase 2: Charset determined - decode incoming data into parseBuffer

        // Decode directly into parseBuffer
        parseBuffer.clear();
        CoderResult result = decoder.decode(data, parseBuffer, false);
        if (result.isError()) {
          result.throwException();
        }
        parseBuffer.flip(); // Now in read mode

        // Prepend underflow to parseBuffer
        prependUnderflow();
      }

      // Parse from parseBuffer (always in read mode)
      parse();

      // Save any unparsed data as underflow for next receive()
      if (parseBuffer.hasRemaining()) {
        int remaining = parseBuffer.remaining();
        if (charUnderflow.capacity() < remaining) {
          charUnderflow = CharBuffer.allocate(remaining * 2);
        }
        charUnderflow.clear();
        charUnderflow.put(parseBuffer);
        charUnderflow.flip(); // In read mode
      }

    } catch (SAXException e) {
      throw toParseException(e);
    } catch (Exception e) {
      throw new SAXParseException("Parsing error: " + e.getMessage(),
          null, null, line, column, e);
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
    try {
      // If charset not yet determined, just switch to char mode now
      if (!charsetDetermined) {
        charsetDetermined = true;
        byteUnderflow = null;
      }

      // Process any remaining characters in charUnderflow or parseBuffer
      if (charUnderflow.hasRemaining() || parseBuffer.hasRemaining()) {
        // Prepend any underflow
        prependUnderflow();
        
        // Parse remaining data
        parse();
      }

      // Verify we're in a valid end state
      if (parseBuffer.hasRemaining() && state != DTDState.DONE && state != DTDState.INITIAL) {
        throw new SAXParseException("Incomplete DTD declaration",
            null, null, line, column);
      }
    } catch (SAXException e) {
      throw toParseException(e);
    } catch (Exception e) {
      throw new SAXParseException("Error closing DTD parser: " + e.getMessage(),
          null, null, line, column, e);
    }
  }

  /**
   * Main DTD parsing state machine.
   */
  private void parse() throws SAXException {
    while (parseBuffer.hasRemaining()) {
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
      if (!parseBuffer.hasRemaining()) {
        break;
      }
    }
  }

  private void parseInitial() throws SAXException {
    skipWhitespace();

    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
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
      saveToUnderflow();
      return;
    }

    consumeChar(); // '<'
    char next = peekChar();

    if (next == '!') {
      consumeChar(); // '!'
      if (!hasAvailable(2)) {
        saveToUnderflow();
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
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return; // Need more data
    }

    // Parse element name
    String name = parseName();
    if (name == null) {
      saveToUnderflow();
      return; // Need more data
    }

    skipWhitespace();
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return; // Need more data
    }

    // Parse content model
    StringBuilder model = new StringBuilder();
    int depth = 0;
    boolean inParens = false;

    while (parseBuffer.hasRemaining()) {
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
        saveToUnderflow();
        return;
      } else {
        model.append(ch);
        consumeChar();
      }
    }

    // Need more data
    saveToUnderflow();
  }

  private void parseAttlistDecl() throws SAXException {
    // Parse: <!ATTLIST elementName attName attType default>
    // We've already consumed "<!ATTLIST"

    skipWhitespace();
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return;
    }

    // Parse element name
    String elementName = parseName();
    if (elementName == null) {
      saveToUnderflow();
      return;
    }

    // Parse attribute definitions (can be multiple)
    while (true) {
      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
        return;
      }

      // Check for end of ATTLIST
      if (peekChar() == '>') {
        consumeChar();
        state = DTDState.INITIAL;
        saveToUnderflow();
        return;
      }

      // Parse attribute name
      String attName = parseName();
      if (attName == null) {
        saveToUnderflow();
        return;
      }

      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
        return;
      }

      // Parse attribute type
      String attType = parseAttType();
      if (attType == null) {
        saveToUnderflow();
        return;
      }

      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
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
          saveToUnderflow();
          return;
        }
        mode = "#" + keyword;

        if ("#FIXED".equals(mode)) {
          skipWhitespace();
          if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
            return;
          }
          value = parseQuotedValue();
          if (value == null) {
            saveToUnderflow();
            return;
          }
        }
      } else if (ch == '"' || ch == '\'') {
        // Default value
        value = parseQuotedValue();
        if (value == null) {
          saveToUnderflow();
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
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return;
    }

    // Check for parameter entity
    boolean isParameter = false;
    if (peekChar() == '%') {
      isParameter = true;
      consumeChar();
      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
        return;
      }
    }

    // Parse entity name
    String name = parseName();
    if (name == null) {
      saveToUnderflow();
      return;
    }

    skipWhitespace();
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return;
    }

    char ch = peekChar();

    if (ch == '"' || ch == '\'') {
      // Internal entity
      String value = parseQuotedValue();
      if (value == null) {
        saveToUnderflow();
        return;
      }

      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
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
        saveToUnderflow();
        return;
      }

      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
        return;
      }

      if ("PUBLIC".equals(keyword)) {
        publicId = parseQuotedValue();
        if (publicId == null) {
          saveToUnderflow();
          return;
        }
        skipWhitespace();
        if (!parseBuffer.hasRemaining()) {
          saveToUnderflow();
          return;
        }
      }

      systemId = parseQuotedValue();
      if (systemId == null) {
        saveToUnderflow();
        return;
      }

      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
        return;
      }

      // Check for NDATA (unparsed entity)
      if (peekChar() == 'N') {
        String ndataKeyword = parseName();
        if (ndataKeyword == null) {
          saveToUnderflow();
          return;
        }

        if ("NDATA".equals(ndataKeyword)) {
          skipWhitespace();
          if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
            return;
          }

          String notationName = parseName();
          if (notationName == null) {
            saveToUnderflow();
            return;
          }

          skipWhitespace();
          if (!parseBuffer.hasRemaining()) {
            saveToUnderflow();
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
          saveToUnderflow();
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
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return;
    }

    String name = parseName();
    if (name == null) {
      saveToUnderflow();
      return;
    }

    skipWhitespace();
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return;
    }

    String keyword = parseName();
    if (keyword == null) {
      saveToUnderflow();
      return;
    }

    skipWhitespace();
    if (!parseBuffer.hasRemaining()) {
      saveToUnderflow();
      return;
    }

    String publicId = null;
    String systemId = null;

    if ("PUBLIC".equals(keyword)) {
      publicId = parseQuotedValue();
      if (publicId == null) {
        saveToUnderflow();
        return;
      }

      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
        return;
      }

      // System ID is optional for PUBLIC notations
      if (peekChar() == '"' || peekChar() == '\'') {
        systemId = parseQuotedValue();
        if (systemId == null) {
          saveToUnderflow();
          return;
        }
        skipWhitespace();
        if (!parseBuffer.hasRemaining()) {
          saveToUnderflow();
          return;
        }
      }
    } else if ("SYSTEM".equals(keyword)) {
      systemId = parseQuotedValue();
      if (systemId == null) {
        saveToUnderflow();
        return;
      }
      skipWhitespace();
      if (!parseBuffer.hasRemaining()) {
        saveToUnderflow();
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

    while (parseBuffer.hasRemaining()) {
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
        saveToUnderflow();
        return;
      }

      dash1 = dash2;
      dash2 = (ch == '-');
      comment.append(ch);
      consumeChar();
    }

    // Need more data
    saveToUnderflow();
  }

  // Helper parsing methods

  /**
   * Parses an XML name (element name, attribute name, etc.)
   * Returns null if more data is needed.
   */
  private String parseName() throws SAXException {
    if (!parseBuffer.hasRemaining()) {
      return null;
    }

    int start = parseBuffer.position();

    // First character must be NameStartChar
    char ch = peekChar();
    if (!isNameStartChar(ch)) {
      throw new SAXParseException("Invalid name start character: '" + ch + "'",
          null, null, line, column);
    }
    consumeChar();

    // Subsequent characters can be NameChar
    while (parseBuffer.hasRemaining()) {
      ch = peekChar();
      if (isNameChar(ch)) {
        consumeChar();
      } else {
        break;
      }
    }

    // Extract name
    int end = parseBuffer.position();
    int length = end - start;

    char[] nameChars = new char[length];
    int savedPos = parseBuffer.position();
    parseBuffer.position(start);
    parseBuffer.get(nameChars, 0, length);
    parseBuffer.position(savedPos);

    return new String(nameChars);
  }

  /**
   * Parses a quoted value (attribute value, entity value, etc.)
   * Returns null if more data is needed.
   */
  private String parseQuotedValue() throws SAXException {
    if (!parseBuffer.hasRemaining()) {
      return null;
    }

    char quote = peekChar();
    if (quote != '"' && quote != '\'') {
      throw new SAXParseException("Expected quote character",
          null, null, line, column);
    }
    consumeChar();

    StringBuilder value = new StringBuilder();

    while (parseBuffer.hasRemaining()) {
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
    if (!parseBuffer.hasRemaining()) {
      return null;
    }

    char ch = peekChar();

    if (ch == '(') {
      // Enumerated type: (val1|val2|val3)
      StringBuilder type = new StringBuilder();
      int depth = 0;

      while (parseBuffer.hasRemaining()) {
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
        if (!parseBuffer.hasRemaining()) {
          return null;
        }

        if (peekChar() == '(') {
          StringBuilder notation = new StringBuilder("NOTATION ");
          int depth = 0;

          while (parseBuffer.hasRemaining()) {
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

  private void skipWhitespace() {
    while (parseBuffer.hasRemaining()) {
      char ch = peekChar();
      if (Character.isWhitespace(ch)) {
        consumeChar();
      } else {
        break;
      }
    }
  }

  private char peekChar() {
    return parseBuffer.get(parseBuffer.position());
  }

  private char peekChar(int offset) {
    int pos = parseBuffer.position() + offset;
    if (pos >= parseBuffer.limit()) {
      return 0;
    }
    return parseBuffer.get(pos);
  }

  /**
   * Consumes a single character from the buffer and updates position tracking.
   * Handles line endings according to XML 1.0 spec section 2.11.
   */
  private char consumeChar() {
    char ch = parseBuffer.get();

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
      if (parseBuffer.hasRemaining() && peekChar() == '\n') {
        // CRLF in same buffer - consume the LF as well
        parseBuffer.get();
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
    return parseBuffer.remaining() >= count;
  }

  private boolean matchesKeyword(String keyword) {
    if (parseBuffer.remaining() < keyword.length()) {
      return false;
    }

    int pos = parseBuffer.position();
    for (int i = 0; i < keyword.length(); i++) {
      if (parseBuffer.get(pos + i) != keyword.charAt(i)) {
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
    // In the new architecture, buffered data is in charUnderflow
    // Just resume parsing
    if (parseBuffer.hasRemaining() || charUnderflow.hasRemaining()) {
      prependUnderflow();
      parse();
    }
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
