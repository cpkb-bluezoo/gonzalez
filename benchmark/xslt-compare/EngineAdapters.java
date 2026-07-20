/*
 * Engine adapters for the XSLT bake-off.
 */
package org.bluezoo.gonzalez.xsltcompare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.bluezoo.gonzalez.transform.GonzalezTransformer;
import org.bluezoo.gonzalez.transform.GonzalezTransformerFactory;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;

interface EngineAdapter {
    String name();
    EngineResult run(CompareTestCase test);
}

enum Status {
    PASS, FAIL, ERROR, SKIP
}

final class EngineResult {
    final Status status;
    final String detail;
    final String output;

    EngineResult(Status status, String detail, String output) {
        this.status = status;
        this.detail = detail;
        this.output = output;
    }

    static EngineResult skip(String reason) {
        return new EngineResult(Status.SKIP, reason, null);
    }

    static EngineResult pass(String output) {
        return new EngineResult(Status.PASS, null, output);
    }

    static EngineResult fail(String detail, String output) {
        return new EngineResult(Status.FAIL, detail, output);
    }

    static EngineResult error(String detail) {
        return new EngineResult(Status.ERROR, detail, null);
    }
}

abstract class JaxpAdapter implements EngineAdapter {
    protected final TransformerFactory factory;
    private final String name;

    protected JaxpAdapter(String name, TransformerFactory factory) {
        this.name = name;
        this.factory = factory;
        factory.setErrorListener(SILENT_ERRORS);
        try {
            factory.setAttribute(
                    "http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
            factory.setAttribute(
                    "http://javax.xml.XMLConstants/property/accessExternalStylesheet", "file");
        } catch (IllegalArgumentException ignored) {
            // Provider may not support these attributes (e.g. some JDK builds).
        }
    }

    @Override
    public String name() {
        return name;
    }

    protected void configureFactory(CompareTestCase test) { }

    protected EngineResult prepare(CompareTestCase test, Transformer transformer) {
        CompareSupport.applyStylesheetParams(new CompareSupport.TransformerLike() {
            @Override
            public void setParameter(String paramName, Object value) {
                transformer.setParameter(paramName, value);
            }
        }, test);
        return null;
    }

    @Override
    public EngineResult run(CompareTestCase test) {
        if (test.skipReason != null) {
            return EngineResult.skip(test.skipReason);
        }
        try {
            configureFactory(test);

            StreamSource stylesheet = new StreamSource(new FileInputStream(test.stylesheetFile));
            stylesheet.setSystemId(test.stylesheetFile.toURI().toString());
            Templates templates;
            try {
                templates = factory.newTemplates(stylesheet);
            } catch (TransformerException e) {
                if (test.expectsError) {
                    return EngineResult.pass(null);
                }
                return EngineResult.error("compile: " + message(e));
            }

            Transformer transformer = templates.newTransformer();
            EngineResult prep = prepare(test, transformer);
            if (prep != null) {
                return prep;
            }

            Source source = sourceOf(test);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                transformer.transform(source, new StreamResult(out));
            } catch (TransformerException e) {
                if (test.expectsError) {
                    return EngineResult.pass(null);
                }
                return EngineResult.error("transform: " + message(e));
            }

            String actual = CompareSupport.decodeOutput(out.toByteArray());
            return judge(test, actual);
        } catch (Throwable e) {
            if (test.expectsError) {
                return EngineResult.pass(null);
            }
            return EngineResult.error(message(e));
        }
    }

    protected static EngineResult judge(CompareTestCase test, String actual) {
        if (test.expectsError) {
            if (matchesAnyExpected(test, actual)) {
                return EngineResult.pass(actual);
            }
            if (test.anyOfAcceptsSuccess) {
                return EngineResult.pass(actual);
            }
            return EngineResult.fail("expected error", actual);
        }
        if (test.expectedXml != null) {
            if (matchesAnyExpected(test, actual)) {
                return EngineResult.pass(actual);
            }
            return EngineResult.fail(XmlCompare.diff(test.expectedXml, actual), actual);
        }
        return EngineResult.pass(actual);
    }

