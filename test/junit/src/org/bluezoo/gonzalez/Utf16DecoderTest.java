/*
 * Utf16DecoderTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link Utf16Decoder} decodes UTF-16 in bulk where the JDK's decoder does
 * it a character at a time. It must report exactly what the JDK's does, for
 * the same input however that arrives and into however small a buffer: the
 * same characters, the same refusal of an unpaired surrogate, and the same
 * waiting for the second half of a pair or the second byte of a character.
 */
public class Utf16DecoderTest {

    private static CharsetDecoder jdk(Charset cs) {
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static CharsetDecoder ours(Charset cs) {
        return new Utf16Decoder(cs, cs.equals(StandardCharsets.UTF_16BE))
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * Decodes as the parser does: the input arrives in pieces of {@code
     * inChunk} bytes, whatever the decoder leaves is kept in front of the
     * next piece, and the output is taken out of a buffer of {@code outSize}
     * characters whenever it fills. Returns the characters decoded, then how
     * it ended: an error with its length, or the bytes left over.
     */
    private static String decode(CharsetDecoder decoder, byte[] data, int inChunk, int outSize,
            boolean direct) {
        StringBuilder text = new StringBuilder();
        ByteBuffer pending = direct ? ByteBuffer.allocateDirect(inChunk + 8) : ByteBuffer.allocate(inChunk + 8);
        CharBuffer out = CharBuffer.allocate(outSize);
        int offset = 0;
        while (offset < data.length || pending.position() > 0) {
            int n = Math.min(inChunk, data.length - offset);
            pending.put(data, offset, n);
            offset += n;
            pending.flip();
            boolean more = true;
            while (more) {
                CoderResult result = decoder.decode(pending, out, false);
                out.flip();
                text.append(out);
                out.clear();
                if (result.isError()) {
                    return text + "|error length " + result.length();
                }
                more = result.isOverflow();
            }
            pending.compact();
            if (n == 0) {
                break;
            }
        }
        return text + "|left over " + pending.position();
    }

    /**
     * The characters as UTF-16, one by one, so that a surrogate on its own
     * is written as it is: {@code String.getBytes} would replace it.
     */
    private static byte[] encode(String text, Charset cs) {
        boolean big = cs.equals(StandardCharsets.UTF_16BE);
        byte[] bytes = new byte[2 * text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            bytes[2 * i] = (byte) (big ? c >> 8 : c);
            bytes[2 * i + 1] = (byte) (big ? c : c >> 8);
        }
        return bytes;
    }

    private static String randomText(Random random, int length, int surrogateEvery) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            int kind = random.nextInt(surrogateEvery);
            if (kind == 0) {
                sb.appendCodePoint(0x10000 + random.nextInt(0xFFFFF));
            } else if (kind == 1) {
                sb.append((char) (0x3040 + random.nextInt(0x60)));
            } else if (kind == 2) {
                sb.append((char) (0x00c0 + random.nextInt(0x40)));
            } else {
                sb.append((char) ('a' + random.nextInt(26)));
            }
        }
        return sb.toString();
    }

    private static void same(String what, Charset cs, byte[] data) {
        for (int inChunk : new int[] { 1, 2, 3, 5, 64, 1000, 40000 }) {
            for (int outSize : new int[] { 2, 3, 7, 64, 4096 }) {
                for (boolean direct : new boolean[] { false, true }) {
                    String expected = decode(jdk(cs), data, inChunk, outSize, direct);
                    String actual = decode(ours(cs), data, inChunk, outSize, direct);
                    assertEquals(what + " " + cs + " in " + inChunk + " out " + outSize
                            + (direct ? " direct" : ""), expected, actual);
                }
            }
        }
    }

    @Test
    public void validTextOfEveryKind() {
        Random random = new Random(1);
        for (Charset cs : new Charset[] { StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE }) {
            for (int every : new int[] { 3, 4, 20, 1000 }) {
                same("valid " + every, cs, encode(randomText(random, 3000, every), cs));
            }
        }
    }

    @Test
    public void emptyAndTiny() {
        for (Charset cs : new Charset[] { StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE }) {
            same("empty", cs, new byte[0]);
            same("one byte", cs, new byte[] { 0x41 });
            same("one character", cs, encode("a", cs));
            same("three bytes", cs, new byte[] { 0x41, 0, 0x42 });
        }
    }

