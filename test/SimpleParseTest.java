/*
 * SimpleParseTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Simple test program to parse an XML file and print all SAX events.
 * 
 * Usage: java -cp build:test SimpleParseTest <xml-file>
 */
public class SimpleParseTest {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java SimpleParseTest <xml-file>");
            System.exit(1);
        }
        
        File xmlFile = new File(args[0]);
        if (!xmlFile.exists()) {
            System.err.println("File not found: " + xmlFile);
            System.exit(1);
        }
        
        System.out.println("Parsing: " + xmlFile);
        System.out.println("================================================");
        
        try {
            Parser parser = new Parser();
            VerboseHandler handler = new VerboseHandler();
            
            parser.setContentHandler(handler);
            parser.setSystemId(xmlFile.toURI().toString());
            
            // Read and parse the file using proper NIO buffer management:
            // read, flip, receive, compact
            try (FileInputStream fis = new FileInputStream(xmlFile);
                 FileChannel channel = fis.getChannel()) {
                
                ByteBuffer buffer = ByteBuffer.allocate(1024);  // Small buffer to see incremental behavior
                int bytesRead;
                
                // Buffer is in write mode: position indicates end of any unprocessed data
                while (true) {
                    bytesRead = channel.read(buffer);
                    
                    // If we have data in buffer, process it
                    if (buffer.position() > 0) {
                        buffer.flip();  // Switch to read mode
                        System.out.println("[FEED] Feeding " + buffer.remaining() + " bytes to parser");
                        parser.receive(buffer);
                        buffer.compact();  // Compact unprocessed bytes for next cycle
                    }
                    
                    // Exit loop on EOF when no remaining data
                    if (bytesRead == -1 && buffer.position() == 0) {
                        break;
                    }
                }
                
                System.out.println("[CLOSE] Calling parser.close()");
                parser.close();
            }
            
            System.out.println("================================================");
            System.out.println("Parse completed successfully!");
            
        } catch (SAXParseException e) {
            System.out.println("================================================");
            System.err.println("PARSE ERROR at line " + e.getLineNumber() + ", column " + e.getColumnNumber());
            System.err.println("  " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("  Caused by: " + e.getCause());
            }
            System.exit(1);
            
        } catch (Exception e) {
            System.out.println("================================================");
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Handler that prints all SAX events with locator information.
     */
    static class VerboseHandler extends DefaultHandler {
        private Locator locator;
        
        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
            printEvent("setDocumentLocator", "systemId=" + locator.getSystemId() + 
                    ", publicId=" + locator.getPublicId());
        }
        
        @Override
        public void startDocument() throws SAXException {
            printEvent("startDocument", "");
        }
        
        @Override
        public void endDocument() throws SAXException {
            printEvent("endDocument", "");
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) 
                throws SAXException {
            StringBuilder attrs = new StringBuilder();
            for (int i = 0; i < attributes.getLength(); i++) {
                if (i > 0) attrs.append(", ");
                attrs.append(attributes.getQName(i))
                     .append("=\"")
                     .append(attributes.getValue(i))
                     .append("\"");
            }
            printEvent("startElement", qName + (attrs.length() > 0 ? " [" + attrs + "]" : ""));
        }
        
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            printEvent("endElement", qName);
        }
        
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length);
            // Show whitespace clearly
            String display = text.replace("\n", "\\n")
                                 .replace("\r", "\\r")
                                 .replace("\t", "\\t");
            // Truncate long text
            if (display.length() > 60) {
                display = display.substring(0, 60) + "...";
            }
            printEvent("characters", "\"" + display + "\"");
        }
        
        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            String text = new String(ch, start, length);
            String display = text.replace("\n", "\\n")
                                 .replace("\r", "\\r")
                                 .replace("\t", "\\t");
            printEvent("ignorableWhitespace", "\"" + display + "\"");
        }
        
        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            printEvent("processingInstruction", target + " \"" + data + "\"");
        }
        
        @Override
        public void skippedEntity(String name) throws SAXException {
            printEvent("skippedEntity", name);
        }
        
        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            printEvent("startPrefixMapping", prefix + " -> " + uri);
        }
        
        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            printEvent("endPrefixMapping", prefix);
        }
        
        private void printEvent(String eventName, String details) {
            String location = "";
            if (locator != null) {
                location = String.format("[%4d:%-3d] ", locator.getLineNumber(), locator.getColumnNumber());
            }
            System.out.println(location + eventName + ": " + details);
            System.out.flush();  // Ensure output before any potential hang
        }
    }
}

