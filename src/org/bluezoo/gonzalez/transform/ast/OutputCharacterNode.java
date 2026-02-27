/*
 * OutputCharacterNode.java
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

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;

/**
 * OutputCharacterNode XSLT node.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class OutputCharacterNode implements XSLTNode {
    private final int codePoint;  // Unicode code point (supports supplementary characters)
    private final String string;
    
    public OutputCharacterNode(int codePoint, String string) {
        this.codePoint = codePoint;
        this.string = string;
    }
    
    public int getCodePoint() { return codePoint; }
    public String getString() { return string; }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) {
        // Not executed directly - used for data storage only
    }
    
    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.FULL;
    }
}
