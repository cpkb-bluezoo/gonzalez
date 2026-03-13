/*
 * XsConstructorFunctions.java
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathAnyURI;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBinaryValue;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathDateTime;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathQName;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XML Schema constructor functions (xs:date, xs:dateTime, xs:integer, etc.).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class XsConstructorFunctions {

    private XsConstructorFunctions() {
    }

    /**
     * Invokes an XML Schema constructor function.
     *
     * @param localName the local name of the constructor (e.g., "date", "integer")
     * @param args the constructor arguments
     * @param context the XPath evaluation context
     * @return the constructed value
     * @throws XPathException if construction fails
     */
    static XPathValue invoke(String localName, List<XPathValue> args, XPathContext context) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("xs:" + localName + " requires an argument");
        }
        
        XPathValue arg = args.get(0);
        
        if (arg == null || (arg instanceof XPathSequence && ((XPathSequence)arg).isEmpty())) {
            return XPathSequence.EMPTY;
        }
        
        String value = arg.asString();
        
        switch (localName) {
            case "dateTime":
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE) {
                        return XPathDateTime.castDateToDateTime(src);
                    }
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                        return src;
                    }
                }
                return XPathDateTime.parseDateTime(value);
            case "date":
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                        return XPathDateTime.castDateTimeToDate(src);
                    }
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE) {
                        return src;
                    }
                }
                return XPathDateTime.parseDate(value);
            case "time":
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.DATE_TIME) {
                        return XPathDateTime.castDateTimeToTime(src);
                    }
                    if (src.getDateTimeType() == XPathDateTime.DateTimeType.TIME) {
                        return src;
                    }
                }
                return XPathDateTime.parseTime(value);
            case "duration":
                return XPathDateTime.parseDuration(value);
            case "yearMonthDuration":
                return XPathDateTime.parseYearMonthDuration(value);
            case "dayTimeDuration":
                return XPathDateTime.parseDayTimeDuration(value);
            case "gYearMonth":
            case "gYear":
            case "gMonthDay":
            case "gDay":
            case "gMonth":
                if (arg instanceof XPathDateTime) {
                    XPathDateTime src = (XPathDateTime) arg;
                    XPathDateTime.DateTimeType srcType = src.getDateTimeType();
                    if (srcType == XPathDateTime.DateTimeType.DATE || 
                        srcType == XPathDateTime.DateTimeType.DATE_TIME) {
                        return XPathDateTime.castToGType(src, localName);
                    }
                }
                switch (localName) {
                    case "gYearMonth": return XPathDateTime.parseGYearMonth(value);
                    case "gYear": return XPathDateTime.parseGYear(value);
                    case "gMonthDay": return XPathDateTime.parseGMonthDay(value);
                    case "gDay": return XPathDateTime.parseGDay(value);
                    case "gMonth": return XPathDateTime.parseGMonth(value);
                    default: throw new XPathException("Unknown g* type: " + localName);
                }
            case "string":
                return XPathString.of(value);
            case "integer":
                try {
                    if (arg instanceof XPathNumber) {
                        XPathNumber argNum = (XPathNumber) arg;
                        if (argNum.isExactInteger()) {
                            return argNum;
                        }
                        double d = argNum.asNumber();
                        if (Double.isNaN(d) || Double.isInfinite(d)) {
                            throw new XPathException("Cannot convert " + d + " to xs:integer");
                        }
                        if (argNum.isDecimal()) {
                            BigDecimal bd = argNum.getDecimalValue();
                            BigInteger bi = bd.toBigInteger();
                            return XPathNumber.ofInteger(bi);
                        }
                        return XPathNumber.ofInteger((long) d);
                    }
                    String intStr = value.trim();
                    if (intStr.indexOf('.') >= 0) {
                        BigDecimal bd = new BigDecimal(intStr);
                        BigInteger bi = bd.toBigInteger();
                        return XPathNumber.ofInteger(bi);
                    }
                    try {
                        long intValue = Long.parseLong(intStr);
                        return XPathNumber.ofInteger(intValue);
                    } catch (NumberFormatException e2) {
                        BigInteger bi = new BigInteger(intStr);
                        return XPathNumber.ofInteger(bi);
                    }
                } catch (NumberFormatException e) {
                    throw new XPathException("FORG0001", "Invalid xs:integer: " + value);
                }
            case "decimal":
                try {
                    if (arg instanceof XPathNumber) {
                        XPathNumber argNum = (XPathNumber) arg;
                        if (argNum.isDecimal()) {
                            return argNum;
                        }
                        return new XPathNumber(BigDecimal.valueOf(argNum.asNumber()));
                    }
                    String decStr = value.trim();
                    if (decStr.indexOf('e') >= 0 || decStr.indexOf('E') >= 0) {
                        throw new XPathException("FORG0001: Invalid xs:decimal: " + value);
                    }
                    return new XPathNumber(new BigDecimal(decStr));
                } catch (NumberFormatException e) {
                    throw new XPathException("FORG0001: Invalid xs:decimal: " + value);
                }
            case "double":
                try {
                    if (arg instanceof XPathNumber) {
                        return new XPathNumber(arg.asNumber(), false, true);
                    }
                    double numValue = XPathNumber.parseXPathDouble(value);
                    return new XPathNumber(numValue, false, true);
                } catch (NumberFormatException e) {
                    throw new XPathException("FORG0001", "Invalid xs:double: " + value);
                }
            case "float":
                try {
                    if (arg instanceof XPathNumber) {
                        float fVal = (float) arg.asNumber();
                        return new XPathNumber(fVal, true);
                    }
                    float floatValue = XPathNumber.parseXPathFloat(value);
                    return new XPathNumber(floatValue, true);
                } catch (NumberFormatException e) {
                    throw new XPathException("FORG0001", "Invalid xs:float: " + value);
                }
            case "boolean":
                if (arg instanceof XPathNumber) {
                    double boolNum = arg.asNumber();
                    return XPathBoolean.of(boolNum != 0.0 && !Double.isNaN(boolNum));
                }
                String trimmed = value.trim();
                if ("true".equals(trimmed) || "1".equals(trimmed)) {
                    return XPathBoolean.TRUE;
                } else if ("false".equals(trimmed) || "0".equals(trimmed)) {
                    return XPathBoolean.FALSE;
                } else {
                    throw new XPathException("FORG0001", "Invalid xs:boolean: " + value);
                }
            case "anyURI":
                return new XPathAnyURI(value);
            case "QName": {
                int colonPos = value.indexOf(':');
                if (colonPos > 0) {
                    String prefix = value.substring(0, colonPos);
                    String local = value.substring(colonPos + 1);
                    String nsUri = "";
                    if (context != null) {
                        String resolved = context.resolveNamespacePrefix(prefix);
                        if (resolved != null) {
                            nsUri = resolved;
                        }
                    }
                    return new XPathQName(nsUri, prefix, local);
                }
                return XPathQName.of(value);
            }
            case "base64Binary":
                if (arg instanceof XPathBinaryValue) {
                    return ((XPathBinaryValue) arg).toBase64Binary();
                }
                String b64 = value.replaceAll("\\s+", "");
                if (!isValidBase64(b64)) {
                    throw new XPathException("FORG0001", "Invalid xs:base64Binary: " + value);
                }
                return XPathBinaryValue.fromBase64(b64);
            case "hexBinary":
                if (arg instanceof XPathBinaryValue) {
                    return ((XPathBinaryValue) arg).toHexBinary();
                }
                String hex = value.trim();
                if (!isValidHexBinary(hex)) {
                    throw new XPathException("FORG0001", "Invalid xs:hexBinary: " + value);
                }
                return XPathBinaryValue.fromHex(hex);
            case "NMTOKENS":
            case "IDREFS":
            case "ENTITIES":
                String listValue = value.trim();
                if (listValue.isEmpty()) {
                    return XPathSequence.EMPTY;
                }
                List<XPathValue> listItems = new ArrayList<>();
                int listStart = 0;
                int listLen = listValue.length();
                while (listStart < listLen) {
                    while (listStart < listLen && Character.isWhitespace(listValue.charAt(listStart))) {
                        listStart++;
                    }
                    if (listStart >= listLen) {
                        break;
                    }
                    int listEnd = listStart;
                    while (listEnd < listLen && !Character.isWhitespace(listValue.charAt(listEnd))) {
                        listEnd++;
                    }
                    String token = listValue.substring(listStart, listEnd);
                    listItems.add(XPathString.of(token));
                    listStart = listEnd;
                }
                if (listItems.size() == 1) {
                    return listItems.get(0);
                }
                return XPathSequence.fromList(listItems);
            case "normalizedString":
            case "token":
            case "language":
            case "NMTOKEN":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "ENTITY":
            case "untypedAtomic":
                return XPathUntypedAtomic.ofUntyped(value);
            default:
                return XPathString.of(value);
        }
    }

    static boolean isValidBase64(String s) {
        if (s.isEmpty()) {
            return true;
        }
        int len = s.length();
        if (len % 4 != 0) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (i >= len - 2 && c == '=') {
                continue;
            }
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || 
                  (c >= '0' && c <= '9') || c == '+' || c == '/')) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidHexBinary(String s) {
        if (s.isEmpty()) {
            return true;
        }
        if (s.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

}
