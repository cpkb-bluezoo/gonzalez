# Changelog

All notable changes to Gonzalez will be documented in this file.

## [Unreleased]

### Added

- **`ant benchmark-compare`, and a workflow that runs it.** The target builds
  Gonzalez, fetches aalto-xml and the French and German novels (`ant
  download-comparison-deps` and `ant download-corpora`, each download checked
  against a SHA-256), runs the comparison with the JDK's parser and aalto-xml,
  and prints the tables of PERFORMANCE.md, which it writes to
  `build/performance.md`. The "Performance comparison" workflow runs it on a
  branch, tag or commit (`main` unless another is given) and shows the tables
  on the summary page of the run. The figures in PERFORMANCE.md were remade
  with it.
  `ant benchmark-memory` runs the memory comparison of PERFORMANCE.md the
  same way, and `ant benchmark-isolated` and `ant benchmark-dtd-probe` the
  other scripts of `benchmark/external-compare`, so that every benchmark task
  is run from `build.xml`.

- **The sizes of the parser's own buffers are settings.** `XMLParser` has
  `setDecodeBufferSize`, `setScanBufferSize`, `setNameTableSize` and
  `setInputBufferSize` (with getters), and `Parser` the properties
  `http://www.nongnu.org/gonzalez/properties/decode-buffer-size`,
  `scan-buffer-size`, `name-table-size` and `input-buffer-size`. The defaults
  are the sizes already used, except that `parse(ReadableByteChannel)` reads
  into 32 KB where it read into 8 KB, and that the decode buffer is 16,384
  characters (32 KB) where it was 32,768 (64 KB), for the reason given under
  Changed. SECURE-CONFIGURATION.md describes them.

- **A memory comparison.** `benchmark/external-compare/run-memory.sh` parses a
  document with one very large text node, CDATA section, attribute value or
  comment in a small heap with Gonzalez, JDK Xerces and Aalto, and says which
  get to the end of it. PERFORMANCE.md shows the result: through the native
  `XMLHandler` interface Gonzalez does, for all four.

- **Documentation split out of the README.** Usage, secure configuration and
  limits, and performance each have a document of their own (`USAGE.md`,
  `SECURE-CONFIGURATION.md` and `PERFORMANCE.md`), and the README summarises
  them and links to them.

### Changed

- **A smaller decode buffer by default.** Input is decoded and scanned in
  pieces of the size of the decode buffer, which was 32,768 characters (64 KB).
  At 16,384 (32 KB) text that is mostly ASCII is parsed 3 to 5% faster, on the
  native path and through SAX, probably because each piece is then scanned from
  the processor's first-level cache; every other corpus measured is unchanged.
  It also takes 32 KB less for each parser. A smaller size is not better: at
  8,192 a document with a DTD of some kilobytes was 9% slower, as a declaration
  has to be tried again for each piece that does not end it. The size remains a
  setting (`setDecodeBufferSize`), and a smaller one still reports exactly the
  same.

- **EUC-JP is decoded from tables.** The JDK's EUC-JP decoder looks each
  character up through several layers of calls, and on a document of Japanese
  text that was almost two thirds of the time of the whole parse. `EucJpDecoder`
  looks the common sequences (JIS X 0208, JIS X 0212 and half-width katakana)
  up directly. The tables are made once, when EUC-JP is first used, by asking
  the JDK's own decoder what each sequence is, so the characters are always
  those of the JDK in use. Anything that is not in them, an unassigned code, a
  sequence cut short or the end of the output buffer, is given back to the
  JDK's decoder, so every error and every wait for more input is as it was. The
  byte-level scan for carriage returns is now used for EUC-JP too. On the
  1.28 MB Japanese document, the native path rose from 510 to 902 MB/s and SAX
  from 491 to 765 MB/s, which is now faster than Aalto's 639. Other encodings
  are unchanged.

- **UTF-8 with non-ASCII characters is decoded faster.** The JDK's UTF-8 decoder
  widens ASCII with a routine of the JVM's that is four times as fast as any
  loop in Java, but it calls it once for each piece of input, and decodes
  everything after the first byte that is not ASCII one character at a time. A
  document of nearly all ASCII with a few accents, dashes or quotation marks
  was parsed 40% slower than one with none. The new `Utf8Decoder` decodes
  non-ASCII input at a speed that does not depend on how it is mixed, and
  `ExternalEntityDecoder` uses the JDK's decoder up to the first non-ASCII
  byte, which it finds in the scan for carriage returns that was already made,
  and `Utf8Decoder` from there. `Utf8Decoder` follows Table 3-7 of the Unicode
  Standard and reports what the JDK's decoder reports, including how much of a
  malformed sequence is malformed and how soon. A document with an accented
  letter in every 300 bytes goes from 626 to 882 MB/s on the native path and
  from 556 to 740 on SAX. A document of dense multibyte text gains 5 to 7%,
  and one that is all ASCII is unchanged.

