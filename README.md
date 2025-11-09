# Gonzalez
Non-blocking streaming XML parser for event-driven I/O

This is Gonzalez, a data-driven XML parser using non-blocking, event-driven I/O. Unlike traditional SAX parsers that pull data from an InputSource, Gonzalez is completely feedforward: you push data to it as it arrives, and it produces SAX events without ever blocking.

## Features

- completely non-blocking: never reads from streams or waits for data
- data-driven: processes whatever is available, state machine driven
- SAX-compatible: generates standard ContentHandler events
- asynchronous entity resolution: external entities resolved without blocking
- lazy DTD parsing: DTD parser loaded only when a DOCTYPE is encountered
- memory efficient: streaming architecture handles documents of any size
- Java NIO throughout: uses ByteBuffer for all I/O operations

## Architecture

Traditional SAX parsers use a pull model where the parser controls the flow of data via InputSource. This requires blocking I/O and makes them incompatible with asynchronous frameworks. Gonzalez inverts this by using a push model where data is fed to the parser via `receive(ByteBuffer)`.

The parser is a state machine that processes whatever data is available and buffers incomplete tokens. When the end of available data is reached, it returns control to the caller, who can then feed more data when it arrives. This makes Gonzalez ideal for integration with async I/O frameworks like the Gumdrop HTTP client.

### DTD Parser

Most XML documents in practice are standalone documents without DTDs. To minimize memory overhead for the common case, the DTD parser is a separate component that is loaded only when a DOCTYPE declaration is encountered.

The DTD parser uses the same non-blocking architecture as the main parser. It receives DTD content via `receive(ByteBuffer)` and reports declarations through the standard SAX interfaces: DTDHandler for notations and unparsed entities, DeclHandler for element and attribute declarations, and LexicalHandler for comments and entity boundaries.

### Entity Resolution

The biggest architectural challenge for a non-blocking XML parser is external entity resolution. Traditional SAX uses EntityResolver, which returns an InputSource that the parser then reads from synchronously. This is fundamentally a blocking operation.

Gonzalez solves this with AsyncEntityResolver, which receives an EntityReceiver callback. The resolver initiates the entity fetch (e.g., via HTTP) and feeds entity content to the receiver as it arrives. The receiver is connected to a nested parse context inside the main parser. When the entity is complete, the parser pops the context and resumes processing the document.

This design allows entities to be fetched via any asynchronous mechanism (HTTP, filesystem with async I/O, etc.) without blocking the parser. Entity resolution becomes just another source of ByteBuffers being fed into the parser.

## External Entity Resolution: Performance and Complexity

**In 99% of real-world cases**, XML documents are **standalone** - they contain no external entity references. For these documents, Gonzalez's non-blocking, asynchronous architecture delivers **superior performance**:

- Zero blocking I/O
- No thread coordination overhead
- Predictable memory usage
- Linear processing of incoming data

However, **external entities represent a fundamental challenge** to the non-blocking model. When an external entity is encountered, the parser must:

1. **Suspend main document processing** (enter buffering mode)
2. **Resolve the entity asynchronously** (potentially via HTTP)
3. **Process entity content** (which may itself contain more entities)
4. **Resume main document processing** (process buffered data)

This creates significant complexity because:

- **Main document data continues to arrive** while entities are being resolved
- **Entities can be nested** (entities referencing other entities)
- **Network delays** can cause entities to resolve in any order
- **Failures must be detected** (timeouts, network errors)

### Entity Receiver Architecture

Gonzalez handles this complexity through a **chain of entity receivers**, each managing one entity resolution:

```
Main Parser (document.xml)
    ├─ buffering main document data
    └─ EntityReceiver 1 (http://example.com/entity1.xml)
        ├─ Wraps child parser instance
        ├─ Processes entity1 content
        └─ EntityReceiver 2 (http://example.com/entity2.xml)
            ├─ Wraps grandchild parser instance
            ├─ Processes entity2 content
            └─ Completes → callbacks to EntityReceiver 1
                └─ Completes → callbacks to Main Parser
```

