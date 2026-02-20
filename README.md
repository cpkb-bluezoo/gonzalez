# Gonzalez

Non-blocking streaming XML parser and serializer for event-driven I/O

This is Gonzalez, a data-driven XML parser and serializer using non-blocking,
event-driven I/O. Unlike traditional SAX parsers that pull data from an
InputSource, Gonzalez is completely feedforward: you push data to it as it
arrives, and it produces SAX events without ever blocking for I/O, as long as
the documents are standalone. The XMLWriter provides the inverse: streaming
XML serialization to NIO channels.

## Features

### Parser
- non-blocking: in streaming mode, will only ever block for external entities
- data-driven: processes whatever is available, state machine driven
- SAX-compatible: generates standard ContentHandler events and has a SAX2
  convenience front end (which is blocking, of course)
- JAXP integration: can be used as a drop-in SAX parser via SAXParserFactory
- JPMS module: proper Java module (`org.bluezoo.gonzalez`) for Java 9+
- lazy DTD parsing: DTD parser loaded only when a DOCTYPE is encountered
- memory efficient: streaming architecture handles documents of any size
- Java NIO throughput: uses ByteBuffer for content I/O operations

### Serializer (XMLWriter)
- SAX2 native: implements `ContentHandler`, `LexicalHandler`, `DTDHandler`, `DeclHandler`
- NIO-first: writes to `WritableByteChannel` with automatic buffering
- SAX pipeline ready: wire directly as parser event sink, no adapter needed
- namespace-aware: full support for prefixed and default namespaces via `startPrefixMapping`
- DOCTYPE output: supports inline DTD declarations with standalone conversion mode
- pretty-print: optional indentation via `IndentConfig`
- empty element optimization: automatically emits `<foo/>` instead of `<foo></foo>`
- proper escaping: handles special characters in content and attributes
- configurable encoding: UTF-8 (default), ISO-8859-1, US-ASCII, or any Java charset

## Architecture

Traditional SAX parsers use a pull model where the parser controls the flow of
data via InputSource. This requires blocking I/O and makes them incompatible
with asynchronous frameworks. Gonzalez inverts this by using a push model where
data is fed to the parser via `receive(ByteBuffer)`.

The parser is a state machine that processes whatever data is available and
buffers incomplete tokens. When the end of available data is reached, it returns
control to the caller, who can then feed more data when it arrives.  This makes
Gonzalez ideal for integration with async I/O frameworks like the Gumdrop
multiserver.

### NIO Event Pipeline

The following diagram illustrates how Gonzalez integrates into an NIO server
pipeline like Gumdrop or Netty, receiving ByteBuffer chunks from network channels, SSL unwrap operations, or other sources, and transforming XML data in real time into SAX events without blocking or loading the entire document into memory:

![Gonzalez NIO Event Pipeline](event-pipeline.svg)

### Tokenizer and EED (external entity decoder)

Gonzalez treats the source document being processed itself as an external
entity, as well as external identities that are potentially referenced
inside it. The job of the EED is to convert byte data into character data,
and it will use the XML declaration and text declarations (if any) as well
as BOMs in the byte data to determine the character set decoding process to
be used via NIO.

The tokenizer processes the character data to produce token events using
a state trie to predictably decide on token types and boundaries.
When the tokenizer emits tokens, it passes them to a token consumer via
the latters receive(Token, CharBuffer) interface, so character data
associated with the token is exposed via the NIO CharBuffer interface.

### Content Parser

The content parser is a type of token consumer which handles the main XML
content grammar: elements, attributes, PI, comments etc. It reports events
to the configured SAX ContentHandler.

### DTD Parser

Most XML documents in practice are standalone documents without DTDs. To
minimize memory overhead for the common case, the DTD parser is a separate
component that is loaded only when a DOCTYPE declaration is encountered.

The DTD parser uses the same token consumer interface as the content parser.
It receives DTD content via `receive(Token, CharBuffer)` and reports
declarations through the standard SAX interfaces: DTDHandler for notations
and unparsed entities, DeclHandler for element and attribute declarations,
and LexicalHandler for comments and entity boundaries.

