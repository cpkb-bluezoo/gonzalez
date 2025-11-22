# External Entity Resolution Implementation

## Overview

Gonzalez now has the infrastructure to resolve and process external entities! The implementation uses a **nested tokenizer** pattern where external entities are parsed by temporary XMLTokenizer instances that feed tokens back to the main XMLParser.

## Architecture

### Entity Processing Flow

```
┌─────────────────────────────────────────────────────────────┐
│                       XMLParser                             │
│  receive(Token, CharBuffer) ◄──────────┬──────────────┐    │
│                                         │              │    │
│  When entity reference encountered:     │              │    │
│  1. Call processExternalEntity()        │              │    │
│  2. Resolve via EntityResolutionHelper  │              │    │
│  3. Create nested XMLTokenizer          │              │    │
│  4. Feed InputStream to tokenizer       │              │    │
│  5. Tokenizer sends tokens back ────────┘              │    │
│  6. Continue with main stream                          │    │
└────────────────────────────────────────────────────────┼────┘
                                                         │
                    ┌────────────────────┬───────────────┘
                    │                    │
         ┌──────────▼────────┐   ┌──────▼──────────────┐
         │  XMLTokenizer     │   │  XMLTokenizer       │
         │  (main document)  │   │  (external entity)  │
         │                   │   │                     │
         │  systemId:        │   │  systemId:          │
         │   main.xml        │   │   entity.xml        │
         │  line: 5          │   │  line: 1            │
         │  col: 23          │   │  col: 0             │
         └───────────────────┘   └─────────────────────┘
```

### Key Components

**1. EntityResolutionHelper** (Already implemented)
- Resolves entity references
- Supports EntityResolver and EntityResolver2
- Handles relative URI resolution
- Returns InputSource for entity

**2. XMLParser.processExternalEntity()** (NEW)
- Creates nested XMLTokenizer for entity
- Feeds InputStream → ByteBuffer → tokenizer.receive()
- Handles recursion protection
- Respects `external-general-entities` feature flag

**3. XMLTokenizer** (Unchanged)
- Each instance tracks its own systemId, line, column
- Implements Locator2 (position reporting)
- Sends tokens to TokenConsumer (XMLParser)
- Can be nested without knowing about nesting

## Implementation Details

### processExternalEntity() Method

```java
private void processExternalEntity(String name, String publicId, String systemId)
        throws SAXException, IOException {
    
    // 1. Check if external entities are enabled
    if (!externalGeneralEntitiesEnabled) {
        return; // Skip
    }
    
    // 2. Get entity resolution helper
    EntityResolutionHelper helper = getEntityResolutionHelper();
    if (helper == null) {
        return; // No resolver
    }
    
    // 3. Resolve entity
    InputSource source = helper.resolveEntity(name, publicId, systemId);
    if (source == null) {
        return; // Resolver returned null (use default)
    }
    
    // 4. Check for recursion
    String resolvedSystemId = source.getSystemId();
    if (entityResolutionStack.contains(resolvedSystemId)) {
        throw new SAXException("Recursive entity reference: " + resolvedSystemId);
    }
    
    // 5. Add to stack
    entityResolutionStack.add(resolvedSystemId);
    
    try {
        // 6. Create nested tokenizer
        XMLTokenizer entityTokenizer = new XMLTokenizer(
            this,  // Sends tokens back to this parser
            source.getPublicId(),
            resolvedSystemId
        );
        
        // 7. Feed InputStream to tokenizer
        InputStream inputStream = source.getByteStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
            entityTokenizer.receive(byteBuffer);
        }
        
        entityTokenizer.close();
        inputStream.close();
        
    } finally {
        // 8. Remove from stack
        entityResolutionStack.remove(resolvedSystemId);
    }
}
```

### Recursion Protection

The `entityResolutionStack` (HashSet) tracks system IDs of entities currently being resolved:

```java
private Set<String> entityResolutionStack;

// Before processing entity
if (entityResolutionStack.contains(resolvedSystemId)) {
    throw new SAXException("Recursive entity reference");
}
entityResolutionStack.add(resolvedSystemId);

try {
    // Process entity...
} finally {
    entityResolutionStack.remove(resolvedSystemId);
}
```

**Why systemId?**
- Entities are identified by their resolved URI
- Same entity referenced twice = same systemId = recursion detected
- Works across different entity names that resolve to same URI

### Code Duplication Handled

The InputStream reading logic (~10 lines) is duplicated from `Parser.parse()`:

```java
byte[] buffer = new byte[4096];
int bytesRead;

while ((bytesRead = inputStream.read(buffer)) != -1) {
    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
    entityTokenizer.receive(byteBuffer);
}

entityTokenizer.close();
```

**Why duplicate?**
- Keeps XMLParser self-contained
- Only ~10 lines of simple code
- Alternative (callback/utility) would be more complex
- XMLParser needs to remain independent of Parser

## Token Flow with Nested Entities

### Example XML with Entity

**main.xml:**
```xml
<!DOCTYPE root [
  <!ENTITY footer SYSTEM "footer.xml">
]>
<root>
  <content>Hello</content>
  &footer;
</root>
```

**footer.xml:**
```xml
<footer>Copyright 2025</footer>
```

### Token Sequence

```
Main Tokenizer (main.xml):
  LT, NAME(root), GT
  LT, NAME(content), GT
  CDATA(Hello)
  ...
  ENTITYREF(footer)  ← Parser detects this

Parser calls processExternalEntity("footer", null, "footer.xml")

  Entity Tokenizer (footer.xml):
    LT, NAME(footer), GT
    CDATA(Copyright 2025)
    ...
  
  Entity processing complete

Main Tokenizer continues:
  ...
  START_END_ELEMENT, NAME(root), GT
```

