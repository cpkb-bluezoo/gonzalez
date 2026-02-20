/*
 * TestContentHandler.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.gonzalez.helpers;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Simple content handler for testing that tracks events.
 */
public class TestContentHandler extends DefaultHandler {
    
    private int startElementCount = 0;
    private int endElementCount = 0;
    private int charactersCount = 0;
    private StringBuilder content = new StringBuilder();
    
    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        startElementCount++;
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        endElementCount++;
    }
    
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        charactersCount++;
        content.append(ch, start, length);
    }
    
    public int getStartElementCount() {
        return startElementCount;
    }
    
    public int getEndElementCount() {
        return endElementCount;
    }
    
    public int getCharactersCount() {
        return charactersCount;
    }
    
    public String getContent() {
        return content.toString();
    }
    
    public void reset() {
        startElementCount = 0;
        endElementCount = 0;
        charactersCount = 0;
        content = new StringBuilder();
    }
}

