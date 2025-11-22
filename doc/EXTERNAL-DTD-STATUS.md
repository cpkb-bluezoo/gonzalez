# External DTD Processing Implementation - Status

## What Was Implemented ✅

### 1. Infrastructure
- ✅ **DefaultEntityResolver** - Automatic URL-based entity resolution
- ✅ **Package-private `processExternalEntity()`** in XMLParser
- ✅ **XMLParser reference** in DTDParser (final field)
- ✅ **Feature flag checking** - Distinguishes between external parameter entities (DTD) and general entities (content)
- ✅ **`processExternalDTDSubset()`** method in DTDParser

### 2. Architecture
The design is in place:
1. DTDParser detects external ID (SYSTEM/PUBLIC) in DOCTYPE declaration
2. Calls `processExternalDTDSubset()` at appropriate time (after external ID, before internal subset)
3. Uses `XMLParser.processExternalEntity()` to resolve and parse external DTD
4. Nested XMLTokenizer created for external DTD content
5. Tokens sent back to parser pipeline

## Current Issue ❌

**Problem**: Tokenizer context handling for quoted strings in DOCTYPE

**Symptom**:
```
TEST FAILED: Expected quoted string for external ID, got: NAME
org.xml.sax.SAXException: Expected quoted string for external ID, got: NAME
    at org.bluezoo.gonzalez.DTDParser.handleAfterSystemPublic(DTDParser.java:294)
```

**Root Cause**:
When tokenizing `<!DOCTYPE root SYSTEM "http://example.com/dtd.dtd">`, the quoted string `"http://example.com/dtd.dtd"` is being tokenized as:
- `QUOT` (opening quote) ✓
- `NAME` (the content) ❌ Should be `CDATA`
- Not reaching closing quote

The tokenizer's context-aware logic needs to recognize that inside quoted strings in DOCTYPE declarations, content should be tokenized as CDATA, not NAME.

**Why it happens**:
- `http://example.com/dtd.dtd` contains characters that match the NAME production (letters, digits, colons, etc.)
- The tokenizer is not tracking that it's inside a quoted string in DOCTYPE context
- The `updateContext()` method doesn't handle QUOT/APOS tokens to switch to `ATTR_VALUE` context

## What Needs to Be Fixed 🔧

### Immediate Fix Required

**Update XMLTokenizer context tracking for DOCTYPE**:

1. **Track quote state in DOCTYPE context**:
   - When in `TokenizerContext.CONTENT` (or a new `DOCTYPE` context)
   - And we see `QUOT` or `APOS`
   - Switch to `ATTR_VALUE` context
   - Track the quote character
   - When we see the matching quote, switch back

2. **Option A**: Extend existing `ATTR_VALUE` context
   ```java
   case QUOT:
   case APOS:
       if (context == TokenizerContext.CONTENT || context == TokenizerContext.DOCTYPE_INTERNAL) {
           // Entering quoted string
           context = TokenizerContext.ATTR_VALUE;
           attrQuoteChar = (token == Token.QUOT) ? '"' : '\'';
       } else if (context == TokenizerContext.ATTR_VALUE && 
                  ch == attrQuoteChar) {
           // Exiting quoted string
           context = TokenizerContext.CONTENT;
           attrQuoteChar = '\0';
       }
       break;
   ```

3. **Option B**: Create DOCTYPE-specific contexts
   - `DOCTYPE_EXTERNAL_ID` - for quoted public/system IDs
   - Simpler and more explicit

### Testing Strategy

**Phase 1**: Fix DOCTYPE SYSTEM ID tokenization
```xml
<!DOCTYPE root SYSTEM "file.dtd">
<root/>
```
Expected tokens:
- START_DOCTYPE, S, NAME(root), S, SYSTEM, S, QUOT, CDATA(file.dtd), QUOT, GT

**Phase 2**: Test with external entities disabled
```xml
<!DOCTYPE root SYSTEM "http://example.com/dtd.dtd">
<root/>
```
Should parse successfully without trying to resolve the DTD.

