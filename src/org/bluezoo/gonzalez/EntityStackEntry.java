/*
 * EntityExpansionContext.java
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
 * Captures the state of entity expansion at a particular nesting level.
 * <p>
 * Used to maintain a stack of entity expansion entries for:
 * - Detecting infinite entity recursion (by name and systemId)
 * - Tracking XML version inheritance across entity boundaries
 * - Validating element nesting across entity boundaries (WFC: Parsed Entity)
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class EntityStackEntry {
    
    /**
     * The entity name (without & or % delimiters).
     * Used for detecting name-based recursion.
     * Null for the document entity (root context).
     */
    final String entityName;
    
    /**
     * The public identifier of the entity (for external entities).
     * Null for internal entities or document entity.
     */
    final String publicId;
    
    /**
     * The system identifier of the entity (for external entities).
     * Used for detecting systemId-based recursion.
     * Null for internal entities or document entity.
     */
    final String systemId;
    
    /**
     * Whether this entity is a parameter entity (vs. general entity).
     * Parameter entities are declared with % and expanded in DTD context.
     * General entities are declared with & and expanded in content context.
     */
    final boolean isParameterEntity;
    
    /**
     * Whether this entity is external (vs. internal).
     * External entities are resolved from URIs and can have text declarations.
     * Internal entities have inline replacement text.
     */
    final boolean isExternal;
    
    /**
     * XML version for this entity context.
     * Inherited from parent entity by default, but can be changed via
     * XML/text declaration parsing (through xmlVersion callback).
     * 
     * Mutable to allow updates when text declarations are parsed.
     */
    boolean isXML11;
    
    /**
     * Element nesting depth when this entity expansion started.
     * Used to validate WFC: Parsed Entity - elements opened within an
     * entity must be closed within that entity.
     */
    final int elementDepth;
    
    /**
     * Creates an entry for the document entity (root level).
     * 
     * @param isXML11 initial XML version (before any declaration is parsed)
     */
    EntityStackEntry(boolean isXML11) {
        this.entityName = null;
        this.publicId = null;
        this.systemId = null;
        this.isParameterEntity = false;
        this.isExternal = false;
        this.isXML11 = isXML11;
        this.elementDepth = 0;
    }
    
    /**
     * Creates an entry for an internal entity expansion.
     * 
     * @param entityName the entity name
     * @param isParameterEntity true for parameter entity, false for general entity
     * @param isXML11 XML version inherited from parent
     * @param elementDepth element nesting depth at expansion point
     */
    EntityStackEntry(String entityName, boolean isParameterEntity, 
                          boolean isXML11, int elementDepth) {
        this.entityName = entityName;
        this.publicId = null;
        this.systemId = null;
        this.isParameterEntity = isParameterEntity;
        this.isExternal = false;
        this.isXML11 = isXML11;
        this.elementDepth = elementDepth;
    }
    
    /**
     * Creates an entry for an external entity expansion.
     * 
     * @param entityName the entity name
     * @param publicId the public identifier (may be null)
     * @param systemId the system identifier
     * @param isParameterEntity true for parameter entity, false for general entity
     * @param isXML11 XML version inherited from parent
     * @param elementDepth element nesting depth at expansion point
     */
    EntityStackEntry(String entityName, String publicId, String systemId,
                          boolean isParameterEntity, boolean isXML11, int elementDepth) {
        this.entityName = entityName;
        this.publicId = publicId;
        this.systemId = systemId;
        this.isParameterEntity = isParameterEntity;
        this.isExternal = true;
        this.isXML11 = isXML11;
        this.elementDepth = elementDepth;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("EntityStackEntry{");
        if (entityName != null) {
            sb.append("name=").append(isParameterEntity ? "%" : "&")
              .append(entityName).append(";");
        } else {
            sb.append("name=<document>");
        }
        if (systemId != null) {
            sb.append(", systemId=").append(systemId);
        }
        sb.append(", ").append(isXML11 ? "XML1.1" : "XML1.0");
        sb.append(", depth=").append(elementDepth);
        sb.append("}");
        return sb.toString();
    }
}
