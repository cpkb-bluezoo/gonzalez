/*
 * DocumentLoaderNativeTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.util.Collections;
import java.util.Iterator;

import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DocumentLoaderNativeTest {

    @After
    public void clearCache() {
        DocumentLoader.clearCache();
    }

    @Test
    public void loadsNamespacedDocumentAndIdFragment() throws Exception {
        String xml =
            "<!DOCTYPE p:root [<!ATTLIST p:item id ID #REQUIRED>]>"
            + "<p:root xmlns:p='urn:test'>"
            + "<p:item id='target' p:value='ok'/>"
            + "<!--c--><?pi data?>"
            + "</p:root>";

        XPathNode root = DocumentLoader.loadDocumentFromString(
                xml, "memory:doc.xml", null, null);
        assertNotNull(root);
        assertEquals(NodeType.ROOT, root.getNodeType());

        XPathNode element = root.getChildren().next();
        assertEquals("root", element.getLocalName());
        assertEquals("urn:test", element.getNamespaceURI());

        XPathNode item = element.getChildren().next();
        assertEquals("item", item.getLocalName());

        boolean foundId = false;
        boolean foundValue = false;
        Iterator<XPathNode> attrs = item.getAttributes();
        while (attrs.hasNext()) {
            XPathNode attr = attrs.next();
            if ("id".equals(attr.getLocalName())) {
                foundId = true;
                assertEquals("ID", attr.getTypeLocalName());
            }
            if ("value".equals(attr.getLocalName())) {
                foundValue = true;
                assertEquals("urn:test", attr.getNamespaceURI());
                assertEquals("ok", attr.getStringValue());
            }
        }
        assertTrue(foundId);
        assertTrue(foundValue);

        boolean foundComment = false;
        boolean foundPi = false;
        Iterator<XPathNode> children = element.getChildren();
        while (children.hasNext()) {
            XPathNode child = children.next();
            if (child.getNodeType() == NodeType.COMMENT) {
                foundComment = true;
                assertEquals("c", child.getStringValue());
            }
            if (child.getNodeType() == NodeType.PROCESSING_INSTRUCTION) {
                foundPi = true;
                assertEquals("pi", child.getLocalName());
                assertEquals("data", child.getStringValue());
            }
        }
        assertTrue(foundComment);
        assertTrue(foundPi);
    }

    @Test
    public void stripsWhitespaceOnlyTextWhenConfigured() throws Exception {
        String xml = "<root>\n  <item>x</item>\n</root>";
        XPathNode root = DocumentLoader.loadDocumentFromString(
                xml, null, Collections.singletonList("*"), null);
        XPathNode element = root.getChildren().next();
        Iterator<XPathNode> children = element.getChildren();
        XPathNode only = children.next();
        assertEquals("item", only.getLocalName());
        assertTrue(!children.hasNext());
    }
}
