package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!ATTLIST declarations within a DTD.
 * 
 * <p>Grammar: &lt;!ATTLIST Name AttDef* &gt;
 * <p>AttDef: S Name S AttType S DefaultDecl
 * <p>AttType: CDATA | ID | IDREF | IDREFS | ENTITY | ENTITIES | NMTOKEN | NMTOKENS | 
 *             NotationType | Enumeration
 * <p>DefaultDecl: #REQUIRED | #IMPLIED | ((#FIXED S)? AttValue)
 * 
 * <p>This parser is instantiated for each &lt;!ATTLIST declaration and
 * handles tokens until the closing &gt; is encountered. It builds a map
 * of attribute declarations which is then merged into the DTD's overall
 * attribute declarations structure.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class AttListDeclParser {
    
    /**
     * Sub-states for parsing &lt;!ATTLIST declarations.
     * Tracks position within the attribute list declaration to enforce well-formedness.
     */
    private enum State {
        EXPECT_ELEMENT_NAME,    // After &lt;!ATTLIST, expecting element name
        AFTER_ELEMENT_NAME,     // After element name, expecting whitespace
        EXPECT_ATTR_OR_GT,      // Expecting attribute name or &gt;
        AFTER_ATTR_NAME,        // After attribute name, expecting whitespace
        EXPECT_ATTR_TYPE,       // After whitespace, expecting type
        AFTER_ATTR_TYPE,        // After type, expecting whitespace
        EXPECT_DEFAULT_DECL,    // After whitespace, expecting #REQUIRED|#IMPLIED|#FIXED|value
        AFTER_HASH,             // After #, expecting REQUIRED|IMPLIED|FIXED keyword
        AFTER_FIXED,            // After #FIXED, expecting whitespace
        EXPECT_DEFAULT_VALUE,   // After whitespace following #FIXED, expecting quoted value
        AFTER_DEFAULT_VALUE,    // After CDATA value, expecting closing quote
        IN_NOTATION_ENUM        // Inside NOTATION enumeration (name1|name2...)
    }
    
    private State state = State.EXPECT_ELEMENT_NAME;
    private boolean sawWhitespaceAfterAttrType; // Track whitespace before enumeration
    
    /**
     * Current attribute list declaration being parsed.
     * 
     * <p>currentAttlistElement: The element name these attributes belong to
     * <p>currentAttlistMap: Map of attribute name → AttributeDeclaration being built
     * <p>currentAttributeDecl: The current attribute being parsed (added to map when complete)
     * <p>defaultValueBuilder: Accumulates CDATA chunks for default values (asynchronous parsing)
     * 
     * <p>When an attribute is complete, it's added to currentAttlistMap keyed by its name.
     * When GT is encountered, currentAttlistMap is merged into attributeDecls keyed by currentAttlistElement.
     */
    private String currentAttlistElement;
    private Map<String, AttributeDeclaration> currentAttlistMap;
    private AttributeDeclaration currentAttributeDecl;
    private List<Object> defaultValueBuilder;  // List of String and GeneralEntityReference
    private StringBuilder defaultValueTextBuilder;  // For accumulating text segments
    private List<String> enumerationBuilder;  // For accumulating enumeration values
    
    private final DTDParser dtdParser;
    private final Locator locator;
    
    /**
     * Creates a new parser for a single &lt;!ATTLIST declaration.
     * 
     * @param dtdParser the parent DTD parser (for accessing entities and saving results)
     * @param locator the locator for error reporting
     */
    AttListDeclParser(DTDParser dtdParser, Locator locator) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.currentAttlistMap = new HashMap<>();
    }
    
    /**
     * Processes a single token within the &lt;!ATTLIST declaration.
     * Returns true if the declaration is complete (GT was encountered).
     * 
     * @param token the token to process
     * @param data the character buffer for the token (may be null)
     * @return true if the ATTLIST declaration is complete
     * @throws SAXException if a parsing error occurs
     */
    boolean handleToken(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_ELEMENT_NAME:
                // Must see NAME for element name (whitespace allowed first)
                switch (token) {
                    case S:
                        // Skip whitespace before element name
                        break;
                        
                    case NAME:
                        currentAttlistElement = data.toString();
                        state = State.AFTER_ELEMENT_NAME;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected element name after <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_ELEMENT_NAME:
                // Must see whitespace after element name
                switch (token) {
                    case S:
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after element name in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_ATTR_OR_GT:
                // Expecting attribute name or GT to close
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case NAME:
                        // Start new attribute
                        currentAttributeDecl = new AttributeDeclaration();
                        currentAttributeDecl.name = data.toString();
                        state = State.AFTER_ATTR_NAME;
                        break;
                        
                    case COLON:
                        // Colon is a valid XML 1.0 name (though discouraged by XML Namespaces)
                        currentAttributeDecl = new AttributeDeclaration();
                        currentAttributeDecl.name = ":";
                        state = State.AFTER_ATTR_NAME;
                        break;
                        
                    case GT:
                        // End of ATTLIST - save and return true to signal completion
                        saveCurrentAttlist();
                        return true;
                        
                    default:
                        throw new SAXParseException("Expected attribute name or '>' in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_ATTR_NAME:
                // Must see whitespace after attribute name
                switch (token) {
                    case S:
                        sawWhitespaceAfterAttrType = false; // Reset for new attribute
                        state = State.EXPECT_ATTR_TYPE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after attribute name in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_ATTR_TYPE:
                // Expecting attribute type
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case CDATA_TYPE:
                        currentAttributeDecl.type = "CDATA";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case ID:
                        currentAttributeDecl.type = "ID";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case IDREF:
                        currentAttributeDecl.type = "IDREF";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case IDREFS:
                        currentAttributeDecl.type = "IDREFS";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case ENTITY:
                        currentAttributeDecl.type = "ENTITY";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case ENTITIES:
                        currentAttributeDecl.type = "ENTITIES";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case NMTOKEN:
                        currentAttributeDecl.type = "NMTOKEN";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case NMTOKENS:
                        currentAttributeDecl.type = "NMTOKENS";
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case NOTATION:
                        currentAttributeDecl.type = "NOTATION";
                        // NOTATION type may be followed by (name1|name2|...)
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    case OPEN_PAREN:
                        // Enumeration starting - collect values
                        currentAttributeDecl.type = "ENUMERATION";
                        enumerationBuilder = new ArrayList<>();
                        state = State.AFTER_ATTR_TYPE;
                        break;
                        
                    default:
                        throw new SAXParseException("Invalid attribute type in <!ATTLIST: " + 
                            (data != null ? data.toString() : token.toString()), locator);
                }
                break;
                
            case AFTER_ATTR_TYPE:
                // Must see whitespace after type (or part of enumeration)
                switch (token) {
                    case S:
                        // Whitespace after type means we're done with enumeration (if any)
                        sawWhitespaceAfterAttrType = true;
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        state = State.EXPECT_DEFAULT_DECL;
                        break;
                        
                    case OPEN_PAREN:
                        // Start of enumeration (for NOTATION or after OPEN_PAREN in EXPECT_ATTR_TYPE)
                        // For NOTATION type, require whitespace before enumeration
                        if ("NOTATION".equals(currentAttributeDecl.type) && !sawWhitespaceAfterAttrType) {
                            throw new SAXParseException("Expected whitespace after NOTATION keyword in <!ATTLIST", locator);
                        }
                        if (enumerationBuilder == null) {
                            enumerationBuilder = new ArrayList<>();
                        }
                        // Stay in AFTER_ATTR_TYPE to collect values
                        break;
                        
                    case CLOSE_PAREN:
                        // End of enumeration
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        // Stay in AFTER_ATTR_TYPE waiting for whitespace
                        break;
                        
                    case PIPE:
                        // Separator in enumeration - stay in AFTER_ATTR_TYPE
                        break;
                        
                    case NAME:
                        // Part of enumeration - collect it
                        if (enumerationBuilder != null) {
                            enumerationBuilder.add(data.toString());
                        }
                        // Stay in AFTER_ATTR_TYPE
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after attribute type in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_DEFAULT_DECL:
                // Expecting #REQUIRED | #IMPLIED | #FIXED | default value
                // Or OPEN_PAREN for NOTATION enumeration
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case OPEN_PAREN:
                        // Start of NOTATION enumeration
                        enumerationBuilder = new ArrayList<>();
                        state = State.IN_NOTATION_ENUM;
                        break;
                        
                    case HASH:
                        // Hash before keyword - transition to AFTER_HASH to expect keyword
                        state = State.AFTER_HASH;
                        break;
                        
                    case REQUIRED:
                        // #REQUIRED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.REQUIRED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case IMPLIED:
                        // #IMPLIED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.IMPLIED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case FIXED:
                        // #FIXED without hash (tokenizer combined them)
                        currentAttributeDecl.mode = Token.FIXED;
                        state = State.AFTER_FIXED;
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Default value starting (no #FIXED) - initialize accumulators
                        defaultValueBuilder = new ArrayList<>();
                        defaultValueTextBuilder = new StringBuilder();
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    case CDATA:
                        // Default value chunk - accumulate (may receive multiple CDATA tokens)
                        if (defaultValueBuilder == null) {
                            defaultValueBuilder = new ArrayList<>();
                            defaultValueTextBuilder = new StringBuilder();
                        }
                        defaultValueTextBuilder.append(data.toString());
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected default declaration (#REQUIRED, #IMPLIED, #FIXED, or value) in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_HASH:
                // After #, must see REQUIRED | IMPLIED | FIXED keyword
                switch (token) {
                    case REQUIRED:
                        currentAttributeDecl.mode = Token.REQUIRED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case IMPLIED:
                        currentAttributeDecl.mode = Token.IMPLIED;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    case FIXED:
                        currentAttributeDecl.mode = Token.FIXED;
                        state = State.AFTER_FIXED;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected REQUIRED, IMPLIED, or FIXED after # in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_FIXED:
                // Must see whitespace after #FIXED
                switch (token) {
                    case S:
                        state = State.EXPECT_DEFAULT_VALUE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after #FIXED in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case EXPECT_DEFAULT_VALUE:
                // Expecting quoted default value after #FIXED
                switch (token) {
                    case S:
                        // Skip extra whitespace
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Quote starting value - initialize accumulators
                        defaultValueBuilder = new ArrayList<>();
                        defaultValueTextBuilder = new StringBuilder();
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    case CDATA:
                        // Fixed default value chunk - accumulate (may receive multiple CDATA tokens)
                        if (defaultValueBuilder == null) {
                            defaultValueBuilder = new ArrayList<>();
                            defaultValueTextBuilder = new StringBuilder();
                        }
                        defaultValueTextBuilder.append(data.toString());
                        state = State.AFTER_DEFAULT_VALUE;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected quoted value after #FIXED in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case AFTER_DEFAULT_VALUE:
                // After default value CDATA, expecting more CDATA chunks, entity refs, or closing quote
                switch (token) {
                    case CDATA:
                    case S:
                        // Additional chunk of default value - accumulate
                        defaultValueTextBuilder.append(data.toString());
                        break;
                        
                    case CHARENTITYREF:
                        // Character reference (already expanded) - e.g., &#60; -> '<'
                        defaultValueTextBuilder.append(data.toString());
                        break;
                        
                    case PREDEFENTITYREF:
                        // Predefined entity reference (already expanded) - e.g., &lt; -> '<'
                        defaultValueTextBuilder.append(data.toString());
                        break;
                        
                    case GENERALENTITYREF:
                        // General entity reference - flush text and add reference
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                            defaultValueTextBuilder.setLength(0);
                        }
                        String entityName = data.toString();
                        // WFC: Entity Declared - entity must be declared before use in attribute default
                        if (dtdParser.getGeneralEntity(entityName) == null) {
                            throw new SAXParseException(
                                "Entity '" + entityName + "' must be declared before use in attribute default value " +
                                "(WFC: Entity Declared)", locator);
                        }
                        defaultValueBuilder.add(new GeneralEntityReference(entityName));
                        break;
                        
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in default value - flush text and add reference
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                            defaultValueTextBuilder.setLength(0);
                        }
                        String paramEntityName = data.toString();
                        // Parameter entities are allowed in default values
                        defaultValueBuilder.add(new ParameterEntityReference(paramEntityName));
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Closing quote - finalize default value, save attribute, and return to expecting next attribute
                        // Flush final text segment
                        if (defaultValueTextBuilder.length() > 0) {
                            defaultValueBuilder.add(defaultValueTextBuilder.toString());
                        }
                        // Set default value on attribute
                        currentAttributeDecl.defaultValue = defaultValueBuilder;
                        defaultValueBuilder = null;
                        defaultValueTextBuilder = null;
                        saveCurrentAttribute();
                        state = State.EXPECT_ATTR_OR_GT;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected closing quote after default value in <!ATTLIST, got: " + token, locator);
                }
                break;
                
            case IN_NOTATION_ENUM:
                // Inside NOTATION enumeration: (name1|name2|name3)
                switch (token) {
                    case NAME:
                        // Collect notation name
                        if (enumerationBuilder != null) {
                            enumerationBuilder.add(data.toString());
                        }
                        break;
                        
                    case PIPE:
                        // Separator between notation names
                        break;
                        
                    case CLOSE_PAREN:
                        // End of notation enumeration
                        if (enumerationBuilder != null) {
                            currentAttributeDecl.enumeration = enumerationBuilder;
                            enumerationBuilder = null;
                        }
                        // Back to expecting default declaration
                        state = State.EXPECT_DEFAULT_DECL;
                        break;
                        
                    case S:
                        // Whitespace in enumeration, ignore
                        break;
                        
                    default:
                        throw new SAXParseException("Expected notation name, |, or ) in NOTATION enumeration, got: " + token, locator);
                }
                break;
                
            default:
                throw new SAXParseException("Invalid ATTLIST parser state: " + state, locator);
        }
        
        return false; // Not done yet
    }
    
    /**
     * Saves the current attribute to the attribute map.
     * Called when an attribute declaration is complete.
     */
    private void saveCurrentAttribute() {
        if (currentAttributeDecl != null && currentAttributeDecl.name != null) {
            // Set default type if not specified
            if (currentAttributeDecl.type == null) {
                currentAttributeDecl.type = "CDATA";
            }
            // Add to current ATTLIST map keyed by attribute name
            currentAttlistMap.put(currentAttributeDecl.name.intern(), currentAttributeDecl);
            currentAttributeDecl = null;
        }
    }
    
    /**
     * Saves the completed ATTLIST to the DTD's attribute declarations.
     * Merges with existing attributes for this element if present.
     */
    private void saveCurrentAttlist() {
        if (currentAttlistElement != null && !currentAttlistMap.isEmpty()) {
            dtdParser.addAttributeDeclarations(currentAttlistElement, currentAttlistMap);
        }
    }
    
}

