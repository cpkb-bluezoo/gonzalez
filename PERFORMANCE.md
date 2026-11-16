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
deterministically generates three additional substantial documents, and the
harness makes a ninth from the plain one by putting an accented letter (two
bytes of UTF-8) after one space in every 300 bytes or so, which is what text
that is nearly all ASCII looks like once it has a few accents, dashes or
quotation marks in it:

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
| Multibyte UTF-8 | 0.49 MiB | **653 MB/s** | 531 MB/s | 334 MB/s | 740 MB/s | 792 MB/s |
| Mostly ASCII with accents | 1.02 MiB | **882 MB/s** | 743 MB/s | 450 MB/s | 1,171 MB/s | 1,289 MB/s |
| Japanese UTF-16LE | 1.50 MiB | **1,418 MB/s** | 1,147 MB/s | 828 MB/s | 918 MB/s | 912 MB/s |
| Japanese EUC-JP | 1.28 MiB | **511 MB/s** | 498 MB/s | 446 MB/s | 643 MB/s | 643 MB/s |
| XHTML internal DTD (not equivalent; see above) | 1.19 MiB | **350 MB/s** | 287 MB/s | 149 MB/s | *887 MB/s†* | *904 MB/s†* |

† Aalto's figures are non-validating: it skips the internal DTD declarations.

Gonzalez SAX is faster than the JDK's Xerces on every corpus, and the two are
close on EUC-JP (Gonzalez about 12% faster). Aalto SAX is between about 1.1 and
1.7 times as fast as Gonzalez SAX on the UTF-8 and EUC-JP corpora; on UTF-16LE
Gonzalez SAX is about 25% faster than Aalto. Throughput is measured from
encoded byte size, so UTF-16's MB/s also reflects that it uses more bytes for
the same logical Japanese document. UTF-16 is decoded by Gonzalez's own
decoder, which copies the characters out of the input in bulk, where the JDK's
takes about half the time of the whole parse to do the same.

UTF-8 is decoded by the JDK's decoder up to the first byte that is not ASCII,
and by Gonzalez's own from there. The JDK's is four times as fast as anything
written in Java on ASCII, which it widens with a routine of the JVM's that
application code cannot call, but it makes that call once for each piece of
input it is given, and from the first non-ASCII byte decodes the rest of the
piece a character at a time. One accented letter in every 300 bytes made the
parse of the plain document 40% slower for that reason.

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
| Multibyte UTF-8 | 539 MB/s | 547 MB/s |
| Mostly ASCII with accents | 740 MB/s | 1,107 MB/s |
| Japanese UTF-16LE | 1,137 MB/s | 772 MB/s |
| Japanese EUC-JP | 492 MB/s | 561 MB/s |
| XHTML internal DTD (not equivalent; see above) | 283 MB/s | *764 MB/s†* |

Aalto remains faster on the UTF-8 and EUC-JP corpora, by between about 1.0 and
1.5 times, most on the accented document and on attribute-heavy, plain and
markup-heavy UTF-8, and not at all on multibyte UTF-8. On UTF-16LE Gonzalez is
about 1.5 times as fast.

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

## Small documents

The tables above are for documents of about a megabyte, where the time goes on
scanning. A document of a few kilobytes is parsed in a few microseconds, and
what dominates is what a parser does before it reads a byte. The figures below
are microseconds per parse, the median of five fresh JVMs, each parsing the
one document 200,000 times after 100,000 warm-up parses, on one thread. The SAX
columns use the handler that reads every attribute and all the text, as in the
second table above; the `XMLHandler` columns are the native path with an empty
handler, and so do less than the SAX ones.

| Document | Size | `XMLHandler`, new parser | `XMLHandler`, reused | SAX, new parser | SAX, reused | Aalto SAX |
|----------|-----:|-------------------------:|---------------------:|----------------:|------------:|----------:|
| `<a/>` | 4 bytes | 2.9 | 0.4 | 3.8 | 0.5 | 0.1 |
| `small.xml` | 1.3 KB | 4.7 | 2.1 | 6.2 | 2.8 | 1.6 |
| `attrs-small.xml` | 3.2 KB | 7.9 | 5.2 | 10.1 | 7.0 | 4.8 |
| `books.xml` | 4.3 KB | 7.2 | 4.5 | 9.1 | 5.5 | 4.7 |

A new parser is one made for the document, as `SAXParserFactory.newSAXParser()`
or `new XMLParser()` gives you. A reused parser is one that is kept and
`reset()` between documents. Aalto's figure is the same for both, because
aalto-xml recycles its buffers in a `ThreadLocal`, and so a parser made for
each document finds them already allocated.

Gonzalez keeps nothing in a `ThreadLocal`. The memory a parser works in is
allocated when it first parses a document and is the size you set (see
[Buffer Sizes](SECURE-CONFIGURATION.md#buffer-sizes)), so a new parser
allocates about 100 KB before it reads anything. The difference between the new
and reused columns, between 2.5 and 3.5 microseconds whatever the document, is
what a new parser spends allocating that memory and setting itself up, and it is most of
the 2.9 microseconds it takes to parse `<a/>`. `reset()` keeps the buffers,
which is what the reused columns show. An application that parses many small
documents should keep a parser for each thread that parses and reset it between
documents, and should set the buffer sizes to what its documents need if memory
is dear.

With that done, SAX through a reused Gonzalez parser is between 1.2 and 1.8
times the time Aalto takes for the documents of a kilobyte or more, and closer
the larger the document. For a parser made for each document the factor is
between 1.9 and 3.9. A document of a few bytes is not a measure of anything
useful: both are a fraction of a microsecond, and the difference is the cost
of constructing the scanner, name filter and adapter that Gonzalez builds for
each document.

Two cautions. First, these are one thread parsing the same document over and
over, which is as kind to the caches as a benchmark can be. Giving each parse
a thread of its own makes every parser slower by one to three microseconds,
because a new thread usually runs on a core whose caches hold something else.
That narrows the ratios, as it adds about the same to each parser, but it is
a cost of the thread and not of the parser, so it is not shown. Second, the
documents are small and plain. They have no DTD, and the work that Gonzalez
does for one is not in these figures.

To reproduce them, with the same `AALTO_JAR` and `STAX2_JAR` variables as above:

```sh
UNIT=us benchmark/external-compare/run-isolated.sh 5 "tiny small books attrs-small" \
    "xmlhandler xmlhandler-reuse sax-touch sax-touch-reuse aalto-sax-touch"
```

