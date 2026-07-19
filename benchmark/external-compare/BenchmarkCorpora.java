/*
 * BenchmarkCorpora.java
 *
 * Deterministic generators for benchmark inputs whose byte encoding or large
 * internal DTD makes them awkward to keep as hand-edited resource files.
 */
package org.bluezoo.gonzalez;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class BenchmarkCorpora {

    private static final int JAPANESE_ARTICLES = 4000;
    private static final int XHTML_ARTICLES = 1500;

    private BenchmarkCorpora() {
    }

    /**
     * The UTF-16 and EUC-JP inputs have the same logical document shape and
     * Japanese content, so their results primarily expose decoder differences.
     * UTF-16LE includes the BOM required by XML's encoding autodetection rules.
     */
    static byte[] japaneseUtf16() throws CharacterCodingException {
        byte[] body = encode(japaneseDocument("UTF-16"), StandardCharsets.UTF_16LE);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 2);
        out.write(0xFF);
        out.write(0xFE);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    static byte[] japaneseEucJp() throws CharacterCodingException {
        return encode(japaneseDocument("EUC-JP"), Charset.forName("EUC-JP"));
    }

    private static String japaneseDocument(String encoding) {
        StringBuilder xml = new StringBuilder(700000);
        xml.append("<?xml version=\"1.0\" encoding=\"").append(encoding).append("\"?>\n");
        xml.append("<記事一覧 発行者=\"青空出版\">\n");
        for (int i = 0; i < JAPANESE_ARTICLES; i++) {
            xml.append("  <記事 番号=\"").append(i).append("\" 分類=\"技術\">\n");
            xml.append("    <表題>日本語文書の解析性能を測定する記事 ").append(i).append("</表題>\n");
            xml.append("    <概要>この資料は文字コード変換と構文解析の速度を公平に比較します。</概要>\n");
            xml.append("    <本文>東京と大阪の開発者が、大規模な情報交換文書を継続して処理します。")
                    .append("要素名、属性値、文章には日本語を使い、実際の業務データに近い負荷を作ります。</本文>\n");
            xml.append("  </記事>\n");
        }
        xml.append("</記事一覧>\n");
        return xml.toString();
    }

    /**
     * A self-contained XHTML-shaped document. Its internal subset declares the
     * structural, text, list, table and form vocabulary used by the body,
     * common attributes, and several character entities. There is deliberately
     * no PUBLIC or SYSTEM identifier, so no benchmark path can fetch a subset.
     */
    static byte[] xhtmlInternalSubset() {
        StringBuilder xml = new StringBuilder(1000000);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!DOCTYPE html [\n");
        xml.append("  <!ENTITY nbsp \"&#160;\">\n");
        xml.append("  <!ENTITY copy \"&#169;\">\n");
        xml.append("  <!ENTITY mdash \"&#8212;\">\n");
        xml.append("  <!ELEMENT html (head, body)>\n");
        xml.append("  <!ATTLIST html id ID #IMPLIED class CDATA #IMPLIED title CDATA #IMPLIED ")
                .append("xmlns CDATA #IMPLIED xml:lang NMTOKEN #IMPLIED>\n");
        xml.append("  <!ELEMENT head (title, meta*, style?)>\n");
        appendCoreAttlist(xml, "head");
        xml.append("  <!ELEMENT title (#PCDATA)>\n");
        xml.append("  <!ELEMENT meta EMPTY>\n");
        appendCoreAttlist(xml, "meta", "name CDATA #IMPLIED content CDATA #REQUIRED");
        xml.append("  <!ELEMENT style (#PCDATA)>\n");
        appendCoreAttlist(xml, "style", "type CDATA #REQUIRED");
        xml.append("  <!ELEMENT body (header, main, footer)>\n");
        appendCoreAttlist(xml, "body");
        xml.append("  <!ELEMENT header (h1, p)>\n");
        appendCoreAttlist(xml, "header");
        xml.append("  <!ELEMENT main (article+)>\n");
        appendCoreAttlist(xml, "main");
        xml.append("  <!ELEMENT article (h2, p+, ul, table, form)>\n");
        appendCoreAttlist(xml, "article");
        appendInlineElement(xml, "h1");
        appendInlineElement(xml, "h2");
        appendInlineElement(xml, "p");
        appendInlineElement(xml, "span");
        appendInlineElement(xml, "strong");
        appendInlineElement(xml, "em");
        appendInlineElement(xml, "code");
        appendInlineElement(xml, "a", "href CDATA #REQUIRED");
        xml.append("  <!ELEMENT br EMPTY>\n");
        xml.append("  <!ELEMENT img EMPTY>\n");
        appendCoreAttlist(xml, "img", "src CDATA #REQUIRED alt CDATA #REQUIRED");
        xml.append("  <!ELEMENT ul (li+)>\n");
        appendCoreAttlist(xml, "ul");
        appendInlineElement(xml, "li");
        xml.append("  <!ELEMENT table (caption?, thead, tbody)>\n");
        appendCoreAttlist(xml, "table");
        appendInlineElement(xml, "caption");
        xml.append("  <!ELEMENT thead (tr+)>\n");
        xml.append("  <!ELEMENT tbody (tr+)>\n");
        xml.append("  <!ELEMENT tr (th | td)+>\n");
        appendInlineElement(xml, "th", "scope (row | col) #IMPLIED");
        appendInlineElement(xml, "td");
        xml.append("  <!ELEMENT form (label, input, button)>\n");
        appendCoreAttlist(xml, "form", "action CDATA #REQUIRED method (get | post) \"get\"");
        appendInlineElement(xml, "label", "for IDREF #IMPLIED");
        xml.append("  <!ELEMENT input EMPTY>\n");
        appendCoreAttlist(xml, "input", "name CDATA #REQUIRED type CDATA \"text\"");
        appendInlineElement(xml, "button", "type CDATA \"submit\"");
        xml.append("  <!ELEMENT footer (p)>\n");
        appendCoreAttlist(xml, "footer");
        xml.append("]>\n");
        xml.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">\n");
        xml.append("<head><title>Parser benchmark publication</title>");
        xml.append("<meta name=\"description\" content=\"A substantial XHTML-shaped benchmark\"/>");
        xml.append("<style type=\"text/css\">article { margin: 1em; }</style></head>\n");
        xml.append("<body><header><h1>XML parser field notes</h1>");
        xml.append("<p>Self-contained XHTML with a parsed internal subset.</p></header><main>\n");
        for (int i = 0; i < XHTML_ARTICLES; i++) {
            xml.append("<article id=\"article-").append(i).append("\" class=\"benchmark-entry\">");
            xml.append("<h2>Report ").append(i).append(" — streaming XML</h2>");
            xml.append("<p>A substantial document exercises <strong>elements</strong>, ")
                    .append("<em>attributes</em>, declared character entities, and ")
                    .append("<a href=\"/reports/").append(i).append("\">namespace-aware names</a>.</p>");
            xml.append("<p>The parser processes the same in-memory bytes repeatedly while callbacks ")
                    .append("are delivered to an empty consumer.</p>");
            xml.append("<ul><li>Decoder and tokenizer work</li><li>DTD declaration processing</li>")
                    .append("<li><code>Attributes2</code> assembly</li></ul>");
            xml.append("<table><caption>Sample ").append(i).append("</caption><thead><tr>")
                    .append("<th scope=\"col\">Metric</th><th scope=\"col\">Value</th></tr></thead>")
                    .append("<tbody><tr><td>Sequence</td><td>").append(i).append("</td></tr></tbody></table>");
            xml.append("<form action=\"/search\" method=\"get\"><label for=\"query-").append(i)
                    .append("\">Query</label><input id=\"query-").append(i)
                    .append("\" name=\"q\"/><button type=\"submit\">Search</button></form>");
            xml.append("</article>\n");
        }
        xml.append("</main><footer><p>Copyright © XML benchmark project.</p></footer></body></html>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendInlineElement(StringBuilder xml, String name) {
        appendInlineElement(xml, name, null);
    }

    private static void appendInlineElement(StringBuilder xml, String name, String extraAttributes) {
        xml.append("  <!ELEMENT ").append(name)
                .append(" (#PCDATA | span | strong | em | code | a | br | img)*>\n");
        appendCoreAttlist(xml, name, extraAttributes);
    }

    private static void appendCoreAttlist(StringBuilder xml, String name) {
        appendCoreAttlist(xml, name, null);
    }

    private static void appendCoreAttlist(StringBuilder xml, String name, String extraAttributes) {
        xml.append("  <!ATTLIST ").append(name)
                .append(" id ID #IMPLIED class CDATA #IMPLIED title CDATA #IMPLIED");
        if (extraAttributes != null) {
            xml.append(' ').append(extraAttributes);
        }
        xml.append(">\n");
    }

    private static byte[] encode(String text, Charset charset) throws CharacterCodingException {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(java.nio.CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }
}
