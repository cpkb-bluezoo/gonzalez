/*
 * Utf8DecoderTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
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
 * {@link Utf8Decoder} is a second decoder for UTF-8, faster than the JDK's on
 * text that is not all ASCII, and {@link Utf8LongRuns} is faster again on
 * text in which what is not ASCII is rare. Each must report exactly what the
 * JDK's does for the same input, however that arrives
 * and into however small a buffer: the same characters, the same refusal of
 * what is not well-formed, with the same length of malformed input, and the
 * same waiting for the end of a sequence.
 */
public class Utf8DecoderTest {

    private static CharsetDecoder jdk() {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static CharsetDecoder ours() {
        return new Utf8Decoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /** The decoder the parser uses instead of {@link #ours} where non-ASCII characters are few. */
    private static CharsetDecoder forLongRuns() {
        return new Utf8LongRuns().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * Decodes as the parser does: the input arrives in pieces of {@code
     * inChunk} bytes, what the decoder leaves is kept in front of the next
     * piece, and the output is taken out of a buffer of {@code outSize}
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
                    return text + "|error length " + result.length() + " after " + pending.position();
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

    private static void same(String what, byte[] data, int[] inChunks, int[] outSizes, boolean[] directs) {
        for (int inChunk : inChunks) {
            for (int outSize : outSizes) {
                for (boolean direct : directs) {
                    String expected = decode(jdk(), data, inChunk, outSize, direct);
                    String where = what + " in " + inChunk + " out " + outSize + (direct ? " direct" : "");
                    assertEquals(where, expected, decode(ours(), data, inChunk, outSize, direct));
                    assertEquals(where + " for long runs", expected,
                            decode(forLongRuns(), data, inChunk, outSize, direct));
                }
            }
        }
    }

    private static final int[] CHUNKS = { 1, 2, 3, 5, 64, 1000, 40000 };
    private static final int[] OUTS = { 2, 3, 7, 64, 4096 };
    private static final boolean[] BOTH = { false, true };

    private static String randomText(Random random, int length, int nonAsciiEvery) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            int kind = random.nextInt(nonAsciiEvery);
            if (kind == 0) {
                sb.append((char) (0x80 + random.nextInt(0x780)));
            } else if (kind == 1) {
                char c;
                do {
                    c = (char) (0x800 + random.nextInt(0xF800));
                } while (Character.isSurrogate(c));
                sb.append(c);
            } else if (kind == 2) {
                sb.appendCodePoint(0x10000 + random.nextInt(0xFFFFF));
            } else {
                sb.append((char) ('a' + random.nextInt(26)));
            }
        }
        return sb.toString();
    }

    @Test
    public void validTextOfEveryMixture() {
        Random random = new Random(1);
        for (int every : new int[] { 3, 4, 5, 20, 300, 5000 }) {
            byte[] data = randomText(random, 4000, every).getBytes(StandardCharsets.UTF_8);
            same("valid every " + every, data, CHUNKS, OUTS, BOTH);
        }
    }

    /**
     * Runs of ASCII are copied by a method of their own, which is given how many
     * it may copy, so the runs are of every length, ending at the input, at the
     * output and at a byte that is not UTF-8.
     */
    @Test
    public void runsOfAsciiOfEveryLength() {
        int[] chunks = { 7, 64, 100, 1000, 40000 };
        int[] outs = { 3, 47, 48, 49, 64, 4096 };
        for (int run = 1; run <= 130; run++) {
            StringBuilder sb = new StringBuilder();
            for (int part = 0; part < 4; part++) {
                for (int i = 0; i < run; i++) {
                    sb.append((char) ('a' + (i + part) % 26));
                }
                sb.append(part % 2 == 0 ? "\u00e9" : "\ud83d\ude00");
            }
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            same("run " + run, data, chunks, outs, BOTH);

            byte[] bad = new byte[data.length + 1];
            System.arraycopy(data, 0, bad, 0, data.length);
            bad[data.length] = (byte) 0xFF;
            same("run " + run + " then bad", bad, chunks, outs, BOTH);
        }
    }

    /**
     * Where non-ASCII characters are few the runs of ASCII between them are
     * widened by another decoder, a run at a time, after being measured; so
     * the runs are of every length, near the lengths at which a run is given
     * to it and the length of the sample that says whether to, and end at the
     * input, at the output and at a byte that is not UTF-8.
     */
    @Test
    public void longRunsOfAsciiOfEveryLength() {
        int[] chunks = { 300, 1000, 40000 };
        int[] outs = { 3, 47, 300, 4096 };
        for (int run = 1; run <= 700; run += (run < 100 ? 1 : 7)) {
            StringBuilder sb = new StringBuilder();
            for (int part = 0; part < 3; part++) {
                for (int i = 0; i < run; i++) {
                    sb.append((char) ('a' + (i + part) % 26));
                }
                sb.append(part == 0 ? "\u00e9" : part == 1 ? "\u3042" : "\ud83d\ude00");
            }
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            same("long run " + run, data, chunks, outs, BOTH);

            byte[] bad = new byte[data.length + 1];
            System.arraycopy(data, 0, bad, 0, data.length);
            bad[data.length] = (byte) 0xFF;
            same("long run " + run + " then bad", bad, chunks, outs, BOTH);
        }
    }

    @Test
    public void textWithFewNonAsciiCharacters() {
        Random random = new Random(3);
        for (int every : new int[] { 100, 300, 1000, 5000 }) {
            byte[] data = randomText(random, 20000, every).getBytes(StandardCharsets.UTF_8);
            same("sparse every " + every, data, new int[] { 1, 7, 300, 1000, 40000 },
                    new int[] { 3, 64, 4096 }, BOTH);
        }
    }

    /**
     * What is not well-formed, in the middle of long runs of ASCII and at the
     * end of them, is refused as the JDK's decoder refuses it, however long
     * the part of it that is there is.
     */
    @Test
    public void whatIsNotWellFormedAmongLongRuns() {
        int[][] bad = {
            { 0xE0, 0x80, 0x80 }, { 0xE0, 0x9F, 0xBF }, { 0xE0, 0x9C, 0x80 }, { 0xED, 0xA0, 0x80 },
            { 0xED, 0xBF, 0xBF }, { 0xF0, 0x80, 0x80, 0x80 }, { 0xF0, 0x8F, 0xBF, 0xBF },
            { 0xF4, 0x90, 0x80, 0x80 }, { 0xF5, 0x80, 0x80, 0x80 }, { 0xF8, 0x88, 0x80, 0x80 },
            { 0xC0, 0x80 }, { 0xC1, 0xBF }, { 0x80 }, { 0xBF }, { 0xFF }, { 0xC3 }, { 0xC3, 0x41 },
            { 0xE3, 0x81 }, { 0xE3, 0x41, 0x80 }, { 0xE3, 0x81, 0x41 }, { 0xF0, 0x9F, 0x98 },
            { 0xF0, 0x9F, 0x41, 0x80 }, { 0xF0, 0x9F, 0x98, 0x41 }
        };
        for (int[] sequence : bad) {
            for (int run : new int[] { 40, 300, 301 }) {
                StringBuilder before = new StringBuilder();
                for (int i = 0; i < run; i++) {
                    before.append((char) ('a' + i % 26));
                }
                byte[] ascii = before.toString().getBytes(StandardCharsets.US_ASCII);
                for (int after : new int[] { 0, 5, 300 }) {
                    byte[] data = new byte[ascii.length + sequence.length + after];
                    System.arraycopy(ascii, 0, data, 0, ascii.length);
                    for (int i = 0; i < sequence.length; i++) {
                        data[ascii.length + i] = (byte) sequence[i];
                    }
                    for (int i = 0; i < after; i++) {
                        data[ascii.length + sequence.length + i] = (byte) ('a' + i % 26);
                    }
                    same("bad " + hex(java.util.Arrays.copyOfRange(data, ascii.length, ascii.length + sequence.length))
                            + " after " + run + " before " + after, data,
                            new int[] { 5, 300, 40000 }, new int[] { 4, 47, 4096 }, BOTH);
                }
            }
        }
    }

    @Test
    public void corruptedTextWithFewNonAsciiCharacters() {
        Random random = new Random(11);
        for (int round = 0; round < 200; round++) {
            byte[] data = randomText(random, 1000 + random.nextInt(1000), 150 + random.nextInt(500))
                    .getBytes(StandardCharsets.UTF_8);
            int flips = 1 + random.nextInt(3);
            for (int i = 0; i < flips; i++) {
                data[random.nextInt(data.length)] = (byte) random.nextInt(256);
            }
            if (random.nextInt(4) == 0) {
                data = java.util.Arrays.copyOf(data, data.length - random.nextInt(4));
            }
            same("corrupted sparse " + round, data, new int[] { 3, 300, 1000, 40000 },
                    new int[] { 5, 47, 4096 }, new boolean[] { false });
        }
    }

    @Test
    public void emptyAndTiny() {
        same("empty", new byte[0], CHUNKS, OUTS, BOTH);
        same("one", new byte[] { 0x41 }, CHUNKS, OUTS, BOTH);
        same("two", new byte[] { (byte) 0xC3, (byte) 0xA9 }, CHUNKS, OUTS, BOTH);
        same("three", new byte[] { (byte) 0xE3, (byte) 0x81, (byte) 0x82 }, CHUNKS, OUTS, BOTH);
        same("four", new byte[] { (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80 }, CHUNKS, OUTS, BOTH);
    }

    @Test
    public void everySequenceOfUpToFourBytes() {
        // Every first and second byte, with a selection of third and fourth,
        // alone (the careful loop), between ASCII (the fast loop), and at the
        // end of the input (waiting for more).
        int[] thirds = { 0x41, 0x80, 0xA0, 0xBF, 0xC3 };
        int[] fourths = { 0x80, 0xBF, 0x41 };
        int[] whole = { 100000 };
        int[] big = { 4096 };
        boolean[] heap = { false };
        byte[] pad = "abcdefgh".getBytes(StandardCharsets.US_ASCII);
        for (int b1 = 0; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                for (int b3 : thirds) {
                    for (int b4 : fourths) {
                        byte[] seq = { (byte) b1, (byte) b2, (byte) b3, (byte) b4 };
                        byte[] between = new byte[pad.length * 2 + 4];
                        System.arraycopy(pad, 0, between, 0, pad.length);
                        System.arraycopy(seq, 0, between, pad.length, 4);
                        System.arraycopy(pad, 0, between, pad.length + 4, pad.length);
                        same("alone " + hex(seq), seq, whole, big, heap);
                        same("between " + hex(seq), between, whole, big, heap);
                    }
                }
                // and cut short
                for (int length = 1; length <= 3; length++) {
                    byte[] cut = { (byte) b1, (byte) b2, (byte) 0xA0 };
                    byte[] shortened = java.util.Arrays.copyOf(cut, length);
                    byte[] padded = new byte[pad.length + length];
                    System.arraycopy(pad, 0, padded, 0, pad.length);
                    System.arraycopy(shortened, 0, padded, pad.length, length);
                    same("cut " + hex(shortened), padded, whole, big, heap);
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    @Test
    public void corruptedText() {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            byte[] data = randomText(random, 200 + random.nextInt(300), 2 + random.nextInt(8))
                    .getBytes(StandardCharsets.UTF_8);
            int flips = 1 + random.nextInt(3);
            for (int i = 0; i < flips; i++) {
                data[random.nextInt(data.length)] = (byte) random.nextInt(256);
            }
            if (random.nextInt(4) == 0) {
                data = java.util.Arrays.copyOf(data, data.length - random.nextInt(4));
            }
            same("corrupted " + round, data, new int[] { 1, 3, 1000 }, new int[] { 2, 5, 4096 },
                    new boolean[] { false });
        }
    }

    @Test
    public void aBufferWithNoArrayIsDecodedToo() {
        Random random = new Random(3);
        String text = randomText(random, 500, 4);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        CharsetDecoder decoder = ours();
        CharBuffer out = ByteBuffer.allocateDirect(2 * 2000).asCharBuffer();
        CoderResult result = decoder.decode(ByteBuffer.wrap(data), out, false);
        assertEquals(true, result.isUnderflow());
        out.flip();
        assertEquals(text, out.toString());
    }

    @Test
    public void aDocumentOfMostlyAsciiWithAccents() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            body.append("some plain text here and then an accented caf\u00e9 and a dash \u2014 and a smile \ud83d\ude00 ");
        }
        byte[] data = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><r a=\"\u00e9\">" + body + "</r>")
                .getBytes(StandardCharsets.UTF_8);
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
        assertEquals(body.toString(), seen.toString());
        assertEquals("\u00e9", attribute[0]);
    }

    @Test
    public void whatIsNotWellFormedIsStillRefused() throws Exception {
        byte[][] bad = {
            "<r>abc\u00e9".getBytes(StandardCharsets.UTF_8),
            { '<', 'r', '>', 'a', (byte) 0xC3, 'b', '<', '/', 'r', '>' },
            { '<', 'r', '>', 'a', (byte) 0xC0, (byte) 0x80, 'b', '<', '/', 'r', '>' },
            { '<', 'r', '>', 'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b', '<', '/', 'r', '>' },
            { '<', 'r', '>', (byte) 0xC3, (byte) 0xA9, (byte) 0xFF, '<', '/', 'r', '>' },
        };
        for (int i = 0; i < bad.length; i++) {
            try {
                new Parser().parse(new InputSource(new ByteArrayInputStream(bad[i])));
                fail("accepted malformed UTF-8 " + i);
            } catch (SAXParseException expected) {
                // refused
            }
        }
    }

    /**
     * The input is scanned eight bytes at a time for the first byte that is
     * not ASCII, and decoded by one decoder up to it and another from it:
     * the document is made with that byte at every place in a stride, a
     * carriage return before it and after it, and arrives in pieces of every
     * awkward size.
     */
    @Test
    public void theFirstNonAsciiByteAtEveryPlace() throws Exception {
        for (int lead = 0; lead < 40; lead++) {
            for (int crAt = -1; crAt < 3; crAt++) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < lead; i++) {
                    text.append(crAt == 0 && i == lead / 2 ? "\r\n" : "x");
                }
                text.append("\u00e9");
                for (int i = 0; i < 20; i++) {
                    text.append(crAt == 1 && i == 5 ? "\r" : "y");
                }
                text.append("\u3042").append(crAt == 2 ? "\r\n" : "z");
                String expected = text.toString().replace("\r\n", "\n").replace('\r', '\n');
                byte[] data = ("<r>" + text + "</r>").getBytes(StandardCharsets.UTF_8);
                for (int chunk : new int[] { 1, 3, 7, 8, 9, 16, 4096 }) {
                    final StringBuilder seen = new StringBuilder();
                    Parser parser = new Parser();
                    parser.setContentHandler(new DefaultHandler() {
                        @Override
                        public void characters(char[] ch, int start, int length) {
                            seen.append(ch, start, length);
                        }
                    });
                    java.io.InputStream in = new ChunkedInputStream(new ByteArrayInputStream(data), chunk);
                    parser.parse(new InputSource(in));
                    assertEquals("lead " + lead + " cr " + crAt + " chunk " + chunk, expected, seen.toString());
                }
            }
        }
    }
}
