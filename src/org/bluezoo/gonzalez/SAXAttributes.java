/*
 * SAXAttributes.java
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.Attributes;
import org.xml.sax.ext.Attributes2;

/**
 * Extended attributes implementation that provides SAX2 extension information.
 *
 * <p>This class implements {@link Attributes2}, providing information about
 * whether attributes were specified in the document or came from DTD defaults,
 * and whether they were declared in the DTD.
 *
 * <p>The implementation is namespace-aware first and optimized for lookup
 * performance using multiple indexing strategies:
 * <ul>
 *   <li>Direct lookup by {@link QName} (namespace URI + local name)</li>
 *   <li>Lookup by qualified name string (for non-namespace-aware code)</li>
 *   <li>Sequential access by index</li>
 * </ul>
 *
 * <p>DTD information is retrieved lazily - {@link #isDeclared} queries the
 * DTD parser only when called, avoiding unnecessary overhead.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class SAXAttributes implements Attributes2 {

  /**
   * Single attribute holder.
   */
  private static class Attribute {
    final QName qname;
    final String type;
    final String value;
    final boolean specified;

    Attribute(QName qname, String type, String value, boolean specified) {
      this.qname = qname;
      this.type = type;
      this.value = value;
      this.specified = specified;
    }
  }

  // Sequential access
  private List<Attribute> attributes;

  // Namespace-aware lookup (CRITICAL PATH - primary access method)
  private Map<QName, Attribute> qnameMap;

  // Non-namespace-aware lookup (for legacy SAX methods and DTD)
  private Map<String, Attribute> stringNameMap;

  // Element name for lazy DTD lookup
  private String elementName;
  private DTDParser dtdParser;

  /**
   * Creates a new empty attribute list.
   */
  public SAXAttributes() {
    this.attributes = new ArrayList<>();
    this.qnameMap = new HashMap<>();
    this.stringNameMap = new HashMap<>();
  }

  /**
   * Sets the context for DTD lookups.
   *
   * @param elementName the element name these attributes belong to
   * @param dtdParser the DTD parser to query, or null if no DTD
   */
  public void setDTDContext(String elementName, DTDParser dtdParser) {
    this.elementName = elementName;
    this.dtdParser = dtdParser;
  }

  /**
   * Adds an attribute to the list.
   * Throws an IllegalArgumentException if an attribute with the same qName already exists
   * (violates XML well-formedness constraint).
   *
   * @param uri the namespace URI (use "" for no namespace)
   * @param localName the local name
   * @param qName the qualified name
   * @param type the attribute type
   * @param value the attribute value
   * @param specified whether the attribute was specified in the document
   * @throws IllegalArgumentException if duplicate attribute detected
   */
  public void addAttribute(String uri, String localName, String qName,
      String type, String value, boolean specified) {
    // Check for duplicate attribute (well-formedness constraint)
    if (stringNameMap.containsKey(qName)) {
      throw new IllegalArgumentException("Duplicate attribute: " + qName);
    }
    
    QName qnameKey = new QName(uri, localName, qName);
    Attribute attr = new Attribute(qnameKey, type, value, specified);

    // Add to all indices
    attributes.add(attr);
    qnameMap.put(qnameKey, attr);
    stringNameMap.put(qName, attr);
  }

  /**
   * Sets the type of an attribute by index.
   *
   * @param index the attribute index
   * @param type the new type
   */
  public void setType(int index, String type) {
    if (index >= 0 && index < attributes.size()) {
      Attribute oldAttr = attributes.get(index);
      Attribute newAttr = new Attribute(oldAttr.qname, type, oldAttr.value, oldAttr.specified);

      // Update all indices
      attributes.set(index, newAttr);
      qnameMap.put(oldAttr.qname, newAttr);
      stringNameMap.put(oldAttr.qname.getQName(), newAttr);
    }
  }

  /**
   * Clears all attributes.
   */
  public void clear() {
    attributes.clear();
    qnameMap.clear();
    stringNameMap.clear();
    elementName = null;
    dtdParser = null;
  }

  // Attributes interface

  @Override
  public int getLength() {
    return attributes.size();
  }

  @Override
  public String getURI(int index) {
    if (index < 0 || index >= attributes.size()) {
      return null;
    }
    return attributes.get(index).qname.getURI();
  }

  @Override
  public String getLocalName(int index) {
    if (index < 0 || index >= attributes.size()) {
      return null;
    }
    return attributes.get(index).qname.getLocalName();
  }

  @Override
  public String getQName(int index) {
    if (index < 0 || index >= attributes.size()) {
      return null;
    }
    return attributes.get(index).qname.getQName();
  }

  @Override
  public String getType(int index) {
    if (index < 0 || index >= attributes.size()) {
      return null;
    }

    Attribute attr = attributes.get(index);

    // Check DTD for more specific type information
    if (dtdParser != null && elementName != null) {
      AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
          elementName, attr.qname.getQName());
      if (decl != null) {
        return decl.type;
      }
    }

    return attr.type;
  }

  @Override
  public String getValue(int index) {
    if (index < 0 || index >= attributes.size()) {
      return null;
    }
    return attributes.get(index).value;
  }

  @Override
  public int getIndex(String uri, String localName) {
    // Create temporary QName for lookup
    QName key = new QName(uri, localName, "");
    Attribute attr = qnameMap.get(key);

    if (attr == null) {
      return -1;
    }

    // Find index in list
    return attributes.indexOf(attr);
  }

  @Override
  public int getIndex(String qName) {
    Attribute attr = stringNameMap.get(qName);

    if (attr == null) {
      return -1;
    }

    // Find index in list
    return attributes.indexOf(attr);
  }

  @Override
  public String getType(String uri, String localName) {
    QName key = new QName(uri, localName, "");
    Attribute attr = qnameMap.get(key);

    if (attr == null) {
      return null;
    }

    // Check DTD for more specific type information
    if (dtdParser != null && elementName != null) {
      AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
          elementName, attr.qname.getQName());
      if (decl != null) {
        return decl.type;
      }
    }

    return attr.type;
  }

  @Override
  public String getType(String qName) {
    Attribute attr = stringNameMap.get(qName);

    if (attr == null) {
      return null;
    }

    // Check DTD for more specific type information
    if (dtdParser != null && elementName != null) {
      AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
          elementName, qName);
      if (decl != null) {
        return decl.type;
      }
    }

    return attr.type;
  }

  @Override
  public String getValue(String uri, String localName) {
    QName key = new QName(uri, localName, "");
    Attribute attr = qnameMap.get(key);
    return (attr != null) ? attr.value : null;
  }

  @Override
  public String getValue(String qName) {
    Attribute attr = stringNameMap.get(qName);
    return (attr != null) ? attr.value : null;
  }

  // Attributes2 interface - lazy DTD lookup

  @Override
  public boolean isDeclared(int index) {
    if (index < 0 || index >= attributes.size()) {
      throw new ArrayIndexOutOfBoundsException(index);
    }

    if (dtdParser == null || elementName == null) {
      return false;
    }

    Attribute attr = attributes.get(index);
    AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
        elementName, attr.qname.getQName());
    return decl != null;
  }

  @Override
  public boolean isDeclared(String qName) {
    Attribute attr = stringNameMap.get(qName);
    if (attr == null) {
      throw new IllegalArgumentException("Unknown attribute: " + qName);
    }

    if (dtdParser == null || elementName == null) {
      return false;
    }

    AttributeDeclaration decl = dtdParser.getAttributeDeclaration(elementName, qName);
    return decl != null;
  }

  @Override
  public boolean isDeclared(String uri, String localName) {
    QName key = new QName(uri, localName, "");
    Attribute attr = qnameMap.get(key);

    if (attr == null) {
      throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
    }

    if (dtdParser == null || elementName == null) {
      return false;
    }

    AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
        elementName, attr.qname.getQName());
    return decl != null;
  }

  @Override
  public boolean isSpecified(int index) {
    if (index < 0 || index >= attributes.size()) {
      throw new ArrayIndexOutOfBoundsException(index);
    }

    return attributes.get(index).specified;
  }

  @Override
  public boolean isSpecified(String qName) {
    Attribute attr = stringNameMap.get(qName);
    if (attr == null) {
      throw new IllegalArgumentException("Unknown attribute: " + qName);
    }
    return attr.specified;
  }

  @Override
  public boolean isSpecified(String uri, String localName) {
    QName key = new QName(uri, localName, "");
    Attribute attr = qnameMap.get(key);

    if (attr == null) {
      throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
    }
    return attr.specified;
  }

}
