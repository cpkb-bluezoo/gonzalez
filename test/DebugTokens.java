import org.bluezoo.gonzalez.*;
import java.io.*;

/**
 * Simple tool to show all tokens emitted while parsing a file.
 * Just enables debug mode on Tokenizer via system property.
 * Usage: java DebugTokens <xmlfile>
 */
public class DebugTokens {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java DebugTokens <xmlfile>");
            System.exit(1);
        }
        
        String filename = args[0];
        File file = new File(filename);
        
        System.err.println("=== Parsing: " + filename + " ===\n");
        
        // Read and display the file content
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        int lineNum = 1;
        while ((line = reader.readLine()) != null) {
            System.err.println(String.format("%3d: %s", lineNum++, line));
        }
        reader.close();
        
        System.err.println("\n=== Tokens: ===\n");
        
        // Enable debug mode
        System.setProperty("gonzalez.debug.tokens", "true");
        
        // Parse
        Parser parser = new Parser();
        try {
            parser.parse(new org.xml.sax.InputSource(new FileInputStream(file)));
            System.err.println("\n=== Parsing completed successfully ===");
        } catch (Exception e) {
            System.err.println("\n=== Parsing failed: ===");
            System.err.println(e.getMessage());
            // e.printStackTrace();
        }
    }
}
