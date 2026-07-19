package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Fresh-JVM harness: one corpus, one pipeline per JVM, so numbers are not
 * distorted by shared-JVM effects. Also convenient under JFR.
 *
 *   java ... org.bluezoo.gonzalez.IsolatedAB <corpus> <xmlhandler|sax>
 *
 * corpus: plain|attrs|whitespace|markup|multibyte (benchmark/resources/*-large.xml)
 */
public class IsolatedAB {

    public static void main(String[] args) throws Exception {
        byte[] bytes = corpus(args[0]);
        String mode = args[1];
        double mb = bytes.length / (1024.0 * 1024.0);
        int warmup = Integer.getInteger("warmup", 800);
        int measured = Integer.getInteger("measured", 500);
        for (int i = 0; i < warmup; i++) {
            parse(bytes, mode);
        }
        long total = 0;
        for (int i = 0; i < measured; i++) {
            long t = System.nanoTime();
            parse(bytes, mode);
            total += System.nanoTime() - t;
        }
        double avgMs = (total / (double) measured) / 1_000_000.0;
        System.out.printf("%-12s %-10s %8.3f ms %8.1f MB/s%n",
                args[0], mode, avgMs, mb / (avgMs / 1000.0));
    }

    private static byte[] corpus(String name) throws Exception {
        switch (name) {
            case "plain": return Files.readAllBytes(Paths.get("benchmark/resources/large.xml"));
            default: return Files.readAllBytes(Paths.get("benchmark/resources/" + name + "-large.xml"));
        }
    }

    private static void parse(byte[] bytes, String mode) throws Exception {
        if (mode.equals("sax")) {
            org.xml.sax.XMLReader reader = new Parser();
            reader.setFeature("http://xml.org/sax/features/namespaces", true);
            reader.setContentHandler(new org.xml.sax.helpers.DefaultHandler());
            reader.parse(new org.xml.sax.InputSource(new java.io.ByteArrayInputStream(bytes)));
            return;
        }
        XMLHandler handler = new NamespaceFilter(new EmptyHandler(), false);
        ScannerSettings settings = new ScannerSettings(
                false, false, false, true, "", ScannerSettings.DEFAULT_EXPANSION_LIMIT);
        Scanner scanner = new Scanner(handler, false, null, null, null,
                false, true, settings, true);
        ExternalEntityDecoder decoder = new ExternalEntityDecoder(scanner, null, null, false);
        decoder.receive(ByteBuffer.wrap(bytes));
        decoder.close();
    }

    private static final class EmptyHandler implements XMLHandler {
        @Override public void setLocator(Locator locator) { }
        @Override public void setXml11(boolean xml11) { }
        @Override public void startDocument() { }
        @Override public void endDocument() { }
        @Override public void startElement(String qName) { }
        @Override public void namespace(String prefix, String uri) { }
        @Override public void startAttribute(String name, String type, boolean declared, boolean specified) { }
        @Override public void attributeValueContent(CharBuffer value, boolean end) { }
        @Override public void endAttributes() { }
        @Override public void characters(CharBuffer text, boolean ignorable, boolean end) { }
        @Override public void endElement() { }
        @Override public void startComment() { }
        @Override public void commentData(CharBuffer text, boolean end) { }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
        @Override public void startDTD(String name, String publicId, String systemId) { }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void notationDecl(String name, String publicId, String systemId) { }
        @Override public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) { }
        @Override public void piTarget(String target) { }
        @Override public void piData(CharBuffer data, boolean end) { }
        @Override public void saveBuffers() { }
        @Override public SAXException fatalError(String message) { return new SAXException(message); }
        @Override public void error(String message) { }
    }
}
