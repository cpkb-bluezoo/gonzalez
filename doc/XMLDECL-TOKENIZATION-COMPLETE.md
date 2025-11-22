# XML Declaration Tokenization - Complete

## Overview

Successfully replaced inefficient string manipulation in XML declaration parsing with proper tokenization-based approach. This provides robust well-formedness checking and establishes patterns for full document tokenization.

## What Was Changed

### Before: String Manipulation (❌ Inefficient, No Well-formedness)

```java
private String extractXMLDeclarationAttributes(String decl) {
    String encoding = null;
    
    // indexOf/substring manipulation
    int encIdx = decl.indexOf("encoding");
    if (encIdx != -1) {
        int quoteStart = decl.indexOf('"', encIdx);
        // ... more string searching ...
    }
    // No validation of XML syntax
    // No proper tokenization
    // Can't detect malformed declarations
}
```

**Problems:**
- No well-formedness checking
- Can't detect syntax errors (missing quotes, wrong order, etc.)
- Inefficient string searching
- Doesn't validate attribute order
- Doesn't enforce XML declaration syntax rules

### After: Tokenization-Based (✅ Robust, Well-formed)

```java
private String extractXMLDeclarationAttributes(CharBuffer declBuffer) throws SAXException {
    XMLDeclContext ctx = new XMLDeclContext();
    
    while (declBuffer.hasRemaining()) {
        Token token = nextXMLDeclToken(declBuffer);
        if (token == null) break;
        
        xmlDeclReceive(token, declBuffer, ctx);
        
        // State machine validates syntax
        switch (ctx.state) {
            case EXPECT_WHITESPACE_BEFORE_NAME: ...
            case EXPECT_NAME: ...
            case EXPECT_EQ: ...
            // etc.
        }
    }
    
    // Validate required attributes
    if ((ctx.seenAttributes & 1) == 0) {
        throw new SAXException("XML declaration missing required 'version' attribute");
    }
    
    return ctx.encoding;
}
```

## Token Sequence Example

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      ^     ^   ^   ^       ^       ^           ^   ^
      S   NAME EQ QUOT CDATA  QUOT   S    NAME  EQ APOS CDATA APOS S NAME EQ QUOT CDATA QUOT
```

**Token flow:**
1. `S` - Whitespace after `<?xml`
2. `NAME` - Attribute name (`version`)
3. `EQ` - Equals sign
4. `QUOT` - Opening quote
5. `CDATA` - Attribute value (`1.0`)
6. `QUOT` - Closing quote
7. (Repeat for `encoding` and `standalone`)

## Architecture

### Components

1. **`nextXMLDeclToken(CharBuffer)`** - Tokenizer
   - Scans buffer and returns next token
   - Handles: `S`, `EQ`, `QUOT`, `APOS`, `NAME`, `CDATA`
   - Consumes characters from buffer

2. **`xmlDeclReceive(Token, CharBuffer, XMLDeclContext)`** - Token processor
   - Processes each token
   - Extracts values for `NAME` and `CDATA` tokens
   - Validates attribute names and order
   - Updates XMLTokenizer state (`version`, `encoding`, `standalone`)

3. **`XMLDeclState`** - State machine
   - `EXPECT_WHITESPACE_BEFORE_NAME`
   - `EXPECT_NAME`
   - `EXPECT_EQ`
   - `EXPECT_QUOTE`
   - `EXPECT_VALUE`
   - `EXPECT_CLOSE_QUOTE`

4. **`XMLDeclContext`** - Mutable state
   - Current state
   - Current attribute name being processed
   - Bit flags for seen attributes (1=version, 2=encoding, 4=standalone)
   - Encoding value (returned)

### State Machine Flow

```
START
  ↓
EXPECT_WHITESPACE_BEFORE_NAME (must see S)
  ↓
EXPECT_NAME (see NAME token → extract & validate)
  ↓
EXPECT_EQ (see EQ token)
  ↓
EXPECT_QUOTE (see QUOT or APOS)
  ↓
EXPECT_VALUE (see CDATA → extract value)
  ↓
EXPECT_CLOSE_QUOTE (see QUOT or APOS matching opening)
  ↓
