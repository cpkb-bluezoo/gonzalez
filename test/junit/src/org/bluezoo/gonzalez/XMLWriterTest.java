package org.bluezoo.gonzalez;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for XMLWriter.
 */
public class XMLWriterTest {

    // ========== Basic Element Tests ==========

    @Test
    public void testSimpleElement() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root/>", xml);
    }

    @Test
    public void testElementWithContent() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("greeting");
        writer.writeCharacters("Hello, World!");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<greeting>Hello, World!</greeting>", xml);
    }

    @Test
    public void testNestedElements() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("parent");
        writer.writeStartElement("child");
        writer.writeCharacters("text");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<parent><child>text</child></parent>", xml);
    }

    @Test
    public void testEmptyElementOptimization() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("container");
        writer.writeStartElement("br");
        writer.writeEndElement();
        writer.writeStartElement("hr");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<container><br/><hr/></container>", xml);
    }

    // ========== Attribute Tests ==========

    @Test
    public void testElementWithAttribute() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("item");
        writer.writeAttribute("id", "123");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<item id=\"123\"/>", xml);
    }

    @Test
    public void testElementWithMultipleAttributes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("person");
        writer.writeAttribute("id", "1");
        writer.writeAttribute("name", "Alice");
        writer.writeAttribute("age", "30");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<person id=\"1\" name=\"Alice\" age=\"30\"/>", xml);
    }

    @Test
    public void testAttributeValueEscaping() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("test");
        writer.writeAttribute("value", "\"quotes\" & <angles>");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<test value=\"&quot;quotes&quot; &amp; &lt;angles&gt;\"/>", xml);
    }

    // ========== Namespace Tests ==========

    @Test
    public void testDefaultNamespace() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("http://example.com/ns", "root");
        writer.writeDefaultNamespace("http://example.com/ns");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root xmlns=\"http://example.com/ns\"/>", xml);
    }

    @Test
    public void testPrefixedNamespace() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("ex", "root", "http://example.com/ns");
        writer.writeNamespace("ex", "http://example.com/ns");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<ex:root xmlns:ex=\"http://example.com/ns\"/>", xml);
    }

    @Test
    public void testMixedNamespaces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeDefaultNamespace("http://default.com");
        writer.writeNamespace("other", "http://other.com");
        writer.writeStartElement("other", "child", "http://other.com");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root xmlns=\"http://default.com\" xmlns:other=\"http://other.com\"><other:child/></root>", xml);
    }

    @Test
    public void testNamespacedAttribute() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeNamespace("xlink", "http://www.w3.org/1999/xlink");
        writer.writeAttribute("xlink", "http://www.w3.org/1999/xlink", "href", "http://example.com");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"http://example.com\"/>", xml);
    }

    @Test
    public void testGetPrefix() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeNamespace("ex", "http://example.com");
        
        assertEquals("ex", writer.getPrefix("http://example.com"));
        assertNull(writer.getPrefix("http://unknown.com"));
        
        writer.writeEndElement();
        writer.close();
    }

    @Test
    public void testGetNamespaceURI() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeDefaultNamespace("http://default.com");
        writer.writeNamespace("ex", "http://example.com");
        
        assertEquals("http://default.com", writer.getNamespaceURI(""));
        assertEquals("http://example.com", writer.getNamespaceURI("ex"));
        assertNull(writer.getNamespaceURI("unknown"));
        
        writer.writeEndElement();
        writer.close();
    }

    // ========== Character Content Tests ==========

    @Test
    public void testCharacterEscaping() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("text");
        writer.writeCharacters("5 < 10 & 10 > 5");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<text>5 &lt; 10 &amp; 10 &gt; 5</text>", xml);
    }

    @Test
    public void testUtf8Characters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("text");
        writer.writeCharacters("Hello 👋 World 🌍 你好");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertTrue(xml.contains("Hello 👋 World 🌍 你好"));
    }

    @Test
    public void testCharacterArray() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        char[] chars = "Hello, World!".toCharArray();
        writer.writeStartElement("text");
        writer.writeCharacters(chars, 7, 5); // "World"
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<text>World</text>", xml);
    }

    // ========== CDATA Tests ==========

    @Test
    public void testCDataSection() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("code");
        writer.writeCData("<script>alert('hello');</script>");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<code><![CDATA[<script>alert('hello');</script>]]></code>", xml);
    }

    @Test
    public void testCDataWithClosingSequence() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("test");
        writer.writeCData("first ]]> second");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        // Should split the CDATA section at ]]>
        assertEquals("<test><![CDATA[first ]]]]><![CDATA[> second]]></test>", xml);
    }

    // ========== Comment Tests ==========

    @Test
    public void testComment() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeComment(" This is a comment ");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root><!-- This is a comment --></root>", xml);
    }

    // ========== Processing Instruction Tests ==========

    @Test
    public void testProcessingInstruction() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeProcessingInstruction("xml-stylesheet", "type=\"text/xsl\" href=\"style.xsl\"");
        writer.writeStartElement("root");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?><root/>", xml);
    }

    @Test
    public void testProcessingInstructionNoData() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeProcessingInstruction("page-break");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root><?page-break?></root>", xml);
    }

    // ========== Entity Reference Tests ==========

    @Test
    public void testEntityRef() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("text");
        writer.writeCharacters("Copyright ");
        writer.writeEntityRef("copy");
        writer.writeCharacters(" 2025");
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<text>Copyright &copy; 2025</text>", xml);
    }

    // ========== Indentation Tests ==========

    @Test
    public void testIndentationWithTabs() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out, IndentConfig.tabs());

        writer.writeStartElement("root");
        writer.writeStartElement("child");
        writer.writeCharacters("text");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        String expected = "<root>\n\t<child>text</child>\n</root>";
        assertEquals(expected, xml);
    }

    @Test
    public void testIndentationWithSpaces() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out, IndentConfig.spaces2());

        writer.writeStartElement("root");
        writer.writeStartElement("child");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        String expected = "<root>\n  <child/>\n</root>";
        assertEquals(expected, xml);
    }

    @Test
    public void testDeepNestingIndentation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out, IndentConfig.spaces2());

        writer.writeStartElement("a");
        writer.writeStartElement("b");
        writer.writeStartElement("c");
        writer.writeCharacters("deep");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        String expected = "<a>\n  <b>\n    <c>deep</c>\n  </b>\n</a>";
        assertEquals(expected, xml);
    }

    @Test
    public void testIndentationWithMultipleChildren() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out, IndentConfig.spaces2());

        writer.writeStartElement("root");
        writer.writeStartElement("child1");
        writer.writeEndElement();
        writer.writeStartElement("child2");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        String expected = "<root>\n  <child1/>\n  <child2/>\n</root>";
        assertEquals(expected, xml);
    }

    // ========== Edge Cases ==========

    @Test
    public void testEmptyCharacters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeCharacters("");
        writer.writeCharacters(null);
        writer.writeEndElement();
        writer.close();

        // Empty/null characters should not prevent empty element optimization
        String xml = out.toString(StandardCharsets.UTF_8);
        assertEquals("<root/>", xml);
    }

    @Test(expected = IllegalStateException.class)
    public void testAttributeAfterContent() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeCharacters("content");
        writer.writeAttribute("attr", "value"); // Should throw
    }

    @Test(expected = IllegalStateException.class)
    public void testEndElementWithoutStart() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeEndElement(); // Should throw
    }

    @Test(expected = IllegalStateException.class)
    public void testNamespaceAfterContent() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("root");
        writer.writeCharacters("content");
        writer.writeNamespace("ns", "http://example.com"); // Should throw
    }

    // ========== Large Output Test ==========

    @Test
    public void testLargeOutput() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);

        writer.writeStartElement("items");
        for (int i = 0; i < 1000; i++) {
            writer.writeStartElement("item");
            writer.writeAttribute("id", String.valueOf(i));
            writer.writeCharacters("Item number " + i);
            writer.writeEndElement();
        }
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertTrue(xml.startsWith("<items><item id=\"0\">Item number 0</item>"));
        assertTrue(xml.endsWith("<item id=\"999\">Item number 999</item></items>"));
    }

    // ========== Complex Document Test ==========

    @Test
    public void testComplexDocument() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out, IndentConfig.spaces2());

        writer.writeProcessingInstruction("xml-stylesheet", "href=\"style.css\"");
        writer.writeStartElement("html");
        writer.writeDefaultNamespace("http://www.w3.org/1999/xhtml");
        
        writer.writeStartElement("head");
        writer.writeStartElement("title");
        writer.writeCharacters("Test Document");
        writer.writeEndElement();
        writer.writeEndElement();
        
        writer.writeStartElement("body");
        writer.writeComment(" Main content ");
        writer.writeStartElement("p");
        writer.writeAttribute("class", "intro");
        writer.writeCharacters("Hello, ");
        writer.writeStartElement("strong");
        writer.writeCharacters("World");
        writer.writeEndElement();
        writer.writeCharacters("!");
        writer.writeEndElement();
        writer.writeStartElement("br");
        writer.writeEndElement();
        writer.writeEndElement();
        
        writer.writeEndElement();
        writer.close();

        String xml = out.toString(StandardCharsets.UTF_8);
        assertTrue(xml.contains("xmlns=\"http://www.w3.org/1999/xhtml\""));
        assertTrue(xml.contains("<title>Test Document</title>"));
        assertTrue(xml.contains("<br/>"));
        assertTrue(xml.contains("<!-- Main content -->"));
    }
}

