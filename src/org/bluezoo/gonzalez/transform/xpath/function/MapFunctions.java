/*
 * MapFunctions.java
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathBoolean;
import org.bluezoo.gonzalez.transform.xpath.type.XPathMap;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathUntypedAtomic;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.1 map namespace functions (map:size, map:keys, etc.).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class MapFunctions {

    private MapFunctions() {
    }

    static XPathValue invoke(String localName, List<XPathValue> args,
            XPathContext context) throws XPathException {
        switch (localName) {
            case "size":
                return mapSize(args);
            case "keys":
                return mapKeys(args);
            case "contains":
                return mapContains(args);
            case "get":
                return mapGet(args);
            case "put":
                return mapPut(args);
            case "remove":
                return mapRemove(args);
            case "entry":
                return mapEntry(args);
            case "merge":
                return mapMerge(args);
            case "find":
                return mapFind(args);
            case "for-each":
                return mapForEach(args, context);
            default:
                throw new XPathException("Unknown map function: map:" + localName);
        }
    }

    private static XPathMap requireMap(List<XPathValue> args, String funcName) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("map:" + funcName + " requires a map argument");
        }
        XPathValue first = args.get(0);
        if (first instanceof XPathMap) {
            return (XPathMap) first;
        }
        throw new XPathException("map:" + funcName + ": first argument is not a map");
    }

    private static XPathValue mapSize(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "size");
        return XPathNumber.of(map.size());
    }

    private static XPathValue mapKeys(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "keys");
        List<XPathValue> keys = map.keys();
        if (keys.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        return new XPathSequence(keys);
    }

    private static XPathValue mapContains(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "contains");
        if (args.size() < 2) {
            throw new XPathException("map:contains requires 2 arguments");
        }
        String key = args.get(1).asString();
        return XPathBoolean.of(map.containsKey(key));
    }

    private static XPathValue mapGet(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "get");
        if (args.size() < 2) {
            throw new XPathException("map:get requires 2 arguments");
        }
        String key = args.get(1).asString();
        XPathValue value = map.get(key);
        if (value == null) {
            return XPathSequence.EMPTY;
        }
        return value;
    }

    private static XPathValue mapPut(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "put");
        if (args.size() < 3) {
            throw new XPathException("map:put requires 3 arguments");
        }
        XPathValue typedKey = atomizeKey(args.get(1));
        String key = typedKey.asString();
        XPathValue value = args.get(2);
        return map.put(key, typedKey, value);
    }

    /**
     * Atomizes a map key to an atomic value. Nodes are atomized to
     * xs:untypedAtomic (Gonzalez is a Basic, non-schema-aware processor).
     */
    private static XPathValue atomizeKey(XPathValue value) {
        if (value instanceof XPathNode || value instanceof XPathNodeSet) {
            return XPathUntypedAtomic.ofUntyped(value.asString());
        }
        if (value instanceof XPathSequence) {
            Iterator<XPathValue> iter = ((XPathSequence) value).iterator();
            if (iter.hasNext()) {
                return atomizeKey(iter.next());
            }
            return XPathString.of("");
        }
        return value;
    }

    private static XPathValue mapRemove(List<XPathValue> args) throws XPathException {
        XPathMap map = requireMap(args, "remove");
        if (args.size() < 2) {
            throw new XPathException("map:remove requires 2 arguments");
        }
        String key = args.get(1).asString();
        return map.remove(key);
    }

    private static XPathValue mapEntry(List<XPathValue> args) throws XPathException {
        if (args.size() < 2) {
            throw new XPathException("map:entry requires 2 arguments");
        }
        String key = args.get(0).asString();
        XPathValue value = args.get(1);
        Map<String, XPathValue> entries = new LinkedHashMap<String, XPathValue>();
        entries.put(key, value);
        return new XPathMap(entries);
    }

    private static XPathValue mapMerge(List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("map:merge requires at least 1 argument");
        }
        Map<String, XPathValue> merged = new LinkedHashMap<String, XPathValue>();
        XPathValue first = args.get(0);
        Iterator<XPathValue> it = first.sequenceIterator();
        while (it.hasNext()) {
            XPathValue item = it.next();
            if (item instanceof XPathMap) {
                XPathMap m = (XPathMap) item;
                for (Map.Entry<String, XPathValue> entry : m.entries()) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new XPathMap(merged);
    }

    private static XPathValue mapFind(List<XPathValue> args) throws XPathException {
        if (args.size() < 2) {
            throw new XPathException("map:find requires 2 arguments");
        }
        String key = args.get(1).asString();
        List<XPathValue> found = new ArrayList<XPathValue>();
        mapFindRecursive(args.get(0), key, found);
        if (found.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        return new XPathSequence(found);
    }

    private static void mapFindRecursive(XPathValue value, String key, List<XPathValue> found) {
        if (value instanceof XPathMap) {
            XPathMap map = (XPathMap) value;
            XPathValue v = map.get(key);
            if (v != null) {
                found.add(v);
            }
            for (Map.Entry<String, XPathValue> entry : map.entries()) {
                mapFindRecursive(entry.getValue(), key, found);
            }
        } else if (value instanceof XPathSequence) {
            Iterator<XPathValue> it = value.sequenceIterator();
            while (it.hasNext()) {
                mapFindRecursive(it.next(), key, found);
            }
        }
    }

    private static XPathValue mapForEach(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathMap map = requireMap(args, "for-each");
        if (args.size() < 2) {
            throw new XPathException("map:for-each requires 2 arguments");
        }
        XPathValue funcItem = args.get(1);

        List<XPathValue> results = new ArrayList<XPathValue>();
        for (Map.Entry<String, XPathValue> entry : map.entries()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(XPathString.of(entry.getKey()));
            callArgs.add(entry.getValue());
            XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "map:for-each");
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
