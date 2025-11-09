/*
 * EntityReceiver.java
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

import java.nio.ByteBuffer;
import org.xml.sax.SAXException;

/**
 * Receives byte data for an external entity in a non-blocking manner.
 *
 * <p>This interface mirrors the parser's data-driven design pattern. When
 * external entity content is available (whether from HTTP, filesystem, or
 * other sources), it is fed incrementally to this receiver, which processes
 * it as part of the overall XML parse operation.
 *
 * <p>The receiver is provided by the parser when resolving an external entity
 * and feeds entity content back into the parser's internal state machine.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see AsyncEntityResolver
 */
public interface EntityReceiver {

  /**
   * Receive entity content data.
   *
   * <p>This method may be called multiple times for a single entity as data
   * arrives incrementally. Each invocation processes the provided bytes as
   * part of the entity content.
   *
   * <p>The buffer's position will be at the start of available data and its
   * limit at the end. The receiver may modify the buffer's position but
   * should not modify its content or other properties.
   *
   * @param data the entity content bytes to process
   * @throws SAXException if parsing the entity content fails
   */
  void receive(ByteBuffer data) throws SAXException;

  /**
   * Signal that no more entity content will be provided.
   *
   * <p>This method must be called exactly once for each entity resolution,
   * after all content has been provided via {@link #receive(ByteBuffer)}.
   * It allows the parser to finalize entity processing and resume parsing
   * of the parent context.
   *
   * <p>If entity resolution fails, implementations should still call this
   * method or throw a SAXException to ensure the parser can handle the
   * error appropriately.
   *
   * @throws SAXException if completing the entity parse fails
   */
  void close() throws SAXException;

}
