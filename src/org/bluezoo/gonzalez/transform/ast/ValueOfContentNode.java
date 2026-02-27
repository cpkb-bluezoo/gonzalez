/*
 * ValueOfContentNode.java
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
 * ValueOfContentNode XSLT instruction.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ValueOfContentNode extends XSLTInstruction {
    private final XSLTNode content;
    private final boolean disableEscaping;
    private final String separator;  // null means use default (empty string for content)
    
    public ValueOfContentNode(XSLTNode content, boolean disableEscaping, String separator) {
        this.content = content;
        this.disableEscaping = disableEscaping;
        this.separator = separator;
    }
    
    @Override public String getInstructionName() { return "value-of"; }
    
    @Override
    public void execute(TransformContext context, 
                       OutputHandler output) throws SAXException {
        // Execute content into a buffer to capture its string output
        SAXEventBuffer eventBuffer = new SAXEventBuffer();
        BufferOutputHandler buffer = new BufferOutputHandler(eventBuffer);
        content.execute(context, buffer);
        
        String value = eventBuffer.getTextContent();
        
        // Only output non-empty values to preserve empty element serialization
        if (value != null && !value.isEmpty()) {
            if (disableEscaping) {
                output.charactersRaw(value);
            } else {
                output.characters(value);
            }
        }
    }
}
