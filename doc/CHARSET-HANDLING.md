# Charset Handling Per XML Specification

## XML Specification Requirements

From XML 1.0 Specification, Appendix F (Character Encoding):

> In an encoding declaration, the values "UTF-8", "UTF-16", "ISO-10646-UCS-2", and 
> "ISO-10646-UCS-4" SHOULD be used for the various encodings and transformations of 
> Unicode / ISO/IEC 10646, the values "ISO-8859-1", "ISO-8859-2", ... "ISO-8859-n" 
> (where n is the part number) SHOULD be used for the parts of ISO 8859, and the 
> values "ISO-2022-JP", "Shift_JIS", and "EUC-JP" SHOULD be used for the various 
> encoded forms of JIS X-0208-1997.

> It is RECOMMENDED that character encodings registered (as charsets) with the 
> Internet Assigned Numbers Authority [IANA-CHARSETS], other than those just listed, 
> be referred to using their registered names; other encodings SHOULD use names 
> starting with an "x-" prefix.

> **XML processors SHOULD match character encoding names in a case-insensitive way** 
> and SHOULD either interpret an IANA-registered name as the encoding registered at 
> IANA for that name or treat it as unknown (processors are, of course, not required 
> to support all IANA-registered encodings).

## Implementation

### Case-Insensitive Matching

Java's `Charset.forName()` already handles case-insensitive matching:

```java
Charset.forName("UTF-8");      // ✓
Charset.forName("utf-8");      // ✓ Same result
Charset.forName("Utf-8");      // ✓ Same result
Charset.forName("ISO-8859-1"); // ✓
Charset.forName("iso-8859-1"); // ✓ Same result
```

Our implementation:
```java
try {
    declaredCharset = Charset.forName(declaredEncoding);
} catch (Exception e) {
    throw new SAXException("Unsupported encoding in XML declaration: " + 
        declaredEncoding + " (reason: " + e.getMessage() + ")");
}
```

### Charset Aliases

Java's `Charset` class handles common aliases automatically:

| Declared in XML | Java Canonical Name | Aliases |
|-----------------|---------------------|---------|
| `UTF-8` | `UTF-8` | `utf8`, `unicode-1-1-utf-8` |
| `UTF-16` | `UTF-16` | `utf16`, `unicode`, `UnicodeBig` |
| `UTF-16BE` | `UTF-16BE` | `X-UTF-16BE`, `UTF_16BE`, `ISO-10646-UCS-2` |
| `UTF-16LE` | `UTF-16LE` | `X-UTF-16LE`, `UTF_16LE` |
| `ISO-8859-1` | `ISO-8859-1` | `iso-ir-100`, `latin1`, `l1`, `cp819` |
| `US-ASCII` | `US-ASCII` | `ascii`, `ANSI_X3.4-1968`, `iso-ir-6` |
| `Shift_JIS` | `Shift_JIS` | `sjis`, `ms_kanji`, `csShiftJIS` |
| `EUC-JP` | `EUC-JP` | `eucjis`, `x-eucjp`, `csEUCPkdFmtJapanese` |

### UTF-16 Special Handling

The XML spec and Java have specific behavior for UTF-16:

#### BOM + "UTF-16" Declaration = Compatible

```xml
<!-- File with UTF-16LE BOM (FF FE) -->
<?xml version="1.0" encoding="UTF-16"?>
```

**Behavior:**
- BOM indicates UTF-16LE (specific byte order)
- Declaration says "UTF-16" (generic, allows either LE or BE)
- **Result: Compatible** ✓

**Implementation:**
```java
private boolean isCharsetCompatible(Charset bomCharset, Charset declaredCharset) {
    // UTF-16 (generic) is compatible with UTF-16LE or UTF-16BE
    if (declName.equals("utf-16") || declName.equals("utf16")) {
        return bomName.equals("utf-16le") || bomName.equals("utf-16be") ||
               bomName.equals("utf-16") || bomName.equals("utf16");
    }
    // ...
}
```

#### BOM + Specific Encoding = Must Match

