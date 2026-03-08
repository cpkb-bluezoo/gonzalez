/*
 * ModeDeclaration.java
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

package org.bluezoo.gonzalez.transform.compiler;

import java.util.HashMap;
import java.util.Map;

/**
 * XSLT 3.0 mode declaration.
 *
 * <p>A mode declaration specifies properties for a named mode or the default
 * (unnamed) mode. Properties include:
 *
 * <ul>
 *   <li><b>streamable</b> - whether templates in this mode must be streamable</li>
 *   <li><b>on-no-match</b> - what to do when no template matches a node</li>
 *   <li><b>visibility</b> - public, private, or final (for packages)</li>
 *   <li><b>use-accumulators</b> - which accumulators are available in this mode</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * &lt;xsl:mode name="streaming" streamable="yes" on-no-match="shallow-copy"/&gt;
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class ModeDeclaration {

    /**
     * Behavior when no template matches a node.
     */
    public enum OnNoMatch {
        /** Copy the node (element without content) and apply-templates to children. */
        SHALLOW_COPY,
        /** Copy the entire subtree. */
        DEEP_COPY,
        /** Copy only text content. */
        TEXT_ONLY_COPY,
        /** Skip the node and apply-templates to children. */
        SHALLOW_SKIP,
        /** Skip the entire subtree. */
        DEEP_SKIP,
        /** Raise an error. */
        FAIL;

        /**
         * Parses an on-no-match attribute value.
         *
         * @param value the attribute value
         * @return the enum value, or null if invalid
         */
        public static OnNoMatch parse(String value) {
            if (value == null) {
                return null;
            }
            switch (value.toLowerCase()) {
                case "shallow-copy": return SHALLOW_COPY;
                case "deep-copy": return DEEP_COPY;
                case "text-only-copy": return TEXT_ONLY_COPY;
                case "shallow-skip": return SHALLOW_SKIP;
                case "deep-skip": return DEEP_SKIP;
                case "fail": return FAIL;
                default: return null;
            }
        }
    }

    /**
     * Behavior when multiple templates match a node with the same priority.
     */
    public enum OnMultipleMatch {
        /** Use the last matching template (XSLT 1.0 recovery behavior). */
        USE_LAST,
        /** Raise an error XTDE0540. */
        FAIL;

        /**
         * Parses an on-multiple-match attribute value.
         *
         * @param value the attribute value
         * @return the enum value, or null if invalid
         */
        public static OnMultipleMatch parse(String value) {
            if (value == null) {
                return null;
            }
            switch (value.toLowerCase()) {
                case "use-last": return USE_LAST;
                case "fail": return FAIL;
                default: return null;
            }
        }
    }

    /**
     * Visibility of the mode (for packages).
     */
    public enum Visibility {
        /** Mode can be used anywhere. */
        PUBLIC,
        /** Mode can only be used within the same package. */
        PRIVATE,
        /** Mode can be used but not overridden. */
        FINAL;

        /**
         * Parses a visibility attribute value.
         *
         * @param value the attribute value
         * @return the enum value, or null if invalid
         */
        public static Visibility parse(String value) {
            if (value == null) {
                return null;
            }
            switch (value.toLowerCase()) {
                case "public": return PUBLIC;
                case "private": return PRIVATE;
                case "final": return FINAL;
                default: return null;
            }
        }
    }

    private final String name;
    private final boolean streamable;
    private final boolean streamableExplicit;
    private final OnNoMatch onNoMatch;
    private final boolean onNoMatchExplicit;
    private final OnMultipleMatch onMultipleMatch;
    private final boolean onMultipleMatchExplicit;
    private final Visibility visibility;
    private final boolean visibilityExplicit;
    private final String useAccumulators;
    private final boolean useAccumulatorsExplicit;
    private final String expandedUseAccumulators;
    private final boolean typed;
    private final boolean warning;

    /**
     * Creates a new mode declaration with full explicit-flag tracking.
     */
    ModeDeclaration(String name,
                    boolean streamable, boolean streamableExplicit,
                    OnNoMatch onNoMatch, boolean onNoMatchExplicit,
                    OnMultipleMatch onMultipleMatch, boolean onMultipleMatchExplicit,
                    Visibility visibility, boolean visibilityExplicit,
                    String useAccumulators, boolean useAccumulatorsExplicit,
                    String expandedUseAccumulators,
                    boolean typed, boolean warning) {
        this.name = name;
        this.streamable = streamable;
        this.streamableExplicit = streamableExplicit;
        this.onNoMatchExplicit = onNoMatchExplicit;
        this.onNoMatch = onNoMatch != null ? onNoMatch : OnNoMatch.TEXT_ONLY_COPY;
        this.onMultipleMatchExplicit = onMultipleMatchExplicit;
        this.onMultipleMatch = onMultipleMatch != null ? onMultipleMatch : OnMultipleMatch.USE_LAST;
        this.visibilityExplicit = visibilityExplicit;
        this.visibility = visibility != null ? visibility : Visibility.PUBLIC;
        this.useAccumulatorsExplicit = useAccumulatorsExplicit;
        this.useAccumulators = useAccumulators;
        this.expandedUseAccumulators = expandedUseAccumulators;
        this.typed = typed;
        this.warning = warning;
    }

    /**
     * Creates a new mode declaration (backward-compatible constructor).
     *
     * @param name the mode name (null for default mode)
     * @param streamable whether the mode is streamable
     * @param onNoMatch behavior when no template matches
     * @param onMultipleMatch behavior when multiple templates match with same priority
     * @param visibility visibility for packages
     * @param useAccumulators accumulator names (whitespace-separated)
     * @param typed whether typed validation is enabled
     * @param warning whether to warn on no match (when fail)
     */
    public ModeDeclaration(String name, boolean streamable, OnNoMatch onNoMatch,
                           OnMultipleMatch onMultipleMatch, Visibility visibility,
                           String useAccumulators, boolean typed, boolean warning) {
        this(name,
             streamable, false,
             onNoMatch, onNoMatch != null,
             onMultipleMatch, onMultipleMatch != null,
             visibility, visibility != null,
             useAccumulators, useAccumulators != null,
             null,
             typed, warning);
    }

    /**
     * Returns true if on-no-match was explicitly specified.
     */
    public boolean isOnNoMatchExplicit() {
        return onNoMatchExplicit;
    }

    /**
     * Returns true if use-accumulators was explicitly specified.
     */
    public boolean isUseAccumulatorsExplicit() {
        return useAccumulatorsExplicit;
    }

    /**
     * Returns true if streamable was explicitly specified.
     */
    public boolean isStreamableExplicit() {
        return streamableExplicit;
    }

    /**
     * Returns true if visibility was explicitly specified.
     */
    public boolean isVisibilityExplicit() {
        return visibilityExplicit;
    }

    /**
     * Returns true if on-multiple-match was explicitly specified.
     */
    public boolean isOnMultipleMatchExplicit() {
        return onMultipleMatchExplicit;
    }

    /**
     * Returns the namespace-expanded form of use-accumulators, or null if not set.
     */
    public String getExpandedUseAccumulators() {
        return expandedUseAccumulators;
    }

    /**
     * Merges this mode declaration with an imported one.
     * Explicitly-set attributes on this declaration take priority;
     * unset attributes are inherited from the imported declaration.
     */
    public ModeDeclaration mergeWith(ModeDeclaration imported) {
        boolean mergedStreamable = this.streamableExplicit
            ? this.streamable : imported.streamable;
        boolean mergedStreamableExplicit = this.streamableExplicit
            || imported.streamableExplicit;

        OnNoMatch mergedOnNoMatch = this.onNoMatchExplicit
            ? this.onNoMatch : (imported.onNoMatchExplicit ? imported.onNoMatch : null);
        boolean mergedOnNoMatchExplicit = this.onNoMatchExplicit
            || imported.onNoMatchExplicit;

        OnMultipleMatch mergedOnMultipleMatch = this.onMultipleMatchExplicit
            ? this.onMultipleMatch
            : (imported.onMultipleMatchExplicit ? imported.onMultipleMatch : null);
        boolean mergedOnMultipleMatchExplicit = this.onMultipleMatchExplicit
            || imported.onMultipleMatchExplicit;

        Visibility mergedVisibility = this.visibilityExplicit
            ? this.visibility
            : (imported.visibilityExplicit ? imported.visibility : null);
        boolean mergedVisibilityExplicit = this.visibilityExplicit
            || imported.visibilityExplicit;

        String mergedUseAccumulators = this.useAccumulatorsExplicit
            ? this.useAccumulators : imported.useAccumulators;
        boolean mergedUseAccumulatorsExplicit = this.useAccumulatorsExplicit
            || imported.useAccumulatorsExplicit;
        String mergedExpandedUseAccumulators = this.useAccumulatorsExplicit
            ? this.expandedUseAccumulators : imported.expandedUseAccumulators;

        return new ModeDeclaration(this.name,
            mergedStreamable, mergedStreamableExplicit,
            mergedOnNoMatch, mergedOnNoMatchExplicit,
            mergedOnMultipleMatch, mergedOnMultipleMatchExplicit,
            mergedVisibility, mergedVisibilityExplicit,
            mergedUseAccumulators, mergedUseAccumulatorsExplicit,
            mergedExpandedUseAccumulators,
            this.typed || imported.typed,
            this.warning || imported.warning);
    }

    /**
     * Detects per-attribute conflicts between this declaration and another
     * at the same import precedence. Returns a map of attribute-name to error
     * message for each conflict, or null if there are no conflicts.
     */
    public Map<String, String> detectConflictsWith(ModeDeclaration other) {
        Map<String, String> conflicts = null;
        String modeKey = name != null ? name : "#default";

        if (this.streamableExplicit && other.streamableExplicit
                && this.streamable != other.streamable) {
            conflicts = new HashMap<>();
            conflicts.put("streamable",
                "XTSE0545: Conflicting values for streamable on mode '"
                + modeKey + "': '" + this.streamable + "' vs '"
                + other.streamable + "'");
        }

        if (this.onNoMatchExplicit && other.onNoMatchExplicit
                && this.onNoMatch != other.onNoMatch) {
            if (conflicts == null) {
                conflicts = new HashMap<>();
            }
            conflicts.put("on-no-match",
                "XTSE0545: Conflicting values for on-no-match on mode '"
                + modeKey + "': '" + this.onNoMatch + "' vs '"
                + other.onNoMatch + "'");
        }

        if (this.onMultipleMatchExplicit && other.onMultipleMatchExplicit
                && this.onMultipleMatch != other.onMultipleMatch) {
            if (conflicts == null) {
                conflicts = new HashMap<>();
            }
            conflicts.put("on-multiple-match",
                "XTSE0545: Conflicting values for on-multiple-match on mode '"
                + modeKey + "': '" + this.onMultipleMatch + "' vs '"
                + other.onMultipleMatch + "'");
        }

        if (this.visibilityExplicit && other.visibilityExplicit
                && this.visibility != other.visibility) {
            if (conflicts == null) {
                conflicts = new HashMap<>();
            }
            conflicts.put("visibility",
                "XTSE0545: Conflicting values for visibility on mode '"
                + modeKey + "': '" + this.visibility + "' vs '"
                + other.visibility + "'");
        }

        if (this.useAccumulatorsExplicit && other.useAccumulatorsExplicit) {
            String thisExpanded = this.expandedUseAccumulators != null
                ? this.expandedUseAccumulators
                : (this.useAccumulators != null ? this.useAccumulators : "");
            String otherExpanded = other.expandedUseAccumulators != null
                ? other.expandedUseAccumulators
                : (other.useAccumulators != null ? other.useAccumulators : "");
            if (!thisExpanded.equals(otherExpanded)) {
                if (conflicts == null) {
                    conflicts = new HashMap<>();
                }
                conflicts.put("use-accumulators",
                    "XTSE0545: Conflicting values for use-accumulators on mode '"
                    + modeKey + "': '" + thisExpanded + "' vs '"
                    + otherExpanded + "'");
            }
        }

        return conflicts;
    }

    /**
     * Returns the mode name.
     *
     * @return the name, or null for the default (unnamed) mode
     */
    public String getName() {
        return name;
    }

    /**
     * Returns true if the mode is the default (unnamed) mode.
     *
     * @return true if default mode
     */
    public boolean isDefaultMode() {
        return name == null || "#default".equals(name) || "#unnamed".equals(name);
    }

    /**
     * Returns true if the mode is declared as streamable.
     *
     * @return true if streamable
     */
    public boolean isStreamable() {
        return streamable;
    }

    /**
     * Returns the on-no-match behavior.
     *
     * @return the behavior when no template matches
     */
    public OnNoMatch getOnNoMatch() {
        return onNoMatch;
    }

    /**
     * Returns the on-multiple-match behavior.
     *
     * @return the behavior when multiple templates match with same priority
     */
    public OnMultipleMatch getOnMultipleMatch() {
        return onMultipleMatch;
    }

    /**
     * Returns the mode visibility.
     *
     * @return the visibility
     */
    public Visibility getVisibility() {
        return visibility;
    }

    /**
     * Returns the mode visibility as a ComponentVisibility.
     * This is for compatibility with the XSLT 3.0 package system.
     *
     * @return the component visibility
     */
    public ComponentVisibility getComponentVisibility() {
        switch (visibility) {
            case PUBLIC:
                return ComponentVisibility.PUBLIC;
            case PRIVATE:
                return ComponentVisibility.PRIVATE;
            case FINAL:
                return ComponentVisibility.FINAL;
            default:
                return ComponentVisibility.PUBLIC;
        }
    }

    /**
     * Creates a copy of this mode declaration with a different component visibility.
     *
     * @param newVisibility the new visibility
     * @return a new ModeDeclaration with the specified visibility
     */
    public ModeDeclaration withComponentVisibility(ComponentVisibility newVisibility) {
        Visibility vis;
        switch (newVisibility) {
            case PRIVATE:
            case HIDDEN:
                vis = Visibility.PRIVATE;
                break;
            case FINAL:
                vis = Visibility.FINAL;
                break;
            default:
                vis = Visibility.PUBLIC;
                break;
        }
        return new ModeDeclaration(name,
            streamable, streamableExplicit,
            onNoMatch, onNoMatchExplicit,
            onMultipleMatch, onMultipleMatchExplicit,
            vis, true,
            useAccumulators, useAccumulatorsExplicit,
            expandedUseAccumulators,
            typed, warning);
    }

    /**
     * Returns the whitespace-separated list of accumulator names
     * available in this mode.
     *
     * @return the accumulator names, or null
     */
    public String getUseAccumulators() {
        return useAccumulators;
    }

    /**
     * Returns true if typed validation is enabled.
     *
     * @return true if typed
     */
    public boolean isTyped() {
        return typed;
    }

    /**
     * Returns true if warnings should be issued for no-match.
     *
     * @return true if warning enabled
     */
    public boolean isWarning() {
        return warning;
    }

    @Override
    public String toString() {
        return "ModeDeclaration[name=" + (name != null ? name : "#default") +
               ", streamable=" + streamable +
               ", onNoMatch=" + onNoMatch + "]";
    }

    /**
     * Builder for constructing ModeDeclaration instances.
     *
     * <p>Provides a fluent API for creating mode declarations.
     *
     * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
     */
    public static class Builder {
        private String name;
        private boolean streamable = false;
        private boolean streamableSet = false;
        private OnNoMatch onNoMatch = OnNoMatch.TEXT_ONLY_COPY;
        private boolean onNoMatchSet = false;
        private OnMultipleMatch onMultipleMatch = OnMultipleMatch.USE_LAST;
        private boolean onMultipleMatchSet = false;
        private Visibility visibility = Visibility.PUBLIC;
        private boolean visibilitySet = false;
        private String useAccumulators;
        private String expandedUseAccumulators;
        private boolean typed = false;
        private boolean warning = false;

        /**
         * Sets the mode name.
         *
         * @param name the name, or null for default mode
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets whether the mode is streamable.
         *
         * @param streamable true if streamable
         * @return this builder
         */
        public Builder streamable(boolean streamable) {
            this.streamable = streamable;
            this.streamableSet = true;
            return this;
        }

        /**
         * Sets the on-no-match behavior.
         *
         * @param onNoMatch the behavior enum
         * @return this builder
         */
        public Builder onNoMatch(OnNoMatch onNoMatch) {
            this.onNoMatch = onNoMatch;
            this.onNoMatchSet = onNoMatch != null;
            return this;
        }

        /**
         * Sets the on-no-match behavior from a string value.
         *
         * @param value the attribute value
         * @return this builder
         */
        public Builder onNoMatch(String value) {
            OnNoMatch parsed = OnNoMatch.parse(value);
            this.onNoMatch = parsed;
            this.onNoMatchSet = parsed != null;
            return this;
        }

        /**
         * Sets the on-multiple-match behavior.
         *
         * @param onMultipleMatch the behavior enum
         * @return this builder
         */
        public Builder onMultipleMatch(OnMultipleMatch onMultipleMatch) {
            this.onMultipleMatch = onMultipleMatch;
            this.onMultipleMatchSet = onMultipleMatch != null;
            return this;
        }

        /**
         * Sets the on-multiple-match behavior from a string value.
         *
         * @param value the attribute value
         * @return this builder
         */
        public Builder onMultipleMatch(String value) {
            OnMultipleMatch parsed = OnMultipleMatch.parse(value);
            if (parsed != null) {
                this.onMultipleMatch = parsed;
                this.onMultipleMatchSet = true;
            }
            return this;
        }

        /**
         * Sets the mode visibility.
         *
         * @param visibility the visibility enum
         * @return this builder
         */
        public Builder visibility(Visibility visibility) {
            this.visibility = visibility;
            this.visibilitySet = visibility != null;
            return this;
        }

        /**
         * Sets the mode visibility from a string value.
         *
         * @param value the attribute value
         * @return this builder
         */
        public Builder visibility(String value) {
            Visibility parsed = Visibility.parse(value);
            this.visibility = parsed;
            this.visibilitySet = parsed != null;
            return this;
        }

        /**
         * Sets the accumulator names available in this mode.
         *
         * @param accumulators whitespace-separated accumulator names
         * @return this builder
         */
        public Builder useAccumulators(String accumulators) {
            this.useAccumulators = accumulators;
            return this;
        }

        /**
         * Sets the namespace-expanded accumulator names for conflict detection.
         *
         * @param expanded the expanded accumulator names
         * @return this builder
         */
        public Builder expandedUseAccumulators(String expanded) {
            this.expandedUseAccumulators = expanded;
            return this;
        }

        /**
         * Sets whether typed validation is enabled.
         *
         * @param typed true if typed
         * @return this builder
         */
        public Builder typed(boolean typed) {
            this.typed = typed;
            return this;
        }

        /**
         * Sets whether warnings should be issued for no-match.
         *
         * @param warning true if warnings enabled
         * @return this builder
         */
        public Builder warning(boolean warning) {
            this.warning = warning;
            return this;
        }

        /**
         * Builds the ModeDeclaration.
         *
         * @return the mode declaration
         */
        public ModeDeclaration build() {
            OnNoMatch effectiveOnNoMatch = onNoMatchSet ? onNoMatch : null;
            OnMultipleMatch effectiveOnMultipleMatch = onMultipleMatchSet
                ? onMultipleMatch : null;
            Visibility effectiveVisibility = visibilitySet ? visibility : null;
            return new ModeDeclaration(name,
                streamable, streamableSet,
                effectiveOnNoMatch, onNoMatchSet,
                effectiveOnMultipleMatch, onMultipleMatchSet,
                effectiveVisibility, visibilitySet,
                useAccumulators, useAccumulators != null,
                expandedUseAccumulators,
                typed, warning);
        }
    }

}
