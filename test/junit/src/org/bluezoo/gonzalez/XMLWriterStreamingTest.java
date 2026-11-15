/*
 * XMLWriterStreamingTest.java
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
 */

package org.bluezoo.gonzalez;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An attribute value, a comment, the data of a processing instruction
 * and character data can each be written in pieces, so that neither the
 * caller nor the writer has to hold the whole of one. However it is
 * divided, what is written is what a single call would have written, and
 * the writer's buffer stays the size it started.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
// The whole-value write methods are deprecated but still supported, so they stay covered.
@SuppressWarnings("deprecation")
public class XMLWriterStreamingTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private static final Charset[] CHARSETS = { StandardCharsets.UTF_8, StandardCharsets.UTF_16BE,
        StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII };

    private static final int ATTRIBUTE = 0;
    private static final int COMMENT = 1;
    private static final int PI = 2;
    private static final int TEXT = 3;
    private static final int CDATA = 4;
    private static final int KINDS = 5;

    private static final String[] KIND_NAMES = { "attribute", "comment", "processing instruction", "text",
        "CDATA section" };

    /** Content with something awkward for each kind: characters to
     *  escape, the delimiters that must not appear, surrogate pairs and
     *  what some charsets cannot encode. */
    private static final String[] CONTENTS = {
        "",
        "plain",
        "a<b>&\"c\"'d'\t\n\r",
        "--", "a--b", "---", "a-", "-", "-a-b-",
        "?>", "a?>b", "??>>", "a?", "?", ">",
        "]]", "]] >", "a]b]",
        "x" + EMOJI + EMOJI + "y" + (char) 0xE9 + (char) 0x20AC + EMOJI,
        EMOJI.substring(0, 1) + "x", "x" + EMOJI.substring(0, 1), EMOJI.substring(1),
    };

    // ===== The same output however the content is divided =====

    @Test
    public void testEveryDivisionWritesTheSame() throws Exception {
        for (int c = 0; c < CHARSETS.length; c++) {
            for (int kind = 0; kind < KINDS; kind++) {
                for (int k = 0; k < CONTENTS.length; k++) {
                    String content = CONTENTS[k];
                    byte[] whole = writeWhole(CHARSETS[c], kind, content, false);
                    String label = KIND_NAMES[kind] + " in " + CHARSETS[c] + " content " + k;
                    assertArrayEquals(label + " in one piece", whole,
                            writePieces(CHARSETS[c], kind, content, new int[0], false));
                    for (int i = 1; i < content.length(); i++) {
                        assertArrayEquals(label + " split at " + i, whole,
                                writePieces(CHARSETS[c], kind, content, new int[] { i }, false));
                        for (int j = i + 1; j < content.length(); j++) {
                            assertArrayEquals(label + " split at " + i + " and " + j, whole,
                                    writePieces(CHARSETS[c], kind, content, new int[] { i, j }, false));
                        }
                    }
                    assertArrayEquals(label + " one character at a time", whole,
                            writePieces(CHARSETS[c], kind, content, everyChar(content.length()), false));
                }
            }
        }
    }

    @Test
    public void testIndentedOutputIsTheSame() throws Exception {
        for (int kind = 0; kind < KINDS; kind++) {
            for (int k = 0; k < CONTENTS.length; k++) {
                String content = CONTENTS[k];
                assertArrayEquals(KIND_NAMES[kind] + " content " + k,
                        writeWhole(StandardCharsets.UTF_8, kind, content, true),
                        writePieces(StandardCharsets.UTF_8, kind, content, everyChar(content.length()), true));
            }
        }
    }

    // ===== What is written =====

    @Test
    public void testAttributeInPieces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeStartAttribute("a");
        writer.writeAttributeContent("one ");
        writer.writeAttributeContent(new StringBuilder("<two> "));
        writer.writeAttributeContent(CharBuffer.wrap("\"three\""));
        writer.writeEndAttribute();
        writer.writeStartAttribute("p:b");
        writer.writeEndAttribute();
        writer.writeAttribute("c", "whole");
        writer.writeCharacters("t");
        writer.writeEndElement();
        writer.close();
        assertEquals("<r a=\"one &lt;two&gt; &quot;three&quot;\" p:b=\"\" c=\"whole\">t</r>", utf8(out));
    }

    @Test
    public void testCommentInPieces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeStartComment();
        writer.writeCommentContent(" a <b> & c -");
        writer.writeCommentContent("- d -");
        writer.writeEndComment();
        writer.writeStartComment();
        writer.writeEndComment();
        writer.writeEndElement();
        writer.close();
        // The two hyphens came in different calls, and the last would
        // otherwise run into the closing delimiter.
        assertEquals("<r><!-- a <b> & c - - d - --><!----></r>", utf8(out));
    }

    @Test
    public void testProcessingInstructionInPieces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartProcessingInstruction("one");
        writer.writeProcessingInstructionContent("a=\"1\" ?");
        writer.writeProcessingInstructionContent("> b");
        writer.writeEndProcessingInstruction();
        writer.writeStartProcessingInstruction("two");
        writer.writeEndProcessingInstruction();
        writer.writeStartProcessingInstruction("three");
        writer.writeProcessingInstructionContent("");
        writer.writeProcessingInstructionContent("x");
        writer.writeEndProcessingInstruction();
        writer.writeStartElement("r");
        writer.writeEndElement();
        writer.close();
        assertEquals("<?one a=\"1\" ? > b?><?two?><?three x?><r/>", utf8(out));
    }

    @Test
    public void testCharactersFromAnyCharSequence() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeCharacters(new StringBuilder("a<b "));
        // A buffer that is part way through a larger one, as a parser's is.
        CharBuffer buffer = CharBuffer.wrap("skipped&kept ignored");
        buffer.position(7);
        buffer.limit(13);
        writer.writeCharacters(buffer);
        assertEquals("The buffer is left as it was", 7, buffer.position());
        assertEquals(13, buffer.limit());
        writer.writeCharacters((CharSequence) null);
        writer.writeCharacters(CharBuffer.allocate(0));
        writer.writeStartCDATA();
        writer.writeCharacters(new StringBuilder("<c>&"));
        writer.writeEndCDATA();
        writer.writeEndElement();
        writer.close();
        assertEquals("<r>a&lt;b &amp;kept <![CDATA[<c>&]]></r>", utf8(out));
    }

    @Test
    public void testCommentLeftOutOfTheDTDInPieces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartDTD("r", null, "r.dtd");
        writer.writeStartComment();
        writer.writeCommentContent(" internal ");
        writer.writeEndComment();
        writer.startExternalSubset();
        writer.writeStartComment();
        writer.writeCommentContent(" external ");
        writer.writeEndComment();
        writer.endExternalSubset();
        writer.writeEndDTD();
        writer.writeStartElement("r");
        writer.writeEndElement();
        writer.close();
        assertEquals("<!DOCTYPE r SYSTEM \"r.dtd\" [\n<!-- internal -->\n]><r/>", utf8(out));
    }

    // ===== The CDATA end delimiter =====

    @Test
    public void testCDATAEndDelimiterDividedBetweenCallsIsRefused() throws Exception {
        String[][] divisions = { { "a]]>b" }, { "a]", "]>b" }, { "a]]", ">b" }, { "a", "]", "]", ">", "b" },
            { "]]]", ">" } };
        for (int d = 0; d < divisions.length; d++) {
            for (int form = 0; form < 3; form++) {
                XMLWriter writer = new XMLWriter(new ByteArrayOutputStream());
                writer.writeStartElement("r");
                writer.writeStartCDATA();
                try {
                    for (int i = 0; i < divisions[d].length; i++) {
                        String piece = divisions[d][i];
                        if (form == 0) {
                            writer.writeCharacters(piece);
                        } else if (form == 1) {
                            writer.writeCharacters(piece.toCharArray(), 0, piece.length());
                        } else {
                            writer.writeCharacters(CharBuffer.wrap(piece));
                        }
                    }
                    fail("Expected IOException for division " + d + " form " + form);
                } catch (IOException e) {
                    assertTrue("Was: " + e.getMessage(), e.getMessage().contains("]]>"));
                }
            }
        }
    }

    @Test
    public void testBracketsThatDoNotEndTheSectionAreAllowed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeStartCDATA();
        writer.writeCharacters("a]]");
        writer.writeCharacters(" >b]");
        writer.writeCharacters("]");
        writer.writeEndCDATA();
        // A new section starts afresh: this is not "]]>".
        writer.writeStartCDATA();
        writer.writeCharacters(">");
        writer.writeEndCDATA();
        writer.writeEndElement();
        writer.close();
        assertEquals("<r><![CDATA[a]] >b]]]]><![CDATA[>]]></r>", utf8(out));
    }

    // ===== Order of calls =====

    @Test
    public void testContentAndEndNeedAStart() throws Exception {
        XMLWriter writer = new XMLWriter(new ByteArrayOutputStream());
        writer.writeStartElement("r");
        assertRefused(writer, "writeAttributeContent");
        assertRefused(writer, "writeEndAttribute");
        assertRefused(writer, "writeCommentContent");
        assertRefused(writer, "writeEndComment");
        assertRefused(writer, "writeProcessingInstructionContent");
        assertRefused(writer, "writeEndProcessingInstruction");
    }

    @Test
    public void testAttributeNeedsAnOpenStartTag() throws Exception {
        XMLWriter writer = new XMLWriter(new ByteArrayOutputStream());
        assertRefused(writer, "writeStartAttribute");
        writer.writeStartElement("r");
        writer.writeCharacters("t");
        assertRefused(writer, "writeStartAttribute");
    }

    @Test
    public void testNothingElseMayBeWrittenUntilThePiecesAreFinished() throws Exception {
        String[] starts = { "writeStartAttribute", "writeStartComment", "writeStartProcessingInstruction" };
        String[] others = { "writeStartElement", "writeEndElement", "writeAttribute", "writeNamespace",
            "writeDefaultNamespace", "writeCharacters", "writeCharactersArray", "writeCharactersSequence",
            "writeStartCDATA", "writeComment", "writeProcessingInstruction", "writeEntityRef", "writeRaw",
            "writeStartAttribute", "writeStartComment", "writeStartProcessingInstruction" };
        for (int s = 0; s < starts.length; s++) {
            for (int o = 0; o < others.length; o++) {
                XMLWriter writer = new XMLWriter(new ByteArrayOutputStream());
                writer.writeStartElement("r");
                call(writer, starts[s]);
                try {
                    call(writer, others[o]);
                    fail("Expected IOException for " + others[o] + " after " + starts[s]);
                } catch (IOException e) {
                    // Refused, whether for what is unfinished or, as an
                    // attribute after a comment is, for its own reasons.
                    assertTrue(e.getMessage() != null);
                }
            }
        }
    }

    /** Sending what there is so far does not end a start tag that has an
     *  attribute still being written. */
    @Test
    public void testFlushInTheMiddleOfAnAttribute() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement("r");
        writer.writeStartAttribute("a");
        writer.writeAttributeContent("one" + EMOJI.substring(0, 1));
        writer.flush();
        assertEquals("<r a=\"one", utf8(out));
        writer.writeAttributeContent(EMOJI.substring(1) + "two");
        writer.writeEndAttribute();
        writer.writeEndElement();
        writer.close();
        assertEquals("<r a=\"one" + EMOJI + "two\"/>", utf8(out));
    }

    // ===== The buffer =====

    /**
     * A megabyte of each kind of content, a kilobyte at a time and then
     * in a single call, leaves the buffer the size it was created, and
     * is never handed to the channel in a larger piece than that.
     */
    @Test
    public void testBufferDoesNotGrowWithTheContent() throws Exception {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 1000000) {
            builder.append("abc <d> &e; \"f\" -g ?h ").append((char) 0xE9).append((char) 0x20AC).append(EMOJI);
        }
        String large = builder.toString();
        for (int kind = 0; kind < KINDS; kind++) {
            for (int pieces = 0; pieces < 2; pieces++) {
                CountingChannel channel = new CountingChannel();
                XMLWriter writer = new XMLWriter(channel);
                int capacity = writer.bufferCapacity();
                writer.writeStartElement("r");
                if (pieces == 0) {
                    write(writer, kind, large);
                } else {
                    start(writer, kind);
                    for (int i = 0; i < large.length(); i += 1024) {
                        content(writer, kind, CharBuffer.wrap(large, i, Math.min(large.length(), i + 1024)));
                    }
                    end(writer, kind);
                }
                writer.writeEndElement();
                writer.close();
                String label = KIND_NAMES[kind] + (pieces == 0 ? " in one call" : " in pieces");
                assertEquals(label + ": buffer capacity", capacity, writer.bufferCapacity());
                assertTrue(label + ": largest write was " + channel.largest, channel.largest <= capacity);
                assertTrue(label + ": wrote " + channel.total, channel.total >= large.length());
            }
        }
    }

    /** One item that cannot fit is the only thing the buffer grows for. */
    @Test
    public void testNameLongerThanTheBuffer() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            name.append((char) ('a' + i % 26));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.writeStartElement(name.toString());
        writer.writeStartAttribute(name.toString());
        writer.writeAttributeContent("v");
        writer.writeEndAttribute();
        writer.writeCharacters("t");
        writer.writeEndElement();
        writer.close();
        assertEquals("<" + name + " " + name + "=\"v\">t</" + name + ">", utf8(out));
    }

    // ===== Helpers =====

    private static String utf8(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int[] everyChar(int length) {
        int[] splits = new int[Math.max(0, length - 1)];
        for (int i = 0; i < splits.length; i++) {
            splits[i] = i + 1;
        }
        return splits;
    }

    /** Writes the content with the method that takes it whole. */
    private static void write(XMLWriter writer, int kind, String content) throws IOException {
        switch (kind) {
            case ATTRIBUTE:
                writer.writeAttribute("a", content);
                break;
            case COMMENT:
                writer.writeComment(content);
                break;
            case PI:
                writer.writeProcessingInstruction("p", content);
                break;
            case TEXT:
                writer.writeCharacters(content);
                break;
            default:
                writer.writeStartCDATA();
                writer.writeCharacters(content);
                writer.writeEndCDATA();
                break;
        }
    }

    private static void start(XMLWriter writer, int kind) throws IOException {
        switch (kind) {
            case ATTRIBUTE:
                writer.writeStartAttribute("a");
                break;
            case COMMENT:
                writer.writeStartComment();
                break;
            case PI:
                writer.writeStartProcessingInstruction("p");
                break;
            case TEXT:
                break;
            default:
                writer.writeStartCDATA();
                break;
        }
    }

    private static void content(XMLWriter writer, int kind, CharSequence content) throws IOException {
        switch (kind) {
            case ATTRIBUTE:
                writer.writeAttributeContent(content);
                break;
            case COMMENT:
                writer.writeCommentContent(content);
                break;
            case PI:
                writer.writeProcessingInstructionContent(content);
                break;
            default:
                writer.writeCharacters(content);
                break;
        }
    }

    private static void end(XMLWriter writer, int kind) throws IOException {
        switch (kind) {
            case ATTRIBUTE:
                writer.writeEndAttribute();
                break;
            case COMMENT:
                writer.writeEndComment();
                break;
            case PI:
                writer.writeEndProcessingInstruction();
                break;
            case TEXT:
                break;
            default:
                writer.writeEndCDATA();
                break;
        }
    }

    /** A document with the content in it, written whole. */
    private static byte[] writeWhole(Charset charset, int kind, String content, boolean indent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = newWriter(out, charset, indent);
        writer.writeStartElement("r");
        writer.writeStartElement("s");
        write(writer, kind, content);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();
        return out.toByteArray();
    }

    /** The same document with the content written in the pieces that
     *  {@code splits} divides it into. */
    private static byte[] writePieces(Charset charset, int kind, String content, int[] splits, boolean indent)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = newWriter(out, charset, indent);
        writer.writeStartElement("r");
        writer.writeStartElement("s");
        start(writer, kind);
        int offset = 0;
        for (int i = 0; i <= splits.length; i++) {
            int next = (i < splits.length) ? splits[i] : content.length();
            // Alternate the kinds of sequence the pieces arrive as.
            if (i % 2 == 0) {
                content(writer, kind, CharBuffer.wrap(content, offset, next));
            } else {
                content(writer, kind, content.substring(offset, next));
            }
            offset = next;
        }
        end(writer, kind);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();
        return out.toByteArray();
    }

    private static XMLWriter newWriter(ByteArrayOutputStream out, Charset charset, boolean indent) {
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(charset);
        if (indent) {
            writer.setIndentConfig(IndentConfig.spaces2());
        }
        return writer;
    }

    private static void assertRefused(XMLWriter writer, String method) {
        try {
            call(writer, method);
            fail("Expected IOException for " + method + "()");
        } catch (IOException e) {
            // expected
        }
    }

    private static void call(XMLWriter writer, String method) throws IOException {
        if (method.equals("writeStartAttribute")) {
            writer.writeStartAttribute("a");
        } else if (method.equals("writeAttributeContent")) {
            writer.writeAttributeContent("x");
        } else if (method.equals("writeEndAttribute")) {
            writer.writeEndAttribute();
        } else if (method.equals("writeStartComment")) {
            writer.writeStartComment();
        } else if (method.equals("writeCommentContent")) {
            writer.writeCommentContent("x");
        } else if (method.equals("writeEndComment")) {
            writer.writeEndComment();
        } else if (method.equals("writeStartProcessingInstruction")) {
            writer.writeStartProcessingInstruction("p");
        } else if (method.equals("writeProcessingInstructionContent")) {
            writer.writeProcessingInstructionContent("x");
        } else if (method.equals("writeEndProcessingInstruction")) {
            writer.writeEndProcessingInstruction();
        } else if (method.equals("writeStartElement")) {
            writer.writeStartElement("s");
        } else if (method.equals("writeEndElement")) {
            writer.writeEndElement();
        } else if (method.equals("writeAttribute")) {
            writer.writeAttribute("b", "x");
        } else if (method.equals("writeNamespace")) {
            writer.writeNamespace("p", "urn:p");
        } else if (method.equals("writeDefaultNamespace")) {
            writer.writeDefaultNamespace("urn:d");
        } else if (method.equals("writeCharacters")) {
            writer.writeCharacters("x");
        } else if (method.equals("writeCharactersArray")) {
            writer.writeCharacters(new char[] { 'x' }, 0, 1);
        } else if (method.equals("writeCharactersSequence")) {
            writer.writeCharacters(new StringBuilder("x"));
        } else if (method.equals("writeStartCDATA")) {
            writer.writeStartCDATA();
        } else if (method.equals("writeComment")) {
            writer.writeComment("x");
        } else if (method.equals("writeProcessingInstruction")) {
            writer.writeProcessingInstruction("p", "x");
        } else if (method.equals("writeEntityRef")) {
            writer.writeEntityRef("e");
        } else if (method.equals("writeRaw")) {
            writer.writeRaw("x");
        } else {
            throw new IllegalArgumentException(method);
        }
    }

    /** Counts what it is given and remembers the largest piece. */
    private static class CountingChannel implements WritableByteChannel {

        long total;
        int largest;

        @Override
        public int write(ByteBuffer src) {
            int n = src.remaining();
            total += n;
            largest = Math.max(largest, n);
            src.position(src.limit());
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }

}
