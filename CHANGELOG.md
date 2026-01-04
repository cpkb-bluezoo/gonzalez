# Changelog

All notable changes to Gonzalez will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

