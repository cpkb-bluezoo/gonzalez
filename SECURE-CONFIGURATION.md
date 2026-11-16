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
