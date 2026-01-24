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

    /** Output method. */
    public enum Method {
        XML, HTML, TEXT
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

    public OutputProperties() {}

    // Getters and setters

    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        this.method = method;
    }

    public void setMethod(String method) {
        if ("xml".equalsIgnoreCase(method)) {
            this.method = Method.XML;
        } else if ("html".equalsIgnoreCase(method)) {
            this.method = Method.HTML;
        } else if ("text".equalsIgnoreCase(method)) {
            this.method = Method.TEXT;
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public boolean isOmitXmlDeclaration() {
        return omitXmlDeclaration;
    }

    public void setOmitXmlDeclaration(boolean omitXmlDeclaration) {
        this.omitXmlDeclaration = omitXmlDeclaration;
    }

    public boolean isStandalone() {
        return standalone;
    }

    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    public String getDoctypePublic() {
        return doctypePublic;
    }

    public void setDoctypePublic(String doctypePublic) {
        this.doctypePublic = doctypePublic;
    }

    public String getDoctypeSystem() {
        return doctypeSystem;
    }

    public void setDoctypeSystem(String doctypeSystem) {
        this.doctypeSystem = doctypeSystem;
    }

    public Set<String> getCdataSectionElements() {
        return Collections.unmodifiableSet(cdataSectionElements);
    }

    public void addCdataSectionElement(String element) {
        cdataSectionElements.add(element);
    }

    public boolean isIndent() {
        return indent;
    }

    public void setIndent(boolean indent) {
        this.indent = indent;
    }

    public String getMediaType() {
        return mediaType;
    }

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
        if (other.method != null) this.method = other.method;
        if (other.version != null) this.version = other.version;
        if (other.encoding != null) this.encoding = other.encoding;
        this.omitXmlDeclaration = other.omitXmlDeclaration;
        this.standalone = other.standalone;
        if (other.doctypePublic != null) this.doctypePublic = other.doctypePublic;
        if (other.doctypeSystem != null) this.doctypeSystem = other.doctypeSystem;
        this.cdataSectionElements.addAll(other.cdataSectionElements);
        this.indent = other.indent;
        if (other.mediaType != null) this.mediaType = other.mediaType;
    }

}
