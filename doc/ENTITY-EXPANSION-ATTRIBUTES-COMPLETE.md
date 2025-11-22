# Entity Expansion in Attribute Values - Implementation Complete

**Date**: November 15, 2025  
**Status**: ✅ COMPLETE

## Overview

Implemented full entity expansion support for general entity references in XML attribute values, with proper handling of:
- Internal entities with literal replacement text
- Nested entity references (entities referencing other entities)
- Circular reference detection
- External entity rejection (forbidden by XML spec)
- Unparsed entity rejection (forbidden by XML spec)
- Integration with attribute value normalization

## Implementation

### 1. XMLParser.java - Entity Expansion Methods

#### `expandGeneralEntityInAttributeValue(String entityName)`
Main entry point for expanding a general entity reference in an attribute value.

**Error handling:**
- Throws `SAXParseException` if no DTD present
- Throws `SAXParseException` if entity not declared
- Throws `SAXParseException` if entity is external (forbidden in attribute values)
- Throws `SAXParseException` if entity is unparsed (forbidden in attribute values)

#### `expandEntityValue(List<Object> replacementText, Set<String> visitedEntities)`
Recursively expands an entity value, processing both literal strings and nested entity references.

**Features:**
- Handles `List<Object>` with mixed `String` and `GeneralEntityReference` objects
- Circular reference detection using `visitedEntities` set
- Recursive expansion of nested entity references
- Proper error reporting with locator information

**Error handling:**
- Throws `SAXParseException` on circular references
- Throws `SAXParseException` if nested entity not declared
- Throws `SAXParseException` if nested entity is external/unparsed

### 2. XMLParser.java - Token Handling

Updated `handleAttributeValue()` to process `GENERALENTITYREF` tokens:

```java
} else if (token == Token.GENERALENTITYREF) {
    // General entity reference: &name;
    String entityName = extractString(data);
    String expandedValue = expandGeneralEntityInAttributeValue(entityName);
    currentAttributeValue.append(expandedValue);
}
```

### 3. XMLTokenizer.java - Context-Aware Tokenization

Fixed tokenization of DTD-specific characters (`[`, `]`, `(`, `)`, `*`, `+`, `,`, `|`) to only emit special tokens outside of content contexts.

**Modified characters:**
- `[` - OPEN_BRACKET token only in DOCTYPE contexts
- `]` - CLOSE_BRACKET token only in DOCTYPE contexts (with special handling for `]]>`)
- `(`, `)` - OPEN_PAREN/CLOSE_PAREN tokens only in DOCTYPE contexts
- `*`, `+` - STAR/PLUS tokens only in DOCTYPE contexts
- `,` - COMMA token only in DOCTYPE contexts
- `|` - PIPE token only in DOCTYPE contexts

**Behavior in ATTR_VALUE, CONTENT, COMMENT, CDATA_SECTION, PI_DATA contexts:**
- These characters are treated as regular text and emitted as CDATA tokens

**Example:**
```xml
<root attr="[value]"/>  <!-- [ and ] emitted as CDATA -->
<!ELEMENT root (#PCDATA)>  <!-- ( and ) emitted as OPEN_PAREN/CLOSE_PAREN -->
```

## Integration with Attribute Normalization

Entity expansion occurs **before** attribute value normalization:

1. **Token accumulation**: GENERALENTITYREF tokens trigger entity lookup and expansion
2. **Entity expansion**: Entity value is recursively expanded to final string
3. **Append to value**: Expanded string appended to `currentAttributeValue`
4. **Normalization**: When closing quote encountered, complete value normalized

This ordering ensures:
- Whitespace in entity values is properly normalized
- Type-based normalization (CDATA vs non-CDATA) applies to expanded text
- Entity references in entity values are recursively expanded

## Tests

### EntityRefInAttributeTest.java

Comprehensive test suite with 6 tests:

1. **Simple entity reference**: `&copy;` → `"Copyright 2025"`
2. **Nested entity references**: `&outer;` where outer contains `&inner;`
3. **Circular reference detection**: `&a;` references `&b;`, `&b;` references `&a;`
4. **Undefined entity error**: `&undefined;` with no declaration
5. **External entity forbidden**: `&external;` with SYSTEM identifier
6. **Unparsed entity forbidden**: `&logo;` with NDATA notation

### EntityAndNormalizationTest.java

Tests integration with attribute normalization:

1. **Entity with whitespace**: Tabs in entity value normalized to spaces
2. **Entity in ID attribute**: Leading/trailing spaces trimmed for ID type
3. **Multiple entities with NMTOKENS**: Multiple spaces collapsed

## XML Specification Compliance

✅ **XML 1.0 Specification, Section 4.1**: Entity References
- General entities properly looked up from DTD
- External entities forbidden in attribute values
- Unparsed entities forbidden in attribute values

✅ **XML 1.0 Specification, Section 3.3**: Attribute-Value Normalization
- Entity expansion occurs during attribute value processing
- Normalization applied to expanded text

✅ **Well-formedness constraints**:
- Undefined entity references cause fatal errors
- External entity references in attribute values cause fatal errors
- Circular entity references cause fatal errors

## Error Reporting

All errors reported as `SAXParseException` with:
- Descriptive error messages
- Current `Locator` for line/column information
- Entity name in error message for debugging

**Examples:**
```
General entity reference '&undefined;' used but entity not declared
External general entity reference '&external;' is forbidden in attribute values
Circular entity reference detected: &b;
```

## Performance Considerations

- Entity lookups use interned strings for efficient HashMap access
- Circular reference detection uses `HashSet<String>` with O(1) lookups
- Each recursion level creates new `HashSet` to avoid mutation issues
- No unnecessary string copying during expansion

## Limitations and Future Work

### Not Yet Implemented

1. **Entity expansion in element content**: Similar logic needed for `handleElementContent()`
   - External entities ARE allowed in element content
   - Will require async entity resolution architecture

2. **Parameter entity expansion**: Currently throws TODO exception
   - Only relevant in DTD entity values
   - Less critical for most use cases

3. **Default attribute values**: Entity references in default values not yet expanded
   - Requires integration with ATTLIST processing

### Known Issues

None - all tests pass and existing tests still work correctly.

## Files Modified

1. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/XMLParser.java`
   - Added `expandGeneralEntityInAttributeValue()` method
   - Added `expandEntityValue()` method
   - Updated `handleAttributeValue()` to call expansion

2. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/XMLTokenizer.java`
   - Fixed context-aware handling of `[`, `]`, `(`, `)`, `*`, `+`, `,`, `|`
   - Removed duplicate `case ']':` block

## Files Created

1. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/test/EntityRefInAttributeTest.java`
   - Comprehensive entity expansion tests

2. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/test/EntityAndNormalizationTest.java`
   - Integration tests with normalization

3. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/doc/ENTITY-EXPANSION-ATTRIBUTES-COMPLETE.md`
   - This documentation

## Test Results

All tests pass:
```
✓ EntityRefInAttributeTest - 6/6 tests passed
✓ EntityAndNormalizationTest - 3/3 tests passed
✓ AttributeNormalizationTest - 4/4 tests passed (no regression)
✓ QuickATTLISTTest - passed (no regression)
✓ EntityDeclTest - 5/5 tests passed (no regression)
✓ EntityRefTokenTest - 4/4 tests passed (no regression)
```

## Conclusion

Entity expansion in attribute values is now fully functional and compliant with the XML specification. The implementation correctly handles:
- Simple and nested entity references
- Error cases (undefined, external, unparsed, circular)
- Integration with attribute value normalization
- Context-aware tokenization of special characters

The next step would be to implement entity expansion in element content, which will require additional work for external entity resolution.