**Phase 3**: Test with actual external DTD files
```xml
<!-- main.xml -->
<!DOCTYPE root SYSTEM "external.dtd">
<root><title>Test</title></root>

<!-- external.dtd -->
<!ELEMENT root (title)>
<!ELEMENT title (#PCDATA)>
```

**Phase 4**: Handle external DTD subset processing
This requires additional work because external DTD content contains markup declarations, not DOCTYPE declaration syntax. The tokenizer will produce tokens like:
- START_ELEMENTDECL, NAME(root), ...

These need to be processed by DTDParser, but DTDParser's state machine needs to handle being in "external subset mode" vs "DOCTYPE declaration mode".

## Architectural Consideration 🏗️

### External DTD Subset Processing

The XML spec distinguishes between:
1. **DOCTYPE declaration** (in main document):
   ```xml
   <!DOCTYPE root SYSTEM "file.dtd" [ internal subset ]>
   ```
   
2. **External DTD subset** (in file.dtd):
   ```
   <!ELEMENT root (title)>
   <!ATTLIST root id ID #REQUIRED>
   <!ENTITY footer "Copyright 2025">
   ```

These have different grammar productions and should be handled by different states in DTDParser:
- **DOCTYPE mode**: Parsing the DOCTYPE declaration syntax (SYSTEM, PUBLIC, [, ])
- **MarkupDecl mode**: Parsing markup declarations (ELEMENT, ATTLIST, ENTITY, NOTATION, PI, comment)

**Solution**: DTDParser needs a flag or state to indicate whether it's processing:
- The DOCTYPE declaration itself (current behavior)
- An external DTD subset (markup declarations only)
- An internal DTD subset (markup declarations between [ and ])

## Files Modified

- ✅ `src/org/bluezoo/gonzalez/DefaultEntityResolver.java` - New file
- ✅ `src/org/bluezoo/gonzalez/XMLParser.java` - Made `processExternalEntity()` package-private, added feature flag checking
- ✅ `src/org/bluezoo/gonzalez/DTDParser.java` - Added XMLParser reference, `processExternalDTDSubset()` method
- ✅ `test/ExternalDTDTest.java` - Comprehensive test (currently failing)
- ✅ `test/SimpleDOCTYPETest.java` - Basic DOCTYPE test (partially passing)

## Next Steps 📋

1. **Fix tokenizer context for DOCTYPE quoted strings** (high priority)
   - Update `updateContext()` in XMLTokenizer
   - Handle QUOT/APOS in DOCTYPE context
   - Produce CDATA tokens inside quotes

2. **Test basic DOCTYPE with SYSTEM ID** (external entities disabled)
   - Should parse without errors
   - Should not try to resolve external DTD

3. **Implement DTDParser external subset mode**
   - Add flag to distinguish DOCTYPE vs external subset processing
   - Handle markup declarations in external DTD

4. **Test full external DTD resolution**
   - Create actual DTD files
   - Parse documents that reference them
   - Verify declarations are processed correctly

5. **Handle TextDecl in external entities**
   - External DTD subsets can have TextDecl (like XML declaration)
   - Tokenizer needs to handle this

## Summary

The infrastructure for external DTD processing is in place and well-designed. The immediate blocker is a tokenization issue: quoted strings in DOCTYPE declarations are being tokenized as NAME instead of CDATA. Once this is fixed, we can proceed with testing and refining the external DTD processing logic.

The architecture correctly separates concerns:
- **DefaultEntityResolver** - URL resolution
- **XMLParser.processExternalEntity()** - Entity loading and nested tokenizer creation
- **DTDParser.processExternalDTDSubset()** - Triggering external DTD processing
- **Feature flags** - Control over external entity processing

After the tokenization fix, the main remaining work is ensuring DTDParser can handle both DOCTYPE declaration syntax and markup declaration syntax in the appropriate contexts.

