/*
 * AxisRegistry.java
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

package org.bluezoo.gonzalez.transform.xpath.axis;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry of XPath axis implementations.
 *
 * <p>Provides singleton access to all 13 XPath axis implementations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public final class AxisRegistry {

    private static final Map<String, Axis> AXES;

    static {
        Map<String, Axis> map = new HashMap<>();
        map.put("ancestor", AncestorAxis.INSTANCE);
        map.put("ancestor-or-self", AncestorOrSelfAxis.INSTANCE);
        map.put("attribute", AttributeAxis.INSTANCE);
        map.put("child", ChildAxis.INSTANCE);
        map.put("descendant", DescendantAxis.INSTANCE);
        map.put("descendant-or-self", DescendantOrSelfAxis.INSTANCE);
        map.put("following", FollowingAxis.INSTANCE);
        map.put("following-sibling", FollowingSiblingAxis.INSTANCE);
        map.put("namespace", NamespaceAxis.INSTANCE);
        map.put("parent", ParentAxis.INSTANCE);
        map.put("preceding", PrecedingAxis.INSTANCE);
        map.put("preceding-sibling", PrecedingSiblingAxis.INSTANCE);
        map.put("self", SelfAxis.INSTANCE);
        AXES = Collections.unmodifiableMap(map);
    }

    private AxisRegistry() {}

    /**
     * Returns the axis with the given name.
     *
     * @param name the axis name
     * @return the axis, or null if not found
     */
    public static Axis get(String name) {
        return AXES.get(name);
    }

    /**
     * Returns all registered axes.
     *
     * @return map of axis name to axis (unmodifiable)
     */
    public static Map<String, Axis> getAll() {
        return AXES;
    }

}
