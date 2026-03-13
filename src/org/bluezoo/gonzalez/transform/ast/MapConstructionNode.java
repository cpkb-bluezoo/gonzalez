/*
 * MapConstructionNode.java
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

import java.util.LinkedHashMap;
import java.util.Map;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT 3.0 xsl:map instruction.
 *
 * <p>Executes its children (typically {@code xsl:map-entry} instructions)
 * and collects the resulting single-entry maps into a single {@code XPathMap}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class MapConstructionNode extends XSLTInstruction {

    private final SequenceNode content;

    /**
     * Creates a map construction node.
     *
     * @param content the child instructions (typically xsl:map-entry elements)
     */
    public MapConstructionNode(SequenceNode content) {
        this.content = content;
    }

    /**
     * Returns the content sequence node (typically xsl:map-entry children).
     *
     * @return the content node, or null if empty
     */
    public SequenceNode getContent() {
        return content;
    }

    @Override
    public String getInstructionName() {
        return "map";
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        Map<String, XPathValue> resultEntries = new LinkedHashMap<String, XPathValue>();
        Map<String, XPathValue> resultTypedKeys = new LinkedHashMap<String, XPathValue>();
        SequenceBuilderOutputHandler collector = new SequenceBuilderOutputHandler();
        if (content != null && content.getChildren() != null) {
            for (XSLTNode child : content.getChildren()) {
                child.execute(context, collector);
                collector.markItemBoundary();
            }
        }
        XPathValue collected = collector.getSequence();
        if (collected instanceof XPathMap) {
            mergeMapEntries(resultEntries, resultTypedKeys, (XPathMap) collected);
        } else if (collected instanceof XPathSequence) {
            XPathSequence seq = (XPathSequence) collected;
            for (XPathValue item : seq) {
                if (item instanceof XPathMap) {
                    mergeMapEntries(resultEntries, resultTypedKeys, (XPathMap) item);
                } else {
                    throw new SAXException("XTTE3375: Non-map item in xsl:map content");
                }
            }
        } else if (collected != null) {
            throw new SAXException("XTTE3375: Non-map item in xsl:map content");
        }
        XPathMap resultMap = new XPathMap(resultEntries, resultTypedKeys);
        output.atomicValue(resultMap);
    }

    /**
     * Merges entries from a source map into the target. Duplicate detection
     * uses XPath key identity: two keys are the same only if they have the
     * same type and value.
     */
    private void mergeMapEntries(Map<String, XPathValue> target,
            Map<String, XPathValue> targetTypedKeys, XPathMap source)
            throws SAXException {
        for (Map.Entry<String, XPathValue> entry : source.entries()) {
            String key = entry.getKey();
            XPathValue typedKey = source.getTypedKey(key);
            if (target.containsKey(key)) {
                XPathValue existingTypedKey = targetTypedKeys.get(key);
                if (sameKeyType(existingTypedKey, typedKey)) {
                    throw new SAXException("XTDE3365: Duplicate key '" + key +
                        "' in xsl:map");
                }
                String syntheticKey = key + "\0" + target.size();
                target.put(syntheticKey, entry.getValue());
                if (typedKey != null) {
                    targetTypedKeys.put(syntheticKey, typedKey);
                }
            } else {
                target.put(key, entry.getValue());
                if (typedKey != null) {
                    targetTypedKeys.put(key, typedKey);
                }
            }
        }
    }

    /**
     * Checks if two typed keys have the same XPath type for duplicate detection.
     */
    private boolean sameKeyType(XPathValue a, XPathValue b) {
        if (a == null || b == null) {
            return true;
        }
        boolean aIsString = (a instanceof XPathString) && !(a instanceof XPathUntypedAtomic);
        boolean bIsString = (b instanceof XPathString) && !(b instanceof XPathUntypedAtomic);
        if (aIsString && bIsString) {
            return true;
        }
        return a.getClass().equals(b.getClass());
    }
}
