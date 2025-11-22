# External ID and Entity Declaration Infrastructure

## Date
November 15, 2025

## Overview

Implemented foundational classes for entity and notation declarations in the DTD parser, consolidating external identifier handling into a unified `ExternalID` class.

## New Classes Created

### 1. `ExternalID.java`

Represents an XML External ID, which can be either:
- `SYSTEM "systemId"`
- `PUBLIC "publicId" "systemId"`

**Usage:**
- DOCTYPE declarations (external DTD subset)
- NOTATION declarations
- External ENTITY declarations

**Key Features:**
- Mutable fields: `publicId` (nullable), `systemId` (nullable)
- Helper methods: `isSystem()`, `isPublic()`
- Clean `toString()` for debugging

**Design Rationale:**
- Single class handles both SYSTEM and PUBLIC forms
- publicId null indicates SYSTEM (simpler than enum)
- Mutable for incremental building during parsing
- Used throughout DTDParser for consistency

### 2. `EntityDeclaration.java`

Represents an entity declaration in a DTD.

**Entity Types:**
- **General entities**: Referenced as `&name;`
- **Parameter entities**: Referenced as `%name;` (DTD only)

**Entity Categories:**
- **Internal**: Contains replacement text directly
- **External parsed**: References external XML content
- **External unparsed**: References non-XML data with NDATA notation

**Fields:**
- `name`: Entity name
- `isParameter`: true for parameter entities (%)
- `replacementText`: For internal entities (null for external)
- `externalID`: For external entities (null for internal)
- `notationName`: For unparsed entities (null for parsed)

**Helper Methods:**
- `isInternal()`, `isExternal()`
- `isParsed()`, `isUnparsed()`
- `toString()` for debugging

**Examples:**
```xml
<!ENTITY copyright "Copyright © 2025">              <!-- internal general -->
<!ENTITY chapter1 SYSTEM "chap1.xml">               <!-- external parsed general -->
<!ENTITY logo SYSTEM "logo.gif" NDATA gif>          <!-- external unparsed -->
<!ENTITY % common SYSTEM "common.dtd">              <!-- external parsed parameter -->
```

## DTDParser Changes

### New Fields

```java
/** External ID for the external DTD subset (if present) */
private ExternalID doctypeExternalID;

/** Entity declarations: entity name → EntityDeclaration */
private Map<String, EntityDeclaration> entities;

/** Parameter entity declarations: entity name → EntityDeclaration */
private Map<String, EntityDeclaration> parameterEntities;

/** Notation declarations: notation name → ExternalID */
private Map<String, ExternalID> notations;
```

### Refactored Code

**Before:**
```java
private String publicId;
private String systemId;
```

**After:**
```java
private ExternalID doctypeExternalID;
```

**Benefits:**
- Single cohesive object instead of two separate fields
- Consistent handling throughout codebase
- Easier to pass to entity resolvers
- Better encapsulation

### Updated Methods

1. **`handleAfterSystemPublic()`**: Creates/populates `ExternalID` during parsing
2. **`handleAfterPublicId()`**: Continues populating `ExternalID`
3. **`handleAfterExternalId()`**: Uses `ExternalID` for `startDTD()` calls
4. **`processExternalDTDSubset()`**: Extracts publicId/systemId from `ExternalID`
5. **Getter methods**: 
   - Added `getDoctypeExternalID()` → returns `ExternalID` object
   - Updated `getPublicId()` → delegates to `doctypeExternalID.publicId`
   - Updated `getSystemId()` → delegates to `doctypeExternalID.systemId`

### All startDTD() Calls Updated

Changed from:
```java
lexicalHandler.startDTD(doctypeName, publicId, systemId);
```

To:
```java
String publicId = doctypeExternalID != null ? doctypeExternalID.publicId : null;
String systemId = doctypeExternalID != null ? doctypeExternalID.systemId : null;
lexicalHandler.startDTD(doctypeName, publicId, systemId);
```

## Testing

All existing tests pass:
- ✅ `QuickATTLISTTest`
- ✅ `SimpleCommentTest`
- ✅ `XMLParserCommentPITest`
- ✅ `DTDPITest`
- ✅ `AttributeNormalizationTest`
- ✅ `AttributeNormalizationEdgeCasesTest`

No behavioral changes - this is purely a refactoring for better structure.

## Design Benefits

### 1. **Unified External ID Handling**
- Single class used for DOCTYPE, NOTATION, and ENTITY declarations
- Consistent API for entity resolution
- Easier to extend (e.g., adding base URI tracking)

### 2. **Entity Resolver Integration**
Both `publicId` and `systemId` are now easily accessible as a unit, which is exactly what entity resolvers need. The `EntityResolver2.resolveEntity()` signature is:
```java
InputSource resolveEntity(String name, String publicId, String baseURI, String systemId)
```

Having `ExternalID` makes this trivial:
```java
ExternalID extID = ... ;
resolver.resolveEntity(name, extID.publicId, baseURI, extID.systemId);
```

### 3. **Memory Efficiency**
Maps are created lazily only when declarations are encountered:
- `entities` - created on first `<!ENTITY>` (not `<!ENTITY %...>`)
- `parameterEntities` - created on first `<!ENTITY %...>`
- `notations` - created on first `<!NOTATION>`

Documents without DTDs: zero overhead.
Documents with DTD but no entities: no entity map allocated.

### 4. **Type Safety**
`EntityDeclaration` clearly distinguishes between:
- General vs. parameter entities (`isParameter` flag)
- Internal vs. external entities (which field is non-null)
- Parsed vs. unparsed entities (`notationName` presence)

No risk of mixing up entity types or accessing wrong fields.

## Next Steps

The infrastructure is now in place for:

1. **NOTATION declaration parsing** (next immediate step)
   - Add `START_NOTATIONDECL` token handling
   - Parse NOTATION declarations
   - Store in `notations` map

2. **ENTITY declaration parsing**
   - Add `START_ENTITYDECL` token handling
   - Parse internal/external/parameter entities
   - Store in appropriate map (`entities` or `parameterEntities`)
   - Report to `DTDHandler.unparsedEntityDecl()` for unparsed entities

3. **Entity reference resolution** (in attribute values and content)
   - Query `getEntityDeclaration(name)` from `DTDParser`
   - For internal entities: expand replacement text
   - For external entities: use `processExternalEntity()` mechanism
   - For unparsed entities: error (not allowed in content)

4. **Parameter entity resolution** (in DTD)
   - Query `parameterEntities` map
   - Process replacement text or external entity
   - Integrate into DTD parsing flow

## Files Modified

- `src/org/bluezoo/gonzalez/ExternalID.java` - NEW
- `src/org/bluezoo/gonzalez/EntityDeclaration.java` - NEW
- `src/org/bluezoo/gonzalez/DTDParser.java` - REFACTORED

## Specification Compliance

This implementation follows:
- **XML 1.0 Specification, Section 4.2**: Entity Declarations
- **XML 1.0 Specification, Section 4.7**: Notation Declarations
- **XML 1.0 Specification, Section 4.2.2**: External Entities

## Backward Compatibility

The `DTDParser` public API maintains backward compatibility:
- `getPublicId()` still returns `String`
- `getSystemId()` still returns `String`
- Added new method `getDoctypeExternalID()` for direct access

Existing code using the old getters will continue to work unchanged.

