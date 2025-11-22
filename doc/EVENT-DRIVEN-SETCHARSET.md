# Event-Driven setCharset() Implementation - Complete

## Overview

Refactored the charset switching logic to follow an event-driven pattern. The `setCharset()` method is now called after the entire XML declaration is parsed, ensuring that charset switching doesn't interfere with parsing the declaration itself.

## The Problem

**Initial approach (incorrect):**
```java
// In xmlDeclReceive(), when processing encoding attribute:
else if ("encoding".equals(ctx.currentName)) {
    setCharset(value);  // ❌ Called immediately!
}
```

**Why this is wrong:**
- If we switch charsets mid-declaration, it could affect parsing the rest of the declaration
- The decoder would be changed while still processing the declaration content
- The byte buffer position would be reset, corrupting the state

## The Solution

**Store the encoding value, call setCharset() after parsing complete:**

### Step 1: Store in Context
```java
// In xmlDeclReceive(), when processing encoding attribute:
else if ("encoding".equals(ctx.currentName)) {
    ctx.encoding = value;  // ✅ Store for later
}
```

### Step 2: Call After Parsing Complete
```java
// At the end of extractXMLDeclarationAttributes():
if (ctx.encoding != null) {
    setCharset(ctx.encoding, ctx.byteBuffer);  // ✅ Called after full parse
}
```

### Step 3: setCharset() Handles Switching
```java
private void setCharset(String encodingName, ByteBuffer byteBuffer) throws SAXException {
    // Resolve charset
    Charset declaredCharset = Charset.forName(encodingName);
    
    // Validate (BOM mismatch check)
    // ...
    
    // Switch if needed
    if (bomCharset == null && !charset.equals(declaredCharset)) {
        charset = declaredCharset;
        decoder = charset.newDecoder();
        byteBuffer.position(postXMLDeclBytePosition);  // Reset to after declaration
        charBuffer.clear();
        charUnderflow.clear();
    }
}
```

## Flow Diagram

```
receive(ByteBuffer data)
  ↓
parseXMLDeclaration(data)
  ↓
extractXMLDeclarationAttributes(declContent, data)
  ↓
[Tokenization loop]
  ↓
nextXMLDeclToken() → Token
  ↓
xmlDeclReceive(token, buffer, ctx)
  ├─ Token.NAME → ctx.currentName = "encoding"
  ├─ Token.CDATA → ctx.encoding = "ISO-8859-1"  // STORED, not processed yet
  └─ ...continue parsing...
  ↓
[After loop completes]
  ↓
if (ctx.encoding != null)
    setCharset(ctx.encoding, ctx.byteBuffer)  // ← Called here!
      ↓
      Validate charset
      ↓
      Switch decoder
      ↓
      Reset buffer position
      ↓
      Clear char buffers
```

## Timing is Critical

### Example: Declaration with Multiple Attributes

```xml
<?xml version="1.0" encoding="ISO-8859-1" standalone="yes"?>
```

**Token sequence:**
1. `S` - whitespace
2. `NAME` - "version"
3. `EQ` - =
4. `QUOT` - "
5. `CDATA` - "1.0"
6. `QUOT` - "
7. `S` - whitespace
8. **`NAME`** - "encoding"  ← ctx.currentName = "encoding"
9. `EQ` - =
10. `QUOT` - "
11. **`CDATA`** - "ISO-8859-1"  ← ctx.encoding = "ISO-8859-1" (stored)
12. `QUOT` - "
13. `S` - whitespace
14. `NAME` - "standalone"  ← Still parsing! Can't switch charset yet!
15. `EQ` - =
16. `APOS` - '
17. `CDATA` - "yes"
18. `APOS` - '
19. **[End of loop]**
20. **setCharset() called** ← Now it's safe!

If we called `setCharset()` at token #11, we'd switch charsets while still parsing tokens #13-18, which is wrong!

## Data Structures

### XMLDeclContext

```java
private static class XMLDeclContext {
    XMLDeclState state = ...;
    String currentName = null;
    short seenAttributes = 0;
    String encoding = null;        // ← Store encoding value
    ByteBuffer byteBuffer = null;  // ← For resetting position
}
```

**Purpose of each field:**
- `state` - Current parsing state (EXPECT_NAME, EXPECT_EQ, etc.)
- `currentName` - Last attribute name seen (version/encoding/standalone)
- `seenAttributes` - Bit flags: which attributes have been processed
- `encoding` - Encoding value to pass to `setCharset()` after parsing
- `byteBuffer` - Reference to byte buffer for position reset during charset switch

## Three Cases Handled by setCharset()

### Case 1: BOM + Mismatched Encoding = ERROR

