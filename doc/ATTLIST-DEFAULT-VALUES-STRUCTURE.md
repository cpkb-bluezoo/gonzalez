# ATTLIST Default Value with Entity References - Implementation Complete

**Date**: November 15, 2025  
**Status**: ✅ COMPLETE

## Summary

Successfully updated ATTLIST default value parsing to use `List<Object>` structure (like `EntityDeclaration.replacementText`) containing mixed `String` and `GeneralEntityReference` objects. This allows entity references in default values to be properly stored for later expansion.

## Changes Made

### 1. AttributeDeclaration.java

**Before:**
```java
public String defaultValue;
```

**After:**
```java
/**
 * The default value, or null if not specified or if mode is #REQUIRED or #IMPLIED.
 * Each element is either a String (literal text) or a GeneralEntityReference
 * (entity reference to be expanded when the attribute is used).
 */
public List<Object> defaultValue;
```

**Updated methods:**
- `toString()` - Now iterates through List<Object> to build string representation

### 2. DTDParser.java

**Added fields:**
```java
private List<Object> defaultValueBuilder;  // List of String and GeneralEntityReference
private StringBuilder defaultValueTextBuilder;  // For accumulating text segments
```

**Updated ATTLIST parsing logic:**

#### Initialization (when quote encountered):
```java
case QUOT:
case APOS:
    // Initialize accumulators
    defaultValueBuilder = new ArrayList<>();
    defaultValueTextBuilder = new StringBuilder();
    break;
```

#### Text accumulation:
```java
case CDATA:
case S:
    // Accumulate text
    defaultValueTextBuilder.append(extractString(data));
    break;
    
case ENTITYREF:
    // Predefined entity or character reference (already expanded)
    defaultValueTextBuilder.append(extractString(data));
    break;
```

#### Entity reference handling:
```java
case GENERALENTITYREF:
    // General entity reference - flush text and add reference
    if (defaultValueTextBuilder.length() > 0) {
        defaultValueBuilder.add(defaultValueTextBuilder.toString());
        defaultValueTextBuilder.setLength(0);
    }
    String entityName = extractString(data);
    defaultValueBuilder.add(new GeneralEntityReference(entityName));
    break;
```

#### Finalization (closing quote):
```java
case QUOT:
case APOS:
    // Flush final text segment
    if (defaultValueTextBuilder.length() > 0) {
        defaultValueBuilder.add(defaultValueTextBuilder.toString());
    }
    // Set default value on attribute
    currentAttributeDecl.defaultValue = defaultValueBuilder;
    defaultValueBuilder = null;
    defaultValueTextBuilder = null;
    saveCurrentAttribute();
    attListDeclState = AttListDeclState.EXPECT_ATTR_OR_GT;
    break;
```

## Test Coverage

Created `AttlistDefaultValueTest.java` with 3 comprehensive tests:

### ✅ Test 1: Simple Default Value
```xml
<!ATTLIST root attr CDATA "default value">
```
**Result**: `defaultValue = ["default value"]` (single String)

### ✅ Test 2: Default with Entity Reference
```xml
<!ENTITY copy "Copyright 2025">
<!ATTLIST root attr CDATA "before &copy; after">
```
**Result**: `defaultValue = ["before ", GeneralEntityReference("copy"), " after"]`

### ✅ Test 3: #FIXED Value with Entity Reference
```xml
<!ENTITY version "1.0">
<!ATTLIST root ver CDATA #FIXED "v&version;">
```
**Result**: `defaultValue = ["v", GeneralEntityReference("version")]`

## Architecture Benefits

### Consistency with Entity Values
Both entity declarations and attribute declarations now use the same structure:
- `EntityDeclaration.replacementText`: `List<Object>`
- `AttributeDeclaration.defaultValue`: `List<Object>`

### Deferred Expansion
Entity references in default values aren't expanded during DTD parsing. This allows:
1. Forward references (entity declared after ATTLIST)
2. Lazy expansion (only when attribute is actually used)
3. Consistent expansion mechanism (via `EntityExpansionHelper`)

### Ready for Application
The framework is now in place to apply default values. Next steps:
1. In `XMLParser`, before calling `startElement()`
2. Check if element has default attributes in DTD
3. For missing attributes, expand the `List<Object>` using `EntityExpansionHelper`
4. Add to `SAXAttributes` with `specified=false`

## Example Usage (Next Step)

```java
// In XMLParser.handleElementAttrs(), before startElement():
if (dtdParser != null) {
    Map<String, AttributeDeclaration> attrDecls = 
        dtdParser.getAttributeDeclarations(currentElementName);
    
    if (attrDecls != null) {
        for (AttributeDeclaration decl : attrDecls.values()) {
            // Check if attribute has default and wasn't specified
            if (decl.defaultValue != null && !attributes.hasAttribute(decl.name)) {
                // Expand entity references in default value
                EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
                String expandedValue = helper.expandEntityValue(
                    decl.defaultValue, 
                    EntityExpansionContext.ATTRIBUTE_VALUE
                );
                
                // Add to attributes with specified=false
                attributes.addAttribute("", decl.name, decl.name, 
                    decl.type, expandedValue, false);
            }
        }
    }
}
```

## All Tests Pass

```
✓ AttlistDefaultValueTest - 3/3 tests passed (NEW)
✓ QuickATTLISTTest - passed (no regression)
✓ EntityRefInContentTest - 7/7 tests passed
✓ EntityRefInAttributeTest - 6/6 tests passed
✓ EntityAndNormalizationTest - 3/3 tests passed
```

## Files Modified

1. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/AttributeDeclaration.java`
   - Changed `defaultValue` from `String` to `List<Object>`
   - Updated `toString()` to iterate through list
   - Added javadoc explaining structure

2. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/DTDParser.java`
   - Added `defaultValueBuilder` and `defaultValueTextBuilder` fields
   - Updated all default value initialization to create List + StringBuilder
   - Updated `AFTER_DEFAULT_VALUE` state to handle entity references
   - Flush text segments when entity references encountered
   - Final flush on closing quote

## Files Created

1. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/test/AttlistDefaultValueTest.java`
   - Comprehensive tests for default value parsing
   - Verifies List<Object> structure
   - Tests entity references in defaults

2. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/doc/ATTLIST-DEFAULT-VALUES-STRUCTURE.md`
   - This documentation

## Next Steps

With the default value structure in place, the next step is to:

1. **Apply default values** in `XMLParser.handleElementAttrs()`
   - Query DTD for element's attribute declarations
   - For each attribute with default value
   - Check if attribute was specified
   - If not, expand entity references and add to SAXAttributes
   - Mark as `specified=false` for Attributes2 interface

2. **Handle #FIXED attributes**
   - If attribute has #FIXED mode
   - Verify specified value matches fixed value
   - Or apply fixed value if not specified

## Conclusion

The foundation for default attribute values is now complete. Entity references in default values are properly parsed and stored as `List<Object>`, matching the structure used by entity declarations. This provides a consistent architecture and allows the existing `EntityExpansionHelper` to handle expansion when defaults are applied.

The implementation was straightforward, requiring only:
- Changing `defaultValue` type in `AttributeDeclaration`
- Adding dual accumulation in `DTDParser` (text builder + list builder)
- Handling `GENERALENTITYREF` tokens during default value parsing
- Flushing text segments appropriately

All tests pass, and the architecture is now ready for applying default values to elements!

