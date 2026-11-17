/*
 * MarkupLegalityTest.java
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * What a comment, a CDATA section and a processing instruction may hold, and
 * where each ends: any legal character, including a lone character that is
 * the first of the terminator ({@code -}, {@code ]}, {@code ?}), but not one
 * that is illegal in the XML version of the document, and not a {@code --} in
 * a comment. Each is parsed whole and in pieces of every small size, so that
 * a terminator may be cut anywhere.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MarkupLegalityTest {

    private static final int[] CHUNKS = { 1, 2, 3, 5, 1000 };

    private enum Kind {
        COMMENT("<!--", "-->"),
        CDATA("<![CDATA[", "]]>"),
        PI("<?target ", "?>");

        final String open;
        final String close;

        Kind(String open, String close) {
            this.open = open;
            this.close = close;
        }
    }

    /** Collects what the construct held. */
    private static final class Collector extends DefaultHandler2 {
        final StringBuilder held = new StringBuilder();

        @Override
        public void comment(char[] ch, int start, int length) {
            held.append(ch, start, length);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            held.append(ch, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) {
            held.append(data);
        }
    }

    /** Parses the document in pieces of {@code chunk} bytes; returns what the construct held. */
    private static String parse(String xml, int chunk) throws Exception {
        Parser parser = new Parser();
        Collector collector = new Collector();
        parser.setContentHandler(collector);
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", collector);
        parser.parse(new InputSource(new ChunkedInputStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), chunk)));
        return collector.held.toString();
    }

    private static String document(String version, Kind kind, String body) {
        return "<?xml version=\"" + version + "\"?><r>" + kind.open + body + kind.close + "</r>";
    }

    private static void accepted(String version, Kind kind, String body) throws Exception {
        for (int chunk : CHUNKS) {
            assertEquals(kind + " " + version + " in pieces of " + chunk,
                    body, parse(document(version, kind, body), chunk));
        }
    }

    private static void refused(String version, Kind kind, String body) throws Exception {
        for (int chunk : CHUNKS) {
            try {
                parse(document(version, kind, body), chunk);
                fail(kind + " " + version + " in pieces of " + chunk + " should have been refused");
            } catch (SAXException expected) {
                // the point
            }
        }
    }

    @Test
    public void legalCharactersAreHeldWhole() throws Exception {
        for (Kind kind : Kind.values()) {
            // A PI's data may not begin with white space, which is the
            // separator after the target and so is not part of the data.
            for (String body : new String[] { "abc", "a b\tc\nd", "a\u00e9b", "a\u3042b", "x]y", "x?y",
                    "x-y", "x-y-z", "x>y", "a]]b", "a??b" }) {
                if (kind == Kind.COMMENT && body.contains("--")) {
                    continue;
                }
                if (kind == Kind.CDATA && body.contains("]]>")) {
                    continue;
                }
                accepted("1.0", kind, body);
            }
        }
    }

    @Test
    public void aLoneCharacterThatBeginsTheTerminatorIsHeld() throws Exception {
        accepted("1.0", Kind.COMMENT, "a-b");
        accepted("1.0", Kind.COMMENT, "-a");
        accepted("1.0", Kind.CDATA, "a]");
        accepted("1.0", Kind.CDATA, "a]]");
        accepted("1.0", Kind.CDATA, "]a");
        accepted("1.0", Kind.PI, "a?");
        accepted("1.0", Kind.PI, "?a");
    }

    @Test
    public void twoDashesInACommentAreRefused() throws Exception {
        refused("1.0", Kind.COMMENT, "a--b");
        refused("1.0", Kind.COMMENT, "a---");
        // A comment may not end in a dash: that and its own "-->" make "--".
        refused("1.0", Kind.COMMENT, "a-");
    }

    @Test
    public void aCharacterThatIsIllegalInXml10IsRefused() throws Exception {
        for (Kind kind : Kind.values()) {
            refused("1.0", kind, "a\u0001b");
            refused("1.0", kind, "a\u0000b");
            refused("1.0", kind, "a\ufffeb");
            refused("1.0", kind, "a\uffffb");
        }
    }

    @Test
    public void xml11HasItsOwnTableButStillRefusesWhatIsNeverLegal() throws Exception {
        for (Kind kind : Kind.values()) {
            accepted("1.1", kind, "a\u00e9b");
            accepted("1.1", kind, "x-y]z?w");
            refused("1.1", kind, "a\u0000b");
            refused("1.1", kind, "a\ufffeb");
        }
    }

    @Test
    public void anIllegalCharacterAfterALoneTerminatorCharacterIsRefused() throws Exception {
        refused("1.0", Kind.COMMENT, "a-b\u0001");
        refused("1.0", Kind.CDATA, "a]\u0001");
        refused("1.0", Kind.PI, "a?\u0001");
    }
}
