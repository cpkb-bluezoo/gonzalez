/*
 * XSLTInstruction.java
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

/**
 * Base class for XSLT instruction elements.
 *
 * <p>XSLT instructions are elements in the XSLT namespace that perform
 * specific transformation operations. Examples include:
 * <ul>
 *   <li>{@code xsl:value-of} - outputs a string value</li>
 *   <li>{@code xsl:apply-templates} - processes child nodes</li>
 *   <li>{@code xsl:if} - conditional processing</li>
 *   <li>{@code xsl:for-each} - iterates over a node-set</li>
 * </ul>
 *
 * <p>This abstract class provides common functionality for all instructions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public abstract class XSLTInstruction implements XSLTNode {

    /** XSLT namespace URI. */
    public static final String XSLT_NAMESPACE = "http://www.w3.org/1999/XSL/Transform";

    /** Line number in stylesheet source (for error reporting). */
    protected int lineNumber = -1;

    /** Column number in stylesheet source (for error reporting). */
    protected int columnNumber = -1;

    /** System ID of stylesheet source (for error reporting). */
    protected String systemId;

    /** Static base URI for this instruction (computed from xml:base inheritance). */
    protected String staticBaseURI;

    /**
     * Returns the instruction name (e.g., "value-of", "apply-templates").
     *
     * @return the local name of the instruction
     */
    public abstract String getInstructionName();

    /**
     * Sets the source location for error reporting.
     *
     * @param systemId the system ID
     * @param lineNumber the line number
     * @param columnNumber the column number
     */
    public void setSourceLocation(String systemId, int lineNumber, int columnNumber) {
        this.systemId = systemId;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    /**
     * Sets the static base URI for this instruction.
     *
     * <p>The static base URI is computed from xml:base attribute inheritance
     * during stylesheet compilation and is used by the static-base-uri() function.
     *
     * @param baseURI the static base URI
     */
    public void setStaticBaseURI(String baseURI) {
        this.staticBaseURI = baseURI;
    }

    /**
     * Returns the static base URI for this instruction.
     *
     * @return the static base URI, or null if not set
     */
    public String getStaticBaseURI() {
        return staticBaseURI;
    }

    /**
     * Returns the system ID of the stylesheet source.
     *
     * @return the system ID, or null
     */
    public String getSystemId() {
        return systemId;
    }

    /**
     * Returns the line number in the stylesheet source.
     *
     * @return the line number, or -1 if unknown
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Returns the column number in the stylesheet source.
     *
     * @return the column number, or -1 if unknown
     */
    public int getColumnNumber() {
        return columnNumber;
    }

    @Override
    public String toString() {
        return "xsl:" + getInstructionName();
    }

}
