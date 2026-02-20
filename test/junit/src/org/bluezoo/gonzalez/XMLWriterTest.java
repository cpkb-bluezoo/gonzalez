package org.bluezoo.gonzalez;

import org.junit.Test;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Unit tests for XMLWriter (SAX2 interface).
 */
public class XMLWriterTest {

    // ========== Basic Element Tests ==========

    @Test
    public void testSimpleElement() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root/>", xml);
    }

    @Test
    public void testElementWithContent() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "greeting", "greeting", new AttributesImpl());
        char[] text = "Hello, World!".toCharArray();
        writer.characters(text, 0, text.length);
        writer.endElement("", "greeting", "greeting");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<greeting>Hello, World!</greeting>", xml);
    }

    @Test
    public void testNestedElements() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "parent", "parent", new AttributesImpl());
        writer.startElement("", "child", "child", new AttributesImpl());
        char[] text = "text".toCharArray();
        writer.characters(text, 0, text.length);
        writer.endElement("", "child", "child");
        writer.endElement("", "parent", "parent");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<parent><child>text</child></parent>", xml);
    }

    @Test
    public void testEmptyElementOptimization() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "container", "container", new AttributesImpl());
        writer.startElement("", "br", "br", new AttributesImpl());
        writer.endElement("", "br", "br");
        writer.startElement("", "hr", "hr", new AttributesImpl());
        writer.endElement("", "hr", "hr");
        writer.endElement("", "container", "container");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<container><br/><hr/></container>", xml);
    }

    // ========== Attribute Tests ==========

    @Test
    public void testElementWithAttribute() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        AttributesImpl atts = new AttributesImpl();
        atts.addAttribute("", "id", "id", "CDATA", "123");

        writer.startDocument();
        writer.startElement("", "item", "item", atts);
        writer.endElement("", "item", "item");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<item id=\"123\"/>", xml);
    }

    @Test
    public void testElementWithMultipleAttributes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        AttributesImpl atts = new AttributesImpl();
        atts.addAttribute("", "id", "id", "CDATA", "1");
        atts.addAttribute("", "name", "name", "CDATA", "Alice");
        atts.addAttribute("", "age", "age", "CDATA", "30");

        writer.startDocument();
        writer.startElement("", "person", "person", atts);
        writer.endElement("", "person", "person");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<person id=\"1\" name=\"Alice\" age=\"30\"/>", xml);
    }

    @Test
    public void testAttributeValueEscaping() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        AttributesImpl atts = new AttributesImpl();
        atts.addAttribute("", "value", "value", "CDATA", "\"quotes\" & <angles>");

        writer.startDocument();
        writer.startElement("", "test", "test", atts);
        writer.endElement("", "test", "test");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<test value=\"&quot;quotes&quot; &amp; &lt;angles&gt;\"/>", xml);
    }

    // ========== Namespace Tests ==========

    @Test
    public void testDefaultNamespace() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startPrefixMapping("", "http://example.com/ns");
        writer.startElement("http://example.com/ns", "root", "root", new AttributesImpl());
        writer.endElement("http://example.com/ns", "root", "root");
        writer.endPrefixMapping("");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root xmlns=\"http://example.com/ns\"/>", xml);
    }

    @Test
    public void testPrefixedNamespace() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startPrefixMapping("ex", "http://example.com/ns");
        writer.startElement("http://example.com/ns", "root", "ex:root", new AttributesImpl());
        writer.endElement("http://example.com/ns", "root", "ex:root");
        writer.endPrefixMapping("ex");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<ex:root xmlns:ex=\"http://example.com/ns\"/>", xml);
    }

    @Test
    public void testMixedNamespaces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startPrefixMapping("", "http://default.com");
        writer.startPrefixMapping("other", "http://other.com");
        writer.startElement("http://default.com", "root", "root", new AttributesImpl());
        writer.startElement("http://other.com", "child", "other:child", new AttributesImpl());
        writer.endElement("http://other.com", "child", "other:child");
        writer.endElement("http://default.com", "root", "root");
        writer.endPrefixMapping("other");
        writer.endPrefixMapping("");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root xmlns=\"http://default.com\" xmlns:other=\"http://other.com\">" +
                     "<other:child/></root>", xml);
    }

    @Test
    public void testNamespacedAttribute() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        AttributesImpl atts = new AttributesImpl();
        atts.addAttribute("http://www.w3.org/1999/xlink", "href", "xlink:href",
                          "CDATA", "http://example.com");

        writer.startDocument();
        writer.startPrefixMapping("xlink", "http://www.w3.org/1999/xlink");
        writer.startElement("", "root", "root", atts);
        writer.endElement("", "root", "root");
        writer.endPrefixMapping("xlink");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root xmlns:xlink=\"http://www.w3.org/1999/xlink\"" +
                     " xlink:href=\"http://example.com\"/>", xml);
    }

    @Test
    public void testGetPrefix() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startPrefixMapping("ex", "http://example.com");
        writer.startElement("http://example.com", "root", "ex:root", new AttributesImpl());

        assertEquals("ex", writer.getPrefix("http://example.com"));
        assertNull(writer.getPrefix("http://unknown.com"));

        writer.endElement("http://example.com", "root", "ex:root");
        writer.endPrefixMapping("ex");
        writer.endDocument();
    }

    // ========== Character Content Tests ==========

    @Test
    public void testCharacterEscaping() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "text", "text", new AttributesImpl());
        char[] chars = "5 < 10 & 10 > 5".toCharArray();
        writer.characters(chars, 0, chars.length);
        writer.endElement("", "text", "text");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<text>5 &lt; 10 &amp; 10 &gt; 5</text>", xml);
    }

    @Test
    public void testUtf8Characters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "text", "text", new AttributesImpl());
        char[] chars = "Hello \u4f60\u597d".toCharArray();
        writer.characters(chars, 0, chars.length);
        writer.endElement("", "text", "text");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("Hello \u4f60\u597d"));
    }

    @Test
    public void testCharacterArraySubset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        char[] chars = "Hello, World!".toCharArray();
        writer.startDocument();
        writer.startElement("", "text", "text", new AttributesImpl());
        writer.characters(chars, 7, 5);
        writer.endElement("", "text", "text");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<text>World</text>", xml);
    }

    // ========== CDATA Tests ==========

    @Test
    public void testCDataSection() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "code", "code", new AttributesImpl());
        writer.startCDATA();
        char[] data = "<script>alert('hello');</script>".toCharArray();
        writer.characters(data, 0, data.length);
        writer.endCDATA();
        writer.endElement("", "code", "code");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<code><![CDATA[<script>alert('hello');</script>]]></code>", xml);
    }

    // ========== Comment Tests ==========

    @Test
    public void testComment() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        char[] comment = " This is a comment ".toCharArray();
        writer.comment(comment, 0, comment.length);
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root><!-- This is a comment --></root>", xml);
    }

    // ========== Processing Instruction Tests ==========

    @Test
    public void testProcessingInstruction() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.processingInstruction("xml-stylesheet", "type=\"text/xsl\" href=\"style.xsl\"");
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?><root/>", xml);
    }

    @Test
    public void testProcessingInstructionNoData() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.processingInstruction("page-break", null);
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root><?page-break?></root>", xml);
    }

    // ========== Skipped Entity Tests ==========

    @Test
    public void testSkippedEntity() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "text", "text", new AttributesImpl());
        char[] c1 = "Copyright ".toCharArray();
        writer.characters(c1, 0, c1.length);
        writer.skippedEntity("copy");
        char[] c2 = " 2025".toCharArray();
        writer.characters(c2, 0, c2.length);
        writer.endElement("", "text", "text");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<text>Copyright &copy; 2025</text>", xml);
    }

    // ========== Indentation Tests ==========

    @Test
    public void testIndentationWithTabs() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setIndentConfig(IndentConfig.tabs());

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.startElement("", "child", "child", new AttributesImpl());
        char[] text = "text".toCharArray();
        writer.characters(text, 0, text.length);
        writer.endElement("", "child", "child");
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root>\n\t<child>text</child>\n</root>", xml);
    }

    @Test
    public void testIndentationWithSpaces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setIndentConfig(IndentConfig.spaces2());

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.startElement("", "child", "child", new AttributesImpl());
        writer.endElement("", "child", "child");
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root>\n  <child/>\n</root>", xml);
    }

    @Test
    public void testDeepNestingIndentation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setIndentConfig(IndentConfig.spaces2());

        writer.startDocument();
        writer.startElement("", "a", "a", new AttributesImpl());
        writer.startElement("", "b", "b", new AttributesImpl());
        writer.startElement("", "c", "c", new AttributesImpl());
        char[] text = "deep".toCharArray();
        writer.characters(text, 0, text.length);
        writer.endElement("", "c", "c");
        writer.endElement("", "b", "b");
        writer.endElement("", "a", "a");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<a>\n  <b>\n    <c>deep</c>\n  </b>\n</a>", xml);
    }

    @Test
    public void testIndentationWithMultipleChildren() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setIndentConfig(IndentConfig.spaces2());

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.startElement("", "child1", "child1", new AttributesImpl());
        writer.endElement("", "child1", "child1");
        writer.startElement("", "child2", "child2", new AttributesImpl());
        writer.endElement("", "child2", "child2");
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root>\n  <child1/>\n  <child2/>\n</root>", xml);
    }

    // ========== Edge Cases ==========

    @Test(expected = SAXException.class)
    public void testEndElementWithoutStart() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.startDocument();
        writer.endElement("", "root", "root");
    }

    // ========== Large Output Test ==========

    @Test
    public void testLargeOutput() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "items", "items", new AttributesImpl());
        for (int i = 0; i < 1000; i++) {
            AttributesImpl atts = new AttributesImpl();
            atts.addAttribute("", "id", "id", "CDATA", String.valueOf(i));
            writer.startElement("", "item", "item", atts);
            char[] text = ("Item number " + i).toCharArray();
            writer.characters(text, 0, text.length);
            writer.endElement("", "item", "item");
        }
        writer.endElement("", "items", "items");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.startsWith("<items><item id=\"0\">Item number 0</item>"));
        assertTrue(xml.endsWith("<item id=\"999\">Item number 999</item></items>"));
    }

    // ========== DOCTYPE Tests ==========

    @Test
    public void testDoctypeWithSystemId() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startDTD("html", null, "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
        writer.endDTD();
        writer.startElement("", "html", "html", new AttributesImpl());
        writer.endElement("", "html", "html");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("<!DOCTYPE html SYSTEM \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"));
        assertTrue(xml.contains("<html/>"));
    }

    @Test
    public void testDoctypeWithPublicId() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startDTD("html", "-//W3C//DTD XHTML 1.0//EN",
                        "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
        writer.endDTD();
        writer.startElement("", "html", "html", new AttributesImpl());
        writer.endElement("", "html", "html");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0//EN\" " +
                   "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"));
    }

    @Test
    public void testDoctypeWithInternalSubset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startDTD("root", null, null);
        writer.elementDecl("root", "(child)*");
        writer.elementDecl("child", "(#PCDATA)");
        writer.endDTD();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
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

        writer.startDocument();
        writer.startDTD("root", null, "root.dtd");
        // Internal subset declaration
        writer.elementDecl("root", "(child)*");
        // Simulate external subset
        writer.startEntity("[dtd]");
        writer.elementDecl("child", "(#PCDATA)");
        writer.endEntity("[dtd]");
        writer.endDTD();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        // Standalone mode: no SYSTEM id, all declarations included
        assertTrue(xml.contains("<!DOCTYPE root [\n"));
        assertFalse(xml.contains("root.dtd"));
        assertTrue(xml.contains("<!ELEMENT root (child)*>"));
        assertTrue(xml.contains("<!ELEMENT child (#PCDATA)>"));
    }

    @Test
    public void testDoctypeNormalFilterExternal() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        // standalone=false by default

        writer.startDocument();
        writer.startDTD("root", null, "root.dtd");
        // Internal subset declaration
        writer.elementDecl("root", "(child)*");
        // Simulate external subset
        writer.startEntity("[dtd]");
        writer.elementDecl("child", "(#PCDATA)");
        writer.endEntity("[dtd]");
        writer.endDTD();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        // Normal mode: external subset declarations suppressed
        assertTrue(xml.contains("root.dtd"));
        assertTrue(xml.contains("<!ELEMENT root (child)*>"));
        assertFalse(xml.contains("<!ELEMENT child (#PCDATA)>"));
    }

    @Test
    public void testDoctypeAttlistDecl() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startDTD("root", null, null);
        writer.elementDecl("root", "EMPTY");
        writer.attributeDecl("root", "id", "ID", "#REQUIRED", null);
        writer.attributeDecl("root", "name", "CDATA", "#IMPLIED", null);
        writer.endDTD();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("<!ATTLIST root id ID #REQUIRED>"));
        assertTrue(xml.contains("<!ATTLIST root name CDATA #IMPLIED>"));
    }

    @Test
    public void testDoctypeEntityDecl() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startDTD("root", null, null);
        writer.internalEntityDecl("copyright", "\u00A9 2025");
        writer.externalEntityDecl("logo", null, "logo.xml");
        writer.endDTD();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("<!ENTITY copyright \""));
        assertTrue(xml.contains("<!ENTITY logo SYSTEM \"logo.xml\">"));
    }

    @Test
    public void testDoctypeNotationDecl() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startDTD("root", null, null);
        writer.notationDecl("gif", null, "image/gif");
        writer.endDTD();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("<!NOTATION gif SYSTEM \"image/gif\">"));
    }

    // ========== Raw Output Tests ==========

    @Test
    public void testWriteRaw() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        writer.writeRaw("<b>raw content</b>");
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root><b>raw content</b></root>", xml);
    }

    // ========== SAX Pipeline Integration Tests ==========

    @Test
    public void testIgnorableWhitespace() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.startDocument();
        writer.startElement("", "root", "root", new AttributesImpl());
        char[] ws = "  ".toCharArray();
        writer.ignorableWhitespace(ws, 0, ws.length);
        writer.endElement("", "root", "root");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertEquals("<root>  </root>", xml);
    }

    // ========== Complex Document Test ==========

    @Test
    public void testComplexDocument() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        writer.setIndentConfig(IndentConfig.spaces2());

        writer.startDocument();
        writer.processingInstruction("xml-stylesheet", "href=\"style.css\"");

        writer.startPrefixMapping("", "http://www.w3.org/1999/xhtml");
        writer.startElement("http://www.w3.org/1999/xhtml", "html", "html", new AttributesImpl());

        writer.startElement("http://www.w3.org/1999/xhtml", "head", "head", new AttributesImpl());
        writer.startElement("http://www.w3.org/1999/xhtml", "title", "title", new AttributesImpl());
        char[] title = "Test Document".toCharArray();
        writer.characters(title, 0, title.length);
        writer.endElement("http://www.w3.org/1999/xhtml", "title", "title");
        writer.endElement("http://www.w3.org/1999/xhtml", "head", "head");

        writer.startElement("http://www.w3.org/1999/xhtml", "body", "body", new AttributesImpl());
        char[] commentText = " Main content ".toCharArray();
        writer.comment(commentText, 0, commentText.length);

        AttributesImpl pAtts = new AttributesImpl();
        pAtts.addAttribute("", "class", "class", "CDATA", "intro");
        writer.startElement("http://www.w3.org/1999/xhtml", "p", "p", pAtts);
        char[] hello = "Hello, ".toCharArray();
        writer.characters(hello, 0, hello.length);
        writer.startElement("http://www.w3.org/1999/xhtml", "strong", "strong", new AttributesImpl());
        char[] world = "World".toCharArray();
        writer.characters(world, 0, world.length);
        writer.endElement("http://www.w3.org/1999/xhtml", "strong", "strong");
        char[] excl = "!".toCharArray();
        writer.characters(excl, 0, excl.length);
        writer.endElement("http://www.w3.org/1999/xhtml", "p", "p");

        writer.startElement("http://www.w3.org/1999/xhtml", "br", "br", new AttributesImpl());
        writer.endElement("http://www.w3.org/1999/xhtml", "br", "br");
        writer.endElement("http://www.w3.org/1999/xhtml", "body", "body");

        writer.endElement("http://www.w3.org/1999/xhtml", "html", "html");
        writer.endPrefixMapping("");
        writer.endDocument();

        String xml = out.toString("UTF-8");
        assertTrue(xml.contains("xmlns=\"http://www.w3.org/1999/xhtml\""));
        assertTrue(xml.contains("<title>Test Document</title>"));
        assertTrue(xml.contains("<br/>"));
        assertTrue(xml.contains("<!-- Main content -->"));
    }
}
