/*
 * GonzalezSAXParserFactoryTest.java
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
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.junit.Test;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.Assert.*;

/**
 * Regression tests for GonzalezSAXParserFactory's feature persistence: any
 * feature name not backed by a dedicated SAXParserFactory method (namespaces,
 * validation, secure-processing) previously validated against a throwaway
 * Parser instance in setFeature() but never stored the value anywhere, so
 * newSAXParser() silently ignored it - setFeature() looked like it worked but
 * had no effect on parsers the factory actually created, and getFeature()
 * always reported the class default regardless of what was set.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GonzalezSAXParserFactoryTest {

    private static final String DISALLOW_DOCTYPE_DECL_FEATURE =
        "http://apache.org/xml/features/disallow-doctype-decl";

    @Test
    public void testGenericFeatureAppliedToCreatedParser() throws Exception {
        String xxeDoc = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                + "<foo>&xxe;</foo>";

        SAXParserFactory factory = new GonzalezSAXParserFactory();
        factory.setFeature(DISALLOW_DOCTYPE_DECL_FEATURE, true);
        SAXParser saxParser = factory.newSAXParser();

        try {
            saxParser.parse(new ByteArrayInputStream(xxeDoc.getBytes("UTF-8")), new DefaultHandler());
            fail("Expected disallow-doctype-decl to reject the DOCTYPE");
        } catch (SAXException e) {
            assertTrue("Error should mention disallow-doctype-decl, was: " + e.getMessage(),
                    e.getMessage().contains(DISALLOW_DOCTYPE_DECL_FEATURE));
        }
    }

    @Test
    public void testGenericFeatureGetFeatureReflectsSetFeature() throws Exception {
        SAXParserFactory factory = new GonzalezSAXParserFactory();

        assertFalse("Should default to false before being set",
                factory.getFeature(DISALLOW_DOCTYPE_DECL_FEATURE));

        factory.setFeature(DISALLOW_DOCTYPE_DECL_FEATURE, true);

        assertTrue("getFeature should reflect the value passed to setFeature",
                factory.getFeature(DISALLOW_DOCTYPE_DECL_FEATURE));
    }

    @Test
    public void testGenericFeatureNotSetLeavesParserAtDefault() throws Exception {
        // Without setFeature(), a document with a normal DOCTYPE should still parse.
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ELEMENT foo (#PCDATA)>]><foo>x</foo>";

        SAXParserFactory factory = new GonzalezSAXParserFactory();
        SAXParser saxParser = factory.newSAXParser();
        saxParser.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")), new DefaultHandler());
    }

    @Test
    public void testMultipleParsersFromSameFactoryAllGetFeature() throws Exception {
        String xxeDoc = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                + "<foo>&xxe;</foo>";

        SAXParserFactory factory = new GonzalezSAXParserFactory();
        factory.setFeature(DISALLOW_DOCTYPE_DECL_FEATURE, true);

        for (int i = 0; i < 3; i++) {
            SAXParser saxParser = factory.newSAXParser();
            try {
                saxParser.parse(new ByteArrayInputStream(xxeDoc.getBytes("UTF-8")), new DefaultHandler());
                fail("Parser #" + i + " should have rejected the DOCTYPE");
            } catch (SAXException e) {
                // expected
            }
        }
    }
}
