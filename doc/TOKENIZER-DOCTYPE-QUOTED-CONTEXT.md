# Tokenizer Fix: DOCTYPE_QUOTED Context for Parameter Entity References

**Date**: 2025-11-15  
**Status**: ✅ Complete

## Problem

The tokenizer was not properly emitting `PARAMETERENTITYREF` tokens for parameter entity references inside quoted strings in the DTD, such as:

```xml
<!ENTITY % combined "type is %common;">
```

Instead of emitting separate tokens:
- `QUOT`
- `CDATA("type is ")`
- `PARAMETERENTITYREF("common")`
- `QUOT`

It was emitting:
- `QUOT`
- `CDATA("type is %common;")`
- `QUOT`

## Root Cause

When the tokenizer encountered a quote character in `DOCTYPE` or `DOCTYPE_INTERNAL` context, it switched to `ATTR_VALUE` context (lines 2036-2040 in `updateContext()`). 

In `ATTR_VALUE` context, `tryEmitCDATA()` only stops at `<`, `&`, `>`, and the matching quote - it does NOT stop at `%`. This caused parameter entity references to be treated as literal text.

## Solution

Added a new tokenizer context: `DOCTYPE_QUOTED`

### Changes Made

1. **New TokenizerContext** (`XMLTokenizer.java` line 152):
   ```java
   DOCTYPE_QUOTED  // Inside quoted string in DOCTYPE (entity values, public/system IDs) - like ATTR_VALUE but stops at %
   ```

2. **Context Switching** (`XMLTokenizer.java` line 2040):
   - Changed from `context = TokenizerContext.ATTR_VALUE;`
   - To: `context = TokenizerContext.DOCTYPE_QUOTED;`
   - When entering quoted strings in DOCTYPE context

3. **CDATA Stop Characters** (`XMLTokenizer.java` lines 1922-1927):
   - Added `DOCTYPE_QUOTED` case in `tryEmitCDATA()`
   - Stops at: `<`, `&`, `%`, and the matching quote
   - The `%` is critical for recognizing parameter entity references

4. **Parameter Entity Reference Handling** (`XMLTokenizer.java` lines 1014-1016):
   - Extended the check for `%` to include `DOCTYPE_QUOTED` context
   - Now tries to emit `PARAMETERENTITYREF` in all DOCTYPE contexts

5. **Default Case Handling** (`XMLTokenizer.java` line 1273):
   - Added `DOCTYPE_QUOTED` to the list of CDATA-emitting contexts

## Result

The tokenizer now correctly emits `PARAMETERENTITYREF` tokens inside DOCTYPE quoted strings:

```
<!ENTITY % combined "type is %common;">
```

Tokens emitted:
- `NAME("ENTITY")`
- `PERCENT`
- `NAME("combined")`  
- `QUOT`
- `CDATA("type is ")`
- **`PARAMETERENTITYREF("common")`** ← Now correctly emitted!
- `QUOT`

## DTDParser Storage

The `DTDParser` correctly stores these as `ParameterEntityReference` objects:

```java
replacementText: [
    String("type is "),
    ParameterEntityReference("common")
]
```

## Testing

- ✅ All existing tests pass (attribute values, general entities)
- ✅ Parameter entity references are correctly tokenized
- ✅ Parameter entity references are correctly stored by DTDParser

## Next Steps

Now that parameter entity references are properly stored, the next phase is to implement **post-processing expansion** after the DOCTYPE declaration is complete. This is necessary because parameter entities can have forward references (an entity can reference a parameter entity declared later in the DTD).

## Related Files

- `src/org/bluezoo/gonzalez/XMLTokenizer.java` - Tokenizer with DOCTYPE_QUOTED context
- `src/org/bluezoo/gonzalez/DTDParser.java` - Stores ParameterEntityReference objects
- `src/org/bluezoo/gonzalez/ParameterEntityReference.java` - Reference class
- `src/org/bluezoo/gonzalez/EntityExpansionHelper.java` - Expansion logic (to be used in post-processing)

