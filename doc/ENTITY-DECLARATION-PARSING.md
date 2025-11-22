# Entity Declaration Parsing Implementation

**Date**: November 15, 2025  
**Status**: Complete

## Overview

Implemented comprehensive `<!ENTITY>` declaration parsing in `DTDParser` with support for:
- Internal general entities
- External parsed entities
- External unparsed entities (NDATA)
- Parameter entities
- Entity values with deferred entity reference expansion

## Key Components

### 1. GeneralEntityReference Class
Created `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/GeneralEntityReference.java`

Represents an entity reference that must be expanded later. Used in entity values where entity references can refer to entities not yet declared in the DTD.

```java
public class GeneralEntityReference {
    public final String name;  // Entity name (without & and ;)
}
```

### 2. EntityDeclaration Updates
Modified `EntityDeclaration.replacementText` from `String` to `List<Object>`:
- Each element is either a `String` (literal text) or `GeneralEntityReference` (deferred expansion)
- Allows entity values like `"before &middle; after"` to be stored as `["before ", GeneralEntityReference("middle"), " after"]`
- Entity references in entity values can now refer to entities declared later in the DTD

### 3. DTDParser Entity Handling

#### State Machine
Added `EntityDeclState` enum with states:
- `EXPECT_PERCENT_OR_NAME` - After `<!ENTITY`, optional `%` or entity name
- `EXPECT_NAME` - After `%`, expecting parameter entity name
- `AFTER_NAME` - After name, expecting whitespace
- `EXPECT_VALUE_OR_ID` - Expecting quoted value, `SYSTEM`, or `PUBLIC`
- `IN_ENTITY_VALUE` - Accumulating entity value between quotes
- `AFTER_ENTITY_VALUE` - After closing quote
- `EXPECT_SYSTEM_ID` - After `SYSTEM`
- `EXPECT_PUBLIC_ID` - After `PUBLIC`
- `AFTER_PUBLIC_ID` - After public ID
- `AFTER_EXTERNAL_ID` - After external ID, expecting `NDATA` or `>`
- `EXPECT_NDATA_NAME` - After `NDATA`
- `EXPECT_GT` - Expecting `>` to close

#### Entity Value Parsing
In `IN_ENTITY_VALUE` state:
- Accumulates `CDATA` and `S` tokens as literal text
- Expands `ENTITYREF` tokens (predefined entities, character references) immediately
- Stores `GENERALENTITYREF` tokens as `GeneralEntityReference` objects for deferred expansion
- Handles parameter entity references (currently throws TODO exception)
- Tracks quote character to detect closing quote

#### Storage
- General entities stored in `entities` map (entity name → `EntityDeclaration`)
- Parameter entities stored in `parameterEntities` map
- Keys are interned strings for efficient lookup
- Unparsed entities reported to `DTDHandler.unparsedEntityDecl()`

### 4. Public API
Added getter methods to `DTDParser`:
```java
public EntityDeclaration getGeneralEntity(String entityName)
public EntityDeclaration getParameterEntity(String entityName)
```

### 5. XMLTokenizer Context Fixes

#### DOCTYPE Quoted String Handling
- Extended quote handling to include `DOCTYPE_INTERNAL` context
- When quote encountered in DOCTYPE/DOCTYPE_INTERNAL, switches to `ATTR_VALUE` context
- Content between quotes emitted as `CDATA` tokens
- Closing quote restores previous DOCTYPE context

#### Percent Sign Context
- `%` character only has special meaning inside DOCTYPE contexts
- Outside DOCTYPE: `%` treated as regular character, emitted as `CDATA`
- Inside DOCTYPE: `%` triggers parameter entity reference parsing via `tryEmitParameterEntityRef()`

## Testing

Created `/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/test/EntityDeclTest.java` with comprehensive tests:

1. **Internal General Entity**: `<!ENTITY copy "Copyright 2025">`
   - Verifies simple internal entity with literal text
   
2. **Entity with References**: `<!ENTITY combined "before &middle; after">`
   - Verifies `List<Object>` structure with mixed `String` and `GeneralEntityReference`
   - Confirms deferred expansion architecture

3. **External Parsed Entity**: `<!ENTITY chapter SYSTEM "chapter1.xml">`
   - Verifies external ID storage
   - Confirms `isParsed()` returns true

4. **External Unparsed Entity**: `<!ENTITY logo SYSTEM "logo.gif" NDATA gif>`
   - Verifies NDATA notation handling
   - Confirms `isUnparsed()` returns true
   - Notation must be declared before use

5. **Parameter Entity**: `<!ENTITY % common "value">`
   - Verifies parameter entity flag
   - Stored in separate `parameterEntities` map

All tests pass successfully!

## Implementation Notes

### Quote Token Handling
External ID parsing requires handling `QUOT`/`APOS` tokens in multiple states:
- `EXPECT_SYSTEM_ID` - Skip opening quote before system ID
- `EXPECT_PUBLIC_ID` - Skip opening quote before public ID  
- `AFTER_PUBLIC_ID` - Skip opening/closing quotes
- `AFTER_EXTERNAL_ID` - Skip closing quote after system ID

This is necessary because the tokenizer emits separate quote tokens when entering/exiting quoted strings in DOCTYPE context.

### Workarounds
- Accept both `CDATA` and `NAME` tokens for quoted string values (tokenizer sometimes splits strings)
- Accumulate multiple tokens for system/public IDs to handle tokenizer splitting on special characters

### Future Work
- Parameter entity reference expansion in entity values (currently throws TODO exception)
- General entity reference resolution in content (XMLParser integration)
- External entity resolution and parsing

## Benefits

1. **Deferred Expansion**: Entity values can reference entities not yet declared
2. **Complete Coverage**: All entity types supported (internal, external, parsed, unparsed, general, parameter)
3. **SAX Integration**: Unparsed entities reported to `DTDHandler`
4. **Efficient Storage**: Interned keys, lazy map initialization
5. **Robust Parsing**: Full state machine with well-formedness checks

## Related Changes

- `Token.java`: Already had `GENERALENTITYREF` and `PARAMETERENTITYREF` tokens
- `XMLTokenizer.java`: Fixed context handling for `%` and DOCTYPE quoted strings
- `EntityDeclaration.java`: Changed `replacementText` to `List<Object>`
- `GeneralEntityReference.java`: New class for deferred entity expansion

