/*
 * ElementValidationContext.java
 * Copyright (C) 2025 Chris Burdess
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
 * Tracks validation state for a single element in the document tree.
 * Combines the element name and its content model validator in a single object
 * to maintain stack consistency.
 *
 * <p>This class is designed to be pooled and reused to minimize allocation.
 * Use {@link #update} to reinitialize a pooled instance and {@link #clear}
 * before returning to the pool.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class ElementValidationContext {

    /**
     * Pool of reusable ElementValidationContext objects.
     * Not thread-safe but parser is single-threaded.
     */
    private static final int MAX_POOL_SIZE = 64;

    /**
     * Intrusive link to another ElementValidationContext. Dual-purpose and never
     * used for both at once, since an instance is either checked out (on the
     * active element stack) or sitting in the pool, never both:
     * <ul>
     * <li>On ContentParser's active element stack: the context below this one
     *     (i.e. the parent element), with the stack itself being just a
     *     reference to the top context.</li>
     * <li>In {@link Pool}'s free list: the next available (unused) context.</li>
     * </ul>
     * This avoids a separate backing array for either structure - the objects
     * themselves form the list, the same way W3C DOM nodes link directly to
     * their siblings/parent rather than living inside a wrapper collection.
     */
    ElementValidationContext next;

    /**
     * The element name (for both well-formedness and validation error reporting).
     */
    String elementName;

    /**
     * The content model validator for this element (may be null if validation is disabled).
     */
    ContentModelValidator validator;

    /**
     * The entity expansion depth at which this element was opened.
     * Used to enforce WFC: Parsed Entity - elements opened within an entity must be closed within that entity.
     */
    int entityExpansionDepth;

    /**
     * Creates a new element validation context with default values.
     * For pooling - use {@link #update} to initialize.
     */
    ElementValidationContext() {
        // Default constructor for pooling
    }
    
    /**
     * Creates a new element validation context.
     * 
     * @param elementName the element name
     * @param validator the content model validator (may be null)
     * @param entityExpansionDepth the entity expansion depth when this element was opened
     */
    ElementValidationContext(String elementName, ContentModelValidator validator, int entityExpansionDepth) {
        this.elementName = elementName;
        this.validator = validator;
        this.entityExpansionDepth = entityExpansionDepth;
    }
    
    /**
     * Updates this context with new values (for pool reuse).
     * 
     * @param elementName the element name
     * @param validator the content model validator (may be null)
     * @param entityExpansionDepth the entity expansion depth when this element was opened
     */
    void update(String elementName, ContentModelValidator validator, int entityExpansionDepth) {
        this.elementName = elementName;
        this.validator = validator;
        this.entityExpansionDepth = entityExpansionDepth;
    }
    
    /**
     * Clears this context before returning to pool.
     * Releases references to allow GC of element names and validators.
     */
    void clear() {
        this.elementName = null;
        this.validator = null;
        this.entityExpansionDepth = 0;
    }
    
    @Override
    public String toString() {
        return "ElementValidationContext{elementName='" + elementName + 
               "', validator=" + (validator != null ? "present" : "null") + "}";
    }
    
    // ===== Pool Management =====
    
    /**
     * Pool of available ElementValidationContext objects.
     * Each ContentParser should have its own pool instance.
     */
    static class Pool {
        /** Head of the free list, threaded through each context's own {@code next} field. */
        private ElementValidationContext freeListHead;
        private int size = 0;

        /**
         * Checks out an ElementValidationContext from the pool.
         * If pool is empty, creates a new instance.
         *
         * @param elementName the element name
         * @param validator the content model validator (may be null)
         * @param entityExpansionDepth the entity expansion depth
         * @return an initialized ElementValidationContext
         */
        ElementValidationContext checkout(String elementName, ContentModelValidator validator, int entityExpansionDepth) {
            ElementValidationContext ctx;
            if (freeListHead != null) {
                ctx = freeListHead;
                freeListHead = ctx.next;
                ctx.next = null;
                size--;
            } else {
                ctx = new ElementValidationContext();
            }
            ctx.update(elementName, validator, entityExpansionDepth);
            return ctx;
        }

        /**
         * Returns an ElementValidationContext to the pool.
         *
         * @param ctx the context to return
         */
        void returnToPool(ElementValidationContext ctx) {
            if (ctx != null && size < MAX_POOL_SIZE) {
                ctx.clear();
                ctx.next = freeListHead;
                freeListHead = ctx;
                size++;
            }
        }

        /**
         * Clears the pool, releasing all objects.
         */
        void clear() {
            freeListHead = null;
            size = 0;
        }
    }
}
