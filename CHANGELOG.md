# Changelog

All notable changes to Gonzalez will be documented in this file.

## [Unreleased]

### Changed

- **Constant memory without a DTD.** A document with no DTD is now parsed in
  memory bounded by the structural limits below, whatever its size and
  however any part of it is padded. The scanner carries no more than one
  name from one `receive` call to the next: white space inside tags is
  consumed as it is scanned, the XML declaration is consumed as it arrives,
  and the value of `xml:space` is streamed like any other attribute's.
  DTD-typed and validated attribute values, DTD declarations and SAX
  `Attributes` are still materialised.

- **`Parser` is a SAX wrapper.** `Parser` keeps its public API but now owns
  an `XMLParser` and translates feature and property URIs into calls on it.
  Every setting is held once, in `XMLParser`. The XSLT runtime uses
  `XMLParser` directly where it opens a stream itself.

- **`XMLWriter` no longer grows its buffer.** The buffer is sent to the
  channel whenever it fills, and is only replaced by a larger one for a
  single item, such as a name, that would not fit in it empty. Before, it
  grew to hold whatever one call wrote and never shrank. Runs of plain ASCII
  in attribute values, comments, processing instructions and CDATA sections
  are copied in bulk, which makes writing attributes about a third faster.

- **The `receive` buffer contract is checked.** `receive` leaves at most an
  incomplete character in the caller's buffer and expects the next call to
  begin with it. A call that does not now throws `IllegalStateException`
  naming `compact()` as the remedy, where before the lost bytes surfaced
  later as an error in the document. The examples in the Javadoc, the
  package overview and the README, which cleared or replaced the buffer,
  now show the read, flip, receive, compact loop.

- **`XMLWriter` whole-value methods are deprecated.** The three
  `writeAttribute` overloads, `writeComment(String)` and
  `writeProcessingInstruction(String, String)` still work, but each now
  points to its streaming counterpart (`writeStartAttribute`,
  `writeAttributeContent` and `writeEndAttribute`, and likewise for comments
  and processing instructions), which never need the whole value in hand.
  The README and Javadoc examples use the streaming calls.

- **Corrected `XMLWriter` documentation.** The README described `XMLWriter`
  as implementing the SAX2 interfaces and showed it installed as a
  `ContentHandler`, which no longer compiled. It now covers the `write*`
  methods, writing in pieces and `XMLWriterHandler`.

### Added

- **`XMLParser`, the native parser.** A parser without SAX: one
  `XMLHandler`, typed setters in place of feature and property URIs,
  `receive`/`close`/`reset`, and blocking `parse` methods for an
  `InputStream` or a `ReadableByteChannel` that run the same receive loop.

- **Structural limits.** `XMLParser` limits element depth (1,000 by
  default), name length (4,096) and attributes per element (10,000),
  alongside the existing entity expansion limit; zero or a negative value
  removes a limit. The name length limit also covers the digits of a
  character reference and the length of a namespace name. `Parser` maps the
  JDK's property names onto them (`jdk.xml.maxElementDepth`,
  `jdk.xml.maxXMLNameLimit`, `jdk.xml.elementAttributeLimit` and
  `jdk.xml.entityExpansionLimit`, with their older
  `http://www.oracle.com/xml/jaxp/properties/` forms), so configuration
  written for the JDK parser carries over.

- **Writing content in pieces.** An attribute value, a comment and the data
  of a processing instruction can each be written to `XMLWriter` between a
  start and an end call (`writeStartAttribute`, `writeAttributeContent`,
  `writeEndAttribute`, and likewise for comments and processing
  instructions), and `writeCharacters` accepts any `CharSequence`. The
  pieces may divide a surrogate pair, the `--` of a comment or the `?>` of a
  processing instruction.

- **`XMLWriterHandler`.** An `XMLHandler` that passes each event to an
  `XMLWriter` as it arrives and holds nothing, so that an `XMLParser` can
  copy or re-encode a document in the memory it takes to parse it.

### Security