    @Test
    public void unpairedSurrogates() {
        Random random = new Random(2);
        String[] bad = {
            "ab\udc00cd",              // a low surrogate alone
            "ab\ud800cd",              // a high surrogate followed by an ordinary character
            "ab\ud800\ud800\udc00",    // a high surrogate followed by another high one
            "ab\udc00\ud800\udc00",    // a low surrogate before a pair
            "abcd\ud800",              // a high surrogate at the very end
            "\ud800",
            "\udc00",
            "\udbff\udfff\udc00",
        };
        for (Charset cs : new Charset[] { StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE }) {
            for (String text : bad) {
                same("bad " + text.length(), cs, encode(text, cs));
                // the same among a lot of ordinary text, so that it falls at
                // every place in a decoded stretch
                for (int pad = 0; pad < 40; pad += 7) {
                    String padding = randomText(random, pad + 60, 5);
                    same("bad after " + pad, cs, encode(padding + text + padding, cs));
                }
            }
        }
    }

    @Test
    public void aPairAtEveryOffset() {
        for (Charset cs : new Charset[] { StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE }) {
            for (int lead = 0; lead < 40; lead++) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lead; i++) {
                    sb.append('x');
                }
                sb.append("\ud83d\ude00");
                for (int i = 0; i < 40 - lead; i++) {
                    sb.append('y');
                }
                same("pair at " + lead, cs, encode(sb.toString(), cs));
            }
        }
    }

    @Test
    public void aBufferWithNoArrayIsDecodedToo() {
        Random random = new Random(3);
        String text = randomText(random, 500, 6);
        byte[] data = encode(text, StandardCharsets.UTF_16LE);
        CharsetDecoder decoder = ours(StandardCharsets.UTF_16LE);
        CharBuffer out = ByteBuffer.allocateDirect(2 * 2000).asCharBuffer();
        CoderResult result = decoder.decode(ByteBuffer.wrap(data), out, false);
        assertEquals(true, result.isUnderflow());
        out.flip();
        assertEquals(text, out.toString());
    }

    /** A document in UTF-16 with characters beyond the first plane parses as it should. */
    @Test
    public void aDocumentWithAstralCharacters() throws Exception {
        for (Charset cs : new Charset[] { StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE }) {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 20000; i++) {
                body.append("a\ud83d\ude00\u3042");
            }
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><r a=\"\ud83d\ude00\">" + body + "</r>";
            byte[] bom = cs.equals(StandardCharsets.UTF_16LE)
                    ? new byte[] { (byte) 0xFF, (byte) 0xFE } : new byte[] { (byte) 0xFE, (byte) 0xFF };
            byte[] text = encode(xml, cs);
            byte[] data = new byte[bom.length + text.length];
            System.arraycopy(bom, 0, data, 0, bom.length);
            System.arraycopy(text, 0, data, bom.length, text.length);
            final StringBuilder seen = new StringBuilder();
            final String[] attribute = new String[1];
            Parser parser = new Parser();
            parser.setContentHandler(new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName,
                        org.xml.sax.Attributes atts) {
                    attribute[0] = atts.getValue("a");
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    seen.append(ch, start, length);
                }
            });
            parser.parse(new InputSource(new ByteArrayInputStream(data)));
            assertEquals(cs.toString(), body.toString(), seen.toString());
            assertEquals("\ud83d\ude00", attribute[0]);
        }
    }

    @Test
    public void anUnpairedSurrogateInADocumentIsStillRefused() throws Exception {
        for (Charset cs : new Charset[] { StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE }) {
            String xml = "<r>abc\ud800def</r>";
            byte[] bom = cs.equals(StandardCharsets.UTF_16LE)
                    ? new byte[] { (byte) 0xFF, (byte) 0xFE } : new byte[] { (byte) 0xFE, (byte) 0xFF };
            byte[] text = encode(xml, cs);
            byte[] data = new byte[bom.length + text.length];
            System.arraycopy(bom, 0, data, 0, bom.length);
            System.arraycopy(text, 0, data, bom.length, text.length);
            try {
                new Parser().parse(new InputSource(new ByteArrayInputStream(data)));
                fail("accepted an unpaired surrogate in " + cs);
            } catch (SAXParseException expected) {
                // refused, as it always was
            }
        }
    }
}
