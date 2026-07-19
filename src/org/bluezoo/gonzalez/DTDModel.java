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
import java.util.List;
import java.util.Map;

/**
 * DTD model for {@link Scanner}: element content models/types and attribute
 * declarations, used for attribute defaulting, type-aware attribute value
 * normalisation, content-type-driven {@code ignorableWhitespace}
 * determination, and - when {@link Scanner}'s validation is enabled -
 * content-model and attribute validity constraint (VC) checking. Populated
 * entirely by {@link Scanner} while it parses a DTD subset (internal or
 * external); queried by {@link Scanner} itself, since there is no separate
 * downstream pipeline stage for DTD-driven behaviour in this pipeline.
 * <p>
 * {@code <!ELEMENT>} declarations are stored as a full {@link
 * ElementDeclaration}: its top-level {@link ElementDeclaration.ContentType}
 * (EMPTY/ANY/MIXED/ELEMENT, always known - enough on its own to know whether
 * whitespace-only text is ignorable) plus, only when validation is enabled,
 * the parsed content-model tree needed to actually validate children against
 * it. {@code <!ATTLIST>} declarations are stored as an {@link AttDef} per
 * attribute: its declared type name (the literal keyword text - {@code
 * "CDATA"}, {@code "ID"}, {@code "IDREF"}, ..., {@code "NOTATION"}, or
 * {@code "ENUMERATION"} for a bare enumeration - matching what SAX's
 * {@code Attributes.getType()} should report), its {@link Mode} ({@code
 * #REQUIRED}/{@code #IMPLIED}/{@code #FIXED}/plain default), its resolved
 * default value if any, and - for {@code ENUMERATION}/{@code NOTATION} - the
 * declared list of legal values.
 * <p>
 * First declaration wins for a repeated name, matching {@code <!ENTITY>}'s
 * XML 4.2 rule and applied here for consistency (the spec doesn't actually
 * mandate this for element/attribute declarations the way it does for
 * entities, but silently accepting the first and ignoring later duplicates
 * is a reasonable, safe default).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DTDModel {

    /** The {@code #REQUIRED}/{@code #IMPLIED}/{@code #FIXED}/(plain literal
     *  default) distinction, needed for VC "Required Attribute"/"Fixed
     *  Attribute Default"/"ID Attribute Default" (an ID-typed attribute may
     *  only be {@code REQUIRED} or {@code IMPLIED}, never {@code FIXED} or a
     *  plain default). {@code NONE} means a plain literal default (mutually
     *  exclusive with {@code defaultValue == null}, except that {@code
     *  REQUIRED}/{@code IMPLIED} also always have a null {@code
     *  defaultValue} - there's simply nothing to inject for either). */
    enum Mode {
        NONE, REQUIRED, IMPLIED, FIXED
    }

    static final class AttDef {
        /** The declared type's literal keyword text - never null (an
         *  undeclared attribute simply has no AttDef at all, rather than one
         *  with a null type; see Scanner.lookupAttributeType for the
         *  undeclared-defaults-to-"CDATA" SAX convention). */
        final String type;
        /** See {@link Mode}. */
        final Mode mode;
        /** Resolved (entity-expanded) default value, or null if this
         *  attribute has no default to inject ({@code #REQUIRED}/{@code
         *  #IMPLIED}). Mutable: stored as raw literal text at parse time,
         *  then resolved in place once the whole DOCTYPE - and therefore
         *  every entity - is known (see Scanner.scanDoctypeSubset's
         *  finishing step), since a default value may reference an entity
         *  declared later in the same internal subset. */
        String defaultValue;
        /** For {@code type} {@code "ENUMERATION"} or {@code "NOTATION"}, the
         *  declared list of legal values (VC "Enumeration"/"Notation
         *  Attributes") - null for every other type. */
        final List<String> enumeration;
        /** True if this attribute's own {@code AttDef} (within the
         *  {@code <!ATTLIST>}) was declared in external markup (the
         *  external DTD subset, or an external parameter entity's own
         *  replacement text) - used for VC "Standalone Document
         *  Declaration" (Section 2.9), mirroring {@link
         *  ElementDeclaration#fromExternalSubset}'s equivalent role for
         *  element declarations. */
        final boolean declaredExternally;

        AttDef(String type, Mode mode, String defaultValue, List<String> enumeration, boolean declaredExternally) {
            this.type = type;
            this.mode = mode;
            this.defaultValue = defaultValue;
            this.enumeration = enumeration;
            this.declaredExternally = declaredExternally;
        }

        /** True if the type is CDATA - the type-dependent collapse
         *  normalisation (XML 3.3.3) applies only when this is false. */
        boolean isCdata() {
            return "CDATA".equals(type);
        }
    }

    /** Element declarations, keyed by name - the content-model tree is only
     *  present when validation was enabled at declaration time (see {@link
     *  Scanner#scanElementDeclaration}). Reuses {@link ElementDeclaration}
     *  unchanged from the old tokenizer-based pipeline: it and {@link
     *  ContentModelValidator} are framework-independent already, with no
     *  {@code Tokenizer}/{@code ContentParser} coupling to work around. */
    private final Map<String, ElementDeclaration> elements = new HashMap<String, ElementDeclaration>();
    private final Map<String, LinkedHashMap<String, AttDef>> attlists =
            new HashMap<String, LinkedHashMap<String, AttDef>>();

    void declareElement(String element, ElementDeclaration decl) {
        if (!elements.containsKey(element)) {
            elements.put(element, decl);
        }
    }

    void declareAttribute(String element, String attrName, String type, Mode mode, String rawDefault,
            List<String> enumeration, boolean declaredExternally) {
        LinkedHashMap<String, AttDef> attrs = attlists.get(element);
        if (attrs == null) {
            attrs = new LinkedHashMap<String, AttDef>();
            attlists.put(element, attrs);
        }
        if (!attrs.containsKey(attrName)) {
            attrs.put(attrName, new AttDef(type, mode, rawDefault, enumeration, declaredExternally));
        }
    }

    /** Returns the declared content type for {@code element}, or null if
     *  no {@code <!ELEMENT>} declaration was seen for it. The isEmpty()
     *  guard short-circuits the common no-DTD case: this is queried once
     *  per text run by Scanner.scanContent, and profiling showed the
     *  hash-and-probe of an always-empty map as a measurable per-document
     *  cost (~10% on the multibyte benchmark corpus). */
    ElementDeclaration.ContentType getContentType(String element) {
        if (elements.isEmpty()) {
            return null;
        }
        ElementDeclaration decl = elements.get(element);
        return decl == null ? null : decl.contentType;
    }

    /** Returns the full element declaration (content-model tree included)
     *  for {@code element}, or null if none was seen - used only by VC
     *  validation (see {@link ContentModelValidator}); everything else
     *  keeps using the cheaper {@link #getContentType}. */
    ElementDeclaration getElementDeclaration(String element) {
        if (elements.isEmpty()) {
            return null;
        }
        return elements.get(element);
    }

    /** Returns the declared attributes for {@code element} in declaration
     *  order, or null if no {@code <!ATTLIST>} declaration was seen for it.
     *  The isEmpty() guard short-circuits the common no-DTD case, as in
     *  {@link #getContentType} - this is queried per attribute (see
     *  Scanner.lookupAttributeType) and per start tag (see
     *  Scanner.applyAttributeDefaults). */
    Map<String, AttDef> getAttributes(String element) {
        if (attlists.isEmpty()) {
            return null;
        }
        return attlists.get(element);
    }

    /** True if {@code element} already has an attribute of {@code type}
     *  declared under a different name than {@code excludeName} - used for
     *  VC "One ID per Element Type"/"One Notation Per Element Type"
     *  (Section 3.3.1), checked by {@link Scanner} just before declaring a
     *  new attribute of that same type. */
    boolean hasAttributeOfType(String element, String type, String excludeName) {
        LinkedHashMap<String, AttDef> attrs = attlists.get(element);
        if (attrs == null) {
            return false;
        }
        for (Map.Entry<String, AttDef> entry : attrs.entrySet()) {
            if (!entry.getKey().equals(excludeName) && type.equals(entry.getValue().type)) {
                return true;
            }
        }
        return false;
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
