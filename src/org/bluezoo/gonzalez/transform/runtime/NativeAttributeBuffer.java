/*
 * NativeAttributeBuffer.java
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

package org.bluezoo.gonzalez.transform.runtime;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.SAXException;

/**
 * Pooled attribute assembly for streamed {@code startAttribute}/
 * {@code attributeValueContent} events.
 */
public final class NativeAttributeBuffer {

    public static final class Attr {
        public String qName;
        public String type;
        public String value;
        public String uri;
        public String localName;
        public String prefix;
    }

    private final List<Attr> pool = new ArrayList<>();
    private int count;
    private Attr current;
    private boolean valueFirstChunk;
    private final StringBuilder valueBuilder = new StringBuilder();

    public void clear() {
        count = 0;
        current = null;
    }

    public int size() {
        return count;
    }

    public Attr get(int index) {
        return pool.get(index);
    }

    public void startAttribute(String name, String type) {
        Attr attr;
        if (count < pool.size()) {
            attr = pool.get(count);
        } else {
            attr = new Attr();
            pool.add(attr);
        }
        attr.qName = name;
        attr.type = type;
        attr.value = null;
        attr.uri = null;
        attr.localName = null;
        attr.prefix = null;
        current = attr;
        valueFirstChunk = true;
        count++;
    }

    public void attributeValueContent(CharBuffer value, boolean end) {
        if (valueFirstChunk && end) {
            current.value = value.toString();
            return;
        }
        if (valueFirstChunk) {
            valueBuilder.setLength(0);
            valueFirstChunk = false;
        }
        valueBuilder.append(value);
        if (end) {
            current.value = valueBuilder.toString();
        }
    }

    public void resolveAndCheckDuplicates(java.util.Map<String, String> bindings)
            throws SAXException {
        for (int i = 0; i < count; i++) {
            Attr attr = pool.get(i);
            if (NativeExpandedNames.isNamespaceDeclaration(attr.qName)) {
                continue;
            }
            attr.prefix = NativeExpandedNames.extractPrefix(attr.qName);
            attr.localName = NativeExpandedNames.extractLocalName(attr.qName);
            attr.uri = NativeExpandedNames.resolveNamespaceURI(
                    attr.prefix, true, bindings);
            for (int j = 0; j < i; j++) {
                Attr other = pool.get(j);
                if (NativeExpandedNames.isNamespaceDeclaration(other.qName)) {
                    continue;
                }
                if (attr.uri.equals(other.uri)
                        && attr.localName.equals(other.localName)) {
                    throw new org.xml.sax.SAXParseException(
                            "Duplicate attribute by expanded name: {"
                                    + attr.uri + "}" + attr.localName,
                            null);
                }
            }
        }
    }
}
