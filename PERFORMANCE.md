# Performance and Scalability

Part of the [Gonzalez documentation](README.md).

The XML parser was compared with the SAX parser bundled with JDK 25 (Xerces)
and [aalto-xml](https://github.com/FasterXML/aalto-xml) 1.4.0. All paths ran
namespace-aware. There are two comparisons, of the native interfaces and of
SAX.

The figures are median throughput across five fresh JVM runs, each with one
corpus and one parser, 800 warm-up parses and then 500 measured parses,
measured from the encoded size of the document. The documents are:

- the original five, in `benchmark/resources`: plain, attribute-heavy,
  whitespace-heavy, markup-heavy and multibyte UTF-8;
- three that `BenchmarkCorpora` generates deterministically: the same
  4,000-article Japanese document as BOM-marked UTF-16LE and as EUC-JP,
  isolating the two decoder paths as far as practical, and a 1,500-article
  XHTML-shaped document with a self-contained internal subset declaring 33
  elements, common and element-specific attributes, content models and
  character entities (it has no public or system identifier, so no external
  subset is fetched);
- two that the harness makes from the plain document, as other kinds of text
  with non-ASCII characters in them: an accented letter (two bytes of UTF-8)
  after one space in every 300 bytes or so, which is what text that is nearly
  all ASCII looks like once it has a few accents, dashes or quotation marks in
  it; and an emoji (four bytes) in about one place in 220 bytes, as in casual
  English;
- a generated MathML document of small expressions of Latin and Greek
  identifiers, numbers and operators, some of them the three-byte symbols of
  Unicode's mathematical blocks, which is a good deal of markup for each
  character of text, in one `math` element with the namespace declared once on
  it, which everything under it inherits, as MathML is usually written;
- two real documents, a French and a German novel of about 1 MB each from the
  [ELTeC](https://github.com/COST-ELTeC) collection of public domain novels in
  TEI XML (`FRA03601_Proust.xml` and `DEU037.xml`). They are not kept in this
  repository: `ant download-corpora` fetches them into `benchmark/corpora`,
  each from a fixed commit of its collection and checked against its SHA-256,
  and the harness and the table script measure them when they are there.
  Their accented letters are about one in 35 bytes in the French and one in 48
  in the German, lighter than the generated documents with an accent on every
  e.

## Native interfaces

Gonzalez's `XMLHandler` is compared with Aalto's StAX cursor, each the parser's
own interface and its most direct. The `XMLHandler` does byte decoding, XML
grammar processing, DTD work and namespace declaration handling, but no SAX
adaptation. Unlike SAX, the native SPI is Gonzalez-specific and may evolve
between releases.

Both are given a consumer that reads what it is handed: every character of
text, comments, processing instruction data and attribute values, and the
length of every name. This is what makes them comparable. Aalto's cursor
decodes text and attribute values only when they are asked for, so a loop that
only steps it through the events does less work than a Gonzalez handler, to
which the decoded characters are delivered whether it reads them or not.

| Corpus | Size | Gonzalez XMLHandler | Aalto StAX cursor | Gonzalez / Aalto |
|--------|-----:|--------------------:|------------------:|-----------------:|
| Plain UTF-8 | 1.02 MiB | 978 MB/s | 937 MB/s | 1.04 |
| Attribute-heavy UTF-8 | 1.28 MiB | 694 MB/s | 566 MB/s | 1.22 |
| Whitespace-heavy UTF-8 | 0.94 MiB | 1,216 MB/s | 1,054 MB/s | 1.15 |
| Markup-heavy UTF-8 | 0.96 MiB | 1,243 MB/s | 1,081 MB/s | 1.15 |
| Multibyte UTF-8 | 0.49 MiB | 628 MB/s | 532 MB/s | 1.18 |
| Mostly ASCII with accents | 1.02 MiB | 879 MB/s | 910 MB/s | 0.97 |
| English with emoji | 1.04 MiB | 830 MB/s | 889 MB/s | 0.93 |
| MathML | 1.17 MiB | 346 MB/s | 382 MB/s | 0.91 |
| Japanese UTF-16LE | 1.50 MiB | 1,353 MB/s | 733 MB/s | 1.84 |
| Japanese EUC-JP | 1.28 MiB | 884 MB/s | 527 MB/s | 1.68 |
| French novel (ELTeC) | 1.00 MiB | 1,057 MB/s | 1,123 MB/s | 0.94 |
| German novel (ELTeC) | 0.95 MiB | 993 MB/s | 1,022 MB/s | 0.97 |

Gonzalez is faster than Aalto's cursor on plain, attribute-heavy,
whitespace-heavy, markup-heavy and multibyte UTF-8 (by between 1.04 and 1.22
times) and on UTF-16LE and EUC-JP (1.84 and 1.68 times). Aalto is faster where
the text has accented letters in it, by between 1.03 and 1.08 times (1.06 on
the French novel, 1.03 on the German novel and 1.08 on the document with
emoji), and on MathML, by 1.10 times.

## SAX

The same documents through SAX. The handler does what the use of a SAX parser
is: for every element it reads the names, for every attribute in the
`Attributes` it is given it reads the name and the value, and it reads the text
that it is given.

| Corpus | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Gonzalez / Aalto |
|--------|-------------:|---------------:|----------:|-----------------:|
| Plain UTF-8 | 1,008 MB/s | 537 MB/s | 1,140 MB/s | 0.88 |
| Attribute-heavy UTF-8 | 631 MB/s | 218 MB/s | 718 MB/s | 0.88 |
| Whitespace-heavy UTF-8 | 1,306 MB/s | 708 MB/s | 1,332 MB/s | 0.98 |
| Markup-heavy UTF-8 | 1,268 MB/s | 537 MB/s | 1,470 MB/s | 0.86 |
| Multibyte UTF-8 | 597 MB/s | 316 MB/s | 582 MB/s | 1.03 |
| Mostly ASCII with accents | 968 MB/s | 442 MB/s | 1,119 MB/s | 0.87 |
| English with emoji | 851 MB/s | 408 MB/s | 1,068 MB/s | 0.80 |
| MathML | 334 MB/s | 176 MB/s | 420 MB/s | 0.80 |
| Japanese UTF-16LE | 1,285 MB/s | 760 MB/s | 760 MB/s | 1.69 |
| Japanese EUC-JP | 839 MB/s | 421 MB/s | 565 MB/s | 1.49 |
| French novel (ELTeC) | 1,237 MB/s | 840 MB/s | 1,558 MB/s | 0.79 |
| German novel (ELTeC) | 1,178 MB/s | 668 MB/s | 1,370 MB/s | 0.86 |
| XHTML with an internal DTD subset | 388 MB/s | 148 MB/s | *not compared†* | |

† Aalto is not compared on the document with a DTD. It is not a validating
parser and does not process a DTD subset.

Gonzalez SAX is faster than the JDK's Xerces on every document, by between 1.5
and 2.9 times. Aalto SAX is ahead in some scenarios and behind on others,
as shown.

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
chunks (the `XMLHandler` events, each with an `end` flag), and the SAX adapter
is a layer on top of that. The layer costs throughput, which is most of the gap
between the native interface and SAX above. In exchange, for a document with
no DTD the parser itself holds no more than a bounded amount of it, however
large any one construct is.

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

You can reproduce the memory comparison with:

```sh
ant benchmark-memory
```

The construct is 256 MB and the heap 32 MB unless `-Dbenchmark.memory.mb=N` and
`-Dbenchmark.memory.heap=Nm` say otherwise. The target runs
`benchmark/external-compare/run-memory.sh`, which takes the same two as
arguments, and needs `AALTO_JAR` and `STAX2_JAR` when run on its own.

To reproduce the throughput comparison:

```sh
ant benchmark-compare
```

