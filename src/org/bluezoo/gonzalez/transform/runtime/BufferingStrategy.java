/*
 * BufferingStrategy.java
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

package org.bluezoo.gonzalez.transform.runtime;

/**
 * Determines the buffering strategy for streaming transformations.
 *
 * <p>The buffering strategy specifies how much of the source document
 * needs to be buffered to perform a transformation. Accumulators are
 * the primary mechanism for stateful streaming; buffering is a fallback
 * when streaming is not possible.
 *
 * <p>Strategies are determined at compile time based on stylesheet analysis:
 *
 * <table border="1">
 *   <caption>Buffering Decision Matrix</caption>
 *   <tr><th>Construct</th><th>Strategy</th><th>Reason</th></tr>
 *   <tr><td>Forward axes (child, descendant, following-sibling)</td><td>NONE</td><td>Streamable</td></tr>
 *   <tr><td>Reverse axes (parent, ancestor, preceding-sibling)</td><td>GROUNDED</td><td>Need context</td></tr>
 *   <tr><td>key() function</td><td>FULL_DOCUMENT</td><td>Document-wide index</td></tr>
 *   <tr><td>last() in predicates</td><td>GROUNDED</td><td>Need sibling count</td></tr>
 *   <tr><td>xsl:sort</td><td>GROUNDED</td><td>Collect then sort</td></tr>
 *   <tr><td>Variables with node-set content</td><td>Depends on use</td><td>Analyze references</td></tr>
 *   <tr><td>Accumulators</td><td>NONE</td><td>Explicit state management</td></tr>
 * </table>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum BufferingStrategy {

    /**
     * Pure streaming with accumulators - no buffering required.
     *
     * <p>This is the optimal strategy where the transformation can
     * process events as they arrive without storing any part of the
     * document. State is maintained using accumulators.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Constant memory usage</li>
     *   <li>Can process documents of unlimited size</li>
     *   <li>Single-pass processing</li>
     * </ul>
     */
    NONE,

    /**
     * Buffer only the current subtree.
     *
     * <p>This strategy buffers the current element and its descendants,
     * allowing reverse axis navigation within the subtree while still
     * streaming the rest of the document.
     *
     * <p>Required for:
     * <ul>
     *   <li>parent:: axis</li>
     *   <li>ancestor:: axis</li>
     *   <li>preceding-sibling:: axis</li>
     *   <li>last() in predicates (needs sibling count)</li>
     *   <li>xsl:sort (collect then sort)</li>
     * </ul>
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Memory proportional to subtree depth/breadth</li>
     *   <li>Still O(n) document processing</li>
     *   <li>Multiple passes over subtree possible</li>
     * </ul>
     */
    GROUNDED,

    /**
     * Buffer the entire document.
     *
     * <p>This is the fallback strategy when document-wide constructs
     * are required. The entire document is buffered before processing,
     * enabling full XPath navigation.
     *
     * <p>Required for:
     * <ul>
     *   <li>key() function with document scope</li>
     *   <li>preceding:: axis</li>
     *   <li>following:: axis across siblings</li>
     *   <li>document() function referencing current doc</li>
     * </ul>
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Memory proportional to document size</li>
     *   <li>Two-pass: parse then transform</li>
     *   <li>Full random access</li>
     * </ul>
     */
    FULL_DOCUMENT;

    /**
     * Returns true if this strategy allows streaming (no buffering or grounded only).
     *
     * @return true if streaming is possible
     */
    public boolean allowsStreaming() {
        return this == NONE || this == GROUNDED;
    }

    /**
     * Returns true if this strategy requires no buffering at all.
     *
     * @return true if pure streaming
     */
    public boolean isPureStreaming() {
        return this == NONE;
    }

    /**
     * Returns true if this strategy requires document buffering.
     *
     * @return true if document buffering is required
     */
    public boolean requiresDocumentBuffering() {
        return this == FULL_DOCUMENT;
    }

    /**
     * Combines two buffering strategies, returning the more restrictive one.
     *
     * @param other the other strategy
     * @return the combined strategy
     */
    public BufferingStrategy combine(BufferingStrategy other) {
        // Higher ordinal is more restrictive
        return this.ordinal() > other.ordinal() ? this : other;
    }

    /**
     * Returns a human-readable description of this strategy.
     *
     * @return description
     */
    public String getDescription() {
        switch (this) {
            case NONE:
                return "Pure streaming (no buffering)";
            case GROUNDED:
                return "Grounded (subtree buffering)";
            case FULL_DOCUMENT:
                return "Full document buffering";
            default:
                return name();
        }
    }

}
