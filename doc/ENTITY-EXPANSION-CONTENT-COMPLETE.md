# Entity Expansion in Element Content - Implementation Complete

**Date**: November 15, 2025  
**Status**: ✅ COMPLETE (internal entities) / 🔲 TODO (external entities - async)

## Summary

Successfully implemented entity expansion for `GENERALENTITYREF` tokens in element content using the context-aware `EntityExpansionHelper` framework. This required only **19 lines of code** thanks to the generic expansion mechanism!

## Implementation

### XMLParser.java Changes

#### 1. Updated handleElementContent() 
**Before (line 921-926):**
```java
case GENERALENTITYREF:
    // General entity reference: &name;
    String entityName = extractString(data);
    // TODO: Look up entity in DTD and resolve (may be external)
    // For now, this is an error
    throw new SAXParseException("...", locator);
```

**After:**
```java
case GENERALENTITYREF:
    // General entity reference: &name;
    String entityName = extractString(data);
    expandGeneralEntityInContent(entityName);
    break;
```

#### 2. Added expandGeneralEntityInContent() Method

```java
private void expandGeneralEntityInContent(String entityName) throws SAXException {
    EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
    String expandedValue = helper.expandGeneralEntity(entityName, EntityExpansionContext.CONTENT);
    
    if (expandedValue == null) {
        // External entity - would require async resolution
        throw new SAXParseException(
            "External entity reference '&" + entityName + ";' in content requires async resolution (not yet implemented)",
            locator);
    }
    
    // Internal entity - send expanded text to content handler
    if (contentHandler != null && !expandedValue.isEmpty()) {
        contentHandler.characters(expandedValue.toCharArray(), 0, expandedValue.length());
    }
}
```

**Total new code: 19 lines** (vs ~120 lines if hardcoded like the original attribute value implementation!)

## How It Works

1. **Token received**: `GENERALENTITYREF` token arrives in `handleElementContent()`
2. **Create helper**: New `EntityExpansionHelper` with current DTD and locator
3. **Expand with context**: Call `expandGeneralEntity(entityName, CONTENT)`
4. **Context validation**: Helper validates entity is allowed in CONTENT context:
   - Internal entities: ✅ Allowed
   - External parsed entities: ✅ Allowed (returns `null`)
   - Unparsed entities: ❌ Forbidden
5. **Handle result**:
   - Non-null string: Send to `contentHandler.characters()`
   - Null: External entity (report not yet implemented)

## Context Differences: ATTRIBUTE_VALUE vs CONTENT

| Aspect | ATTRIBUTE_VALUE | CONTENT |
|--------|----------------|---------|
| Internal entities | ✅ Allowed | ✅ Allowed |
| External parsed entities | ❌ Forbidden | ✅ Allowed (async) |
| Unparsed entities | ❌ Forbidden | ❌ Forbidden |
| Output | Appended to attribute string | Sent to contentHandler |
| Implementation | Returns String | Calls contentHandler |

The context-aware framework handles all validation - we just specify `CONTENT` vs `ATTRIBUTE_VALUE`!

## Test Coverage

Created `EntityRefInContentTest.java` with 7 comprehensive tests:

### ✅ Test 1: Simple Entity Reference
```xml
<!ENTITY copy "Copyright 2025">
<root>&copy;</root>
```
**Result**: Content = `"Copyright 2025"`

### ✅ Test 2: Nested Entity References
```xml
<!ENTITY inner "INNER">
<!ENTITY outer "before &inner; after">
<root>&outer;</root>
```
**Result**: Content = `"before INNER after"`

### ✅ Test 3: Multiple Entities
```xml
<!ENTITY first "FIRST">
<!ENTITY second "SECOND">
<!ENTITY third "THIRD">
<root>&first; &second; &third;</root>
```
**Result**: Content = `"FIRST SECOND THIRD"`

### ✅ Test 4: Circular Reference Detection
```xml
<!ENTITY a "before &b; after">
<!ENTITY b "before &a; after">
<root>&a;</root>
```
**Result**: `SAXParseException` - "Circular entity reference detected: &a;"

### ✅ Test 5: Undefined Entity
```xml
<!DOCTYPE root []>
<root>&undefined;</root>
```
**Result**: `SAXParseException` - "General entity reference '&undefined;' used but entity not declared"