```xml
<!-- File with UTF-16LE BOM (FF FE) -->
<?xml version="1.0" encoding="UTF-8"?>
```

**Result:**
```
SAXException: Encoding mismatch: BOM indicates UTF-16LE but XML declaration specifies UTF-8
```

### Case 2: No BOM + Different Encoding = SWITCH

```xml
<!-- File with no BOM, defaults to UTF-8 -->
<?xml version="1.0" encoding="ISO-8859-1"?>
<doc>...</doc>
```

**Actions:**
1. Parse declaration using UTF-8 (7-bit clean for ASCII)
2. Extract `encoding="ISO-8859-1"`
3. After parsing complete, call `setCharset("ISO-8859-1", buffer)`
4. Switch: `charset = ISO-8859-1`, `decoder = new decoder`
5. Reset: `buffer.position(postXMLDeclBytePosition)`
6. Clear character buffers
7. Re-decode remaining document (`<doc>...</doc>`) with ISO-8859-1

### Case 3: BOM + Matching Encoding = SUCCESS

```xml
<!-- File with UTF-16LE BOM (FF FE) -->
<?xml version="1.0" encoding="UTF-16"?>
```

**Result:**
- Charsets compatible (UTF-16 generic matches UTF-16LE specific)
- No action needed, continue

## Event-Driven Pattern Benefits

### 1. **Separation of Concerns**
- Tokenization: Extract tokens from buffer
- Token processing: Update state, store values
- Action execution: Call methods after parsing complete

### 2. **Deferred Execution**
- Store values during parsing
- Execute actions after parsing
- Prevents state corruption

### 3. **Cleaner Code**
- `xmlDeclReceive()` just stores values
- `extractXMLDeclarationAttributes()` orchestrates
- `setCharset()` handles charset logic independently

### 4. **Extensibility**
- Easy to add more post-processing actions
- Can validate all attributes before taking action
- Can perform actions in correct order

## Method Signatures

### extractXMLDeclarationAttributes()
```java
private void extractXMLDeclarationAttributes(CharBuffer declBuffer, ByteBuffer byteBuffer) 
        throws SAXException
```
- Returns `void` (no longer returns encoding string)
- Takes `ByteBuffer` parameter for charset switching

### xmlDeclReceive()
```java
private void xmlDeclReceive(Token token, CharBuffer buffer, XMLDeclContext ctx) 
        throws SAXException
```
- Stores encoding in `ctx.encoding`
- Does NOT call `setCharset()`

### setCharset()
```java
private void setCharset(String encodingName, ByteBuffer byteBuffer) throws SAXException
```
- Takes encoding name and byte buffer
- Called after parsing complete
- Handles all three cases (error/switch/success)

## Execution Order

1. **`parseXMLDeclaration()`**
   - Decodes bytes to characters
   - Finds `<?xml...?>`
   - Saves `postXMLDeclBytePosition`

2. **`extractXMLDeclarationAttributes()`**
   - Tokenizes declaration content
   - Calls `xmlDeclReceive()` for each token
   - **After loop:** calls `setCharset()` if encoding was found

3. **`xmlDeclReceive()`**
   - Processes each token
   - Stores `version`, `encoding`, `standalone` values
   - Does **not** execute charset switch

4. **`setCharset()`**
   - Resolves charset name
   - Validates compatibility
   - Switches decoder if needed
   - Resets buffer position

## Testing Scenarios

### Valid: Charset Switch After Full Parse

```xml
<?xml version="1.0" encoding="ISO-8859-1" standalone="yes"?>
```

**Expected:**
1. Parse all attributes
2. Validate all present and in order
3. Call `setCharset("ISO-8859-1")`
4. Switch successful

### Invalid: Would Have Failed with Immediate Switch

```xml
<?xml version="1.0" encoding="INVALID-CHARSET" standalone="yes"?>
```

**With immediate switch (wrong):**
- Would fail at `encoding` attribute, never parse `standalone`

**With deferred switch (correct):**
- Parse all attributes successfully
- Validate syntax is correct
- **Then** fail with clear error: "Unsupported encoding: INVALID-CHARSET"

## Summary

✅ **Event-driven pattern** - Actions deferred until parsing complete  
✅ **Store-then-execute** - `ctx.encoding` stored, `setCharset()` called later  
✅ **No mid-parse switching** - Charset switch only after full declaration parsed  
✅ **Clean separation** - Tokenization, processing, and actions are separate  
✅ **Proper error handling** - All attributes validated before switching  
✅ **Buffer management** - Position reset after complete parse  

This pattern will be used throughout Gonzalez parsing: tokenize → store state → execute actions after complete parse! 🎯


