# Gonzalez XSLT Transformer

Streaming XSLT transformation facility with XSLT 3.0 streaming support

## Overview

The Gonzalez XSLT transformer provides streaming transformation capabilities that
align with Gonzalez's core design principles: event-driven processing, predictable
resource usage, and JAXP compliance. Unlike traditional XSLT processors that build
complete in-memory DOM trees, Gonzalez uses an accumulator-based streaming
architecture that enables processing of arbitrarily large documents.

The transformer supports XSLT 1.0, 2.0, and 3.0 stylesheets with a focus on
streaming execution. XSLT 3.0's explicit streaming constructs (`xsl:stream`,
`xsl:accumulator`, `xsl:iterate`) are fully supported, and XSLT 1.0/2.0
stylesheets are automatically analyzed for streamability.

## Design Goals

1. **Streaming-First** - Process documents larger than available memory
2. **JAXP Compliance** - Drop-in replacement via `TransformerFactory`
3. **Event-Driven** - SAX-based input and output
4. **Accumulator-Based** - XSLT 3.0 accumulators as the primary stateful mechanism
5. **Automatic Streamability** - Analyze XSLT 1.0/2.0 stylesheets for streaming execution
6. **Progressive XPath Support** - XPath 2.0/3.0 features essential for XSLT 3.0
7. **Predictable Resources** - Iterative algorithms, explicit state management

## Architecture

### SAX Event Filter Model

The transformer operates as a **SAX event filter**: it receives input SAX events
from the source document, applies XSLT transformations, and emits output SAX
events to the result. This design enables:

- Direct integration with Gonzalez's streaming parser
- Pipeline composition with other SAX filters
- Memory-efficient processing of large documents
- Native SAX-to-SAX transformation path

![SAX Event Filter Model](xslt-sax-filter.svg)

### Component Overview

![Component Overview](xslt-component-overview.svg)

### Streaming Architecture

The transformer uses a three-tier buffering strategy:

| Strategy | Description | Use Case |
|----------|-------------|----------|
| NONE | Pure streaming with accumulators | Forward-only access |
| GROUNDED | Subtree buffering | Reverse axes within element |
| FULL_DOCUMENT | Complete document buffering | Global reverse access |

**Streaming Decision Flow:**

![Streaming Decision Flow](xslt-streaming-flow.svg)

### Accumulators

Accumulators are the primary mechanism for maintaining state during streaming.
They accumulate values as nodes are processed, eliminating the need to buffer
the document.

**Explicit Accumulators (XSLT 3.0):**

```xml
<xsl:accumulator name="line-count" initial-value="0" as="xs:integer">
  <xsl:accumulator-rule match="line" select="$value + 1"/>
</xsl:accumulator>

<!-- Access in templates -->
<xsl:value-of select="accumulator-after('line-count')"/>
```

**Internal Accumulators (Automatic):**

For XSLT 1.0/2.0 stylesheets, the `InternalAccumulatorFactory` automatically
creates synthetic accumulators for common patterns:

| Pattern | Internal Accumulator |
|---------|---------------------|
| `position()` | Counter accumulator |
| `count(preceding-sibling::*)` | Sibling counter |
| `sum(preceding-sibling::@value)` | Running sum |

This allows stylesheets written for XSLT 1.0 to execute in streaming mode
without modification.

### Stylesheet Compilation

Stylesheets are compiled using SAX events from the Gonzalez parser:

1. **StylesheetCompiler** - SAX ContentHandler that builds the compiled representation
2. **StreamabilityAnalyzer** - Analyzes templates for streaming capability
3. **InternalAccumulatorFactory** - Creates synthetic accumulators for 1.0/2.0 patterns
4. **CompiledStylesheet** - Immutable representation containing:
   - Template rules with match patterns
   - Named templates
   - Accumulator definitions
   - Mode declarations
   - Global variables and parameters
   - Attribute sets
   - Key definitions
   - Output properties

### XPath Engine

The XPath engine implements XPath 1.0 with progressive XPath 2.0/3.1 support:

- **XPathLexer** - Tokenizes XPath expressions
- **XPathParser** - Pratt (operator precedence) parser using iterative algorithm
- **Expression AST** - `Expr` interface with implementations for all XPath constructs
- **Axis Iterators** - Lazy evaluation of XPath axes over node sets
- **Function Libraries** - Core XPath 1.0 + XSLT 2.0/3.0 functions
- **Type System** - XPath 2.0+ sequences and atomic values

#### XPath Type System

| Type | Description | Version |
|------|-------------|---------|
| String | Character sequence | 1.0 |
| Number | IEEE 754 double | 1.0 |
| Boolean | True/false | 1.0 |
| NodeSet | Unordered collection of nodes | 1.0 |
| Sequence | Ordered collection of items | 2.0+ |
| Atomic | Untyped atomic value | 2.0+ |

