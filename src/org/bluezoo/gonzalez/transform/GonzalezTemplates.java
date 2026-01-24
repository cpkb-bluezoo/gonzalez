/*
 * GonzalezTemplates.java
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

import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.OutputProperties;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import java.util.Properties;

/**
 * JAXP Templates implementation for compiled Gonzalez stylesheets.
 *
 * <p>Templates represent a compiled stylesheet that can create multiple
 * Transformer instances for concurrent transformation.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class GonzalezTemplates implements Templates {

    private final CompiledStylesheet stylesheet;

    /**
     * Creates templates from a compiled stylesheet.
     *
     * @param stylesheet the compiled stylesheet
     */
    public GonzalezTemplates(CompiledStylesheet stylesheet) {
        this.stylesheet = stylesheet;
    }

    @Override
    public Transformer newTransformer() throws TransformerConfigurationException {
        return new GonzalezTransformer(stylesheet);
    }

    @Override
    public Properties getOutputProperties() {
        Properties props = new Properties();
        
        if (stylesheet != null) {
            OutputProperties op = stylesheet.getOutputProperties();
            
            props.setProperty("method", op.getMethod().name().toLowerCase());
            props.setProperty("version", op.getVersion());
            props.setProperty("encoding", op.getEncoding());
            props.setProperty("omit-xml-declaration", op.isOmitXmlDeclaration() ? "yes" : "no");
            props.setProperty("indent", op.isIndent() ? "yes" : "no");
            
            if (op.getDoctypePublic() != null) {
                props.setProperty("doctype-public", op.getDoctypePublic());
            }
            if (op.getDoctypeSystem() != null) {
                props.setProperty("doctype-system", op.getDoctypeSystem());
            }
            if (op.getMediaType() != null) {
                props.setProperty("media-type", op.getMediaType());
            }
        }
        
        return props;
    }

    /**
     * Returns the compiled stylesheet.
     *
     * @return the stylesheet
     */
    CompiledStylesheet getStylesheet() {
        return stylesheet;
    }

}
