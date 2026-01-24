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
 * XPath axis implementations.
 *
 * <p>This package provides implementations for all 13 XPath 1.0 axes:
 *
 * <h2>Forward Axes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.ChildAxis} - children of the context node</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.DescendantAxis} - all descendants</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.DescendantOrSelfAxis} - self and descendants</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.FollowingAxis} - all following nodes (not descendants)</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.FollowingSiblingAxis} - following siblings</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.AttributeAxis} - attributes of the context node</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.NamespaceAxis} - namespace nodes</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.SelfAxis} - just the context node</li>
 * </ul>
 *
 * <h2>Reverse Axes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.ParentAxis} - parent of the context node</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.AncestorAxis} - all ancestors</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.AncestorOrSelfAxis} - self and ancestors</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.PrecedingAxis} - all preceding nodes (not ancestors)</li>
 *   <li>{@link org.bluezoo.gonzalez.transform.xpath.axis.PrecedingSiblingAxis} - preceding siblings</li>
 * </ul>
 *
 * <h2>Streaming Support</h2>
 * <p>Each axis reports whether it can be evaluated in streaming mode via
 * {@link org.bluezoo.gonzalez.transform.xpath.axis.Axis#supportsStreaming()}.
 * Forward axes generally support streaming, while reverse axes like
 * preceding and preceding-sibling require buffering.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
package org.bluezoo.gonzalez.transform.xpath.axis;
