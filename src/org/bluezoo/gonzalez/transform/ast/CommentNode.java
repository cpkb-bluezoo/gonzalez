/*
 * CommentNode.java
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

package org.bluezoo.gonzalez.transform.ast;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;

/**
 * CommentNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CommentNode extends XSLTInstruction {
    private final SequenceNode content;
    public CommentNode(SequenceNode content) { this.content = content; }
    @Override public String getInstructionName() { return "comment"; }
    @Override public void execute(TransformContext context, 
                                  OutputHandler output) throws SAXException {
        String text = "";
        if (content != null) {
            SAXEventBuffer buffer = 
                new SAXEventBuffer();
            content.execute(context, new org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler(buffer));
            text = buffer.getTextContent();
        }
        // Per XSLT spec: insert space after any "--" sequence and before trailing "-"
        // to ensure well-formed XML output
        text = sanitizeCommentText(text);
        output.comment(text);
    }
    
    /**
     * Sanitizes comment text to ensure well-formed XML output.
     * XML comments cannot contain "--" or end with "-".
     */
    private static String sanitizeCommentText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Replace all "--" with "- -" (insert space between adjacent hyphens)
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' && prev == '-') {
                sb.append(' '); // Insert space before this hyphen
            }
            sb.append(c);
            prev = c;
        }
        // If comment ends with "-", append a space
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.append(' ');
        }
        return sb.toString();
    }
}
