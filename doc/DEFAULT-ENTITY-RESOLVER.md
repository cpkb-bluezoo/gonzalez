# DefaultEntityResolver Implementation

## Overview

Gonzalez now has a **DefaultEntityResolver** that provides automatic URL-based entity resolution when no user-specified resolver is set. This enables external entity processing and `parse(String systemId)` to work out of the box without requiring users to configure entity resolution.

## Architecture

### Lazy Creation Pattern

```
┌─────────────────────────────────────────────────────────┐
│                    XMLParser                            │
│                                                         │
│  getEntityResolutionHelper()                            │
│    ↓                                                    │
│  Has user resolver?                                     │
│    YES → Use entityResolver                             │
│    NO  → Use getDefaultEntityResolver()                 │
│             ↓                                           │
│          DefaultEntityResolver (lazy created)           │
│            - baseURL from locator.getSystemId()         │
│            - or current directory if no baseURL         │
└─────────────────────────────────────────────────────────┘
```

### Memory Efficiency

✅ **No overhead if not needed:**
- DefaultEntityResolver only created when:
  - External entities are referenced
  - `parse(String systemId)` is called
  - No user-specified resolver is set

✅ **Cleared on reset():**
- New base URL for each document
- GC reclaims memory between parses

## DefaultEntityResolver Class

### Constructor

```java
// Use current directory as base
DefaultEntityResolver resolver = new DefaultEntityResolver();

// Use specific base URL
URL baseURL = new URL("http://example.com/docs/");
DefaultEntityResolver resolver = new DefaultEntityResolver(baseURL);
```

### Resolution Logic

```java
@Override
public InputSource resolveEntity(String publicId, String systemId) 
        throws SAXException, IOException {
    
    if (systemId == null) {
        return null;
    }

    // Try to parse systemId as absolute URL
    URL url;
    try {
        url = new URL(systemId);
        // Already absolute
    } catch (MalformedURLException e) {
        // Relative, resolve against base
        URL base = getBaseURL();
        url = new URL(base, systemId);
    }

    // Open the URL
    URLConnection connection = url.openConnection();
    InputStream stream = connection.getInputStream();

    // Return InputSource
    InputSource source = new InputSource(stream);
    source.setSystemId(url.toString());
    source.setPublicId(publicId);
    
    return source;
}
```

### Base URL Determination

**Priority:**
1. **Base URL from constructor** (if provided)
2. **Current working directory** (`user.dir` system property)

**Current directory as file: URL:**
```java
File currentDir = new File(System.getProperty("user.dir"));
String path = currentDir.toURI().toString();
if (!path.endsWith("/")) {
    path += "/";  // Ensure directory URL
}
return new URL(path);
```

### Supported Protocols

✅ **file:** - Local files
✅ **http:** - HTTP URLs
✅ **https:** - HTTPS URLs  
✅ **Any protocol** supported by `URLConnection`

## Integration

### XMLParser Integration

**getDefaultEntityResolver():**
```java
private DefaultEntityResolver getDefaultEntityResolver() {
    if (defaultEntityResolver == null) {
        // Get base URL from main document's systemId
        String baseSystemId = (locator != null) ? locator.getSystemId() : null;
        
        if (baseSystemId != null) {
            try {
                URL baseURL = new URL(baseSystemId);
                defaultEntityResolver = new DefaultEntityResolver(baseURL);
            } catch (MalformedURLException e) {
                // Not a valid URL, use current directory
                defaultEntityResolver = new DefaultEntityResolver();
            }
        } else {
            // No base systemId, use current directory
            defaultEntityResolver = new DefaultEntityResolver();
        }
    }
    return defaultEntityResolver;
}
```

**getEntityResolutionHelper():**
```java
private EntityResolutionHelper getEntityResolutionHelper() {
    if (entityResolutionHelper == null) {
        // Use user resolver if set, otherwise use default
        EntityResolver resolver = (entityResolver != null) 
            ? entityResolver 
            : getDefaultEntityResolver();
        
        entityResolutionHelper = new EntityResolutionHelper(
            resolver, locator, resolveDTDURIsEnabled);
    }
    return entityResolutionHelper;
}
```

### Parser Integration

**parse(String systemId):**
```java
@Override
public void parse(String systemId) throws IOException, SAXException {
    // Try user-specified resolver first
    EntityResolver resolver = xmlParser.getEntityResolver();
    if (resolver != null) {
        InputSource source = resolver.resolveEntity(null, systemId);
        if (source != null) {
            parse(source);
            return;
        }
    }
    
    // Fall back to default resolver
    DefaultEntityResolver defaultResolver = new DefaultEntityResolver();
    InputSource source = defaultResolver.resolveEntity(null, systemId);
    if (source != null) {
        parse(source);
        return;
    }
    
    throw new SAXException("Could not resolve system ID: " + systemId);
}
```

## Usage Examples

### Example 1: Relative Entity References

**Document structure:**
```
/project/xml/main.xml
/project/xml/entities/footer.xml
```

**main.xml:**
```xml
<!DOCTYPE root [
  <!ENTITY footer SYSTEM "entities/footer.xml">
]>
<root>&footer;</root>
```