Back to EXPECT_WHITESPACE_BEFORE_NAME (or END if no more attributes)
```

## Well-formedness Checking

### Syntax Validation

**Required whitespace:**
```xml
<?xml version="1.0"?>           ✓
<?xmlversion="1.0"?>             ✗ Missing space after <?xml
```

**Proper quotes:**
```xml
<?xml version="1.0"?>           ✓
<?xml version='1.0'?>           ✓
<?xml version=1.0?>             ✗ Missing quotes
<?xml version="1.0'?>           ✗ Mismatched quotes
```

**Required attributes:**
```xml
<?xml version="1.0"?>           ✓
<?xml encoding="UTF-8"?>        ✗ Missing 'version'
```

### Attribute Order Validation

Per XML spec, attributes must appear in this order:
1. `version` (required, must be first)
2. `encoding` (optional, before standalone)
3. `standalone` (optional, must be last)

**Valid:**
```xml
<?xml version="1.0"?>                                    ✓
<?xml version="1.0" encoding="UTF-8"?>                   ✓
<?xml version="1.0" standalone="yes"?>                   ✓
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>  ✓
```

**Invalid:**
```xml
<?xml encoding="UTF-8" version="1.0"?>                   ✗ version must be first
<?xml version="1.0" standalone="yes" encoding="UTF-8"?>  ✗ encoding before standalone
<?xml standalone="yes" version="1.0"?>                   ✗ version must be first
```

### Value Validation

**standalone attribute:**
```xml
<?xml version="1.0" standalone="yes"?>   ✓
<?xml version="1.0" standalone="no"?>    ✓
<?xml version="1.0" standalone="true"?>  ✗ Must be 'yes' or 'no'
```

**Unknown attributes:**
```xml
<?xml version="1.0" foo="bar"?>          ✗ Unknown attribute 'foo'
```

## Implementation Details

### Token Extraction

```java
private Token nextXMLDeclToken(CharBuffer buffer) {
    if (!buffer.hasRemaining()) return null;
    
    buffer.mark();
    char c = buffer.get();
    
    // Whitespace: consume all consecutive
    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        while (buffer.hasRemaining()) {
            buffer.mark();
            c = buffer.get();
            if (!isWhitespace(c)) {
                buffer.reset();
                break;
            }
        }
        return Token.S;
    }
    
    // Special characters
    if (c == '=') return Token.EQ;
    if (c == '"') return Token.QUOT;
    if (c == '\'') return Token.APOS;
    
    // Names: [A-Za-z_:][A-Za-z0-9_:.-]*
    if (isNameStartChar(c)) {
        while (buffer.hasRemaining()) {
            buffer.mark();
            c = buffer.get();
            if (!isNameChar(c)) {
                buffer.reset();
                break;
            }
        }
        return Token.NAME;
    }
    
    // Everything else is CDATA (attribute values)
    buffer.reset();
    return Token.CDATA;
}
```

### Value Extraction

```java
private String extractTokenValue(CharBuffer buffer) {
    int end = buffer.position();
    
    // Find start by scanning backwards
    int start = end - 1;
    while (start > 0) {
        char c = buffer.get(start - 1);
        if (isDelimiter(c)) break;
        start--;
    }
    
    // Extract substring
    char[] chars = new char[end - start];
    for (int i = 0; i < chars.length; i++) {
        chars[i] = buffer.get(start + i);
    }
    
    return new String(chars);
}
```

### Bit Flags for Attributes

```java
short seenAttributes = 0;
// Bit 0 (value 1): version seen
// Bit 1 (value 2): encoding seen  
// Bit 2 (value 4): standalone seen

