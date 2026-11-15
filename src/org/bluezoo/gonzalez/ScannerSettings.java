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
 * Immutable snapshot of the security/entity configuration and the limits
 * a {@link Scanner} is constructed with. Built by {@link XMLParser} from
 * its settings when a document starts; {@link Scanner}'s convenience
 * constructors use {@link #PERMISSIVE} instead, preserving the
 * fetch-everything, unlimited behaviour existing unit tests were written
 * against.
 *
 * <p>A limit of zero or less means no limit.
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
     * Default limit on how deeply elements may nest. The stack of open
     * element names is the one structure well-formedness checking cannot
     * do without, so this is what bounds it. The JDK had no such limit
     * until it adopted 100 in JDK 24; 1000 is the default of the sister
     * JSON parser, and far beyond any document written by hand.
     */
    static final int DEFAULT_MAX_ELEMENT_DEPTH = 1000;

    /**
     * Default limit on the length of a name. The JDK's
     * {@code maxXMLNameLimit} is 1000, but two valid documents of the W3C
     * conformance suite have a processing instruction target longer than
     * that (3,381 characters, in ibm85v01.xml), so the default here is
     * that of the sister JSON parser, which admits them.
     */
    static final int DEFAULT_MAX_NAME_LENGTH = 4096;

    /**
     * Default limit on the number of attributes of one element (matches
     * the JDK's {@code elementAttributeLimit} before JDK 24, as
     * {@link #DEFAULT_EXPANSION_LIMIT} does).
     */
    static final int DEFAULT_MAX_ATTRIBUTES_PER_ELEMENT = 10000;

    /**
     * Permissive defaults for direct/test construction: external entity
     * fetching enabled, all protocols allowed for external DTD access, an
     * effectively unlimited entity expansion budget, and no other limits.
     */
    static final ScannerSettings PERMISSIVE =
            new ScannerSettings(true, true, false, true, "all", Integer.MAX_VALUE, 0, 0, 0);

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

    /** Maximum number of elements that may be open at once. */
    final int maxElementDepth;

    /** Maximum number of characters in a name, or in the digits of a
     *  character reference. */
    final int maxNameLength;

    /** Maximum number of attributes, namespace declarations included,
     *  specified on one element. */
    final int maxAttributesPerElement;

    ScannerSettings(boolean externalGeneralEntities, boolean externalParameterEntities,
            boolean disallowDoctypeDecl, boolean resolveDTDURIs, String accessExternalDTD,
            int entityExpansionLimit, int maxElementDepth, int maxNameLength,
            int maxAttributesPerElement) {
        this.externalGeneralEntities = externalGeneralEntities;
        this.externalParameterEntities = externalParameterEntities;
        this.disallowDoctypeDecl = disallowDoctypeDecl;
        this.resolveDTDURIs = resolveDTDURIs;
        this.accessExternalDTD = (accessExternalDTD != null) ? accessExternalDTD : "";
        this.entityExpansionLimit = entityExpansionLimit;
        this.maxElementDepth = maxElementDepth;
        this.maxNameLength = maxNameLength;
        this.maxAttributesPerElement = maxAttributesPerElement;
    }

}
