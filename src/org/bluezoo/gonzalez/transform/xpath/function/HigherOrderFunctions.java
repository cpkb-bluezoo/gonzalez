/*
 * HigherOrderFunctions.java
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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.transform.Templates;
import javax.xml.transform.stream.StreamSource;

import org.bluezoo.gonzalez.transform.GonzalezTemplates;
import org.bluezoo.gonzalez.transform.GonzalezTransformerFactory;
import org.bluezoo.gonzalez.transform.ast.XSLTNode;
import org.bluezoo.gonzalez.transform.compiler.CompiledPackage;
import org.bluezoo.gonzalez.transform.compiler.CompiledStylesheet;
import org.bluezoo.gonzalez.transform.compiler.GlobalVariable;
import org.bluezoo.gonzalez.transform.compiler.OutputProperties;
import org.bluezoo.gonzalez.transform.compiler.PackageResolver;
import org.bluezoo.gonzalez.transform.compiler.SequenceBuilderOutputHandler;
import org.bluezoo.gonzalez.transform.compiler.TemplateRule;
import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.BufferOutputHandler;
import org.bluezoo.gonzalez.transform.runtime.OutputHandler;
import org.bluezoo.gonzalez.transform.runtime.SAXEventBuffer;
import org.bluezoo.gonzalez.transform.runtime.TemplateMatcher;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.runtime.XMLWriterOutputHandler;
import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathResultTreeFragment;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import org.xml.sax.SAXException;

/**
 * XPath 3.0/3.1 higher-order functions: fold-left, fold-right, for-each,
 * filter, for-each-pair, sort, apply, and fn:transform.
 *
 * @author Chris Burdess
 */
final class HigherOrderFunctions {

    private HigherOrderFunctions() {
    }

    static Function foldLeft() {
        return new FoldLeftFunction();
    }

    static Function foldRight() {
        return new FoldRightFunction();
    }

    static Function forEach() {
        return new ForEachFunction();
    }

    static Function filter() {
        return new FilterFunction();
    }

    static Function forEachPair() {
        return new ForEachPairFunction();
    }

    static Function sort() {
        return new SortFunction();
    }

    static Function apply() {
        return new ApplyFunction();
    }

    static Function transform() {
        return new TransformFunction();
    }

    /**
     * XPath 3.0 fn:fold-left($seq, $zero, $f) — processes items left to right.
     * Applies function $f to accumulator and each item: $f($accumulator, $item).
     */
    private static class FoldLeftFunction implements Function {
        @Override
        public String getName() {
            return "fold-left";
        }

        @Override
        public int getMinArgs() {
            return 3;
        }