### External Entity Resolution

Gonzalez will use standard SAX2 EntityResolver and EntityResolver2 mechanisms
for resolving external entities in a blocking fashion. Therefore, if you process
XML streams with external entities (including the document type declaration),
this will inevitably affect the performance and reliability of the parsing
process. There is no solution to this: the alternative, using asynchronous
non-blocking I/O, is technically feasible but the parser still needs to block at
the point the external entity is defined until it has been able to finish
parsing it. Therefore all its content input would have to be buffered until the
parser was in a state ready to process it. Since that would easily lead to
resource exhaustion, Gonzalez design is to implement external entities as a
blocking process.

## XSLT Transformer

Gonzalez includes an XSLT 3.0 transformer that follows the same design
principles as the parser: event-driven processing with predictable resource
usage. The transformer integrates with standard JAXP APIs
(`TransformerFactory`, `Templates`, `Transformer`) and can be used as a
drop-in replacement for other XSLT processors.

Key features:
- **JAXP compliant** - Standard `TransformerFactory` interface
- **Streaming support** - SAX-based transformation pipeline
- **Iterative XPath parser** - Pratt algorithm with explicit stacks, no
  recursion
- **Forward-compatible** - Graceful handling of later XSLT version
  constructs

See **[XSLT.md](XSLT.md)** for detailed documentation on the transformer design,
features, test methodology, and performance characteristics.

## Documentation

