# Attribute Value Normalization - Summary

## Status: ✅ COMPLETE (except general entity references)

## Implementation Date
November 15, 2025

## What Was Implemented

### Core Functionality
Implemented full attribute value normalization per XML Specification Section 3.3.3:

1. **Whitespace Normalization**: All whitespace characters (`\t`, `\n`, `\r`) replaced with space (`#x20`)
2. **Type-Based Processing**: 
   - **CDATA attributes**: Preserve all spaces (no trimming/collapsing)
   - **Non-CDATA attributes** (ID, IDREF, NMTOKEN, etc.): Trim leading/trailing spaces and collapse space sequences
3. **DTD Integration**: Query `DTDParser` for attribute type, default to CDATA if no DTD
4. **Entity Reference Support**: Predefined entities and character references already handled by tokenizer

## Files Modified

### `src/org/bluezoo/gonzalez/XMLParser.java`
- **Modified `handleAttributeValue()`**: Added normalization call on closing quote, added `S` token handling
- **Added `normalizeAttributeValue()`**: Main normalization algorithm (lines 1163-1214)

## Tests Created

### `test/AttributeNormalizationTest.java`
Basic normalization tests:
- ✅ Test 1: Whitespace normalization (CDATA)
- ✅ Test 2: Non-CDATA normalization (with DTD)
- ✅ Test 3: Entity references in attribute values
- ✅ Test 4: Mixed whitespace types

### `test/AttributeNormalizationEdgeCasesTest.java`
Edge case tests:
- ✅ Test 1: Empty attribute values
- ✅ Test 2: Only whitespace
- ✅ Test 3: Consecutive entity references
- ✅ Test 4: No DTD (all CDATA)
- ✅ Test 5: Multiple attributes per element

## Test Results

All tests pass successfully:
```
AttributeNormalizationTest:
  ✓ Whitespace replaced with spaces in CDATA attribute
  ✓ Non-CDATA attributes trimmed and collapsed
  ✓ Entity references expanded correctly
  ✓ Mixed whitespace normalized correctly

AttributeNormalizationEdgeCasesTest:
  ✓ Empty attributes remain empty
  ✓ Whitespace-only values handled correctly
  ✓ Consecutive entities expanded correctly
  ✓ No DTD: attributes treated as CDATA
  ✓ Multiple attributes normalized correctly
```

Existing tests still pass:
- ✅ `QuickATTLISTTest`
- ✅ `SimpleCommentTest`
- ✅ `XMLParserCommentPITest`
- ✅ `DTDDeclarationsTest` (Tests 1-3; Test 4 has pre-existing tokenizer issue)

## Documentation Created

### `doc/ATTRIBUTE-VALUE-NORMALIZATION.md`
Comprehensive documentation covering:
- Implementation details and algorithm
- DTD integration strategy
- Test coverage description
- Pending work (general entity references)
- Specification compliance notes

## Pending Work

### General Entity References (TODO)

Currently throws error when encountering `AMP` token in attribute values:
```java
} else if (token == Token.AMP) {
    // TODO: Look up entity in DTD and resolve (may be external)
    throw new SAXException("General entity references in attribute values not yet supported");
}
```

**Required for completion:**
1. Accumulate entity name after `AMP` token
2. Wait for `SEMICOLON` to complete reference
3. Query `DTDParser.getEntityDeclaration(entityName)`
4. For internal entities: Append replacement text
5. For external entities: Use `processExternalEntity()` mechanism
6. Handle recursive normalization (entity text may contain more entities)
7. Error if entity undeclared (well-formedness violation)

## Specification Compliance

✅ **XML 1.0 Specification, Section 3.3.3**: Attribute-Value Normalization  
✅ **XML 1.0 Specification, Section 2.11**: End-of-Line Handling

### Key Requirements Met

> "All line breaks MUST have been normalized on input to #xA"
- ✅ Handled by `XMLTokenizer`

> "For a white space character (#x20, #xD, #xA, #x9), append a space character (#x20)"
- ✅ Implemented in normalization algorithm

> "If the attribute type is not CDATA, then the XML processor MUST further process the normalized attribute value by discarding any leading and trailing space (#x20) characters, and by replacing sequences of space (#x20) characters by a single space (#x20) character."
- ✅ Implemented with DTD type checking

> "All attributes for which no declaration has been read SHOULD be treated by a non-validating processor as if declared CDATA."
- ✅ Defaults to `"CDATA"` when no DTD

## Architecture Notes

The asynchronous token-based architecture naturally supports:
- Multi-chunk attribute values (values split across `receive()` calls)
- Incremental accumulation during parsing
- Lazy normalization (applied only after complete value received)
- Clean DTD integration (separation of concerns)

This ensures correctness even with large attribute values arriving in small chunks.

## Next Steps

After general entity reference support is implemented:
1. Add tests for general entity references in attribute values
2. Add tests for external entity references in attribute values
3. Add tests for recursive entity references
4. Add well-formedness error tests (undeclared entities)
5. Performance testing with large attribute values

## Related Issues

None - This is new functionality. Pre-existing issue in `DTDDeclarationsTest` Test 4 (XMLTokenizer context in external DTD) is unrelated to this implementation.

