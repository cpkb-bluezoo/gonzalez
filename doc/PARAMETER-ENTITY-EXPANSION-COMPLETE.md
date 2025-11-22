# Parameter Entity Expansion - Implementation Complete

**Date**: 2025-11-15  
**Status**: ✅ Complete

## Overview

Successfully implemented parameter entity expansion for entity values and attribute default values in the DTD. Parameter entities (referenced as `%name;`) are now properly tokenized, stored, and expanded after the DOCTYPE declaration is complete.

## Key Insight: Forward References

Parameter entity expansion MUST happen as a post-processing phase after the DOCTYPE is complete, because **forward references are allowed**. An entity can reference a parameter entity that hasn't been declared yet:

```xml
<!ENTITY myval "%replaceme;">     <!-- %replaceme; not yet declared! -->
<!ENTITY % replaceme "some text">
```

## Implementation Components

### 1. ParameterEntityReference Class

Created `src/org/bluezoo/gonzalez/ParameterEntityReference.java` to represent unresolved parameter entity references in entity values:

```java
public class ParameterEntityReference {
    public final String name;
    
    public ParameterEntityReference(String name) {
        this.name = name;
    }
    
    @Override
    public String toString() {
        return "%" + name + ";";
    }
}
```

### 2. Tokenizer Fix: DOCTYPE_QUOTED Context

**Problem**: The tokenizer was using `ATTR_VALUE` context for quoted strings in DOCTYPE, which doesn't stop at `%`.

**Solution**: Added new `DOCTYPE_QUOTED` context that stops at `<`, `&`, `%`, and the matching quote.

**Files Modified**:
- `src/org/bluezoo/gonzalez/XMLTokenizer.java`
  - Added `DOCTYPE_QUOTED` to `TokenizerContext` enum
  - Updated context switching to use `DOCTYPE_QUOTED` for DOCTYPE quotes
  - Added `DOCTYPE_QUOTED` case in `tryEmitCDATA()` to stop at `%`
  - Extended `%` handling to recognize `PARAMETERENTITYREF` in `DOCTYPE_QUOTED` context
  - Added `DOCTYPE_QUOTED` to CDATA-emitting contexts for `[` and `]`

**Result**: Tokenizer now correctly emits:
```
<!ENTITY % combined "type is %common;">
```
As tokens:
- `QUOT`
- `CDATA("type is ")`
- `PARAMETERENTITYREF("common")`
- `QUOT`

### 3. DTDParser Storage

The `DTDParser` correctly stores parameter entity references as `ParameterEntityReference` objects in `replacementText`:

```java
replacementText: [
    String("type is "),
    ParameterEntityReference("common")
]
```

No changes were needed to `EntityDeclaration.replacementText` - it's already `List<Object>`, so it can hold `ParameterEntityReference` objects.

### 4. EntityExpansionHelper Extensions

Extended `src/org/bluezoo/gonzalez/EntityExpansionHelper.java` to support parameter entity expansion:

#### New Method: `expandParameterEntity()`

```java
public String expandParameterEntity(String entityName, EntityExpansionContext context)
        throws SAXException
```

- Only allowed in `DTD` and `ENTITY_VALUE` contexts
- Looks up parameter entity in `DTDParser`
- Detects circular references using `%entityName` as key
- Recursively expands the entity's replacement text
- Returns `null` for external parameter entities requiring async resolution

#### Updated Method: `expandEntityValue()`

Now handles both `GeneralEntityReference` and `ParameterEntityReference` objects:

```java
if (part instanceof ParameterEntityReference) {
    ParameterEntityReference ref = (ParameterEntityReference) part;
    String expanded = helper.expandParameterEntity(ref.name, EntityExpansionContext.ENTITY_VALUE);
    result.add(expanded);
}
```

### 5. Post-Processing Phase

Added post-processing method in `DTDParser.java`:

#### `expandParameterEntitiesInEntityValues()`

Called at the end of DOCTYPE declaration (three locations):
1. After name, no external ID, no internal subset (line 461)
2. After external ID, no internal subset (line 599)
3. After internal subset (line 726)

**What it does**:
1. Iterates through all general entity declarations
2. Iterates through all parameter entity declarations
3. Iterates through all attribute default values
4. For each `replacementText` or `defaultValue`, calls `expandParameterReferencesInList()`

#### `expandParameterReferencesInList()`

- Checks if list contains any `ParameterEntityReference` objects
- If yes, creates `EntityExpansionHelper` and expands each reference
- Returns new list with expanded strings
- Preserves `GeneralEntityReference` objects for deferred expansion

## Test Coverage

Created comprehensive test suite in `test/ParameterEntityExpansionTest.java`:

### Test 1: Simple Parameter Entity
```xml
<!ENTITY % common "CDATA">
<!ENTITY % combined "type is %common;">
```
**Result**: `"type is CDATA"` ✅

### Test 2: Nested Parameter Entities
```xml
<!ENTITY % inner "INNER">
<!ENTITY % middle "[%inner;]">
<!ENTITY % outer "before %middle; after">
```
**Result**: `"before [INNER] after"` ✅

### Test 3: Circular Reference Detection
```xml
<!ENTITY % a "%b;">
<!ENTITY % b "%a;">
```
**Result**: `SAXParseException: "Circular parameter entity reference detected: %b;"` ✅

### Test 4: Undefined Parameter Entity
```xml
<!ENTITY % test "%undefined;">
```
**Result**: `SAXParseException: "Undefined parameter entity: %undefined;"` ✅

### Test 5: Mixed General and Parameter Entities
```xml
<!ENTITY % param "PARAM">
<!ENTITY general "GENERAL">
<!ENTITY mixed "[%param;] and [&general;]">
```
**Result**: `"[PARAM] and [GENERAL]"` ✅

All tests pass!

## Architecture Benefits

1. **Correct Semantics**: Handles forward references as per XML spec
2. **Clean Separation**: Tokenization → Storage → Post-Processing
3. **Reusable Infrastructure**: Uses existing `EntityExpansionHelper` and `EntityExpansionContext`
4. **Error Detection**: Circular references and undefined entities detected during expansion
5. **Type Safety**: `ParameterEntityReference` class provides type-safe representation

## XML Specification Compliance

### Section 4.4: Entity Expansion

✅ Parameter entities only allowed in DTD context  
✅ Parameter entities can have forward references  
✅ Circular reference detection  
✅ External parameter entities identified (return null for async)  
✅ Mixed general and parameter entity expansion

### Section 4.4.5: Included as PE

✅ Parameter entity references in entity values are expanded  
✅ Parameter entity references in attribute default values are expanded  
✅ General entity references remain unexpanded (deferred)

## Related Files

- `src/org/bluezoo/gonzalez/ParameterEntityReference.java` - Reference class
- `src/org/bluezoo/gonzalez/XMLTokenizer.java` - DOCTYPE_QUOTED context
- `src/org/bluezoo/gonzalez/DTDParser.java` - Post-processing logic
- `src/org/bluezoo/gonzalez/EntityExpansionHelper.java` - Expansion logic
- `test/ParameterEntityExpansionTest.java` - Comprehensive tests
- `doc/TOKENIZER-DOCTYPE-QUOTED-CONTEXT.md` - Tokenizer fix documentation

## Next Steps

With parameter entity expansion complete, the remaining core features include:
- Parameter entity expansion in DTD markup (direct references like `%common;` in declarations)
- External entity resolution with blocking I/O
- Namespace processing
- Full XML 1.0 conformance testing

