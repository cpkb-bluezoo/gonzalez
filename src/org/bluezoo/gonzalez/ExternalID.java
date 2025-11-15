/*
 * Copyright (c) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package org.bluezoo.gonzalez;

/**
 * Represents an External ID in XML, which can be either:
 * <ul>
 * <li>SYSTEM "systemId"</li>
 * <li>PUBLIC "publicId" "systemId"</li>
 * </ul>
 * 
 * <p>Used in:
 * <ul>
 * <li>DOCTYPE declarations (external DTD subset)</li>
 * <li>NOTATION declarations</li>
 * <li>External ENTITY declarations</li>
 * </ul>
 * 
 * <p>The publicId is optional (null for SYSTEM declarations).
 * The systemId is required for SYSTEM declarations, but may be
 * optional for PUBLIC declarations in some contexts.
 */
public class ExternalID {
    
    /** The public identifier (null if SYSTEM) */
    public String publicId;
    
    /** The system identifier (may be null for PUBLIC-only) */
    public String systemId;
    
    /**
     * Creates an external ID.
     */
    public ExternalID() {
    }
    
    /**
     * Creates an external ID with the specified identifiers.
     * 
     * @param publicId the public identifier (null for SYSTEM)
     * @param systemId the system identifier
     */
    public ExternalID(String publicId, String systemId) {
        this.publicId = publicId;
        this.systemId = systemId;
    }
    
    /**
     * Returns true if this is a SYSTEM external ID (no public ID).
     * 
     * @return true if this is a SYSTEM external ID
     */
    public boolean isSystem() {
        return publicId == null;
    }
    
    /**
     * Returns true if this is a PUBLIC external ID (has public ID).
     * 
     * @return true if this is a PUBLIC external ID
     */
    public boolean isPublic() {
        return publicId != null;
    }
    
    @Override
    public String toString() {
        if (publicId != null) {
            return "PUBLIC \"" + publicId + "\" \"" + systemId + "\"";
        } else {
            return "SYSTEM \"" + systemId + "\"";
        }
    }
}

