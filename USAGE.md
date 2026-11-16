# Usage

How to use Gonzalez: the push-style SAX parser, the native `XMLHandler`
parser, JAXP, document locations and the `XMLWriter`. Part of the
[Gonzalez documentation](README.md).

```java
Parser parser = new Parser();

// Set handlers
parser.setContentHandler(myContentHandler);

// Set document identifiers (optional, for Locator reporting)
parser.setSystemId("http://example.com/document.xml");
parser.setPublicId("-//Example//DTD Example 1.0//EN");

// Feed data as it arrives. Do this instead of parse() for non-blocking
// behaviour
ByteBuffer buffer = ByteBuffer.allocate(8192);
while (channel.read(buffer) != -1) {
    buffer.flip();
    parser.receive(buffer);
    buffer.compact();   // keep any bytes not yet consumed
}
// Signal end of document
parser.close();
```

The buffer is yours. The parser consumes all it can of each buffer and leaves
at most the few bytes of an incomplete character, which must be at the start
of the buffer on the next call: use `compact()`, not `clear()`, and do not
pass a different buffer holding only the new data. If a call does not begin
with the bytes the previous one left, the parser throws
`IllegalStateException` instead of parsing a document with bytes missing.

## Native XMLHandler Usage

Native consumers can bypass `SAXAdapter` and receive the Scanner's streaming
event vocabulary directly. `XMLParser` is the native parser: it has no SAX
feature or property URIs, only typed settings, and reports to one `XMLHandler`:

```java
XMLParser parser = new XMLParser();
parser.setHandler(createNativeHandler());
parser.setNamespaceAware(true);       // the default
parser.setDisallowDoctypeDecl(true);

ByteBuffer buffer = ByteBuffer.allocate(8192);
while (channel.read(buffer) != -1) {
    buffer.flip();
    parser.receive(buffer);
    buffer.compact();                 // keep any bytes not yet consumed
}
parser.close();
```

`parse(InputStream)` and `parse(ReadableByteChannel)` run the same loop for
callers that are content to block. Set the handler before parsing starts.
Namespace events pass through `NamespaceFilter` when namespace processing is
enabled. It also enforces the constraints of Namespaces in XML, so a document
with an unbound prefix, a malformed qualified name or two attributes with the
same expanded name is rejected here exactly as it is through SAX. Settings and
the handler survive `reset()`.

A native handler can also be installed on the SAX `Parser` with
`Parser.setXMLHandler`, for code that is handed an `XMLReader` or an
`InputSource`. When non-null, it receives content, lexical, declaration, DTD
and error events instead of the configured SAX handlers. The setting survives
`Parser.reset()`; pass `null` before the next parse to restore the SAX path.

`XMLHandler` is intended for Gonzalez modules and tightly integrated
applications that benefit from streamed `CharBuffer` content and attributes.
It is a Gonzalez-specific evolving SPI, not a compatibility substitute for
SAX. Use SAX/JAXP interfaces when a stable standards boundary is required.

## JAXP Usage

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

## Document Location Tracking

The parser implements `org.xml.sax.ext.Locator2`. The locator is automatically
passed to the ContentHandler via `setDocumentLocator()` before
`startDocument()` is called.

The locator reports:
- System identifier (URI)
- Public identifier
- Character encoding
- XML version

Line and column positions are reported as `-1` unless asked for (see
[Line and Column Numbers](#line-and-column-numbers)). Set the system and public
identifiers explicitly if you want them reported:

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

## Line and Column Numbers

The locator the parser gives its handler reports -1 for the line and the
column, unless asked otherwise, because working them out takes a pass over the
characters of the document:

```java
// Native parser
XMLParser parser = new XMLParser();
parser.setLineNumbers(true);

// SAX
Parser reader = new Parser();
reader.setFeature(Parser.LINE_NUMBERS_FEATURE, true);
```

Lines and columns count from 1, and a carriage return and line feed is one
line break. The position is that of the parser when it reports the event, so
for a start tag it is at the end of the tag. The text of an external entity has
lines of its own; that of an internal entity is located at its reference. The
XSLT compiler asks for them, so that an error is reported at the line of the
instruction that caused it.

## XMLWriter Usage

The XMLWriter has a `write*` API whose methods throw `IOException` directly.
A start tag stays open until something other than an attribute or namespace
declaration is written, and an element with no content is written in the
self-closing form:

```java
import org.bluezoo.gonzalez.IndentConfig;
import org.bluezoo.gonzalez.XMLWriter;

try (FileOutputStream fos = new FileOutputStream("output.xml")) {
    XMLWriter writer = new XMLWriter(fos);
    writer.setIndentConfig(IndentConfig.spaces2());

    writer.writeStartElement("root");
    writer.writeDefaultNamespace("http://example.com/ns");
    writer.writeStartAttribute("version");
    writer.writeAttributeContent("1.0");
    writer.writeEndAttribute();

    writer.writeStartElement("item");
    writer.writeStartAttribute("id");
    writer.writeAttributeContent("1");
    writer.writeEndAttribute();
    writer.writeCharacters("Hello, World!");
    writer.writeEndElement();

    writer.writeStartElement("empty");
    writer.writeEndElement();

    writer.writeEndElement();
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

Content of any size can be written in pieces, so that neither the caller nor
the writer holds the whole of it. Character data takes any number of
`writeCharacters` calls; an attribute value, a comment and the data of a
processing instruction are each written between a start and an end call:

```java
writer.writeStartElement("item");
writer.writeStartAttribute("summary");
writer.writeAttributeContent(firstPart);    // any CharSequence
writer.writeAttributeContent(secondPart);
writer.writeEndAttribute();
```

`writeStartComment` and `writeStartProcessingInstruction` work the same way.
The writer sends its buffer to the channel whenever it fills, so its memory
does not grow with what is written.

To copy or re-encode a document, give an `XMLParser` an `XMLWriterHandler`.
Every event goes to the writer as it is reported, in the pieces the parser
reports it in, so the document is written in the same constant memory it is
parsed in:

```java
import org.bluezoo.gonzalez.XMLParser;
import org.bluezoo.gonzalez.XMLWriter;
import org.bluezoo.gonzalez.XMLWriterHandler;

XMLWriter writer = new XMLWriter(outputChannel);
writer.setCharset(StandardCharsets.UTF_8);

XMLParser parser = new XMLParser();
parser.setHandler(new XMLWriterHandler(writer));
parser.parse(inputChannel);         // or receive() as the data arrives
```

What is written is the document as the parser reports it: entity references
are written as what they stand for, attributes defaulted by the DTD are
written out, and no XML declaration is written.
