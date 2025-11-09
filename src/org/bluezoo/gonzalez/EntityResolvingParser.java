/*
 * EntityResolvingParser.java
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

import java.nio.charset.Charset;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * Interface for parsers that can resolve external entities asynchronously.
 * 
 * <p>This interface abstracts the entity resolution mechanism so that both
 * the main XML parser ({@link GonzalezParser}) and the DTD parser can
 * create and manage external entity receivers using the same architecture.
 * 
 * <p>When a parser encounters an external entity reference, it:
 * <ol>
 * <li>Creates an {@link EntityReceiver} via the entity resolver factory</li>
 * <li>Switches to buffering mode - all incoming data is buffered</li>
 * <li>The entity receiver receives and processes entity data asynchronously</li>
 * <li>When entity resolution completes, the receiver calls {@link #onEntityResolutionComplete()}</li>
 * <li>The parser processes buffered data and resumes normal operation</li>
 * </ol>
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public interface EntityResolvingParser {
    
    /**
     * Called when an external entity resolution completes.
     * 
     * <p>This callback is invoked by {@link EntityReceiver#close()} to notify
     * the parent parser that entity data is complete and buffered main document
     * data should be processed.
     * 
     * <p><strong>Thread Safety:</strong> This method may be called from an
     * HTTP client thread, not the main parsing thread. Implementations must
     * properly synchronize access to parser state.
     * 
     * @throws SAXException if processing buffered data fails
     */
    void onEntityResolutionComplete() throws SAXException;
    
    /**
     * Returns the current character encoding being used.
     * 
     * @return the charset, never null
     */
    Charset getCharset();
    
    /**
     * Returns the content handler for SAX events.
     * 
     * @return the content handler, or null if none set
     */
    ContentHandler getContentHandler();
    
    /**
     * Returns the lexical handler for lexical events.
     * 
     * @return the lexical handler, or null if none set
     */
    LexicalHandler getLexicalHandler();
    
    /**
     * Returns the DTD handler for notation and unparsed entity events.
     * 
     * @return the DTD handler, or null if none set
     */
    DTDHandler getDTDHandler();
    
    /**
     * Returns the error handler for error reporting.
     * 
     * @return the error handler, or null if none set
     */
    ErrorHandler getErrorHandler();
    
    /**
     * Returns the entity resolver factory for external entities.
     * 
     * @return the entity resolver factory, or null if none set
     */
    AsyncEntityResolverFactory getEntityResolverFactory();
    
    /**
     * Returns the entity resolution timeout in milliseconds.
     * 
     * @return the timeout in milliseconds, or 0 if disabled
     */
    long getEntityTimeout();
    
    /**
     * Returns the system identifier for error reporting.
     * 
     * @return the system identifier, or null if not set
     */
    String getSystemId();
    
    /**
     * Returns the public identifier for error reporting.
     * 
     * @return the public identifier, or null if not set
     */
    String getPublicId();
}

