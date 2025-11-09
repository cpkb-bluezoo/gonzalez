/*
 * TestEntityResolverFactory.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez.helpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.bluezoo.gonzalez.AsyncEntityResolver;
import org.bluezoo.gonzalez.AsyncEntityResolverFactory;
import org.bluezoo.gonzalez.EntityReceiver;
import org.xml.sax.SAXException;

/**
 * Entity resolver factory for testing that resolves entities from local files.
 */
public class TestEntityResolverFactory implements AsyncEntityResolverFactory {
    
    private final File xmlconfDir;
    
    public TestEntityResolverFactory(File xmlconfDir) {
        this.xmlconfDir = xmlconfDir;
    }
    
    @Override
    public AsyncEntityResolver createResolver(String publicId, String systemId) throws SAXException {
        if (systemId == null) {
            return null; // Can't resolve without systemId
        }
        
        // Convert systemId to file path relative to xmlconf directory
        File entityFile = resolveFile(systemId);
        
        if (entityFile == null || !entityFile.exists() || !entityFile.canRead()) {
            // Entity file not found or not readable
            return null;
        }
        
        return new FileEntityResolver(entityFile);
    }
    
    private File resolveFile(String systemId) {
        // Remove file:// prefix if present
        String path = systemId;
        if (path.startsWith("file://")) {
            path = path.substring(7);
        } else if (path.startsWith("file:")) {
            path = path.substring(5);
        }
        
        // If absolute path, use as-is
        if (path.startsWith("/")) {
            return new File(path);
        }
        
        // Otherwise resolve relative to xmlconf directory
        return new File(xmlconfDir, path);
    }
    
    /**
     * Simple file-based entity resolver.
     */
    private static class FileEntityResolver implements AsyncEntityResolver {
        
        private final File file;
        
        FileEntityResolver(File file) {
            this.file = file;
        }
        
        @Override
        public void resolveEntity(String publicId, String systemId, EntityReceiver receiver) 
                throws SAXException {
            try (FileInputStream fis = new FileInputStream(file);
                 FileChannel channel = fis.getChannel()) {
                
                // Read file in chunks and feed to receiver
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) >= 0) {
                    buffer.flip();
                    if (buffer.hasRemaining()) {
                        receiver.receive(buffer);
                    }
                    buffer.clear();
                }
                
                // Signal completion
                receiver.close();
                
            } catch (IOException e) {
                throw new SAXException("Failed to read entity file: " + file, e);
            }
        }
    }
}

