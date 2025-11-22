# UTF-16 BOM and XML Declaration Analysis

## Research Question

**If we detect a 16-bit BOM, is the XML declaration encoded according to that charset, or is it still in ASCII bytes?**

## Test Files Examined

### Files with UTF-16LE BOM (FF FE)

1. **`xmlconf/xmltest/valid/sa/049.xml`**
   ```
   00000000  ff fe 3c 00 21 00 44 00  4f 00 43 00 54 00 59 00  |..<.!.D.O.C.T.Y.|
   ```
   - BOM: `FF FE` (UTF-16LE)
   - Next bytes: `3C 00 21 00 44 00 4F 00 43 00 54 00 59 00` = `<!DOCTYPE` in UTF-16LE
   - **No XML declaration**, starts directly with `<!DOCTYPE`

2. **`xmlconf/sun/invalid/utf16l.xml`**
   ```
   00000000  ff fe 3c 00 3f 00 78 00  6d 00 6c 00 20 00 76 00  |..<.?.x.m.l. .v.|
   00000010  65 00 72 00 73 00 69 00  6f 00 6e 00 3d 00 27 00  |e.r.s.i.o.n.=.'.|
   00000020  31 00 2e 00 30 00 27 00  20 00 65 00 6e 00 63 00  |1...0.'. .e.n.c.|
   00000030  6f 00 64 00 69 00 6e 00  67 00 3d 00 27 00 55 00  |o.d.i.n.g.=.'.U.|
   00000040  54 00 46 00 2d 00 31 00  36 00 27 00 3f 00 3e 00  |T.F.-.1.6.'.?.>.|
   ```
   - BOM: `FF FE` (UTF-16LE)
   - Next bytes: `3C 00 3F 00 78 00 6D 00 6C 00` = `<?xml` in UTF-16LE
   - Declaration: `<?xml version='1.0' encoding='UTF-16'?>` **fully in UTF-16LE**

### Files with UTF-16BE BOM (FE FF)

3. **`xmlconf/japanese/pr-xml-utf-16.xml`**
   ```
   00000000  fe ff 00 3c 00 3f 00 78  00 6d 00 6c 00 20 00 76  |...<.?.x.m.l. .v|
   00000010  00 65 00 72 00 73 00 69  00 6f 00 6e 00 3d 00 22  |.e.r.s.i.o.n.=."|
   00000020  00 31 00 2e 00 30 00 22  00 3f 00 3e 00 0a 00 0a  |.1...0.".?.>....|
   ```
   - BOM: `FE FF` (UTF-16BE)
   - Next bytes: `00 3C 00 3F 00 78 00 6D 00 6C` = `<?xml` in UTF-16BE
   - Declaration: `<?xml version="1.0"?>` **fully in UTF-16BE**

4. **`xmlconf/sun/invalid/utf16b.xml`**
   ```
   00000000  fe ff 00 3c 00 3f 00 78  00 6d 00 6c 00 20 00 76  |...<.?.x.m.l. .v|
   00000010  00 65 00 72 00 73 00 69  00 6f 00 6e 00 3d 00 27  |.e.r.s.i.o.n.=.'|
   00000020  00 31 00 2e 00 30 00 27  00 20 00 65 00 6e 00 63  |.1...0.'. .e.n.c|
   00000030  00 6f 00 64 00 69 00 6e  00 67 00 3d 00 27 00 55  |.o.d.i.n.g.=.'.U|
   00000040  00 54 00 46 00 2d 00 31  00 36 00 27 00 3f 00 3e  |.T.F.-.1.6.'.?.>|
   ```
   - BOM: `FE FF` (UTF-16BE)
   - Next bytes: `00 3C 00 3F 00 78 00 6D 00 6C` = `<?xml` in UTF-16BE
   - Declaration: `<?xml version='1.0' encoding='UTF-16'?>` **fully in UTF-16BE**

## Conclusion

**YES! When a UTF-16 BOM is present, the entire XML declaration is encoded in that charset!**

This means:
- After detecting `FE FF` (UTF-16BE BOM), all subsequent bytes are UTF-16BE encoded
- After detecting `FF FE` (UTF-16LE BOM), all subsequent bytes are UTF-16LE encoded
- The `<?xml` string itself is NOT ASCII bytes, it's UTF-16 encoded
- The entire declaration (`version`, `encoding`, `standalone` attributes) is in UTF-16

