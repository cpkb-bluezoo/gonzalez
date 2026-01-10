/*
 * XMLWriterTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.bluezoo.gonzalez.IndentConfig;
import org.bluezoo.gonzalez.XMLWriter;

/**
 * Standalone test program for XMLWriter.
 * 
 * Usage: java -cp build:test XMLWriterTest
 */
public class XMLWriterTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("XMLWriter Test Suite");
        System.out.println("====================\n");

        // Basic Element Tests
        test("Simple empty element", "<root/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("root");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Element with content", "<greeting>Hello, World!</greeting>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("greeting");
            writer.writeCharacters("Hello, World!");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Nested elements", "<parent><child>text</child></parent>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("parent");
            writer.writeStartElement("child");
            writer.writeCharacters("text");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Multiple empty elements", "<container><br/><hr/></container>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("container");
            writer.writeStartElement("br");
            writer.writeEndElement();
            writer.writeStartElement("hr");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Attribute Tests
        test("Element with attribute", "<item id=\"123\"/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("item");
            writer.writeAttribute("id", "123");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Multiple attributes", "<person id=\"1\" name=\"Alice\" age=\"30\"/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("person");
            writer.writeAttribute("id", "1");
            writer.writeAttribute("name", "Alice");
            writer.writeAttribute("age", "30");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Attribute value escaping", "<test value=\"&quot;quotes&quot; &amp; &lt;angles&gt;\"/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("test");
            writer.writeAttribute("value", "\"quotes\" & <angles>");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Namespace Tests
        test("Default namespace", "<root xmlns=\"http://example.com/ns\"/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("http://example.com/ns", "root");
            writer.writeDefaultNamespace("http://example.com/ns");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Prefixed namespace", "<ex:root xmlns:ex=\"http://example.com/ns\"/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("ex", "root", "http://example.com/ns");
            writer.writeNamespace("ex", "http://example.com/ns");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Namespaced attribute", 
             "<root xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"http://example.com\"/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("root");
            writer.writeNamespace("xlink", "http://www.w3.org/1999/xlink");
            writer.writeAttribute("xlink", "http://www.w3.org/1999/xlink", "href", "http://example.com");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Character Content Tests
        test("Character escaping", "<text>5 &lt; 10 &amp; 10 &gt; 5</text>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("text");
            writer.writeCharacters("5 < 10 & 10 > 5");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("UTF-8 characters", true, () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("text");
            writer.writeCharacters("Hello 👋 World 🌍 你好");
            writer.writeEndElement();
            writer.close();
            String xml = out.toString(StandardCharsets.UTF_8);
            return xml.contains("Hello 👋 World 🌍 你好");
        });

        // CDATA Tests
        test("CDATA section", "<code><![CDATA[<script>alert('hello');</script>]]></code>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("code");
            writer.writeCData("<script>alert('hello');</script>");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Comment Tests
        test("Comment", "<root><!-- This is a comment --></root>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("root");
            writer.writeComment(" This is a comment ");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Processing Instruction Tests
        test("Processing instruction", "<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?><root/>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeProcessingInstruction("xml-stylesheet", "type=\"text/xsl\" href=\"style.xsl\"");
            writer.writeStartElement("root");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Processing instruction no data", "<root><?page-break?></root>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("root");
            writer.writeProcessingInstruction("page-break");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Entity Reference Tests
        test("Entity reference", "<text>Copyright &copy; 2025</text>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out);
            writer.writeStartElement("text");
            writer.writeCharacters("Copyright ");
            writer.writeEntityRef("copy");
            writer.writeCharacters(" 2025");
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        // Indentation Tests
        test("Indentation with tabs", "<root>\n\t<child>text</child>\n</root>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out, IndentConfig.tabs());
            writer.writeStartElement("root");
            writer.writeStartElement("child");
            writer.writeCharacters("text");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Indentation with spaces", "<root>\n  <child/>\n</root>", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out, IndentConfig.spaces2());
            writer.writeStartElement("root");
            writer.writeStartElement("child");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        });

        test("Deep nesting indentation", "<a>\n  <b>\n    <c>deep</c>\n  </b>\n</a>", () -> {
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
            return out.toString(StandardCharsets.UTF_8);
        });

        // Error Handling Tests
        test("Attribute after content throws exception", true, () -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                XMLWriter writer = new XMLWriter(out);
                writer.writeStartElement("root");
                writer.writeCharacters("content");
                writer.writeAttribute("attr", "value");
                return false; // Should not reach here
            } catch (IllegalStateException e) {
                return true; // Expected
            }
        });

        test("End element without start throws exception", true, () -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                XMLWriter writer = new XMLWriter(out);
                writer.writeEndElement();
                return false; // Should not reach here
            } catch (IllegalStateException e) {
                return true; // Expected
            }
        });

        // Large Output Test
        test("Large output", true, () -> {
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
            return xml.startsWith("<items><item id=\"0\">Item number 0</item>") &&
                   xml.endsWith("<item id=\"999\">Item number 999</item></items>");
        });

        // Summary
        System.out.println("\n====================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        System.out.println("====================");
        
        if (failed > 0) {
            System.exit(1);
        }
    }

    @FunctionalInterface
    interface TestSupplier<T> {
        T get() throws Exception;
    }

    private static void test(String name, String expected, TestSupplier<String> test) {
        try {
            String actual = test.get();
            if (expected.equals(actual)) {
                System.out.println("✓ " + name);
                passed++;
            } else {
                System.out.println("✗ " + name);
                System.out.println("  Expected: " + expected);
                System.out.println("  Actual:   " + actual);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("✗ " + name);
            System.out.println("  Exception: " + e.getMessage());
            failed++;
        }
    }

    private static void test(String name, boolean expected, TestSupplier<Boolean> test) {
        try {
            boolean actual = test.get();
            if (expected == actual) {
                System.out.println("✓ " + name);
                passed++;
            } else {
                System.out.println("✗ " + name);
                System.out.println("  Expected: " + expected);
                System.out.println("  Actual:   " + actual);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("✗ " + name);
            System.out.println("  Exception: " + e.getMessage());
            failed++;
        }
    }
}

