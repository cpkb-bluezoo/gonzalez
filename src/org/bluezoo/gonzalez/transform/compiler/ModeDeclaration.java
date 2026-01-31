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
    private final OnNoMatch onNoMatch;
    private final Visibility visibility;
    private final String useAccumulators;
    private final boolean typed;
    private final boolean warning;

    /**
     * Creates a new mode declaration.
     *
     * @param name the mode name (null for default mode)
     * @param streamable whether the mode is streamable
     * @param onNoMatch behavior when no template matches
     * @param visibility visibility for packages
     * @param useAccumulators accumulator names (whitespace-separated)
     * @param typed whether typed validation is enabled
     * @param warning whether to warn on no match (when fail)
     */
    public ModeDeclaration(String name, boolean streamable, OnNoMatch onNoMatch,
                           Visibility visibility, String useAccumulators,
                           boolean typed, boolean warning) {
        this.name = name;
        this.streamable = streamable;
        this.onNoMatch = onNoMatch != null ? onNoMatch : OnNoMatch.TEXT_ONLY_COPY;
        this.visibility = visibility != null ? visibility : Visibility.PUBLIC;
        this.useAccumulators = useAccumulators;
        this.typed = typed;
        this.warning = warning;
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
     * Returns the mode visibility.
     *
     * @return the visibility
     */
    public Visibility getVisibility() {
        return visibility;
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
        private OnNoMatch onNoMatch = OnNoMatch.TEXT_ONLY_COPY;
        private Visibility visibility = Visibility.PUBLIC;
        private String useAccumulators;
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
            return this;
        }

        /**
         * Sets the on-no-match behavior from a string value.
         *
         * @param value the attribute value
         * @return this builder
         */
        public Builder onNoMatch(String value) {
            this.onNoMatch = OnNoMatch.parse(value);
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
            return this;
        }

        /**
         * Sets the mode visibility from a string value.
         *
         * @param value the attribute value
         * @return this builder
         */
        public Builder visibility(String value) {
            this.visibility = Visibility.parse(value);
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
            return new ModeDeclaration(name, streamable, onNoMatch, visibility,
                                        useAccumulators, typed, warning);
        }
    }

}
