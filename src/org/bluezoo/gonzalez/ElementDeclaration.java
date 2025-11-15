/*
 * ElementDeclaration.java
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

/**
 * Represents an element declaration from the DTD.
 *
 * <p>Element declarations define the content model for an element, specifying
 * what child elements and character data are allowed.
 *
 * <p>Content models can be:
 * <ul>
 *   <li>EMPTY - no content allowed</li>
 *   <li>ANY - any content allowed</li>
 *   <li>Mixed content - (#PCDATA | child1 | child2)*</li>
 *   <li>Element content - structured content model with sequences and choices</li>
 * </ul>
 *
 * <p>This class stores the content model as a parsed structure for efficient
 * validation. For simple cases (EMPTY, ANY), only the type is stored.
 * For complex content models, a tree structure represents the model.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ElementDeclaration {

    /**
     * Content model types.
     */
    public enum ContentType {
        EMPTY,      // <!ELEMENT name EMPTY>
        ANY,        // <!ELEMENT name ANY>
        MIXED,      // <!ELEMENT name (#PCDATA|child)*>
        ELEMENT     // <!ELEMENT name (child1, child2)>
    }

    /**
     * The element name.
     */
    public final String name;

    /**
     * The content type.
     */
    public final ContentType contentType;

    /**
     * The content model root node (null for EMPTY and ANY).
     * For MIXED and ELEMENT content, this is the root of the content model tree.
     */
    public final ContentModel contentModel;

    /**
     * Creates an element declaration with simple content (EMPTY or ANY).
     *
     * @param name the element name
     * @param contentType the content type (EMPTY or ANY)
     */
    public ElementDeclaration(String name, ContentType contentType) {
        this.name = name;
        this.contentType = contentType;
        this.contentModel = null;
    }

    /**
     * Creates an element declaration with a content model.
     *
     * @param name the element name
     * @param contentType the content type (MIXED or ELEMENT)
     * @param contentModel the content model tree
     */
    public ElementDeclaration(String name, ContentType contentType, ContentModel contentModel) {
        this.name = name;
        this.contentType = contentType;
        this.contentModel = contentModel;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!ELEMENT ").append(name).append(" ");
        switch (contentType) {
            case EMPTY:
                sb.append("EMPTY");
                break;
            case ANY:
                sb.append("ANY");
                break;
            case MIXED:
            case ELEMENT:
                sb.append(contentModel);
                break;
        }
        sb.append(">");
        return sb.toString();
    }

    /**
     * Represents a node in the content model tree.
     *
     * <p>Content models are represented as trees where:
     * <ul>
     *   <li>Leaf nodes represent element names or #PCDATA</li>
     *   <li>Internal nodes represent sequences (,), choices (|), or groups</li>
     *   <li>Occurrence indicators (?, *, +) are properties of nodes</li>
     * </ul>
     */
    public static class ContentModel {

        /**
         * Node types in the content model.
         */
        public enum NodeType {
            PCDATA,     // #PCDATA
            ELEMENT,    // Element name
            SEQUENCE,   // (a, b, c)
            CHOICE      // (a | b | c)
        }

        /**
         * Occurrence indicators.
         */
        public enum Occurrence {
            ONCE,       // No indicator
            OPTIONAL,   // ?
            ZERO_OR_MORE, // *
            ONE_OR_MORE  // +
        }

        public final NodeType type;
        public final String elementName;  // For ELEMENT type
        public final ContentModel[] children; // For SEQUENCE and CHOICE
        public final Occurrence occurrence;

        /**
         * Creates a leaf node (#PCDATA or element name).
         */
        public ContentModel(NodeType type, String elementName, Occurrence occurrence) {
            this.type = type;
            this.elementName = elementName;
            this.children = null;
            this.occurrence = occurrence;
        }

        /**
         * Creates an internal node (sequence or choice).
         */
        public ContentModel(NodeType type, ContentModel[] children, Occurrence occurrence) {
            this.type = type;
            this.elementName = null;
            this.children = children;
            this.occurrence = occurrence;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            switch (type) {
                case PCDATA:
                    sb.append("#PCDATA");
                    break;
                case ELEMENT:
                    sb.append(elementName);
                    break;
                case SEQUENCE:
                case CHOICE:
                    sb.append("(");
                    for (int i = 0; i < children.length; i++) {
                        if (i > 0) {
                            sb.append(type == NodeType.SEQUENCE ? ", " : " | ");
                        }
                        sb.append(children[i]);
                    }
                    sb.append(")");
                    break;
            }
            // Add occurrence indicator
            switch (occurrence) {
                case OPTIONAL: sb.append("?"); break;
                case ZERO_OR_MORE: sb.append("*"); break;
                case ONE_OR_MORE: sb.append("+"); break;
                default: break;
            }
            return sb.toString();
        }
    }
}

