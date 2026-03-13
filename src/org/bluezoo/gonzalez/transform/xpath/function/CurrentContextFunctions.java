/*
 * CurrentContextFunctions.java
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
import java.util.List;
import java.util.regex.Matcher;

import org.bluezoo.gonzalez.transform.runtime.BasicTransformContext;
import org.bluezoo.gonzalez.transform.runtime.TransformContext;
import org.bluezoo.gonzalez.transform.xpath.XPathContext;
import org.bluezoo.gonzalez.transform.xpath.expr.XPathException;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;
import org.bluezoo.gonzalez.transform.xpath.type.XPathNodeSet;
import org.bluezoo.gonzalez.transform.xpath.type.XPathSequence;
import org.bluezoo.gonzalez.transform.xpath.type.XPathString;
import org.bluezoo.gonzalez.transform.xpath.type.XPathValue;

/**
 * XSLT current-context functions (current, current-group, current-grouping-key,
 * current-merge-group, current-merge-key, current-output-uri, regex-group).
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class CurrentContextFunctions {

    private CurrentContextFunctions() {
    }

    static Function current() {
        return new CurrentFunction();
    }

    static Function currentGroup() {
        return new CurrentGroupFunction();
    }

    static Function currentGroupingKey() {
        return new CurrentGroupingKeyFunction();
    }

    static Function currentMergeGroup() {
        return new CurrentMergeGroupFunction();
    }

    static Function currentMergeKey() {
        return new CurrentMergeKeyFunction();
    }

    static Function currentOutputUri() {
        return new CurrentOutputUriFunction();
    }

    static Function regexGroup() {
        return new RegexGroupFunction();
    }

    /**
     * Returns the XSLT current node being processed. Unlike the context node,
     * the current node remains constant during predicate evaluation.
     *
     * <p>Signature: current() → node-set
     *
     * @see <a href="https://www.w3.org/TR/xslt/#function-current">XSLT 1.0 current()</a>
     */
    private static class CurrentFunction implements Function {
        @Override
        public String getName() {
            return "current";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 0;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // XTDE1360: context item absent (e.g., inside xsl:function body)
            if (context.isContextItemUndefined()) {
                throw new XPathException("XTDE1360",
                        "The context item for current() is absent");
            }
            // Check for atomic current item first (set by for-each over atomics)
            if (context instanceof BasicTransformContext) {
                XPathValue currentItem = ((BasicTransformContext) context).getXsltCurrentItem();
                if (currentItem != null) {
                    return currentItem;
                }
            }
            // Fall back to XSLT current node (stays the same during predicate evaluation)
            XPathNode node = context.getXsltCurrentNode();
            if (node == null) {
                throw new XPathException("XTDE1360",
                        "The context item for current() is absent");
            }
            List<XPathNode> nodes = new ArrayList<>();
            nodes.add(node);
            return new XPathNodeSet(nodes);
        }
    }

    /**
     * current-group() - Returns the items in the current group during
     * xsl:for-each-group iteration.
     */
    private static class CurrentGroupFunction implements Function {
        @Override
        public String getName() {
            return "current-group";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 0;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            try {
                XPathValue group = context.getVariable(null, "__current_group__");
                if (group != null) {
                    if (group.isNodeSet() || group.isSequence()) {
                        return group;
                    }
                }
            } catch (XPathException e) {
                // Variable not in scope - not inside xsl:for-each-group
            }
            // XSLT 2.0 processor: return empty sequence when not inside for-each-group
            // XSLT 3.0 processor: raise XTDE1061
            if (context.getProcessorVersion() < 3.0) {
                return XPathSequence.EMPTY;
            }
            throw new XPathException("XTDE1061: current-group() is absent " +
                "(not inside xsl:for-each-group)");
        }
    }

    /**
     * current-grouping-key() - Returns the grouping key for the current group
     * during xsl:for-each-group iteration.
     */
    private static class CurrentGroupingKeyFunction implements Function {
        @Override
        public String getName() {
            return "current-grouping-key";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 0;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Check if inside a positional for-each-group (group-starting/ending-with)
            // where current-grouping-key() is not defined
            try {
                XPathValue absent = context.getVariable(null, "__current_grouping_key_absent__");
                if (absent != null) {
                    if (context.getProcessorVersion() < 3.0) {
                        return XPathSequence.EMPTY;
                    }
                    throw new XPathException("XTDE1071: current-grouping-key() is absent " +
                        "(innermost xsl:for-each-group uses group-starting-with or " +
                        "group-ending-with)");
                }
            } catch (XPathException e) {
                // Variable not in scope - not inside positional for-each-group
            }

            try {
                XPathValue key = context.getVariable(null, "__current_grouping_key__");
                if (key != null) {
                    return key;
                }
            } catch (XPathException e) {
                // Variable not in scope - not inside xsl:for-each-group
            }
            // XSLT 2.0 processor: return empty sequence when not inside for-each-group
            // XSLT 3.0 processor: raise XTDE1071
            if (context.getProcessorVersion() < 3.0) {
                return XPathSequence.EMPTY;
            }
            throw new XPathException("XTDE1071: current-grouping-key() is absent " +
                "(not inside xsl:for-each-group)");
        }
    }

    /**
     * current-merge-group() - Returns all items in the current merge group.
     *
     * <p>Within xsl:merge-action, this returns all items from all merge sources
     * that have the current merge key value.
     *
     * <p>current-merge-group('name') returns only items from the named source.
     */
    private static class CurrentMergeGroupFunction implements Function {
        @Override
        public String getName() {
            return "current-merge-group";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 1;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // XTDE3480: only valid directly inside xsl:merge-action, not in called templates
            if (context instanceof BasicTransformContext) {
                if (!((BasicTransformContext) context).isInsideMergeAction()) {
                    throw new XPathException("XTDE3480: current-merge-group() "
                        + "is not available outside xsl:merge-action");
                }
            }
            if (args.isEmpty()) {
                XPathValue group = context.getVariable(null, "__current_merge_group__");
                if (group != null) {
                    return group;
                }
            } else {
                String sourceName = args.get(0).asString();
                // XTDE3490: validate source name against known merge source names
                XPathValue namesVal = context.getVariable(null, "__merge_source_names__");
                if (namesVal != null) {
                    String knownNames = namesVal.asString();
                    boolean found = false;
                    int start = 0;
                    while (start <= knownNames.length()) {
                        int end = knownNames.indexOf('|', start);
                        if (end < 0) {
                            end = knownNames.length();
                        }
                        String name = knownNames.substring(start, end);
                        if (name.equals(sourceName)) {
                            found = true;
                            break;
                        }
                        start = end + 1;
                    }
                    if (!found) {
                        throw new XPathException("XTDE3490: Unknown merge source name: '"
                            + sourceName + "'");
                    }
                }
                XPathValue group = context.getVariable(null,
                    "__current_merge_group_" + sourceName + "__");
                if (group != null) {
                    return group;
                }
            }
            return XPathNodeSet.empty();
        }
    }

    /**
     * current-merge-key() - Returns the current merge key value.
     *
     * <p>Within xsl:merge-action, this returns the key value that is common
     * to all items in the current merge group.
     */
    private static class CurrentMergeKeyFunction implements Function {
        @Override
        public String getName() {
            return "current-merge-key";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 0;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // XTDE3510: only valid directly inside xsl:merge-action, not in called functions
            if (context instanceof BasicTransformContext) {
                if (!((BasicTransformContext) context).isInsideMergeAction()) {
                    throw new XPathException("XTDE3510: current-merge-key() "
                        + "is not available outside xsl:merge-action");
                }
            }
            XPathValue key = context.getVariable(null, "__current_merge_key__");
            if (key != null) {
                return key;
            }
            return XPathString.of("");
        }
    }

    /**
     * regex-group(n) - Returns the captured group from the current regex match.
     *
     * <p>This function is used inside xsl:matching-substring within xsl:analyze-string
     * to access captured groups from the regular expression match.
     *
     * <p>regex-group(0) returns the entire matched string.
     * regex-group(1) returns the first captured group, etc.
     */
    private static class RegexGroupFunction implements Function {
        @Override
        public String getName() {
            return "regex-group";
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
            int groupNum = (int) args.get(0).asNumber();

            // Get the regex matcher from context
            if (context instanceof TransformContext) {
                Matcher matcher = ((TransformContext) context).getRegexMatcher();
                if (matcher != null) {
                    int groupCount = matcher.groupCount();
                    if (groupNum >= 0 && groupNum <= groupCount) {
                        try {
                            String group = matcher.group(groupNum);
                            if (group == null) {
                                group = "";
                            }
                            return XPathString.of(group);
                        } catch (IllegalStateException e) {
                            // No match operation has been performed yet
                        }
                    }
                }
            }

            // No match context or invalid group number - return empty string
            return XPathString.of("");
        }
    }

    /**
     * current-output-uri() - Returns the URI of the current output destination.
     */
    private static class CurrentOutputUriFunction implements Function {
        @Override
        public String getName() {
            return "current-output-uri";
        }

        @Override
        public int getMinArgs() {
            return 0;
        }

        @Override
        public int getMaxArgs() {
            return 0;
        }

        @Override
        public XPathValue evaluate(List<XPathValue> args, XPathContext context) throws XPathException {
            // Per XSLT 3.0 spec: returns the absolute URI of the current output destination.
            // Returns empty sequence when output destination is absent (e.g., global variables,
            // or when no base output URI has been set by the test/application).
            if (context instanceof TransformContext) {
                TransformContext tc = (TransformContext) context;
                String outputUri = tc.getCurrentOutputUri();
                if (outputUri != null) {
                    return XPathString.of(outputUri);
                }
            }
            return XPathSequence.EMPTY;
        }
    }
}
