/*
 * AccumulatorRuleNode.java
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

import org.bluezoo.gonzalez.transform.compiler.Pattern;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.compiler.AccumulatorDefinition;

/**
 * AccumulatorRuleNode XSLT node.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class AccumulatorRuleNode implements XSLTNode {
    private final Pattern pattern;
    private final AccumulatorDefinition.Phase phase;
    private final XPathExpression newValue;
    
    public AccumulatorRuleNode(Pattern pattern, AccumulatorDefinition.Phase phase, 
                       XPathExpression newValue) {
        this.pattern = pattern;
        this.phase = phase;
        this.newValue = newValue;
    }
    
    public AccumulatorDefinition.AccumulatorRule toRule() {
        return new AccumulatorDefinition.AccumulatorRule(pattern, phase, newValue);
    }
    
    @Override
    public void execute(TransformContext context, OutputHandler output) 
            throws SAXException {
        // Accumulator rules are not executed directly
    }
    
    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.FULL;
    }
}