**Resolution:**
1. Main document parsed: `systemId = "file:///project/xml/main.xml"`
2. DefaultEntityResolver created with `baseURL = "file:///project/xml/"`
3. Entity reference: `systemId = "entities/footer.xml"`
4. Resolved: `file:///project/xml/entities/footer.xml`
5. File opened and parsed

### Example 2: Absolute Entity References

**main.xml:**
```xml
<!DOCTYPE root [
  <!ENTITY remote SYSTEM "http://example.com/data.xml">
]>
<root>&remote;</root>
```

**Resolution:**
1. Entity reference: `systemId = "http://example.com/data.xml"`
2. Already absolute, used directly
3. HTTP connection opened and parsed

### Example 3: parse(String systemId)

**No resolver:**
```java
Parser parser = new Parser();
parser.setContentHandler(handler);

// Parses from current directory
parser.parse("document.xml");
// Resolved: file:///current/directory/document.xml

// Absolute URL
parser.parse("http://example.com/doc.xml");
// Opens: http://example.com/doc.xml
```

**With resolver:**
```java
parser.setEntityResolver(catalogResolver);
parser.parse("http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
// Catalog redirects to local file
```

## Base URL Tracking

### Main Document

When parsing starts:
```java
parser.parse(new InputSource("http://example.com/docs/main.xml"));
```

DefaultEntityResolver uses: `baseURL = "http://example.com/docs/"`

### Nested Entities

Each entity gets its own XMLTokenizer with its own systemId:

```
Main: http://example.com/docs/main.xml
  → baseURL: http://example.com/docs/
  
  Entity: entities/footer.xml
    → Resolved: http://example.com/docs/entities/footer.xml
    → New baseURL: http://example.com/docs/entities/
    
    Nested Entity: common/copyright.xml
      → Resolved: http://example.com/docs/entities/common/copyright.xml
```

**Automatic!** Each XMLTokenizer's systemId becomes the base for entities it references.

## Security Considerations

### XXE Protection

Users can disable external entities:
```java
parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
// DefaultEntityResolver will not be called
```

### Custom Resolution

Users can override default behavior:
```java
parser.setEntityResolver(new EntityResolver() {
    @Override
    public InputSource resolveEntity(String publicId, String systemId) {
        // Block remote URLs
        if (systemId.startsWith("http://") || systemId.startsWith("https://")) {
            throw new SAXException("Remote entities not allowed");
        }
        // Allow local files only
        return null; // Use default
    }
});
```

### URL Validation

DefaultEntityResolver uses standard Java URLConnection:
- Respects security manager restrictions
- Subject to URL access policies
- No special privileges

## Benefits

### 1. Zero Configuration

✅ **Works out of the box:**
```java
Parser parser = new Parser();
parser.parse("document.xml");  // Just works!
```

No need to configure entity resolution for common cases.

### 2. Proper Relative Resolution

✅ **Relative entities resolved correctly:**
```xml
<!ENTITY footer SYSTEM "../common/footer.xml">
```

Resolved relative to the document containing the entity declaration.

### 3. Memory Efficient

✅ **Lazy creation:**
- No overhead if no entities
- No overhead if user sets custom resolver
- Cleared on reset()

### 4. Protocol Support

✅ **Any URLConnection protocol:**
- file: (local files)
- http:/https: (web resources)
- ftp: (FTP servers)
- jar: (JAR file entries)
- Custom protocols (via URLStreamHandler)

### 5. Fallback Pattern

✅ **User resolver takes precedence:**
```
User EntityResolver → Custom logic
  ↓ (if returns null)
DefaultEntityResolver → Standard URL resolution
```

## Limitations & Future Work

### Current Limitations

⚠️ **No catalog support** - For XML catalogs, users must provide custom resolver

⚠️ **No caching** - Each entity reference re-opens the URL

⚠️ **Blocking I/O** - URLConnection.openStream() blocks (but this is acceptable per design)

⚠️ **No authentication** - HTTP Basic/Digest auth not handled

### Future Enhancements

**1. Catalog Integration**
```java
// Future: Built-in catalog support
parser.setCatalogFiles("catalog.xml");
// Maps public IDs to local files
```

**2. Entity Caching**
```java
// Future: Cache resolved entities
DefaultEntityResolver.setCache(entityCache);
// Avoids re-downloading frequently used entities
```

**3. Custom URL Handlers**
```java
// Future: Register custom protocols
DefaultEntityResolver.registerProtocol("myproto", handler);
```

## Testing

### Test: With Custom Resolver

```
Test 1: parse(systemId) with EntityResolver
  EntityResolver called:
    publicId: null
    systemId: http://example.com/test.xml
  startDocument()
  startElement: document
  ...
  endDocument()
  ✓ EntityResolver was used to resolve systemId
  ✓ Passed
```

### Test: Without Custom Resolver

DefaultEntityResolver handles resolution automatically (tested implicitly).

## Summary

The DefaultEntityResolver provides:

✅ **Automatic entity resolution** - Works without configuration
✅ **Relative URL support** - Proper base URL tracking
✅ **Memory efficient** - Lazy creation, cleared on reset
✅ **Protocol flexible** - Any URLConnection protocol
✅ **User override** - Custom resolvers take precedence
✅ **Security aware** - Can be disabled via feature flags

Entity resolution now works seamlessly in Gonzalez! 🎯

