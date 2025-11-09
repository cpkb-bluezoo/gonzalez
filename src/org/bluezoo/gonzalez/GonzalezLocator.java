/*
 * GonzalezLocator.java
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

package org.bluezoo.gonzalez;

import org.xml.sax.ext.Locator2;

/**
 * Implementation of SAX Locator2 for tracking document position.
 *
 * <p>This locator maintains line and column position information during
 * parsing. Line numbers start at 1, column numbers start at 0 (first column).
 *
 * <p>According to XML 1.0 specification section 2.11, line endings are:
 * <ul>
 *   <li>CR (U+000D)</li>
 *   <li>LF (U+000A)</li>
 *   <li>CRLF (CR followed by LF)</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class GonzalezLocator implements Locator2 {

  private String publicId;
  private String systemId;
  private int lineNumber;
  private int columnNumber;
  private String encoding;
  private String xmlVersion;

  /**
   * Creates a new locator.
   */
  GonzalezLocator() {
    this.lineNumber = 1;
    this.columnNumber = 0;
    this.xmlVersion = "1.0";
  }

  // Package-private setters for the parser to update position

  void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  void setSystemId(String systemId) {
    this.systemId = systemId;
  }

  void setLineNumber(int lineNumber) {
    this.lineNumber = lineNumber;
  }

  void setColumnNumber(int columnNumber) {
    this.columnNumber = columnNumber;
  }

  void setEncoding(String encoding) {
    this.encoding = encoding;
  }

  void setXMLVersion(String xmlVersion) {
    this.xmlVersion = xmlVersion;
  }

  /**
   * Advances the column number by the given amount.
   */
  void advanceColumn(int count) {
    this.columnNumber += count;
  }

  /**
   * Advances to the next line.
   * Resets the column to 0 and increments the line number.
   */
  void advanceLine() {
    this.lineNumber++;
    this.columnNumber = 0;
  }

  // Locator interface implementation

  @Override
  public String getPublicId() {
    return publicId;
  }

  @Override
  public String getSystemId() {
    return systemId;
  }

  @Override
  public int getLineNumber() {
    return lineNumber;
  }

  @Override
  public int getColumnNumber() {
    return columnNumber;
  }

  // Locator2 interface implementation

  @Override
  public String getXMLVersion() {
    return xmlVersion;
  }

  @Override
  public String getEncoding() {
    return encoding;
  }

}