        @Override
        public int getMaxArgs() {
            return 3;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue accumulator = args.get(1);
            XPathValue funcItem = args.get(2);

            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
                callArgs.add(accumulator);
                callArgs.add(item);
                accumulator = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "fold-left");
            }
            return accumulator;
        }
    }

    /**
     * XPath 3.0 fn:fold-right($seq, $zero, $f) — processes items right to left.
     * Applies function $f to each item and accumulator: $f($item, $accumulator).
     */
    private static class FoldRightFunction implements Function {
        @Override
        public String getName() {
            return "fold-right";
        }

        @Override
        public int getMinArgs() {
            return 3;
        }

        @Override
        public int getMaxArgs() {
            return 3;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue accumulator = args.get(1);
            XPathValue funcItem = args.get(2);

            // Collect items to process right-to-left
            List<XPathValue> items = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                items.add(it.next());
            }
            for (int i = items.size() - 1; i >= 0; i--) {
                List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
                callArgs.add(items.get(i));
                callArgs.add(accumulator);
                accumulator = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "fold-right");
            }
            return accumulator;
        }
    }

    /**
     * XPath 3.0 fn:for-each($seq, $action) — applies a function to every item in a sequence.
     */
    private static class ForEachFunction implements Function {
        @Override
        public String getName() {
            return "for-each";
        }

        @Override
        public int getMinArgs() {
            return 2;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue funcItem = args.get(1);

            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                callArgs.add(item);
                XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "for-each");
                // Flatten sequences into the result
                if (result instanceof XPathSequence) {
                    for (XPathValue v : (XPathSequence) result) {
                        results.add(v);
                    }
                } else {
                    results.add(result);
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (results.size() == 1) {
                return results.get(0);
            }
            return new XPathSequence(results);
        }
    }

    /**
     * XPath 3.0 fn:filter($seq, $f) — returns items for which the function returns true.
     */
    private static class FilterFunction implements Function {
        @Override
        public String getName() {
            return "filter";
        }

        @Override
        public int getMinArgs() {
            return 2;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);
            XPathValue funcItem = args.get(1);

            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                XPathValue item = it.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                callArgs.add(item);
                XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "filter");
                if (result.asBoolean()) {
                    results.add(item);
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (results.size() == 1) {
                return results.get(0);
            }
            return new XPathSequence(results);
        }
    }

    /**
     * XPath 3.0 fn:for-each-pair($seq1, $seq2, $f) — applies $f to corresponding pairs.
     */
    private static class ForEachPairFunction implements Function {
        @Override
        public String getName() {
            return "for-each-pair";
        }

        @Override
        public int getMinArgs() {
            return 3;
        }

        @Override
        public int getMaxArgs() {
            return 3;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq1 = args.get(0);
            XPathValue seq2 = args.get(1);
            XPathValue funcItem = args.get(2);

            List<XPathValue> results = new ArrayList<XPathValue>();
            Iterator<XPathValue> it1 = seq1.sequenceIterator();
            Iterator<XPathValue> it2 = seq2.sequenceIterator();
            while (it1.hasNext() && it2.hasNext()) {
                XPathValue item1 = it1.next();
                XPathValue item2 = it2.next();
                List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
                callArgs.add(item1);
                callArgs.add(item2);
                XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "for-each-pair");
                if (result instanceof XPathSequence) {
                    for (XPathValue v : (XPathSequence) result) {
                        results.add(v);
                    }
                } else {
                    results.add(result);
                }
            }
            if (results.isEmpty()) {
                return XPathSequence.EMPTY;
            }
            if (results.size() == 1) {
                return results.get(0);
            }
            return new XPathSequence(results);
        }
    }

    /**
     * XPath 3.1 fn:sort($input) or fn:sort($input, $collation, $key) — sorts a sequence.
     * The 1-arg form sorts by string value; the 3-arg form uses a key function.
     */
    private static class SortFunction implements Function {
        @Override
        public String getName() {
            return "sort";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 3;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue seq = args.get(0);

            List<XPathValue> items = new ArrayList<XPathValue>();
            Iterator<XPathValue> it = seq.sequenceIterator();
            while (it.hasNext()) {
                items.add(it.next());
            }

            if (items.isEmpty()) {
                return XPathSequence.EMPTY;
            }

            // Get collation from 2nd argument or use default
            final Collation collation;
            if (args.size() >= 2) {
                String collUri = args.get(1).asString();
                if (collUri != null && !collUri.isEmpty()) {
                    collation = Collation.forUri(collUri);
                } else {
                    String defaultUri = context.getDefaultCollation();
                    collation = Collation.forUri(defaultUri);
                }
            } else {
                String defaultUri = context.getDefaultCollation();
                collation = Collation.forUri(defaultUri);
            }

            final XPathValue keyFunc;
            if (args.size() >= 3) {
                keyFunc = args.get(2);
            } else {
                keyFunc = null;
            }

            // Compute sort keys
            final List<String> keys = new ArrayList<String>(items.size());
            for (int i = 0; i < items.size(); i++) {
                if (keyFunc != null) {
                    List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                    callArgs.add(items.get(i));
                    XPathValue keyVal = UserFunctionInvoker.invokeFunctionItem(keyFunc, callArgs, context, "sort");
                    keys.add(keyVal.asString());
                } else {
                    keys.add(items.get(i).asString());
                }
            }

            // Build index array and sort by keys using collation
            Integer[] indices = new Integer[items.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = Integer.valueOf(i);
            }
            Arrays.sort(indices, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    String keyA = keys.get(a.intValue());
                    String keyB = keys.get(b.intValue());
                    return collation.compare(keyA, keyB);
                }
            });

            List<XPathValue> sorted = new ArrayList<XPathValue>(items.size());
            for (int i = 0; i < indices.length; i++) {
                sorted.add(items.get(indices[i].intValue()));
            }

            if (sorted.size() == 1) {
                return sorted.get(0);
            }
            return new XPathSequence(sorted);
        }
    }

    /**
     * XPath 3.1 fn:apply($function, $array) - invokes a function with arguments
     * supplied as an array.
     */
    private static class ApplyFunction implements Function {
        @Override
        public String getName() {
            return "apply";
        }

        @Override
        public int getMinArgs() {
            return 2;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue funcItem = args.get(0);
            XPathValue arrayArg = args.get(1);
            if (!(arrayArg instanceof XPathArray)) {
                throw new XPathException("XPTY0004: Second argument to fn:apply must be an array");
            }
            XPathArray array = (XPathArray) arrayArg;
            List<XPathValue> callArgs = new ArrayList<XPathValue>(array.size());
            for (XPathValue member : array.members()) {
                callArgs.add(member);
            }
            return UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "apply");
        }
    }

    /**
     * XPath 3.0 fn:transform($options as map(*)) as map(*).
     *
     * <p>Invokes a dynamic XSLT transformation and returns a map containing
     * the transformation results. Supports stylesheet-location, package-name,
     * initial-template, initial-match-selection, source-node, and various
     * delivery formats.
     */
    private static class TransformFunction implements Function {
        @Override
        public String getName() {
            return "transform";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context)
                throws XPathException {
            XPathValue optionsArg = args.get(0);
            if (!(optionsArg instanceof XPathMap)) {
                throw new XPathException("FOXT0001: fn:transform argument must be a map");
            }
            XPathMap options = (XPathMap) optionsArg;

            String stylesheetLocation = stringOption(options, "stylesheet-location");
            String packageName = stringOption(options, "package-name");
            String packageVersion = stringOption(options, "package-version");
            String deliveryFormat = stringOption(options, "delivery-format");
            if (deliveryFormat == null) {
                deliveryFormat = "document";
            }
            XPathValue initialTemplateVal = options.get("initial-template");
            XPathValue initialMatchSelection = options.get("initial-match-selection");
            XPathValue sourceNode = options.get("source-node");

            String baseUri = context.getStaticBaseURI();

            try {
                CompiledStylesheet stylesheet = compileStylesheet(
                    stylesheetLocation, packageName, packageVersion,
                    baseUri, context);

                return executeTransformation(stylesheet, initialTemplateVal,
                    initialMatchSelection, sourceNode, deliveryFormat,
                    baseUri, context);
            } catch (SAXException e) {
                throw new XPathException("FOXT0003: " + e.getMessage());
            } catch (Exception e) {
                throw new XPathException("FOXT0003: Transform failed: " + e.getMessage());
            }
        }

        private static String stringOption(XPathMap options, String key) {
            XPathValue val = options.get(key);
            if (val == null) {
                return null;
            }
            if (val instanceof XPathSequence
                    && ((XPathSequence) val).isEmpty()) {
                return null;
            }
            return val.asString();
        }

        private static CompiledStylesheet compileStylesheet(
                String stylesheetLocation, String packageName,
                String packageVersion, String baseUri, XPathContext context)
                throws Exception {
            if (stylesheetLocation != null) {
                String resolvedUri = resolveUri(stylesheetLocation, baseUri);
                GonzalezTransformerFactory factory = new GonzalezTransformerFactory();
                // Propagate package resolver from calling context
                if (context instanceof TransformContext) {
                    CompiledStylesheet callerSheet =
                        ((TransformContext) context).getStylesheet();
                    if (callerSheet != null
                            && callerSheet.getPackageResolver() != null) {
                        factory.setPackageResolver(
                            callerSheet.getPackageResolver());
                    }
                }
                StreamSource source = new StreamSource(resolvedUri);
                Templates templates = factory.newTemplates(source);
                return ((GonzalezTemplates) templates)
                    .getStylesheet();
            }
            if (packageName != null) {
                return compileFromPackage(packageName, packageVersion,
                    baseUri, context);
            }
            throw new SAXException("FOXT0001: stylesheet-location or " +
                "package-name is required");
        }

        private static CompiledStylesheet compileFromPackage(
                String packageName, String packageVersion,
                String baseUri, XPathContext context) throws Exception {
            if (packageVersion == null) {
                packageVersion = "*";
            }
            PackageResolver resolver = null;
            if (context instanceof TransformContext) {
                CompiledStylesheet callerSheet =
                    ((TransformContext) context).getStylesheet();
                if (callerSheet != null) {
                    resolver = callerSheet.getPackageResolver();
                }
            }
            if (resolver == null) {
                resolver = new PackageResolver();
            }
            // Register package locations from the test catalog
            // The resolver already has locations from the calling context
            CompiledPackage pkg = resolver.resolve(
                packageName, packageVersion, baseUri);
            return pkg.getStylesheet();
        }

        private static String resolveUri(String href, String baseUri) {
            if (href == null) {
                return null;
            }
            try {
                URI hrefUri = new URI(href);
                if (hrefUri.isAbsolute()) {
                    return href;
                }
            } catch (URISyntaxException e) {
                // Not valid URI, treat as relative
            }
            if (baseUri != null && !baseUri.isEmpty()) {
                try {
                    URI base = new URI(baseUri);
                    URI resolved = base.resolve(href);
                    return resolved.toString();
                } catch (URISyntaxException e) {
                    // Fall through
                }
            }
            return href;
        }

        private static XPathValue executeTransformation(
                CompiledStylesheet stylesheet, XPathValue initialTemplateVal,
                XPathValue initialMatchSelection, XPathValue sourceNode,
                String deliveryFormat, String baseUri, XPathContext context)
                throws Exception {
            boolean raw = "raw".equals(deliveryFormat);
            boolean serialized = "serialized".equals(deliveryFormat);

            SequenceBuilderOutputHandler seqOutput = new SequenceBuilderOutputHandler();
            OutputHandler primaryOutput;
            ByteArrayOutputStream serializedStream = null;

            if (serialized) {
                serializedStream = new ByteArrayOutputStream();
                OutputProperties props = stylesheet.getOutputProperties();
                primaryOutput = new XMLWriterOutputHandler(serializedStream, props);
            } else {
                primaryOutput = seqOutput;
            }

            // Set up secondary output collector
            Map<String, OutputHandler> secondaryCollector =
                new LinkedHashMap<String, OutputHandler>();

            // Build context for inner transformation
            TemplateMatcher matcher = new TemplateMatcher(stylesheet);
            BasicTransformContext innerContext = new BasicTransformContext(
                stylesheet, null, matcher, primaryOutput, null);
            innerContext.setResultDocumentCollector(secondaryCollector);

            // Initialize global variables
            initializeGlobals(stylesheet, innerContext);

            // For raw delivery, skip document wrapping so function items
            // and other XDM values are preserved directly
            if (!raw) {
                primaryOutput.startDocument();
            }

            if (initialTemplateVal != null) {
                executeInitialTemplate(stylesheet, initialTemplateVal,
                    innerContext, primaryOutput, sourceNode);
            } else if (initialMatchSelection != null) {
                executeInitialMatchSelection(stylesheet, initialMatchSelection,
                    matcher, innerContext, primaryOutput);
            } else if (sourceNode != null) {
                executeWithSourceNode(stylesheet, sourceNode,
                    matcher, innerContext, primaryOutput);
            } else {
                throw new SAXException("FOXT0001: initial-template, " +
                    "initial-match-selection, or source-node is required");
            }

            if (!raw) {
                primaryOutput.endDocument();
            }

            // Build result map
            Map<String, XPathValue> resultMap =
                new LinkedHashMap<String, XPathValue>();
            if (serialized) {
                resultMap.put("output",
                    XPathString.of(serializedStream.toString("UTF-8")));
            } else if (raw) {
                XPathValue seq = seqOutput.getSequence();
                resultMap.put("output", seq);
            } else {
                XPathValue seq = seqOutput.getSequence();
                XPathResultTreeFragment rtf = wrapAsDocument(seq);
                resultMap.put("output", rtf);
            }

            // Add secondary outputs
            for (Map.Entry<String, OutputHandler> entry
                    : secondaryCollector.entrySet()) {
                String uri = entry.getKey();
                OutputHandler handler = entry.getValue();
                if (serialized) {
                    // For serialized, we need to serialize the buffered content
                    XPathValue secVal = replayToString(handler, stylesheet);
                    resultMap.put(uri, secVal);
                } else {
                    XPathValue secVal = replayToSequence(handler);
                    if (raw) {
                        resultMap.put(uri, secVal);
                    } else {
                        resultMap.put(uri, wrapAsDocument(secVal));
                    }
                }
            }

            return new XPathMap(resultMap);
        }

        private static void initializeGlobals(CompiledStylesheet stylesheet,
                BasicTransformContext context) throws SAXException {
            context.setThrowOnUndefinedVariable(true);
            List<GlobalVariable> allVars = stylesheet.getGlobalVariables();
            Set<String> evaluated = new HashSet<String>();
            boolean progress = true;
            while (progress) {
                progress = false;
                for (GlobalVariable var : allVars) {
                    String key = var.getName();
                    if (evaluated.contains(key)) {
                        continue;
                    }
                    try {
                        XPathValue value = null;
                        if (var.getSelectExpr() != null) {
                            value = var.getSelectExpr().evaluate(context);
                        } else if (var.getContent() != null) {
                            SequenceBuilderOutputHandler varOutput =
                                new SequenceBuilderOutputHandler();
                            var.getContent().execute(context, varOutput);
                            value = varOutput.getSequence();
                        } else if (var.isParam() && !var.isRequired()) {
                            value = XPathString.of("");
                        }
                        if (value != null) {
                            context.setVariable(var.getNamespaceURI(),
                                var.getLocalName(), value);
                            evaluated.add(key);
                            progress = true;
                        }
                    } catch (Exception e) {
                        // Forward reference - try later
                    }
                }
            }
            context.setThrowOnUndefinedVariable(false);
        }

        private static void executeInitialTemplate(
                CompiledStylesheet stylesheet, XPathValue templateQName,
                BasicTransformContext context, OutputHandler output,
                XPathValue sourceNode) throws SAXException {
            String templateName;
            if (templateQName instanceof XPathQName) {
                XPathQName qn = (XPathQName) templateQName;
                String nsUri = qn.getNamespaceURI();
                String local = qn.getLocalName();
                if (nsUri != null && !nsUri.isEmpty()) {
                    templateName = "{" + nsUri + "}" + local;
                } else {
                    templateName = local;
                }
            } else {
                templateName = templateQName.asString();
            }

            TemplateRule template = stylesheet.getNamedTemplate(templateName);
            if (template == null) {
                throw new SAXException("FOXT0003: Initial template '" +
                    templateName + "' not found");
            }

            TransformContext execContext = context.pushVariableScope();
            if (sourceNode != null && sourceNode instanceof XPathNode) {
                execContext = execContext.withContextNode((XPathNode) sourceNode);
            } else if (sourceNode != null && sourceNode instanceof XPathNodeSet) {
                XPathNodeSet ns = (XPathNodeSet) sourceNode;
                if (!ns.isEmpty()) {
                    execContext = execContext.withContextNode(ns.getNodes().get(0));
                }
            } else {
                execContext = execContext.withContextNode(null);
                if (execContext instanceof BasicTransformContext) {
                    ((BasicTransformContext) execContext)
                        .setContextItemUndefined(true);
                }
            }

            XSLTNode body = template.getBody();
            if (body != null) {
                body.execute(execContext, output);
            }
        }

        private static void executeInitialMatchSelection(
                CompiledStylesheet stylesheet, XPathValue selection,
                TemplateMatcher matcher, BasicTransformContext context,
                OutputHandler output) throws SAXException {
            List<XPathValue> items = new ArrayList<XPathValue>();
            if (selection instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) selection) {
                    items.add(item);
                }
            } else if (selection instanceof XPathNodeSet) {
                for (XPathNode node : ((XPathNodeSet) selection).getNodes()) {
                    items.add(new XPathNodeSet(
                        Collections.singletonList(node)));
                }
            } else {
                items.add(selection);
            }

            int position = 1;
            int size = items.size();
            for (XPathValue item : items) {
                if (item instanceof XPathNode) {
                    XPathNode node = (XPathNode) item;
                    TransformContext nodeCtx = context
                        .withXsltCurrentNode(node)
                        .withPositionAndSize(position, size);
                    TemplateRule rule = matcher.findMatch(node, null, nodeCtx);
                    if (rule != null && rule.getBody() != null) {
                        TransformContext execCtx = nodeCtx
                            .pushVariableScope()
                            .withCurrentTemplateRule(rule);
                        rule.getBody().execute(execCtx, output);
                    }
                } else if (item instanceof XPathNodeSet) {
                    XPathNodeSet ns = (XPathNodeSet) item;
                    if (!ns.isEmpty()) {
                        XPathNode node = ns.getNodes().get(0);
                        TransformContext nodeCtx = context
                            .withXsltCurrentNode(node)
                            .withPositionAndSize(position, size);
                        TemplateRule rule = matcher.findMatch(
                            node, null, nodeCtx);
                        if (rule != null && rule.getBody() != null) {
                            TransformContext execCtx = nodeCtx
                                .pushVariableScope()
                                .withCurrentTemplateRule(rule);
                            rule.getBody().execute(execCtx, output);
                        }
                    }
                } else {
                    TransformContext atomicCtx = context
                        .withContextItem(item)
                        .withPositionAndSize(position, size);
                    TemplateRule rule = matcher.findMatchForAtomicValue(
                        item, null, atomicCtx);
                    if (rule != null && rule.getBody() != null) {
                        TransformContext execCtx = atomicCtx
                            .pushVariableScope()
                            .withCurrentTemplateRule(rule);
                        rule.getBody().execute(execCtx, output);
                    }
                }
                position++;
            }
        }

        private static void executeWithSourceNode(
                CompiledStylesheet stylesheet, XPathValue sourceNode,
                TemplateMatcher matcher, BasicTransformContext context,
                OutputHandler output) throws SAXException {
            XPathNode node = null;
            if (sourceNode instanceof XPathNode) {
                node = (XPathNode) sourceNode;
            } else if (sourceNode instanceof XPathNodeSet) {
                XPathNodeSet ns = (XPathNodeSet) sourceNode;
                if (!ns.isEmpty()) {
                    node = ns.getNodes().get(0);
                }
            }
            if (node == null) {
                throw new SAXException("FOXT0003: source-node must be a node");
            }
            // Apply templates to the source node (default mode)
            TransformContext nodeCtx = context.withXsltCurrentNode(node)
                .withPositionAndSize(1, 1);
            TemplateRule rule = matcher.findMatch(node, null, nodeCtx);
            if (rule != null && rule.getBody() != null) {
                TransformContext execCtx = nodeCtx.pushVariableScope()
                    .withCurrentTemplateRule(rule);
                rule.getBody().execute(execCtx, output);
            }
        }

        private static XPathResultTreeFragment wrapAsDocument(XPathValue seq)
                throws SAXException {
            SAXEventBuffer buffer = new SAXEventBuffer();
            BufferOutputHandler bufOutput = new BufferOutputHandler(buffer);
            bufOutput.startDocument();
            if (seq instanceof XPathResultTreeFragment) {
                ((XPathResultTreeFragment) seq).replayToOutput(bufOutput);
            } else if (seq instanceof XPathSequence) {
                for (XPathValue item : (XPathSequence) seq) {
                    if (item instanceof XPathResultTreeFragment) {
                        ((XPathResultTreeFragment) item).replayToOutput(bufOutput);
                    } else {
                        bufOutput.characters(item.asString());
                    }
                }
            } else if (seq != null) {
                bufOutput.characters(seq.asString());
            }
            bufOutput.endDocument();
            return new XPathResultTreeFragment(buffer, null);
        }

        private static XPathValue replayToSequence(OutputHandler handler)
                throws SAXException {
            if (handler instanceof BufferOutputHandler) {
                BufferOutputHandler buf = (BufferOutputHandler) handler;
                SAXEventBuffer buffer = buf.getBuffer();
                return new XPathResultTreeFragment(buffer, null);
            }
            return XPathSequence.EMPTY;
        }

        private static XPathValue replayToString(OutputHandler handler,
                CompiledStylesheet stylesheet) throws SAXException {
            if (handler instanceof BufferOutputHandler) {
                BufferOutputHandler buf = (BufferOutputHandler) handler;
                SAXEventBuffer buffer = buf.getBuffer();
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    OutputProperties props = stylesheet.getOutputProperties();
                    XMLWriterOutputHandler serOutput =
                        new XMLWriterOutputHandler(baos, props);
                    XPathResultTreeFragment rtf =
                        new XPathResultTreeFragment(buffer, null);
                    serOutput.startDocument();
                    rtf.replayToOutput(serOutput);
                    serOutput.endDocument();
                    return XPathString.of(baos.toString("UTF-8"));
                } catch (Exception e) {
                    return XPathString.of("");
                }
            }
            return XPathString.of("");
        }
    }
}