#### XPath 2.0/3.0 Feature Support

The XPath engine progressively supports XPath 2.0 and 3.0 features essential for
XSLT 3.0 streaming transformations.

**Supported (XPath 2.0):**

| Feature | Syntax | Example |
|---------|--------|---------|
| Sequences | `()`, `(a, b, c)` | `(1, 2, 3)` |
| Range expressions | `to` | `1 to 10` |
| Value comparisons | `eq`, `ne`, `lt`, `le`, `gt`, `ge` | `$x eq 5` |
| For expressions | `for $x in expr return expr` | `for $x in (1,2,3) return $x*2` |
| If expressions | `if (test) then expr else expr` | `if ($x > 0) then "+" else "-"` |
| Quantified expressions | `some`/`every` | `some $x in //item satisfies $x > 10` |
| Node comparisons | `is`, `<<`, `>>` | `$a is $b`, `$a << $b` |
| Set operations | `intersect`, `except` | `$a intersect $b` |

**Supported (XPath 3.0/3.1):**

| Feature | Syntax | Example |
|---------|--------|---------|
| Let expressions | `let $x := expr return expr` | `let $x := 5 return $x * 2` |
| String concatenation | `\|\|` | `$first \|\| " " \|\| $last` |
| Simple map | `!` | `//item ! @name` |
| Arrow operator | `=>` | `$x => upper-case()` |

**Deferred (Requires Schema Support):**

| Feature | Syntax | Notes |
|---------|--------|-------|
| Type expressions | `instance of`, `cast as` | Requires XSD |
| Typed atomic values | `xs:integer`, `xs:date` | Requires XSD |
| Schema element tests | `schema-element()` | Requires XSD |

**Not Planned (Complexity vs. Streaming Benefit):**

| Feature | Reason |
|---------|--------|
| Higher-order functions | Complex; limited streaming benefit |
| Maps and arrays | XPath 3.1; adds significant complexity |
| Dynamic function calls | Requires higher-order functions |

#### Iterative Parser Design

The XPath parser uses explicit stacks instead of recursion, consistent with
Gonzalez's state-machine design philosophy. This provides:

- **Predictable stack usage** - No risk of stack overflow on deeply nested expressions
- **Explicit state management** - Parser state is fully visible and debuggable
- **Context isolation** - Nested expression parsing uses separate contexts

### XPath Axis Streaming

XPath axes are classified by their streaming characteristics:

| Axis Type | Axes | Streaming |
|-----------|------|-----------|
| Forward | child, descendant, following, following-sibling, attribute, namespace, self, descendant-or-self | Can stream |
| Reverse | parent, ancestor, preceding, preceding-sibling, ancestor-or-self | Require buffering |

Forward axes traverse nodes in document order, allowing evaluation as events
arrive. Reverse axes require access to preceding nodes, triggering grounded
or full-document buffering based on scope.

### Dual-Mode Node Representation

Since Gonzalez is event-driven, nodes have two representations:

- **StreamingNode** - Lightweight wrapper during streaming (forward-only)
- **BufferedNode** - Full XPath node interface over captured SAX events

The `GroundedExecutor` manages transitions between modes, buffering subtrees
only when required for reverse axis navigation.

### Runtime Execution

Transformation execution uses:

- **TransformContext** - Maintains transformation state (current node, variables, etc.)
- **AccumulatorManager** - Manages all accumulator state during streaming
- **StreamingContext** - Context for `xsl:stream` execution
- **TemplateMatcher** - Selects best matching template rule for a node
- **OutputHandler** - Receives transformation output (SAX events or stream)
- **VariableScope** - Manages variable bindings with proper scoping
- **SAXEventBuffer** - Captures and replays SAX event subtrees when buffering is needed

## JAXP Integration

The transformer integrates with standard JAXP APIs:

```java
// Create factory
TransformerFactory factory = TransformerFactory.newInstance(
    "org.bluezoo.gonzalez.transform.GonzalezTransformerFactory", null);

// Compile stylesheet (reusable)
Templates templates = factory.newTemplates(new StreamSource("style.xsl"));

// Create transformer instance
Transformer transformer = templates.newTransformer();

// Transform
transformer.transform(
    new StreamSource("input.xml"),
    new StreamResult("output.xml"));
```

### SAX Pipeline Integration

For streaming transformations:

```java
// Create SAX transformer handler
TransformerHandler handler = factory.newTransformerHandler(templates);
handler.setResult(new StreamResult(outputStream));

// Feed SAX events (e.g., from Gonzalez parser)
parser.setContentHandler(handler);
parser.parse(inputSource);
```

## Features

### XSLT 3.0 Streaming Elements

