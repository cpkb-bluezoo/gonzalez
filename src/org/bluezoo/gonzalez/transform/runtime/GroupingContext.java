/*
 * GroupingContext.java
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

package org.bluezoo.gonzalez.transform.runtime;

import org.bluezoo.gonzalez.transform.xpath.type.XPathNode;

import java.util.Collections;
import java.util.List;

/**
 * Context for xsl:for-each-group iteration.
 *
 * <p>Holds the current group and grouping key during group iteration.
 * This context is used by current-group() and current-grouping-key() functions.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class GroupingContext {

    private final String groupingKey;
    private final List<XPathNode> currentGroup;

    /**
     * Creates a new grouping context.
     *
     * @param groupingKey the grouping key value
     * @param currentGroup the items in the current group
     */
    public GroupingContext(String groupingKey, List<XPathNode> currentGroup) {
        this.groupingKey = groupingKey;
        this.currentGroup = currentGroup != null ? currentGroup : Collections.emptyList();
    }

    /**
     * Returns the current grouping key.
     * This is the value returned by current-grouping-key().
     *
     * @return the grouping key
     */
    public String getGroupingKey() {
        return groupingKey;
    }

    /**
     * Returns the items in the current group.
     * This is the value returned by current-group().
     *
     * @return the group items
     */
    public List<XPathNode> getCurrentGroup() {
        return currentGroup;
    }

    /**
     * Returns the number of items in the current group.
     *
     * @return the group size
     */
    public int getGroupSize() {
        return currentGroup.size();
    }

    @Override
    public String toString() {
        return "GroupingContext[key=" + groupingKey + ", size=" + currentGroup.size() + "]";
    }

}
