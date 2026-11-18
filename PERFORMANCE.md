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
| Plain UTF-8 | 1.02 MiB | 960 MB/s | 935 MB/s | 1.03 |
| Attribute-heavy UTF-8 | 1.28 MiB | 687 MB/s | 564 MB/s | 1.22 |
| Whitespace-heavy UTF-8 | 0.94 MiB | 1,231 MB/s | 1,062 MB/s | 1.16 |
| Markup-heavy UTF-8 | 0.96 MiB | 1,259 MB/s | 1,079 MB/s | 1.17 |
| Multibyte UTF-8 | 0.49 MiB | 638 MB/s | 536 MB/s | 1.19 |
| Mostly ASCII with accents | 1.02 MiB | 815 MB/s | 920 MB/s | 0.89 |
| English with emoji | 1.04 MiB | 792 MB/s | 889 MB/s | 0.89 |
| MathML | 1.17 MiB | 346 MB/s | 384 MB/s | 0.90 |
| Japanese UTF-16LE | 1.50 MiB | 1,360 MB/s | 737 MB/s | 1.85 |
| Japanese EUC-JP | 1.28 MiB | 885 MB/s | 535 MB/s | 1.65 |
| French novel (ELTeC) | 1.00 MiB | 1,029 MB/s | 1,146 MB/s | 0.90 |
| German novel (ELTeC) | 0.95 MiB | 1,013 MB/s | 982 MB/s | 1.03 |

Gonzalez is faster than Aalto's cursor on plain, attribute-heavy,
whitespace-heavy, markup-heavy and multibyte UTF-8 (by between 1.03 and 1.22
times) and on UTF-16LE and EUC-JP (1.85 and 1.65 times), and is level on the
German novel. Aalto is faster where the text has accented letters in it, by
1.11 to 1.12 times (on the French novel, the document with accents and the
document with emoji), and on MathML, by 1.11 times.

## SAX

The same documents through SAX. The handler does what the use of a SAX parser
is: for every element it reads the names, for every attribute in the
`Attributes` it is given it reads the name and the value, and it reads the text
that it is given.

| Corpus | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Gonzalez / Aalto |
|--------|-------------:|---------------:|----------:|-----------------:|
| Plain UTF-8 | 996 MB/s | 540 MB/s | 1,134 MB/s | 0.88 |
| Attribute-heavy UTF-8 | 631 MB/s | 223 MB/s | 717 MB/s | 0.88 |
| Whitespace-heavy UTF-8 | 1,320 MB/s | 715 MB/s | 1,323 MB/s | 1.00 |
| Markup-heavy UTF-8 | 1,292 MB/s | 540 MB/s | 1,449 MB/s | 0.89 |
| Multibyte UTF-8 | 602 MB/s | 317 MB/s | 583 MB/s | 1.03 |
| Mostly ASCII with accents | 871 MB/s | 439 MB/s | 1,105 MB/s | 0.79 |
| English with emoji | 812 MB/s | 409 MB/s | 1,071 MB/s | 0.76 |
| MathML | 334 MB/s | 154 MB/s | 424 MB/s | 0.79 |
| Japanese UTF-16LE | 1,280 MB/s | 762 MB/s | 770 MB/s | 1.66 |
| Japanese EUC-JP | 842 MB/s | 426 MB/s | 569 MB/s | 1.48 |
| French novel (ELTeC) | 1,307 MB/s | 855 MB/s | 1,561 MB/s | 0.84 |
| German novel (ELTeC) | 1,208 MB/s | 674 MB/s | 1,373 MB/s | 0.88 |
| XHTML with an internal DTD subset | 371 MB/s | 150 MB/s | *not compared†* | |

† Aalto is not compared on the document with a DTD. It is not a validating
parser and does not process a DTD subset.

Gonzalez SAX is faster than the JDK's Xerces on every document, by between 1.5
and 2.8 times. Aalto SAX is ahead in some scenarios and behind on others,
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

