/*
 * LiteralText.java
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

package org.bluezoo.gonzalez.transform.ast;

import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.xml.sax.SAXException;

/**
 * Literal text content in an XSLT stylesheet.
 *
 * <p>Literal text represents character data that appears in the stylesheet
 * and is copied directly to the output.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class LiteralText implements XSLTNode {

    private final String text;
    private final boolean disableOutputEscaping;

    /**
     * Creates literal text.
     *
     * @param text the text content
     */
    public LiteralText(String text) {
        this(text, false);
    }

    /**
     * Creates literal text with optional output escaping control.
     *
     * @param text the text content
     * @param disableOutputEscaping true to disable output escaping
     */
    public LiteralText(String text, boolean disableOutputEscaping) {
        this.text = text != null ? text : "";
        this.disableOutputEscaping = disableOutputEscaping;
    }

    @Override
    public void execute(TransformContext context, OutputHandler output) throws SAXException {
        if (!text.isEmpty()) {
            if (disableOutputEscaping) {
                output.charactersRaw(text);
            } else {
                output.characters(text);
            }
        }
    }

    @Override
    public StreamingCapability getStreamingCapability() {
        return StreamingCapability.FULL;
    }

    /**
     * Returns the text content.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * Returns true if output escaping is disabled.
     *
     * @return true if escaping disabled
     */
    public boolean isDisableOutputEscaping() {
        return disableOutputEscaping;
    }

    /**
     * Returns true if this text is whitespace-only.
     *
     * @return true if only whitespace
     */
    public boolean isWhitespaceOnly() {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        String preview = text.length() > 20 ? text.substring(0, 20) + "..." : text;
        return "LiteralText[\"" + preview.replace("\n", "\\n") + "\"]";
    }

}