- **UTF-16 is decoded in bulk.** The JDK's UTF-16 decoder takes two bytes at a
  time through the buffer accessors, and on a document of Japanese text that
  was about half the time of the whole parse. A document of known byte order
  (from its byte order mark or its declared encoding of `UTF-16LE` or
  `UTF-16BE`) is now decoded by `Utf16Decoder`, which copies the characters out
  with one call that swaps the bytes and then checks that every surrogate has
  its partner. It reports what the JDK's decoder reports, for the same input:
  the same characters, the same refusal of an unpaired surrogate and the same
  length of malformed input. On the 1.5 MB Japanese document, the native path
  rose from 796 to 1,418 MB/s and SAX from 540 to 1,137 MB/s, which is now
  faster than Xerces and Aalto. Other encodings are unchanged.

- **A parser used for many documents allocates its buffers once.** The decoder's
  character buffer (64 KB), the scan buffer, the table of interned names and
  the stack of element names, which were allocated afresh for every document,
  are kept by `XMLParser.reset()` (and so by `Parser.reset()`). Nothing about a
  document is kept with them: each document still gets a new scanner, and the
  buffers are written before they are read. A reused parser parses `<a/>`
  through SAX in 0.6 microseconds instead of 3.8, a 1.3 KB document in 2.6
  instead of 6.1, and a 4.4 KB one in 5.3 instead of 8.7. A parser used for a
  single document allocates the same as before, and large documents are
  parsed as fast as before.

- **A better spread of names in the name table.** The table was indexed by
  little more than the last character of each group of four, so that names
  ending alike filled the probe limit and went uncached, though the table was
  nearly empty. Names are now mixed before they are indexed, and eight probes
  are allowed in place of four. A document with a few hundred distinct names
  keeps nearly all of them; with 240 distinct names, 231 of 721 occurrences
  were uncached before and none are now.

- **Faster SAX parsing.** `SAXAdapter` keeps its element stack and attribute
  list in arrays instead of `ArrayList`s, assembles each attribute in place
  instead of copying it through intermediate fields, takes each attribute
  value from the scanner as a range of its buffer instead of through a
  `CharBuffer` made for the purpose (a route inside the package; any other
  handler still receives a `CharBuffer`), and remembers what an element name
  resolves to for as long as the namespace bindings stay as they are. With a
  handler that reads every attribute, SAX throughput rose by 7 to 34% across
  the benchmark corpora (the most on attribute-heavy and white-space-dense
  data, the least on markup-heavy data and the XHTML document), measured
  against 1.3.0 as the median of five fresh JVMs.

- **Faster scanning of repetitive documents.** The scanner tries the name that
  followed the previous attribute name (or the element name, for the first
  attribute) last time, and the name that followed the previous start tag,
  before scanning and interning a name. A name that matches and is followed by
  something that is not a name character is accepted as it stands; anything
  else takes the existing path, so what is reported does not change. Attribute-
  heavy input is about 12% faster on the native path; a document whose
  attributes come in a different order every time gains nothing and may be
  about 2 to 3% slower.
- **Comments, CDATA sections and processing instructions are scanned with one
  table read for each character.** The scan for the end of each did two tests
  for every character, one for the character that begins the terminator and one
  for whether the character is legal, where text and attribute values did one.
  The two are now one, from a table of flags. On a document that is mostly
  comments, CDATA sections and processing instructions this is about 13%
  faster on the native path and 12% through SAX; other documents are not
  affected. What is accepted and refused is as it was.
- **Fewer references are stored for each attribute in the SAX layer.** Under
  G1, the JDK's default collector, a reference stored in an object that has
  been around for a while costs a write barrier, and each attribute made about
  nine such stores, most of them of what the slot already held. An attribute's
  name and type are now kept only for a namespace declaration, which is the only
  thing that reads them back, the attribute being assembled is found by its
  index and not by a field, and a pooled slot or the record of names seen is
  written only when it differs from what it holds. Attribute-heavy documents
  are about 6% faster through SAX under G1, and are unchanged on the native
  path and under the other collectors.
