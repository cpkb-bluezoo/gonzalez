# Gonzalez

Non-blocking streaming XML parser for event-driven I/O

This is Gonzalez, a data-driven XML parser using non-blocking, event-driven I/O.
Unlike traditional SAX parsers that pull data from an InputSource, Gonzalez is
completely feedforward: you push data to it as it arrives, and it produces SAX
events without ever blocking, as long as the documents are standalone.

## Features

- non-blocking: in streaming mode, will only ever block for external
  entities
- data-driven: processes whatever is available, state machine driven
- SAX-compatible: generates standard ContentHandler events and has a SAX2
  convenience front end (which is blocking, of course)
- lazy DTD parsing: DTD parser loaded only when a DOCTYPE is encountered
- memory efficient: streaming architecture handles documents of any size
- Java NIO throughput: uses ByteBuffer for content I/O operations

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

### Tokenizer

The tokenizer internally manages conversion of bytes to characters via an NIO
decoder. It then processes the character buffer to produce token events using
a state trie to predictably decide on token types and boundaries. The XML
declaration, which is associated with the entity the tokenizer forms a part
of, is transparently handled via the tokenizer which also acts as the
locator for the system and performs line-end normalisation.

When the tokenizer emits tokens, it passes them to a token consumer via
the latters receiveToken interface, using an NIO CharBuffer for any character
data..

### Content Parser

The content parser is a type of token consumer which handles the main XML
content grammar: elements, attributes, PI, comments etc. It reports events
to the configured SAX ContentHandler.

### DTD Parser

Most XML documents in practice are standalone documents without DTDs. To
minimize memory overhead for the common case, the DTD parser is a separate
component that is loaded only when a DOCTYPE declaration is encountered.

The DTD parser uses the same token consumer interface as the content parser. It
receives DTD content via `receive(ByteBuffer)` and reports declarations through
the standard SAX interfaces: DTDHandler for notations and unparsed entities,
DeclHandler for element and attribute declarations, and LexicalHandler for
comments and entity boundaries.

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

## Building

Use ant to build the project:

```bash
ant
```

This produces a jar file in the `dist` directory.

The core parser has no external dependencies. The HTTP entity resolver requires
Gumdrop's HTTP client implementation and is built separately if Gumdrop is
available.

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

## Dependencies

Core parser:
- Java 8 or later
- SAX API (org.xml.sax)

## License

Gonzalez is licensed under the GNU General Public License version 3.  See
`COPYING` for full terms.


-- Chris Burdess

