/*
 * ForkAccumulatorTest.java
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

import static org.junit.Assert.assertTrue;

/**
 * The branches of an xsl:fork run at the same time, each with its own copy of
 * the accumulators. The check that an accumulator does not depend on itself
 * is about one evaluation, and so must not see an evaluation that another
 * branch is making: a branch that reads an accumulator while another is
 * working one out is not a cycle.
 * <p>
 * This is the W3C test si-fork-816, in which one branch has an accumulator
 * whose rule reads another accumulator, while the other branch is working
 * that one out. Whether the two branches meet at that moment depends on how
 * the threads happen to be scheduled, so the transformation is made many
 * times.
 */
public class ForkAccumulatorTest {

    private static final String STYLESHEET =
        ""
        + "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\"\n"
        + "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n"
        + "xmlns:map=\"http://www.w3.org/2005/xpath-functions/map\"\n"
        + "exclude-result-prefixes=\"#all\"\n"
        + "expand-text=\"yes\"\n"
        + "version=\"3.0\">\n"
        + "<xsl:mode streamable=\"yes\" on-no-match=\"shallow-copy\" use-accumulators=\"#all\"/>\n"
        + "<xsl:strip-space elements=\"*\"/>\n"
        + "<xsl:output indent=\"yes\"/>\n"
        + "<xsl:accumulator name=\"inventtransid\" as=\"xs:string?\" initial-value=\"()\" streamable=\"yes\">\n"
        + "<xsl:accumulator-rule match=\"CustInvoiceTrans\" select=\"()\"/>\n"
        + "<xsl:accumulator-rule match=\"CustInvoiceTrans/InventTransId/text()\" select=\"string()\"/>\n"
        + "</xsl:accumulator>\n"
        + "<xsl:accumulator name=\"map-inventtrans-to-itemid\" as=\"map(xs:string, xs:integer)\" initial-value=\"map{}\" streamable=\"yes\">\n"
        + "<xsl:accumulator-rule match=\"CustInvoiceTrans/ItemId/text()\" select=\"map:put($value, accumulator-before('inventtransid'), xs:integer(.))\"/>\n"
        + "</xsl:accumulator>\n"
        + "<xsl:template match=\"/*\">\n"
        + "<xsl:copy>\n"
        + "<xsl:fork>\n"
        + "<xsl:sequence>\n"
        + "<xsl:for-each-group select=\"CustInvoiceTrans!copy-of()\" group-by=\"ItemId\">\n"
        + "<xsl:copy>\n"
        + "<xsl:apply-templates select=\"@*\"/>\n"
        + "<xsl:apply-templates>\n"
        + "<xsl:with-param name=\"sum\" tunnel=\"yes\" select=\"sum(current-group()/LineAmount)\"/>\n"
        + "</xsl:apply-templates>\n"
        + "</xsl:copy>\n"
        + "</xsl:for-each-group>\n"
        + "</xsl:sequence>\n"
        + "<xsl:sequence>\n"
        + "<xsl:for-each-group select=\"TaxTrans!copy-of()\" group-by=\"accumulator-before('map-inventtrans-to-itemid')(InventTransId)\">\n"
        + "<xsl:copy>\n"
        + "<xsl:apply-templates select=\"@*\"/>\n"
        + "<xsl:apply-templates>\n"
        + "<xsl:with-param name=\"sums\" tunnel=\"yes\" select=\"map { 'TaxAmount' : sum(current-group()/TaxAmount), 'TaxBaseAmount' : sum(current-group()/TaxBaseAmount) }\"/>\n"
        + "</xsl:apply-templates>\n"
        + "</xsl:copy>\n"
        + "</xsl:for-each-group>\n"
        + "</xsl:sequence>\n"
        + "</xsl:fork>\n"
        + "</xsl:copy>\n"
        + "</xsl:template>\n"
        + "<xsl:template match=\"CustInvoiceTrans/LineAmount\">\n"
        + "<xsl:param name=\"sum\" tunnel=\"yes\"/>\n"
        + "<xsl:copy>{$sum}</xsl:copy>\n"
        + "</xsl:template>\n"
        + "<xsl:template match=\"TaxTrans/TaxAmount\">\n"
        + "<xsl:param name=\"sums\" tunnel=\"yes\"/>\n"
        + "<xsl:copy>{$sums?TaxAmount}</xsl:copy>\n"
        + "</xsl:template>\n"
        + "<xsl:template match=\"TaxTrans/TaxBaseAmount\">\n"
        + "<xsl:param name=\"sums\" tunnel=\"yes\"/>\n"
        + "<xsl:copy>{$sums?TaxBaseAmount}</xsl:copy>\n"
        + "</xsl:template>\n"
        + "</xsl:stylesheet>\n";

