# Gonzalez Parser - Implementation Status

**Date**: November 15, 2025  
**Architecture Decision**: Blocking I/O for external entities (no async)

## ✅ CORE FEATURES IMPLEMENTED

### 1. Tokenization (XMLTokenizer)
**Status**: ✅ Complete

- ✅ BOM detection (UTF-8, UTF-16 BE/LE)
- ✅ XML declaration parsing (encoding, version, standalone)
- ✅ Character encoding detection and decoding
- ✅ Newline normalization (CRLF → LF, CR → LF)
- ✅ Underflow handling (byte and character level)
- ✅ Locator2 implementation (line/column tracking)
- ✅ Context-aware tokenization (CONTENT, ATTR_VALUE, DOCTYPE, etc.)
- ✅ All token types emitted correctly
- ✅ Entity reference tokenization (ENTITYREF, GENERALENTITYREF, PARAMETERENTITYREF)

### 2. Basic XML Parsing (XMLParser)
**Status**: ✅ Complete

- ✅ Elements (start tags, end tags, empty elements)
- ✅ Attributes
- ✅ Character data (CDATA)
- ✅ Comments
- ✅ Processing instructions (PIs)
- ✅ CDATA sections
- ✅ Predefined entities (&amp;, &lt;, &gt;, &apos;, &quot;)
- ✅ Character references (&#65;, &#x41;)
- ✅ Well-formedness checking

### 3. DTD Parsing (DTDParser)
**Status**: ✅ Mostly Complete

- ✅ DOCTYPE declaration
- ✅ ELEMENT declarations
- ✅ ATTLIST declarations (including multiple ATTLISTs per element)
- ✅ ENTITY declarations (general and parameter)
- ✅ NOTATION declarations
- ✅ Internal subset parsing
- ✅ External subset parsing (reads external DTD)
- ✅ Entity value parsing (stores GeneralEntityReference for deferred expansion)
- ✅ DTDHandler callbacks
- ✅ DeclHandler callbacks
- ⚠️ ATTLIST enumeration values (basic parsing, not fully validated)
- 🔲 Conditional sections (<![ INCLUDE/IGNORE [...]]>)

### 4. Entity Expansion
**Status**: ✅ Internal Entities Complete, 🔲 External Entities TODO

#### General Entities
- ✅ Internal entities in attribute values
- ✅ Internal entities in content
- ✅ Nested entity references
- ✅ Circular reference detection
- ✅ Context-aware validation (ATTRIBUTE_VALUE vs CONTENT)
- 🔲 External parsed entities (blocking I/O needed)
- ✅ Unparsed entities forbidden in content/attributes

#### Parameter Entities
- ✅ Declaration parsing
- ✅ Storage in DTDParser
- 🔲 Expansion in DTD internal subset
- 🔲 Expansion in entity values
- 🔲 External parameter entities

### 5. Attribute Processing
**Status**: ✅ Complete

- ✅ Attribute value normalization (whitespace)
- ✅ Type-based normalization (CDATA vs non-CDATA)
- ✅ Entity reference expansion in attribute values
- ✅ DTD attribute type lookup
- ✅ Attributes2 interface (specified vs default)
- 🔲 Default attribute values from DTD

### 6. SAX2 Interface
**Status**: ✅ Complete

- ✅ ContentHandler callbacks
- ✅ DTDHandler callbacks
- ✅ LexicalHandler callbacks
- ✅ DeclHandler callbacks
- ✅ ErrorHandler callbacks
- ✅ Locator2 interface
- ✅ Attributes2 interface
- ✅ Feature flags (14 features)
- ✅ EntityResolver2 support
- ✅ Proper relative URI resolution

---

## 🔲 CORE FEATURES NOT YET IMPLEMENTED

### 1. External Entity Resolution (BLOCKING I/O)
**Priority**: HIGH  
**Complexity**: Medium

**What's needed:**
- Resolve external parsed general entities (in content)
- Resolve external parsed parameter entities (in DTD)
- Use blocking I/O to read InputSource
- Nested tokenizer for entity content
- ExternalID loop detection (already in EntityExpansionHelper)
- Entity name recursion detection (already in EntityExpansionHelper)

**Implementation approach:**
```java
// In XMLParser.expandGeneralEntityInContent():
if (expandedValue == null) {
    // External entity
    EntityDeclaration entity = dtdParser.getGeneralEntity(entityName);
    helper.markExternalIDVisited(entity.externalID);
    
    InputSource source = resolveExternalEntity(entity.externalID);
    parseExternalEntity(source, entityName); // Blocking read + parse
}
```

**Files to modify:**
- `XMLParser.java` - Add `parseExternalEntity()` method
- `EntityExpansionHelper.java` - Already has `markExternalIDVisited()`
- `EntityResolutionHelper.java` - Already exists, use it

**Estimated effort**: 2-3 hours

---

### 2. Parameter Entity Expansion
**Priority**: MEDIUM  
**Complexity**: Medium

**What's needed:**
- Expand %entities; in DTD internal subset
- Expand %entities; in entity values
- External parameter entity resolution (blocking I/O)
- Inject expanded text back into token stream

**Current status:**
- ✅ PARAMETERENTITYREF tokens emitted by tokenizer
- ✅ Parameter entity declarations parsed and stored
- 🔲 Expansion logic in DTDParser
- 🔲 Token injection mechanism

**Implementation approach:**
```java
// In DTDParser, when receiving PARAMETERENTITYREF token:
String entityName = extractString(data);
EntityExpansionHelper helper = new EntityExpansionHelper(this, locator);
String expandedValue = helper.expandParameterEntity(entityName, EntityExpansionContext.DTD);

// Inject expanded text back into token stream
// (requires mechanism to re-tokenize expanded text)
```

**Complexity**: Need a way to re-tokenize expanded parameter entity text

**Files to modify:**
- `DTDParser.java` - Add parameter entity expansion
- `EntityExpansionHelper.java` - Add `expandParameterEntity()` method
- May need token buffer/injection mechanism

**Estimated effort**: 3-4 hours

---

### 3. Default Attribute Values
**Priority**: MEDIUM  
**Complexity**: Low-Medium

**What's needed:**
- Store default values from ATTLIST declarations
- Apply defaults when element parsed without the attribute
- Expand entity references in default values
- Mark as "not specified" in Attributes2

**Current status:**
- ✅ ATTLIST declarations parsed
- ✅ Default values stored in AttributeDeclaration
- 🔲 Application of defaults in XMLParser
- 🔲 Entity expansion in defaults

**Implementation approach:**
```java
// In XMLParser.handleElementAttrs(), before calling startElement:
if (dtdParser != null) {
    List<AttributeDeclaration> defaults = dtdParser.getDefaultAttributes(elementName);
    for (AttributeDeclaration attr : defaults) {
        if (!attributes.hasAttribute(attr.name)) {
            // Apply default
            String value = expandDefaultValue(attr.defaultValue);
            attributes.addAttribute("", attr.name, attr.name, attr.type, value, false); // specified=false
        }
    }
}
```

**Files to modify:**
- `DTDParser.java` - Add `getDefaultAttributes(String elementName)`
- `XMLParser.java` - Apply defaults before startElement
- `SAXAttributes.java` - Track specified flag

**Estimated effort**: 2-3 hours

---

### 4. Namespace Processing
**Priority**: MEDIUM  
**Complexity**: Medium-High

**What's needed:**
- Namespace prefix mapping (xmlns:prefix="uri")
- Split qNames into (uri, localName, qName)
- Filter/include xmlns attributes based on feature flags
- startPrefixMapping/endPrefixMapping callbacks
- Default namespace handling (xmlns="uri")

**Current status:**
- ✅ Feature flags exist (namespaces, namespace-prefixes)
- 🔲 Namespace logic not implemented

**Implementation approach:**
- Maintain namespace context stack (maps prefix → URI)
- Process xmlns attributes before other attributes
- Split element/attribute names based on namespace context
- Call startPrefixMapping before startElement
- Call endPrefixMapping after endElement

**Files to modify:**
- `XMLParser.java` - Add namespace context and processing
- New `NamespaceContext.java` class

**Estimated effort**: 4-5 hours

---

### 5. ATTLIST Enumeration Validation
**Priority**: LOW  
**Complexity**: Low

**What's needed:**
- Parse enumeration values properly: `(val1|val2|val3)`
- Store enumeration choices in AttributeDeclaration
- Validate attribute values match enumeration (if validation enabled)

**Current status:**
- ⚠️ Basic parsing, but not stored/validated

**Files to modify:**
- `DTDParser.java` - Properly parse and store enumerations
- `AttributeDeclaration.java` - Add enumeration storage

**Estimated effort**: 1-2 hours

---

### 6. Conditional Sections
**Priority**: LOW  
**Complexity**: Medium

**What's needed:**
- Parse `<![ INCLUDE [...]]>` - include content
- Parse `<![ IGNORE [...]]>` - skip content
- Handle parameter entity references in condition: `<![ %entity; [...]]>`
- Nested conditional sections

**Current status:**
- 🔲 Not implemented at all

**Files to modify:**
- `DTDParser.java` - Add conditional section handling

**Estimated effort**: 3-4 hours

---

## 🚫 EXPLICITLY DEFERRED (Not Core Features)

### 1. Validation
**Priority**: LOW (explicitly deferred)

- DTD content model validation
- Attribute type validation (NMTOKEN, ID, IDREF, etc.)
- ID/IDREF uniqueness and reference checking
- NOTATION validation
- ENTITY/ENTITIES attribute validation

### 2. Character Encoding Detection Improvements
**Priority**: LOW

- HTTP Content-Type header parsing
- Additional encodings beyond UTF-8/UTF-16

### 3. Reader Support
**Priority**: LOW

Currently only ByteBuffer supported. Reader (character stream) support deferred.

---

## SUMMARY OF MISSING CORE FEATURES

Ranked by priority:

| Feature | Priority | Complexity | Effort | Blocks? |
|---------|----------|-----------|--------|---------|
| **External entity resolution** | HIGH | Medium | 2-3h | Many documents |
| **Default attribute values** | MEDIUM | Low-Medium | 2-3h | DTD-aware apps |
| **Parameter entity expansion** | MEDIUM | Medium | 3-4h | Complex DTDs |
| **Namespace processing** | MEDIUM | Medium-High | 4-5h | Modern XML |
| **ATTLIST enumeration** | LOW | Low | 1-2h | Validation |
| **Conditional sections** | LOW | Medium | 3-4h | Rare |

**Total estimated effort for all core features**: ~15-20 hours

---

## RECOMMENDATION

Focus on these in order:

1. **External entity resolution** (3h) - Most impactful, many documents need it
2. **Default attribute values** (3h) - Important for DTD-aware applications
3. **Namespace processing** (5h) - Essential for modern XML
4. **Parameter entity expansion** (4h) - Needed for complex DTDs

After these 4, you'll have a very solid, production-ready XML parser covering ~95% of real-world use cases.

The rest (validation, conditional sections, etc.) can be added later as needed.

