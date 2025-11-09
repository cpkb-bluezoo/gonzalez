/*
 * AsyncEntityResolverFactory.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import org.xml.sax.SAXException;

/**
 * Factory for creating entity resolvers on demand as external entities are
 * encountered during parsing.
 *
 * <p>The parser discovers entity references during processing and needs to
 * resolve them. Since entities can reference arbitrary URLs with different
 * hosts and ports, the parser cannot maintain a single resolver or HTTP
 * client. Instead, it calls back to the application via this factory interface
 * to request an appropriate resolver for each entity.
 *
 * <p>This design allows the application to:
 * <ul>
 * <li>Control which entities to resolve and which to reject</li>
 * <li>Create HTTP clients with appropriate host/port/TLS settings per URL</li>
 * <li>Implement caching or alternate resolution mechanisms</li>
 * <li>Apply security policies (e.g., disallow external entities)</li>
 * <li>Use different resolution strategies for different URL schemes</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * parser.setEntityResolverFactory(new AsyncEntityResolverFactory() {
 *   public AsyncEntityResolver createResolver(String publicId, String systemId) {
 *     // Security: only resolve entities from trusted hosts
 *     if (!systemId.startsWith("https://trusted.example.com/")) {
 *       return null; // Reject, treat as empty
 *     }
 *
 *     // Parse URL and create appropriate HTTP client
 *     URL url = new URL(systemId);
 *     HTTPClient client = new HTTPClient(url.getHost(), url.getPort(),
 *                                        "https".equals(url.getProtocol()));
 *     return new HTTPEntityResolver(client, systemId);
 *   }
 * });
 * }</pre>
 *
 * <p><strong>Returning null:</strong> If the factory returns null, the parser
 * treats the entity as having empty content and continues processing. This
 * allows applications to implement security policies that reject external
 * entities without causing parse errors.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see AsyncEntityResolver
 */
public interface AsyncEntityResolverFactory {

  /**
   * Creates an entity resolver for the specified entity reference.
   *
   * <p>This method is called by the parser when it encounters an external
   * entity reference. The implementation should examine the public and system
   * identifiers and decide whether to resolve the entity. If resolution should
   * proceed, return an appropriate AsyncEntityResolver. If the entity should
   * be rejected or skipped, return null.
   *
   * <p>For HTTP/HTTPS system IDs, the implementation typically needs to:
   * <ol>
   * <li>Parse the URL to extract host, port, and protocol</li>
   * <li>Create or obtain an HTTPClient for that host/port</li>
   * <li>Return an HTTPEntityResolver wrapping that client</li>
   * </ol>
   *
   * <p>The implementation may apply security policies, maintain connection
   * pools, implement caching, or use any other strategy appropriate for the
   * application.
   *
   * @param publicId the public identifier of the entity, or null
   * @param systemId the system identifier (URI) of the entity
   * @return an AsyncEntityResolver to resolve this entity, or null to treat
   *         the entity as empty
   * @throws SAXException if an error occurs creating the resolver
   */
  AsyncEntityResolver createResolver(String publicId, String systemId) throws SAXException;

}