- **The performance comparison puts the native interfaces first.** The table
  of PERFORMANCE.md now compares Gonzalez's `XMLHandler` with Aalto's StAX
  cursor, each with a consumer that reads every character of text, comments,
  processing instruction data and attribute values. The StAX figures there were
  of a loop that only stepped the cursor, which reads nothing and so does less
  work than a handler that is given decoded characters, and they are no longer
  shown. The SAX table is of handlers that read everything, includes Xerces, and
  leaves Aalto out for the document with a DTD, which Aalto does not process.
  There are new documents: French-like and German-like accents, English with an
  odd foreign word, English with emoji, and MathML with the namespace declared
  once on the root and redeclared on each equation, generated by the harness,
  and a French and a German novel that `ant download-corpora` fetches (the
  `benchmark` target needs it) into `benchmark/corpora`, each from a fixed
  commit of its ELTeC collection and checked against its SHA-256, and that
  the harness and `performance-tables.sh` then use.
  `IsolatedAB` has the new modes `xmlhandler-sum`,
  `aalto-stax-sum` and `jdk-touch`, and `performance-tables.sh` prints the
  tables. The quick single-JVM comparison, `ExternalCompare` and `run.sh`, is
  removed: it had empty handlers and an Aalto cursor that was only stepped, so
  its figures were not comparable with the tables, and `ant benchmark-isolated`
  does the same job with the same consumers.

- **The UTF-8 decoder's speed no longer depends on how the JIT happened to
  compile it.** The loop that copies a run of ASCII was part of the large
  decoding method, and in some JVMs it was compiled so that the decoder took up
  to twice as long on the same input, for text with a few non-ASCII characters
  in it: about 40% of fresh JVMs for some patterns. It is now a method of its
  own, written as a loop with one counter, and was the same speed in every JVM
  tried. English with emoji through SAX is about 23% faster (it was in the slow
  state every time), and text with an accent on every e is 7 to 10% faster;
  plain text is not affected, and the French and multibyte documents measured
  are within 1 to 3% of what they were.

- **Namespace scopes are kept in arrays and not hash maps.** Each element's
  scope held its declarations in a `HashMap`, which had to be iterated to
  report the prefixes the element declares (on its start and again on its end)
  and cleared to be reused, and the scopes were an `ArrayList`. They are now two
  small arrays in a scope, in the order declared, and the scopes themselves an
  array that is reused, so an element that declares nothing, which is nearly all
  of them, costs a store, and one that does costs no iterator or entry. A
  document in which an element declares a namespace often is about 7 to 12%
  faster through SAX, and plain text about 4%. `NamespaceScopeTracker` has
  `getCurrentScopeDeclarationCount`, `getCurrentScopePrefix` and
  `getCurrentScopeURI` for this, and `getCurrentScopeDeclarations` remains.
  The prefix mappings of SAX are now reported in the order the prefixes were
  declared, and ended in the reverse of it; they were in the order of a hash
  map.

- **The SAX adapter takes what names mean from the namespace filter.** The
  filter that every namespace-aware parse has in front of its handler already
  keeps the declarations in scope and checks every name, and the SAX adapter
  kept the same bindings a second time to work out the namespace of each
  element and prefixed attribute, once at the start tag and again, from a cache
  of names, at the end. The filter now keeps the default namespace as well,
  works out what a name stands for (a name with no prefix is in the default
  namespace, which costs no lookup), and tells the adapter when the start tag
  ends, as well as what is declared when the adapter reports the prefix
  mappings; the adapter keeps no namespace state of its own, and a
  namespace-aware adapter must be behind a filter (the package-private
  `SAXAdapter` is not API). Through SAX, a document in which the namespace is
  declared once is 9% faster, one in which it is declared again and again 18%
  faster, plain text 8%, English with emoji 7%, and the others by 2 to 4%; the
  native interface is within 1% of what it was.
  `NamespaceScopeTracker`, `QName` and the other classes the adapter used
  remain, unused by the parser.

