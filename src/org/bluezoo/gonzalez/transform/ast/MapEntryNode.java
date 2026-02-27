/*
 * MapEntryNode.java
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

import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT 3.0 xsl:map-entry instruction.
 *
 * <p>Produces a single-entry {@code XPathMap} as an atomic value,
 * intended to be collected by an enclosing {@link MapConstructionNode}.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class MapEntryNode extends XSLTInstruction {

    private final XPathExpression keyExpr;
    private final XPathExpression selectExpr;
    private final SequenceNode content;

    /**
     * Creates a map entry node.
     *
     * @param keyExpr expression for the entry key
     * @param selectExpr expression for the entry value (may be null if content is used)
     * @param content child content for the entry value (used when selectExpr is null)
     */
    public MapEntryNode(XPathExpression keyExpr, XPathExpression selectExpr, SequenceNode content) {
        this.keyExpr = keyExpr;
        this.selectExpr = selectExpr;
        this.content = content;
    }

    @Override
    public String getInstructionName() {
        return "map-entry";
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        try {
            XPathValue keyVal = keyExpr.evaluate(context);
            String key = keyVal.asString();
            XPathValue value;
            if (selectExpr != null) {
                value = selectExpr.evaluate(context);
            } else if (content != null) {
                value = executeContentAsValue(context);
            } else {
                value = XPathMap.EMPTY;
            }
            Map<String, XPathValue> entries = new LinkedHashMap<String, XPathValue>();
            entries.put(key, value);
            XPathMap entryMap = new XPathMap(entries);
            output.atomicValue(entryMap);
        } catch (XPathException e) {
            throw new SAXException("Error in xsl:map-entry", e);
        }
    }

    private XPathValue executeContentAsValue(TransformContext context) throws SAXException {
        org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler builder =
            new org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler();
        if (content.getChildren() != null) {
            for (XSLTNode child : content.getChildren()) {
                child.execute(context, builder);
                builder.markItemBoundary();
            }
        }
        return builder.getSequence();
    }
}
