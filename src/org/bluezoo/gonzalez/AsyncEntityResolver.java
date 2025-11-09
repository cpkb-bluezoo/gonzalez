/*
 * AsyncEntityResolver.java
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
 * Resolves external entities in a non-blocking, data-driven manner.
 *
 * <p>Unlike SAX's {@code EntityResolver} which returns an {@code InputSource}
 * synchronously and requires blocking I/O, this resolver is invoked with a
 * receiver callback that accepts entity content as it arrives. This design
 * allows entity resolution to be fully non-blocking and compatible with
 * asynchronous I/O mechanisms such as the Gumdrop HTTP client.
 *
 * <p><strong>Usage Pattern:</strong>
 * <ol>
 * <li>Parser encounters an external entity reference (e.g., in DOCTYPE or
 *     entity expansion)</li>
 * <li>Parser creates an {@link EntityReceiver} that feeds into a nested
 *     parse context</li>
 * <li>Parser calls {@link #resolveEntity} with the entity identifiers and
 *     the receiver</li>
 * <li>Resolver initiates entity fetch (HTTP request, file read, etc.)</li>
 * <li>As entity content arrives, resolver calls {@code receiver.receive(data)}</li>
 * <li>When entity is complete, resolver calls {@code receiver.close()}</li>
 * <li>Parser resumes processing the original document context</li>
 * </ol>
 *
 * <p><strong>Example Implementation (HTTP):</strong>
 * <pre>{@code
 * public void resolveEntity(String publicId, String systemId,
 *                           EntityReceiver receiver) throws SAXException {
 *   HTTPClientStream stream = httpClient.request("GET", systemId,
 *     new HTTPClientHandler() {
 *       public void onStreamData(HTTPClientStream s, ByteBuffer data,
 *                                boolean endStream) {
 *         try {
 *           receiver.receive(data);
 *           if (endStream) {
 *             receiver.close();
 *           }
 *         } catch (SAXException e) {
 *           // Handle parse error
 *         }
 *       }
 *       // ... other handler methods ...
 *     });
 * }
 * }</pre>
 *
 * <p>Implementations must ensure that {@code receiver.close()} is called
 * exactly once, even in error cases, to prevent the parser from hanging
 * while waiting for entity content.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see EntityReceiver
 * @see org.xml.sax.EntityResolver
 */
public interface AsyncEntityResolver {

  /**
   * Resolve an external entity reference.
   *
   * <p>The implementation should determine how to fetch the entity content
   * (via HTTP, filesystem, cache, etc.) and initiate the fetch operation.
   * As entity content arrives, it should be fed to the receiver via
   * {@link EntityReceiver#receive(ByteBuffer)}. When all content has been
   * provided, {@link EntityReceiver#close()} must be called.
   *
   * <p>The parser suspends processing of the current context until the
   * receiver's {@code close()} method is called. This creates a nested
   * parse context for the entity content.
   *
   * <p>If entity resolution fails (e.g., network error, file not found),
   * the implementation may:
   * <ul>
   * <li>Throw a SAXException immediately (strict mode)</li>
   * <li>Call {@code receiver.close()} without providing any content and
   *     let the parser handle the empty entity</li>
   * <li>Provide fallback or placeholder content to the receiver</li>
   * </ul>
   *
   * @param publicId the public identifier of the entity, or null if none
   * @param systemId the system identifier (URI) of the entity
   * @param receiver callback to feed entity content to
   * @throws SAXException if the entity cannot be resolved or an immediate
   *                      error occurs
   */
  void resolveEntity(String publicId, String systemId, EntityReceiver receiver) throws SAXException;

}
