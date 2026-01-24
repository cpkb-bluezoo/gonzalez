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
 * XSLT stylesheet compilation.
 *
 * <p>This package handles the compilation of XSLT stylesheets from XML
 * input (via SAX events) into executable AST form.
 *
 * <h2>Compiled Stylesheet Components</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler} - SAX handler that compiles stylesheets</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.StylesheetResolver} - resolves xsl:import and xsl:include</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet} - the compiled stylesheet</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.TemplateRule} - template rules</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.Pattern} - XSLT patterns</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.AttributeValueTemplate} - AVTs</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.OutputProperties} - output settings</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.compiler.KeyDefinition} - key definitions</li>
 * </ul>
 *
 * <h2>External Stylesheet Loading</h2>
 * <p>The {@link org.bluezoo.gonzalez.transform.compiler.StylesheetResolver} handles
 * resolution of external stylesheets referenced by {@code xsl:import} and
 * {@code xsl:include}. It integrates with the JAXP {@link javax.xml.transform.URIResolver}
 * mechanism for custom resolution strategies.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.compiler;
