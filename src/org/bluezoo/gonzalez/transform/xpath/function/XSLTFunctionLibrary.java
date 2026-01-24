/*
 * XSLTFunctionLibrary.java
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

package org.bluezoo.gonzalez.transform.xpath.function;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.XPathFunctionLibrary;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XSLT-specific function library.
 *
 * <p>Extends the XPath core function library with XSLT-specific functions:
 * <ul>
 *   <li>current() - returns the current node being processed</li>
 *   <li>key(name, value) - returns nodes matching a key</li>
 *   <li>document(uri) - loads external documents</li>
 *   <li>format-number(number, pattern) - formats numbers</li>
 *   <li>generate-id(node?) - generates unique ID</li>
 *   <li>system-property(name) - returns system property</li>
 *   <li>element-available(name) - tests for element availability</li>
 *   <li>function-available(name) - tests for function availability</li>
 *   <li>unparsed-entity-uri(name) - returns URI of unparsed entity</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class XSLTFunctionLibrary implements XPathFunctionLibrary {

    /** Singleton instance. */
    public static final XSLTFunctionLibrary INSTANCE = new XSLTFunctionLibrary();

    private final Map<String, Function> xsltFunctions;

    private XSLTFunctionLibrary() {
        Map<String, Function> map = new HashMap<>();
        
        map.put("current", new CurrentFunction());
        map.put("key", new KeyFunction());
        map.put("document", new DocumentFunction());
        map.put("format-number", new FormatNumberFunction());
        map.put("generate-id", new GenerateIdFunction());
        map.put("system-property", new SystemPropertyFunction());
        map.put("element-available", new ElementAvailableFunction());
        map.put("function-available", new FunctionAvailableFunction());
        map.put("unparsed-entity-uri", new UnparsedEntityUriFunction());
        
        this.xsltFunctions = Collections.unmodifiableMap(map);
    }

    @Override
    public boolean hasFunction(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            if (xsltFunctions.containsKey(localName)) {
                return true;
            }
        }
        return CoreFunctionLibrary.INSTANCE.hasFunction(namespaceURI, localName);
    }

    @Override
    public XPathValue invokeFunction(String namespaceURI, String localName, 
                                     List<XPathValue> args, XPathContext context) 
            throws XPathException {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return f.evaluate(args, context);
            }
        }
        return CoreFunctionLibrary.INSTANCE.invokeFunction(namespaceURI, localName, args, context);
    }

    @Override
    public int getArgumentCount(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            Function f = xsltFunctions.get(localName);
            if (f != null) {
                return -1; // Variable args for most XSLT functions
            }
        }
        return CoreFunctionLibrary.INSTANCE.getArgumentCount(namespaceURI, localName);
    }

    // ========================================================================
    // XSLT Function Implementations
    // ========================================================================

    /** current() - Returns the current node being processed. */
    private static class CurrentFunction implements Function {
        @Override
        public String getName() { return "current"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 0; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node = context.getContextNode();
            if (node == null) {
                return XPathNodeSet.empty();
            }
            List<XPathNode> nodes = new ArrayList<>();
            nodes.add(node);
            return new XPathNodeSet(nodes);
        }
    }

    /** key(name, value) - Returns nodes matching a key definition. */
    private static class KeyFunction implements Function {
        @Override
        public String getName() { return "key"; }
        
        @Override
        public int getMinArgs() { return 2; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // TODO: Implement proper key lookup
            return XPathNodeSet.empty();
        }
    }

    /** document(uri, base?) - Loads an external document. */
    private static class DocumentFunction implements Function {
        @Override
        public String getName() { return "document"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 2; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // TODO: Implement document loading
            return XPathNodeSet.empty();
        }
    }

    /** format-number(number, format, decimal-format?) - Formats a number. */
    private static class FormatNumberFunction implements Function {
        @Override
        public String getName() { return "format-number"; }
        
        @Override
        public int getMinArgs() { return 2; }
        
        @Override
        public int getMaxArgs() { return 3; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            double number = args.get(0).asNumber();
            String pattern = args.get(1).asString();
            
            try {
                java.text.DecimalFormat df = new java.text.DecimalFormat(pattern);
                return XPathString.of(df.format(number));
            } catch (IllegalArgumentException e) {
                if (number == Math.floor(number) && !Double.isInfinite(number)) {
                    return XPathString.of(String.valueOf((long) number));
                }
                return XPathString.of(String.valueOf(number));
            }
        }
    }

    /** generate-id(node?) - Generates a unique ID for a node. */
    private static class GenerateIdFunction implements Function {
        @Override
        public String getName() { return "generate-id"; }
        
        @Override
        public int getMinArgs() { return 0; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode node;
            if (args.isEmpty()) {
                node = context.getContextNode();
            } else {
                XPathValue arg = args.get(0);
                if (!arg.isNodeSet()) {
                    throw new XPathException("generate-id() argument must be a node-set");
                }
                XPathNodeSet ns = (XPathNodeSet) arg;
                if (ns.isEmpty()) {
                    return XPathString.of("");
                }
                node = ns.first();
            }
            
            if (node == null) {
                return XPathString.of("");
            }
            return XPathString.of("id" + node.getDocumentOrder());
        }
    }

    /** system-property(name) - Returns XSLT processor properties. */
    private static class SystemPropertyFunction implements Function {
        @Override
        public String getName() { return "system-property"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            if ("xsl:version".equals(name)) {
                return XPathString.of("1.0");
            }
            if ("xsl:vendor".equals(name)) {
                return XPathString.of("Gonzalez XSLT");
            }
            if ("xsl:vendor-url".equals(name)) {
                return XPathString.of("https://www.nongnu.org/gonzalez/");
            }
            
            return XPathString.of("");
        }
    }

    /** element-available(name) - Tests if an XSLT element is available. */
    private static class ElementAvailableFunction implements Function {
        @Override
        public String getName() { return "element-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            
            if (name.startsWith("xsl:")) {
                String localName = name.substring(4);
                switch (localName) {
                    case "apply-imports":
                    case "apply-templates":
                    case "attribute":
                    case "attribute-set":
                    case "call-template":
                    case "choose":
                    case "comment":
                    case "copy":
                    case "copy-of":
                    case "decimal-format":
                    case "element":
                    case "fallback":
                    case "for-each":
                    case "if":
                    case "import":
                    case "include":
                    case "key":
                    case "message":
                    case "namespace-alias":
                    case "number":
                    case "otherwise":
                    case "output":
                    case "param":
                    case "preserve-space":
                    case "processing-instruction":
                    case "sort":
                    case "strip-space":
                    case "stylesheet":
                    case "template":
                    case "text":
                    case "transform":
                    case "value-of":
                    case "variable":
                    case "when":
                    case "with-param":
                        return XPathBoolean.TRUE;
                    default:
                        return XPathBoolean.FALSE;
                }
            }
            
            return XPathBoolean.FALSE;
        }
    }

    /** function-available(name) - Tests if a function is available. */
    private static class FunctionAvailableFunction implements Function {
        @Override
        public String getName() { return "function-available"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String name = args.get(0).asString();
            boolean available = INSTANCE.hasFunction(null, name);
            return available ? XPathBoolean.TRUE : XPathBoolean.FALSE;
        }
    }

    /** unparsed-entity-uri(name) - Returns the URI of an unparsed entity. */
    private static class UnparsedEntityUriFunction implements Function {
        @Override
        public String getName() { return "unparsed-entity-uri"; }
        
        @Override
        public int getMinArgs() { return 1; }
        
        @Override
        public int getMaxArgs() { return 1; }
        
        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Would need access to DTD declarations
            return XPathString.of("");
        }
    }
}
