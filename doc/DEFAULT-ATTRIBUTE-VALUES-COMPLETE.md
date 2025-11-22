# Default Attribute Values - Implementation Complete

**Date**: 2025-11-15  
**Status**: ✅ Complete

## Overview

Successfully implemented the application of default attribute values from DTD declarations to element start tags. This feature ensures that attributes declared with default values or `#FIXED` values in `<!ATTLIST>` declarations are automatically applied to elements when those attributes are not explicitly specified in the XML document.

## Implementation

### 1. Core Method: `applyDefaultAttributeValues()`

Added method in `XMLParser.java` (lines 1218-1290) that:
- Queries `DTDParser` for attribute declarations for the current element
- For each declared attribute:
  - **Default values** (no mode): If not specified, expands and adds with `specified=false`
  - **#FIXED values**: If not specified, expands and adds with `specified=false`; if specified, validates that it matches the fixed value
  - **#REQUIRED**: No default applied (validation would fail if missing)
  - **#IMPLIED**: No default applied (attribute is optional)

### 2. Integration Points

The method is called at **four** strategic points in `XMLParser.java`, just before `contentHandler.startElement()`:

1. **Line 736**: `ELEMENT_TAG` state, `GT` token - element with no explicitly specified attributes
2. **Line 754**: `ELEMENT_TAG` state, `END_EMPTY_ELEMENT` token - empty element with no attributes
3. **Line 791**: `ELEMENT_ATTRS` state, `GT` token - element with attributes
4. **Line 803**: `ELEMENT_ATTRS` state, `END_EMPTY_ELEMENT` token - empty element with attributes

This ensures default values are applied regardless of whether the element has explicit attributes or not.

### 3. Entity Expansion in Default Values

Default values are stored as `List<Object>` (containing `String` and `GeneralEntityReference` objects), enabling:
- Deferred expansion of entity references
- Proper context-aware expansion using `EntityExpansionHelper`
- Detection of circular references
- Validation of forbidden entity types (external, unparsed)

```java
EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
String expandedValue = helper.expandEntityValue(decl.defaultValue, EntityExpansionContext.ATTRIBUTE_VALUE);
```

### 4. Attribute Value Normalization

After expansion, default values are normalized according to their declared type:
- **CDATA**: Whitespace preserved (newlines normalized to spaces)
- **Non-CDATA** (ID, IDREF, NMTOKEN, etc.): Leading/trailing whitespace trimmed, internal whitespace collapsed

```java
String normalizedValue = normalizeAttributeValue(expandedValue, elementName, decl.name);
```

### 5. Specified Flag (Attributes2)

Default attributes are added with `specified=false`, correctly implementing the SAX2 `Attributes2` interface:
```java
attributes.addAttribute("", decl.name, decl.name, decl.type, normalizedValue, false);
```

## Test Coverage

Created comprehensive test suite in `test/DefaultAttributeValueTest.java`:

### Test 1: Simple Default Value
```xml
<!ATTLIST root attr CDATA "default value">
<root/>
```
**Result**: Attribute `attr="default value"` applied with `specified=false`

### Test 2: Default Value with Entity Reference
```xml
<!ENTITY copy "Copyright 2025">
<!ATTLIST root attr CDATA "&copy;">
<root/>
```
**Result**: Attribute `attr="Copyright 2025"` applied (entity expanded)

### Test 3: #FIXED Value Applied
```xml
<!ATTLIST root version CDATA #FIXED "1.0">
<root/>
```
**Result**: Attribute `version="1.0"` applied with `specified=false`

### Test 4: #FIXED Value Mismatch
```xml
<!ATTLIST root version CDATA #FIXED "1.0">
<root version='2.0'/>
```
**Result**: `SAXParseException` - specified value doesn't match fixed value

### Test 5: Multiple Default Values
```xml
<!ATTLIST root
  a CDATA "valueA"
  b CDATA "valueB"
  c CDATA "valueC"
>
<root/>
```
**Result**: All three attributes applied

### Test 6: Specified Flag (Attributes2)
```xml
<!ATTLIST root
  specified CDATA "default1"
  defaulted CDATA "default2"
>
<root specified='value1'/>
```
**Result**: 
- `specified="value1"` with `specified=true`
- `defaulted="default2"` with `specified=false`

## XML Specification Compliance

### Section 3.3.2: Attribute Defaults

✅ **Default values**: Applied when attribute not specified  
✅ **#REQUIRED**: Not applied (would trigger validation error)  
✅ **#IMPLIED**: Not applied (optional attribute)  
✅ **#FIXED**: Applied if not specified, validated if specified

### Section 4.4: Entity Expansion

✅ Entity references in default values are expanded before application  
✅ External entities forbidden in attribute values (enforced)  
✅ Unparsed entities forbidden in attribute values (enforced)  
✅ Circular references detected and reported

### SAX2 Attributes2 Interface

✅ `isSpecified()` correctly returns `false` for default attributes  
✅ `isSpecified()` correctly returns `true` for explicitly specified attributes

## Benefits

1. **Complete SAX2 Compliance**: Applications can distinguish default from specified attributes
2. **Proper Entity Expansion**: Entity references in defaults expanded with full context awareness
3. **Error Reporting**: Errors in entity expansion reported before `startElement()`, as required
4. **Performance**: Only queries DTD when present, minimal overhead for documents without DTDs

## Architecture

The implementation leverages:
- **`AttributeDeclaration.defaultValue`**: `List<Object>` structure for deferred expansion
- **`EntityExpansionHelper`**: Generic, context-aware expansion mechanism
- **`normalizeAttributeValue()`**: Existing normalization infrastructure
- **`SAXAttributes.addAttribute()`**: Existing attribute management with `specified` flag

## Next Steps

With default attribute values complete, the core non-validating parser features are largely implemented. Remaining items:
- Parameter entity expansion in DTD context
- Namespace processing (SAX2 namespace features)
- External entity resolution with blocking I/O
- Full XML 1.0 conformance testing

## Related Documentation

- `ATTLIST-DEFAULT-VALUES-STRUCTURE.md` - Data structure refactoring
- `CONTEXT-AWARE-ENTITY-EXPANSION.md` - Entity expansion framework
- `ENTITY-EXPANSION-ATTRIBUTES-COMPLETE.md` - Entity expansion in attribute values
- `TOKEN-BASED-MODE-REFACTORING.md` - Using `Token` enum for mode values

