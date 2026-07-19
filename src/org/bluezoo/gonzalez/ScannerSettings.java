/*
 * ScannerSettings.java
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

package org.bluezoo.gonzalez;

/**
 * Immutable snapshot of the security/entity configuration a {@link Scanner}
 * is constructed with. Built by {@code Parser.ensureScannerReady()} from the
 * feature/property values accumulated on the {@code Parser} before parsing
 * starts; {@link Scanner}'s convenience constructors use {@link #PERMISSIVE}
 * instead, preserving the fetch-everything behaviour existing unit tests
 * were written against.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ScannerSettings {

    /**
     * Default entity expansion limit for a {@code Parser}-built Scanner
     * (matches the JDK's own default {@code entityExpansionLimit}).
     */
    static final int DEFAULT_EXPANSION_LIMIT = 64000;

    /**
     * Permissive defaults for direct/test construction: external entity
     * fetching enabled, all protocols allowed for external DTD access, and
     * an effectively unlimited entity expansion budget.
     */
    static final ScannerSettings PERMISSIVE =
            new ScannerSettings(true, true, false, true, "all", Integer.MAX_VALUE);

    /** Whether external general entities may be fetched and included. */
    final boolean externalGeneralEntities;

    /** Whether external parameter entities (and the external DTD subset)
     *  may be fetched and included. */
    final boolean externalParameterEntities;

    /** Whether any DOCTYPE declaration is rejected outright (defense
     *  against XXE and entity-expansion attacks). */
    final boolean disallowDoctypeDecl;

    /** Whether system identifiers reported to DTD-related events are
     *  resolved against the base URI before reporting. */
    final boolean resolveDTDURIs;

    /** Allowed protocols for external DTD/entity access, in the JAXP
     *  {@code accessExternalDTD} format: empty string means none, "all"
     *  means every protocol. */
    final String accessExternalDTD;

    /** Maximum number of entity expansions permitted in one document. */
    final int entityExpansionLimit;

    ScannerSettings(boolean externalGeneralEntities, boolean externalParameterEntities,
            boolean disallowDoctypeDecl, boolean resolveDTDURIs, String accessExternalDTD,
            int entityExpansionLimit) {
        this.externalGeneralEntities = externalGeneralEntities;
        this.externalParameterEntities = externalParameterEntities;
        this.disallowDoctypeDecl = disallowDoctypeDecl;
        this.resolveDTDURIs = resolveDTDURIs;
        this.accessExternalDTD = (accessExternalDTD != null) ? accessExternalDTD : "";
        this.entityExpansionLimit = entityExpansionLimit;
    }

}
