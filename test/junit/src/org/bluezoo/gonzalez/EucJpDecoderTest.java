/*
 * EucJpDecoderTest.java
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
import java.util.Random;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.assertEquals;

/**
 * {@link EucJpDecoder} looks EUC-JP up in tables where the JDK's decoder goes
 * through several layers of calls for each character, and gives anything
 * irregular back to the JDK's decoder. It must report exactly what the JDK's
 * does for the same input, however that arrives and into however small a
 * buffer.
 */
public class EucJpDecoderTest {

    private Charset eucJp;

    @Before
    public void charsetIsAvailable() {
        Assume.assumeTrue(Charset.isSupported("EUC-JP"));
        eucJp = Charset.forName("EUC-JP");
    }

    private CharsetDecoder jdk() {
        return eucJp.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private CharsetDecoder ours() {
        return new EucJpDecoder(eucJp).onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /** As the parser decodes: pieces of the input, a buffer of the output taken out as it fills. */
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
                    return text + "|error " + (result.isMalformed() ? "malformed " : "unmappable ")
                            + result.length() + " after " + pending.position();
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

    private void same(String what, byte[] data, int[] inChunks, int[] outSizes, boolean[] directs) {
        for (int inChunk : inChunks) {
            for (int outSize : outSizes) {
                for (boolean direct : directs) {
                    assertEquals(what + " in " + inChunk + " out " + outSize + (direct ? " direct" : ""),
                            decode(jdk(), data, inChunk, outSize, direct),
                            decode(ours(), data, inChunk, outSize, direct));
                }
            }
        }
    }

    private static final int[] CHUNKS = { 1, 2, 3, 5, 64, 1000, 40000 };
    private static final int[] OUTS = { 2, 3, 7, 64, 4096 };
    private static final boolean[] BOTH = { false, true };

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private String randomJapanese(Random random, int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            int kind = random.nextInt(8);
            if (kind == 0) {
                sb.append((char) (0xFF61 + random.nextInt(63)));       // half-width katakana
            } else if (kind == 1) {
                sb.append((char) (0x4E00 + random.nextInt(0x1000)));   // kanji
            } else if (kind == 2) {
                sb.append((char) (0x3041 + random.nextInt(0x50)));     // hiragana
            } else if (kind == 3) {
                sb.append((char) 0x4E02);                              // in JIS X 0212 only
            } else {
                sb.append((char) ('a' + random.nextInt(26)));
            }
        }
        return sb.toString();
    }

    @Test
    public void japaneseTextOfEveryMixture() throws Exception {
        Random random = new Random(1);
        for (int round = 0; round < 6; round++) {
            byte[] data = randomJapanese(random, 3000).getBytes(eucJp);
            same("text " + round, data, CHUNKS, OUTS, BOTH);
        }
    }

    @Test
    public void everyTwoByteSequenceAloneAndAmongAscii() {
        int[] whole = { 100000 };
        int[] big = { 4096 };
        boolean[] heap = { false };
        byte[] pad = "abcdefgh".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int b1 = 0x80; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                byte[] seq = { (byte) b1, (byte) b2 };
                byte[] between = new byte[pad.length * 2 + 2];
                System.arraycopy(pad, 0, between, 0, pad.length);
                System.arraycopy(seq, 0, between, pad.length, 2);
                System.arraycopy(pad, 0, between, pad.length + 2, pad.length);
                same("alone " + hex(seq), seq, whole, big, heap);
                same("between " + hex(seq), between, whole, big, heap);
            }
            same("cut " + hex(new byte[] { (byte) b1 }), new byte[] { 0x41, 0x42, (byte) b1 }, whole, big, heap);
        }
    }

    @Test
    public void everyThreeByteSequence() {
        int[] whole = { 100000 };
        int[] big = { 4096 };
        boolean[] heap = { false };
        byte[] pad = "abcdefgh".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int[] thirds = { 0x00, 0x41, 0x80, 0xA1, 0xB0, 0xFE, 0xFF };
        for (int b2 = 0; b2 < 256; b2++) {
            for (int b3 : thirds) {
                byte[] seq = { (byte) 0x8F, (byte) b2, (byte) b3 };
                byte[] between = new byte[pad.length * 2 + 3];
                System.arraycopy(pad, 0, between, 0, pad.length);
                System.arraycopy(seq, 0, between, pad.length, 3);
                System.arraycopy(pad, 0, between, pad.length + 3, pad.length);
                same("alone " + hex(seq), seq, whole, big, heap);
                same("between " + hex(seq), between, whole, big, heap);
            }
        }
        // every assigned JIS X 0212 character, which is most of what the above does not reach
        for (int b2 = 0xA1; b2 <= 0xFE; b2++) {
            byte[] all = new byte[94 * 3];
            for (int b3 = 0xA1; b3 <= 0xFE; b3++) {
                all[(b3 - 0xA1) * 3] = (byte) 0x8F;
                all[(b3 - 0xA1) * 3 + 1] = (byte) b2;
                all[(b3 - 0xA1) * 3 + 2] = (byte) b3;
            }
            same("row " + b2, all, new int[] { 7, 100000 }, new int[] { 3, 4096 }, heap);
        }
    }

    @Test
    public void everyAssignedTwoByteCharacter() {
        boolean[] heap = { false };
        for (int b1 = 0xA1; b1 <= 0xFE; b1++) {
            byte[] row = new byte[94 * 2];
            for (int b2 = 0xA1; b2 <= 0xFE; b2++) {
                row[(b2 - 0xA1) * 2] = (byte) b1;
                row[(b2 - 0xA1) * 2 + 1] = (byte) b2;
            }
            same("row " + b1, row, new int[] { 5, 100000 }, new int[] { 3, 4096 }, heap);
        }
        byte[] kana = new byte[256 * 2];
        for (int b2 = 0; b2 < 256; b2++) {
            kana[b2 * 2] = (byte) 0x8E;
            kana[b2 * 2 + 1] = (byte) b2;
        }
        same("half-width katakana", kana, new int[] { 5, 100000 }, new int[] { 3, 4096 }, heap);
    }

    /** What lets the scan for carriage returns be made on the bytes: no sequence of several decodes to one. */
    @Test
    public void noSequenceOfSeveralBytesDecodesToACarriageReturn() throws Exception {
        CharsetDecoder decoder = eucJp.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        for (int b1 = 0x80; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                CharBuffer decoded = decoder.decode(ByteBuffer.wrap(new byte[] { (byte) b1, (byte) b2 }));
                assertEquals("" + b1 + " " + b2, -1, decoded.toString().indexOf('\r'));
                if (b1 == 0x8F) {
                    for (int b3 : new int[] { 0x0D, 0xA1, 0xB0 }) {
                        CharBuffer three = decoder.decode(ByteBuffer.wrap(
                                new byte[] { (byte) b1, (byte) b2, (byte) b3 }));
                        assertEquals("8F " + b2 + " " + b3, -1, three.toString().indexOf('\r'));
                    }
                }
            }
        }
    }

    @Test
    public void corruptedText() throws Exception {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            byte[] data = randomJapanese(random, 100 + random.nextInt(300)).getBytes(eucJp);
            int flips = 1 + random.nextInt(3);
            for (int i = 0; i < flips; i++) {
                data[random.nextInt(data.length)] = (byte) random.nextInt(256);
            }
            if (random.nextInt(4) == 0) {
                data = java.util.Arrays.copyOf(data, data.length - random.nextInt(3));
            }
            same("corrupted " + round, data, new int[] { 1, 3, 1000 }, new int[] { 2, 5, 4096 },
                    new boolean[] { false });
        }
    }

    @Test
    public void aDocumentParsesAsItShould() throws Exception {
        StringBuilder body = new StringBuilder();
        Random random = new Random(5);
        for (int i = 0; i < 3000; i++) {
            body.append(randomJapanese(random, 40)).append('\n');
        }
        String xml = "<?xml version=\"1.0\" encoding=\"EUC-JP\"?><r a=\"\u65e5\u672c\">" + body + "</r>";
        byte[] data = xml.getBytes(eucJp);
        // Some of the random kanji are not in EUC-JP and were written as a
        // question mark; what is expected is what the JDK reads back.
        String expected = new String(body.toString().getBytes(eucJp), eucJp);
        final StringBuilder seen = new StringBuilder();
        final String[] attribute = new String[1];
        for (int chunk : new int[] { 1, 7, 4096, 100000 }) {
            seen.setLength(0);
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
            parser.parse(new InputSource(new ChunkedInputStream(new ByteArrayInputStream(data), chunk)));
            assertEquals("chunk " + chunk, expected, seen.toString());
            assertEquals("\u65e5\u672c", attribute[0]);
        }
    }
}
