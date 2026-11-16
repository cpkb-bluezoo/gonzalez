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
| Plain UTF-8 | 1.02 MiB | **1,043 MB/s** | 856 MB/s | 554 MB/s | 1,197 MB/s | 1,264 MB/s |
| Attribute-heavy UTF-8 | 1.28 MiB | **710 MB/s** | 533 MB/s | 256 MB/s | 947 MB/s | 943 MB/s |
| Whitespace-heavy UTF-8 | 0.94 MiB | **1,363 MB/s** | 1,194 MB/s | 700 MB/s | 1,335 MB/s | 1,597 MB/s |
| Markup-heavy UTF-8 | 0.96 MiB | **1,227 MB/s** | 1,074 MB/s | 526 MB/s | 1,463 MB/s | 1,723 MB/s |
| Multibyte UTF-8 | 0.49 MiB | **659 MB/s** | 554 MB/s | 335 MB/s | 755 MB/s | 798 MB/s |
| Mostly ASCII with accents | 1.02 MiB | **870 MB/s** | 740 MB/s | 441 MB/s | 1,163 MB/s | 1,289 MB/s |
| Japanese UTF-16LE | 1.50 MiB | **1,420 MB/s** | 1,132 MB/s | 815 MB/s | 922 MB/s | 911 MB/s |
| Japanese EUC-JP | 1.28 MiB | **902 MB/s** | 765 MB/s | 430 MB/s | 639 MB/s | 648 MB/s |
| XHTML internal DTD (not equivalent; see above) | 1.19 MiB | **422 MB/s** | 332 MB/s | 150 MB/s | *899 MB/s†* | *904 MB/s†* |

† Aalto's figures are non-validating: it skips the internal DTD declarations.

Gonzalez SAX is faster than the JDK's Xerces on every corpus. Aalto SAX is
between about 1.1 and 1.8 times as fast as Gonzalez SAX on the UTF-8 corpora; on
UTF-16LE Gonzalez SAX is about 23% faster than Aalto, and on EUC-JP about 20%
faster. Throughput is measured from
encoded byte size, so UTF-16's MB/s also reflects that it uses more bytes for
the same logical Japanese document. UTF-16 is decoded by Gonzalez's own
decoder, which copies the characters out of the input in bulk, where the JDK's
takes about half the time of the whole parse to do the same.

EUC-JP is decoded from tables, which are made once by asking the JDK's decoder
what each sequence is, where the JDK's decoder took almost two thirds of the
time of the whole parse to look each character up; anything that is not in the
tables, an unassigned code or a sequence cut short, is given back to the JDK's
decoder, so that errors are reported as they were.

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
| Plain UTF-8 | 848 MB/s | 1,112 MB/s |
| Attribute-heavy UTF-8 | 520 MB/s | 707 MB/s |
| Whitespace-heavy UTF-8 | 1,199 MB/s | 1,315 MB/s |
| Markup-heavy UTF-8 | 1,080 MB/s | 1,432 MB/s |
| Multibyte UTF-8 | 546 MB/s | 569 MB/s |
| Mostly ASCII with accents | 731 MB/s | 1,081 MB/s |
| Japanese UTF-16LE | 1,117 MB/s | 761 MB/s |
| Japanese EUC-JP | 750 MB/s | 561 MB/s |
| XHTML internal DTD (not equivalent; see above) | 326 MB/s | *752 MB/s†* |

Aalto remains faster on the UTF-8 corpora, by between about 1.0 and 1.5 times,
most on the accented document and on attribute-heavy, plain and markup-heavy
UTF-8, and by little on multibyte UTF-8. On UTF-16LE Gonzalez is about 1.5 times
as fast, and on EUC-JP about 1.3 times.

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

`benchmark/external-compare/performance-tables.sh` runs the whole comparison and
prints the two tables above, as Markdown, with the commit, the JDK and the
machine it was run on. The "Performance comparison" workflow runs it on GitHub
for any branch, tag or commit, `main` unless another is given, and shows the
tables on the summary page of the run: from the Actions tab, or with

```sh
gh workflow run performance.yml -f ref=my-branch
```

A hosted runner is shared with other work and is not the same machine from one
run to the next, so compare the columns of one run with each other, and not
its figures with those here or in another run.

The scripts exit with an error if either variable is unset or does not name a
file. `benchmark/external-compare/run-dtd-probe.sh` uses the same variables.
