/*
 * XSLTNode.java
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

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.xml.sax.SAXException;

/**
 * Base interface for all XSLT AST nodes.
 *
 * <p>XSLT AST nodes represent compiled XSLT instructions that can be executed
 * to produce output. Each node is both a data structure (representing the
 * parsed instruction) and an executor (able to perform the transformation).
 *
 * <p>Execution is driven by calling {@link #execute(TransformContext, OutputHandler)}
 * which transforms input context into output events.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface XSLTNode {

    /**
     * Executes this XSLT node.
     *
     * <p>This method performs the transformation action represented by this node,
     * potentially generating output events on the output handler.
     *
     * @param context the transformation context (current node, variables, etc.)
     * @param output the output handler for generated events
     * @throws SAXException if an error occurs during transformation
     */
    void execute(TransformContext context, OutputHandler output) throws SAXException;

    /**
     * Returns the streaming capability of this node.
     *
     * @return the streaming capability
     */
    default StreamingCapability getStreamingCapability() {
        return StreamingCapability.FULL;
    }

    /**
     * Streaming capability levels.
     */
    enum StreamingCapability {
        /** Can execute in full streaming mode with no buffering. */
        FULL,
        
        /** Can stream but requires the current subtree to be buffered. */
        GROUNDED,
        
        /** Requires partial buffering of the document. */
        PARTIAL,
        
        /** Requires the full document to be buffered. */
        NONE
    }

}
