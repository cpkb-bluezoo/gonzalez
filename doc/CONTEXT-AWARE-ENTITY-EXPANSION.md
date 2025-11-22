# Context-Aware Entity Expansion Architecture

**Date**: November 15, 2025  
**Status**: ✅ FRAMEWORK COMPLETE (ATTRIBUTE_VALUE context fully implemented)

## Overview

Implemented a context-aware entity expansion framework based on XML 1.0 specification section 4.4. The framework provides:

1. **Five entity expansion contexts** with different validation rules
2. **Two-level infinite loop detection** (entity names + external IDs)
3. **Extensible architecture** for implementing remaining contexts

## XML Specification Section 4.4 - Entity Contexts

The XML specification defines five contexts where entity references can occur, each with different rules:

| Context | Description | External Parsed | Unparsed | Notes |
|---------|-------------|----------------|----------|-------|
| **Reference in Content** | `&name;` in element content | ✅ Allowed (async) | ❌ Forbidden | Most complex |
| **Reference in Attribute Value** | `attr="&name;"` | ❌ Forbidden | ❌ Forbidden | ✅ IMPLEMENTED |
| **Occurs as Attribute Value** | Attribute type ENTITY | N/A | Reference only | Special case |
| **Reference in Entity Value** | `<!ENTITY x "&y;">` | ❌ Forbidden | ❌ Forbidden | Bypassed |
| **Reference in DTD** | Parameter entities `%name;` | ✅ Allowed (async) | N/A | Only `%` |

## Architecture Components

### 1. EntityExpansionContext Enum

Defines the five contexts from the XML specification:

```java
public enum EntityExpansionContext {
    CONTENT,                 // Reference in element content
    ATTRIBUTE_VALUE,         // Reference in attribute value  ✅ IMPLEMENTED
    ENTITY_ATTRIBUTE_VALUE,  // Attribute of type ENTITY
    ENTITY_VALUE,           // Reference in entity value
    DTD                     // Reference in DTD markup
}
```

### 2. EntityExpansionHelper Class

Unified mechanism for context-aware entity expansion with two-level loop detection:

