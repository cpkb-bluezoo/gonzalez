/*
 * BreakNode.java
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
import org.xml.sax.SAXException;

/**
 * XSLT 3.0 xsl:break instruction.
 *
 * <p>Used within xsl:iterate to terminate iteration early. Any content
 * within xsl:break is evaluated as the iteration result before the
 * xsl:on-completion executes.
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:if test="@stop"&gt;
 *   &lt;xsl:break&gt;
 *     &lt;stopped/&gt;
 *   &lt;/xsl:break&gt;
 * &lt;/xsl:if&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class BreakNode implements XSLTNode {

    private final XSLTNode content;

    /**
     * Creates a new break instruction.
     *
     * @param content the content to output before breaking (may be null)
     */
    public BreakNode(XSLTNode content) {
        this.content = content;
    }

    /**
     * Returns the break content.
     *
     * @return the content node, or null
     */
    public XSLTNode getContent() {
        return content;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        // Execute content first if present
        if (content != null) {
            content.execute(context, output);
        }
        
        // Signal break to enclosing xsl:iterate
        throw new BreakSignal();
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.FULL;
    }

    @Override
    public String toString() {
        return "BreakNode[hasContent=" + (content != null) + "]";
    }

    /**
     * Signal exception used to communicate break to the iterate loop.
     */
    public static class BreakSignal extends SAXException {
        public BreakSignal() {
            super("break");
        }
    }

}