**Key behaviors:**

1. **Each entity gets its own parser instance** that processes entity content independently
2. **Parent parsers buffer incoming data** while waiting for child entities to complete
3. **Memory usage grows** with nesting depth and buffered data size
4. **Thread safety** is maintained through synchronization on the parent parser
5. **Circular references are detected** by tracking systemIds (URLs) in the resolution chain

### Timeout Configuration

Since entity resolution involves network I/O, **timeouts are essential** to prevent the parser from hanging indefinitely. Gonzalez provides configurable per-entity timeouts:

```java
// Set timeout to 10 seconds (default)
parser.setEntityTimeout(10_000);

// Increase timeout for slow networks
parser.setEntityTimeout(30_000);

// Disable timeout (not recommended)
parser.setEntityTimeout(0);
```

**How timeouts work:**

- Timeout measures **gaps between data chunks**, not total resolution time
- Timer **resets on each receive()** call, so only network delays are measured
- Timeout applies **per entity**, allowing identification of which entity failed
- When timeout occurs, a **fatal error** is raised and propagates up the resolution chain
- All active entities are **cleaned up** and buffering stops

**Configuration guidelines:**

- **LAN/localhost**: 5-10 seconds is usually sufficient
- **Internet**: 15-30 seconds to account for network variability
- **Slow/unreliable networks**: 30-60 seconds or more
- **Known fast sources**: Can use shorter timeouts (2-5 seconds)

**Important:** You must configure timeouts based on your knowledge of:
- Network topology (LAN vs Internet)
- Entity source reliability (internal server vs third-party)
- Expected entity sizes (small DTDs vs large documents)
- Acceptable failure modes (fail fast vs tolerate delays)

**Security note:** Entity resolution is a known attack vector (XXE - XML External Entity attacks). Always:
- Validate entity URLs in your `AsyncEntityResolverFactory`
- Use allowlists for trusted entity sources
- Set reasonable timeouts to prevent resource exhaustion
- Consider disabling external entities entirely for untrusted input

### Memory Considerations

When external entities are encountered:

- **Main document buffering**: All incoming main document data is buffered until the entity completes
- **Nested buffering**: Each nesting level buffers its parent's data
- **Maximum depth**: Limited to 10 levels by default to prevent unbounded memory growth
- **Maximum entity size**: Limited to 10MB per entity to prevent memory exhaustion

For documents with many or large entities, memory usage can be significant. Monitor your application's memory footprint when processing entity-heavy documents.

## Usage

```java
GonzalezParser parser = new GonzalezParser();

// Set handlers
parser.setContentHandler(myContentHandler);
parser.setEntityResolverFactory(myResolverFactory);

// Set document identifiers (optional, for Locator reporting)
parser.setSystemId("http://example.com/document.xml");
parser.setPublicId("-//Example//DTD Example 1.0//EN");

// Feed data as it arrives
parser.receive(byteBuffer1);
parser.receive(byteBuffer2);
// ...
parser.close(); // Signal end of document
```

### Document Location Tracking

The parser implements `org.xml.sax.ext.Locator2` to provide accurate position information during parsing. The locator is automatically passed to the ContentHandler via `setDocumentLocator()` before `startDocument()` is called.

The locator tracks:
- Line number (starting at 1)
- Column number (starting at 0)
- System identifier (URI)
- Public identifier
- Character encoding
- XML version

Since Gonzalez is data-driven and doesn't use InputSource, you must explicitly set the system and public identifiers if you want them reported:

```java
parser.setSystemId("http://example.com/document.xml");
parser.setPublicId("-//Example//DTD Example 1.0//EN");
```

Line endings are handled according to XML 1.0 specification section 2.11:
- LF (U+000A)
- CR (U+000D)
- CRLF (CR followed by LF, treated as single line ending)

