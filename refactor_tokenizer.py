#!/usr/bin/env python3
"""
Refactor Tokenizer to remove byte-level concerns, keeping only character-level tokenization.
"""

import re

def refactor_tokenizer(filepath):
    with open(filepath, 'r') as f:
        content = f.read()
    
    # 1. Remove ByteBuffer-related imports and Locator2
    content = re.sub(r'import java\.nio\.ByteBuffer;\n', '', content)
    content = re.sub(r'import java\.nio\.charset\.Charset;\n', '', content)
    content = re.sub(r'import java\.nio\.charset\.CharsetDecoder;\n', '', content)
    content = re.sub(r'import java\.nio\.charset\.CoderResult;\n', '', content)
    content = re.sub(r'import java\.nio\.charset\.StandardCharsets;\n', '', content)
    content = re.sub(r'import org\.xml\.sax\.ext\.Locator2;\n', '', content)
    
    # 2. Change class declaration
    content = content.replace('public class Tokenizer implements Locator2 {', 'public class Tokenizer {')
    
    # 3. Remove the emitTokenWindow column parameter and update all calls
    content = re.sub(r'emitTokenWindow\(([^,]+), ([^,]+), ([^,]+), tokenStartColumn\)', 
                     r'emitTokenWindow(\1, \2, \3)', content)
    content = content.replace('private void emitTokenWindow(Token token, int start, int length, int column)',
                             'private void emitTokenWindow(Token token, int start, int length)')
    
    # 4. Remove all tokenStartColumn = assignments
    content = re.sub(r'^\s*tokenStartColumn = .*;\n', '', content, flags=re.MULTILINE)
    
    # 5. Remove all columnNumber updates
    content = re.sub(r'^\s*columnNumber\+\+;\n', '', content, flags=re.MULTILINE)
    content = re.sub(r'^\s*columnNumber = .*;\n', '', content, flags=re.MULTILINE)
    
    # 6. Remove all lineNumber updates
    content = re.sub(r'^\s*lineNumber\+\+;\n', '', content, flags=re.MULTILINE)
    content = re.sub(r'^\s*lineNumber = .*;\n', '', content, flags=re.MULTILINE)
    
    # 7. Remove charsetSwitched references
    content = re.sub(r'^\s*charsetSwitched = .*;\n', '', content, flags=re.MULTILINE)
    content = re.sub(r'if \(charsetSwitched\) \{\s*\}', '', content, flags=re.DOTALL)
    
    with open(filepath, 'w') as f:
        f.write(content)

if __name__ == '__main__':
    refactor_tokenizer('/Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez/src/org/bluezoo/gonzalez/Tokenizer.java')
    print("Refactoring complete!")

