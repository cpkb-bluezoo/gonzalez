/*
 * AttributeDeclaration.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.dtd;

/**
 * Holds information about an attribute declaration from the DTD.
 *
 * <p>This class is used internally by the DTD parser
 * to track attribute declarations for later use in attribute defaulting
 * and type information.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class AttributeDeclaration {

  public final String type;
  public final String mode;
  public final String defaultValue;

  public AttributeDeclaration(String type, String mode, String defaultValue) {
    this.type = type;
    this.mode = mode;
    this.defaultValue = defaultValue;
  }

}
