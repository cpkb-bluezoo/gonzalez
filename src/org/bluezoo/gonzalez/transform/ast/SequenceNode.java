/*
 * SequenceNode.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A sequence of XSLT nodes executed in order.
 *
 * <p>This is used to represent the contents of template bodies, conditional
 * branches, and other containers that hold multiple child nodes.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class SequenceNode implements XSLTNode {

    /**
     * Empty sequence singleton.
     *
     * <p>This is a shared instance representing an empty sequence with no children.
     * It can be used to avoid creating multiple empty sequence instances.
     */
    public static final SequenceNode EMPTY = new SequenceNode(Collections.emptyList());

    private final List<XSLTNode> children;
    private final StreamingCapability streamingCapability;

    /**
     * Creates a sequence from a list of nodes.
     *
     * @param children the child nodes
     */
    public SequenceNode(List<XSLTNode> children) {
        this.children = children != null ? 
            Collections.unmodifiableList(new ArrayList<>(children)) : 
            Collections.emptyList();
        this.streamingCapability = computeStreamingCapability();
    }

    /**
     * Creates a sequence from a single node.
     *
     * @param child the single child
     * @return the sequence
     */
    public static SequenceNode of(XSLTNode child) {
        if (child == null) {
            return EMPTY;
        }
        return new SequenceNode(Collections.singletonList(child));
    }

    /**
     * Creates a sequence from multiple nodes.
     *
     * @param children the child nodes
     * @return the sequence
     */
    public static SequenceNode of(XSLTNode... children) {
        if (children == null || children.length == 0) {
            return EMPTY;
        }
        List<XSLTNode> list = new ArrayList<>(children.length);
        Collections.addAll(list, children);
        return new SequenceNode(list);
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        for (XSLTNode child : children) {
            child.execute(context, output);
        }
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return streamingCapability;
    }

    /**
     * Computes the streaming capability as the minimum of all children.
     */
    private StreamingCapability computeStreamingCapability() {
        StreamingCapability result = StreamingCapability.FULL;
        for (XSLTNode child : children) {
            StreamingCapability childCap = child.getStreamingCapability();
            if (childCap.ordinal() > result.ordinal()) {
                result = childCap;
            }
        }
        return result;
    }

    /**
     * Returns the child nodes.
     *
     * @return the children (immutable)
     */
    public List<XSLTNode> getChildren() {
        return children;
    }

    /**
     * Returns true if this sequence is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return children.isEmpty();
    }

    /**
     * Returns the number of children.
     *
     * @return the size
     */
    public int size() {
        return children.size();
    }

    @Override
    public String toString() {
        return "Sequence[" + children.size() + " children]";
    }

}
