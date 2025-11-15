/*
 * EntityExpansionHelper.java
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Helper class for context-aware entity expansion.
 * 
 * <p>This class provides a unified mechanism for expanding entity references
 * in different contexts (content, attribute values, entity values, DTD).
 * Each context has different rules per XML 1.0 specification section 4.4.
 * 
 * <p>The helper tracks two types of infinite loops:
 * <ol>
 * <li><b>Entity name recursion</b>: Entity A references Entity B which references Entity A
 * <li><b>External ID recursion</b>: Same external resource (publicId/systemId) referenced multiple times
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EntityExpansionHelper {
    
    /** DTD parser for entity lookups */
    private final DTDParser dtdParser;
    
    /** Locator for error reporting */
    private final Locator locator;
    
    /** 
     * Set of entity names currently being expanded (for circular reference detection).
     * This is passed through the recursion chain.
     */
    private final Set<String> visitedEntityNames;
    
    /** 
     * Set of external IDs currently being resolved (for external entity loop detection).
     * Contains both publicId and systemId strings from ExternalID objects.
     * This is passed through the external resolution chain.
     */
    private final Set<String> visitedExternalIDs;
    
    /**
     * Creates an entity expansion helper.
     * 
     * @param dtdParser the DTD parser for entity lookups (may be null if no DTD)
     * @param locator the locator for error reporting
     */
    public EntityExpansionHelper(DTDParser dtdParser, Locator locator) {
        this.dtdParser = dtdParser;
        this.locator = locator;
        this.visitedEntityNames = new HashSet<>();
        this.visitedExternalIDs = new HashSet<>();
    }
    
    /**
     * Creates a child helper for nested expansion.
     * Inherits the visited sets from parent.
     * 
     * @param parent the parent helper
     */
    private EntityExpansionHelper(EntityExpansionHelper parent) {
        this.dtdParser = parent.dtdParser;
        this.locator = parent.locator;
        this.visitedEntityNames = new HashSet<>(parent.visitedEntityNames);
        this.visitedExternalIDs = new HashSet<>(parent.visitedExternalIDs);
    }
    
    /**
     * Expands a general entity reference in the given context.
     * 
     * @param entityName the entity name (without &amp; and ;)
     * @param context the expansion context
     * @return the expanded value (for internal entities) or null (for external entities requiring async resolution)
     * @throws SAXException if expansion fails or entity reference is invalid in context
     */
    public String expandGeneralEntity(String entityName, EntityExpansionContext context) 
            throws SAXException {
        // Check if we have a DTD
        if (dtdParser == null) {
            throw new SAXParseException(
                "General entity reference '&" + entityName + ";' used but no DTD present",
                locator);
        }
        
        // Look up the entity declaration
        EntityDeclaration entity = dtdParser.getGeneralEntity(entityName);
        if (entity == null) {
            throw new SAXParseException(
                "General entity reference '&" + entityName + ";' used but entity not declared",
                locator);
        }
        
        // Check circular reference
        if (visitedEntityNames.contains(entityName)) {
            throw new SAXParseException(
                "Circular entity reference detected: &" + entityName + ";",
                locator);
        }
        
        // Context-specific validation
        switch (context) {
            case ATTRIBUTE_VALUE:
                // External entities forbidden in attribute values
                if (entity.isExternal()) {
                    throw new SAXParseException(
                        "External entity reference '&" + entityName + ";' is forbidden in attribute values",
                        locator);
                }
                // Unparsed entities forbidden in attribute values
                if (entity.isUnparsed()) {
                    throw new SAXParseException(
                        "Unparsed entity reference '&" + entityName + ";' is forbidden in attribute values",
                        locator);
                }
                break;
                
            case CONTENT:
                // Unparsed entities forbidden in content
                if (entity.isUnparsed()) {
                    throw new SAXParseException(
                        "Unparsed entity reference '&" + entityName + ";' is forbidden in content",
                        locator);
                }
                // External entities allowed but require async resolution
                if (entity.isExternal()) {
                    // Check external ID loop
                    String externalKey = entity.externalID.toString();
                    if (visitedExternalIDs.contains(externalKey)) {
                        throw new SAXParseException(
                            "Circular external entity reference detected: " + externalKey,
                            locator);
                    }
                    // Return null to signal async resolution needed
                    return null;
                }
                break;
                
            case ENTITY_VALUE:
                // In entity values, external entities are forbidden
                if (entity.isExternal()) {
                    throw new SAXParseException(
                        "External entity reference in entity value is forbidden",
                        locator);
                }
                break;
                
            default:
                throw new SAXParseException(
                    "General entity references not allowed in context: " + context,
                    locator);
        }
        
        // Expand internal entity
        EntityExpansionHelper childHelper = new EntityExpansionHelper(this);
        childHelper.visitedEntityNames.add(entityName);
        return childHelper.expandEntityValue(entity.replacementText, context);
    }
    
    /**
     * Recursively expands an entity value.
     * 
     * @param replacementText the entity value as list of String and GeneralEntityReference
     * @param context the expansion context
     * @return the fully expanded value
     * @throws SAXException if expansion fails
     */
    private String expandEntityValue(List<Object> replacementText, EntityExpansionContext context) 
            throws SAXException {
        if (replacementText == null || replacementText.isEmpty()) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        
        for (Object part : replacementText) {
            if (part instanceof String) {
                // Literal text
                result.append((String) part);
            } else if (part instanceof GeneralEntityReference) {
                // Nested entity reference
                GeneralEntityReference ref = (GeneralEntityReference) part;
                String expanded = expandGeneralEntity(ref.name, context);
                if (expanded == null) {
                    // External entity requiring async resolution
                    throw new SAXParseException(
                        "External entity reference in entity value requires async resolution",
                        locator);
                }
                result.append(expanded);
            } else {
                throw new SAXParseException(
                    "Unexpected entity value part type: " + part.getClass().getName(),
                    locator);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Marks an external ID as visited (for loop detection).
     * Call this when beginning to resolve an external entity.
     * 
     * @param externalID the external ID being resolved
     * @throws SAXException if this external ID has already been visited (circular reference)
     */
    public void markExternalIDVisited(ExternalID externalID) throws SAXException {
        String key = externalID.toString();
        if (visitedExternalIDs.contains(key)) {
            throw new SAXParseException(
                "Circular external entity reference detected: " + key,
                locator);
        }
        visitedExternalIDs.add(key);
    }
}

