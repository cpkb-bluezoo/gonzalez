/*
 * LocatorStack.java
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

import java.util.ArrayDeque;
import java.util.Deque;
import org.xml.sax.Locator;
import org.xml.sax.ext.Locator2;

/**
 * A SAX Locator that maintains a stack of delegate locators (tokenizers).
 *
 * <p>During XML parsing, multiple tokenizers may be active simultaneously
 * when external entities are being expanded. Each tokenizer tracks its own
 * position (system ID, line number, column number). This class acts as a
 * single stable Locator reference for the SAX ContentHandler, always
 * delegating to whichever tokenizer is currently active (the top of the
 * stack).
 *
 * <p>When a new tokenizer context is entered (e.g. external entity
 * expansion), the tokenizer is pushed onto the stack. When that context
 * ends, it is popped, restoring the previous tokenizer as the active
 * delegate.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class LocatorStack implements Locator2 {

    private final Deque<Locator> stack = new ArrayDeque<>();

    /**
     * Pushes a locator (tokenizer) onto the stack, making it the active
     * delegate for all Locator method calls.
     *
     * @param locator the locator to push
     */
    void push(Locator locator) {
        stack.push(locator);
    }

    /**
     * Pops the current locator from the stack, restoring the previous one
     * as the active delegate.
     *
     * @return the popped locator, or null if the stack was empty
     */
    Locator pop() {
        if (stack.isEmpty()) {
            return null;
        }
        return stack.pop();
    }

    /**
     * Returns the current active locator without removing it.
     *
     * @return the top locator, or null if the stack is empty
     */
    Locator peek() {
        return stack.peek();
    }

    /**
     * Returns whether the stack is empty.
     *
     * @return true if no locators are on the stack
     */
    boolean isEmpty() {
        return stack.isEmpty();
    }

    @Override
    public String getPublicId() {
        Locator current = stack.peek();
        return (current != null) ? current.getPublicId() : null;
    }

    @Override
    public String getSystemId() {
        Locator current = stack.peek();
        return (current != null) ? current.getSystemId() : null;
    }

    @Override
    public int getLineNumber() {
        Locator current = stack.peek();
        return (current != null) ? current.getLineNumber() : -1;
    }

    @Override
    public int getColumnNumber() {
        Locator current = stack.peek();
        return (current != null) ? current.getColumnNumber() : -1;
    }

    @Override
    public String getXMLVersion() {
        Locator current = stack.peek();
        if (current instanceof Locator2) {
            return ((Locator2) current).getXMLVersion();
        }
        return null;
    }

    @Override
    public String getEncoding() {
        Locator current = stack.peek();
        if (current instanceof Locator2) {
            return ((Locator2) current).getEncoding();
        }
        return null;
    }

}
