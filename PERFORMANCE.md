# Performance and Scalability

Part of the [Gonzalez documentation](README.md).

The XML parser was compared with the SAX parser bundled with JDK 25 (Xerces)
and [aalto-xml](https://github.com/FasterXML/aalto-xml) 1.4.0. All paths ran
namespace-aware. The `XMLHandler` column is the public native path: an
`XMLParser` with an empty `XMLHandler`. It does byte decoding, XML grammar
processing, DTD work and namespace declaration handling, but no SAX. Gonzalez
SAX adds name resolution, `Attributes2` assembly and standard SAX callback
adaptation. Unlike SAX, the native SPI is Gonzalez-specific and may evolve
between releases.

Aalto's lower-level StAX cursor is included as an additional reference point,
but is not directly equivalent to SAX callback delivery.

The figures below are median throughput across five fresh JVM runs, each with
one corpus and one parser, 800 warm-up parses and then 500 measured parses.
The original five documents are in `benchmark/resources`; `BenchmarkCorpora`
deterministically generates three additional substantial documents:

- the same 4,000-article Japanese document encoded as BOM-marked UTF-16LE and
  EUC-JP, isolating the two decoder paths as far as practical;
- a 1,500-article XHTML-shaped document with a self-contained internal subset
  declaring 33 elements, common and element-specific attributes, content
  models, and character entities. It has no public or system identifier, so
  no external subset is fetched.

**Aalto is not a validating or DTD-processing parser.** Its SAX factory rejects
validating mode, and its own `Attributes2` implementation states that DTD
processing is absent. For the XHTML corpus, Aalto recognizes the DOCTYPE and
emits `startDTD`/`endDTD`, but skips the internal declarations: it does not
apply declared attribute types or defaults and cannot expand the declared
general entities. Gonzalez and JDK Xerces parse and apply those declarations.
The XHTML row is therefore included to expose this capability and cost
difference, not as a like-for-like throughput comparison.

| Corpus | Size | Gonzalez XMLHandler | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Aalto StAX cursor |
|--------|-----:|--------------------:|-------------:|----------------:|----------:|------------------:|
| Plain UTF-8 | 1.02 MiB | **1,045 MB/s** | 857 MB/s | 548 MB/s | 1,187 MB/s | 1,286 MB/s |
| Attribute-heavy UTF-8 | 1.28 MiB | **739 MB/s** | 540 MB/s | 252 MB/s | 941 MB/s | 929 MB/s |
| Whitespace-heavy UTF-8 | 0.94 MiB | **1,389 MB/s** | 1,206 MB/s | 696 MB/s | 1,334 MB/s | 1,585 MB/s |
| Markup-heavy UTF-8 | 0.96 MiB | **1,234 MB/s** | 1,082 MB/s | 540 MB/s | 1,461 MB/s | 1,732 MB/s |
| Multibyte UTF-8 | 0.49 MiB | **622 MB/s** | 516 MB/s | 332 MB/s | 741 MB/s | 784 MB/s |
| Japanese UTF-16LE | 1.50 MiB | **792 MB/s** | 544 MB/s | 832 MB/s | 917 MB/s | 930 MB/s |
| Japanese EUC-JP | 1.28 MiB | **511 MB/s** | 498 MB/s | 446 MB/s | 643 MB/s | 643 MB/s |
| XHTML internal DTD (not equivalent; see above) | 1.19 MiB | **350 MB/s** | 287 MB/s | 149 MB/s | *887 MB/s†* | *904 MB/s†* |

† Aalto's figures are non-validating: it skips the internal DTD declarations.

Gonzalez SAX is faster than the JDK's Xerces on every corpus except UTF-16LE,
where Xerces is about 53% faster, and the two are close on EUC-JP (Gonzalez
about 12% faster). Aalto SAX is between about 1.1 and 1.7 times as fast as
Gonzalez SAX. Throughput is measured from encoded byte size, so UTF-16's MB/s
also reflects that it uses more bytes for the same logical Japanese document.

**What the handler does matters.** With an empty handler, Aalto's SAX wrapper
does less work than Gonzalez, because it decodes an attribute value only when
the handler asks for it, whereas Gonzalez builds each value as it scans. A
handler that reads every attribute and all the text is closer to real use, and
narrows the gap:

| Corpus | Gonzalez SAX | Aalto SAX |
|--------|-------------:|----------:|
| Plain UTF-8 | 846 MB/s | 1,109 MB/s |
| Attribute-heavy UTF-8 | 528 MB/s | 705 MB/s |
| Whitespace-heavy UTF-8 | 1,187 MB/s | 1,309 MB/s |
| Markup-heavy UTF-8 | 1,070 MB/s | 1,404 MB/s |
| Multibyte UTF-8 | 505 MB/s | 567 MB/s |
| Japanese UTF-16LE | 545 MB/s | 754 MB/s |
| Japanese EUC-JP | 492 MB/s | 561 MB/s |
| XHTML internal DTD (not equivalent; see above) | 283 MB/s | *764 MB/s†* |

Aalto remains faster, by between about 1.1 and 1.4 times, most on UTF-16 and
on attribute-heavy, plain and markup-heavy UTF-8.

The DTD behavior was verified from SAX events using
`benchmark/external-compare/DtdProbe.java`: Gonzalez and JDK Xerces report
`id` attributes with declared type `ID` and inject the DTD-defaulted,
non-specified `type="text"` attribute on `<input>`. Aalto reports `id` as
`CDATA` and omits the defaulted attribute. Its higher number on this row must
not be interpreted as faster DTD processing, because it does not perform that
processing.

These are microbenchmarks, not application-level latency guarantees. Results
depend on the JDK, hardware, handlers and document shape. Gonzalez's primary
architectural advantages remain:

- **Non-blocking document input**: `receive(ByteBuffer)` processes available
  network data without dedicating a thread to wait for the rest of the
  document. External entity resolution may still block when explicitly
  enabled.
- **Streaming memory use**: main-document bytes and character data are
  processed in reusable chunks rather than loading the whole document. The
  next section measures it.

## What the throughput buys: memory

Gonzalez separates scanning from SAX. The scanner reports every construct in
pieces (the `XMLHandler` events, each with an `end` flag), and the SAX adapter
is a layer on top of that. The layer costs throughput, which is most of the gap
between the `XMLHandler` and SAX columns above. In exchange, for a document with
no DTD the parser itself holds no more than a bounded amount of it, however
large any one construct is. The other parsers compared do not manage that for
every kind of construct, and the throughput comparison does not show it.

`MemoryProbe` makes up a document with one very large construct as the parser
reads it, so that nothing about the harness is large, and parses it in a JVM
with a 32 MB heap. The table says whether each parser gets to the end of a
256 MB construct:

| Construct | Gonzalez `XMLHandler` | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Aalto StAX cursor |
|-----------|:---------------------:|:------------:|:--------------:|:---------:|:-----------------:|
| Text node | ok | ok | ok | out of memory | out of memory |
| CDATA section | ok | ok | out of memory | out of memory | out of memory |
| Attribute value | ok | out of memory | out of memory | out of memory | out of memory |
| Comment | ok | out of memory | out of memory | out of memory | out of memory |
| Many small elements (control) | ok | ok | ok | ok | ok |

This is not only a matter of extreme sizes: a 16 MB construct in the same heap
gives the same table, and at 4 MB every parser copes. Ordinary documents, made
of many small constructs, are streamed by all of them; the difference is in what
happens when a single text node, attribute value or comment is large.

Two things in the table are limits of the SAX interface and not of Gonzalez.
SAX hands a handler the whole value of an attribute as a `String` and the whole
text of a comment as one array, so the adapter has to assemble them, and
Gonzalez SAX runs out of memory on those just as the others do. It delivers
text and CDATA in pieces, as SAX allows. The `XMLHandler` interface has no such
requirement, which is why a native consumer, such as `XMLWriterHandler`
copying a document, can process any of these in constant memory. The rest of
what Gonzalez must remember is bounded by the structural limits described
in [Secure Configuration and Limits](SECURE-CONFIGURATION.md#structural-limits).

A document with a single construct that large is unusual, and this measures what
a parser is able to do, not how typical documents behave. It is the reason for
the layering all the same, and is what the SAX columns' lower throughput pays
for. To run it:

```sh
benchmark/external-compare/run-memory.sh [megabytes] [heap]
```

with the same `AALTO_JAR` and `STAX2_JAR` variables; it defaults to 256 MB and a
32 MB heap.

To reproduce the comparison, set the external dependency jar locations and
run the script:

```sh
export AALTO_JAR=/path/to/aalto-xml.jar
export STAX2_JAR=/path/to/stax2-api.jar
benchmark/external-compare/run-isolated.sh
```

`run-isolated.sh` runs each corpus and parser in a fresh JVM, five times by
default, and reports the median; its arguments are the number of runs, and
quoted lists of corpora and modes. Set `GONZALEZ_CLASSES` to compare another
build of Gonzalez. Besides the empty handlers used for the first table it has
`sax-touch` and `aalto-sax-touch` modes, which use a handler that reads every
attribute and all text, as in the second. `benchmark/external-compare/run.sh`
is a quicker single-JVM comparison that runs every parser in turn.

The scripts exit with an error if either variable is unset or does not name a
file. `benchmark/external-compare/run-dtd-probe.sh` uses the same variables.