- **Hang on a long XML declaration.** An XML declaration longer than the
  blocking parse methods' buffer, such as an ordinary one padded with white
  space to 40 KB, made `parse` spin forever at 100% CPU. The declaration is
  now consumed as it arrives, and the blocking loops fail if a buffer of
  1 MiB still makes no progress.

- **Unbounded memory.** A document of nothing but start tags, one enormous
  name, an element with unlimited attributes, a tag padded with white space
  or an unending namespace name each grew the parser's memory without
  limit. All are now bounded by the structural limits above.

- **Duplicate attributes were accepted.** An attribute repeated on an
  element was not always rejected: the check compared names by reference
  and so missed any name beyond the bounded name pool, as in
  `<r ax="" bx="" cx="" dx="" ex="1" ex="2"/>`. A repeated namespace
  declaration was accepted in namespace-aware mode, and a native
  `XMLHandler` consumer got no check at all. The scanner now enforces the
  constraint itself for every name and every consumer.

- **Truncated documents were accepted.** A document that ended in an
  unfinished comment, processing instruction or tag after the root element,
  such as `<a/><!-- x`, was reported as complete. It is now a fatal error.

### Fixed

- The base URI of a node from an external parsed entity is now that entity's
  location. The scanner's locator reported the document's system identifier
  throughout, and it did not move the base for relative references to the
  fetched entity while it was being read, so `base-uri()` and the static
  base URI of a stylesheet that includes an entity were those of the
  referring document. A relative system identifier inside an external
  entity now resolves against that entity, and one in an entity declaration
  against the file that holds the declaration, so an entity declared in an
  external DTD subset in another directory is found.

- A pattern predicate that used `position()` or `last()` inside an `if`,
  `let`, `for`, sequence, filter or similar expression was evaluated as if
  the node were the only match, so `match="a/*[if (c) then last() else 1]"`
  matched every child. The check for whether a predicate needs the position
  and size now assumes it does for any expression it does not recognise.

- `xsl:apply-templates` that selected a map, an array or an atomic value did
  not bind the matched template's declared parameters, so `$p` was
  undefined when its default was empty or its `xsl:param` was required. It
  now binds them as for a node, including `XTDE0700` for a required one.

- Namespace constraints are enforced for native consumers. A document with
  an unbound prefix, a malformed qualified name or two attributes with the
  same expanded name was rejected through SAX but accepted by a native
  `XMLHandler`, because the checks lived in the SAX adapter. They are now
  made in the namespace filter ahead of every handler.

- The data of a processing instruction no longer begins with white space.
  Only the first white space character after the target was treated as the
  separator, so `<?target   data?>` was reported with the data `"  data"`.

- A specified attribute whose name was beyond the name pool was also given
  its DTD default, as a second attribute.

- Closing a parser that was never given any data, including parsing an empty
  channel, threw `NullPointerException`. It is now reported as a document
  that is not well-formed.

- `XMLWriter` threw `BufferOverflowException` when a character outside ASCII
  landed on the end of its buffer, which a few thousand accented letters in
  one `writeCharacters` or `writeAttribute` call was enough to cause.

- `XMLWriter` wrote the two halves of a surrogate pair separately when they
  came in different `writeCharacters` calls, producing invalid UTF-8. The
  `char[]` overload also read one char beyond its range when the range
  ended on a high surrogate.

- `XMLWriter` wrote a processing instruction inside a DTD before opening the
  internal subset, producing a malformed DOCTYPE.

- `XMLWriter` wrote entity values and system identifiers that did not read
  back the same. `&` and `"` in an entity value were written as `&amp;` and
  `&quot;`, which are not expanded in a declaration and so became part of
  the value, turning a reference to another entity into text. They are now
  character references. A quotation mark in a system or public identifier
  is now handled by delimiting the literal with apostrophes.

- `XMLWriter` wrote a carriage return in text as it was when writing
  XML 1.0, to be read back as a line feed, and likewise a tab, line feed or
  carriage return in a namespace name, read back as a space. All are now
  character references.

- `XMLWriter` did not notice `]]>` in a CDATA section when it was divided
  between two `writeCharacters` calls.

### Compatibility

- No public class, constructor or method has been removed.

