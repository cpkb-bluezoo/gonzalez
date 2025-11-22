package org.bluezoo.gonzalez;

import java.nio.CharBuffer;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser for &lt;!NOTATION declarations within a DTD.
 * 
 * <p>Grammar: &lt;!NOTATION Name S (ExternalID | PublicID) S? &gt;
 * <p>ExternalID: SYSTEM S SystemLiteral | PUBLIC S PubidLiteral S SystemLiteral
 * <p>PublicID: PUBLIC S PubidLiteral
 * 
 * <p>This parser is instantiated for each &lt;!NOTATION declaration and
 * handles tokens until the closing &gt; is encountered. It builds an
 * ExternalID which is then saved to the DTD's notations structure and
 * reported to the DTDHandler.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class NotationDeclParser {
    
    /**
     * Sub-states for parsing &lt;!NOTATION declarations.
     * Tracks position within the notation declaration to enforce well-formedness.
     */
    private enum State {
        EXPECT_NAME,           // After &lt;!NOTATION, expecting notation name
        AFTER_NAME,            // After notation name, expecting whitespace
        EXPECT_EXTERNAL_ID,    // After whitespace, expecting SYSTEM or PUBLIC
        EXPECT_SYSTEM_ID,      // After SYSTEM, expecting quoted system ID
        EXPECT_PUBLIC_ID,      // After PUBLIC, expecting quoted public ID
        AFTER_PUBLIC_ID,       // After public ID, expecting optional system ID or &gt;
        EXPECT_GT              // Expecting &gt; to close declaration
    }
    
    private State state = State.EXPECT_NAME;
    
    /**
     * Current notation declaration being parsed.
     */
    private String currentNotationName;
    private ExternalID currentNotationExternalID;
    private boolean sawPublicInNotation;
    
    private final DTDParser dtdParser;
    private final Locator locator;
    
    /**
     * Creates a new parser for a single &lt;!NOTATION declaration.
     * 
     * @param dtdParser the parent DTD parser (for validation and saving results)
     * @param locator the locator for error reporting
     */
    NotationDeclParser(DTDParser dtdParser, Locator locator) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.currentNotationExternalID = new ExternalID();
    }
    
    /**
     * Processes a single token within the &lt;!NOTATION declaration.
     * Returns true if the declaration is complete (GT was encountered).
     * 
     * @param token the token to process
     * @param data the character buffer for the token (may be null)
     * @return true if the NOTATION declaration is complete
     * @throws SAXException if a parsing error occurs
     */
    boolean handleToken(Token token, CharBuffer data) throws SAXException {
        switch (state) {
            case EXPECT_NAME:
                // After &lt;!NOTATION, expecting notation name
                switch (token) {
                    case S:
                        // Skip whitespace before name
                        break;
                        
                    case NAME:
                        // Notation name
                        currentNotationName = data.toString();
                        state = State.AFTER_NAME;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected notation name after <!NOTATION, got: " + token, locator);
                }
                break;
                
            case AFTER_NAME:
                // After notation name, expecting whitespace
                switch (token) {
                    case S:
                        // Whitespace between name and external ID
                        state = State.EXPECT_EXTERNAL_ID;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected whitespace after notation name, got: " + token, locator);
                }
                break;
                
            case EXPECT_EXTERNAL_ID:
                // After whitespace, expecting SYSTEM or PUBLIC
                switch (token) {
                    case S:
                        // Skip additional whitespace
                        break;
                        
                    case SYSTEM:
                        // SYSTEM external ID
                        sawPublicInNotation = false;
                        state = State.EXPECT_SYSTEM_ID;
                        break;
                        
                    case PUBLIC:
                        // PUBLIC external ID (or PublicID)
                        sawPublicInNotation = true;
                        state = State.EXPECT_PUBLIC_ID;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected SYSTEM or PUBLIC after notation name, got: " + token, locator);
                }
                break;
                
            case EXPECT_SYSTEM_ID:
                // After SYSTEM, expecting quoted system ID
                switch (token) {
                    case S:
                        // Whitespace between SYSTEM and quoted string
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Opening quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // System ID (tokenizer may emit NAME or CDATA in DOCTYPE context)
                        currentNotationExternalID.systemId = data.toString();
                        state = State.EXPECT_GT;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected quoted system ID after SYSTEM, got: " + token, locator);
                }
                break;
                
            case EXPECT_PUBLIC_ID:
                // After PUBLIC, expecting quoted public ID
                switch (token) {
                    case S:
                        // Whitespace between PUBLIC and quoted string
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Opening quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Public ID (tokenizer may emit NAME or CDATA in DOCTYPE context)
                        String publicId = data.toString();
                        dtdParser.validatePublicId(publicId);
                        currentNotationExternalID.publicId = publicId;
                        state = State.AFTER_PUBLIC_ID;
                        break;
                        
                    default:
                        throw new SAXParseException("Expected quoted public ID after PUBLIC, got: " + token, locator);
                }
                break;
                
            case AFTER_PUBLIC_ID:
                // After public ID, expecting optional system ID or &gt;
                switch (token) {
                    case S:
                        // Whitespace, ignore
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Quote (closing previous or opening next)
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Optional system ID after PUBLIC (tokenizer may emit NAME or CDATA)
                        currentNotationExternalID.systemId = data.toString();
                        state = State.EXPECT_GT;
                        break;
                        
                    case GT:
                        // End of declaration (PUBLIC without system ID) - save and return true
                        dtdParser.addNotationDeclaration(currentNotationName, currentNotationExternalID);
                        return true;
                        
                    default:
                        throw new SAXParseException("Expected system ID or '>' after public ID, got: " + token, locator);
                }
                break;
                
            case EXPECT_GT:
                // Expecting &gt; to close declaration
                switch (token) {
                    case S:
                        // Whitespace before &gt;, ignore
                        break;
                        
                    case QUOT:
                    case APOS:
                        // Closing quote, ignore
                        break;
                        
                    case CDATA:
                    case NAME:
                        // Additional text (e.g., continuation of system ID if split by tokenizer)
                        // Append to existing system ID if present
                        if (currentNotationExternalID.systemId != null) {
                            currentNotationExternalID.systemId += data.toString();
                        }
                        break;
                        
                    case GT:
                        // End of declaration - save and return true
                        dtdParser.addNotationDeclaration(currentNotationName, currentNotationExternalID);
                        return true;
                        
                    default:
                        throw new SAXParseException("Expected '>' to close <!NOTATION declaration, got: " + token, locator);
                }
                break;
                
            default:
                throw new SAXParseException("Invalid NOTATION parser state: " + state, locator);
        }
        
        return false; // Not done yet
    }
    
}

