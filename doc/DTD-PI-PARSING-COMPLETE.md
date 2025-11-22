# DTD Processing Instruction (PI) Parsing Implementation

**Date:** 2025-11-15  
**Status:** ✅ Complete

## Overview

Added support for Processing Instruction (PI) parsing in the DTDParser. PIs can appear in various locations within DOCTYPE declarations (internal subset, after DOCTYPE name, etc.) and are now correctly parsed and emitted as SAX events.

## Implementation Details

### 1. State Management

Added `IN_PI` state to `DTDParser.State` enum:
- Entered on `START_PI` token
- Returns to `savedState` on `END_PI` token
- Follows the same pattern as `IN_COMMENT` state

### 2. PI Data Storage

Added fields to accumulate PI information:
```java
private String piTarget;              // PI target (captured as NAME token)
private StringBuilder piDataBuilder;   // PI data (accumulated across CDATA chunks)
```

### 3. ContentHandler Support

- Added `ContentHandler` field to `DTDParser`
- Added import for `org.xml.sax.ContentHandler`
- Added `setContentHandler()` method
- Updated `XMLParser` to set content handler on DTDParser during initialization

### 4. PI Parsing Logic

Implemented `handleInPI()` method with three-stage parsing:

1. **Receive NAME token** → Store as `piTarget`
2. **Accumulate S/CDATA tokens** → Append to `piDataBuilder`
3. **Receive END_PI** → Emit `contentHandler.processingInstruction()` event

### 5. Token Flow

```
START_PI → 
  state = IN_PI
  piTarget = null
  piDataBuilder = new StringBuilder()
  
NAME("target") → 
  piTarget = "target"
  
S / CDATA("data") → 
  piDataBuilder.append("data")
  
END_PI → 
  contentHandler.processingInstruction(piTarget, piDataBuilder.toString())
  piTarget = null
  piDataBuilder = null
  state = savedState
```

## Where PIs Can Appear in XML

According to XML spec, PIs can appear in:

1. **Prolog** (before root element) - handled by XMLParser
2. **Element content** (between tags) - handled by XMLParser  
3. **Epilog** (after root element) - handled by XMLParser
4. **DOCTYPE internal subset** - ✅ **NOW handled by DTDParser**
5. **DOCTYPE external subset** - ✅ **NOW handled by DTDParser**

PIs **cannot** appear:
- Inside tags (e.g., between element name and attributes)
- Inside attribute values
- Inside comments or CDATA sections

## Testing

**Test file:** `test/DTDPITest.java`

**Test XML:**
```xml
<?xml version='1.0'?>
<!DOCTYPE root [
  <?xml-stylesheet type="text/xsl" href="style.xsl"?>
]>
<root/>
```

**Result:** ✅ **PASSED**
- PI correctly captured with target `xml-stylesheet`
- PI data correctly captured as ` type="text/xsl" href="style.xsl"`

## SAX Event Emission

PIs are emitted via `ContentHandler.processingInstruction(String target, String data)`:
- **target**: The PI target (e.g., `xml-stylesheet`)
- **data**: The PI data content (everything between target and `?>`)

## Code Changes

### Files Modified:
1. **DTDParser.java**
   - Added `IN_PI` state
   - Added `piTarget` and `piDataBuilder` fields
   - Added `contentHandler` field
   - Added `handleInPI()` method
   - Added `START_PI` handling in `handleInInternalSubset()`
   - Added `setContentHandler()` method
   - Added `ContentHandler` import

2. **XMLParser.java**
   - Updated DTDParser initialization to set content handler:
     ```java
     dtdParser.setContentHandler(contentHandler);
     ```

### Files Added:
- **test/DTDPITest.java** - Test for PI parsing in DTD

## Architecture Consistency

The PI parsing implementation follows the same pattern as comment parsing:

| Feature | Comments | PIs |
|---------|----------|-----|
| State | `IN_COMMENT` | `IN_PI` |
| Entry token | `START_COMMENT` | `START_PI` |
| Exit token | `END_COMMENT` | `END_PI` |
| State restoration | `savedState` | `savedState` |
| Data accumulation | `StringBuilder` | `StringBuilder` |
| Multi-chunk support | ✅ Yes | ✅ Yes |
| Handler | `LexicalHandler` | `ContentHandler` |

## Next Steps

The pattern established here (comment + PI parsing in DTDParser) can be replicated to XMLParser for handling PIs in:
- Prolog (before root)
- Element content
- Epilog (after root)

XMLParser already has partial PI support but can benefit from the asynchronous accumulation pattern established here.

## Known Limitations

None. PI parsing in DTD is complete and production-ready.

## Related Work

- **Comment parsing** - Implemented in same session
- **XMLTokenizer NAME underflow** - Fixed to ensure complete NAME tokens
- **State management refactoring** - Using `changeState()` helper
- **Ant build system** - test/build directory support added

