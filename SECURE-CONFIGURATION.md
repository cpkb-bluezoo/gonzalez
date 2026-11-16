# Secure Configuration and Limits

Gonzalez is **secure by default**. External entity processing and external DTD
loading are disabled out of the box, protecting against XXE (XML External
Entity) attacks, SSRF, and entity expansion bombs.

This document describes the defaults, how to relax them, and the limits that
bound what the parser holds. It is part of the [Gonzalez documentation](README.md).
To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Default Security Settings

| Setting | Default | Effect |
|---------|---------|--------|
| `external-general-entities` | `false` | External general entities are not resolved |
| `external-parameter-entities` | `false` | External DTD subsets are not loaded |
| `accessExternalDTD` | `""` (none) | No protocols allowed for external DTD access |
| Entity expansion limit | 64,000 | Protects against billion-laughs entity bombs |
| Element depth limit | 1,000 | Bounds the stack of open elements |
| Name length limit | 4,096 | Bounds what is held while a name is still arriving |
| Attributes per element limit | 10,000 | Bounds the names remembered to detect duplicates |

## Enabling External Entities

If you need to process documents with external DTDs or entity references, you
must explicitly opt in to both the feature flags **and** the allowed protocols:

```java
Parser parser = new Parser();

// Enable external entity processing
parser.setFeature("http://xml.org/sax/features/external-general-entities", true);
parser.setFeature("http://xml.org/sax/features/external-parameter-entities", true);

// Allow specific protocols (required even when entities are enabled)
parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
```

The `accessExternalDTD` property accepts:
- `""` -- no protocols allowed (default)
- `"all"` -- all protocols allowed
- Comma-separated list -- e.g. `"file"`, `"file,http,https"`

## JAXP Secure Processing

Gonzalez recognizes the standard JAXP `FEATURE_SECURE_PROCESSING` feature.
Setting it to `false` enables external entities and sets `accessExternalDTD`
to `"all"`:

```java
// Via SAXParserFactory
SAXParserFactory factory = new GonzalezSAXParserFactory();
factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", false);
SAXParser parser = factory.newSAXParser();
```

## XSLT Transformer Security

The transformer factory also enforces secure defaults. To allow stylesheet
and DTD loading from files:

```java
GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "file");
factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
```

## Entity Expansion Limit

The default limit of 64,000 entity expansions matches the JDK default and
protects against entity expansion attacks (billion laughs). To adjust:

```java
parser.setProperty("http://www.nongnu.org/gonzalez/properties/entity-expansion-limit", 100000);
```

Set to `0` to disable the limit (not recommended for untrusted input).

## Structural Limits

Text, attribute values, comments and processing instruction data are passed
on in pieces and never held, so their size costs the parser nothing. What it
must remember while it reads is bounded by three further limits. A document
that exceeds one is rejected with a fatal error at the point where it does so:

| Limit | `XMLParser` setter | SAX property | Default |
|-------|--------------------|--------------|---------|
| Element nesting depth | `setMaxElementDepth` | `jdk.xml.maxElementDepth` | 1,000 |
| Length of a name, in characters | `setMaxNameLength` | `jdk.xml.maxXMLNameLimit` | 4,096 |
| Attributes of one element | `setMaxAttributesPerElement` | `jdk.xml.elementAttributeLimit` | 10,000 |

```java
// Native parser
XMLParser parser = new XMLParser();
parser.setMaxElementDepth(200);

// SAX: the JDK's property names, with an Integer or a numeric String
Parser reader = new Parser();
reader.setProperty("jdk.xml.maxElementDepth", 200);
```

The SAX properties are the JDK's own names for these limits, so configuration
written for the JDK parser carries over; `jdk.xml.entityExpansionLimit` is
accepted for the entity expansion limit in the same way, as are the older
`http://www.oracle.com/xml/jaxp/properties/` forms of all four. The defaults
are Gonzalez's own and do not follow the JDK in use, and the `jdk.xml` system
properties are not read. The name length limit covers element and attribute
names, processing instruction targets and references, but not names inside a
DTD. With namespace processing enabled it also covers the namespace name in a
namespace declaration, the one attribute value that is reported whole and so
has to be held. Zero or a negative value disables a limit.

## Buffer Sizes

What a parser allocates is yours to size. The buffer you pass to
`receive(ByteBuffer)` is your own, so its size is your choice. The buffers the
parser allocates itself are allocated once, at the sizes below, and kept by
`reset()`, so a parser used for many documents allocates none of them after
the first. Each has a default that suits most uses:

| Buffer | `XMLParser` setter | SAX property | Default |
|--------|--------------------|--------------|---------|
| Bytes decoded into characters | `setDecodeBufferSize` | `decode-buffer-size` | 32,768 characters (64 KB) |
| Scan buffer | `setScanBufferSize` | `scan-buffer-size` | 8,192 characters (16 KB) |
| Table of interned names | `setNameTableSize` | `name-table-size` | 512 entries (about 16 KB) |
| Read buffer of `parse(InputStream)` and `parse(ReadableByteChannel)` | `setInputBufferSize` | `input-buffer-size` | 32,768 bytes |

The SAX properties are in the namespace
`http://www.nongnu.org/gonzalez/properties/` and take an Integer or a numeric
String. A parser with the defaults holds about 100 KB, and a further 32 KB if
it is asked to `parse` a stream.

```java
XMLParser parser = new XMLParser();
parser.setDecodeBufferSize(4096);    // where memory is dear and parsers are many
parser.setNameTableSize(64);         // a vocabulary of a few dozen names
```

The decode buffer is the largest, and the one to make smaller. Input is
decoded and scanned in pieces of its size, so a smaller buffer makes no
difference to what is reported, only to how many pieces there are. The scan
buffer holds the data the parser was last given and any one name or declaration
still arriving when that ended; a document that needs more (a name as long as
the name length limit allows, or a large declaration in a DTD) makes it grow
for that document, and it is put back to the size set when the document is
done. The name table is the same size whatever the document: one with more
distinct names than it has room for is parsed correctly, the names it has no
room for being made afresh each time they occur.

A change to a size takes effect with the next document. The smallest sizes
allowed are 256 characters for the scan buffer, 16 characters for the decode
buffer, 16 entries for the name table and 16 bytes for the read buffer; a
smaller one is refused.

A parser that is made for one document and discarded allocates these buffers
for that document. To parse many documents, keep the parser and call `reset()`
between them.