| Element | Description |
|---------|-------------|
| `xsl:accumulator` | Define streaming accumulators |
| `xsl:accumulator-rule` | Accumulator update rules (pre/post-descent) |
| `xsl:stream` | Explicit streaming entry point |
| `xsl:iterate` | Stateful streaming iteration |
| `xsl:next-iteration` | Continue iteration with new param values |
| `xsl:break` | Early termination of iteration |
| `xsl:on-completion` | Final processing after iteration |
| `xsl:mode` | Mode declarations with streamable attribute |
| `xsl:fork` | Parallel streaming branches |

### XSLT 2.0 Elements

| Element | Description |
|---------|-------------|
| `xsl:result-document` | Multiple output documents |
| `xsl:for-each-group` | Grouping with group-by/group-adjacent |
| `xsl:sequence` | Sequence construction |

### XSLT 1.0 Elements

| Element | Status | Notes |
|---------|--------|-------|
| `xsl:stylesheet` | Complete | Including version detection |
| `xsl:template` | Complete | Match patterns and named templates |
| `xsl:apply-templates` | Complete | With select and mode |
| `xsl:call-template` | Complete | Named template invocation |
| `xsl:value-of` | Complete | Including disable-output-escaping |
| `xsl:text` | Complete | Literal text output |
| `xsl:element` | Complete | Dynamic element creation |
| `xsl:attribute` | Complete | Dynamic attribute creation |
| `xsl:variable` | Complete | Local and global variables |
| `xsl:param` | Complete | Template and stylesheet parameters |
| `xsl:if` | Complete | Conditional processing |
| `xsl:choose/when/otherwise` | Complete | Multi-way conditional |
| `xsl:for-each` | Complete | Iteration with sorting |
| `xsl:sort` | Complete | Multiple sort keys |
| `xsl:copy` | Complete | Shallow copy |
| `xsl:copy-of` | Complete | Deep copy |
| `xsl:comment` | Complete | Comment output |
| `xsl:processing-instruction` | Complete | PI output |
| `xsl:number` | Complete | value, level, count, from, format (1/a/A/i/I), grouping |
| `xsl:output` | Complete | Method, encoding, indent |
| `xsl:strip-space` | Complete | Whitespace stripping |
| `xsl:preserve-space` | Complete | Whitespace preservation |
| `xsl:key` | Complete | Key definitions and lookup |
| `xsl:decimal-format` | Complete | Number formatting patterns |
| `xsl:attribute-set` | Complete | Reusable attribute groups |
| `xsl:import` | Complete | Stylesheet import |
| `xsl:include` | Complete | Stylesheet inclusion |
| `xsl:namespace-alias` | Partial | Basic support |
| `xsl:fallback` | Complete | Forward-compatible processing |

### XPath Functions

#### Node Set Functions
- `last()`, `position()`, `count()`, `id()`, `local-name()`, `namespace-uri()`, `name()`

#### String Functions
- `string()`, `concat()`, `starts-with()`, `contains()`, `substring-before()`,
  `substring-after()`, `substring()`, `string-length()`, `normalize-space()`,
  `translate()`

#### Boolean Functions
- `boolean()`, `not()`, `true()`, `false()`, `lang()`

#### Number Functions
- `number()`, `sum()`, `floor()`, `ceiling()`, `round()`

#### XSLT 1.0 Functions
- `document()`, `key()`, `format-number()`, `current()`, `unparsed-entity-uri()`,
  `generate-id()`, `system-property()`, `element-available()`, `function-available()`

#### XSLT 2.0 Grouping Functions
- `current-group()`, `current-grouping-key()`

#### XSLT 3.0 Accumulator Functions
- `accumulator-before()`, `accumulator-after()`

### Forward-Compatible Processing

When a stylesheet declares `version="2.0"` or higher, the processor:

1. Ignores unrecognized XSLT elements (executes `xsl:fallback` if present)
2. Parses XPath 2.0 syntax (with XPath 1.0 semantics where possible)
3. Continues processing rather than failing on unknown constructs

## Streamability Analysis

The `StreamabilityAnalyzer` classifies expressions and templates at compile time:

### Expression Streamability

| Classification | Description | Example |
|----------------|-------------|---------|
| MOTIONLESS | No navigation, same result anywhere | `$var`, `"text"`, `3.14` |
| CONSUMING | Forward traversal | `child::*`, `.//item` |
| GROUNDED | Needs subtree but not document | `preceding-sibling::*` within element |
| FREE_RANGING | Needs full document | `//item`, `preceding::*` |

### Automatic Streaming for XSLT 1.0/2.0

The transformer analyzes XSLT 1.0/2.0 stylesheets and creates internal
accumulators for patterns that can be streamed:

```xml
<!-- Original XSLT 1.0 -->
<xsl:template match="item">
  <item position="{position()}">
    <count><xsl:value-of select="count(preceding-sibling::item)"/></count>
  </item>
</xsl:template>

<!-- Automatically converted to use internal accumulators -->
<!-- position() → counter accumulator -->
<!-- count(preceding-sibling::item) → sibling counter accumulator -->
```

This allows existing stylesheets to benefit from streaming without modification.

## Test Methodology

### Conformance Testing

The transformer is tested against the **W3C XSLT 3.0 Conformance Test Suite**
and optionally the **W3C XPath/XQuery 3.1 Test Suite**, filtered for
non-schema-aware tests. The test suites include:

- XSLT 1.0, 2.0, and 3.0 tests
- XPath 2.0 and 3.1 expression tests
- Tests from multiple sources (W3C, OASIS, vendor contributions)

### Extracting Tests

Before running conformance tests, extract them from the W3C repositories:

```bash
# Clone the test suites (one-time setup)
git clone https://github.com/w3c/xslt30-test ../xslt30-test
git clone https://github.com/w3c/qt3tests ../qt3tests

# Extract XSLT and XPath tests
./tools/extract-xslt-tests.sh ../xslt30-test ../qt3tests
```

The extraction script:
- Includes XSLT 1.0, 2.0, and 3.0 tests
- Includes XPath 2.0/3.1 tests (from qt3tests)
- Excludes schema-aware tests requiring XSD type support
- Creates a `xsltconf/` directory with organized test files

### Running Tests

```bash
# Compile and run all conformance tests
ant test-xslt

# Run tests for a specific XSLT version
ant test-xslt -Dxslt.version=1.0
ant test-xslt -Dxslt.version=2.0
ant test-xslt -Dxslt.version=3.0
```

## Performance

### Design Considerations

The transformer is designed for:

1. **Streaming by default** - Accumulators handle state without buffering
2. **Lazy evaluation** - XPath node sets are evaluated lazily
3. **Compiled patterns** - Match patterns are pre-compiled for efficient matching
4. **Pooled objects** - Reuse of QName and other frequently-allocated objects

### Memory Characteristics

- **Compiled stylesheet** - Immutable, can be shared across threads
- **Transformation context** - Per-transformation state, not thread-safe
- **Accumulator state** - O(depth) stack for accumulator values
- **Node representation** - Lightweight wrappers over SAX events where possible

### Benchmarking

Benchmark infrastructure is available in the `benchmark/` directory. Key metrics:

- Stylesheet compilation time
- Transformation throughput (documents/second)
- Memory usage during transformation
- Streaming efficiency (bytes buffered vs document size)

## Extension Mechanisms

### Extension Functions

Custom XPath functions can be registered:

```java
ExtensionRegistry registry = new ExtensionRegistry();
registry.registerFunction("http://example.com/fn", "myFunc", 
    new MyExtensionFunction());
```

### Extension Elements

Custom XSLT elements (planned):

```java
registry.registerElement("http://example.com/xsl", "custom",
    new MyExtensionElement());
```

## Limitations

### Current Limitations

1. **No schema awareness** - Type information is not available (XSD types not recognized)
2. **`xsl:number`** - Basic formatting; some advanced features (ordinals, words) not implemented

### By Design

1. **No DOM output** - Gonzalez focuses on streaming; use SAX result
2. **External entity blocking** - Same limitation as Gonzalez parser
3. **No Reader/character stream input** - Gonzalez requires byte streams for 
   encoding detection (BOM, XML declaration). Use `InputStream` or system ID instead of `Reader`
4. **No higher-order functions** - Complex feature with limited streaming benefit
5. **No maps/arrays** - XPath 3.1 feature that adds significant complexity

## Future Work

### XSLT Enhancements

- [ ] Advanced `xsl:number` formatting (ordinals, words, letter-value)
- [ ] Performance optimization passes

### Future Considerations

- [ ] Schema-aware processing (when Gonzalez adds XSD support)
- [ ] Higher-order functions (XSLT 3.0) - complex, limited streaming benefit
- [ ] Maps and arrays (XPath 3.1) - adds significant complexity

## Related Documentation

- [Gonzalez README](README.md) - Main project documentation
- [Javadoc](https://cpkb-bluezoo.github.io/gonzalez/doc/) - API documentation
- [XSLT 1.0 Specification](https://www.w3.org/TR/xslt) - W3C XSLT 1.0 specification
- [XSLT 3.0 Specification](https://www.w3.org/TR/xslt-30/) - W3C XSLT 3.0 specification
- [XPath 1.0 Specification](https://www.w3.org/TR/xpath/) - W3C XPath 1.0 specification
- [XPath 3.1 Specification](https://www.w3.org/TR/xpath-31/) - W3C XPath 3.1 specification
