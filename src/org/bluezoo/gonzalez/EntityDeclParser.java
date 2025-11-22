package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!ENTITY declarations within a DTD.
 * 
 * <p>Grammar: &lt;!ENTITY Name EntityDef &gt; | &lt;!ENTITY % Name PEDef &gt;
 * <p>EntityDef: EntityValue | (ExternalID NDataDecl?)
 * <p>PEDef: EntityValue | ExternalID
 * <p>EntityValue: '"' ([^%&"] | PEReference | Reference)* '"' | "'" ([^%&'] | PEReference | Reference)* "'"
 * <p>ExternalID: 'SYSTEM' S SystemLiteral | 'PUBLIC' S PubidLiteral S SystemLiteral
 * <p>NDataDecl: S 'NDATA' S Name
 * 
 * <p>This parser is instantiated for each &lt;!ENTITY declaration and
 * handles tokens until the closing &gt; is encountered. It builds an
 * EntityDeclaration which is then saved to the DTD's entity or parameter
 * entity map.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class EntityDeclParser {
    
    /**
     * Sub-states for parsing &lt;!ENTITY declarations.
     */
    private enum State {
        EXPECT_PERCENT_OR_NAME,  // After &lt;!ENTITY, expecting optional % or entity name
        EXPECT_NAME,             // After %, expecting parameter entity name
        AFTER_NAME,              // After entity name, expecting whitespace
        EXPECT_VALUE_OR_ID,      // After whitespace, expecting quoted value, SYSTEM, or PUBLIC
        IN_ENTITY_VALUE,         // Accumulating entity value between quotes
        AFTER_ENTITY_VALUE,      // After closing quote of entity value
        EXPECT_SYSTEM_ID,        // After SYSTEM, expecting quoted system ID
        EXPECT_PUBLIC_ID,        // After PUBLIC, expecting quoted public ID
        AFTER_PUBLIC_ID,         // After public ID, expecting system ID
        AFTER_EXTERNAL_ID,       // After external ID, expecting NDATA or &gt;
        EXPECT_NDATA_NAME,       // After NDATA, expecting notation name
        EXPECT_GT                // Expecting &gt; to close declaration
    }
    
    private State state = State.EXPECT_PERCENT_OR_NAME;
    
    /**
     * State tracking flags for whitespace requirements.
     */
    private boolean sawWhitespaceAfterEntityKeyword;
    private boolean sawWhitespaceAfterEntityPublicId;
    private boolean sawWhitespaceAfterSystemId;
    private boolean sawClosingQuoteOfPublicId;
    
    /**
     * Current entity declaration being parsed.
     */
    private EntityDeclaration currentEntity;
    private List<Object> entityValueBuilder;  // Accumulates String and GeneralEntityReference
    private StringBuilder entityValueTextBuilder;  // Accumulates current text segment
    private char entityValueQuote;           // The opening quote character (' or ")
    
    private final DTDParser dtdParser;
    private final Locator locator;
    private final DTDParser.State savedState; // State to return to after completing
    
    /**
     * Creates a new parser for a single &lt;!ENTITY declaration.
     * 
     * @param dtdParser the parent DTD parser (for validation and saving results)
     * @param locator the locator for error reporting
     * @param savedState the state to return to when parsing completes
     */
    EntityDeclParser(DTDParser dtdParser, Locator locator, DTDParser.State savedState) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.savedState = savedState;
        this.currentEntity = new EntityDeclaration();
        this.sawWhitespaceAfterEntityKeyword = false;
    }
    
    /**
     * Processes a single token within the &lt;!ENTITY declaration.
     * Returns true if the declaration is complete (&gt; was encountered).
     * 
     * @param token the token to process
     * @param data the character buffer for the token (may be null)
     * @return true if the ENTITY declaration is complete
     * @throws SAXException if a parsing error occurs
     */
    boolean handleToken(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_PERCENT_OR_NAME:
                // After <!ENTITY, expecting optional % or entity name
                switch (token) {
                    case S:
                        // Whitespace after <!ENTITY keyword is required
                        sawWhitespaceAfterEntityKeyword = true;
                        break;
                    case PERCENT:
                        // Parameter entity - must have whitespace before %
                        if (!sawWhitespaceAfterEntityKeyword) {
                            throw new SAXParseException(
                                "Expected whitespace after <!ENTITY keyword", locator);
                        }
                        currentEntity.isParameter = true;
                        state = State.EXPECT_NAME;
                        break;
                    case NAME:
                        // General entity name - must have whitespace before name
                        if (!sawWhitespaceAfterEntityKeyword) {
                            throw new SAXParseException(
                                "Expected whitespace after <!ENTITY keyword", locator);
                        }
                        currentEntity.isParameter = false;
                        currentEntity.name = data.toString();
                        state = State.AFTER_NAME;
                        break;
                    default:
                        throw new SAXParseException("Expected entity name or '%' in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_NAME:
                // After %, expecting parameter entity name
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case NAME:
                        currentEntity.name = data.toString();
                        state = State.AFTER_NAME;
                        break;
                    default:
                        throw new SAXParseException("Expected parameter entity name after '%' in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // After entity name, expecting whitespace
                if (token == Token.S) {
                    state = State.EXPECT_VALUE_OR_ID;
                } else {
                    throw new SAXParseException("Expected whitespace after entity name in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_VALUE_OR_ID:
                // After whitespace, expecting quoted value, SYSTEM, or PUBLIC
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Start of entity value
                        entityValueQuote = (token == Token.QUOT) ? '"' : '\'';
                        entityValueBuilder = new ArrayList<>();
                        entityValueTextBuilder = new StringBuilder();
                        state = State.IN_ENTITY_VALUE;
                        break;
                    case SYSTEM:
                        // External entity with system ID
                        currentEntity.externalID = new ExternalID();
                        sawWhitespaceAfterSystemId = false; // Reset for new external ID
                        state = State.EXPECT_SYSTEM_ID;
                        break;
                    case PUBLIC:
                        // External entity with public ID
                        currentEntity.externalID = new ExternalID();
                        sawWhitespaceAfterEntityPublicId = false; // Reset for new external ID
                        sawClosingQuoteOfPublicId = false; // Reset for new public ID
                        state = State.EXPECT_PUBLIC_ID;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted value, SYSTEM, or PUBLIC in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case IN_ENTITY_VALUE:
                // Accumulating entity value between quotes
                switch (token) {
                    case CDATA:
                    case S:
                        // Accumulate text
                        entityValueTextBuilder.append(data.toString());
                        break;
                    case CHARENTITYREF:
                        // Character reference (already expanded) - e.g., &#60; -> '<'
                        // These are expanded immediately during DTD parsing per XML 1.0 § 4.4.4
                        // and do NOT trigger the bypass rule
                        entityValueTextBuilder.append(data.toString());
                        break;
                    case PREDEFENTITYREF:
                        // Predefined entity reference (already expanded) - e.g., &lt; -> '<'
                        // Per XML 1.0 § 4.4.8, markup delimiters from predefined entity
                        // references are bypassed (not recognized as markup)
                        currentEntity.containsCharacterReferences = true;
                        entityValueTextBuilder.append(data.toString());
                        break;
                    case GENERALENTITYREF:
                        // General entity reference - flush text and add reference
                        if (entityValueTextBuilder.length() > 0) {
                            entityValueBuilder.add(entityValueTextBuilder.toString());
                            entityValueTextBuilder.setLength(0);
                        }
                        String entityName = data.toString();
                        entityValueBuilder.add(new GeneralEntityReference(entityName));
                        break;
                    case PARAMETERENTITYREF:
                        // Parameter entity reference in entity value
                        // WFC: PEs in Internal Subset - parameter entity references are NOT allowed
                        // within markup declarations in the internal subset (including entity values)
                        // This restriction only applies to the internal subset, not external subset
                        if (savedState == DTDParser.State.IN_INTERNAL_SUBSET) {
                            throw new SAXParseException(
                                "Parameter entity references are not allowed within entity values " +
                                "in the internal subset (WFC: PEs in Internal Subset)", locator);
                        }
                        
                        // In external subset, parameter entities are allowed in entity values
                        // Expand the parameter entity inline
                        String paramEntityName = data.toString();
                        // XXX: For now, just store the reference - proper expansion would require
                        // XXX: retokenizing the expanded value
                        // XXX: This is a limitation that should be addressed when implementing
                        // XXX: full parameter entity expansion in entity values
                        if (entityValueTextBuilder.length() > 0) {
                            entityValueBuilder.add(entityValueTextBuilder.toString());
                            entityValueTextBuilder.setLength(0);
                        }
                        entityValueBuilder.add(new ParameterEntityReference(paramEntityName));
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Check if this is the closing quote
                        char quoteChar = (token == Token.QUOT) ? '"' : '\'';
                        if (quoteChar == entityValueQuote) {
                            // Flush final text segment
                            if (entityValueTextBuilder.length() > 0) {
                                entityValueBuilder.add(entityValueTextBuilder.toString());
                            }
                            // Set replacement text on entity
                            currentEntity.replacementText = entityValueBuilder;
                            state = State.AFTER_ENTITY_VALUE;
                        } else {
                            // Wrong quote, treat as text
                            entityValueTextBuilder.append(quoteChar);
                        }
                        break;
                    default:
                        throw new SAXParseException("Unexpected token in entity value: " + token, locator);
                }
                break;
                
            case AFTER_ENTITY_VALUE:
                // After closing quote, expecting > or whitespace
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case GT:
                        // End of entity declaration - save and return true
                        dtdParser.addEntityDeclaration(currentEntity);
                        return true;
                    default:
                        throw new SAXParseException("Expected '>' after entity value in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_SYSTEM_ID:
                // After SYSTEM, expecting quoted system ID
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for system ID - next will be CDATA
                        break;
                    case CDATA:
                    case NAME:
                        // Workaround for tokenizer issues: accumulate system ID
                        if (currentEntity.externalID.systemId == null) {
                            currentEntity.externalID.systemId = data.toString();
                        } else {
                            currentEntity.externalID.systemId += data.toString();
                        }
                        // Transition to AFTER_EXTERNAL_ID after system ID is read
                        state = State.AFTER_EXTERNAL_ID;
                        break;
                    case GT:
                        // End of entity declaration (external parsed entity) - save and return true
                        dtdParser.addEntityDeclaration(currentEntity);
                        return true;
                    default:
                        throw new SAXParseException("Expected quoted system ID after SYSTEM in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_PUBLIC_ID:
                // After PUBLIC, expecting quoted public ID
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case QUOT:
                    case APOS:
                        // Opening quote for public ID - next will be CDATA
                        break;
                    case CDATA:
                    case NAME:
                        // Workaround for tokenizer issues
                        if (currentEntity.externalID.publicId == null) {
                            currentEntity.externalID.publicId = data.toString();
                        } else {
                            currentEntity.externalID.publicId += data.toString();
                        }
                        // Validate public ID when complete (on transition to AFTER_PUBLIC_ID)
                        dtdParser.validatePublicId(currentEntity.externalID.publicId);
                        state = State.AFTER_PUBLIC_ID;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted public ID after PUBLIC in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_ID:
                // After public ID, expecting closing quote, then system ID
                switch (token) {
                    case S:
                        // Whitespace after public ID - required before system ID (after closing quote)
                        sawWhitespaceAfterEntityPublicId = true;
                        break;
                    case QUOT:
                    case APOS:
                        if (!sawClosingQuoteOfPublicId) {
                            // First quote is the closing quote of public ID - just skip it
                            sawClosingQuoteOfPublicId = true;
                        } else {
                            // Second quote is opening quote of system ID - must have whitespace first
                            if (!sawWhitespaceAfterEntityPublicId) {
                                throw new SAXParseException("Expected whitespace between public ID and system ID in <!ENTITY", locator);
                            }
                        }
                        break;
                    case CDATA:
                    case NAME:
                        // System ID following public ID - must have whitespace first (after closing quote)
                        if (!sawWhitespaceAfterEntityPublicId) {
                            throw new SAXParseException("Expected whitespace between public ID and system ID in <!ENTITY", locator);
                        }
                        if (currentEntity.externalID.systemId == null) {
                            currentEntity.externalID.systemId = data.toString();
                        } else {
                            currentEntity.externalID.systemId += data.toString();
                        }
                        state = State.AFTER_EXTERNAL_ID;
                        break;
                    default:
                        throw new SAXParseException("Expected quoted system ID after public ID in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case AFTER_EXTERNAL_ID:
                // After external ID, expecting NDATA or >
                switch (token) {
                    case S:
                        // Whitespace after external ID - required before NDATA
                        sawWhitespaceAfterSystemId = true;
                        break;
                    case QUOT:
                    case APOS:
                        // Closing quote after system ID - skip
                        break;
                    case CDATA:
                    case NAME:
                        // Tokenizer splitting system ID, accumulate
                        currentEntity.externalID.systemId += data.toString();
                        break;
                    case GT:
                        // End of entity declaration (external parsed entity) - save and return true
                        dtdParser.addEntityDeclaration(currentEntity);
                        return true;
                    case NDATA:
                        // Unparsed entity - must have whitespace first
                        if (!sawWhitespaceAfterSystemId) {
                            throw new SAXParseException("Expected whitespace before NDATA in <!ENTITY", locator);
                        }
                        // Parameter entities cannot have NDATA (they are always parsed)
                        if (currentEntity.isParameter) {
                            throw new SAXParseException("Parameter entities cannot have NDATA annotation", locator);
                        }
                        state = State.EXPECT_NDATA_NAME;
                        break;
                    default:
                        throw new SAXParseException("Expected '>' or NDATA after external ID in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_NDATA_NAME:
                // After NDATA, expecting notation name
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case NAME:
                        currentEntity.notationName = data.toString();
                        state = State.EXPECT_GT;
                        break;
                    default:
                        throw new SAXParseException("Expected notation name after NDATA in <!ENTITY, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Expecting > to close declaration
                switch (token) {
                    case S:
                        // Skip whitespace
                        break;
                    case GT:
                        // End of entity declaration - save and return true
                        dtdParser.addEntityDeclaration(currentEntity);
                        return true;
                    default:
                        throw new SAXParseException("Expected '>' to close <!ENTITY declaration, got: " + token, locator);
                }
                break;
                
            default:
                throw new SAXParseException("Invalid ENTITY parser state: " + state, locator);
        }
        
        return false; // Not done yet
    }
    
}

