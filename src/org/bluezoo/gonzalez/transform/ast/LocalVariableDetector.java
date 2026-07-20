/*
 * LocalVariableDetector.java
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

package org.bluezoo.gonzalez.transform.ast;

/**
 * Detects whether an XSLT subtree declares {@code xsl:variable} or
 * {@code xsl:param}, which require a fresh variable scope.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
final class LocalVariableDetector {

    private LocalVariableDetector() {
    }

    /**
     * Returns true when {@code node} (or a descendant) declares a local
     * variable or param that must bind in a pushed scope.
     *
     * @param node the AST node to inspect, or null
     * @return true if a local variable/param is declared
     */
    static boolean declaresLocalVariables(XSLTNode node) {
        if (node == null) {
            return false;
        }
        if (node instanceof VariableNode || node instanceof ParamNode) {
            return true;
        }
        if (node instanceof SequenceNode) {
            for (XSLTNode child : ((SequenceNode) node).getChildren()) {
                if (declaresLocalVariables(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof CopyNode) {
            return declaresLocalVariables(((CopyNode) node).getContent());
        }
        if (node instanceof ElementNode) {
            return declaresLocalVariables(((ElementNode) node).getContent());
        }
        if (node instanceof LiteralResultElement) {
            return declaresLocalVariables(((LiteralResultElement) node).getContent());
        }
        if (node instanceof IfNode) {
            return declaresLocalVariables(((IfNode) node).getContent());
        }
        if (node instanceof WhenNode) {
            return declaresLocalVariables(((WhenNode) node).getContent());
        }
        if (node instanceof OtherwiseNode) {
            return declaresLocalVariables(((OtherwiseNode) node).getContent());
        }
        if (node instanceof ChooseNode) {
            ChooseNode choose = (ChooseNode) node;
            for (WhenNode when : choose.getWhens()) {
                if (declaresLocalVariables(when)) {
                    return true;
                }
            }
            return declaresLocalVariables(choose.getOtherwise());
        }
        if (node instanceof ForEachNode) {
            return declaresLocalVariables(((ForEachNode) node).getBody());
        }
        if (node instanceof ForEachGroupNode) {
            return declaresLocalVariables(((ForEachGroupNode) node).getBody());
        }
        if (node instanceof AttributeNode) {
            return declaresLocalVariables(((AttributeNode) node).getContent());
        }
        if (node instanceof MessageNode) {
            return declaresLocalVariables(((MessageNode) node).getContent());
        }
        return false;
    }
}
