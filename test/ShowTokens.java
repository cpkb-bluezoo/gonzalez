package org.bluezoo.gonzalez;

import java.io.*;
import java.lang.reflect.Field;

/**
 * Simple tool to show all tokens emitted while parsing a file.
 * Uses reflection to insert a DebugTokenConsumer between tokenizer and parser.
 * Usage: java org.bluezoo.gonzalez.ShowTokens <xmlfile>
 */
public class ShowTokens {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java org.bluezoo.gonzalez.ShowTokens <xmlfile>");
            System.exit(1);
        }
        
        String filename = args[0];
        File file = new File(filename);
        
        System.out.println("=== Parsing: " + filename + " ===\n");
        
        // Read and display the file content
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        int lineNum = 1;
        while ((line = reader.readLine()) != null) {
            System.out.println(String.format("%3d: %s", lineNum++, line));
        }
        reader.close();
        
        System.out.println("\n=== Tokens: ===\n");
        
        // Create parser
        Parser parser = new Parser();
        
        // Use reflection to get the tokenizer and xmlParser
        Field tokenizerField = Parser.class.getDeclaredField("tokenizer");
        tokenizerField.setAccessible(true);
        Tokenizer tokenizer = (Tokenizer) tokenizerField.get(parser);
        
        Field xmlParserField = Parser.class.getDeclaredField("xmlParser");
        xmlParserField.setAccessible(true);
        ContentParser xmlParser = (ContentParser) xmlParserField.get(parser);
        
        // Use reflection to replace tokenizer's consumer with DebugTokenConsumer
        Field consumerField = Tokenizer.class.getDeclaredField("consumer");
        consumerField.setAccessible(true);
        
        DebugTokenConsumer debugConsumer = new DebugTokenConsumer(xmlParser);
        consumerField.set(tokenizer, debugConsumer);
        
        // Parse
        try {
            parser.parse(new org.xml.sax.InputSource(new FileInputStream(file)));
            System.out.println("\n=== Parsing completed successfully ===");
        } catch (Exception e) {
            System.out.println("\n=== Parsing failed: ===");
            System.out.println(e.getMessage());
        }
    }
}