```xml
<!-- File with UTF-16LE BOM (FF FE) -->
<?xml version="1.0" encoding="UTF-16BE"?>
```

**Behavior:**
- BOM indicates UTF-16LE
- Declaration says "UTF-16BE" (conflicts!)
- **Result: Error** ✗

```
SAXException: Encoding mismatch: BOM indicates UTF-16LE but XML declaration specifies UTF-16BE
```

### Supported Encodings

Our implementation relies on Java's `Charset` support. Common encodings:

#### Always Available (per Java spec)
- `US-ASCII` - 7-bit ASCII
- `ISO-8859-1` - Latin-1
- `UTF-8` - 8-bit Unicode
- `UTF-16BE` - 16-bit Unicode, big-endian
- `UTF-16LE` - 16-bit Unicode, little-endian
- `UTF-16` - 16-bit Unicode with BOM

#### Commonly Available
- `ISO-8859-2` through `ISO-8859-15` - Extended Latin alphabets
- `windows-1252` - Windows Western European
- `Shift_JIS` - Japanese
- `EUC-JP` - Japanese
- `ISO-2022-JP` - Japanese
- `GB2312` / `GBK` / `GB18030` - Chinese
- `Big5` - Traditional Chinese
- `EUC-KR` - Korean

#### Unsupported Encoding Handling

```xml
<?xml version="1.0" encoding="X-MyCustomEncoding"?>
```

**Result:**
```
SAXException: Unsupported encoding in XML declaration: X-MyCustomEncoding 
(reason: X-MyCustomEncoding not supported)
```

## Test Cases

### Case 1: Standard Encodings (Case Variations)

```xml
<?xml version="1.0" encoding="UTF-8"?>     <!-- ✓ -->
<?xml version="1.0" encoding="utf-8"?>     <!-- ✓ -->
<?xml version="1.0" encoding="Utf-8"?>     <!-- ✓ -->
<?xml version="1.0" encoding="ISO-8859-1"?><!-- ✓ -->
<?xml version="1.0" encoding="iso-8859-1"?><!-- ✓ -->
```

All accepted, resolved to canonical charset names.

### Case 2: UTF-16 with BOM

```xml
<!-- UTF-16LE BOM (FF FE) -->
<?xml version="1.0" encoding="UTF-16"?>    <!-- ✓ Compatible -->
<?xml version="1.0" encoding="UTF-16LE"?>  <!-- ✓ Matches -->
<?xml version="1.0" encoding="UTF-16BE"?>  <!-- ✗ Mismatch -->

<!-- UTF-16BE BOM (FE FF) -->
<?xml version="1.0" encoding="UTF-16"?>    <!-- ✓ Compatible -->
<?xml version="1.0" encoding="UTF-16BE"?>  <!-- ✓ Matches -->
<?xml version="1.0" encoding="UTF-16LE"?>  <!-- ✗ Mismatch -->
```

### Case 3: Charset Aliases

```xml
<?xml version="1.0" encoding="latin1"?>      <!-- ✓ → ISO-8859-1 -->
<?xml version="1.0" encoding="ascii"?>       <!-- ✓ → US-ASCII -->
<?xml version="1.0" encoding="sjis"?>        <!-- ✓ → Shift_JIS -->
<?xml version="1.0" encoding="eucjis"?>      <!-- ✓ → EUC-JP -->
```

Java automatically resolves aliases to canonical names.

### Case 4: Unsupported Encodings

```xml
<?xml version="1.0" encoding="EBCDIC"?>      <!-- ✗ Not in Java -->
<?xml version="1.0" encoding="X-Custom"?>    <!-- ✗ Unknown -->
```

Error: `SAXException: Unsupported encoding...`

### Case 5: No BOM + Encoding Switch

```xml
<!-- No BOM (defaults to UTF-8) -->
<?xml version="1.0" encoding="ISO-8859-1"?>

Result:
1. Parse XML declaration using UTF-8 (7-bit clean, works for ASCII)
2. Extract encoding="ISO-8859-1"
3. Switch decoder to ISO-8859-1
4. Reset buffer position to after XML declaration
5. Decode remaining document with ISO-8859-1
```