### ✅ Test 6: Unparsed Entity Forbidden
```xml
<!NOTATION gif SYSTEM "image/gif">
<!ENTITY logo SYSTEM "logo.gif" NDATA gif>
<root>&logo;</root>
```
**Result**: `SAXParseException` - "Unparsed entity reference '&logo;' is forbidden in content"

### ✅ Test 7: External Entity (Not Yet Implemented)
```xml
<!ENTITY external SYSTEM "external.xml">
<root>&external;</root>
```
**Result**: `SAXParseException` - "External entity reference '&external;' in content requires async resolution (not yet implemented)"

## Error Messages

Context-aware error messages clearly indicate the issue:

**Content context:**
- `"Unparsed entity reference '&logo;' is forbidden in content"`
- `"External entity reference '&external;' in content requires async resolution (not yet implemented)"`

**Attribute context:**
- `"External entity reference '&external;' is forbidden in attribute values"`
- `"Unparsed entity reference '&logo;' is forbidden in attribute values"`

## Benefits of Context-Aware Framework

### Code Reuse
- **Attribute expansion**: 3 lines delegating to helper
- **Content expansion**: 19 lines (including null check and contentHandler call)
- **Total implementation**: 22 lines for both contexts!

### Maintainability
- Entity lookup logic: ✅ Centralized in helper
- Circular reference detection: ✅ Centralized in helper
- Context validation: ✅ Centralized in helper
- Error messages: ✅ Context-specific in helper

### Extensibility
To add a new context (e.g., `DTD` for parameter entities):
1. Create helper with context: `new EntityExpansionHelper(dtdParser, locator)`
2. Call: `helper.expandGeneralEntity(entityName, EntityExpansionContext.DTD)`
3. Handle result appropriately for that context

That's it! No need to duplicate validation logic.

## Remaining Work

### External Entity Resolution (Async)
When `expandedValue == null`:
1. Get entity declaration: `dtdParser.getGeneralEntity(entityName)`
2. Get external ID: `entity.externalID`
3. Mark visited: `helper.markExternalIDVisited(entity.externalID)`
4. Trigger async resolution: `processExternalEntity(...)`
5. When content arrives, parse through nested XMLTokenizer
6. Nested parser sends tokens back to XMLParser → contentHandler

**Architecture already exists** in Parser.java for external DTD resolution - same pattern applies.

### Parameter Entity Expansion
In `DTDParser`, handle `PARAMETERENTITYREF` tokens:
1. Create helper: `new EntityExpansionHelper(this, locator)`
2. Call: `helper.expandParameterEntity(entityName, EntityExpansionContext.DTD)`
3. For internal: Inject expanded text back into token stream
4. For external: Trigger async resolution

## All Tests Pass

Existing tests continue to work:
```
✓ EntityRefInContentTest - 7/7 tests passed (NEW)
✓ EntityRefInAttributeTest - 6/6 tests passed
✓ EntityAndNormalizationTest - 3/3 tests passed
✓ AttributeNormalizationTest - 4/4 tests passed
✓ EntityDeclTest - 5/5 tests passed
✓ EntityRefTokenTest - 4/4 tests passed
```

**Total: 29/29 tests passing!**

## Files Modified

1. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/XMLParser.java`
   - Updated `handleElementContent()` (1 line change)
   - Added `expandGeneralEntityInContent()` (19 lines)

## Files Created

1. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/test/EntityRefInContentTest.java`
   - Comprehensive test suite with 7 tests

2. `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/doc/ENTITY-EXPANSION-CONTENT-COMPLETE.md`
   - This documentation

## Comparison with Original Approach

If we had implemented this **before** creating the context-aware framework, it would have required:
- ~120 lines of duplicated expansion logic
- Hardcoded "forbidden in content" checks
- Separate circular reference tracking
- Duplicate error handling

**With the framework**: 19 lines of simple delegation!

## Conclusion

Entity expansion in element content is now fully functional for internal entities. The implementation demonstrates the power of the context-aware `EntityExpansionHelper` framework:

- **Minimal code**: 19 lines vs ~120 lines if hardcoded
- **Correct validation**: Context-specific rules automatically applied
- **Clear errors**: Context-aware error messages
- **Easy extension**: External entity support is just handling the `null` case

The framework makes it trivial to add entity expansion to new contexts. When we implement external entity resolution, it will work automatically for both `ATTRIBUTE_VALUE` and `CONTENT` contexts with no changes to the expansion logic!

