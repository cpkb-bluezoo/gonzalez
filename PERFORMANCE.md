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
- five that the harness makes from the plain document, as other kinds of text
  with non-ASCII characters in them: an accented letter (two bytes of UTF-8)
  after one space in every 300 bytes or so, which is what text that is nearly
  all ASCII looks like once it has a few accents, dashes or quotation marks in
  it; the letter e replaced by an accented e (two bytes) in every second place
  and in every place in the text, about one non-ASCII character in 43 and in
  22 bytes; an accented letter in about one place in 900 bytes, as in English
  with the odd foreign word; and an emoji (four bytes) in about one place in
  220 bytes, as in casual English;
- a generated MathML document of small expressions of Latin and Greek
  identifiers, numbers and operators, some of them the three-byte symbols of
  Unicode's mathematical blocks, which is a good deal of markup for each
  character of text;
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
| Plain UTF-8 | 1.02 MiB | 949 MB/s | 922 MB/s | 1.03 |
| Attribute-heavy UTF-8 | 1.28 MiB | 662 MB/s | 551 MB/s | 1.20 |
| Whitespace-heavy UTF-8 | 0.94 MiB | 1,217 MB/s | 1,048 MB/s | 1.16 |
| Markup-heavy UTF-8 | 0.96 MiB | 1,243 MB/s | 1,077 MB/s | 1.15 |
| Multibyte UTF-8 | 0.49 MiB | 646 MB/s | 531 MB/s | 1.22 |
| Mostly ASCII with accents | 1.02 MiB | 821 MB/s | 901 MB/s | 0.91 |
| Accent on every second e (German-like) | 1.04 MiB | 718 MB/s | 854 MB/s | 0.84 |
| Accent on every e (French-like) | 1.06 MiB | 716 MB/s | 837 MB/s | 0.86 |
| English with the odd foreign word | 1.02 MiB | 817 MB/s | 910 MB/s | 0.90 |
| English with emoji | 1.04 MiB | 799 MB/s | 875 MB/s | 0.91 |
| MathML (generated) | 1.17 MiB | 369 MB/s | 390 MB/s | 0.95 |
| Japanese UTF-16LE | 1.50 MiB | 1,352 MB/s | 731 MB/s | 1.85 |
| Japanese EUC-JP | 1.28 MiB | 873 MB/s | 525 MB/s | 1.66 |
| French novel (ELTeC) | 1.00 MiB | 1,045 MB/s | 1,124 MB/s | 0.93 |
| German novel (ELTeC) | 0.95 MiB | 986 MB/s | 986 MB/s | 1.00 |

Gonzalez is faster than Aalto's cursor on plain, attribute-heavy,
whitespace-heavy, markup-heavy and multibyte UTF-8 (by between 1.03 and 1.22
times) and on UTF-16LE and EUC-JP (1.85 and 1.66 times), and is level on the
German novel. Aalto is faster where the text has accented letters in it and not
much markup around them: by between 1.08 and 1.2 times on the French novel, the
generated documents with accents and the document with emoji, and about 5% on
MathML.

Attribute values reach Aalto's consumer as `String`s, which the loop above reads
a character at a time. If only the length of each value is read, Aalto reads the
attribute-heavy document at 635 MB/s and not 551, and Gonzalez is ahead of it by
1.08 times and not 1.2.

Throughput is measured from encoded byte size, so UTF-16's MB/s also reflects
that it uses more bytes for the same logical Japanese document. UTF-16 is
decoded by Gonzalez's own decoder, which copies the characters out of the input
in bulk, where the JDK's takes about half the time of the whole parse to do the
same.

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
parse of the plain document 40% slower for that reason. What is left of the
difference with Aalto on text with accents is the decoder: Aalto works on the
bytes, where Gonzalez decodes them first, and the widening of the runs of ASCII
between accented letters is the part that Gonzalez cannot do as fast as the
JDK's own routine does.

## SAX

The same documents through SAX, with a handler that reads every attribute and
all the text, for the same reason as above: with an empty handler Aalto's SAX
wrapper does less work than Gonzalez, because it decodes an attribute value only
when the handler asks for it, whereas Gonzalez builds each value as it scans.

| Corpus | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Gonzalez / Aalto |
|--------|-------------:|---------------:|----------:|-----------------:|
| Plain UTF-8 | 858 MB/s | 532 MB/s | 1,132 MB/s | 0.76 |
| Attribute-heavy UTF-8 | 524 MB/s | 220 MB/s | 704 MB/s | 0.75 |
| Whitespace-heavy UTF-8 | 1,180 MB/s | 705 MB/s | 1,323 MB/s | 0.89 |
| Markup-heavy UTF-8 | 1,237 MB/s | 533 MB/s | 1,421 MB/s | 0.87 |
| Multibyte UTF-8 | 528 MB/s | 313 MB/s | 568 MB/s | 0.93 |
| Mostly ASCII with accents | 758 MB/s | 440 MB/s | 1,102 MB/s | 0.69 |
| Accent on every second e (German-like) | 666 MB/s | 426 MB/s | 1,027 MB/s | 0.65 |
| Accent on every e (French-like) | 664 MB/s | 433 MB/s | 996 MB/s | 0.67 |
| English with the odd foreign word | 753 MB/s | 431 MB/s | 1,112 MB/s | 0.68 |
| English with emoji | 590 MB/s | 403 MB/s | 1,062 MB/s | 0.56 |
| MathML (generated) | 257 MB/s | 158 MB/s | 431 MB/s | 0.60 |
| Japanese UTF-16LE | 1,151 MB/s | 754 MB/s | 787 MB/s | 1.46 |
| Japanese EUC-JP | 773 MB/s | 415 MB/s | 559 MB/s | 1.38 |
| French novel (ELTeC) | 1,172 MB/s | 841 MB/s | 1,540 MB/s | 0.76 |
| German novel (ELTeC) | 1,103 MB/s | 646 MB/s | 1,357 MB/s | 0.81 |
| XHTML with an internal DTD subset | 326 MB/s | 148 MB/s | *not compared†* | |

