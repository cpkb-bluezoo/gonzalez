/*
 * Test application of default attribute values from DTD.
 */

import java.io.ByteArrayInputStream;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Attributes2;

/**
 * Tests default attribute value application.
 */
public class DefaultAttributeValueTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing default attribute value application...\n");
        test1_SimpleDefault();
        test2_DefaultWithEntityRef();
        test3_FixedValue();
        test4_FixedValueMismatch();
        test5_MultipleDefaults();
        test6_SpecifiedFlag();
        System.out.println("\n✓ All tests passed!");
    }
    
    /**
     * Test 1: Simple default value.
     */
    static void test1_SimpleDefault() throws Exception {
        System.out.println("Test 1: Simple default value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"default value\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        assert handler.attrValue != null : "Default value not applied";
        assert handler.attrValue.equals("default value") : "Expected 'default value', got: '" + handler.attrValue + "'";
        assert !handler.attrSpecified : "Default value should have specified=false";
        
        System.out.println("  Attribute value: '" + handler.attrValue + "' (specified=" + handler.attrSpecified + ")");
        System.out.println("  ✓ Simple default value applied correctly\n");
    }
    
    /**
     * Test 2: Default value with entity reference.
     */
    static void test2_DefaultWithEntityRef() throws Exception {
        System.out.println("Test 2: Default value with entity reference");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ENTITY copy \"Copyright 2025\">\n" +
                    "  <!ATTLIST root\n" +
                    "    attr CDATA \"&copy;\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        assert handler.attrValue != null : "Default value not applied";
        assert handler.attrValue.equals("Copyright 2025") : "Expected 'Copyright 2025', got: '" + handler.attrValue + "'";
        assert !handler.attrSpecified : "Default value should have specified=false";
        
        System.out.println("  Attribute value: '" + handler.attrValue + "' (specified=" + handler.attrSpecified + ")");
        System.out.println("  ✓ Default with entity reference applied correctly\n");
    }
    
    /**
     * Test 3: #FIXED value applied when not specified.
     */
    static void test3_FixedValue() throws Exception {
        System.out.println("Test 3: #FIXED value");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    version CDATA #FIXED \"1.0\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        assert handler.attrValue != null : "Fixed value not applied";
        assert handler.attrValue.equals("1.0") : "Expected '1.0', got: '" + handler.attrValue + "'";
        assert !handler.attrSpecified : "Fixed value should have specified=false";
        
        System.out.println("  Attribute value: '" + handler.attrValue + "' (specified=" + handler.attrSpecified + ")");
        System.out.println("  ✓ #FIXED value applied correctly\n");
    }
    
    /**
     * Test 4: #FIXED value mismatch should cause error.
     */
    static void test4_FixedValueMismatch() throws Exception {
        System.out.println("Test 4: #FIXED value mismatch");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    version CDATA #FIXED \"1.0\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root version='2.0'/>";
        
        AttributeCapture handler = new AttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
            parser.parse(new org.xml.sax.InputSource(in));
            throw new AssertionError("Expected SAXParseException for #FIXED value mismatch");
        } catch (SAXParseException e) {
            assert e.getMessage().contains("#FIXED") : "Expected '#FIXED' in error message, got: " + e.getMessage();
            System.out.println("  ✓ #FIXED value mismatch detected: " + e.getMessage() + "\n");
        }
    }
    
    /**
     * Test 5: Multiple default values.
     */
    static void test5_MultipleDefaults() throws Exception {
        System.out.println("Test 5: Multiple default values");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    a CDATA \"valueA\"\n" +
                    "    b CDATA \"valueB\"\n" +
                    "    c CDATA \"valueC\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root/>";
        
        MultiAttributeCapture handler = new MultiAttributeCapture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        assert handler.attrCount == 3 : "Expected 3 attributes, got: " + handler.attrCount;
        assert "valueA".equals(handler.getAttrValue("a")) : "Attribute 'a' incorrect";
        assert "valueB".equals(handler.getAttrValue("b")) : "Attribute 'b' incorrect";
        assert "valueC".equals(handler.getAttrValue("c")) : "Attribute 'c' incorrect";
        
        System.out.println("  Attributes: a=" + handler.getAttrValue("a") + ", b=" + handler.getAttrValue("b") + ", c=" + handler.getAttrValue("c"));
        System.out.println("  ✓ Multiple defaults applied correctly\n");
    }
    
    /**
     * Test 6: Specified vs default attributes (Attributes2).
     */
    static void test6_SpecifiedFlag() throws Exception {
        System.out.println("Test 6: Specified flag (Attributes2)");
        String xml = "<?xml version='1.0'?>\n" +
                    "<!DOCTYPE root [\n" +
                    "  <!ATTLIST root\n" +
                    "    specified CDATA \"default1\"\n" +
                    "    defaulted CDATA \"default2\"\n" +
                    "  >\n" +
                    "]>\n" +
                    "<root specified='value1'/>";
        
        Attributes2Capture handler = new Attributes2Capture();
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        
        ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new org.xml.sax.InputSource(in));
        
        assert handler.attrs.getLength() == 2 : "Expected 2 attributes, got: " + handler.attrs.getLength();
        
        int specIdx = handler.attrs.getIndex("specified");
        int defIdx = handler.attrs.getIndex("defaulted");
        
        assert handler.attrs.isSpecified(specIdx) : "Attribute 'specified' should be specified=true";
        assert !handler.attrs.isSpecified(defIdx) : "Attribute 'defaulted' should be specified=false";
        
        System.out.println("  specified: value='" + handler.attrs.getValue(specIdx) + "', specified=" + handler.attrs.isSpecified(specIdx));
        System.out.println("  defaulted: value='" + handler.attrs.getValue(defIdx) + "', specified=" + handler.attrs.isSpecified(defIdx));
        System.out.println("  ✓ Specified flag handled correctly\n");
    }
    
    /**
     * Helper ContentHandler that captures one attribute value.
     */
    static class AttributeCapture implements ContentHandler {
        String attrValue;
        boolean attrSpecified;
        String attrName;
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (atts.getLength() > 0) {
                attrName = atts.getQName(0);
                attrValue = atts.getValue(0);
                if (atts instanceof Attributes2) {
                    attrSpecified = ((Attributes2) atts).isSpecified(0);
                }
            }
        }
        
        @Override public void setDocumentLocator(Locator locator) {}
        @Override public void startDocument() {}
        @Override public void endDocument() {}
        @Override public void endElement(String uri, String localName, String qName) {}
        @Override public void characters(char[] ch, int start, int length) {}
        @Override public void ignorableWhitespace(char[] ch, int start, int length) {}
        @Override public void processingInstruction(String target, String data) {}
        @Override public void startPrefixMapping(String prefix, String uri) {}
        @Override public void endPrefixMapping(String prefix) {}
        @Override public void skippedEntity(String name) {}
    }
    
    /**
     * Helper ContentHandler that captures multiple attributes.
     */
    static class MultiAttributeCapture implements ContentHandler {
        int attrCount;
        Attributes attrs;
        
        String getAttrValue(String name) {
            return attrs != null ? attrs.getValue(name) : null;
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            this.attrs = atts;
            this.attrCount = atts.getLength();
        }
        
        @Override public void setDocumentLocator(Locator locator) {}
        @Override public void startDocument() {}
        @Override public void endDocument() {}
        @Override public void endElement(String uri, String localName, String qName) {}
        @Override public void characters(char[] ch, int start, int length) {}
        @Override public void ignorableWhitespace(char[] ch, int start, int length) {}
        @Override public void processingInstruction(String target, String data) {}
        @Override public void startPrefixMapping(String prefix, String uri) {}
        @Override public void endPrefixMapping(String prefix) {}
        @Override public void skippedEntity(String name) {}
    }
    
    /**
     * Helper ContentHandler that captures Attributes2.
     */
    static class Attributes2Capture implements ContentHandler {
        Attributes2 attrs;
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            this.attrs = (Attributes2) atts;
        }
        
        @Override public void setDocumentLocator(Locator locator) {}
        @Override public void startDocument() {}
        @Override public void endDocument() {}
        @Override public void endElement(String uri, String localName, String qName) {}
        @Override public void characters(char[] ch, int start, int length) {}
        @Override public void ignorableWhitespace(char[] ch, int start, int length) {}
        @Override public void processingInstruction(String target, String data) {}
        @Override public void startPrefixMapping(String prefix, String uri) {}
        @Override public void endPrefixMapping(String prefix) {}
        @Override public void skippedEntity(String name) {}
    }
}

