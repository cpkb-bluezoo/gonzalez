/*
 * ErrorHandlingMode.java
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

package org.bluezoo.gonzalez.transform;

/**
 * Error handling mode for XSLT transformations.
 *
 * <p>Controls how type errors and validation errors are handled during
 * transformation execution.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>STRICT</b> - Fail immediately on type errors (XTTE*) - spec-compliant behavior</li>
 *   <li><b>RECOVER</b> - Log warnings and attempt to continue - useful for testing/debugging</li>
 *   <li><b>SILENT</b> - Ignore type errors entirely - maximum compatibility mode</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public enum ErrorHandlingMode {
    
    /**
     * Strict mode: fail immediately on type errors.
     *
     * <p>This is the spec-compliant behavior. Type errors (XTTE*) cause
     * immediate transformation failure with an exception.
     *
     * <p>Use this mode for:
     * <ul>
     *   <li>Production transformations that must be spec-compliant</li>
     *   <li>Testing XSLT conformance</li>
     *   <li>Development of new stylesheets</li>
     * </ul>
     */
    STRICT("strict"),
    
    /**
     * Recovery mode: log warnings and attempt to continue.
     *
     * <p>Type errors are logged as warnings to stderr, but transformation
     * continues. This allows partial results to be produced even when
     * type constraints are violated.
     *
     * <p>Use this mode for:
     * <ul>
     *   <li>Debugging type errors in complex stylesheets</li>
     *   <li>Running test suites with partial compliance</li>
     *   <li>Migrating stylesheets with type issues</li>
     * </ul>
     */
    RECOVER("recover"),
    
    /**
     * Silent mode: ignore type errors entirely.
     *
     * <p>Type errors are not checked or reported. This provides maximum
     * compatibility with non-schema-aware processors but may produce
     * incorrect results.
     *
     * <p>Use this mode for:
     * <ul>
     *   <li>Legacy stylesheets without type annotations</li>
     *   <li>Maximum performance (skips type checking)</li>
     *   <li>Compatibility with XSLT 1.0 behavior</li>
     * </ul>
     */
    SILENT("silent");
    
    private final String name;
    
    ErrorHandlingMode(String name) {
        this.name = name;
    }
    
    /**
     * Returns the mode name.
     *
     * @return the name (e.g., "strict", "recover", "silent")
     */
    public String getName() {
        return name;
    }
    
    /**
     * Parses a mode name string.
     *
     * @param name the mode name (case-insensitive)
     * @return the error handling mode
     * @throws IllegalArgumentException if the name is not recognized
     */
    public static ErrorHandlingMode parse(String name) {
        if (name == null || name.isEmpty()) {
            return STRICT;  // Default
        }
        
        String normalized = name.trim().toLowerCase();
        switch (normalized) {
            case "strict":
                return STRICT;
            case "recover":
            case "recovery":
                return RECOVER;
            case "silent":
            case "ignore":
                return SILENT;
            default:
                throw new IllegalArgumentException("Unknown error handling mode: " + name);
        }
    }
    
    /**
     * Returns true if type errors should cause transformation failure.
     *
     * @return true for STRICT mode
     */
    public boolean isStrict() {
        return this == STRICT;
    }
    
    /**
     * Returns true if type errors should be reported but not cause failure.
     *
     * @return true for RECOVER mode
     */
    public boolean isRecovery() {
        return this == RECOVER;
    }
    
    /**
     * Returns true if type errors should be silently ignored.
     *
     * @return true for SILENT mode
     */
    public boolean isSilent() {
        return this == SILENT;
    }
    
    @Override
    public String toString() {
        return name;
    }
}
