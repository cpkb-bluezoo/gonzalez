# Changelog

All notable changes to Gonzalez will be documented in this file.

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