- Documents that exceed a structural limit are now rejected: elements nested
  more than 1,000 deep, a name or namespace name longer than 4,096
  characters, or more than 10,000 attributes on an element. Raise or remove
  the limit on `XMLParser`, or through the JDK property names on `Parser`,
  to accept them.

- Code that calls `receive` must `compact()` its buffer after each call and
  not clear or replace it. Code that did not was already losing bytes at
  some chunk boundaries; it now gets an `IllegalStateException`.

- Processing instruction data no longer has leading white space. A consumer
  that trimmed it is unaffected.

- With namespace processing on, an attribute name that is not a qualified
  name, such as `:a` or `a:b:c`, is now a fatal error. Element names were
  already checked.

- An end tag that names the wrong element and also has stray characters
  after the name is now reported as mismatched and no longer as malformed.

- `XMLWriter` output changes where it was previously wrong: a carriage
  return in text and white space in a namespace name are written as
  character references, entity values use `&#38;` and `&#34;`, and an
  identifier containing a quotation mark is delimited by apostrophes. An
  identifier containing both kinds of quotation mark cannot be written and
  now throws `IOException`.

- With indentation on, `XMLWriter` no longer writes a blank line between a
  DTD that contains a comment and the root element.

- In a CDATA section, `XMLWriter` now detects illegal content as it reaches
  it, so the legal content before it in the same call has been written when
  the `IOException` is thrown. Before, nothing from that call was written.

## [1.2.0] - 2026-07-19

### Changed

- **Rewritten XML parsing engine.** The former tokenizer, token-object and
  `ContentParser` pipeline has been replaced by a resumable character
  `Scanner` that recognizes XML grammar directly. The new engine streams
  text, attribute values, comments and processing-instruction data without
  materializing an intermediate token stream, substantially reducing
  allocation and dispatch overhead.

- **Native streaming event pipeline.** `Scanner` emits a new low-level
  `XMLHandler` event stream through `NamespaceFilter`; native consumers can
  receive it directly, while `SAXAdapter` preserves the standard SAX2 API,
  including `ContentHandler`, `LexicalHandler`, `DeclHandler`, `DTDHandler`
  and `Attributes2` behavior.

- **Faster parsing.** Name interning, character classification, content and
  attribute scans, namespace handling, line-ending normalization and DTD
  lookups have been optimized. On the release benchmark corpus the native
  XMLHandler path reaches 796 MB/s on plain UTF-8, 1,183 MB/s on
  whitespace-heavy XML and 1,133 MB/s on markup-heavy XML; the SAX path is
  faster than the JDK-bundled Xerces parser on all measured UTF-8 corpora.

- **Native XSLT input path.** Stylesheet compilation, Gonzalez-owned
  transformation input, secondary document loading and XSLT package
  compilation now consume the native `XMLHandler` stream. Foreign SAX sources
  and SAX results remain on the SAX path by design.

- **Stronger streaming guarantees.** Arbitrarily large text, attribute
  values, comments and processing instructions are delivered incrementally,
  and incomplete constructs resume correctly across caller-supplied
  `ByteBuffer` boundaries.

### Added

- **Public `XMLHandler` SPI.** Applications that do not require SAX name
  adaptation can install a native streaming handler with
  `Parser.setXMLHandler`; `Parser.getXMLHandler` returns the current native
  handler.

- **DOCTYPE rejection feature.** The de facto standard
  `http://apache.org/xml/features/disallow-doctype-decl` feature rejects any
  document containing a DOCTYPE before entity declarations are processed.
  Default is `false` (matching Xerces); applications that do not need DTDs
  should enable it for the strongest defense against XXE and
  entity-expansion attacks.

- **Expanded regression and conformance coverage.** Added exhaustive chunk
  boundary sweeps, randomized chunking tests, UTF-16 fixtures, encoding and
  malformed-input tests, SAX event-fidelity tests, secure-default tests and
  native XSLT pipeline tests. The parser passes all 1,879 applicable W3C XML
  conformance tests.

- **Continuous integration.** Added a CI workflow for repeatable builds and
  test execution.

### Security

