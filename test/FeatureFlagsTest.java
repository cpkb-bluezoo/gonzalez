import org.bluezoo.gonzalez.Parser;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

/**
 * Test for SAX2 feature flags in Parser.
 */
public class FeatureFlagsTest {

    public static void main(String[] args) {
        System.out.println("=== Feature Flags Test ===\n");

        try {
            testMutableFeatures();
            testReadOnlyFeatures();
            testUnsupportedFeatures();
            testFeaturePersistence();
            
            System.out.println("\n=== All feature flag tests passed! ===");
        } catch (Exception e) {
            System.err.println("\n=== Test failed with exception ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void testMutableFeatures() throws Exception {
        System.out.println("Test 1: Mutable Features");
        
        Parser parser = new Parser();
        
        // Test namespaces (default: true)
        boolean namespaces = parser.getFeature("http://xml.org/sax/features/namespaces");
        if (!namespaces) {
            throw new Exception("namespaces should default to true, got: " + namespaces);
        }
        System.out.println("  ✓ namespaces defaults to true");
        
        // Change namespaces
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        namespaces = parser.getFeature("http://xml.org/sax/features/namespaces");
        if (namespaces) {
            throw new Exception("namespaces should be false after setting, got: " + namespaces);
        }
        System.out.println("  ✓ namespaces can be set to false");
        
        // Test namespace-prefixes (default: false)
        boolean namespacePrefixes = parser.getFeature("http://xml.org/sax/features/namespace-prefixes");
        if (namespacePrefixes) {
            throw new Exception("namespace-prefixes should default to false, got: " + namespacePrefixes);
        }
        System.out.println("  ✓ namespace-prefixes defaults to false");
        
        // Change namespace-prefixes
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        namespacePrefixes = parser.getFeature("http://xml.org/sax/features/namespace-prefixes");
        if (!namespacePrefixes) {
            throw new Exception("namespace-prefixes should be true after setting, got: " + namespacePrefixes);
        }
        System.out.println("  ✓ namespace-prefixes can be set to true");
        
        // Test validation (default: false)
        boolean validation = parser.getFeature("http://xml.org/sax/features/validation");
        if (validation) {
            throw new Exception("validation should default to false, got: " + validation);
        }
        System.out.println("  ✓ validation defaults to false");
        
        // Change validation
        parser.setFeature("http://xml.org/sax/features/validation", true);
        validation = parser.getFeature("http://xml.org/sax/features/validation");
        if (!validation) {
            throw new Exception("validation should be true after setting, got: " + validation);
        }
        System.out.println("  ✓ validation can be set to true");
        
        // Test external-general-entities (default: true)
        boolean externalGeneral = parser.getFeature("http://xml.org/sax/features/external-general-entities");
        if (!externalGeneral) {
            throw new Exception("external-general-entities should default to true, got: " + externalGeneral);
        }
        System.out.println("  ✓ external-general-entities defaults to true");
        
        // Test external-parameter-entities (default: true)
        boolean externalParameter = parser.getFeature("http://xml.org/sax/features/external-parameter-entities");
        if (!externalParameter) {
            throw new Exception("external-parameter-entities should default to true, got: " + externalParameter);
        }
        System.out.println("  ✓ external-parameter-entities defaults to true");
        
        // Test resolve-dtd-uris (default: true)
        boolean resolveDTDURIs = parser.getFeature("http://xml.org/sax/features/resolve-dtd-uris");
        if (!resolveDTDURIs) {
            throw new Exception("resolve-dtd-uris should default to true, got: " + resolveDTDURIs);
        }
        System.out.println("  ✓ resolve-dtd-uris defaults to true");
        
        // Test string-interning (default: true)
        boolean stringInterning = parser.getFeature("http://xml.org/sax/features/string-interning");
        if (!stringInterning) {
            throw new Exception("string-interning should default to true, got: " + stringInterning);
        }
        System.out.println("  ✓ string-interning defaults to true");
        
        // Change string-interning
        parser.setFeature("http://xml.org/sax/features/string-interning", false);
        stringInterning = parser.getFeature("http://xml.org/sax/features/string-interning");
        if (stringInterning) {
            throw new Exception("string-interning should be false after setting, got: " + stringInterning);
        }
        System.out.println("  ✓ string-interning can be set to false");
        
        // Test xml-1.1 (default: false, but can be enabled)
        boolean xml11 = parser.getFeature("http://xml.org/sax/features/xml-1.1");
        if (xml11) {
            throw new Exception("xml-1.1 should default to false, got: " + xml11);
        }
        System.out.println("  ✓ xml-1.1 defaults to false");
        
        // Enable xml-1.1
        parser.setFeature("http://xml.org/sax/features/xml-1.1", true);
        xml11 = parser.getFeature("http://xml.org/sax/features/xml-1.1");
        if (!xml11) {
            throw new Exception("xml-1.1 should be true after setting, got: " + xml11);
        }
        System.out.println("  ✓ xml-1.1 can be enabled");
        
        System.out.println("  ✓ Passed\n");
    }

    static void testReadOnlyFeatures() throws Exception {
        System.out.println("Test 2: Read-Only Features");
        
        Parser parser = new Parser();
        
        // Test use-attributes2 (always true)
        boolean useAttributes2 = parser.getFeature("http://xml.org/sax/features/use-attributes2");
        if (!useAttributes2) {
            throw new Exception("use-attributes2 should be true, got: " + useAttributes2);
        }
        System.out.println("  ✓ use-attributes2 is true");
        
        // Try to change it (should throw exception)
        try {
            parser.setFeature("http://xml.org/sax/features/use-attributes2", false);
            throw new Exception("Setting use-attributes2 should throw SAXNotSupportedException");
        } catch (SAXNotSupportedException e) {
            System.out.println("  ✓ use-attributes2 is read-only");
        }
        
        // Test use-locator2 (always true)
        boolean useLocator2 = parser.getFeature("http://xml.org/sax/features/use-locator2");
        if (!useLocator2) {
            throw new Exception("use-locator2 should be true, got: " + useLocator2);
        }
        System.out.println("  ✓ use-locator2 is true");
        
        // Test use-entity-resolver2 (always true)
        boolean useEntityResolver2 = parser.getFeature("http://xml.org/sax/features/use-entity-resolver2");
        if (!useEntityResolver2) {
            throw new Exception("use-entity-resolver2 should be true, got: " + useEntityResolver2);
        }
        System.out.println("  ✓ use-entity-resolver2 is true");
        
        // Test lexical-handler (always true)
        boolean lexicalHandler = parser.getFeature("http://xml.org/sax/features/lexical-handler");
        if (!lexicalHandler) {
            throw new Exception("lexical-handler should be true, got: " + lexicalHandler);
        }
        System.out.println("  ✓ lexical-handler is true");
        
        // Test parameter-entities (always true)
        boolean parameterEntities = parser.getFeature("http://xml.org/sax/features/parameter-entities");
        if (!parameterEntities) {
            throw new Exception("parameter-entities should be true, got: " + parameterEntities);
        }
        System.out.println("  ✓ parameter-entities is true");
        
        // Test xmlns-uris (always false)
        boolean xmlnsURIs = parser.getFeature("http://xml.org/sax/features/xmlns-uris");
        if (xmlnsURIs) {
            throw new Exception("xmlns-uris should be false, got: " + xmlnsURIs);
        }
        System.out.println("  ✓ xmlns-uris is false");
        
        System.out.println("  ✓ Passed\n");
    }

    static void testUnsupportedFeatures() throws Exception {
        System.out.println("Test 3: Unsupported Features");
        
        Parser parser = new Parser();
        
        // Test unicode-normalization-checking (not supported)
        boolean unicodeNorm = parser.getFeature("http://xml.org/sax/features/unicode-normalization-checking");
        if (unicodeNorm) {
            throw new Exception("unicode-normalization-checking should be false, got: " + unicodeNorm);
        }
        System.out.println("  ✓ unicode-normalization-checking is false");
        
        // Try to enable it (should throw exception)
        try {
            parser.setFeature("http://xml.org/sax/features/unicode-normalization-checking", true);
            throw new Exception("Enabling unicode-normalization-checking should throw SAXNotSupportedException");
        } catch (SAXNotSupportedException e) {
            System.out.println("  ✓ unicode-normalization-checking cannot be enabled");
        }
        
        // Setting to false should be allowed (no-op)
        parser.setFeature("http://xml.org/sax/features/unicode-normalization-checking", false);
        System.out.println("  ✓ unicode-normalization-checking can be set to false (no-op)");
        
        // Test xml-1.1 (now supported - moved to testMutableFeatures)
        // This test now only checks unsupported features
        
        // Test unrecognized feature
        try {
            parser.getFeature("http://example.com/nonexistent-feature");
            throw new Exception("Getting nonexistent feature should throw SAXNotRecognizedException");
        } catch (SAXNotRecognizedException e) {
            System.out.println("  ✓ Nonexistent feature throws SAXNotRecognizedException");
        }
        
        System.out.println("  ✓ Passed\n");
    }

    static void testFeaturePersistence() throws Exception {
        System.out.println("Test 4: Feature Persistence After Reset");
        
        Parser parser = new Parser();
        
        // Change some features
        parser.setFeature("http://xml.org/sax/features/namespaces", false);
        parser.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        parser.setFeature("http://xml.org/sax/features/validation", true);
        
        // Reset parser
        parser.reset();
        
        // Features should persist after reset
        boolean namespaces = parser.getFeature("http://xml.org/sax/features/namespaces");
        if (namespaces) {
            throw new Exception("namespaces should remain false after reset, got: " + namespaces);
        }
        System.out.println("  ✓ namespaces persists after reset");
        
        boolean namespacePrefixes = parser.getFeature("http://xml.org/sax/features/namespace-prefixes");
        if (!namespacePrefixes) {
            throw new Exception("namespace-prefixes should remain true after reset, got: " + namespacePrefixes);
        }
        System.out.println("  ✓ namespace-prefixes persists after reset");
        
        boolean validation = parser.getFeature("http://xml.org/sax/features/validation");
        if (!validation) {
            throw new Exception("validation should remain true after reset, got: " + validation);
        }
        System.out.println("  ✓ validation persists after reset");
        
        System.out.println("  ✓ Passed\n");
    }
}

