/*
 * OutputProperties.java
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

import java.util.*;

/**
 * Output serialization properties (from xsl:output).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class OutputProperties {

    /**
     * Output serialization method.
     *
     * <p>Determines how the result tree is serialized to output.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public enum Method {
        /** XML output method - produces well-formed XML. */
        XML,
        /** HTML output method - uses HTML-specific serialization rules. */
        HTML,
        /** Text output method - outputs only text content. */
        TEXT
    }

    private Method method = Method.XML;
    private String version = "1.0";
    private String encoding = "UTF-8";
    private boolean omitXmlDeclaration = false;
    private boolean standalone = false;
    private String doctypePublic;
    private String doctypeSystem;
    private Set<String> cdataSectionElements = new HashSet<>();
    private boolean indent = false;
    private String mediaType;

    /**
     * Creates a new OutputProperties with default values.
     */
    public OutputProperties() {}

    /**
     * Returns the output method.
     *
     * @return the output method (XML, HTML, or TEXT)
     */
    public Method getMethod() {
        return method;
    }

    /**
     * Sets the output method.
     *
     * @param method the output method
     */
    public void setMethod(Method method) {
        this.method = method;
    }

    /**
     * Sets the output method from a string value.
     *
     * @param method the method name ("xml", "html", or "text", case-insensitive)
     */
    public void setMethod(String method) {
        if ("xml".equalsIgnoreCase(method)) {
            this.method = Method.XML;
        } else if ("html".equalsIgnoreCase(method)) {
            this.method = Method.HTML;
        } else if ("text".equalsIgnoreCase(method)) {
            this.method = Method.TEXT;
        }
    }

    /**
     * Returns the XML version string.
     *
     * @return the version (e.g., "1.0")
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the XML version string.
     *
     * @param version the version string
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the character encoding.
     *
     * @return the encoding (e.g., "UTF-8")
     */
    public String getEncoding() {
        return encoding;
    }

    /**
     * Sets the character encoding.
     *
     * @param encoding the encoding name
     */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    /**
     * Returns whether the XML declaration should be omitted.
     *
     * @return true if the XML declaration should be omitted
     */
    public boolean isOmitXmlDeclaration() {
        return omitXmlDeclaration;
    }

    /**
     * Sets whether the XML declaration should be omitted.
     *
     * @param omitXmlDeclaration true to omit the XML declaration
     */
    public void setOmitXmlDeclaration(boolean omitXmlDeclaration) {
        this.omitXmlDeclaration = omitXmlDeclaration;
    }

    /**
     * Returns whether the document is standalone.
     *
     * @return true if standalone="yes" should be included
     */
    public boolean isStandalone() {
        return standalone;
    }

    /**
     * Sets whether the document is standalone.
     *
     * @param standalone true for standalone="yes"
     */
    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    /**
     * Returns the public identifier for the DOCTYPE declaration.
     *
     * @return the public identifier, or null
     */
    public String getDoctypePublic() {
        return doctypePublic;
    }

    /**
     * Sets the public identifier for the DOCTYPE declaration.
     *
     * @param doctypePublic the public identifier
     */
    public void setDoctypePublic(String doctypePublic) {
        this.doctypePublic = doctypePublic;
    }

    /**
     * Returns the system identifier for the DOCTYPE declaration.
     *
     * @return the system identifier, or null
     */
    public String getDoctypeSystem() {
        return doctypeSystem;
    }

    /**
     * Sets the system identifier for the DOCTYPE declaration.
     *
     * @param doctypeSystem the system identifier
     */
    public void setDoctypeSystem(String doctypeSystem) {
        this.doctypeSystem = doctypeSystem;
    }

    /**
     * Returns the set of element names that should be output as CDATA sections.
     *
     * @return immutable set of element names
     */
    public Set<String> getCdataSectionElements() {
        return Collections.unmodifiableSet(cdataSectionElements);
    }

    /**
     * Adds an element name to the CDATA section elements list.
     *
     * @param element the element name (in Clark notation {uri}localName)
     */
    public void addCdataSectionElement(String element) {
        cdataSectionElements.add(element);
    }

    /**
     * Returns whether output should be indented.
     *
     * @return true if indentation is enabled
     */
    public boolean isIndent() {
        return indent;
    }

    /**
     * Sets whether output should be indented.
     *
     * @param indent true to enable indentation
     */
    public void setIndent(boolean indent) {
        this.indent = indent;
    }

    /**
     * Returns the media type (MIME type) for the output.
     *
     * @return the media type, or null
     */
    public String getMediaType() {
        return mediaType;
    }

    /**
     * Sets the media type (MIME type) for the output.
     *
     * @param mediaType the media type
     */
    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    /**
     * Merges another OutputProperties into this one.
     * Properties from other override those in this.
     *
     * @param other the properties to merge
     */
    public void merge(OutputProperties other) {
        if (other.method != null) {
            this.method = other.method;
        }
        if (other.version != null) {
            this.version = other.version;
        }
        if (other.encoding != null) {
            this.encoding = other.encoding;
        }
        this.omitXmlDeclaration = other.omitXmlDeclaration;
        this.standalone = other.standalone;
        if (other.doctypePublic != null) {
            this.doctypePublic = other.doctypePublic;
        }
        if (other.doctypeSystem != null) {
            this.doctypeSystem = other.doctypeSystem;
        }
        this.cdataSectionElements.addAll(other.cdataSectionElements);
        this.indent = other.indent;
        if (other.mediaType != null) {
            this.mediaType = other.mediaType;
        }
    }

}
