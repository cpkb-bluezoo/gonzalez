import org.bluezoo.gonzalez.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import java.io.*;

/**
 * Tests namespace processing in the ContentParser.
 */
public class NamespaceTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing namespace processing...\n");
        
        test1_BasicNamespace();
        test2_MultiplePrefixes();
        test3_DefaultNamespace();
        test4_AttributeNamespaces();
        test5_NamespacePrefixesFlag();
        test6_NonNamespaceAware();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    static void test1_BasicNamespace() throws Exception {
        System.out.println("Test 1: Basic namespace with prefix");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<doc:root xmlns:doc='http://example.com/doc'>\n" +
            "  <doc:child>text</doc:child>\n" +
            "</doc:root>";
        
        TestHandler handler = new TestHandler();
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("  Start prefix mappings: " + handler.prefixMappings);
        System.out.println("  Root element: " + handler.elements);
        
        // Verify prefix mapping was called
        if (!handler.prefixMappings.contains("doc=http://example.com/doc")) {
            throw new Exception("Expected prefix mapping for 'doc'");
        }
        
        // Verify element names have namespace info
        if (!handler.elements.get(0).contains("http://example.com/doc")) {
            throw new Exception("Root element missing namespace URI");
        }
        if (!handler.elements.get(0).contains("root")) {
            throw new Exception("Root element missing local name");
        }
        
        System.out.println("  ✓ Basic namespace processing works");
    }
    
    static void test2_MultiplePrefixes() throws Exception {
        System.out.println("\nTest 2: Multiple namespace prefixes");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns:a='http://example.com/a' xmlns:b='http://example.com/b'>\n" +
            "  <a:foo/>\n" +
            "  <b:bar/>\n" +
            "</root>";
        
        TestHandler handler = new TestHandler();
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("  Prefix mappings: " + handler.prefixMappings);
        System.out.println("  Elements: " + handler.elements);
        
        if (!handler.prefixMappings.contains("a=http://example.com/a")) {
            throw new Exception("Missing prefix 'a'");
        }
        if (!handler.prefixMappings.contains("b=http://example.com/b")) {
            throw new Exception("Missing prefix 'b'");
        }
        
        System.out.println("  ✓ Multiple prefixes handled correctly");
    }
    
    static void test3_DefaultNamespace() throws Exception {
        System.out.println("\nTest 3: Default namespace");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns='http://example.com/default'>\n" +
            "  <child>text</child>\n" +
            "</root>";
        
        TestHandler handler = new TestHandler();
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("  Prefix mappings: " + handler.prefixMappings);
        System.out.println("  Root element: " + handler.elements.get(0));
        
        // Default namespace uses empty prefix
        if (!handler.prefixMappings.contains("=http://example.com/default")) {
            throw new Exception("Missing default namespace mapping");
        }
        
        // Elements should be in default namespace
        if (!handler.elements.get(0).contains("http://example.com/default")) {
            throw new Exception("Root not in default namespace");
        }
        
        System.out.println("  ✓ Default namespace works");
    }
    
    static void test4_AttributeNamespaces() throws Exception {
        System.out.println("\nTest 4: Attribute namespaces");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns:ns='http://example.com/ns' ns:attr='value'/>";
        
        TestHandler handler = new TestHandler();
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("  Attributes: " + handler.attributes);
        
        // Prefixed attribute should have namespace
        if (!handler.attributes.get(0).contains("http://example.com/ns")) {
            throw new Exception("Prefixed attribute missing namespace");
        }
        
        System.out.println("  ✓ Attribute namespaces work");
    }
    
    static void test5_NamespacePrefixesFlag() throws Exception {
        System.out.println("\nTest 5: namespace-prefixes feature flag");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<root xmlns='http://example.com/ns'/>";
        
        TestHandler handler = new TestHandler();
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", true);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("  Attributes: " + handler.attributes);
        
        // xmlns attribute should be present when namespace-prefixes=true
        boolean hasXmlns = false;
        for (String attr : handler.attributes) {
            if (attr.contains("xmlns")) {
                hasXmlns = true;
                break;
            }
        }
        
        if (!hasXmlns) {
            throw new Exception("xmlns attribute missing when namespace-prefixes=true");
        }
        
        System.out.println("  ✓ namespace-prefixes flag works");
    }
    
    static void test6_NonNamespaceAware() throws Exception {
        System.out.println("\nTest 6: Non-namespace-aware mode");
        
        String xml =
            "<?xml version='1.0'?>\n" +
            "<ns:root xmlns:ns='http://example.com/ns'/>";
        
        TestHandler handler = new TestHandler();
        
        org.bluezoo.gonzalez.Parser parser = new org.bluezoo.gonzalez.Parser();
        parser.setContentHandler(handler);
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        parser.parse(new InputSource(bais));
        
        System.out.println("  Prefix mappings: " + handler.prefixMappings);
        System.out.println("  Elements: " + handler.elements);
        
        // No prefix mappings should occur
        if (!handler.prefixMappings.isEmpty()) {
            throw new Exception("Prefix mappings called when namespaces disabled");
        }
        
        // Element name should be raw qName
        if (!handler.elements.get(0).contains("ns:root")) {
            throw new Exception("Element name not raw qName");
        }
        
        System.out.println("  ✓ Non-namespace-aware mode works");
    }
    
    static class TestHandler extends DefaultHandler {
        java.util.List<String> prefixMappings = new java.util.ArrayList<>();
        java.util.List<String> elements = new java.util.ArrayList<>();
        java.util.List<String> attributes = new java.util.ArrayList<>();
        
        @Override
        public void startPrefixMapping(String prefix, String uri) {
            prefixMappings.add(prefix + "=" + uri);
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            elements.add("{" + uri + "}" + localName + " [qName=" + qName + "]");
            
            for (int i = 0; i < atts.getLength(); i++) {
                attributes.add("{" + atts.getURI(i) + "}" + atts.getLocalName(i) + 
                             " [qName=" + atts.getQName(i) + "]");
            }
        }
    }
}

