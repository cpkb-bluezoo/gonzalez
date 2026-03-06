/*
 * XPathBinaryValue.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.transform.xpath.type;

import java.util.Arrays;

/**
 * XPath 2.0 xs:hexBinary and xs:base64Binary atomic values.
 *
 * <p>Stores the raw binary data and serializes to hex or base64 form
 * depending on the encoding type. Supports casting between hex and base64
 * representations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathBinaryValue implements XPathValue {

    /** Whether this is base64Binary (true) or hexBinary (false). */
    private final boolean isBase64;

    /** The raw binary data. */
    private final byte[] data;

    /**
     * Creates a binary value from raw bytes.
     *
     * @param data the binary data
     * @param isBase64 true for xs:base64Binary, false for xs:hexBinary
     */
    public XPathBinaryValue(byte[] data, boolean isBase64) {
        this.data = data;
        this.isBase64 = isBase64;
    }

    /**
     * Creates a hexBinary value from a hex string.
     *
     * @param hex the hex-encoded string
     * @return the binary value
     */
    public static XPathBinaryValue fromHex(String hex) {
        byte[] data = hexToBytes(hex);
        return new XPathBinaryValue(data, false);
    }

    /**
     * Creates a base64Binary value from a base64 string.
     *
     * @param base64 the base64-encoded string
     * @return the binary value
     */
    public static XPathBinaryValue fromBase64(String base64) {
        byte[] data = base64ToBytes(base64);
        return new XPathBinaryValue(data, true);
    }

    /**
     * Returns a new binary value with the same data but as base64Binary type.
     *
     * @return base64Binary representation of the same data
     */
    public XPathBinaryValue toBase64Binary() {
        return new XPathBinaryValue(data, true);
    }

    /**
     * Returns a new binary value with the same data but as hexBinary type.
     *
     * @return hexBinary representation of the same data
     */
    public XPathBinaryValue toHexBinary() {
        return new XPathBinaryValue(data, false);
    }

    public boolean isBase64() {
        return isBase64;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public Type getType() {
        return Type.STRING;
    }

    @Override
    public String asString() {
        if (isBase64) {
            return bytesToBase64(data);
        }
        return bytesToHex(data);
    }

    @Override
    public double asNumber() {
        return Double.NaN;
    }

    @Override
    public boolean asBoolean() {
        return data.length > 0;
    }

    @Override
    public XPathNodeSet asNodeSet() {
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof XPathBinaryValue) {
            XPathBinaryValue other = (XPathBinaryValue) obj;
            return Arrays.equals(data, other.data);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return (isBase64 ? "base64Binary" : "hexBinary") + "[" + asString() + "]";
    }

    // --- Conversion utilities ---

    private static byte[] hexToBytes(String hex) {
        String clean = hex.trim();
        int len = clean.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = hexDigit(clean.charAt(i));
            int low = hexDigit(clean.charAt(i + 1));
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return 10 + (c - 'A');
        }
        if (c >= 'a' && c <= 'f') {
            return 10 + (c - 'a');
        }
        return 0;
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            sb.append(HEX_CHARS[b >>> 4]);
            sb.append(HEX_CHARS[b & 0x0F]);
        }
        return sb.toString();
    }

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    private static final char[] BASE64_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String bytesToBase64(byte[] data) {
        int len = data.length;
        StringBuilder sb = new StringBuilder(((len + 2) / 3) * 4);
        for (int i = 0; i < len; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < len) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < len) ? (data[i + 2] & 0xFF) : 0;
            sb.append(BASE64_CHARS[b0 >>> 2]);
            sb.append(BASE64_CHARS[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            if (i + 1 < len) {
                sb.append(BASE64_CHARS[((b1 & 0x0F) << 2) | (b2 >>> 6)]);
            } else {
                sb.append('=');
            }
            if (i + 2 < len) {
                sb.append(BASE64_CHARS[b2 & 0x3F]);
            } else {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    private static byte[] base64ToBytes(String base64) {
        String clean = base64.replaceAll("\\s+", "");
        int len = clean.length();
        if (len == 0) {
            return new byte[0];
        }
        int padding = 0;
        if (clean.charAt(len - 1) == '=') {
            padding++;
        }
        if (len > 1 && clean.charAt(len - 2) == '=') {
            padding++;
        }
        int outputLen = (len / 4) * 3 - padding;
        byte[] result = new byte[outputLen];
        int outIdx = 0;
        for (int i = 0; i < len; i += 4) {
            int b0 = base64Digit(clean.charAt(i));
            int b1 = base64Digit(clean.charAt(i + 1));
            int b2 = (i + 2 < len && clean.charAt(i + 2) != '=') ? base64Digit(clean.charAt(i + 2)) : 0;
            int b3 = (i + 3 < len && clean.charAt(i + 3) != '=') ? base64Digit(clean.charAt(i + 3)) : 0;
            if (outIdx < outputLen) {
                result[outIdx++] = (byte) ((b0 << 2) | (b1 >>> 4));
            }
            if (outIdx < outputLen) {
                result[outIdx++] = (byte) (((b1 & 0x0F) << 4) | (b2 >>> 2));
            }
            if (outIdx < outputLen) {
                result[outIdx++] = (byte) (((b2 & 0x03) << 6) | b3);
            }
        }
        return result;
    }

    private static int base64Digit(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return 26 + (c - 'a');
        }
        if (c >= '0' && c <= '9') {
            return 52 + (c - '0');
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        return 0;
    }
}
