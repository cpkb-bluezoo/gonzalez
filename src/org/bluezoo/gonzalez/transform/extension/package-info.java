/*
 * package-info.java
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

/**
 * XSLT extension mechanism.
 *
 * <p>This package provides interfaces for extending XSLT functionality:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.extension.ExtensionFunction} - custom XPath functions</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.extension.ExtensionElement} - custom XSLT elements</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.extension.ExtensionRegistry} - extension registration</li>
 * </ul>
 *
 * <h2>Creating Extension Functions</h2>
 * <pre>
 * public class MyFunction implements ExtensionFunction {
 *     public String getNamespaceURI() { return "http://example.com/ext"; }
 *     public String getLocalName() { return "my-func"; }
 *     public int getMinArgs() { return 1; }
 *     public int getMaxArgs() { return 1; }
 *     
 *     public XPathValue invoke(List&lt;XPathValue&gt; args, TransformContext ctx) {
 *         return XPathString.of(args.get(0).asString().toUpperCase());
 *     }
 * }
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.extension;