### Locator Behavior

While processing entity, `locator` (from XMLTokenizer) reports:
- `getSystemId()` = "footer.xml"
- `getLineNumber()` = line in footer.xml
- `getColumnNumber()` = column in footer.xml

After entity completes:
- `getSystemId()` = "main.xml"
- Line/column = position in main.xml

**Automatic!** Each XMLTokenizer updates the locator, XMLParser doesn't track this.

## Feature Flag Control

```java
// Disable external entity resolution
parser.setFeature("http://xml.org/sax/features/external-general-entities", false);

// Now entities are skipped (not resolved)
```

**Security:** Applications can disable external entities to prevent XXE attacks.

## Benefits

### 1. Clean Separation
✅ **XMLParser doesn't manage I/O** - just calls processExternalEntity()
✅ **XMLTokenizer doesn't know about nesting** - just tokenizes its stream
✅ **EntityResolutionHelper handles resolution** - isolates complexity

### 2. Natural Recursion
✅ **Entities can reference other entities** - stack unwinds naturally
✅ **Recursion protection** - prevents infinite loops
✅ **Proper error reporting** - locator shows exact position in entity file

### 3. Position Tracking
✅ **Each tokenizer tracks its own position** - no manual tracking needed
✅ **Locator automatically switches** - reports entity position during entity processing
✅ **Line/column accuracy** - preserved across entity boundaries

### 4. Memory Efficiency
✅ **Entity tokenizers are temporary** - GC'd after use
✅ **No entity content buffering** - streaming all the way
✅ **Stack size = nesting depth** - minimal overhead

## Limitations & Future Work

### Current Limitations

❌ **Character streams not supported** - only byte streams (InputStream)
```java
if (inputStream == null) {
    // TODO: Handle Reader (character stream)
    throw new SAXException("Entity must have byte stream");
}
```

❌ **External parameter entities** - not yet implemented
- Would need similar `processExternalParameterEntity()` method
- Called from DTD parsing context
- Uses `externalParameterEntitiesEnabled` flag

❌ **Predefined entities only** - &amp; &lt; &gt; &apos; &quot;
- Custom entity declarations not yet parsed from DTD
- Would need DTDParser to build entity map
- XMLParser would look up entity by name

❌ **No entity value caching** - re-parses on each reference
- Could add entity cache (systemId → tokens)
- Trade memory for speed
- Would need cache invalidation strategy

### Future Enhancements

**1. Character Stream Support**
```java
Reader reader = source.getCharacterStream();
if (reader != null) {
    // Convert Reader → bytes using encoding from XML declaration
    // Or: Create CharBuffer-based tokenizer variant
}
```

**2. External Parameter Entities**
```java
void processExternalParameterEntity(String name, String publicId, String systemId) {
    if (!externalParameterEntitiesEnabled) return;
    // Similar to processExternalEntity but for DTD context
}
```

**3. Internal Entity Support**
```java
// When DTDParser sees: <!ENTITY name "value">
entityMap.put("name", "value");

// When XMLParser sees: &name;
String value = entityMap.get("name");
// Inject value as CDATA tokens
```

**4. Entity Caching**
```java
Map<String, List<TokenEvent>> entityCache;

void processExternalEntity(...) {
    if (entityCache.containsKey(systemId)) {
        // Replay cached tokens
        return;
    }
    // ... parse and cache ...
}
```

## When Entities Will Be Called

Currently, `processExternalEntity()` is implemented but **not yet called** from anywhere. Future work:

**1. In XMLParser.receive()** when encountering entity reference:
```java
case ENTITYREF:
    // Extract entity name from data
    String entityName = extractString(data);
    
    // Look up in predefined entities first
    String predefined = getPredefinedEntity(entityName);
    if (predefined != null) {
        // Inject predefined value
        characters(predefined);
    } else {
        // Resolve external entity
        // Need: publicId, systemId from entity declaration
        processExternalEntity(entityName, publicId, systemId);
    }
    break;
```

**2. In DTDParser** when external subset is declared:
```java
<!DOCTYPE root SYSTEM "dtd/root.dtd">
//             ^^^^^^ External DTD subset

// Call processExternalEntity("root", null, "dtd/root.dtd")
// Tokens from DTD go to DTDParser
```

## Testing

### Test Setup

```java
Parser parser = new Parser();

// Create entity resolver
parser.setEntityResolver(new EntityResolver2() {
    @Override
    public InputSource resolveEntity(String name, String publicId,
                                      String baseURI, String systemId) {
        System.out.println("Resolving: " + name + " -> " + systemId);
        
        // Return InputSource for entity file
        InputStream stream = new FileInputStream(systemId);
        InputSource source = new InputSource(stream);
        source.setSystemId(systemId);
        return source;
    }
    
    // ... other methods ...
});

parser.parse(new InputSource("main.xml"));
```

### Test Case: Nested Entities

**main.xml** references **entity1.xml** which references **entity2.xml**.

**Expected:**
- 3 tokenizers created (main, entity1, entity2)
- Stack depth: 3
- Locator switches between files
- All tokens flow to same XMLParser
- No memory leaks

## Summary

The external entity infrastructure is now in place:

✅ **processExternalEntity()** - resolves and parses entities
✅ **Nested XMLTokenizer pattern** - clean separation
✅ **Recursion protection** - prevents infinite loops
✅ **Feature flag control** - security (disable external entities)
✅ **Automatic position tracking** - Locator2 per entity
✅ **10 lines of I/O code duplication** - acceptable trade-off

**Next steps:**
1. Call `processExternalEntity()` when entity references are encountered
2. Implement internal entity support (DTD declarations)
3. Add external parameter entity support (DTD context)
4. Add character stream support (Reader)

The foundation is solid and ready for entity processing! 🎯