- **JAXP factory feature enforcement.** Fixed
  `GonzalezSAXParserFactory.setFeature` so arbitrary parser features are
  retained and applied to parsers created by `newSAXParser`. Previously,
  settings such as external-entity controls and
  `disallow-doctype-decl` could be validated against a temporary parser but
  silently omitted from the parser returned by the factory.

- **Truncated-input denial-of-service fix.** A document ending midway through
  a multibyte character no longer causes blocking parse methods to loop
  forever at 100% CPU. End of input now produces a fatal malformed-input
  error.

- **Stronger external-access checks.** When external entities are explicitly
  enabled, the JAXP `accessExternalDTD` allow-list is enforced against the
  resolved absolute system identifier rather than only the raw relative
  string. An empty allow-list remains a hard deny.

- **Secure XSLT secondary parsing.** Secondary document loading and native
  stylesheet/package compilation now go through a locked-down Gonzalez
  parser with secure-processing enabled and external entities disabled,
  instead of relying on whatever platform SAX parser JAXP discovery finds.

- **Security controls preserved by the new engine.** The Scanner pipeline
  enforces the secure defaults (external general and parameter entities
  disabled), JAXP `accessExternalDTD` protocol allow-list, secure-processing
  mode, configurable entity-expansion limit (64,000 expansions by default;
  zero for unlimited), recursive entity detection and the new DOCTYPE
  rejection feature.

### Fixed

- Corrected parser behavior across buffer boundaries, including XML and text
  declarations, UTF-8/UTF-16 decoding, comments, CDATA sections, processing
  instructions, entity references, DTD declarations and location tracking.

- Completed Scanner support for internal and external subsets, parameter
  entities, attribute defaults and normalization, standalone-document rules,
  content-model validation, notation/unparsed-entity reporting and
  `EntityResolver2`.

- Correctly rejects duplicate attributes by both qualified name and expanded
  name, illegal XML 1.0/1.1 literal characters, malformed character
  references and `]]>` in ordinary content.

- Fixed XSLT `PackageResolver` behavior after moving stylesheet and package
  compilation to the native pipeline.

### Compatibility

- The combined `gonzalez-1.2.0.jar` remains available for existing
  deployments. A public bytecode API comparison against 1.1 found no removed
  public classes, constructors or methods; 1.2.0 adds APIs only. Upgrade
  compatibility is still conditional on the behavioral notes below (and the
  security/factory fixes above).

- The implementation-specific SAX property
  `http://www.nongnu.org/gonzalez/properties/dtd-parser` is no longer
  recognized because the old `DTDParser` implementation was removed by the
  Scanner rewrite. Standard SAX/JAXP parsing APIs are unaffected.

- `http://xml.org/sax/features/xmlns-uris` is now settable (default remains
  `false`). In 1.1, `setFeature` rejected attempts to change it.

- Changing `namespaces` or `validation` after parsing has started is now
  rejected. Feature changes that affect the scanner snapshot must be made
  before the parse begins.

- `Parser.parse(InputSource)` now accepts a system-ID-only source (resolved
  via the configured `EntityResolver`, falling back to
  `DefaultEntityResolver`). Version 1.1 required a byte stream. Character
  streams remain unsupported.

## [1.1.1] - 2026-03-23

### Added

- Split the distribution into a zero-dependency parser/writer core jar,
  an XSLT/schema jar, and the combined jar retained for compatibility. Added
  corresponding JPMS module descriptors and TransformerFactory service
  registration.

- Added UTF-32 byte-order-mark detection and decoding.

### Fixed

- Corrected the release version after 1.1.1 was initially mis-tagged as
  `v1.2.1`.

## [1.1] - 2026-03-13

### Added

- **XMLWriter DOCTYPE output.** XMLWriter can now serialize complete DOCTYPE
  declarations via new `writeStartDTD`, `writeEndDTD`, `writeElementDecl`,
  `writeAttributeDecl`, `writeInternalEntityDecl`, `writeExternalEntityDecl`,
  `writeNotationDecl`, and `writeUnparsedEntityDecl` methods.

- **XMLWriter standalone conversion mode.** When `setStandalone(true)` is called,
  the writer omits external DOCTYPE identifiers and inlines all DTD declarations
  (both internal and external subset) into the internal subset, producing a
  self-contained document.