- **A whole attribute, and text, are reported to the SAX adapter without an
  object each.** The scanner reported every attribute as two events, its name
  and then its value, which the filter and the adapter each had to pass on and
  remember between, and every run of text wrapped in a `CharBuffer` that the
  adapter at once unwrapped. When the value of an attribute is entirely in the
  buffer and needs nothing done to it (nearly all of them) it is now one call
  with the range of the buffer, and text is passed on as a range too. This is
  only done where the handler at the end of the chain is the SAX adapter: a
  native `XMLHandler` is given the same events, with the same objects, as it
  was. Through SAX, the attribute-heavy document is 11% faster, and documents
  with a lot of text 1 to 5% faster.

### Fixed

- **An `xsl:fork` could report a cycle between accumulators that was not
  there.** The branches of a fork run at the same time, each with its own copy
  of the accumulators, but the copies shared the record of which accumulators
  were being worked out, which is what the check for an accumulator that
  depends on itself (XTDE3400) looks at. A branch that read an accumulator
  while another branch was in the middle of that accumulator's rule was
  stopped with a false "cyclic dependency" error. The W3C test `si-fork-816`
  failed in this way in about half of its runs. Each copy now has its own
  record.

## [1.3.0] - 2026-11-15

### Changed

- **Faster XSLT transformation.** Template rules are indexed by the name
  they match, and by an `@attr = 'literal'` predicate, so that a stylesheet
  with many rules no longer tests each in turn. Absolute and parent-relative
  paths that do not depend on the context are evaluated once and reused,
  `child::name` steps and simple pattern predicates take shorter routes, and
  a node gets one context where it got several, with no variable scope
  pushed where nothing declares a variable. `XSLT.md` has the comparison with other
  processors.

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

- `SECURITY.md`, and scripts for publishing the three jars to Maven Central.

- **`XMLParser`, the native parser.** A parser without SAX: one
  `XMLHandler`, typed setters in place of feature and property URIs,
  `receive`/`close`/`reset`, and blocking `parse` methods for an
  `InputStream` or a `ReadableByteChannel` that run the same receive loop.

- **Line and column numbers.** `XMLParser.setLineNumbers(true)`, or the SAX
  feature `Parser.LINE_NUMBERS_FEATURE`, makes the locator report them where
  it reported -1. They are off by default, since counting takes a pass over
  the input; nothing changes for a parser that does not ask. The XSLT
  compiler asks, so `$err:line-number` in `xsl:catch` and the line in a
  static error are those of the stylesheet instruction.

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

- A validating parser did not report an attribute that was not declared when
  its element had no `ATTLIST` at all, so `xml:space` and `xml:lang`, which
  need declaring like any other, passed. It also accepted a CDATA section,
  even an empty one, in element-only or `EMPTY` content, though it is not
  white space (the `S` production). Both are now validity errors.

- A text declaration without an `encoding` is now a fatal error. The text
  declaration that may begin an external entity or an external DTD subset
  must give one (XML 4.3.1), though the XML declaration of the document need
  not, and a declaration with only a `version` was accepted.

- An initial mode that is the stylesheet's `default-mode` is no longer
  rejected with `XTDE0045` when nothing else mentions it. The attribute
  names the mode, so it is present, holding only the built-in rules.

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

- No public class, constructor or method has been removed. The
  `XMLWriter` methods that take a whole attribute value, comment or
  processing instruction data are deprecated in favour of the ones that
  take it in pieces, and still work.

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

- A validating parser now reports more validity errors, through
  `ErrorHandler.error` and so not fatally: an undeclared attribute on an
  element with no `ATTLIST`, and a CDATA section in element-only or `EMPTY`
  content. A document that was reported valid and has either is now reported
  invalid. Without validation nothing changes.

- The W3C conformance run now includes the Sun suites (`sun/valid`,
  `sun/invalid` and `sun/not-wf`, 158 tests), which were never loaded, since
  their catalogues are fragments without a root element.

- An external entity or DTD subset that begins `<?xml version="1.0"?>`,
  with no `encoding`, is now rejected as XML requires; add the `encoding`
  to its text declaration, or remove the declaration, to accept it.

- A relative system identifier in an entity declaration is now resolved
  against the file that holds the declaration, as XML requires, so an entity
  declared in an external DTD subset in another directory is found where it
  was not before. The locator's system identifier is that of an external
  entity while its text is read, and the document's again after it.

- XSLT: a pattern predicate that uses `position()` or `last()` inside an
  `if`, `let`, `for` or similar expression now sees the real position and
  size, so a stylesheet that relied on matching every node there matches
  fewer. `xsl:apply-templates` on a map, an array or an atomic value now
  binds a template's declared parameters, and a `default-mode` is accepted as
  an initial mode.

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

