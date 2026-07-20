/*
 * Shared helpers for the XSLT bake-off harness.
 */
package org.bluezoo.gonzalez.xsltcompare;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class CompareSupport {

    private CompareSupport() { }

    static String detectXmlEncoding(byte[] bytes) {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) {
                return "UTF-16";
            }
            if ((b0 == 0x00 && b1 == 0x3C) || (b0 == 0x3C && b1 == 0x00)) {
                return "UTF-16";
            }
        }
        int limit = Math.min(bytes.length, 200);
        String header = new String(bytes, 0, limit, StandardCharsets.US_ASCII);
        int encIdx = header.indexOf("encoding=");
        if (encIdx < 0) {
            return StandardCharsets.UTF_8.name();
        }
        encIdx += 9;
        if (encIdx >= header.length()) {
            return StandardCharsets.UTF_8.name();
        }
        char quote = header.charAt(encIdx);
        if (quote != '"' && quote != '\'') {
            return StandardCharsets.UTF_8.name();
        }
        int end = header.indexOf(quote, encIdx + 1);
        if (end < 0) {
            return StandardCharsets.UTF_8.name();
        }
        return header.substring(encIdx + 1, end);
    }

    static String decodeOutput(byte[] bytes) throws Exception {
        return new String(bytes, detectXmlEncoding(bytes));
    }

    static Object evaluateParamSelect(String select) {
        if (select == null || select.isEmpty()) {
            return select;
        }
        if (select.length() >= 2) {
            char first = select.charAt(0);
            char last = select.charAt(select.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return select.substring(1, select.length() - 1);
            }
        }
        if ("true()".equals(select)) {
            return Boolean.TRUE;
        }
        if ("false()".equals(select)) {
            return Boolean.FALSE;
        }
        try {
            if (select.indexOf('.') >= 0) {
                return Double.valueOf(select);
            }
            long val = Long.parseLong(select);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) val);
            }
            return Long.valueOf(val);
        } catch (NumberFormatException ignored) {
            // not numeric
        }
        return select;
    }

    static double parseSpecVersionAsDouble(String spec) {
        if (spec == null) {
            return -1;
        }
        for (String part : spec.split("\\s+")) {
            String s = part.trim();
            if (s.startsWith("XSLT")) {
                try {
                    int v = Integer.parseInt(s.substring(4));
                    return v / 10.0;
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        return -1;
    }

    static void applyStylesheetParams(TransformerLike transformer, CompareTestCase test) {
        if (test.stylesheetParams == null) {
            return;
        }
        for (Map.Entry<String, String> entry : test.stylesheetParams.entrySet()) {
            transformer.setParameter(entry.getKey(),
                    evaluateParamSelect(entry.getValue()));
        }
    }

    interface TransformerLike {
        void setParameter(String name, Object value);
    }
}
