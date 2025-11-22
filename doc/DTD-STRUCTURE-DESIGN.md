# DTD Structure Design

## Overview

Implemented efficient data structures for storing and accessing DTD declarations in the DTDParser. The design prioritizes:

1. **Memory Efficiency**: Lazy allocation, string interning, minimal overhead
2. **Access Performance**: O(1) lookup for declarations
3. **Simplicity**: Clean API, easy integration with XMLParser

## Class Structure

### AttributeDeclaration

**Purpose**: Represents an attribute declaration from `<!ATTLIST>`.

**Fields**:
- `name`: Attribute name
- `type`: Type (CDATA, ID, IDREF, etc.)
- `mode`: Default mode (#REQUIRED, #IMPLIED, #FIXED, or null)
- `defaultValue`: Default value if specified

**Memory Optimizations**:
- String interning for common type values (CDATA, ID, etc.)
- Immutable design allows sharing across elements
- Small memory footprint: 4 object references per instance

**Example**:
```xml
<!ATTLIST chapter id ID #REQUIRED>
<!ATTLIST chapter title CDATA #IMPLIED>
<!ATTLIST chapter status (draft|final) "draft">
```

### ElementDeclaration

**Purpose**: Represents an element declaration from `<!ELEMENT>`.

**Content Model Types**:
1. **EMPTY**: `<!ELEMENT br EMPTY>`
2. **ANY**: `<!ELEMENT div ANY>`
3. **MIXED**: `<!ELEMENT p (#PCDATA | em | strong)*>`
4. **ELEMENT**: `<!ELEMENT chapter (title, para+)>`

**Content Model Structure**:
```
ContentModel (tree structure):
├── NodeType: PCDATA, ELEMENT, SEQUENCE, CHOICE
├── elementName: For ELEMENT nodes
├── children[]: For SEQUENCE/CHOICE nodes
└── occurrence: ONCE, OPTIONAL (?), ZERO_OR_MORE (*), ONE_OR_MORE (+)
```

**Example Tree**:
```
<!ELEMENT book (title, author+, (chapter | appendix)+)>

book (SEQUENCE)
├── title (ELEMENT, ONCE)
├── author (ELEMENT, ONE_OR_MORE)
└── (CHOICE, ONE_OR_MORE)
    ├── chapter (ELEMENT, ONCE)
    └── appendix (ELEMENT, ONCE)
```

**Memory Efficiency**:
- Simple content (EMPTY/ANY): Only contentType stored, no model tree
- Complex content: Compact tree representation with arrays
- Shared occurrence enums

## DTDParser Storage

### Data Structures

```java
// Element declarations: O(1) lookup
Map<String, ElementDeclaration> elementDecls;
Key: element name
Value: ElementDeclaration

// Attribute declarations: O(1) lookup
Map<String, AttributeDeclaration> attributeDecls;
Key: "elementName:attributeName"  // Composite key
Value: AttributeDeclaration
```

### Why Composite Keys for Attributes?

**Problem**: Each element can have multiple attributes.

**Alternatives Considered**:

1. **Map<String, Map<String, AttributeDeclaration>>**
   - Pro: Natural nesting
   - Con: Two HashMap allocations per element
   - Con: Two hash lookups for access

2. **Map<String, List<AttributeDeclaration>>**
   - Pro: All attributes for element together
   - Con: O(n) search through list
   - Con: Requires iteration for single attribute lookup

3. **Composite Key: Map<String, AttributeDeclaration>** ✅ *CHOSEN*
   - Pro: Single hash lookup O(1)
   - Pro: One HashMap, minimal memory overhead
   - Pro: Simple implementation
   - Con: String concatenation for key (minimal cost)

**Performance**:
```
Lookup: O(1) with single hash computation
Memory: ~32 bytes per HashMap entry
```

### Lazy Allocation

Maps are only created when first declaration is added:
```java
private void addElementDeclaration(ElementDeclaration decl) {
    if (elementDecls == null) {
        elementDecls = new HashMap<>();
    }
    elementDecls.put(decl.name, decl);
}
```

**Benefits**:
- Zero memory overhead for documents without DOCTYPE
- Maps allocated only when needed
- XMLParser lazily constructs DTDParser itself

### Access API

```java
// Get element declaration
ElementDeclaration elemDecl = dtdParser.getElementDeclaration("chapter");

// Get attribute declaration
AttributeDeclaration attrDecl = 
    dtdParser.getAttributeDeclaration("chapter", "id");

// Check if attribute is required
if (attrDecl != null && attrDecl.isRequired()) {
    // ... validate presence
}

// Get default value
if (attrDecl != null && attrDecl.hasDefault()) {
    String defaultVal = attrDecl.defaultValue;
    // ... apply default
}
```

## Integration with SAXAttributes

The `SAXAttributes` class already has hooks for DTD integration:

```java
public void setDTDContext(String elementName, DTDParser dtdParser) {
    this.elementName = elementName;
    this.dtdParser = dtdParser;
}

// In SAXAttributes methods:
public String getType(String qName) {
    Attribute attr = stringNameMap.get(qName);
    if (attr == null) return null;
    
    // Check DTD for type information
    if (dtdParser != null) {
        AttributeDeclaration decl = 
            dtdParser.getAttributeDeclaration(elementName, qName);
        if (decl != null) {
            return decl.type;  // Use DTD type
        }
    }
    return attr.type;  // Use document type
}
```

## Future Enhancements

### 1. Content Model Validation

```java
public boolean validateContent(ElementDeclaration decl, 
                               List<String> childElements) {
    // Validate child elements against content model
    // Use state machine for efficient validation
}
```

### 2. Attribute Defaulting

```java
public void applyDefaults(SAXAttributes attrs, 
                         String elementName,
                         DTDParser dtdParser) {
    // For each declared attribute:
    // - If not present and has default: add it
    // - If REQUIRED and not present: error
    // - If FIXED and present with different value: error
}
```

### 3. Entity Declarations

```java
Map<String, EntityDeclaration> generalEntities;
Map<String, EntityDeclaration> parameterEntities;

// For entity expansion in attribute values and content
```

### 4. Notation Declarations

```java
Map<String, NotationDeclaration> notations;

// For unparsed entities and NOTATION attributes
```

## Memory Profile

**Empty Document** (no DOCTYPE):
- DTDParser: Not constructed
- Overhead: 0 bytes

**Document with DOCTYPE** (no internal subset):
- DTDParser: Constructed
- Maps: Not allocated
- Overhead: ~40 bytes (object header + fields)

**Document with 10 Elements, 20 Attributes**:
- elementDecls HashMap: ~600 bytes (10 entries)
- attributeDecls HashMap: ~1200 bytes (20 entries)
- ElementDeclaration objects: ~400 bytes (10 * ~40 bytes)
- AttributeDeclaration objects: ~1600 bytes (20 * ~80 bytes)
- **Total**: ~3800 bytes (~190 bytes per declaration)

**Comparison**: XML parsers that pre-allocate DTD structures use 10-50KB even for documents without DTDs. Our lazy approach saves significant memory.

## Performance Characteristics

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Add element declaration | O(1) | HashMap put |
| Add attribute declaration | O(1) | HashMap put with composite key |
| Lookup element | O(1) | HashMap get |
| Lookup attribute | O(1) | HashMap get, single string concat |
| Validate content model | O(n) | n = number of child elements |
| Apply attribute defaults | O(m) | m = number of declared attributes |

## Design Principles

1. **Pay for what you use**: No DTD structures unless DOCTYPE present
2. **Optimize common case**: Fast lookup for validation
3. **Simple API**: Easy integration with XMLParser
4. **Memory efficient**: Minimal overhead, string interning
5. **Extensible**: Easy to add entity/notation support

This design provides a solid foundation for DTD support while maintaining the performance and memory characteristics that make Gonzalez suitable for high-performance applications.

