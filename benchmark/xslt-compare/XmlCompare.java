/*
 * Lightweight XML equality for bake-off assertions (mirrors XMLComparator intent).
 */
package org.bluezoo.gonzalez.xsltcompare;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

final class XmlCompare {

    private XmlCompare() { }

    static boolean matchesExpected(String expected, String actual) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        if (strip(expected).equals(strip(actual))) {
            return true;
        }
        if (!looksLikeXml(expected)) {
            return normalizeText(expected).equals(normalizeText(extractTextContent(actual)));
        }
        return equalsXml(expected, actual);
    }

    static boolean equals(String expected, String actual) {
        return matchesExpected(expected, actual);
    }

    private static boolean equalsXml(String expected, String actual) {
        try {
            return XMLComparatorHolder.INSTANCE.compare(expected, actual).equal;
        } catch (Exception e) {
            try {
                Node en = normalize(parse(expected));
                Node an = normalize(parse(actual));
                return nodesEqual(en, an);
            } catch (Exception e2) {
                return strip(expected).equals(strip(actual));
            }
        }
    }

    private static final class XMLComparatorHolder {
        static final org.bluezoo.gonzalez.transform.XMLComparator INSTANCE =
                new org.bluezoo.gonzalez.transform.XMLComparator();
    }

    private static boolean looksLikeXml(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("<") || trimmed.startsWith("<?xml");
    }

    private static String extractTextContent(String xml) {
        if (xml == null) {
            return "";
        }
        if (!looksLikeXml(xml)) {
            return xml;
        }
        try {
            Document doc = parse(xml);
            return normalizeText(doc.getDocumentElement().getTextContent());
        } catch (Exception e) {
            return normalizeText(xml);
        }
    }

    static String diff(String expected, String actual) {
        if (equals(expected, actual)) {
            return null;
        }
        String e = strip(expected);
        String a = strip(actual);
        int max = Math.min(120, Math.min(e.length(), a.length()));
        return "xml mismatch; expected starts '"
                + e.substring(0, Math.min(max, e.length()))
                + "' actual starts '"
                + a.substring(0, Math.min(max, a.length())) + "'";
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setIgnoringElementContentWhitespace(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        String wrapped = xml.trim().startsWith("<?") || xml.trim().startsWith("<")
                ? xml : "<wrapper>" + xml + "</wrapper>";
        return builder.parse(new InputSource(new StringReader(wrapped)));
    }

    private static Node normalize(Document doc) {
        return doc.getDocumentElement();
    }

    private static boolean nodesEqual(Node a, Node b) {
        if (a.getNodeType() != b.getNodeType()) {
            return false;
        }
        if (a.getNodeType() == Node.TEXT_NODE || a.getNodeType() == Node.CDATA_SECTION_NODE) {
            return normalizeText(a.getNodeValue()).equals(normalizeText(b.getNodeValue()));
        }
        if (a.getNodeType() != Node.ELEMENT_NODE) {
            return true;
        }
        Element ea = (Element) a;
        Element eb = (Element) b;
        if (!eq(ea.getNamespaceURI(), eb.getNamespaceURI())
                || !eq(ea.getLocalName(), eb.getLocalName())) {
            return false;
        }
        Map<String, String> aa = attrs(ea);
        Map<String, String> ab = attrs(eb);
        if (!aa.equals(ab)) {
            return false;
        }
        List<Node> ca = children(ea);
        List<Node> cb = children(eb);
        if (ca.size() != cb.size()) {
            return false;
        }
        for (int i = 0; i < ca.size(); i++) {
            if (!nodesEqual(ca.get(i), cb.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> attrs(Element e) {
        Map<String, String> map = new TreeMap<String, String>();
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node n = attrs.item(i);
            if ("xmlns".equals(n.getPrefix()) || "xmlns".equals(n.getNodeName())) {
                continue;
            }
            String key = n.getNamespaceURI() != null
                    ? "{" + n.getNamespaceURI() + "}" + n.getLocalName()
                    : n.getNodeName();
            map.put(key, n.getNodeValue());
        }
        return map;
    }

    private static List<Node> children(Element e) {
        List<Node> list = new ArrayList<Node>();
        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (!normalizeText(n.getNodeValue()).isEmpty()) {
                    list.add(n);
                }
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                list.add(n);
            }
        }
        return list;
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String strip(String xml) {
        return xml == null ? "" : xml.replaceAll(">\\s+<", "><").trim();
    }

    private static boolean eq(String a, String b) {
        if (a == null) {
            return b == null || b.isEmpty();
        }
        return a.equals(b);
    }
}