    private static final String SOURCE =
        ""
        + "<CustInvoiceJour class=\"entity\">\n"
        + "<CustInvoiceTrans class=\"entity\">\n"
        + "<InventTransId>AABB</InventTransId>\n"
        + "<InvoiceId>SI100</InvoiceId>\n"
        + "<ItemId>444</ItemId>\n"
        + "<LineAmount>100</LineAmount>\n"
        + "</CustInvoiceTrans>\n"
        + "<CustInvoiceTrans class=\"entity\">\n"
        + "<InventTransId>BBCC</InventTransId>\n"
        + "<InvoiceId>SI100</InvoiceId>\n"
        + "<ItemId>444</ItemId>\n"
        + "<LineAmount>25</LineAmount>\n"
        + "</CustInvoiceTrans>\n"
        + "<CustInvoiceTrans class=\"entity\">\n"
        + "<InventTransId>CCDD</InventTransId>\n"
        + "<InvoiceId>SI100</InvoiceId>\n"
        + "<ItemId>555</ItemId>\n"
        + "<LineAmount>200</LineAmount>\n"
        + "</CustInvoiceTrans>\n"
        + "<CustInvoiceTrans class=\"entity\">\n"
        + "<InventTransId>DDEE</InventTransId>\n"
        + "<InvoiceId>SI100</InvoiceId>\n"
        + "<ItemId>555</ItemId>\n"
        + "<LineAmount>15</LineAmount>\n"
        + "</CustInvoiceTrans>\n"
        + "<CustInvoiceTrans class=\"entity\">\n"
        + "<InventTransId>EEFF</InventTransId>\n"
        + "<InvoiceId>SI100</InvoiceId>\n"
        + "<ItemId>12345</ItemId>\n"
        + "<LineAmount>40</LineAmount>\n"
        + "</CustInvoiceTrans>\n"
        + "<TaxTrans class=\"entity\">\n"
        + "<InventTransId>AABB</InventTransId>\n"
        + "<TaxAmount>-21</TaxAmount>\n"
        + "<TaxBaseAmount>-100</TaxBaseAmount>\n"
        + "<TaxValue>21.00</TaxValue>\n"
        + "</TaxTrans>\n"
        + "<TaxTrans class=\"entity\">\n"
        + "<InventTransId>BBCC</InventTransId>\n"
        + "<TaxAmount>-5.25</TaxAmount>\n"
        + "<TaxBaseAmount>-25</TaxBaseAmount>\n"
        + "<TaxValue>21.00</TaxValue>\n"
        + "</TaxTrans>\n"
        + "<TaxTrans class=\"entity\">\n"
        + "<InventTransId>CCDD</InventTransId>\n"
        + "<TaxAmount>-42</TaxAmount>\n"
        + "<TaxBaseAmount>-200</TaxBaseAmount>\n"
        + "<TaxValue>21.00</TaxValue>\n"
        + "</TaxTrans>\n"
        + "<TaxTrans class=\"entity\">\n"
        + "<InventTransId>DDEE</InventTransId>\n"
        + "<TaxAmount>-3.15</TaxAmount>\n"
        + "<TaxBaseAmount>-15</TaxBaseAmount>\n"
        + "<TaxValue>15</TaxValue>\n"
        + "</TaxTrans>\n"
        + "<TaxTrans class=\"entity\">\n"
        + "<InventTransId>EEFF</InventTransId>\n"
        + "<TaxAmount>-8.40</TaxAmount>\n"
        + "<TaxBaseAmount>-40</TaxBaseAmount>\n"
        + "<TaxValue>15</TaxValue>\n"
        + "</TaxTrans>\n"
        + "</CustInvoiceJour>\n";

    @Test
    public void branchesReadingEachOthersAccumulatorsAreNotACycle()
            throws Exception {
        GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
        for (int run = 0; run < 100; run++) {
            Transformer transformer = factory.newTransformer(new StreamSource(
                new ByteArrayInputStream(
                    STYLESHEET.getBytes(StandardCharsets.UTF_8))));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                transformer.transform(new StreamSource(new ByteArrayInputStream(
                    SOURCE.getBytes(StandardCharsets.UTF_8))),
                    new StreamResult(output));
            } catch (Exception e) {
                throw new AssertionError("run " + run + ": " + e, e);
            }
            String result = new String(output.toByteArray(),
                StandardCharsets.UTF_8);
            assertTrue(result, result.contains("<TaxValue>21.00</TaxValue>"));
        }
    }
}
