# SAXParseException Migration

**Date**: November 15, 2025  
**Status**: Complete

## Overview

All parsing exceptions in the Gonzalez XML parser now throw `SAXParseException` instead of the generic `SAXException`, and include proper locator information for accurate error reporting.

## Changes

### XMLTokenizer
- All `throw new SAXException(...)` changed to `throw new org.xml.sax.SAXParseException(..., this)`
- XMLTokenizer implements `Locator2`, so it passes itself as the locator
- This applies to all tokenization errors including:
  - XML declaration parsing errors
  - Invalid character encoding errors
  - BOM/encoding mismatch errors
  - Invalid entity reference errors
  - Malformed token errors
  - Token length limit errors (added 1024 character maximum for entity references)

### DTDParser
- Added `import org.xml.sax.SAXParseException`
- All `throw new SAXException(...)` changed to `throw new SAXParseException(..., locator)`
- DTDParser stores a `Locator` reference set by `XMLParser.setLocator()`
- This applies to all DTD parsing errors including:
  - DOCTYPE declaration errors
  - ELEMENT declaration errors
  - ATTLIST declaration errors
  - NOTATION declaration errors
  - Comment and PI errors in DTD context
  - External DTD resolution errors

### XMLParser
- Added `import org.xml.sax.SAXParseException`
- All `throw new SAXException(...)` changed to `throw new SAXParseException(..., locator)`
- XMLParser stores a `Locator` reference from the `XMLTokenizer`
- This applies to all parsing errors including:
  - Element structure errors
  - Attribute parsing errors
  - Entity reference errors
  - Processing instruction errors
  - State machine violations

## Entity Reference Underflow Handling

Enhanced `tryEmitEntityRef()` and `tryEmitParameterEntityRef()` methods to properly handle incomplete entity references:

### Previous Behavior
- Would scan to end of buffer looking for `;`
- No limit on how long an entity reference could be
- Would return `false` if `;` not found, but no length check

### New Behavior
- Scans buffer with a `MAX_ENTITY_REF_LENGTH` limit of 1024 characters
- Throws `SAXParseException` if entity reference exceeds limit
- Returns `false` (need more data) if `;` not found within buffer
- Properly marks position and resets when underflow occurs
- Acts like `NAME` token: waits for complete token or definitive error

### Example
```java
// Entity reference spanning multiple receive() calls
First receive:  "&verylongen"
Second receive: "tityname;"

// Tokenizer behavior:
// - First receive: tryEmitEntityRef() returns false (no `;` yet)
// - Buffer underflow saved
// - Second receive: completes entity ref, emits GENERALENTITYREF
```

This ensures entity references are handled asynchronously like all other tokens.

## Benefits

1. **Better Error Messages**: All exceptions now include line and column information
2. **SAX Compliance**: Proper use of `SAXParseException` per SAX2 specification
3. **Debugging**: Easier to locate errors in source XML documents
4. **Consistency**: All three parser components use the same exception pattern
5. **Underflow Safety**: Entity references properly handle incomplete data across buffer boundaries

## Example Error Output

**Before**:
```
org.xml.sax.SAXException: Invalid character in entity reference: '&@'
```

**After**:
```
org.xml.sax.SAXParseException: Invalid character in entity reference: '&@'
  Line: 42
  Column: 15
  SystemId: file:///path/to/document.xml
```

## Testing

All existing tests pass with the new exception types:
- `EntityRefTokenTest` - Entity reference tokenization with underflow
- `AttributeNormalizationTest` - Attribute value processing
- `QuickATTLISTTest` - ATTLIST declaration parsing
- `NotationDeclTest` - NOTATION declaration parsing

## Implementation Notes

- `SAXParseException` constructors used:
  - `SAXParseException(String message, Locator locator)`
  - `SAXParseException(String message, Locator locator, Exception cause)`
- XMLTokenizer uses `this` as locator (implements `Locator2`)
- DTDParser and XMLParser use stored `locator` field
- Entity reference length limit prevents unbounded memory usage during underflow

