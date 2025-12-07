import org.bluezoo.gonzalez.*;
import java.util.*;

/**
 * Tests the NamespaceScopeTracker class.
 */
public class NamespaceScopeTrackerTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing NamespaceScopeTracker...\n");
        
        test1_PreBoundPrefixes();
        test2_BasicPrefixBinding();
        test3_DefaultNamespace();
        test4_ScopeNesting();
        test5_PrefixRedeclaration();
        test6_ProcessName();
        test7_GetPrefixes();
        
        System.out.println("\n✓ All tests passed!");
    }
    
    static void test1_PreBoundPrefixes() throws Exception {
        System.out.println("Test 1: Pre-bound xml and xmlns prefixes");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        String xmlURI = tracker.getURI("xml");
        String xmlnsURI = tracker.getURI("xmlns");
        
        if (!NamespaceScopeTracker.XML_NAMESPACE_URI.equals(xmlURI)) {
            throw new Exception("xml prefix not pre-bound correctly");
        }
        if (!NamespaceScopeTracker.XMLNS_NAMESPACE_URI.equals(xmlnsURI)) {
            throw new Exception("xmlns prefix not pre-bound correctly");
        }
        
        System.out.println("  xml prefix -> " + xmlURI);
        System.out.println("  xmlns prefix -> " + xmlnsURI);
        System.out.println("  ✓ Pre-bound prefixes correct");
    }
    
    static void test2_BasicPrefixBinding() throws Exception {
        System.out.println("\nTest 2: Basic prefix binding");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        tracker.pushContext(); // Enter element
        tracker.declarePrefix("foo", "http://example.com/foo");
        
        String uri = tracker.getURI("foo");
        if (!"http://example.com/foo".equals(uri)) {
            throw new Exception("Prefix binding failed");
        }
        
        System.out.println("  foo prefix -> " + uri);
        System.out.println("  ✓ Basic prefix binding works");
    }
    
    static void test3_DefaultNamespace() throws Exception {
        System.out.println("\nTest 3: Default namespace");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        // Initially no default namespace
        String uri1 = tracker.getURI("");
        System.out.println("  Default namespace (initial): " + uri1);
        
        tracker.pushContext();
        tracker.declarePrefix("", "http://example.com/default");
        
        String uri2 = tracker.getURI("");
        if (!"http://example.com/default".equals(uri2)) {
            throw new Exception("Default namespace not set");
        }
        
        System.out.println("  Default namespace (after declaration): " + uri2);
        System.out.println("  ✓ Default namespace works");
    }
    
    static void test4_ScopeNesting() throws Exception {
        System.out.println("\nTest 4: Scope nesting and inheritance");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        // Outer scope
        tracker.pushContext();
        tracker.declarePrefix("a", "http://example.com/a");
        
        String uri1 = tracker.getURI("a");
        System.out.println("  Outer scope: a -> " + uri1);
        
        // Inner scope
        tracker.pushContext();
        tracker.declarePrefix("b", "http://example.com/b");
        
        String uri2 = tracker.getURI("a"); // Should inherit
        String uri3 = tracker.getURI("b");
        
        if (!uri1.equals(uri2)) {
            throw new Exception("Inner scope did not inherit outer binding");
        }
        if (!"http://example.com/b".equals(uri3)) {
            throw new Exception("Inner scope binding failed");
        }
        
        System.out.println("  Inner scope: a -> " + uri2 + " (inherited)");
        System.out.println("  Inner scope: b -> " + uri3);
        
        // Pop back to outer scope
        tracker.popContext();
        
        String uri4 = tracker.getURI("a");
        String uri5 = tracker.getURI("b");
        
        if (!uri1.equals(uri4)) {
            throw new Exception("Outer binding lost after pop");
        }
        if (uri5 != null) {
            throw new Exception("Inner binding not removed after pop");
        }
        
        System.out.println("  After pop: a -> " + uri4 + ", b -> " + uri5);
        System.out.println("  ✓ Scope nesting works correctly");
    }
    
    static void test5_PrefixRedeclaration() throws Exception {
        System.out.println("\nTest 5: Prefix redeclaration (shadowing)");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        // Outer scope
        tracker.pushContext();
        tracker.declarePrefix("ns", "http://example.com/outer");
        String uri1 = tracker.getURI("ns");
        System.out.println("  Outer scope: ns -> " + uri1);
        
        // Inner scope shadows
        tracker.pushContext();
        tracker.declarePrefix("ns", "http://example.com/inner");
        String uri2 = tracker.getURI("ns");
        System.out.println("  Inner scope: ns -> " + uri2);
        
        if (uri1.equals(uri2)) {
            throw new Exception("Shadowing failed");
        }
        
        // Pop restores original
        tracker.popContext();
        String uri3 = tracker.getURI("ns");
        System.out.println("  After pop: ns -> " + uri3);
        
        if (!uri1.equals(uri3)) {
            throw new Exception("Original binding not restored after pop");
        }
        
        System.out.println("  ✓ Prefix shadowing works correctly");
    }
    
    static void test6_ProcessName() throws Exception {
        System.out.println("\nTest 6: processName (QName -> namespace components)");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        tracker.pushContext();
        tracker.declarePrefix("foo", "http://example.com/foo");
        tracker.declarePrefix("", "http://example.com/default");
        
        // Element with prefix
        String[] parts1 = tracker.processName("foo:bar", false);
        System.out.println("  foo:bar (element) -> [" + parts1[0] + ", " + parts1[1] + ", " + parts1[2] + "]");
        if (!"http://example.com/foo".equals(parts1[0]) || !"bar".equals(parts1[1]) || !"foo:bar".equals(parts1[2])) {
            throw new Exception("processName failed for prefixed element");
        }
        
        // Element without prefix (uses default namespace)
        String[] parts2 = tracker.processName("baz", false);
        System.out.println("  baz (element) -> [" + parts2[0] + ", " + parts2[1] + ", " + parts2[2] + "]");
        if (!"http://example.com/default".equals(parts2[0]) || !"baz".equals(parts2[1]) || !"baz".equals(parts2[2])) {
            throw new Exception("processName failed for unprefixed element");
        }
        
        // Attribute without prefix (no namespace, per spec)
        String[] parts3 = tracker.processName("attr", true);
        System.out.println("  attr (attribute) -> [" + parts3[0] + ", " + parts3[1] + ", " + parts3[2] + "]");
        if (!"".equals(parts3[0]) || !"attr".equals(parts3[1]) || !"attr".equals(parts3[2])) {
            throw new Exception("processName failed for unprefixed attribute");
        }
        
        // Attribute with prefix
        String[] parts4 = tracker.processName("foo:attr", true);
        System.out.println("  foo:attr (attribute) -> [" + parts4[0] + ", " + parts4[1] + ", " + parts4[2] + "]");
        if (!"http://example.com/foo".equals(parts4[0]) || !"attr".equals(parts4[1]) || !"foo:attr".equals(parts4[2])) {
            throw new Exception("processName failed for prefixed attribute");
        }
        
        System.out.println("  ✓ processName works correctly");
    }
    
    static void test7_GetPrefixes() throws Exception {
        System.out.println("\nTest 7: getPrefixes (reverse lookup)");
        
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        
        tracker.pushContext();
        tracker.declarePrefix("a", "http://example.com/same");
        tracker.declarePrefix("b", "http://example.com/same");
        tracker.declarePrefix("c", "http://example.com/different");
        
        // Get all prefixes for a URI
        List<String> prefixes = new ArrayList<>();
        Iterator<String> iter = tracker.getPrefixes("http://example.com/same");
        while (iter.hasNext()) {
            prefixes.add(iter.next());
        }
        
        System.out.println("  Prefixes for http://example.com/same: " + prefixes);
        
        if (!prefixes.contains("a") || !prefixes.contains("b")) {
            throw new Exception("getPrefixes missing expected prefixes");
        }
        if (prefixes.contains("c")) {
            throw new Exception("getPrefixes included wrong prefix");
        }
        
        System.out.println("  ✓ getPrefixes works correctly");
    }
}

