# Changelog

All notable changes to Gonzalez will be documented in this file.

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

