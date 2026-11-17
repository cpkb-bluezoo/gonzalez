/*
 * NamespaceScopeTrackerTest.java
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scopes of namespace declarations: what an element declares is visible
 * to it and what is inside it, hides what an outer scope bound to the same
 * prefix, and is gone, with the outer binding back, when the element ends.
 */
public class NamespaceScopeTrackerTest {

    @Test
    public void aBindingIsVisibleInsideItsScopeOnly() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        assertTrue(tracker.declarePrefix("a", "urn:a"));
        assertEquals("urn:a", tracker.getURI("a"));
        tracker.pushContext();
        assertEquals("urn:a", tracker.getURI("a"));
        tracker.popContext();
        assertEquals("urn:a", tracker.getURI("a"));
        tracker.popContext();
        assertNull(tracker.getURI("a"));
    }

    @Test
    public void aRedeclarationHidesTheOuterBindingAndEndsWithIt() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        tracker.declarePrefix("p", "urn:outer");
        tracker.pushContext();
        tracker.declarePrefix("p", "urn:inner");
        assertEquals("urn:inner", tracker.getURI("p"));
        tracker.pushContext();
        assertEquals("urn:inner", tracker.getURI("p"));
        tracker.popContext();
        tracker.popContext();
        assertEquals("urn:outer", tracker.getURI("p"));
    }

    @Test
    public void theSameBindingAgainAtOneLevelIsNotNew() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        assertTrue(tracker.declarePrefix("p", "urn:one"));
        int version = tracker.getBindingVersion();
        assertFalse(tracker.declarePrefix("p", "urn:one"));
        assertEquals(version, tracker.getBindingVersion());
        assertEquals(1, tracker.getCurrentScopeDeclarationCount());
        // A different URI for the prefix at the same level replaces it, and is
        // still one declaration.
        assertTrue(tracker.declarePrefix("p", "urn:two"));
        assertEquals("urn:two", tracker.getURI("p"));
        assertEquals(1, tracker.getCurrentScopeDeclarationCount());
        assertEquals("urn:two", tracker.getCurrentScopeURI(0));
        assertTrue(tracker.getBindingVersion() != version);
    }

    @Test
    public void manyDeclarationsInOneScopeAreKeptInTheOrderMade() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        int count = 40;
        for (int i = 0; i < count; i++) {
            assertTrue(tracker.declarePrefix("p" + i, "urn:" + i));
        }
        assertEquals(count, tracker.getCurrentScopeDeclarationCount());
        for (int i = 0; i < count; i++) {
            assertEquals("p" + i, tracker.getCurrentScopePrefix(i));
            assertEquals("urn:" + i, tracker.getCurrentScopeURI(i));
            assertEquals("urn:" + i, tracker.getURI("p" + i));
        }
        tracker.popContext();
        for (int i = 0; i < count; i++) {
            assertNull(tracker.getURI("p" + i));
        }
    }

    @Test
    public void theIteratorOfTheCurrentScopeHasWhatItDeclared() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        assertFalse(tracker.getCurrentScopeDeclarations().hasNext());
        tracker.declarePrefix("", "urn:default");
        tracker.declarePrefix("x", "urn:x");
        tracker.pushContext();
        assertFalse(tracker.getCurrentScopeDeclarations().hasNext());
        tracker.popContext();
        List<String> seen = new ArrayList<String>();
        Iterator<Map.Entry<String, String>> declarations = tracker.getCurrentScopeDeclarations();
        while (declarations.hasNext()) {
            Map.Entry<String, String> entry = declarations.next();
            seen.add(entry.getKey() + "=" + entry.getValue());
        }
        assertEquals(2, seen.size());
        assertEquals("=urn:default", seen.get(0));
        assertEquals("x=urn:x", seen.get(1));
    }

    @Test
    public void nestingDeeperThanTheScopesFirstKept() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        int depth = 100;
        for (int i = 0; i < depth; i++) {
            tracker.pushContext();
            tracker.declarePrefix("p", "urn:" + i);
            assertEquals("urn:" + i, tracker.getURI("p"));
        }
        for (int i = depth - 1; i >= 0; i--) {
            assertEquals("urn:" + i, tracker.getURI("p"));
            tracker.popContext();
        }
        assertNull(tracker.getURI("p"));
        // The scopes are reused: another document of the same depth.
        for (int i = 0; i < depth; i++) {
            tracker.pushContext();
            assertEquals(0, tracker.getCurrentScopeDeclarationCount());
            tracker.declarePrefix("q", "urn:q" + i);
        }
        for (int i = 0; i < depth; i++) {
            tracker.popContext();
        }
        assertNull(tracker.getURI("q"));
    }

    @Test
    public void aScopeReusedStartsWithNoDeclarations() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        tracker.declarePrefix("a", "urn:a");
        tracker.declarePrefix("b", "urn:b");
        tracker.popContext();
        tracker.pushContext();
        assertEquals(0, tracker.getCurrentScopeDeclarationCount());
        assertNull(tracker.getURI("a"));
        tracker.popContext();
    }

    @Test
    public void thePredeclaredPrefixesAreKeptAndAResetStartsAgain() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        assertEquals(NamespaceScopeTracker.XML_NAMESPACE_URI, tracker.getURI("xml"));
        tracker.pushContext();
        tracker.declarePrefix("a", "urn:a");
        tracker.pushContext();
        tracker.declarePrefix("b", "urn:b");
        tracker.reset();
        assertNull(tracker.getURI("a"));
        assertNull(tracker.getURI("b"));
        assertEquals(NamespaceScopeTracker.XML_NAMESPACE_URI, tracker.getURI("xml"));
        assertEquals(NamespaceScopeTracker.XMLNS_NAMESPACE_URI, tracker.getURI("xmlns"));
        tracker.pushContext();
        assertEquals(0, tracker.getCurrentScopeDeclarationCount());
        tracker.declarePrefix("c", "urn:c");
        assertEquals("urn:c", tracker.getURI("c"));
        tracker.popContext();
        assertNull(tracker.getURI("c"));
    }

    @Test
    public void theBindingVersionChangesWhenABindingIsDeclaredOrEnds() {
        NamespaceScopeTracker tracker = new NamespaceScopeTracker();
        tracker.pushContext();
        int version = tracker.getBindingVersion();
        tracker.popContext();
        assertEquals("an element that declares nothing leaves what names mean as it was",
                version, tracker.getBindingVersion());
        tracker.pushContext();
        tracker.declarePrefix("a", "urn:a");
        int declared = tracker.getBindingVersion();
        assertTrue(declared != version);
        tracker.popContext();
        assertTrue(tracker.getBindingVersion() != declared);
    }
}
