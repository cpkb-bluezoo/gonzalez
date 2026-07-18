/*
 * DTDModel.java
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal DTD model for M5's deliberately narrow scope: attribute
 * defaulting, type-aware attribute value normalisation, and content-type-
 * driven {@code ignorableWhitespace} determination - not full content-model
 * conformance checking or validity (VC) constraint checking, which are out
 * of scope for this milestone (see ASYNC-PIPELINE.md). Populated entirely by
 * {@link Scanner} while it parses an internal DTD subset; queried by
 * {@link Scanner} itself (there is no separate downstream pipeline stage for
 * this - see Scanner's "M5" section for why).
 * <p>
 * {@code <!ELEMENT>} declarations are reduced to just their top-level {@link
 * ContentType} (EMPTY/ANY/MIXED/ELEMENT) - enough to know whether
 * whitespace-only text is ignorable, but not enough to validate actual
 * children against the declared model (no content-model tree is built at
 * all). {@code <!ATTLIST>} declarations are reduced to, per attribute, its
 * declared type name (the literal keyword text - {@code "CDATA"}, {@code
 * "ID"}, {@code "IDREF"}, ..., {@code "NOTATION"}, or {@code "ENUMERATION"}
 * for a bare enumeration, matching the type strings the old parser's
 * {@code AttListDeclParser} uses and therefore what SAX's {@code
 * Attributes.getType()} should report) and its resolved default value, if
 * any (null for {@code #REQUIRED}/{@code #IMPLIED} - no default to inject;
 * the "required" distinction itself is not tracked, since enforcing it is a
 * VC check, out of scope here). Enumerated/NOTATION value lists are
 * recognised syntactically (to skip past them) but their actual allowed
 * values are not retained - checking that a specified value is a legal
 * enumeration member is a VC check, also out of scope.
 * <p>
 * First declaration wins for a repeated name, matching {@code <!ENTITY>}'s
 * XML 4.2 rule and applied here for consistency (the spec doesn't actually
 * mandate this for element/attribute declarations the way it does for
 * entities, but silently accepting the first and ignoring later duplicates
 * is a reasonable, safe default for a non-validating milestone that isn't
 * checking for the "no duplicate declaration" VC anyway).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DTDModel {

    enum ContentType {
        EMPTY, ANY, MIXED, ELEMENT
    }

    static final class AttDef {
        /** The declared type's literal keyword text - never null (an
         *  undeclared attribute simply has no AttDef at all, rather than one
         *  with a null type; see Scanner.lookupAttributeType for the
         *  undeclared-defaults-to-"CDATA" SAX convention). */
        final String type;
        /** Resolved (entity-expanded) default value, or null if this
         *  attribute has no default to inject ({@code #REQUIRED}/{@code
         *  #IMPLIED}). Mutable: stored as raw literal text at parse time,
         *  then resolved in place once the whole DOCTYPE - and therefore
         *  every entity - is known (see Scanner.scanDoctypeSubset's
         *  finishing step), since a default value may reference an entity
         *  declared later in the same internal subset. */
        String defaultValue;

        AttDef(String type, String defaultValue) {
            this.type = type;
            this.defaultValue = defaultValue;
        }

        /** True if the type is CDATA - the type-dependent collapse
         *  normalisation (XML 3.3.3) applies only when this is false. */
        boolean isCdata() {
            return "CDATA".equals(type);
        }
    }

    private final Map<String, ContentType> contentTypes = new HashMap<String, ContentType>();
    private final Map<String, LinkedHashMap<String, AttDef>> attlists =
            new HashMap<String, LinkedHashMap<String, AttDef>>();

    void declareContentType(String element, ContentType type) {
        if (!contentTypes.containsKey(element)) {
            contentTypes.put(element, type);
        }
    }

    void declareAttribute(String element, String attrName, String type, String rawDefault) {
        LinkedHashMap<String, AttDef> attrs = attlists.get(element);
        if (attrs == null) {
            attrs = new LinkedHashMap<String, AttDef>();
            attlists.put(element, attrs);
        }
        if (!attrs.containsKey(attrName)) {
            attrs.put(attrName, new AttDef(type, rawDefault));
        }
    }

    /** Returns the declared content type for {@code element}, or null if
     *  no {@code <!ELEMENT>} declaration was seen for it. */
    ContentType getContentType(String element) {
        return contentTypes.get(element);
    }

    /** Returns the declared attributes for {@code element} in declaration
     *  order, or null if no {@code <!ATTLIST>} declaration was seen for it. */
    Map<String, AttDef> getAttributes(String element) {
        return attlists.get(element);
    }

    /** True if any attribute of any element has a non-null default value -
     *  a cheap way for Scanner to skip the whole defaulting/resolution path
     *  entirely for the (common) no-DTD-defaults case. */
    boolean hasAnyDefaults() {
        for (LinkedHashMap<String, AttDef> attrs : attlists.values()) {
            for (AttDef def : attrs.values()) {
                if (def.defaultValue != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** All attribute declarations across all elements, keyed by element name -
     *  for Scanner's one-time, post-doctype default-value entity-resolution
     *  pass (see Scanner.scanDoctypeSubset's finishing step). Returns the
     *  live map, not a copy: {@link AttDef#defaultValue} is deliberately
     *  mutable so that pass can resolve values in place. */
    Map<String, LinkedHashMap<String, AttDef>> allAttlists() {
        return attlists;
    }

}
