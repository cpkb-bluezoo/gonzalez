/*
 * HTTPEntityResolver.java
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

package org.bluezoo.gonzalez.http;

import java.nio.ByteBuffer;
import org.bluezoo.gonzalez.AsyncEntityResolver;
import org.bluezoo.gonzalez.EntityReceiver;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientConnection;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientStream;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Entity resolver that fetches a specific external entity via HTTP/HTTPS.
 *
 * <p>This implementation integrates with the Gumdrop HTTP client to resolve
 * a single external entity asynchronously. Each instance is created for one
 * specific entity URL and maintains its own HTTP client connection to the
 * appropriate host and port.
 *
 * <p>As entity content arrives from the HTTP response, it is fed directly to
 * the parser's {@link EntityReceiver}, enabling fully non-blocking entity
 * resolution.
 *
 * <p><strong>Features:</strong>
 * <ul>
 * <li>HTTP/1.1 and HTTP/2 support (via Gumdrop HTTP client)</li>
 * <li>Streaming entity content (no buffering of entire entity)</li>
 * <li>TLS/SSL support for HTTPS</li>
 * <li>Timeout configuration</li>
 * </ul>
 *
 * <p><strong>Usage Pattern:</strong>
 * This class is typically not instantiated directly by applications. Instead,
 * it is created by an {@link AsyncEntityResolverFactory} when the parser
 * encounters an entity reference:
 *
 * <pre>{@code
 * parser.setEntityResolverFactory((publicId, systemId) -> {
 *   URL url = new URL(systemId);
 *   HTTPClient client = new HTTPClient(url.getHost(),
 *                                      url.getPort() > 0 ? url.getPort() : 80,
 *                                      "https".equals(url.getProtocol()));
 *   return new HTTPEntityResolver(client, systemId);
 * });
 * }</pre>
 *
 * <p><strong>Security Considerations:</strong>
 * The AsyncEntityResolverFactory should validate system IDs before creating
 * resolvers to prevent XXE attacks and enforce security policies.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see AsyncEntityResolver
 * @see AsyncEntityResolverFactory
 * @see org.bluezoo.gumdrop.http.client.HTTPClient
 */
public class HTTPEntityResolver implements AsyncEntityResolver {

  private final HTTPClient httpClient;
  private final String systemId;

  /**
   * Creates a new HTTP entity resolver for a specific entity URL.
   *
   * <p>The HTTP client should be configured with the appropriate host, port,
   * and TLS settings for the entity's URL. This resolver will use that client
   * to fetch the entity content.
   *
   * @param httpClient the HTTP client configured for the entity's host/port
   * @param systemId the full URL of the entity to fetch
   */
  public HTTPEntityResolver(HTTPClient httpClient, String systemId) {
    if (httpClient == null) {
      throw new IllegalArgumentException("HTTP client required");
    }
    if (systemId == null || systemId.isEmpty()) {
      throw new IllegalArgumentException("System ID required");
    }

    this.httpClient = httpClient;
    this.systemId = systemId;
  }

  @Override
  public void resolveEntity(String publicId, String ignoredSystemId,
      EntityReceiver receiver) throws SAXException {
    // Use the systemId from constructor (already validated by factory)
    try {
      // Create handler that feeds entity content to receiver
      EntityHandler handler = new EntityHandler(receiver);

      // Connect to server and initiate HTTP request
      httpClient.connect(handler);
    } catch (Exception e) {
      throw new SAXException("Failed to resolve entity: " + systemId, e);
    }
  }

  /**
   * HTTP client handler that feeds entity content to the parser.
   */
  private class EntityHandler implements HTTPClientHandler {
    private final EntityReceiver receiver;
    private HTTPClientConnection connection;
    private boolean receivedResponse;
    private boolean completed;

    EntityHandler(EntityReceiver receiver) {
      this.receiver = receiver;
      this.receivedResponse = false;
      this.completed = false;
    }

    @Override
    public void onConnected() {
      // Connection established
    }

    @Override
    public void onDisconnected() {
      if (!completed) {
        // Connection closed prematurely
        try {
          receiver.close();
        } catch (SAXException e) {
          // Already in error state
        }
      }
    }

    @Override
    public void onTLSStarted() {
      // TLS negotiation complete
    }

    @Override
    public void onError(Exception e) {
      try {
        receiver.close();
      } catch (SAXException ex) {
        // Suppress
      }
    }

    @Override
    public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
      // Protocol negotiated, create stream and send request
      this.connection = conn;
      try {
        HTTPClientStream stream = conn.createStream();
        // Stream creation will trigger onStreamCreated
      } catch (java.io.IOException e) {
        try {
          receiver.close();
        } catch (SAXException ex) {
          // Suppress
        }
      }
    }

    @Override
    public void onStreamCreated(HTTPClientStream stream) {
      // Send GET request for the entity
      try {
        HTTPRequest request = new HTTPRequest("GET", systemId);
        stream.sendRequest(request);
      } catch (java.io.IOException e) {
        try {
          receiver.close();
        } catch (SAXException ex) {
          // Suppress
        }
      }
    }

    @Override
    public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
      receivedResponse = true;

      int status = response.getStatusCode();
      if (status < 200 || status >= 300) {
        // Non-success status
        try {
          receiver.close();
        } catch (SAXException e) {
          // Suppress
        }
      }
    }

    @Override
    public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
      if (!receivedResponse) {
        return; // Wait for response headers
      }

      try {
        // Feed entity content to parser
        receiver.receive(data);

        if (endStream) {
          // Entity complete
          receiver.close();
          completed = true;
        }
      } catch (SAXException e) {
        // Parse error - cancel stream
        if (stream instanceof org.bluezoo.gumdrop.http.client.DefaultHTTPClientStream) {
          ((org.bluezoo.gumdrop.http.client.DefaultHTTPClientStream) stream)
              .cancel(0); // Protocol error
        }
      }
    }

    @Override
    public void onStreamComplete(HTTPClientStream stream) {
      if (!completed) {
        try {
          receiver.close();
          completed = true;
        } catch (SAXException e) {
          // Suppress
        }
      }
    }

    @Override
    public void onStreamError(HTTPClientStream stream, Exception error) {
      try {
        receiver.close();
      } catch (SAXException e) {
        // Suppress
      }
    }

    @Override
    public void onServerSettings(java.util.Map<Integer, Long> settings) {
      // HTTP/2 settings received
    }

    @Override
    public boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest) {
      // Reject server push for entity resolution
      return false;
    }

    @Override
    public void onGoAway(int lastStreamId, int errorCode, String debugData) {
      // Server sent GOAWAY, connection is closing
      try {
        receiver.close();
      } catch (SAXException e) {
        // Suppress
      }
    }
  }

}