## Java Charset Behavior

### Case-Insensitive Lookup

```java
// All return the same Charset instance
Charset cs1 = Charset.forName("UTF-8");
Charset cs2 = Charset.forName("utf-8");
Charset cs3 = Charset.forName("Utf-8");

cs1.equals(cs2); // true
cs1.equals(cs3); // true
```

### Canonical Names

```java
Charset.forName("latin1").name();        // "ISO-8859-1"
Charset.forName("ascii").name();         // "US-ASCII"
Charset.forName("utf8").name();          // "UTF-8"
Charset.forName("sjis").name();          // "Shift_JIS"
```

### Alias Checking

```java
Charset utf8 = Charset.forName("UTF-8");
utf8.aliases();  // ["utf8", "unicode-1-1-utf-8"]

Charset latin1 = Charset.forName("ISO-8859-1");
latin1.aliases(); // ["iso-ir-100", "ISO_8859-1", "latin1", "l1", ...]
```

## Error Messages

### Charset Mismatch (BOM vs Declaration)

```
SAXException: Encoding mismatch: BOM indicates UTF-16LE but XML declaration specifies UTF-16BE
```

### Unsupported Charset

```
SAXException: Unsupported encoding in XML declaration: EBCDIC (reason: EBCDIC not supported)
```

### Invalid Charset Name

```
SAXException: Unsupported encoding in XML declaration: Not-A-Real-Encoding 
(reason: Illegal charset name 'Not-A-Real-Encoding')
```

## Implementation Details

### Method: `isCharsetCompatible()`

```java
private boolean isCharsetCompatible(Charset bomCharset, Charset declaredCharset) {
    // 1. Exact match
    if (bomCharset.equals(declaredCharset)) {
        return true;
    }
    
    // 2. UTF-16 generic compatible with UTF-16LE/BE
    String declName = declaredCharset.name().toLowerCase();
    if (declName.equals("utf-16") || declName.equals("utf16")) {
        String bomName = bomCharset.name().toLowerCase();
        return bomName.equals("utf-16le") || bomName.equals("utf-16be") ||
               bomName.equals("utf-16") || bomName.equals("utf16");
    }
    
    // 3. Check aliases
    return bomCharset.aliases().contains(declName) ||
           declaredCharset.aliases().contains(bomName);
}
```

### Error Handling

```java
String declaredEncoding = extractXMLDeclarationAttributes(decl);

if (declaredEncoding != null) {
    Charset declaredCharset;
    try {
        declaredCharset = Charset.forName(declaredEncoding);
    } catch (Exception e) {
        throw new SAXException("Unsupported encoding in XML declaration: " + 
            declaredEncoding + " (reason: " + e.getMessage() + ")");
    }
    
    if (bomCharset != null && !isCharsetCompatible(bomCharset, declaredCharset)) {
        throw new SAXException("Encoding mismatch: BOM indicates " + 
            bomCharset.name() + " but XML declaration specifies " + 
            declaredEncoding);
    }
    // ...
}
```

## XML Specification Compliance

✅ **Case-insensitive matching**: Java's `Charset.forName()` handles this  
✅ **IANA-registered names**: Java uses IANA charset registry  
✅ **Common encodings**: UTF-8, UTF-16, ISO-8859-*, CJK encodings supported  
✅ **Alias support**: Java `Charset` handles common aliases  
✅ **Unknown encodings**: Proper error with descriptive message  
✅ **UTF-16 BOM compatibility**: Custom logic handles UTF-16/UTF-16LE/UTF-16BE

## Summary

The XMLTokenizer correctly implements XML charset handling by:

1. **Leveraging Java's Charset class** for case-insensitive, alias-aware lookup
2. **Special-casing UTF-16** to handle BOM compatibility correctly
3. **Providing clear error messages** for unsupported or mismatched encodings
4. **Following XML spec guidance** on IANA charset names and case-insensitivity

This ensures broad compatibility with real-world XML documents while maintaining strict correctness per the XML specification! 🎯

