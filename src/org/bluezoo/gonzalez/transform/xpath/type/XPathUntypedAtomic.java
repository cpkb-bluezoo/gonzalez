/*
 * XPathUntypedAtomic.java
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

package org.bluezoo.gonzalez.transform.xpath.type;

/**
 * XPath xs:untypedAtomic value.
 *
 * <p>Represents values of type xs:untypedAtomic, which is distinct from
 * xs:string in XPath 2.0+. This distinction matters for {@code instance of}
 * checks: an untypedAtomic value is NOT an instance of xs:string, and
 * a string value is NOT an instance of xs:untypedAtomic.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XPathUntypedAtomic extends XPathString {

    /**
     * Creates a new xs:untypedAtomic value.
     *
     * @param value the string value (must not be null)
     */
    public XPathUntypedAtomic(String value) {
        super(value);
    }

    /**
     * Returns an xs:untypedAtomic value for the given Java string.
     *
     * @param value the string value
     * @return the xs:untypedAtomic value
     */
    public static XPathUntypedAtomic ofUntyped(String value) {
        if (value == null) {
            return new XPathUntypedAtomic("");
        }
        return new XPathUntypedAtomic(value);
    }

    @Override
    public String toString() {
        return "XPathUntypedAtomic[\"" + getValue() + "\"]";
    }
}
