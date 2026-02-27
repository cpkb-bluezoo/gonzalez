/*
 * WherePopulatedNode.java
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
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;

/**
 * WherePopulatedNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class WherePopulatedNode extends XSLTInstruction {
    private final XSLTNode content;
    
    public WherePopulatedNode(XSLTNode content) {
        this.content = content;
    }
    
    @Override public String getInstructionName() { return "where-populated"; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (content == null) {
            return;
        }
        
        // Buffer the output to check if it produces anything
        SAXEventBuffer buffer = new SAXEventBuffer();
        BufferOutputHandler bufferOutput = new BufferOutputHandler(buffer);
        
        content.execute(context, bufferOutput);
        
        // Only output if content was produced
        if (!buffer.isEmpty()) {
            // Replay content events through an adapter to the OutputHandler
            if (output instanceof org.xml.sax.ContentHandler) {
                buffer.replayContent((org.xml.sax.ContentHandler) output);
            } else {
                // For OutputHandlers that don't implement ContentHandler,
                // convert buffer to RTF and serialize
                XPathResultTreeFragment rtf = new XPathResultTreeFragment(buffer);
                String text = rtf.asString();
                if (!text.isEmpty()) {
                    output.characters(text);
                }
            }
        }
    }
}
