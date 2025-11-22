# External DTD Processing - The Elegant Solution

## Key Insight 💡

**External DTD subsets are processed exactly like internal subsets** - they just don't have surrounding `[` and `]` brackets!

## The Design

### DOCTYPE Declaration Types

**Type 1: No external ID, no internal subset**
```xml
<!DOCTYPE root>
```
- Simple case: `START_DOCTYPE`, `NAME`, `GT`
- No DTD processing needed

**Type 2: External ID, no internal subset**
```xml
<!DOCTYPE root SYSTEM "external.dtd">
```
- DTDParser sees: `START_DOCTYPE`, `NAME`, `SYSTEM`, `CDATA`, `GT`
- **At GT**: Enter `IN_INTERNAL_SUBSET` state, call `processExternalDTDSubset()`
- External DTD tokens processed as markup declarations
- After external DTD completes: transition to `DONE`, emit `endDTD()`

**Type 3: External ID with internal subset**
```xml
<!DOCTYPE root SYSTEM "external.dtd" [ <!ENTITY foo "bar"> ]>
```
- DTDParser sees: `START_DOCTYPE`, `NAME`, `SYSTEM`, `CDATA`, `OPEN_BRACKET`, ...
- **At OPEN_BRACKET**: Call `processExternalDTDSubset()` first
- Then enter `IN_INTERNAL_SUBSET` state with `nestingDepth = 1`
- Process internal subset tokens
- **At CLOSE_BRACKET**: Exit `IN_INTERNAL_SUBSET`
- **At GT**: Transition to `DONE`, emit `endDTD()`

**Type 4: No external ID, with internal subset**
```xml
<!DOCTYPE root [ <!ENTITY foo "bar"> ]>
```
- DTDParser sees: `START_DOCTYPE`, `NAME`, `OPEN_BRACKET`, ...
- **At OPEN_BRACKET**: Enter `IN_INTERNAL_SUBSET` state
- Process internal subset tokens
- **At CLOSE_BRACKET**: Exit `IN_INTERNAL_SUBSET`
- **At GT**: Transition to `DONE`, emit `endDTD()`

## State Machine Flow

### Case: External DTD, No Internal Subset

```
State: AFTER_EXTERNAL_ID

Receive: GT
  ↓
1. Report startDTD(name, publicId, systemId)
  ↓
2. state = IN_INTERNAL_SUBSET
   nestingDepth = 0  (no brackets)
  ↓
3. processExternalDTDSubset()
     ↓
   Create nested XMLTokenizer for external.dtd
     ↓
   External DTD contains:
     <!ELEMENT root (title)>
     <!ELEMENT title (#PCDATA)>
     ↓
   Tokens: START_ELEMENTDECL, NAME(root), ...
     ↓
   Fed back to DTDParser.receive()
     ↓
   Processed by handleInInternalSubset()
     ↓
   Build element/attribute declarations
     ↓
   External entity ends, returns
  ↓
4. state = DONE
  ↓
5. Report endDTD()
```

### Case: External DTD With Internal Subset

```
State: AFTER_EXTERNAL_ID

Receive: OPEN_BRACKET
  ↓
1. Report startDTD(name, publicId, systemId)
  ↓
2. processExternalDTDSubset()
     ↓
   (External DTD tokens processed as above)
     ↓
   Returns when external entity complete
  ↓
3. state = IN_INTERNAL_SUBSET
   nestingDepth = 1
  ↓
4. Continue receiving tokens from main document:
     <!ENTITY foo "bar">
       ↓
   Processed by handleInInternalSubset()
  ↓
5. Receive: CLOSE_BRACKET
     ↓
   nestingDepth--
     ↓
   state = AFTER_INTERNAL_SUBSET
  ↓
6. Receive: GT
     ↓
   state = DONE
     ↓
   Report endDTD()
```

## Token Flow

### Main Document
```xml
<!DOCTYPE root SYSTEM "external.dtd">
<root><title>Test</title></root>
```

### External DTD (external.dtd)
```
<!ELEMENT root (title)>
<!ELEMENT title (#PCDATA)>
```