The parser correctly handles CRLF sequences even when split across `receive()` boundaries.

### HTTP Entity Resolution

The parser discovers external entities during processing and needs to resolve them. Since entities can reference arbitrary URLs with different hosts and ports, the parser uses a factory pattern to request resolvers on demand:

```java
parser.setEntityResolverFactory(new AsyncEntityResolverFactory() {
  public AsyncEntityResolver createResolver(String publicId, String systemId) 
      throws SAXException {
    // Parse URL
    URL url = new URL(systemId);
    
    // Security: validate the URL
    if (!systemId.startsWith("https://trusted.example.com/")) {
      return null; // Reject entity, treat as empty
    }
    
    // Create HTTP client for this host/port
    HTTPClient client = new HTTPClient(url.getHost(), 
                                       url.getPort() > 0 ? url.getPort() : 443,
                                       "https".equals(url.getProtocol()));
    
    // Return resolver for this specific entity
    return new HTTPEntityResolver(client, systemId);
  }
});
```

The factory is called each time an external entity is encountered. It can:
- Validate URLs and reject untrusted entities (return null)
- Create appropriately configured HTTP clients per host/port
- Implement caching or connection pooling
- Apply security policies to prevent XXE attacks

When the factory returns null, the parser treats the entity as having empty content and continues processing.

## Interfaces

### AsyncEntityResolverFactory

Factory for creating entity resolvers on demand as external entities are discovered:

```java
public interface AsyncEntityResolverFactory {
  AsyncEntityResolver createResolver(String publicId, String systemId) 
      throws SAXException;
}
```

The parser calls this factory when it encounters an external entity reference. The factory examines the URL and either returns an appropriate resolver or null to reject the entity.

### EntityReceiver

Receives byte data for an external entity. This interface mirrors the parser's own data-driven design:

```java
public interface EntityReceiver {
  void receive(ByteBuffer data) throws SAXException;
  void close() throws SAXException;
}
```

### AsyncEntityResolver

Resolves external entities in a non-blocking manner. Each resolver instance is created for one specific entity URL:

```java
public interface AsyncEntityResolver {
  void resolveEntity(String publicId, String systemId, 
                     EntityReceiver receiver) throws SAXException;
}
```

The implementation initiates fetching of the entity content and feeds it to the receiver as it arrives, then closes the receiver when complete.

### SAX Handler Interfaces

The parser supports the standard SAX2 handler interfaces plus extensions:

- **ContentHandler**: Core XML events (startElement, endElement, characters, etc.)
- **DTDHandler**: Notation and unparsed entity declarations
- **LexicalHandler**: Comments, CDATA sections, and entity boundaries
- **DeclHandler**: Element and attribute declarations from DTD
- **Attributes2**: Extended attribute information (whether declared/specified)

## Building

Use ant to build the project:

```bash
ant
```

This produces a jar file in the `dist` directory.

The core parser has no external dependencies. The HTTP entity resolver requires Gumdrop's HTTP client implementation and is built separately if Gumdrop is available.

## Implementation Status

This is the initial framework for Gonzalez. The core architecture and interfaces are in place, but the parser state machine is not yet complete. Current status:

- Core interfaces: complete
- Main parser framework: skeleton in place
- DTD parser: skeleton in place  
- HTTP entity resolver: complete
- Full XML parsing: to be implemented
- DTD parsing: to be implemented

The architecture has been designed to support the full XML specification, including:
- XML declaration
- DOCTYPE with internal and external subsets
- Elements with attributes
- Character data and CDATA sections
- Entity references (character, internal, external)
- Processing instructions
- Comments
- Namespaces

## Dependencies

Core parser:
- Java 8 or later
- SAX API (org.xml.sax)

HTTP entity resolver:
- Gumdrop HTTP client

## License

Gonzalez is licensed under the GNU General Public License version 3.
See `COPYING` for full terms.


-- Chris Burdess