seenAttributes |= 1;  // Mark version as seen
if ((seenAttributes & 1) == 0) {
    // version not yet seen
}
```

This allows efficient tracking of which attributes have been processed.

## Error Messages

### Missing Required Attribute

```
SAXException: XML declaration missing required 'version' attribute
```

### Wrong Attribute Order

```
SAXException: 'version' must be the first attribute in XML declaration
SAXException: 'version' must come before 'encoding' in XML declaration
SAXException: 'encoding' must come before 'standalone' in XML declaration
```

### Syntax Errors

```
SAXException: Expected whitespace before attribute name in XML declaration
SAXException: Expected '=' after attribute name in XML declaration
SAXException: Expected quote after '=' in XML declaration
SAXException: Expected closing quote in XML declaration
```

### Invalid Values

```
SAXException: Invalid value for 'standalone' attribute: maybe (must be 'yes' or 'no')
SAXException: Unknown attribute in XML declaration: foo
```

## Benefits of Tokenization Approach

### 1. **Well-formedness Checking**
- Validates XML syntax rules
- Catches malformed declarations early
- Provides clear error messages

### 2. **Attribute Order Enforcement**
- Per XML spec: version first, encoding before standalone
- Uses bit flags to track which attributes seen
- Validates order during parsing

### 3. **Preparation for Full Tokenization**
- Same token types used (`S`, `NAME`, `EQ`, `QUOT`, `CDATA`, etc.)
- Same patterns (state machine, token receiver)
- Reusable helper methods (`isNameStartChar`, `isNameChar`)

### 4. **Efficiency**
- Single pass through buffer
- No string allocations for searching
- Direct buffer manipulation

### 5. **Maintainability**
- Clear separation of concerns (tokenize vs. process)
- State machine makes logic explicit
- Easy to add new validations

## Comparison: String vs. Tokenization

| Aspect | String Manipulation | Tokenization |
|--------|---------------------|--------------|
| Well-formedness | ❌ No validation | ✅ Full validation |
| Attribute order | ❌ Not checked | ✅ Enforced |
| Quote matching | ❌ Not validated | ✅ Validated |
| Error messages | ❌ Generic | ✅ Specific |
| Performance | ⚠️ Multiple indexOf calls | ✅ Single pass |
| Complexity | ⚠️ Complex string logic | ✅ Clear state machine |
| Reusability | ❌ Declaration-specific | ✅ Patterns for full parser |

## Testing Scenarios

### Valid Declarations

```xml
<?xml version="1.0"?>
<?xml version="1.0" encoding="UTF-8"?>
<?xml version="1.0" standalone="yes"?>
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?xml version='1.0' encoding='ISO-8859-1' standalone='yes'?>
```

### Invalid - Missing version

```xml
<?xml encoding="UTF-8"?>                     ✗
<?xml standalone="yes"?>                     ✗
```

### Invalid - Wrong order

```xml
<?xml encoding="UTF-8" version="1.0"?>       ✗
<?xml version="1.0" standalone="yes" encoding="UTF-8"?>  ✗
```

### Invalid - Syntax errors

```xml
<?xmlversion="1.0"?>                         ✗ No space
<?xml version=1.0?>                          ✗ No quotes
<?xml version="1.0'?>                        ✗ Mismatched quotes
<?xml version="1.0" encoding?>               ✗ No = or value
```

### Invalid - Bad values

```xml
<?xml version="1.0" standalone="maybe"?>     ✗ Must be yes/no
<?xml version="1.0" foo="bar"?>              ✗ Unknown attribute
```

## Integration with parseXMLDeclaration()

The tokenization is integrated into the XMLDECL state:

```java
private boolean parseXMLDeclaration(ByteBuffer buffer) throws SAXException {
    // ... decode bytes to characters ...
    // ... find "<?xml" and "?>" ...
    
    // Extract content between "<?xml" and "?>"
    charBuffer.position(charBuffer.position() + 5); // Skip "<?xml"
    charBuffer.limit(declEnd - 2); // Exclude "?>"
    CharBuffer declContent = charBuffer.slice();
    
    // Tokenize and parse
    String declaredEncoding = extractXMLDeclarationAttributes(declContent);
    
    // Handle charset switching...
}
```

## Summary

✅ **Complete replacement** of string manipulation with proper tokenization  
✅ **Well-formedness checking** per XML specification  
✅ **Attribute order validation** (version, encoding, standalone)  
✅ **Clear error messages** for all failure cases  
✅ **State machine architecture** ready for full document tokenization  
✅ **Reusable patterns** (nextToken, tokenReceive, state transitions)  
✅ **Efficient single-pass** processing  
✅ **Clean compilation** - no errors  

This establishes the foundation for implementing full document tokenization in the CHARACTERS state! 🎯