† Aalto is not compared on the document with a DTD. It is not a validating
parser and does not process a DTD subset: it recognises the DOCTYPE but skips
the internal declarations, so it neither applies declared attribute types and
defaults nor expands the declared entities, as Gonzalez and Xerces do, and its
throughput would be for less work. Its SAX factory rejects validating mode, and
its own `Attributes2` implementation states that DTD processing is absent.

Gonzalez SAX is faster than the JDK's Xerces on every document, by between 1.4
and 2.4 times. Aalto SAX is between 1.1 and 1.8 times as fast as Gonzalez SAX on
the UTF-8 documents, most on the document with emoji and on MathML, and by
least on multibyte and whitespace-heavy UTF-8; on UTF-16LE Gonzalez SAX is about
1.5 times as fast as Aalto, and on EUC-JP about 1.4 times. The gap between the
native interface and SAX is the SAX layer, which is larger for these two
documents than for the rest.

The DTD behaviour was verified from SAX events using
`benchmark/external-compare/DtdProbe.java`: Gonzalez and JDK Xerces report
`id` attributes with declared type `ID` and inject the DTD-defaulted,
non-specified `type="text"` attribute on `<input>`. Aalto reports `id` as
`CDATA` and omits the defaulted attribute, which is why it is left out of the
row.

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
between the native interface and SAX above. In exchange, for a document with
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
ant benchmark-memory
```

The construct is 256 MB and the heap 32 MB unless `-Dbenchmark.memory.mb=N` and
`-Dbenchmark.memory.heap=Nm` say otherwise. The target runs
`benchmark/external-compare/run-memory.sh`, which takes the same two as
arguments, and needs `AALTO_JAR` and `STAX2_JAR` when run on its own.

To reproduce the comparison:

```sh
ant benchmark-compare
```

The target builds Gonzalez, fetches what it is measured against (aalto-xml, into
`lib`) and the documents it is measured on (the French and German novels, into
`benchmark/corpora`), each download checked against a SHA-256, runs the whole
comparison, each figure in fresh JVMs, and prints the tables above, as Markdown,
with the commit, the JDK and the machine it was run on. It takes a long time,
and writes the tables to `build/performance.md`. `-Dbenchmark.reps=N` is how
many fresh JVMs each figure is the median of (five by default),
`-Dbenchmark.compare.corpora="plain french"` runs only some of the documents,
and `-Dbenchmark.report=FILE` says where the tables are written. `ant
download-comparison-deps` and `ant download-corpora` fetch their parts on
their own.

The "Performance comparison" workflow runs the same target on GitHub for any
branch, tag or commit, `main` unless another is given, and shows the tables on
the summary page of the run: from the Actions tab, or with

```sh
gh workflow run performance.yml -f ref=my-branch
```

A hosted runner is shared with other work and is not the same machine from one
run to the next, so compare the columns of one run with each other, and not
its figures with those here or in another run.

For more control, the scripts that the target runs are in
`benchmark/external-compare`. They need `AALTO_JAR` and `STAX2_JAR` to name the
jars (after `ant download-comparison-deps`, `lib/aalto-xml-1.4.0.jar` and
`lib/stax2-api-4.3.0.jar`), and exit with an error if either is unset or does
not name a file:

```sh
export AALTO_JAR=lib/aalto-xml-1.4.0.jar
export STAX2_JAR=lib/stax2-api-4.3.0.jar
benchmark/external-compare/run-isolated.sh
```

`run-isolated.sh` runs each corpus and parser in a fresh JVM, five times by
default, and reports the median; its arguments are the number of runs, and
quoted lists of corpora and modes. Set `GONZALEZ_CLASSES` to compare another
build of Gonzalez. The modes of the first table are `xmlhandler-sum` and
`aalto-stax-sum`, and those of the second `sax-touch`, `jdk-touch` and
`aalto-sax-touch`. The modes `xmlhandler`, `sax` and `jdk`, with empty handlers,
and `aalto-stax`, which only steps the cursor through the events and reads
nothing, do less work than the others and are not comparable with them.
`performance-tables.sh` takes the number of runs and a file for the tables, and
includes the French and German novels when they are in `benchmark/corpora` (or
`FRENCH_XML` and `GERMAN_XML` name a French and a German document of your own).
`benchmark/external-compare/run.sh` is a quicker single-JVM comparison that runs
every parser in turn, and `run-dtd-probe.sh` uses the same variables.
