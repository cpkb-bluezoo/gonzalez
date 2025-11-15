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
 * Represents a reference to a parameter entity that needs to be expanded later.
 * Used in entity values and DTD declarations where parameter entity references
 * can refer to entities that haven't been declared yet in the DTD.
 * 
 * <p>Parameter entities are used in DTD markup declarations and can only appear
 * in the internal or external DTD subset. They are referenced using {@code %name;}
 * syntax.
 * 
 * <p>Example:
 * <pre>
 * &lt;!ENTITY % common "CDATA"&gt;
 * &lt;!ENTITY % combined "ID | %common;"&gt;
 * </pre>
 * 
 * <p>The entity value for "combined" would be stored as:
 * <pre>
 * ["ID | ", ParameterEntityReference("common")]
 * </pre>
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class ParameterEntityReference {
    
    /** The name of the parameter entity being referenced */
    public final String name;
    
    /**
     * Creates a parameter entity reference.
     * 
     * @param name the parameter entity name (without % and ;)
     */
    public ParameterEntityReference(String name) {
        this.name = name;
    }
    
    @Override
    public String toString() {
        return "%" + name + ";";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ParameterEntityReference)) return false;
        ParameterEntityReference other = (ParameterEntityReference) obj;
        return name.equals(other.name);
    }
    
    @Override
    public int hashCode() {
        return name.hashCode();
    }
}

