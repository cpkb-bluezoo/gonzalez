/*
 * ArrayFunctions.java
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.bluezoo.gonzalez.transform.xpath.Collation;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathArray;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNumber;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XPath 3.1 array namespace functions (array:size, array:get, etc.).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class ArrayFunctions {

    private ArrayFunctions() {
    }

    static XPathValue invoke(String localName, List<XPathValue> args,
            XPathContext context) throws XPathException {
        switch (localName) {
            case "size":
                return arraySize(args);
            case "get":
                return arrayGet(args);
            case "put":
                return arrayPut(args);
            case "append":
                return arrayAppend(args);
            case "subarray":
                return arraySubarray(args);
            case "remove":
                return arrayRemove(args);
            case "insert-before":
                return arrayInsertBefore(args);
            case "head":
                return arrayHead(args);
            case "tail":
                return arrayTail(args);
            case "reverse":
                return arrayReverse(args);
            case "join":
                return arrayJoin(args);
            case "flatten":
                return arrayFlatten(args);
            case "sort":
                return arraySort(args, context);
            case "for-each":
                return arrayForEach(args, context);
            case "filter":
                return arrayFilter(args, context);
            case "fold-left":
                return arrayFoldLeft(args, context);
            case "fold-right":
                return arrayFoldRight(args, context);
            case "for-each-pair":
                return arrayForEachPair(args, context);
            default:
                throw new XPathException("Unknown array function: array:" + localName);
        }
    }

    private static XPathArray requireArray(List<XPathValue> args, String funcName) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("array:" + funcName + " requires an array argument");
        }
        XPathValue first = args.get(0);
        if (first instanceof XPathArray) {
            return (XPathArray) first;
        }
        throw new XPathException("array:" + funcName + ": first argument is not an array");
    }

    private static XPathValue arraySize(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "size");
        return XPathNumber.of(array.size());
    }

    private static XPathValue arrayGet(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "get");
        if (args.size() < 2) {
            throw new XPathException("array:get requires 2 arguments");
        }
        int pos = (int) args.get(1).asNumber();
        if (pos < 1 || pos > array.size()) {
            throw new XPathException("array:get: index " + pos + " out of bounds (1.." + array.size() + ")");
        }
        return array.get(pos);
    }

    private static XPathValue arrayPut(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "put");
        if (args.size() < 3) {
            throw new XPathException("array:put requires 3 arguments");
        }
        int pos = (int) args.get(1).asNumber();
        XPathValue member = args.get(2);
        if (pos < 1 || pos > array.size()) {
            throw new XPathException("array:put: index " + pos + " out of bounds (1.." + array.size() + ")");
        }
        List<XPathValue> members = new ArrayList<XPathValue>(array.members());
        members.set(pos - 1, member);
        return new XPathArray(members);
    }

    private static XPathValue arrayAppend(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "append");
        if (args.size() < 2) {
            throw new XPathException("array:append requires 2 arguments");
        }
        XPathValue appendage = args.get(1);
        List<XPathValue> members = new ArrayList<XPathValue>(array.members());
        members.add(appendage);
        return new XPathArray(members);
    }

    private static XPathValue arraySubarray(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "subarray");
        if (args.size() < 2) {
            throw new XPathException("array:subarray requires at least 2 arguments");
        }
        int start = (int) args.get(1).asNumber();
        if (start < 1 || start > array.size() + 1) {
            throw new XPathException("array:subarray: start " + start + " out of bounds");
        }
        int length;
        if (args.size() >= 3) {
            length = (int) args.get(2).asNumber();
            if (length < 0) {
                throw new XPathException("array:subarray: negative length");
            }
        } else {
            length = array.size() - start + 1;
        }
        int end = start - 1 + length;
        if (end > array.size()) {
            throw new XPathException("array:subarray: start + length exceeds array size");
        }
        List<XPathValue> allMembers = array.members();
        List<XPathValue> sub = new ArrayList<XPathValue>(length);
        for (int i = start - 1; i < end; i++) {
            sub.add(allMembers.get(i));
        }
        return new XPathArray(sub);
    }

    private static XPathValue arrayRemove(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "remove");
        if (args.size() < 2) {
            throw new XPathException("array:remove requires 2 arguments");
        }
        Set<Integer> positions = new HashSet<Integer>();
        Iterator<XPathValue> posIter = args.get(1).sequenceIterator();
        while (posIter.hasNext()) {
            int pos = (int) posIter.next().asNumber();
            positions.add(Integer.valueOf(pos));
        }
        List<XPathValue> allMembers = array.members();
        List<XPathValue> result = new ArrayList<XPathValue>();
        for (int i = 0; i < allMembers.size(); i++) {
            if (!positions.contains(Integer.valueOf(i + 1))) {
                result.add(allMembers.get(i));
            }
        }
        return new XPathArray(result);
    }

    private static XPathValue arrayInsertBefore(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "insert-before");
        if (args.size() < 3) {
            throw new XPathException("array:insert-before requires 3 arguments");
        }
        int pos = (int) args.get(1).asNumber();
        XPathValue member = args.get(2);
        if (pos < 1 || pos > array.size() + 1) {
            throw new XPathException("array:insert-before: index " + pos + " out of bounds");
        }
        List<XPathValue> members = new ArrayList<XPathValue>(array.members());
        members.add(pos - 1, member);
        return new XPathArray(members);
    }

    private static XPathValue arrayHead(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "head");
        if (array.size() == 0) {
            throw new XPathException("array:head: empty array");
        }
        return array.get(1);
    }

    private static XPathValue arrayTail(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "tail");
        if (array.size() == 0) {
            throw new XPathException("array:tail: empty array");
        }
        List<XPathValue> allMembers = array.members();
        List<XPathValue> tail = new ArrayList<XPathValue>(allMembers.size() - 1);
        for (int i = 1; i < allMembers.size(); i++) {
            tail.add(allMembers.get(i));
        }
        return new XPathArray(tail);
    }

    private static XPathValue arrayReverse(List<XPathValue> args) throws XPathException {
        XPathArray array = requireArray(args, "reverse");
        List<XPathValue> allMembers = array.members();
        List<XPathValue> reversed = new ArrayList<XPathValue>(allMembers.size());
        for (int i = allMembers.size() - 1; i >= 0; i--) {
            reversed.add(allMembers.get(i));
        }
        return new XPathArray(reversed);
    }

    private static XPathValue arrayJoin(List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("array:join requires 1 argument");
        }
        List<XPathValue> allMembers = new ArrayList<XPathValue>();
        Iterator<XPathValue> it = args.get(0).sequenceIterator();
        while (it.hasNext()) {
            XPathValue item = it.next();
            if (item instanceof XPathArray) {
                XPathArray arr = (XPathArray) item;
                allMembers.addAll(arr.members());
            } else {
                throw new XPathException("array:join: argument contains a non-array item");
            }
        }
        return new XPathArray(allMembers);
    }

    private static XPathValue arrayFlatten(List<XPathValue> args) throws XPathException {
        if (args.isEmpty()) {
            throw new XPathException("array:flatten requires 1 argument");
        }
        List<XPathValue> result = new ArrayList<XPathValue>();
        flattenRecursive(args.get(0), result);
        if (result.isEmpty()) {
            return XPathSequence.EMPTY;
        }
        if (result.size() == 1) {
            return result.get(0);
        }
        return new XPathSequence(result);
    }

    private static void flattenRecursive(XPathValue value, List<XPathValue> result) {
        if (value instanceof XPathArray) {
            XPathArray arr = (XPathArray) value;
            for (XPathValue member : arr.members()) {
                flattenRecursive(member, result);
            }
        } else if (value instanceof XPathSequence) {
            Iterator<XPathValue> it = value.sequenceIterator();
            while (it.hasNext()) {
                flattenRecursive(it.next(), result);
            }
        } else {
            result.add(value);
        }
    }

    private static XPathValue arraySort(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "sort");
        final List<XPathValue> items = array.members();
        if (items.size() <= 1) {
            return array;
        }

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

        final List<String> keys = new ArrayList<String>(items.size());
        for (int i = 0; i < items.size(); i++) {
            if (keyFunc != null) {
                List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
                callArgs.add(items.get(i));
                XPathValue keyVal = UserFunctionInvoker.invokeFunctionItem(keyFunc, callArgs, context, "array:sort");
                keys.add(keyVal.asString());
            } else {
                keys.add(items.get(i).asString());
            }
        }

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
        return new XPathArray(sorted);
    }

    private static XPathValue arrayForEach(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "for-each");
        if (args.size() < 2) {
            throw new XPathException("array:for-each requires 2 arguments");
        }
        XPathValue funcItem = args.get(1);
        List<XPathValue> results = new ArrayList<XPathValue>(array.size());
        for (XPathValue member : array.members()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
            callArgs.add(member);
            XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "array:for-each");
            results.add(result);
        }
        return new XPathArray(results);
    }

    private static XPathValue arrayFilter(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "filter");
        if (args.size() < 2) {
            throw new XPathException("array:filter requires 2 arguments");
        }
        XPathValue funcItem = args.get(1);
        List<XPathValue> results = new ArrayList<XPathValue>();
        for (XPathValue member : array.members()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(1);
            callArgs.add(member);
            XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "array:filter");
            if (result.asBoolean()) {
                results.add(member);
            }
        }
        return new XPathArray(results);
    }

    private static XPathValue arrayFoldLeft(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "fold-left");
        if (args.size() < 3) {
            throw new XPathException("array:fold-left requires 3 arguments");
        }
        XPathValue accumulator = args.get(1);
        XPathValue funcItem = args.get(2);
        for (XPathValue member : array.members()) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(accumulator);
            callArgs.add(member);
            accumulator = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "array:fold-left");
        }
        return accumulator;
    }

    private static XPathValue arrayFoldRight(List<XPathValue> args, XPathContext context)
            throws XPathException {
        XPathArray array = requireArray(args, "fold-right");
        if (args.size() < 3) {
            throw new XPathException("array:fold-right requires 3 arguments");
        }
        XPathValue accumulator = args.get(1);
        XPathValue funcItem = args.get(2);
        List<XPathValue> members = array.members();
        for (int i = members.size() - 1; i >= 0; i--) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(members.get(i));
            callArgs.add(accumulator);
            accumulator = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "array:fold-right");
        }
        return accumulator;
    }

    private static XPathValue arrayForEachPair(List<XPathValue> args, XPathContext context)
            throws XPathException {
        if (args.size() < 3) {
            throw new XPathException("array:for-each-pair requires 3 arguments");
        }
        XPathValue first = args.get(0);
        XPathValue second = args.get(1);
        if (!(first instanceof XPathArray)) {
            throw new XPathException("array:for-each-pair: first argument is not an array");
        }
        if (!(second instanceof XPathArray)) {
            throw new XPathException("array:for-each-pair: second argument is not an array");
        }
        XPathArray array1 = (XPathArray) first;
        XPathArray array2 = (XPathArray) second;
        XPathValue funcItem = args.get(2);
        List<XPathValue> members1 = array1.members();
        List<XPathValue> members2 = array2.members();
        int len = Math.min(members1.size(), members2.size());
        List<XPathValue> results = new ArrayList<XPathValue>(len);
        for (int i = 0; i < len; i++) {
            List<XPathValue> callArgs = new ArrayList<XPathValue>(2);
            callArgs.add(members1.get(i));
            callArgs.add(members2.get(i));
            XPathValue result = UserFunctionInvoker.invokeFunctionItem(funcItem, callArgs, context, "array:for-each-pair");
            results.add(result);
        }
        return new XPathArray(results);
    }

}
