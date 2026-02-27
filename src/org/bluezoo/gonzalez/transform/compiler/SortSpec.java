/*
 * SortSpec.java
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

package org.bluezoo.gonzalez.transform.compiler;

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathExpression;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;

/**
 * Sort specification for xsl:sort instructions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SortSpec {
    private final XPathExpression selectExpr;
    private final AttributeValueTemplate dataTypeAvt;
    private final AttributeValueTemplate orderAvt;
    private final AttributeValueTemplate caseOrderAvt;
    private final AttributeValueTemplate langAvt;
    private final AttributeValueTemplate collationAvt;
    
    public SortSpec(XPathExpression selectExpr, AttributeValueTemplate dataTypeAvt, 
                   AttributeValueTemplate orderAvt, AttributeValueTemplate caseOrderAvt, 
                   AttributeValueTemplate langAvt, AttributeValueTemplate collationAvt) {
        this.selectExpr = selectExpr;
        this.dataTypeAvt = dataTypeAvt;
        this.orderAvt = orderAvt;
        this.caseOrderAvt = caseOrderAvt;
        this.langAvt = langAvt;
        this.collationAvt = collationAvt;
    }
    
    public XPathExpression getSelectExpr() { return selectExpr; }
    
    /** Evaluate data-type AVT at runtime. Returns "text" or "number". */
    public String getDataType(TransformContext context) throws XPathException {
        if (dataTypeAvt == null) {
            return "text";
        }
        String value = dataTypeAvt.evaluate(context);
        return value != null && !value.isEmpty() ? value : "text";
    }
    
    /** Evaluate order AVT at runtime. Returns "ascending" or "descending". */
    public String getOrder(TransformContext context) throws XPathException {
        if (orderAvt == null) {
            return "ascending";
        }
        String value = orderAvt.evaluate(context);
        return value != null && !value.isEmpty() ? value : "ascending";
    }
    
    /** Evaluate case-order AVT at runtime. Returns "upper-first", "lower-first", or null. */
    public String getCaseOrder(TransformContext context) throws XPathException {
        if (caseOrderAvt == null) {
            return null;
        }
        String value = caseOrderAvt.evaluate(context);
        return value != null && !value.isEmpty() ? value : null;
    }
    
    /** Evaluate lang AVT at runtime. */
    public String getLang(TransformContext context) throws XPathException {
        if (langAvt == null) {
            return null;
        }
        String value = langAvt.evaluate(context);
        return value != null && !value.isEmpty() ? value : null;
    }
    
    /** Evaluate collation AVT at runtime. Returns collation URI or null for default. */
    public String getCollation(TransformContext context) throws XPathException {
        if (collationAvt == null) {
            return null;
        }
        String value = collationAvt.evaluate(context);
        return value != null && !value.isEmpty() ? value : null;
    }
}
