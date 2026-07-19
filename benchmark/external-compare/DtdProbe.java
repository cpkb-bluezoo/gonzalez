/*
 * DtdProbe.java
 *
 * Diagnostic companion to ExternalCompare: rather than measuring throughput,
 * it inspects the SAX events each parser produces for the XHTML internal-subset
 * corpus, to establish whether the internal DTD subset is actually being
 * processed. Three facts in that corpus exist only in the DTD:
 *
 *   - <input> carries only id/name in the instance, but the DTD declares
 *     "type CDATA \"text\"" - a DTD-processing parser reports a defaulted
 *     type="text" attribute that is not physically present.
 *   - id attributes are declared as ID, so Attributes.getType() should be
 *     "ID" rather than the "CDATA" a DTD-blind parser reports.
 *   - <th scope="col"> declares scope as an enumeration (row | col).
 *
 * Run via benchmark/external-compare/run-dtd-probe.sh.
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.Attributes2;
import org.xml.sax.ext.DefaultHandler2;
import org.xml.sax.helpers.DefaultHandler;

public class DtdProbe {

    public static void main(String[] args) throws Exception {
        byte[] bytes = BenchmarkCorpora.xhtmlInternalSubset();

        System.out.println("Probing the xhtml-dtd corpus for DTD-derived SAX facts.");
        System.out.println("A DTD-blind parser reports: startDTD=no, input type absent,");
        System.out.println("id type=CDATA. A DTD-processing parser reports the opposite.");
        System.out.println();

        probeGonzalez(bytes);

        SAXParserFactory jdk = SAXParserFactory.newInstance();
        jdk.setNamespaceAware(true);
        jdk.setValidating(false);
        probeJaxp("jdk-xerces", jdk.newSAXParser(), bytes);

        SAXParserFactory aalto = new com.fasterxml.aalto.sax.SAXParserFactoryImpl();
        aalto.setNamespaceAware(true);
        probeJaxp("aalto-sax", aalto.newSAXParser(), bytes);
    }

    private static void probeGonzalez(byte[] bytes) throws Exception {
        Parser reader = new Parser();
        reader.setFeature("http://xml.org/sax/features/namespaces", true);
        Probe probe = new Probe("gonzalez-sax");
        install(reader, probe);
        run(reader, bytes, probe);
    }

    private static void probeJaxp(String label, SAXParser parser, byte[] bytes) throws Exception {
        XMLReader reader = parser.getXMLReader();
        Probe probe = new Probe(label);
        install(reader, probe);
        run(reader, bytes, probe);
    }

    private static void install(XMLReader reader, Probe probe) {
        reader.setContentHandler(probe);
        try {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", probe);
        } catch (SAXException e) {
            probe.lexicalHandlerSupported = false;
        }
    }

    private static void run(XMLReader reader, byte[] bytes, Probe probe) throws Exception {
        try {
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Probe.Done done) {
            // expected: we abort once the first <input> is seen
        }
        probe.report();
    }

    private static final class Probe extends DefaultHandler2 {
        static final class Done extends SAXException {
            Done() {
                super("probe complete");
            }
        }

        private final String label;
        boolean lexicalHandlerSupported = true;
        private boolean sawStartDtd;
        private String dtdName;

        private boolean sawInput;
        private boolean inputHasType;
        private String inputTypeValue;
        private Boolean inputTypeSpecified;

        private String firstIdType;

        Probe(String label) {
            this.label = label;
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) {
            sawStartDtd = true;
            dtdName = name;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            String name = localName != null && !localName.isEmpty() ? localName : qName;

            if (firstIdType == null) {
                int idIndex = atts.getIndex("id");
                if (idIndex < 0) {
                    idIndex = atts.getIndex("", "id");
                }
                if (idIndex >= 0) {
                    firstIdType = atts.getType(idIndex);
                }
            }

            if ("input".equals(name)) {
                sawInput = true;
                int typeIndex = atts.getIndex("type");
                if (typeIndex < 0) {
                    typeIndex = atts.getIndex("", "type");
                }
                if (typeIndex >= 0) {
                    inputHasType = true;
                    inputTypeValue = atts.getValue(typeIndex);
                    if (atts instanceof Attributes2) {
                        inputTypeSpecified = ((Attributes2) atts).isSpecified(typeIndex);
                    }
                }
                throw new Done();
            }
        }

        void report() {
            System.out.println("== " + label + " ==");
            System.out.println("  lexical-handler supported: " + lexicalHandlerSupported);
            System.out.println("  startDTD fired:            " + sawStartDtd
                    + (sawStartDtd ? " (name=" + dtdName + ")" : ""));
            System.out.println("  first id attribute type:   "
                    + (firstIdType == null ? "<no id attr seen>" : firstIdType)
                    + "   (ID => DTD processed, CDATA => not)");
            if (!sawInput) {
                System.out.println("  <input> element:           not reached");
            } else if (inputHasType) {
                System.out.println("  <input> defaulted type:    present, value=\"" + inputTypeValue
                        + "\", specified=" + inputTypeSpecified
                        + "   (present+specified=false => DTD default applied)");
            } else {
                System.out.println("  <input> defaulted type:    ABSENT"
                        + "   (DTD default NOT applied)");
            }
            System.out.println();
        }
    }

    private DtdProbe() {
    }
}
