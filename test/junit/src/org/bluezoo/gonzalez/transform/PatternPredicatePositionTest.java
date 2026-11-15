/*
 * PatternPredicatePositionTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A pattern predicate that uses position() or last() somewhere other
 * than at the top of the expression must still see the real position
 * and size among the matching siblings.
 */
public class PatternPredicatePositionTest {

    private static final String SOURCE =
        "<doc><a/><b/><c/><d/></doc>";

    @Test
    public void lastInsideIfExpression() throws Exception {
        assertEquals("d", matched("doc/*[if (count(/*) le 1) then last() else 1]"));
    }

    @Test
    public void lastInsideElseBranch() throws Exception {
        assertEquals("d", matched("doc/*[if (count(/*) gt 1) then 1 else last()]"));
    }

    @Test
    public void positionInsideIfCondition() throws Exception {
        assertEquals("b", matched("doc/*[if (position() = 2) then true() else false()]"));
    }

    @Test
    public void lastInsideLetExpression() throws Exception {
        assertEquals("d", matched("doc/*[let $n := 1 return last()]"));
    }

    @Test
    public void lastInsideSequence() throws Exception {
        assertEquals("ad", matched("doc/*[position() = (1, last())]"));
    }

    /**
     * Returns the names of the source's children of doc that the given
     * pattern matches, concatenated.
     */
    private static String matched(String pattern) throws Exception {
        String stylesheet =
            "<xsl:stylesheet version='2.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='text'/>"
            + "<xsl:template match='/'>"
            + "<xsl:apply-templates select='//*'/>"
            + "</xsl:template>"
            + "<xsl:template match='" + pattern + "' priority='2'>"
            + "<xsl:value-of select='name()'/>"
            + "</xsl:template>"
            + "<xsl:template match='*' priority='1'/>"
            + "</xsl:stylesheet>";
        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        Transformer transformer = factory.newTransformer(
                new StreamSource(new ByteArrayInputStream(
                        stylesheet.getBytes(StandardCharsets.UTF_8))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(
                        SOURCE.getBytes(StandardCharsets.UTF_8))),
                new StreamResult(output));
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