    private static boolean matchesAnyExpected(CompareTestCase test, String actual) {
        if (test.expectedXml != null && XmlCompare.matchesExpected(test.expectedXml, actual)) {
            return true;
        }
        List<String> alternatives = test.anyOfExpectedXmls;
        if (alternatives != null) {
            for (String expected : alternatives) {
                if (XmlCompare.matchesExpected(expected, actual)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static Source sourceOf(CompareTestCase test) throws Exception {
        if (test.sourceFile != null && test.sourceFile.exists()) {
            StreamSource source = new StreamSource(new FileInputStream(test.sourceFile));
            source.setSystemId(test.sourceFile.toURI().toString());
            return source;
        }
        if (test.sourceContent != null) {
            byte[] bytes = test.sourceContent.getBytes(StandardCharsets.UTF_8);
            StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
            if (test.testSetFile != null) {
                source.setSystemId(test.testSetFile.toURI().toString());
            }
            return source;
        }
        byte[] dummy = "<dummy/>".getBytes(StandardCharsets.UTF_8);
        return new StreamSource(new ByteArrayInputStream(dummy));
    }

    protected static String message(Throwable e) {
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    private static final ErrorListener SILENT_ERRORS = new ErrorListener() {
        @Override public void warning(TransformerException exception) { }
        @Override public void error(TransformerException exception) throws TransformerException {
            throw exception;
        }
        @Override public void fatalError(TransformerException exception) throws TransformerException {
            throw exception;
        }
    };
}

final class GonzalezAdapter extends JaxpAdapter {
    GonzalezAdapter() {
        super("gonzalez", new GonzalezTransformerFactory());
    }

    @Override
    protected void configureFactory(CompareTestCase test) {
        GonzalezTransformerFactory gFactory = (GonzalezTransformerFactory) factory;
        gFactory.clearStaticParameters();
        gFactory.setMaxXsltVersion(-1);
        if (test.staticParams != null) {
            for (Map.Entry<String, String> entry : test.staticParams.entrySet()) {
                gFactory.setStaticParameter(entry.getKey(), entry.getValue());
            }
        }
        if (test.specValue != null && !test.specValue.contains("+")) {
            double specVersion = CompareSupport.parseSpecVersionAsDouble(test.specValue);
            if (specVersion > 0 && specVersion < 3.0) {
                gFactory.setMaxXsltVersion(specVersion);
            }
        }
    }

    @Override
    protected EngineResult prepare(CompareTestCase test, Transformer transformer) {
        if (!(transformer instanceof GonzalezTransformer)) {
            return EngineResult.error("not GonzalezTransformer");
        }
        GonzalezTransformer gt = (GonzalezTransformer) transformer;
        if (test.initialTemplate != null) {
            gt.setInitialTemplate(test.initialTemplate);
            if (test.sourceFile == null && test.sourceContent == null) {
                gt.setHasInitialContextItem(false);
            }
        }
        if (test.initialMode != null) {
            gt.setInitialMode(test.initialMode);
            if (test.sourceFile == null && test.sourceContent == null) {
                gt.setHasMatchSelection(false);
            }
        }
        if (test.sourceSelect != null) {
            gt.setInitialContextSelect(test.sourceSelect);
        }
        return super.prepare(test, transformer);
    }
}

final class JdkAdapter extends JaxpAdapter {
    JdkAdapter() throws Exception {
        super("jdk", TransformerFactory.newInstance(
                "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl",
                new ClassLoader(null) { }));
    }

    @Override
    protected EngineResult prepare(CompareTestCase test, Transformer transformer) {
        if (test.minSpecVersion() > 1.0) {
            return EngineResult.skip("xslt-" + test.minSpecVersion());
        }
        if (test.sourceSelect != null) {
            return EngineResult.skip("source-select");
        }
        if (test.initialTemplate != null) {
            return EngineResult.skip("initial-template");
        }
        if (test.initialMode != null) {
            return EngineResult.skip("initial-mode");
        }
        return super.prepare(test, transformer);
    }
}

final class SaxonAdapter implements EngineAdapter {
    private final Processor processor = new Processor(false);
    private final TransformerFactory jaxpFactory;

    SaxonAdapter() {
        jaxpFactory = new net.sf.saxon.TransformerFactoryImpl();
        try {
            jaxpFactory.setAttribute(
                    "http://javax.xml.XMLConstants/property/accessExternalDTD", "file");
            jaxpFactory.setAttribute(
                    "http://javax.xml.XMLConstants/property/accessExternalStylesheet", "file");
        } catch (IllegalArgumentException ignored) {
            // ignore
        }
    }

    @Override
    public String name() {
        return "saxon";
    }

    @Override
    public EngineResult run(CompareTestCase test) {
        if (test.skipReason != null) {
            return EngineResult.skip(test.skipReason);
        }
        if (test.sourceSelect != null) {
            return EngineResult.skip("source-select");
        }
        if (test.initialTemplate != null || test.initialMode != null) {
            return runS9api(test);
        }
        return new JaxpAdapter("saxon", jaxpFactory) {
            @Override
            protected EngineResult prepare(CompareTestCase t, Transformer transformer) {
                return super.prepare(t, transformer);
            }
        }.run(test);
    }

    private EngineResult runS9api(CompareTestCase test) {
        try {
            XsltCompiler compiler = processor.newXsltCompiler();
            XsltExecutable executable;
            try {
                executable = compiler.compile(new StreamSource(test.stylesheetFile));
            } catch (Exception e) {
                if (test.expectsError) {
                    return EngineResult.pass(null);
                }
                return EngineResult.error("compile: " + JaxpAdapter.message(e));
            }
            Xslt30Transformer transformer = executable.load30();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Serializer serializer = processor.newSerializer(out);
            try {
                if (test.sourceFile != null && test.sourceFile.exists()) {
                    XdmNode doc = processor.newDocumentBuilder().build(test.sourceFile);
                    transformer.setGlobalContextItem(doc);
                } else if (test.sourceContent != null) {
                    XdmNode doc = processor.newDocumentBuilder().build(
                            new StreamSource(new ByteArrayInputStream(
                                    test.sourceContent.getBytes(StandardCharsets.UTF_8))));
                    transformer.setGlobalContextItem(doc);
                }
                if (test.initialTemplate != null) {
                    transformer.callTemplate(toQName(test.initialTemplate), serializer);
                } else if (test.initialMode != null) {
                    transformer.setInitialMode(toQName(test.initialMode));
                    if (test.sourceFile != null && test.sourceFile.exists()) {
                        transformer.applyTemplates(new StreamSource(test.sourceFile), serializer);
                    } else if (test.sourceContent != null) {
                        transformer.applyTemplates(new StreamSource(new ByteArrayInputStream(
                                test.sourceContent.getBytes(StandardCharsets.UTF_8))), serializer);
                    } else {
                        transformer.applyTemplates(
                                new StreamSource(new ByteArrayInputStream(
                                        "<dummy/>".getBytes(StandardCharsets.UTF_8))),
                                serializer);
                    }
                }
            } catch (Exception e) {
                if (test.expectsError) {
                    return EngineResult.pass(null);
                }
                return EngineResult.error("transform: " + JaxpAdapter.message(e));
            }
            return JaxpAdapter.judge(test, CompareSupport.decodeOutput(out.toByteArray()));
        } catch (Throwable e) {
            if (test.expectsError) {
                return EngineResult.pass(null);
            }
            return EngineResult.error(JaxpAdapter.message(e));
        }
    }

    private static QName toQName(String clarkOrLocal) {
        if (clarkOrLocal != null && clarkOrLocal.startsWith("{")) {
            int end = clarkOrLocal.indexOf('}');
            if (end > 0) {
                return new QName(clarkOrLocal.substring(1, end), clarkOrLocal.substring(end + 1));
            }
        }
        return new QName(clarkOrLocal);
    }
}
