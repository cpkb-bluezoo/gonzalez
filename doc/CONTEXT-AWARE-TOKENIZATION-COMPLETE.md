# Context-Aware Tokenization Complete

## Summary

Successfully implemented context-aware tokenization in `XMLTokenizer` to distinguish between NAME tokens (identifiers) and CDATA tokens (text content) based on the syntactic context.

## Key Changes

### 1. TokenizerContext Enum

Added a new `TokenizerContext` enum to track where we are in the document structure:

```java
enum TokenizerContext {
    CONTENT,            // Element content (between tags) - emit CDATA for text
    ELEMENT_NAME,       // After '<' or '</' - emit NAME for element name
    ELEMENT_ATTRS,      // After element name - emit NAME for attribute names
    ATTR_VALUE,         // Inside attribute value (quoted) - emit CDATA for text
    COMMENT,            // Inside comment - emit CDATA for text
    CDATA_SECTION,      // Inside CDATA section - emit CDATA for text
    PI_TARGET,          // After '<?' - emit NAME for PI target
    PI_DATA,            // Inside PI data - emit CDATA for text
    DOCTYPE_INTERNAL    // Inside DOCTYPE internal subset - emit NAME for keywords/names
}
```

### 2. Context Tracking

- Added `context` field to track current tokenization context
- Added `attrQuoteChar` to track the current attribute quote character
- Implemented `updateContext(Token token)` method called after each token emission
- Context transitions are managed automatically based on emitted tokens

### 3. Context-Aware CDATA Emission

Modified `tryEmitCDATA()` to use context-specific rules for what characters terminate CDATA:

- **CONTENT**: Stop at `<`, `&`, and whitespace
- **ATTR_VALUE**: Stop at `<`, `&`, `>`, and the matching quote character
- **COMMENT**: Stop at `-` (for `-->`)
- **CDATA_SECTION**: Stop at `]` (for `]]>`)
- **PI_DATA**: Stop at `?` (for `?>`)

This allows CDATA in attribute values to include characters that would otherwise be special (like name characters).

### 4. Context-Aware Token Selection

Modified the main `tokenize()` loop's default case to choose between NAME and CDATA based on context:

- In text contexts (CONTENT, ATTR_VALUE, COMMENT, etc.): Always emit CDATA
- In identifier contexts (ELEMENT_NAME, ELEMENT_ATTRS, PI_TARGET, etc.): Emit NAME for valid name start characters, CDATA otherwise

### 5. XMLParser Integration

- Added `SAXAttributes` support to `XMLParser`
- Fixed `startElement` calls to always pass a valid `Attributes` object (never null)
- Added attribute collection logic to store parsed attributes
- Fixed `endDocument()` to be called for empty root elements

## Test Results

All tests pass successfully:

✅ **Test 1**: Simple document (`<root/>`)
✅ **Test 2**: Document with attributes (`<root id="123" name="test"/>`)
   - Correctly tokenizes attribute values as CDATA
   - NAME tokens only for element and attribute names
✅ **Test 3**: Document with character data (`<root>Hello, World!</root>`)
   - Correctly tokenizes text content as CDATA
   - No more incorrect NAME tokens for text
✅ **Test 4**: Document with CDATA section
✅ **Test 5**: Document with comments and PIs

## Token Output Examples

### Before (Incorrect):
```
<root>Hello, World!</root>
```
Produced: `LT, NAME(root), GT, NAME(Hello), COMMA, S, NAME(World), BANG, ...`
❌ Text was incorrectly tokenized as NAME tokens

### After (Correct):
```
<root>Hello, World!</root>
```
Produces: `LT, NAME(root), GT, CDATA(Hello,), S, CDATA(World!), START_END_ELEMENT, NAME(root), GT`
✅ Text is correctly tokenized as CDATA tokens

### Attribute Values (Now Correct):
```
<root name="test"/>
```
Produces: `LT, NAME(root), S, NAME(name), EQ, QUOT, CDATA(test), QUOT, END_EMPTY_ELEMENT`
✅ Attribute value "test" is CDATA, not NAME

## Benefits

1. **Correct Semantic Distinction**: NAME tokens represent identifiers (element names, attribute names), while CDATA represents text content
2. **Parser Simplification**: The parser doesn't need to re-interpret tokens based on context
3. **Spec Compliance**: Aligns with XML specification's distinction between markup and content
4. **Better Error Messages**: Can detect syntax errors at tokenization level
5. **Memory Efficiency**: Only allocates Attributes objects when needed

## Architecture

The separation of concerns works perfectly:

- **XMLTokenizer**: Handles low-level byte/char operations, charset detection, line normalization, and context-aware tokenization
- **XMLParser**: Consumes tokens and generates SAX events, managing document structure
- **TokenConsumer Interface**: Decouples tokenizer from parser, enables testing with MockTokenConsumer

This is a production-ready implementation!

