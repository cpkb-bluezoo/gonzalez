/*
 * NativePackageCompileTest.java
 * Copyright (C) 2026 Chris Burdess
 */
package org.bluezoo.gonzalez.transform;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gonzalez.transform.compiler.CompiledPackage;
import org.bluezoo.gonzalez.transform.compiler.PackageResolver;
import org.bluezoo.gonzalez.transform.compiler.StylesheetCompiler;
import org.junit.Test;
import org.xml.sax.InputSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class NativePackageCompileTest {

    @Test
    public void compilePackageUsesNativeXMLHandlerPath() throws Exception {
        String packageXml =
            "<xsl:package name='urn:test:native-pkg' package-version='1.0'"
            + " version='3.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:mode/>"
            + "<xsl:template match='/'>"
            + "<xsl:text>pkg</xsl:text>"
            + "</xsl:template>"
            + "</xsl:package>";

        StylesheetCompiler compiler = new StylesheetCompiler();
        InputSource source = new InputSource(new ByteArrayInputStream(
                packageXml.getBytes(StandardCharsets.UTF_8)));
        CompiledPackage pkg = compiler.compilePackage(source, new PackageResolver());

        assertEquals("urn:test:native-pkg", pkg.getPackageName());
        assertEquals("1.0", pkg.getPackageVersion());
        assertNotNull(pkg.getStylesheet());
        assertFalse(pkg.getStylesheet().getTemplateRules().isEmpty());
    }
}
