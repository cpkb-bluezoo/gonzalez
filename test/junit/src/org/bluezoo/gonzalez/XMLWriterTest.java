package org.bluezoo.gonzalez;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for XMLWriter.
 *
 * @author Chris Burdess
 */
public class XMLWriterTest {

    private String write(WriterAction action) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        action.write(writer);
        writer.flush();
        return out.toString("UTF-8");
    }

    private String writeIndented(IndentConfig config, WriterAction action) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setIndentConfig(config);
        action.write(writer);
        writer.flush();
        return out.toString("UTF-8");
    }

    interface WriterAction {
        void write(XMLWriter w) throws Exception;
    }

    // ========== Basic Element Tests ==========

    @Test
    public void testSimpleElement() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertEquals("<root/>", xml);
    }

    @Test
    public void testElementWithContent() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("greeting");
                w.writeCharacters("Hello, World!");
                w.writeEndElement();
            }
        });
        assertEquals("<greeting>Hello, World!</greeting>", xml);
    }

    @Test
    public void testNestedElements() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("parent");
                w.writeStartElement("child");
                w.writeCharacters("text");
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<parent><child>text</child></parent>", xml);
    }

    @Test
    public void testEmptyElementOptimization() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("container");
                w.writeStartElement("br");
                w.writeEndElement();
                w.writeStartElement("hr");
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<container><br/><hr/></container>", xml);
    }

    // ========== Attribute Tests ==========

    @Test
    public void testElementWithAttribute() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("item");
                w.writeAttribute("id", "123");
                w.writeEndElement();
            }
        });
        assertEquals("<item id=\"123\"/>", xml);
    }

    @Test
    public void testElementWithMultipleAttributes() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("person");
                w.writeAttribute("id", "1");
                w.writeAttribute("name", "Alice");
                w.writeAttribute("age", "30");
                w.writeEndElement();
            }
        });
        assertEquals("<person id=\"1\" name=\"Alice\" age=\"30\"/>", xml);
    }

    @Test
    public void testAttributeValueEscaping() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("test");
                w.writeAttribute("value", "\"quotes\" & <angles>");
                w.writeEndElement();
            }
        });
        assertEquals("<test value=\"&quot;quotes&quot; &amp; &lt;angles&gt;\"/>", xml);
    }

    // ========== Namespace Tests ==========

    @Test
    public void testDefaultNamespace() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("http://example.com/ns", "root");
                w.writeDefaultNamespace("http://example.com/ns");
                w.writeEndElement();
            }
        });
        assertEquals("<root xmlns=\"http://example.com/ns\"/>", xml);
    }

    @Test
    public void testPrefixedNamespace() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("ex", "root", "http://example.com/ns");
                w.writeNamespace("ex", "http://example.com/ns");
                w.writeEndElement();
            }
        });
        assertEquals("<ex:root xmlns:ex=\"http://example.com/ns\"/>", xml);
    }

    @Test
    public void testMixedNamespaces() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("http://default.com", "root");
                w.writeDefaultNamespace("http://default.com");
                w.writeNamespace("other", "http://other.com");
                w.writeStartElement("other", "child", "http://other.com");
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<root xmlns=\"http://default.com\" xmlns:other=\"http://other.com\">" +
                     "<other:child/></root>", xml);
    }

    @Test
    public void testNamespacedAttribute() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeNamespace("xlink", "http://www.w3.org/1999/xlink");
                w.writeAttribute("xlink", "href", "http://www.w3.org/1999/xlink",
                                 "http://example.com");
                w.writeEndElement();
            }
        });
        assertEquals("<root xmlns:xlink=\"http://www.w3.org/1999/xlink\"" +
                     " xlink:href=\"http://example.com\"/>", xml);
    }

    @Test
    public void testGetPrefix() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("ex", "root", "http://example.com");
        writer.writeNamespace("ex", "http://example.com");

        assertEquals("ex", writer.getPrefix("http://example.com"));
        assertNull(writer.getPrefix("http://unknown.com"));

        writer.writeEndElement();
        writer.flush();
    }

    // ========== Character Content Tests ==========

    @Test
    public void testCharacterEscaping() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("5 < 10 & 10 > 5");
                w.writeEndElement();
            }
        });
        assertEquals("<text>5 &lt; 10 &amp; 10 &gt; 5</text>", xml);
    }

    @Test
    public void testUtf8Characters() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("Hello \u4f60\u597d");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("Hello \u4f60\u597d"));
    }

    @Test
    public void testCharacterArraySubset() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                char[] chars = "Hello, World!".toCharArray();
                w.writeStartElement("text");
                w.writeCharacters(chars, 7, 5);
                w.writeEndElement();
            }
        });
        assertEquals("<text>World</text>", xml);
    }

    // ========== CDATA Tests ==========

    @Test
    public void testCDataSection() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("code");
                w.writeStartCDATA();
                w.writeCharacters("<script>alert('hello');</script>");
                w.writeEndCDATA();
                w.writeEndElement();
            }
        });
        assertEquals("<code><![CDATA[<script>alert('hello');</script>]]></code>", xml);
    }

    // ========== Comment Tests ==========

    @Test
    public void testComment() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeComment(" This is a comment ");
                w.writeEndElement();
            }
        });
        assertEquals("<root><!-- This is a comment --></root>", xml);
    }

    // ========== Processing Instruction Tests ==========

    @Test
    public void testProcessingInstruction() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeProcessingInstruction("xml-stylesheet",
                    "type=\"text/xsl\" href=\"style.xsl\"");
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertEquals("<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?><root/>", xml);
    }

    @Test
    public void testProcessingInstructionNoData() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeProcessingInstruction("page-break");
                w.writeEndElement();
            }
        });
        assertEquals("<root><?page-break?></root>", xml);
    }

    // ========== Skipped Entity Tests ==========

    @Test
    public void testEntityRef() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("Copyright ");
                w.writeEntityRef("copy");
                w.writeCharacters(" 2025");
                w.writeEndElement();
            }
        });
        assertEquals("<text>Copyright &copy; 2025</text>", xml);
    }

    // ========== Indentation Tests ==========

    @Test
    public void testIndentationWithTabs() throws Exception {
        String xml = writeIndented(IndentConfig.tabs(), new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeStartElement("child");
                w.writeCharacters("text");
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<root>\n\t<child>text</child>\n</root>", xml);
    }

    @Test
    public void testIndentationWithSpaces() throws Exception {
        String xml = writeIndented(IndentConfig.spaces2(), new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeStartElement("child");
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<root>\n  <child/>\n</root>", xml);
    }

    @Test
    public void testDeepNestingIndentation() throws Exception {
        String xml = writeIndented(IndentConfig.spaces2(), new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("a");
                w.writeStartElement("b");
                w.writeStartElement("c");
                w.writeCharacters("deep");
                w.writeEndElement();
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<a>\n  <b>\n    <c>deep</c>\n  </b>\n</a>", xml);
    }

    @Test
    public void testIndentationWithMultipleChildren() throws Exception {
        String xml = writeIndented(IndentConfig.spaces2(), new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeStartElement("child1");
                w.writeEndElement();
                w.writeStartElement("child2");
                w.writeEndElement();
                w.writeEndElement();
            }
        });
        assertEquals("<root>\n  <child1/>\n  <child2/>\n</root>", xml);
    }

    // ========== Edge Cases ==========

    @Test(expected = IOException.class)
    public void testEndElementWithoutStart() throws Exception {
        write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeEndElement();
            }
        });
    }

    // ========== Large Output Test ==========

    @Test
    public void testLargeOutput() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("items");
                for (int i = 0; i < 1000; i++) {
                    w.writeStartElement("item");
                    w.writeAttribute("id", String.valueOf(i));
                    w.writeCharacters("Item number " + i);
                    w.writeEndElement();
                }
                w.writeEndElement();
            }
        });
        assertTrue(xml.startsWith("<items><item id=\"0\">Item number 0</item>"));
        assertTrue(xml.endsWith("<item id=\"999\">Item number 999</item></items>"));
    }

    // ========== DOCTYPE Tests ==========

    @Test
    public void testDoctypeWithSystemId() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("html", null,
                    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
                w.writeEndDTD();
                w.writeStartElement("html");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains(
            "<!DOCTYPE html SYSTEM \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"));
        assertTrue(xml.contains("<html/>"));
    }

    @Test
    public void testDoctypeWithPublicId() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("html", "-//W3C//DTD XHTML 1.0//EN",
                    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
                w.writeEndDTD();
                w.writeStartElement("html");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains(
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0//EN\" " +
            "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"));
    }

    @Test
    public void testDoctypeWithInternalSubset() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("root", null, null);
                w.writeElementDecl("root", "(child)*");
                w.writeElementDecl("child", "(#PCDATA)");
                w.writeEndDTD();
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("<!DOCTYPE root [\n"));
        assertTrue(xml.contains("<!ELEMENT root (child)*>"));
        assertTrue(xml.contains("<!ELEMENT child (#PCDATA)>"));
        assertTrue(xml.contains("]>"));
    }

    @Test
    public void testDoctypeStandaloneConversion() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setStandalone(true);

        writer.writeStartDTD("root", null, "root.dtd");
        writer.writeElementDecl("root", "(child)*");
        writer.startExternalSubset();
        writer.writeElementDecl("child", "(#PCDATA)");
        writer.endExternalSubset();
        writer.writeEndDTD();
        writer.writeStartElement("root");
        writer.writeEndElement();
        writer.flush();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("<!DOCTYPE root [\n"));
        assertFalse(xml.contains("root.dtd"));
        assertTrue(xml.contains("<!ELEMENT root (child)*>"));
        assertTrue(xml.contains("<!ELEMENT child (#PCDATA)>"));
    }

    @Test
    public void testDoctypeNormalFilterExternal() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("root", null, "root.dtd");
                w.writeElementDecl("root", "(child)*");
                w.startExternalSubset();
                w.writeElementDecl("child", "(#PCDATA)");
                w.endExternalSubset();
                w.writeEndDTD();
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("root.dtd"));
        assertTrue(xml.contains("<!ELEMENT root (child)*>"));
        assertFalse(xml.contains("<!ELEMENT child (#PCDATA)>"));
    }

    @Test
    public void testDoctypeAttlistDecl() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("root", null, null);
                w.writeElementDecl("root", "EMPTY");
                w.writeAttributeDecl("root", "id", "ID", "#REQUIRED", null);
                w.writeAttributeDecl("root", "name", "CDATA", "#IMPLIED", null);
                w.writeEndDTD();
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("<!ATTLIST root id ID #REQUIRED>"));
        assertTrue(xml.contains("<!ATTLIST root name CDATA #IMPLIED>"));
    }

    @Test
    public void testDoctypeEntityDecl() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("root", null, null);
                w.writeInternalEntityDecl("copyright", "\u00A9 2025");
                w.writeExternalEntityDecl("logo", null, "logo.xml");
                w.writeEndDTD();
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("<!ENTITY copyright \""));
        assertTrue(xml.contains("<!ENTITY logo SYSTEM \"logo.xml\">"));
    }

    @Test
    public void testDoctypeNotationDecl() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("root", null, null);
                w.writeNotationDecl("gif", null, "image/gif");
                w.writeEndDTD();
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("<!NOTATION gif SYSTEM \"image/gif\">"));
    }

    // ========== UTF-16 Encoding Tests ==========

    private String writeWithCharset(Charset cs, WriterAction action) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(cs);
        action.write(writer);
        writer.flush();
        return new String(out.toByteArray(), cs);
    }

    @Test
    public void testUtf16BESimpleElement() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertEquals("<root/>", xml);
    }

    @Test
    public void testUtf16BEElementWithContent() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("greeting");
                w.writeCharacters("Hello, World!");
                w.writeEndElement();
            }
        });
        assertEquals("<greeting>Hello, World!</greeting>", xml);
    }

    @Test
    public void testUtf16BECharacterEscaping() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("5 < 10 & 10 > 5");
                w.writeEndElement();
            }
        });
        assertEquals("<text>5 &lt; 10 &amp; 10 &gt; 5</text>", xml);
    }

    @Test
    public void testUtf16BEAttributes() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("item");
                w.writeAttribute("id", "1");
                w.writeAttribute("value", "\"test\"");
                w.writeEndElement();
            }
        });
        assertEquals("<item id=\"1\" value=\"&quot;test&quot;\"/>", xml);
    }

    @Test
    public void testUtf16BEComment() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeComment(" comment ");
                w.writeEndElement();
            }
        });
        assertEquals("<root><!-- comment --></root>", xml);
    }

    @Test
    public void testUtf16BECData() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("code");
                w.writeStartCDATA();
                w.writeCharacters("<tag/>");
                w.writeEndCDATA();
                w.writeEndElement();
            }
        });
        assertEquals("<code><![CDATA[<tag/>]]></code>", xml);
    }

    @Test
    public void testUtf16BEPI() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeProcessingInstruction("target", "data");
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertEquals("<?target data?><root/>", xml);
    }

    @Test
    public void testUtf16BEEntityRef() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeEntityRef("amp");
                w.writeEndElement();
            }
        });
        assertEquals("<text>&amp;</text>", xml);
    }

    @Test
    public void testUtf16BENamespace() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("ex", "root", "http://example.com");
                w.writeNamespace("ex", "http://example.com");
                w.writeEndElement();
            }
        });
        assertEquals("<ex:root xmlns:ex=\"http://example.com\"/>", xml);
    }

    @Test
    public void testUtf16BENonAsciiContent() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("Hello \u4f60\u597d");
                w.writeEndElement();
            }
        });
        assertEquals("<text>Hello \u4f60\u597d</text>", xml);
    }

    @Test
    public void testUtf16BEBytePattern() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(StandardCharsets.UTF_16BE);

        writer.writeStartElement("a");
        writer.writeEndElement();
        writer.flush();

        byte[] bytes = out.toByteArray();
        assertEquals(8, bytes.length);
        assertEquals(0x00, bytes[0] & 0xFF);
        assertEquals(0x3C, bytes[1] & 0xFF);
        assertEquals(0x00, bytes[2] & 0xFF);
        assertEquals(0x61, bytes[3] & 0xFF);
        assertEquals(0x00, bytes[4] & 0xFF);
        assertEquals(0x2F, bytes[5] & 0xFF);
        assertEquals(0x00, bytes[6] & 0xFF);
        assertEquals(0x3E, bytes[7] & 0xFF);
    }

    @Test
    public void testUtf16BEDoctype() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16BE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartDTD("root", null, null);
                w.writeElementDecl("root", "(child)*");
                w.writeEndDTD();
                w.writeStartElement("root");
                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("<!DOCTYPE root"));
        assertTrue(xml.contains("<!ELEMENT root (child)*>"));
        assertTrue(xml.contains("<root/>"));
    }

    @Test
    public void testUtf16BEIndentation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(StandardCharsets.UTF_16BE);
        writer.setIndentConfig(IndentConfig.spaces2());

        writer.writeStartElement("root");
        writer.writeStartElement("child");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.flush();

        String xml = new String(out.toByteArray(), StandardCharsets.UTF_16BE);
        assertEquals("<root>\n  <child/>\n</root>", xml);
    }

    // ========== UTF-16LE Tests ==========

    @Test
    public void testUtf16LESimpleElement() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16LE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeCharacters("test");
                w.writeEndElement();
            }
        });
        assertEquals("<root>test</root>", xml);
    }

    @Test
    public void testUtf16LEBytePattern() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(StandardCharsets.UTF_16LE);

        writer.writeStartElement("a");
        writer.writeEndElement();
        writer.flush();

        byte[] bytes = out.toByteArray();
        assertEquals(8, bytes.length);
        assertEquals(0x3C, bytes[0] & 0xFF);
        assertEquals(0x00, bytes[1] & 0xFF);
        assertEquals(0x61, bytes[2] & 0xFF);
        assertEquals(0x00, bytes[3] & 0xFF);
        assertEquals(0x2F, bytes[4] & 0xFF);
        assertEquals(0x00, bytes[5] & 0xFF);
        assertEquals(0x3E, bytes[6] & 0xFF);
        assertEquals(0x00, bytes[7] & 0xFF);
    }

    @Test
    public void testUtf16LECharacterEscaping() throws Exception {
        String xml = writeWithCharset(StandardCharsets.UTF_16LE, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("a < b & c > d");
                w.writeEndElement();
            }
        });
        assertEquals("<text>a &lt; b &amp; c &gt; d</text>", xml);
    }

    // ========== UTF-32 Encoding Tests ==========

    @Test
    public void testUtf32BESimpleElement() throws Exception {
        Charset utf32be = Charset.forName("UTF-32BE");
        String xml = writeWithCharset(utf32be, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeCharacters("test");
                w.writeEndElement();
            }
        });
        assertEquals("<root>test</root>", xml);
    }

    @Test
    public void testUtf32BEBytePattern() throws Exception {
        Charset utf32be = Charset.forName("UTF-32BE");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setCharset(utf32be);

        writer.writeStartElement("a");
        writer.writeEndElement();
        writer.flush();

        byte[] bytes = out.toByteArray();
        assertEquals(16, bytes.length);
        assertEquals(0x00, bytes[0] & 0xFF);
        assertEquals(0x00, bytes[1] & 0xFF);
        assertEquals(0x00, bytes[2] & 0xFF);
        assertEquals(0x3C, bytes[3] & 0xFF);
        assertEquals(0x00, bytes[4] & 0xFF);
        assertEquals(0x00, bytes[5] & 0xFF);
        assertEquals(0x00, bytes[6] & 0xFF);
        assertEquals(0x61, bytes[7] & 0xFF);
        assertEquals(0x00, bytes[8] & 0xFF);
        assertEquals(0x00, bytes[9] & 0xFF);
        assertEquals(0x00, bytes[10] & 0xFF);
        assertEquals(0x2F, bytes[11] & 0xFF);
        assertEquals(0x00, bytes[12] & 0xFF);
        assertEquals(0x00, bytes[13] & 0xFF);
        assertEquals(0x00, bytes[14] & 0xFF);
        assertEquals(0x3E, bytes[15] & 0xFF);
    }

    @Test
    public void testUtf32LESimpleElement() throws Exception {
        Charset utf32le = Charset.forName("UTF-32LE");
        String xml = writeWithCharset(utf32le, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeCharacters("test");
                w.writeEndElement();
            }
        });
        assertEquals("<root>test</root>", xml);
    }

    @Test
    public void testUtf32BECharacterEscaping() throws Exception {
        Charset utf32be = Charset.forName("UTF-32BE");
        String xml = writeWithCharset(utf32be, new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("text");
                w.writeCharacters("a < b & c > d");
                w.writeEndElement();
            }
        });
        assertEquals("<text>a &lt; b &amp; c &gt; d</text>", xml);
    }

    // ========== Raw Output Tests ==========

    @Test
    public void testWriteRaw() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeRaw("<b>raw content</b>");
                w.writeEndElement();
            }
        });
        assertEquals("<root><b>raw content</b></root>", xml);
    }

    // ========== Whitespace Tests ==========

    @Test
    public void testWhitespaceContent() throws Exception {
        String xml = write(new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeStartElement("root");
                w.writeCharacters("  ");
                w.writeEndElement();
            }
        });
        assertEquals("<root>  </root>", xml);
    }

    // ========== Complex Document Test ==========

    @Test
    public void testComplexDocument() throws Exception {
        String xml = writeIndented(IndentConfig.spaces2(), new WriterAction() {
            public void write(XMLWriter w) throws Exception {
                w.writeProcessingInstruction("xml-stylesheet", "href=\"style.css\"");

                w.writeStartElement("http://www.w3.org/1999/xhtml", "html");
                w.writeDefaultNamespace("http://www.w3.org/1999/xhtml");

                w.writeStartElement("http://www.w3.org/1999/xhtml", "head");
                w.writeStartElement("http://www.w3.org/1999/xhtml", "title");
                w.writeCharacters("Test Document");
                w.writeEndElement();
                w.writeEndElement();

                w.writeStartElement("http://www.w3.org/1999/xhtml", "body");
                w.writeComment(" Main content ");

                w.writeStartElement("http://www.w3.org/1999/xhtml", "p");
                w.writeAttribute("class", "intro");
                w.writeCharacters("Hello, ");
                w.writeStartElement("http://www.w3.org/1999/xhtml", "strong");
                w.writeCharacters("World");
                w.writeEndElement();
                w.writeCharacters("!");
                w.writeEndElement();

                w.writeStartElement("http://www.w3.org/1999/xhtml", "br");
                w.writeEndElement();
                w.writeEndElement();

                w.writeEndElement();
            }
        });
        assertTrue(xml.contains("xmlns=\"http://www.w3.org/1999/xhtml\""));
        assertTrue(xml.contains("<title>Test Document</title>"));
        assertTrue(xml.contains("<br/>"));
        assertTrue(xml.contains("<!-- Main content -->"));
    }
}