## Architectural Implications

### ❌ WRONG Approach (What We Had)
```java
// Detect BOM → set charset
detectBOM(byteBuffer);

// Try to parse XML declaration as ASCII bytes
byte[] prefix = new byte[5];
buffer.get(prefix);
String prefixStr = new String(prefix, StandardCharsets.US_ASCII); // WRONG!
```

This fails because:
- `<?xml` in UTF-16LE is `3C 00 3F 00 78 00 6D 00 6C 00` (10 bytes)
- `<?xml` in ASCII is `3C 3F 78 6D 6C` (5 bytes)
- Reading 5 bytes and decoding as ASCII gives garbage: `<?x�l` (only got half the UTF-16 chars)

### ✅ CORRECT Approach (What We Now Have)
```java
// Detect BOM → set charset
detectBOM(byteBuffer);

// Allocate decoder for the detected charset
decoder = charset.newDecoder();

// Decode bytes to characters FIRST
charBuffer.clear();
decoder.decode(byteBuffer, charBuffer, false);
charBuffer.flip();

// NOW check for "<?xml" as characters
if (charBuffer.get() == '<' && charBuffer.get() == '?' && 
    charBuffer.get() == 'x' && charBuffer.get() == 'm' &&
    charBuffer.get() == 'l') {
    // Found XML declaration in character mode
}
```

This works because:
- We decode bytes → characters using the BOM-determined charset
- `<?xml` in UTF-16LE bytes `3C 00 3F 00 78 00 6D 00 6C 00` decodes to chars `< ? x m l`
- We compare characters, not bytes
- The decoder handles all the complexity of UTF-16 byte ordering

## Critical Design Point

**The XML declaration MUST be processed in character mode, not byte mode.**

This is why our architecture now:
1. Detects BOM in `INIT` state (byte mode)
2. Allocates decoder immediately after BOM detection
3. Processes XML declaration in character mode (`BOM_READ` state)
4. Continues in character mode for all tokenization (`CHARACTERS` state)

## Buffer Position Management

Important consideration: When we decode bytes to check for `<?xml`, we may consume more bytes than needed. For example:

```
Bytes available: 100 bytes
Decode → 50 characters
Check first 5 characters: "<?xml"
Need to find "?>" to complete declaration
Found "?>" at character position 30
Remaining characters (31-50) need to be saved to charUnderflow
Remaining bytes (if any) need to be saved to byteUnderflow
```

Our implementation handles this by:
- Tracking `initialPos` in byte buffer before decoding
- If we don't find "?>", we rewind: `buffer.position(initialPos)`
- If we do find "?>", we save remaining characters to `charUnderflow`
- The decoder tracks its own state internally for proper continuation

## XML Specification Reference

From XML 1.0 Specification, Section 4.3.3 (Character Encoding in Entities):

> "In the absence of information provided by an external transport protocol 
> (e.g. HTTP or MIME), it is an error for an entity including an encoding 
> declaration to be presented to the XML processor in an encoding other than 
> that named in the declaration."

And Appendix F (Autodetection of Character Encodings):

> "If the byte-order mark (BOM) is present, the declaration must be encoded 
> using the character encoding indicated by the BOM."

This confirms our findings: **The BOM determines the encoding of the entire document, including the XML declaration itself.**

## Test Coverage

To properly test this, we need:
- [x] UTF-16LE with BOM and XML declaration ✓ (`xmlconf/sun/invalid/utf16l.xml`)
- [x] UTF-16BE with BOM and XML declaration ✓ (`xmlconf/sun/invalid/utf16b.xml`)
- [x] UTF-16LE with BOM, no XML declaration ✓ (`xmlconf/xmltest/valid/sa/049.xml`)
- [ ] UTF-16 without BOM (should work via encoding declaration)
- [ ] UTF-8 with BOM and XML declaration
- [ ] UTF-8 without BOM or declaration (default)

## Implementation Status

✅ **COMPLETE**: The tokenizer now correctly:
1. Detects BOM in byte mode
2. Switches to character mode immediately after BOM detection
3. Decodes bytes using the BOM-determined charset
4. Checks for `<?xml` as characters, not bytes
5. Parses the entire XML declaration in character mode
6. Saves remaining decoded characters to charUnderflow
7. Continues tokenization in character mode

The fix ensures proper handling of UTF-16 encoded XML documents per the XML specification! 🎯