**Features:**
- Context-specific validation rules
- Entity name recursion tracking (`visitedEntityNames`)
- External ID loop detection (`visitedExternalIDs`)
- Hierarchical helper instances (child inherits parent's visited sets)
- Proper error reporting with locator information

**Key Methods:**

#### `expandGeneralEntity(String entityName, EntityExpansionContext context)`
Main entry point for expanding an entity reference.

- Looks up entity in DTD
- Validates entity is allowed in the given context
- Detects circular references (entity names)
- Returns expanded string for internal entities
- Returns `null` for external entities requiring async resolution

#### `expandEntityValue(List<Object> replacementText, EntityExpansionContext context)`
Recursively expands entity replacement text.

- Processes `String` parts (literal text)
- Processes `GeneralEntityReference` parts (nested entities)
- Maintains visited entity set through recursion
- Context is passed through to nested expansions

#### `markExternalIDVisited(ExternalID externalID)`
Marks an external ID as visited when beginning async resolution.

- Detects if same external resource (publicId/systemId) already being resolved
- Prevents infinite loops in external entity chains

## Two-Level Infinite Loop Detection

### Level 1: Entity Name Recursion

Detects when entity A references entity B which references entity A:

```xml
<!ENTITY a "before &b; after">
<!ENTITY b "before &a; after">
<root attr="&a;"/>  <!-- Error: Circular entity reference detected: &a; -->
```

**Implementation:**
- `visitedEntityNames` set tracks entities in current expansion chain
- Each child helper inherits parent's visited set
- Adding entity name before recursing catches cycles

### Level 2: External ID Recursion

Detects when same external resource (identified by publicId or systemId) is referenced multiple times:

```xml
<!ENTITY ext1 SYSTEM "http://example.com/entity.xml">
<!-- entity.xml contains: <!ENTITY ext2 SYSTEM "http://example.com/entity.xml"> -->
<root>&ext1;</root>  <!-- Error: Circular external entity reference -->
```

**Implementation:**
- `visitedExternalIDs` set tracks external resources in resolution chain
- Uses `ExternalID.toString()` as key (contains both publicId and systemId)
- Call `markExternalIDVisited()` when beginning async resolution

## Context-Specific Validation

### ATTRIBUTE_VALUE Context (✅ Implemented)

**Rules:**
- Internal entities: ✅ Allowed
- External parsed entities: ❌ Forbidden
- Unparsed entities: ❌ Forbidden

**Example:**
```xml
<!ENTITY copy "Copyright 2025">
<!ENTITY ext SYSTEM "external.xml">
<root 
  valid="&copy;"     <!-- ✅ OK: internal entity -->
  invalid="&ext;"    <!-- ❌ Error: external entity forbidden -->
/>
```

**Implementation:**
- `XMLParser.expandGeneralEntityInAttributeValue()` creates helper with `ATTRIBUTE_VALUE` context
- Helper validates and expands internal entities
- Throws `SAXParseException` for external/unparsed entities

### CONTENT Context (🔲 Not Yet Implemented)

**Rules:**
- Internal entities: ✅ Allowed
- External parsed entities: ✅ Allowed (requires async resolution)
- Unparsed entities: ❌ Forbidden

**Example:**
```xml
<!ENTITY copy "Copyright 2025">
<!ENTITY chapter SYSTEM "chapter1.xml">
<root>
  &copy;     <!-- ✅ OK: internal entity -->
  &chapter;  <!-- ✅ OK: external entity (async resolution) -->
</root>
```

**Implementation Plan:**
1. In `handleElementContent()`, create helper with `CONTENT` context
2. Call `helper.expandGeneralEntity(entityName, CONTENT)`
3. If returns `null`, trigger async external entity resolution
4. When external content arrives, process through nested parser
5. Call `helper.markExternalIDVisited()` before resolution

### ENTITY_VALUE Context (🔲 Not Yet Implemented)

**Rules:**
- General entity refs: Bypassed (stored as `GeneralEntityReference`)
- Parameter entity refs: Immediately included
- External entities: ❌ Forbidden

**Example:**
```xml
<!ENTITY middle "MIDDLE">
<!ENTITY combined "before &middle; after">
<!-- &middle; is stored as GeneralEntityReference, not expanded during parsing -->
```

**Implementation:**
- Already handled in `DTDParser.handleEntityDecl()`
- Entity values stored as `List<Object>` with mixed `String` and `GeneralEntityReference`
- Expansion happens later when entity is referenced

### DTD Context (🔲 Not Yet Implemented)

**Rules:**
- Only parameter entity refs (`%name;`) processed
- General entity refs ignored
- External parameter entities allowed (async resolution)

**Example:**
```xml
<!DOCTYPE root [
  <!ENTITY % common SYSTEM "common.dtd">
  %common;  <!-- ✅ OK: parameter entity (async if external) -->
]>
```

**Implementation Plan:**
- Add parameter entity expansion in `DTDParser`
- Use helper with `DTD` context
- Handle async resolution for external parameter entities

## Usage Example

```java
// Create helper with DTD parser and locator
EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);

// Expand entity in attribute value context
String expanded = helper.expandGeneralEntity("myEntity", EntityExpansionContext.ATTRIBUTE_VALUE);

// For external entities in content (async resolution)
String result = helper.expandGeneralEntity("externalEntity", EntityExpansionContext.CONTENT);
if (result == null) {
    // External entity requires async resolution
    EntityDeclaration entity = dtdParser.getGeneralEntity("externalEntity");
    ExternalID extId = entity.externalID;
    
    // Mark external ID to detect loops
    helper.markExternalIDVisited(extId);
    
    // Begin async resolution...
    // (processExternalEntity mechanism)
}
```

## Benefits of Context-Aware Architecture

1. **Spec Compliance**: Directly maps to XML 1.0 section 4.4 table
2. **Maintainability**: Context rules centralized in one place
3. **Extensibility**: Easy to add remaining contexts
4. **Error Messages**: Context-specific error messages aid debugging
5. **Reusability**: Same helper used across parser components

## Files Created

1. `src/org/bluezoo/gonzalez/EntityExpansionContext.java`
   - Enum defining five contexts from XML spec
   - Comprehensive javadoc explaining rules for each

2. `src/org/bluezoo/gonzalez/EntityExpansionHelper.java`
   - Context-aware expansion logic
   - Two-level loop detection
   - Hierarchical helper instances for recursion

## Files Modified

1. `src/org/bluezoo/gonzalez/XMLParser.java`
   - Removed hardcoded expansion logic (~120 lines)
   - Added simple delegation to `EntityExpansionHelper` (3 lines)
   - Much cleaner and more maintainable

## Test Results

All existing tests still pass after refactoring:
```
✓ EntityRefInAttributeTest - 6/6 tests passed
✓ EntityAndNormalizationTest - 3/3 tests passed
✓ AttributeNormalizationTest - 4/4 tests passed
✓ EntityDeclTest - 5/5 tests passed
```

## Next Steps

### 1. Implement CONTENT Context
- Add entity expansion in `handleElementContent()`
- Implement async external entity resolution
- Test with external parsed entities

### 2. Implement DTD Context
- Add parameter entity expansion in `DTDParser`
- Handle external parameter entity resolution
- Test with external DTD subsets

### 3. Handle ENTITY_ATTRIBUTE_VALUE Context
- Validate ENTITY-type attributes reference declared unparsed entities
- Implement in attribute validation logic

### 4. Add ExternalID Loop Tests
- Create tests with circular external entity references
- Verify `markExternalIDVisited()` catches loops

## Comparison: Before vs After

### Before (Hardcoded)
```java
// 120 lines of context-specific logic in XMLParser
private String expandGeneralEntityInAttributeValue(String entityName) {
    // Check DTD exists
    // Look up entity
    // Check not external (hardcoded for attribute values)
    // Check not unparsed (hardcoded for attribute values)
    // Call expandEntityValue with visitedEntities
}

private String expandEntityValue(List<Object> replacementText, Set<String> visitedEntities) {
    // For each part...
    // Check circular reference
    // Check not external (hardcoded for attribute values)
    // Check not unparsed (hardcoded for attribute values)
    // Recurse with new visited set
}
```

**Problems:**
- Hardcoded "forbidden in attribute values" messages
- Not reusable for other contexts
- Duplicate validation logic in two methods
- No external ID tracking

### After (Context-Aware)
```java
// 3 lines delegating to helper
private String expandGeneralEntityInAttributeValue(String entityName) {
    EntityExpansionHelper helper = new EntityExpansionHelper(dtdParser, locator);
    return helper.expandGeneralEntity(entityName, EntityExpansionContext.ATTRIBUTE_VALUE);
}
```

**Benefits:**
- Context passed as parameter
- Validation rules centralized in helper
- Reusable for all five contexts
- External ID tracking included
- Easy to extend to CONTENT, DTD, etc.

## Conclusion

The context-aware entity expansion framework provides a solid foundation for implementing the complete XML specification. The `ATTRIBUTE_VALUE` context is fully implemented and tested, demonstrating the architecture works correctly. The remaining contexts can now be implemented by:

1. Creating helper with appropriate context
2. Handling `null` return for external entities (async resolution)
3. Adding context-specific logic as needed

This architecture aligns with your description of needing "a generic mechanism that provides the context of the resolution since behaviour can vary depending on this context," and implements both levels of infinite loop detection (entity names and external IDs).