- [Javadoc package and class documentation](https://cpkb-bluezoo.github.io/gonzalez/doc/)
- [XSLT Transformer Documentation](XSLT.md)

## Usage

```java
Parser parser = new Parser();

// Set handlers
parser.setContentHandler(myContentHandler);

// Set document identifiers (optional, for Locator reporting)
parser.setSystemId("http://example.com/document.xml");
parser.setPublicId("-//Example//DTD Example 1.0//EN");

// Feed data as it arrives. Do this instead of parse() for non-blocking
// behaviour
parser.receive(byteBuffer1);
parser.receive(byteBuffer2);
// ...
parser.close();
// Signal end of document
```

### JAXP Usage

Gonzalez can also be used via the standard JAXP SAXParserFactory mechanism,
making it a drop-in replacement for other SAX parsers:

```java
// Explicit factory selection
SAXParserFactory factory = SAXParserFactory.newInstance(
    "org.bluezoo.gonzalez.GonzalezSAXParserFactory", null);
factory.setNamespaceAware(true);

SAXParser parser = factory.newSAXParser();
parser.parse(inputStream, myHandler);
```

Or set Gonzalez as the default SAX parser via system property:

```java
System.setProperty("javax.xml.parsers.SAXParserFactory",
    "org.bluezoo.gonzalez.GonzalezSAXParserFactory");

SAXParserFactory factory = SAXParserFactory.newInstance();
SAXParser parser = factory.newSAXParser();
parser.parse(inputStream, myHandler);
```

### Document Location Tracking

The parser implements `org.xml.sax.ext.Locator2` to provide accurate position
information during parsing. The locator is automatically passed to the
ContentHandler via `setDocumentLocator()` before `startDocument()` is called.

The locator tracks:
- Line number (starting at 1)
- Column number (starting at 0)
- System identifier (URI)
- Public identifier
- Character encoding
- XML version

Since Gonzalez is data-driven, you must explicitly set the system and
public identifiers if you want them reported:

```java
parser.setSystemId("http://example.com/document.xml");
parser.setPublicId("-//Example//DTD Example 1.0//EN");
```

Line endings are handled according to XML 1.0 specification section 2.11:
- LF (U+000A)
- CR (U+000D)
- CRLF (CR followed by LF, treated as single line ending)

The parser correctly handles CRLF sequences even when split across `receive()`
boundaries.

XML 1.1 line-end delimiters are also supported in XML 1.1 documents.

### XMLWriter Usage

The XMLWriter implements SAX2 interfaces directly, so it can be wired into a
SAX pipeline as an event sink with no adapter:

```java
import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.XMLWriter;

// Parse and serialize: wire parser directly to writer
Parser parser = new Parser();
FileChannel channel = FileChannel.open(path, WRITE, CREATE);
XMLWriter writer = new XMLWriter(channel);
writer.setIndentConfig(IndentConfig.spaces2());

parser.setContentHandler(writer);
parser.setProperty("http://xml.org/sax/properties/lexical-handler", writer);
parser.parse(inputSource);
writer.close();
```

For standalone use, call SAX methods directly:

```java
import org.bluezoo.gonzalez.XMLWriter;
import org.xml.sax.helpers.AttributesImpl;

try (FileOutputStream fos = new FileOutputStream("output.xml")) {
    XMLWriter writer = new XMLWriter(fos);
    writer.setIndentConfig(IndentConfig.spaces2());

    AttributesImpl atts = new AttributesImpl();
    atts.addAttribute("", "version", "version", "CDATA", "1.0");

    writer.startDocument();
    writer.startPrefixMapping("", "http://example.com/ns");
    writer.startElement("http://example.com/ns", "root", "root", atts);

    AttributesImpl itemAtts = new AttributesImpl();
    itemAtts.addAttribute("", "id", "id", "CDATA", "1");
    writer.startElement("http://example.com/ns", "item", "item", itemAtts);
    char[] text = "Hello, World!".toCharArray();
    writer.characters(text, 0, text.length);
    writer.endElement("http://example.com/ns", "item", "item");

    writer.startElement("http://example.com/ns", "empty", "empty", new AttributesImpl());
    writer.endElement("http://example.com/ns", "empty", "empty");

    writer.endElement("http://example.com/ns", "root", "root");
    writer.endPrefixMapping("");
    writer.endDocument();
    writer.close();
}
```

Output:
```xml
<root xmlns="http://example.com/ns" version="1.0">
  <item id="1">Hello, World!</item>
  <empty/>
</root>
```

## Building

Use ant to build the project:

```bash
ant
```

This produces a jar file in the `dist` directory.

The parser has zero external dependencies.

## Implementation Status

The architecture has been designed to support the full XML specification,
including:
- XML declaration with all charsets supported by Java
- DOCTYPE with internal and external subsets
- Elements with attributes
- Character data and CDATA sections
- Entity references (character, internal, external)
- Processing instructions
- Comments
- Namespaces
- Validation using DTD
- XML 1.1

## Performance

Benchmark results comparing Gonzalez with the JDK's bundled Xerces SAX parser
(Java 11, measured with JMH):

| Document Size | Gonzalez | Xerces | Comparison |
|---------------|----------|--------|------------|
| Small plain (1.3KB) | 9.1 µs | 11.2 µs | Gonzalez **1.23x faster** |
| Small NS-heavy (2.9KB) | 18.3 µs | 18.6 µs | **Essentially tied** |
| Large plain (1MB) | 4026 µs | 1755 µs | Xerces 2.3x faster |
| Large NS-heavy (1.4MB) | 9876 µs | 4408 µs | Xerces 2.2x faster |

For small documents, Gonzalez can slightly outperform Xerces due to its
lightweight architecture and efficient NIO buffer handling.

For larger documents, Xerces is faster due to decades of micro-optimisation
in its char[] processing and custom UTF-8 decoder. However, Gonzalez's
streaming architecture provides benefits that don't show in synthetic
benchmarks:

- **Non-blocking I/O**: Your thread is never waiting on data from a network
  connection, allowing integration with async frameworks like Netty or Gumdrop
- **Memory efficiency**: Documents are processed in chunks without loading
  the entire file into memory

Xerces and other blocking I/O based parsers require you either to wait for
the entire document to arrive, or to allocate another thread specifically
for that parse.

## Conformance

Gonzalez has been tested with the W3C Conformance test suite xmlconf and
achieves 100% conformance with that suite.

## Dependencies

- Java 8 or later (including SAX API)

For Java 9+, Gonzalez is a proper JPMS module:

```java
module myapp {
    requires org.bluezoo.gonzalez;
}
```

## License

Gonzalez is licensed under the GNU Lesser General Public License version 3.
See `COPYING` for full terms.


-- Chris Burdess

