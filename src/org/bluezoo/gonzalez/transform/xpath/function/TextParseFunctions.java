/*
 * TextParseFunctions.java
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.SAXException;

import org.bluezoo.gonzalez.transform.runtime.DocumentLoader;
import org.bluezoo.gonzalez.transform.runtime.OutputHandlerUtils;
import org.bluezoo.gonzalez.transform.runtime.StreamingNode;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.NodeType;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

import org.bluezoo.gonzalez.transform.xpath.function.Function;

/**
 * XSLT/XPath text and parse functions (unparsed-text, parse-xml, analyze-string, etc.).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class TextParseFunctions {

    private static final int IO_BUFFER_SIZE = 8192;

    private static final Map<String, java.util.regex.Pattern> regexCache = new HashMap<>();

    private TextParseFunctions() {
    }

    static Function unparsedText() {
        return new UnparsedTextFunction();
    }

    static Function unparsedTextAvailable() {
        return new UnparsedTextAvailableFunction();
    }

    static Function unparsedTextLines() {
        return new UnparsedTextLinesFunction();
    }

    static Function parseXml() {
        return new ParseXmlFunction();
    }

    static Function parseXmlFragment() {
        return new ParseXmlFragmentFunction();
    }

    static Function analyzeString() {
        return new AnalyzeStringFunction();
    }

    static Function unparsedEntityUri() {
        return new UnparsedEntityUriFunction();
    }

    static Function unparsedEntityPublicId() {
        return new UnparsedEntityPublicIdFunction();
    }

    /**
     * Decodes raw bytes to a string, handling BOM detection and stripping.
     * If no explicit encoding is given, detects encoding from BOM or XML
     * declaration, falling back to UTF-8.
     */
    static String decodeTextBytes(byte[] bytes, String explicitEncoding) {
        int offset = 0;
        String detectedEncoding = null;

        if (explicitEncoding == null) {
            // Detect encoding from BOM
            if (bytes.length >= 2) {
                if (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                    detectedEncoding = "UTF-16BE";
                    offset = 2;
                } else if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                    detectedEncoding = "UTF-16LE";
                    offset = 2;
                } else if (bytes.length >= 3
                        && bytes[0] == (byte) 0xEF
                        && bytes[1] == (byte) 0xBB
                        && bytes[2] == (byte) 0xBF) {
                    detectedEncoding = "UTF-8";
                    offset = 3;
                }
            }
            if (detectedEncoding == null) {
                // No BOM: try to detect XML declaration encoding
                detectedEncoding = detectXmlEncoding(bytes);
                if (detectedEncoding == null) {
                    detectedEncoding = "UTF-8";
                }
            }
        } else {
            detectedEncoding = explicitEncoding;
            // Strip BOM even when encoding is explicit
            if (bytes.length >= 2) {
                if (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                    offset = 2;
                } else if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                    offset = 2;
                } else if (bytes.length >= 3
                        && bytes[0] == (byte) 0xEF
                        && bytes[1] == (byte) 0xBB
                        && bytes[2] == (byte) 0xBF) {
                    offset = 3;
                }
            }
        }

        Charset charset = Charset.forName(detectedEncoding);
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    /**
     * Scans the initial bytes (assumed ASCII-compatible) for an XML declaration
     * with an encoding attribute, e.g. {@code <?xml version="1.0" encoding="iso-8859-1"?>}.
     * Returns the encoding name if found, or null.
     */
    private static String detectXmlEncoding(byte[] bytes) {
        if (bytes.length < 20) {
            return null;
        }
        // Quick check: must start with "<?xml" in ASCII
        if (bytes[0] != '<' || bytes[1] != '?' || bytes[2] != 'x'
                || bytes[3] != 'm' || bytes[4] != 'l') {
            return null;
        }
        // Find the end of the XML declaration (?>)
        int limit = Math.min(bytes.length, 200);
        String header;
        try {
            header = new String(bytes, 0, limit, "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        int declEnd = header.indexOf("?>");
        if (declEnd < 0) {
            return null;
        }
        String decl = header.substring(0, declEnd);
        int encIdx = decl.indexOf("encoding");
        if (encIdx < 0) {
            return null;
        }
        int eqIdx = decl.indexOf('=', encIdx + 8);
        if (eqIdx < 0) {
            return null;
        }
        // Skip whitespace after '='
        int pos = eqIdx + 1;
        while (pos < decl.length() && decl.charAt(pos) == ' ') {
            pos++;
        }
        if (pos >= decl.length()) {
            return null;
        }
        char quote = decl.charAt(pos);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        pos++;
        int endQuote = decl.indexOf(quote, pos);
        if (endQuote < 0) {
            return null;
        }
        return decl.substring(pos, endQuote);
    }

    /**
     * Extracts the charset parameter from an HTTP Content-Type header value.
     * For example, from "text/html; charset=iso-8859-1" returns "iso-8859-1".
     * Returns null if no charset is specified.
     */
    private static String extractCharsetFromContentType(String contentType) {
        String lower = contentType.toLowerCase();
        int idx = lower.indexOf("charset=");
        if (idx < 0) {
            return null;
        }
        int start = idx + 8;
        if (start >= contentType.length()) {
            return null;
        }
        // Charset value may be quoted
        int end;
        if (contentType.charAt(start) == '"') {
            start++;
            end = contentType.indexOf('"', start);
            if (end < 0) {
                end = contentType.length();
            }
        } else {
            end = start;
            while (end < contentType.length()) {
                char c = contentType.charAt(end);
                if (c == ';' || c == ' ' || c == '\t') {
                    break;
                }
                end++;
            }
        }
        if (start >= end) {
            return null;
        }
        return contentType.substring(start, end).trim();
    }

    private static class UnparsedEntityUriFunction implements Function {
        @Override
        public String getName() {
            return "unparsed-entity-uri";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode docNode;
            if (args.size() > 1) {
                docNode = resolveDocNode(args.get(1), "XTDE1370", "unparsed-entity-uri");
            } else {
                XPathNode contextNode = context.getContextNode();
                if (contextNode == null) {
                    throw new XPathException("XTDE1370: unparsed-entity-uri() requires a context " +
                        "node, but the context item is absent");
                }
                docNode = contextNode.getRoot();
            }
            if (docNode == null || docNode.getNodeType() != NodeType.ROOT) {
                throw new XPathException("XTDE1370: The root of the tree containing the " +
                    "context node is not a document node");
            }
            String entityName = args.get(0).asString();
            StreamingNode streamDoc = unwrapToStreamingNode(docNode);
            if (streamDoc != null) {
                String[] entity = streamDoc.getUnparsedEntity(entityName);
                if (entity != null && entity[1] != null) {
                    String systemId = entity[1];
                    String docUri = streamDoc.getDocumentURI();
                    if (docUri != null && !docUri.isEmpty()) {
                        try {
                            URI base = new URI(docUri);
                            URI resolved = base.resolve(systemId);
                            return XPathString.of(resolved.toString());
                        } catch (Exception e) {
                            return XPathString.of(systemId);
                        }
                    }
                    return XPathString.of(systemId);
                }
            }
            return XPathString.of("");
        }
    }

    private static class UnparsedEntityPublicIdFunction implements Function {
        @Override
        public String getName() {
            return "unparsed-entity-public-id";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathNode docNode;
            if (args.size() > 1) {
                docNode = resolveDocNode(args.get(1), "XTDE1380", "unparsed-entity-public-id");
            } else {
                XPathNode contextNode = context.getContextNode();
                if (contextNode == null) {
                    throw new XPathException("XTDE1380: unparsed-entity-public-id() requires a context " +
                        "node, but the context item is absent");
                }
                docNode = contextNode.getRoot();
            }
            if (docNode == null || docNode.getNodeType() != NodeType.ROOT) {
                throw new XPathException("XTDE1380: The root of the tree containing the " +
                    "context node is not a document node");
            }
            String entityName = args.get(0).asString();
            StreamingNode streamDoc = unwrapToStreamingNode(docNode);
            if (streamDoc != null) {
                String[] entity = streamDoc.getUnparsedEntity(entityName);
                if (entity != null && entity[0] != null) {
                    return XPathString.of(entity[0]);
                }
            }
            return XPathString.of("");
        }
    }

    /**
     * Resolves a document node from a function argument, navigating to root if needed.
     */
    private static XPathNode resolveDocNode(XPathValue arg, String errorCode,
            String funcName) throws XPathException {
        XPathNode node = null;
        if (arg instanceof XPathNode) {
            node = (XPathNode) arg;
        } else if (arg instanceof XPathNodeSet) {
            XPathNodeSet ns = (XPathNodeSet) arg;
            List<XPathNode> nodes = ns.getNodes();
            if (nodes != null && !nodes.isEmpty()) {
                node = nodes.get(0);
            }
        }
        if (node == null) {
            throw new XPathException(errorCode + ": " + funcName +
                "() second argument must be a node");
        }
        return node.getRoot();
    }

    /**
     * Unwraps snapshot/copied node wrappers to find the underlying StreamingNode.
     */
    private static StreamingNode unwrapToStreamingNode(XPathNode node) {
        if (node instanceof StreamingNode) {
            return (StreamingNode) node;
        }
        if (node instanceof NodeSelectionFunctions.SnapshotAncestorNode) {
            XPathNode orig = ((NodeSelectionFunctions.SnapshotAncestorNode) node).getOriginal();
            return unwrapToStreamingNode(orig);
        }
        if (node instanceof SequenceFunctions.CopiedNode) {
            XPathNode orig = ((SequenceFunctions.CopiedNode) node).getOriginal();
            return unwrapToStreamingNode(orig);
        }
        return null;
    }

    private static class UnparsedTextFunction implements Function {
        @Override
        public String getName() {
            return "unparsed-text";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String href = args.get(0).asString();
            String explicitEncoding = args.size() > 1 ? args.get(1).asString() : null;

            if (href == null || href.isEmpty()) {
                throw new XPathException("FOUT1170: href argument to unparsed-text is empty");
            }

            try {
                String baseUri = context.getStaticBaseURI();
                URI uri;
                if (baseUri != null && !baseUri.isEmpty()) {
                    URI baseUriObj = new URI(baseUri);
                    uri = baseUriObj.resolve(href);
                } else {
                    uri = new URI(href);
                }

                byte[] bytes;
                Path path;
                String scheme = uri.getScheme();
                if ("file".equals(scheme)) {
                    path = Paths.get(uri);
                    bytes = Files.readAllBytes(path);
                } else if (scheme == null) {
                    path = Paths.get(href);
                    bytes = Files.readAllBytes(path);
                } else {
                    // Follow redirects (including HTTP→HTTPS)
                    String httpEncoding = null;
                    URL url = uri.toURL();
                    InputStream is;
                    int redirects = 0;
                    while (true) {
                        URLConnection urlConn = url.openConnection();
                        if (urlConn instanceof HttpURLConnection) {
                            HttpURLConnection httpConn = (HttpURLConnection) urlConn;
                            httpConn.setInstanceFollowRedirects(false);
                            int code = httpConn.getResponseCode();
                            if (code >= 300 && code < 400 && redirects < 5) {
                                String location = httpConn.getHeaderField("Location");
                                httpConn.disconnect();
                                if (location == null) {
                                    throw new IOException("Redirect with no Location header");
                                }
                                url = new URL(url, location);
                                redirects++;
                                continue;
                            }
                            // Extract charset from Content-Type if no explicit encoding
                            if (explicitEncoding == null) {
                                String contentType = httpConn.getContentType();
                                if (contentType != null) {
                                    httpEncoding = extractCharsetFromContentType(contentType);
                                }
                            }
                        }
                        is = urlConn.getInputStream();
                        break;
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[IO_BUFFER_SIZE];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    is.close();
                    bytes = baos.toByteArray();
                    // HTTP charset takes priority over BOM detection (per spec)
                    if (httpEncoding != null && explicitEncoding == null) {
                        explicitEncoding = httpEncoding;
                    }
                }

                String content = decodeTextBytes(bytes, explicitEncoding);
                // FOUT1190: NUL characters are not valid in text files
                if (content.indexOf('\u0000') >= 0) {
                    throw new XPathException("FOUT1190: Text file contains " +
                        "a NUL character (U+0000)");
                }
                return XPathString.of(content);
            } catch (XPathException e) {
                throw e;
            } catch (URISyntaxException e) {
                throw new XPathException("FOUT1170: Invalid URI: " + href);
            } catch (UnsupportedCharsetException e) {
                String enc = explicitEncoding != null ? explicitEncoding : "UTF-8";
                throw new XPathException("FOUT1190: Unsupported encoding: " + enc);
            } catch (IOException e) {
                throw new XPathException("FOUT1170: Cannot read resource: " + href + " - " + e.getMessage());
            }
        }
    }

    private static class UnparsedTextAvailableFunction implements Function {
        @Override
        public String getName() {
            return "unparsed-text-available";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String href = args.get(0).asString();
            String encoding = args.size() > 1 ? args.get(1).asString() : "UTF-8";

            if (href == null || href.isEmpty()) {
                return XPathBoolean.FALSE;
            }

            try {
                // Check if encoding is valid
                Charset.forName(encoding);

                // Resolve relative URI against static base URI
                String baseUri = context.getStaticBaseURI();
                URI uri;
                if (baseUri != null && !baseUri.isEmpty()) {
                    URI baseUriObj = new URI(baseUri);
                    uri = baseUriObj.resolve(href);
                } else {
                    uri = new URI(href);
                }

                // Check if file exists
                Path path;
                String scheme = uri.getScheme();
                if ("file".equals(scheme)) {
                    path = Paths.get(uri);
                    boolean available = Files.exists(path) && Files.isReadable(path);
                    return XPathBoolean.of(available);
                } else if (scheme == null) {
                    path = Paths.get(href);
                    boolean available = Files.exists(path) && Files.isReadable(path);
                    return XPathBoolean.of(available);
                } else {
                    // Check if the URI was declared as an available resource
                    // (e.g. by the test harness) before attempting network I/O
                    if (context instanceof org.bluezoo.gonzalez.transform.runtime.BasicTransformContext) {
                        org.bluezoo.gonzalez.transform.runtime.BasicTransformContext tc =
                            (org.bluezoo.gonzalez.transform.runtime.BasicTransformContext) context;
                        String uriStr = uri.toString();
                        if (tc.isResourceUriAvailable(uriStr)) {
                            return XPathBoolean.TRUE;
                        }
                    }

                    // For http/https, try to open connection (follow redirects)
                    try {
                        URL url = uri.toURL();
                        int redirects = 0;
                        while (redirects < 5) {
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("HEAD");
                            conn.setInstanceFollowRedirects(false);
                            int responseCode = conn.getResponseCode();
                            if (responseCode >= 300 && responseCode < 400) {
                                String location = conn.getHeaderField("Location");
                                conn.disconnect();
                                if (location == null) {
                                    return XPathBoolean.FALSE;
                                }
                                url = new URL(url, location);
                                redirects++;
                            } else {
                                conn.disconnect();
                                boolean success = responseCode >= 200 && responseCode < 300;
                                return XPathBoolean.of(success);
                            }
                        }
                        return XPathBoolean.FALSE;
                    } catch (IOException e) {
                        return XPathBoolean.FALSE;
                    } catch (ClassCastException e) {
                        return XPathBoolean.FALSE;
                    }
                }
            } catch (URISyntaxException e) {
                return XPathBoolean.FALSE;
            } catch (UnsupportedCharsetException e) {
                return XPathBoolean.FALSE;
            } catch (InvalidPathException e) {
                return XPathBoolean.FALSE;
            } catch (SecurityException e) {
                return XPathBoolean.FALSE;
            }
        }
    }

    private static class AnalyzeStringFunction implements Function {
        @Override
        public String getName() {
            return "analyze-string";
        }

        @Override
        public int getMinArgs() {
            return 2;
        }

        @Override
        public int getMaxArgs() {
            return 3;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String input = args.get(0) != null ? args.get(0).asString() : "";
            String pattern = args.get(1).asString();
            String flags = args.size() > 2 ? args.get(2).asString() : "";

            validateXsdRegex(pattern);

            int regexFlags = 0;
            if (flags.contains("i")) {
                regexFlags |= java.util.regex.Pattern.CASE_INSENSITIVE;
            }
            if (flags.contains("m")) {
                regexFlags |= java.util.regex.Pattern.MULTILINE;
            }
            if (flags.contains("s")) {
                regexFlags |= java.util.regex.Pattern.DOTALL;
            }
            if (flags.contains("x")) {
                regexFlags |= java.util.regex.Pattern.COMMENTS;
            }

            try {
                String cacheKey = pattern + "\0" + regexFlags;
                java.util.regex.Pattern p = regexCache.get(cacheKey);
                if (p == null) {
                    p = java.util.regex.Pattern.compile(pattern, regexFlags);
                    regexCache.put(cacheKey, p);
                }
                java.util.regex.Matcher m = p.matcher(input);

                StringBuilder xml = new StringBuilder();
                xml.append("<fn:analyze-string-result xmlns:fn=\"http://www.w3.org/2005/xpath-functions\">");

                int lastEnd = 0;
                while (m.find()) {
                    if (m.start() > lastEnd) {
                        xml.append("<fn:non-match>");
                        xml.append(escapeXml(input.substring(lastEnd, m.start())));
                        xml.append("</fn:non-match>");
                    }
                    xml.append("<fn:match>");
                    if (m.groupCount() > 0) {
                        for (int i = 1; i <= m.groupCount(); i++) {
                            String group = m.group(i);
                            if (group != null) {
                                xml.append("<fn:group nr=\"").append(i).append("\">");
                                xml.append(escapeXml(group));
                                xml.append("</fn:group>");
                            }
                        }
                    } else {
                        xml.append(escapeXml(m.group()));
                    }
                    xml.append("</fn:match>");
                    lastEnd = m.end();
                }
                if (lastEnd < input.length()) {
                    xml.append("<fn:non-match>");
                    xml.append(escapeXml(input.substring(lastEnd)));
                    xml.append("</fn:non-match>");
                }

                xml.append("</fn:analyze-string-result>");

                try {
                    XPathNode doc = DocumentLoader.loadDocumentFromString(
                        xml.toString(), null, null, null);
                    return XPathNodeSet.of(doc);
                } catch (SAXException e) {
                    throw new XPathException(
                        "FODC0006: analyze-string result parsing failed: " +
                        e.getMessage());
                }
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new XPathException("FORX0002: Invalid regular expression: " + pattern);
            }
        }

        private String escapeXml(String s) {
            return OutputHandlerUtils.escapeXmlAttribute(s);
        }
    }

    /**
     * Validates that a regex pattern conforms to XSD/XPath regex syntax.
     * Specifically checks for unescaped '}' outside character classes
     * that does not close a valid quantifier ({n}, {n,}, {n,m}).
     * Java regex accepts bare '}' as a literal, but XSD regex does not.
     */
    static void validateXsdRegex(String pattern) throws XPathException {
        int len = pattern.length();
        boolean inCharClass = false;
        int quantifierStart = -1;

        for (int i = 0; i < len; i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i++;
                continue;
            }
            if (inCharClass) {
                if (c == ']') {
                    inCharClass = false;
                }
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                continue;
            }
            if (c == '{') {
                quantifierStart = i;
                continue;
            }
            if (c == '}') {
                if (quantifierStart >= 0) {
                    String content = pattern.substring(quantifierStart + 1, i);
                    if (isValidQuantifierContent(content)) {
                        quantifierStart = -1;
                        continue;
                    }
                }
                throw new XPathException(
                    "FORX0002: Invalid regular expression: " +
                    "unescaped '}' at position " + i);
            }
        }
    }

    private static boolean isValidQuantifierContent(String content) {
        if (content.isEmpty()) {
            return false;
        }
        int comma = content.indexOf(',');
        if (comma < 0) {
            return isDigits(content);
        }
        String before = content.substring(0, comma);
        String after = content.substring(comma + 1);
        return isDigits(before) && (after.isEmpty() || isDigits(after));
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static class UnparsedTextLinesFunction implements Function {
        @Override
        public String getName() {
            return "unparsed-text-lines";
        }

        @Override
        public int getMinArgs() {
            return 1;
        }

        @Override
        public int getMaxArgs() {
            return 2;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            String href = args.get(0).asString();
            if (href == null || href.isEmpty()) {
                return XPathSequence.EMPTY;
            }

            String encoding = null;
            if (args.size() > 1 && args.get(1) != null) {
                encoding = args.get(1).asString();
            }

            String baseUri = context.getStaticBaseURI();

            try {
                String resolvedHref = DocumentLoader.resolveUri(href, baseUri);
                URL url = new URL(resolvedHref);
                InputStream in = url.openStream();
                byte[] data = readAllBytes(in);
                in.close();

                String text = decodeTextBytes(data, encoding);

                // FOUT1190: NUL characters are not valid in text files
                if (text.indexOf('\u0000') >= 0) {
                    throw new XPathException("FOUT1190: Text file contains " +
                        "a NUL character (U+0000)");
                }

                // Empty file → empty sequence
                if (text.isEmpty()) {
                    return XPathSequence.EMPTY;
                }

                // Split on newline boundaries (CR, LF, or CRLF)
                List<XPathValue> result = new ArrayList<XPathValue>();
                int start = 0;
                int len = text.length();
                for (int i = 0; i < len; i++) {
                    char ch = text.charAt(i);
                    if (ch == '\n') {
                        result.add(XPathString.of(text.substring(start, i)));
                        start = i + 1;
                    } else if (ch == '\r') {
                        result.add(XPathString.of(text.substring(start, i)));
                        // Skip LF after CR (CRLF)
                        if (i + 1 < len && text.charAt(i + 1) == '\n') {
                            i++;
                        }
                        start = i + 1;
                    }
                }
                // Add trailing content only if it's non-empty
                // (a trailing newline does NOT produce a final empty line)
                if (start < len) {
                    result.add(XPathString.of(text.substring(start)));
                }

                if (result.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                return new XPathSequence(result);
            } catch (XPathException e) {
                throw e;
            } catch (Exception e) {
                throw new XPathException("FOUT1170: Error reading " + href + ": " + e.getMessage());
            }
        }

        private byte[] readAllBytes(InputStream in) throws IOException {
            List<byte[]> chunks = new ArrayList<byte[]>();
            int totalLen = 0;
            byte[] buf = new byte[IO_BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) >= 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                chunks.add(chunk);
                totalLen += n;
            }
            byte[] result = new byte[totalLen];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }

    private static class ParseXmlFunction implements Function {
        @Override
        public String getName() {
            return "parse-xml";
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
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null) {
                return XPathSequence.EMPTY;
            }
            String xml = arg.asString();
            if (xml == null || xml.isEmpty()) {
                return XPathSequence.EMPTY;
            }

            try {
                XPathNode doc = DocumentLoader.loadDocumentFromString(xml, null, null, null);
                return XPathNodeSet.of(doc);
            } catch (SAXException e) {
                throw new XPathException("FODC0006: parse-xml failed: " + e.getMessage());
            }
        }
    }

    private static class ParseXmlFragmentFunction implements Function {
        @Override
        public String getName() {
            return "parse-xml-fragment";
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
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            XPathValue arg = args.get(0);
            if (arg == null) {
                return XPathSequence.EMPTY;
            }
            String xml = arg.asString();
            if (xml == null || xml.isEmpty()) {
                return XPathSequence.EMPTY;
            }

            // Wrap in a root element to make it well-formed
            String wrapped = "<wrapper>" + xml + "</wrapper>";
            try {
                XPathNode doc = DocumentLoader.loadDocumentFromString(wrapped, null, null, null);
                return XPathNodeSet.of(doc);
            } catch (SAXException e) {
                throw new XPathException("FODC0006: parse-xml-fragment failed: " + e.getMessage());
            }
        }
    }
}