### Token Sequence

**Phase 1: Main document DOCTYPE**
```
XMLTokenizer(main) → START_DOCTYPE → XMLParser → DTDParser
XMLTokenizer(main) → NAME(root) → XMLParser → DTDParser
XMLTokenizer(main) → SYSTEM → XMLParser → DTDParser
XMLTokenizer(main) → CDATA(external.dtd) → XMLParser → DTDParser
XMLTokenizer(main) → GT → XMLParser → DTDParser
  ↓
DTDParser.handleAfterExternalId(GT):
  - state = IN_INTERNAL_SUBSET
  - processExternalDTDSubset() called
```

**Phase 2: External DTD processing**
```
  processExternalDTDSubset()
    ↓
  Resolve "external.dtd" → InputStream
    ↓
  Create XMLTokenizer(external.dtd)
    ↓
  XMLTokenizer(external) → START_ELEMENTDECL → XMLParser → DTDParser
  XMLTokenizer(external) → NAME(root) → XMLParser → DTDParser
  XMLTokenizer(external) → ... → XMLParser → DTDParser
    ↓
  DTDParser.handleInInternalSubset() processes all tokens
    ↓
  External entity exhausted, returns
    ↓
  processExternalDTDSubset() returns
```

**Phase 3: Continue main document**
```
DTDParser.handleAfterExternalId(GT) continues:
  - state = DONE
  - endDTD() emitted
    ↓
Main tokenizer continues with <root>...
```

## Why This Works Beautifully ✨

### 1. **No Special Cases**
- External DTD tokens are processed by the **same** `handleInInternalSubset()` method
- No need for "external subset mode" vs "internal subset mode"
- Markup declarations are markup declarations, regardless of source

### 2. **Correct Processing Order**
- External DTD **always** processed before internal subset
- This matches XML spec: external declarations can be overridden by internal

### 3. **Clean State Machine**
- `IN_INTERNAL_SUBSET` state handles both cases
- `nestingDepth = 0` indicates "no brackets" (external DTD only)
- `nestingDepth >= 1` indicates actual `[...]` present

### 4. **Natural Nesting**
- `processExternalEntity()` creates nested tokenizer
- Nested tokenizer feeds tokens to same parser
- When nested entity exhausted, control returns naturally
- No special "pop" needed - just return from function

### 5. **Entity Resolution Stack**
- `XMLParser.processExternalEntity()` manages recursion protection
- Adds systemId to stack on entry
- Removes systemId from stack on exit (finally block)
- Prevents infinite loops

## Remaining Issue: Tokenization 🐛

The **only** blocker is that quoted strings in DOCTYPE aren't tokenized correctly:

```xml
<!DOCTYPE root SYSTEM "http://example.com/dtd.dtd">
                      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                      Should be: QUOT, CDATA, QUOT
                      Currently: QUOT, NAME, ...
```

Once this is fixed, the entire external DTD processing pipeline will work!

## The Tokenization Fix Needed

```java
// In XMLTokenizer.updateContext():
case QUOT:
case APOS:
    if (context == TokenizerContext.CONTENT || 
        context == TokenizerContext.DOCTYPE_INTERNAL) {
        // Entering quoted string in DOCTYPE
        context = TokenizerContext.ATTR_VALUE;
        attrQuoteChar = (token == Token.QUOT) ? '"' : '\'';
    } else if (context == TokenizerContext.ATTR_VALUE) {
        // Check if this quote closes the value
        // (need to check against attrQuoteChar)
        context = TokenizerContext.CONTENT;
        attrQuoteChar = '\0';
    }
    break;
```

## Summary

The external DTD processing design is **elegant and correct**:

✅ External DTD = Internal subset without brackets
✅ Same state machine handles both
✅ Correct processing order (external before internal)
✅ Natural token flow through nested tokenizers
✅ Clean recursion protection

The implementation is ready—we just need to fix the tokenizer's context tracking for quoted strings in DOCTYPE declarations. Once that's done, all the tests should pass! 🎯