- **XMLWriter CDATA streaming.** New `writeStartCDATA`/`writeEndCDATA` methods
  for streaming CDATA content, alongside the existing `writeCData` convenience
  method for writing complete CDATA sections.

- **XMLWriter configuration setters.** `IndentConfig`, `Charset`, XML 1.1 mode,
  and standalone mode can now also be configured via setter methods
  (`setIndentConfig`, `setCharset`, `setXml11`, `setStandalone`).

- **XMLWriter raw output.** New `writeRaw` method for writing unescaped content,
  supporting XSLT's disable-output-escaping feature.

- **Parser DeclHandler support.** The parser now fires `DeclHandler` events
  (`elementDecl`, `attributeDecl`, `internalEntityDecl`, `externalEntityDecl`)
  when DTD declarations are parsed. Set via the standard SAX property
  `http://xml.org/sax/properties/declaration-handler`.

- **Parser startEntity/endEntity events.** The parser now fires
  `LexicalHandler.startEntity`/`endEntity` events for general entity expansion
  in document content and for the external DTD subset boundary (`[dtd]`).

- **XSLT 1.0/2.0/3.0 transformer.** `GonzalezTransformer` and `GonzalezTemplates`
  provide a JAXP-compliant XSLT processor with streaming SAX-based transformation,
  XPath 3.1 expression evaluation, and forward-compatible processing.

- **XSD schema validation.** `XSDValidator` provides XML Schema validation
  integrated with the Gonzalez parsing pipeline.

## [1.0] - 2026-01-10

### Added

- **XMLWriter**: New streaming XML serializer with NIO-first design. Features include:
  - Writes to `WritableByteChannel` with automatic buffering (UTF-8 output)
  - Full namespace support with prefix/URI tracking
  - Empty element optimization (emits `<foo/>` instead of `<foo></foo>`)
  - Pretty-print indentation via `IndentConfig`
  - Proper character escaping for content and attributes
  - CDATA sections, comments, processing instructions, entity references

- **IndentConfig**: Configuration class for XML/JSON output indentation (space or tab
  based, configurable count per level).

- **JPMS module support**: Gonzalez is now a proper Java module (`org.bluezoo.gonzalez`)
  with `module-info.java`. The jar is compiled with `-release 8` for Java 8 runtime
  compatibility while including `module-info.class` for Java 9+ module system support.

- **JAXP SAXParserFactory integration**: Gonzalez can now be used via the standard
  JAXP discovery mechanism. New classes:
  - `GonzalezSAXParserFactory` - Factory for creating Gonzalez SAX parsers
  - `GonzalezSAXParser` - JAXP SAXParser wrapper around the Gonzalez Parser

- **Service provider registration**: The jar includes
  `META-INF/services/javax.xml.parsers.SAXParserFactory` for classpath-based
  discovery and a `provides` directive in `module-info.java` for JPMS discovery.

### Changed

- Build system updated to compile main sources with `-release 8` and
  `module-info.java` with `-release 9` for multi-release compatibility.

- Maven POM restructured for distribution only; all building delegated to Ant.

## [0.2.0] - 2025-12-01

### Changed

- **Breaking:** Replaced internal composite byte buffer with direct ByteBuffer usage
  in `receive()`. Callers must now perform proper buffer management (flip/compact)
  as is standard practice in NIO code. This significantly improves performance by
  eliminating unnecessary buffer copying.

### Removed

- Composite buffer abstraction that previously handled faulty caller buffer management.

## [0.1.0] - 2025-10-01

### Added

- Initial release of Gonzalez streaming XML parser
- Non-blocking, data-driven architecture using push model
- SAX2-compatible event generation via ContentHandler
- Full XML 1.0 and XML 1.1 support
- DTD parsing with internal and external subsets
- Namespace support
- DTD validation
- Automatic charset detection (BOM and XML declaration)
- Line-end normalization per XML specification
- ByteBuffer-based I/O for NIO integration
- Parser reuse via reset()
- Locator2 support for position reporting
- 100% W3C XML Conformance Test Suite compliance

