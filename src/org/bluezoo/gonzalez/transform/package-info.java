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
 * XSLT 1.0 transformer with streaming optimization.
 *
 * <p>This package provides a complete XSLT 1.0 implementation that operates
 * natively on SAX event streams. The transformer can compile XSLT stylesheets
 * from SAX events (using Gonzalez itself) and transforms input SAX events
 * directly to output SAX events.
 *
 * <h2>Design Philosophy</h2>
 * <p>The transformer is designed to stream where possible and buffer only
 * when necessary. Pure XSLT streaming is impossible due to features like
 * reverse axes and sorting, but many common transformations can execute
 * in constant memory.
 *
 * <h2>Main Entry Points</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.GonzalezTransformerFactory} - JAXP TransformerFactory implementation</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.GonzalezTemplates} - compiled stylesheet (thread-safe, reusable)</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.GonzalezTransformer} - JAXP Transformer for single use</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.GonzalezTransformerHandler} - SAX TransformerHandler</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.GonzalezTemplatesHandler} - SAX TemplatesHandler</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.TransformerXMLFilter} - XMLFilter for pipelines</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * TransformerFactory factory = new GonzalezTransformerFactory();
 * Transformer transformer = factory.newTransformer(new StreamSource("style.xsl"));
 * transformer.transform(new StreamSource("input.xml"), new StreamResult("output.xml"));
 * </pre>
 *
 * <h2>Sub-packages</h2>
 * <ul>
 *   <li>{@code xpath} - XPath 1.0 expression engine</li>
 *   <li>{@code compiler} - stylesheet compilation</li>
 *   <li>{@code ast} - XSLT instruction AST nodes</li>
 *   <li>{@code runtime} - transformation execution engine</li>
 *   <li>{@code extension} - extension mechanism</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform;
