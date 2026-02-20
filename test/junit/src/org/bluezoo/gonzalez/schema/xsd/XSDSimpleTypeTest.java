/*
 * XSDSimpleTypeTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gonzalez.schema.xsd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for XSD schema types and content models:
 * XSDSimpleType, XSDElement, XSDAttribute, XSDComplexType, XSDParticle,
 * XSDContentModelValidator, and XSDSchema.
 *
 * @author Chris Burdess
 */
public class XSDSimpleTypeTest {

    private static final String TNS = "http://example.com/test";

    // ========== XSDSimpleType tests ==========

    @Test
    public void testGetBuiltInTypeString() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("string");
        assertNotNull(type);
        assertEquals("string", type.getName());
        assertTrue(type.isBuiltIn());
    }

    @Test
    public void testGetBuiltInTypeInteger() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("integer");
        assertNotNull(type);
        assertEquals("integer", type.getName());
    }

    @Test
    public void testGetBuiltInTypeUnknown() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("unknownTypeXYZ");
        assertNull(type);
    }

    @Test
    public void testGetBuiltInTypeNames() {
        Set<String> names = XSDSimpleType.getBuiltInTypeNames();
        assertNotNull(names);
        assertTrue(names.contains("string"));
        assertTrue(names.contains("integer"));
        assertTrue(names.contains("boolean"));
    }

    @Test
    public void testValidateString() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("string");
        String error = type.validate("hello");
        assertNull(error);
    }

    @Test
    public void testValidateInteger() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("integer");
        String error = type.validate("42");
        assertNull(error);
    }

    @Test
    public void testValidateIntegerInvalid() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("integer");
        String error = type.validate("not-a-number");
        assertNotNull(error);
    }

    @Test
    public void testValidateBoolean() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("boolean");
        String errorTrue = type.validate("true");
        assertNull(errorTrue);
        String errorFalse = type.validate("false");
        assertNull(errorFalse);
    }

    @Test
    public void testValidateBooleanInvalid() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("boolean");
        String error = type.validate("maybe");
        assertNotNull(error);
    }

    @Test
    public void testValidateDate() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("date");
        String error = type.validate("2025-02-20");
        assertNull(error);
    }

    @Test
    public void testValidateNull() {
        XSDSimpleType type = XSDSimpleType.getBuiltInType("string");
        String error = type.validate(null);
        assertNotNull(error);
    }

    @Test
    public void testFacetMinLength() {
        XSDSimpleType base = XSDSimpleType.getBuiltInType("string");
        XSDSimpleType restricted = new XSDSimpleType("minLenType", TNS, base);
        restricted.setMinLength(Integer.valueOf(5));

        String errorShort = restricted.validate("abcd");
        assertNotNull(errorShort);

        String errorOk = restricted.validate("abcde");
        assertNull(errorOk);
    }

    @Test
    public void testFacetMaxLength() {
        XSDSimpleType base = XSDSimpleType.getBuiltInType("string");
        XSDSimpleType restricted = new XSDSimpleType("maxLenType", TNS, base);
        restricted.setMaxLength(Integer.valueOf(3));

        String errorLong = restricted.validate("abcd");
        assertNotNull(errorLong);

        String errorOk = restricted.validate("abc");
        assertNull(errorOk);
    }

    @Test
    public void testFacetEnumeration() {
        XSDSimpleType base = XSDSimpleType.getBuiltInType("string");
        XSDSimpleType restricted = new XSDSimpleType("enumType", TNS, base);
        Set<String> enumValues = new HashSet<String>();
        enumValues.add("red");
        enumValues.add("green");
        enumValues.add("blue");
        restricted.setEnumeration(enumValues);

        String errorInvalid = restricted.validate("yellow");
        assertNotNull(errorInvalid);

        String errorOk = restricted.validate("red");
        assertNull(errorOk);
    }

    @Test
    public void testFacetMinInclusiveMaxInclusive() {
        XSDSimpleType base = XSDSimpleType.getBuiltInType("integer");
        XSDSimpleType restricted = new XSDSimpleType("rangeType", TNS, base);
        restricted.setMinInclusive("10");
        restricted.setMaxInclusive("20");

        String errorLow = restricted.validate("5");
        assertNotNull(errorLow);

        String errorHigh = restricted.validate("25");
        assertNotNull(errorHigh);

        String errorOk = restricted.validate("15");
        assertNull(errorOk);
    }

    @Test
    public void testListType() {
        XSDSimpleType itemType = XSDSimpleType.getBuiltInType("integer");
        XSDSimpleType listType = XSDSimpleType.createList("intList", TNS, itemType);

        assertEquals(XSDSimpleType.Variety.LIST, listType.getVariety());
        assertEquals(itemType, listType.getItemType());

        String errorOk = listType.validate("1 2 3");
        assertNull(errorOk);

        String errorInvalid = listType.validate("1 2 abc");
        assertNotNull(errorInvalid);
    }

    @Test
    public void testListTypeWithMinLength() {
        XSDSimpleType itemType = XSDSimpleType.getBuiltInType("integer");
        XSDSimpleType listType = XSDSimpleType.createList("intList", TNS, itemType);
        listType.setMinLength(Integer.valueOf(2));

        String errorOne = listType.validate("1");
        assertNotNull(errorOne);

        String errorOk = listType.validate("1 2");
        assertNull(errorOk);
    }

    @Test
    public void testUnionType() {
        XSDSimpleType intType = XSDSimpleType.getBuiltInType("integer");
        XSDSimpleType boolType = XSDSimpleType.getBuiltInType("boolean");
        List<XSDSimpleType> members = new ArrayList<XSDSimpleType>();
        members.add(intType);
        members.add(boolType);
        XSDSimpleType unionType = XSDSimpleType.createUnion("intOrBool", TNS, members);

        assertEquals(XSDSimpleType.Variety.UNION, unionType.getVariety());

        String errorInt = unionType.validate("42");
        assertNull(errorInt);

        String errorBool = unionType.validate("true");
        assertNull(errorBool);

        String errorInvalid = unionType.validate("12.34");
        assertNotNull(errorInvalid);
    }

    @Test
    public void testTypeDerivationIsDerivedFrom() {
        XSDSimpleType stringType = XSDSimpleType.getBuiltInType("string");
        XSDSimpleType intType = XSDSimpleType.getBuiltInType("integer");

        assertTrue(stringType.isDerivedFrom(stringType));
        assertFalse(stringType.isDerivedFrom(intType));
    }

    @Test
    public void testIsIdType() {
        XSDSimpleType idType = XSDSimpleType.getBuiltInType("ID");
        assertNotNull(idType);
        assertTrue(idType.isIdType());
    }

    @Test
    public void testIsIdTypeFalse() {
        XSDSimpleType stringType = XSDSimpleType.getBuiltInType("string");
        assertFalse(stringType.isIdType());
    }

    // ========== XSDElement tests ==========

    @Test
    public void testXSDElementCreation() {
        XSDElement elem = new XSDElement("title", TNS);
        assertEquals("title", elem.getName());
        assertEquals(TNS, elem.getNamespaceURI());
        assertEquals(1, elem.getMinOccurs());
        assertEquals(1, elem.getMaxOccurs());
        assertFalse(elem.isOptional());
        assertFalse(elem.isRepeatable());
        assertFalse(elem.isUnbounded());
    }

    @Test
    public void testXSDElementTypeAssignment() {
        XSDElement elem = new XSDElement("count", TNS);
        XSDSimpleType intType = XSDSimpleType.getBuiltInType("integer");
        elem.setType(intType);

        assertEquals(intType, elem.getType());
        assertTrue(elem.hasSimpleType());
    }

    @Test
    public void testXSDElementValidateContent() {
        XSDElement elem = new XSDElement("count", TNS);
        XSDSimpleType intType = XSDSimpleType.getBuiltInType("integer");
        elem.setType(intType);

        String errorOk = elem.validateContent("42");
        assertNull(errorOk);

        String errorInvalid = elem.validateContent("not-a-number");
        assertNotNull(errorInvalid);
    }

    @Test
    public void testXSDElementFixedValue() {
        XSDElement elem = new XSDElement("version", TNS);
        elem.setFixedValue("1.0");

        String errorWrong = elem.validateContent("2.0");
        assertNotNull(errorWrong);

        String errorOk = elem.validateContent("1.0");
        assertNull(errorOk);
    }

    @Test
    public void testXSDElementOptional() {
        XSDElement elem = new XSDElement("optional", TNS);
        elem.setMinOccurs(0);
        assertTrue(elem.isOptional());
    }

    @Test
    public void testXSDElementRepeatable() {
        XSDElement elem = new XSDElement("item", TNS);
        elem.setMaxOccurs(5);
        assertTrue(elem.isRepeatable());
    }

    @Test
    public void testXSDElementUnbounded() {
        XSDElement elem = new XSDElement("item", TNS);
        elem.setMaxOccurs(-1);
        assertTrue(elem.isUnbounded());
    }

    // ========== XSDAttribute tests ==========

    @Test
    public void testXSDAttributeCreation() {
        XSDAttribute attr = new XSDAttribute("id", null);
        assertEquals("id", attr.getName());
        assertNull(attr.getNamespaceURI());
        assertEquals(XSDAttribute.Use.OPTIONAL, attr.getUse());
        assertFalse(attr.isRequired());
        assertFalse(attr.isProhibited());
    }

    @Test
    public void testXSDAttributeRequired() {
        XSDAttribute attr = new XSDAttribute("id", null);
        attr.setUse(XSDAttribute.Use.REQUIRED);
        assertTrue(attr.isRequired());
    }

    @Test
    public void testXSDAttributeProhibited() {
        XSDAttribute attr = new XSDAttribute("deprecated", null);
        attr.setUse(XSDAttribute.Use.PROHIBITED);
        assertTrue(attr.isProhibited());
    }

    @Test
    public void testXSDAttributeValidate() {
        XSDAttribute attr = new XSDAttribute("count", null);
        XSDSimpleType intType = XSDSimpleType.getBuiltInType("integer");
        attr.setType(intType);

        String errorOk = attr.validate("42");
        assertNull(errorOk);

        String errorInvalid = attr.validate("abc");
        assertNotNull(errorInvalid);
    }

    @Test
    public void testXSDAttributeFixedValue() {
        XSDAttribute attr = new XSDAttribute("version", null);
        attr.setFixedValue("1.0");

        String errorWrong = attr.validate("2.0");
        assertNotNull(errorWrong);
    }

    @Test
    public void testXSDAttributeIsIdAttribute() {
        XSDAttribute attr = new XSDAttribute("id", null);
        XSDSimpleType idType = XSDSimpleType.getBuiltInType("ID");
        attr.setType(idType);
        assertTrue(attr.isIdAttribute());
    }

    @Test
    public void testXSDAttributeIsIdAttributeFalse() {
        XSDAttribute attr = new XSDAttribute("name", null);
        XSDSimpleType stringType = XSDSimpleType.getBuiltInType("string");
        attr.setType(stringType);
        assertFalse(attr.isIdAttribute());
    }

    // ========== XSDComplexType tests ==========

    @Test
    public void testXSDComplexTypeCreation() {
        XSDComplexType ct = new XSDComplexType("documentType", TNS);
        assertEquals("documentType", ct.getName());
        assertEquals(XSDComplexType.ContentType.ELEMENT_ONLY, ct.getContentType());
        assertFalse(ct.isSimpleType());
    }

    @Test
    public void testXSDComplexTypeAddAttribute() {
        XSDComplexType ct = new XSDComplexType("docType", TNS);
        XSDAttribute attr = new XSDAttribute("id", null);
        ct.addAttribute(attr);

        XSDAttribute found = ct.getAttribute("id");
        assertNotNull(found);
        assertEquals("id", found.getName());
    }

    @Test
    public void testXSDComplexTypeAllowsElement() {
        XSDComplexType ct = new XSDComplexType("rootType", TNS);
        XSDElement childElem = new XSDElement("child", TNS);
        XSDParticle elemParticle = XSDParticle.element(childElem);
        ct.addParticle(elemParticle);

        boolean allows = ct.allowsElement(TNS, "child");
        assertTrue(allows);

        boolean disallows = ct.allowsElement(TNS, "other");
        assertFalse(disallows);
    }

    @Test
    public void testXSDComplexTypeGetChildElement() {
        XSDComplexType ct = new XSDComplexType("rootType", TNS);
        XSDElement childElem = new XSDElement("child", TNS);
        XSDParticle elemParticle = XSDParticle.element(childElem);
        ct.addParticle(elemParticle);

        XSDElement found = ct.getChildElement(TNS, "child");
        assertNotNull(found);
        assertEquals("child", found.getName());
    }

    @Test
    public void testXSDComplexTypeEmptyContentNoElements() {
        XSDComplexType ct = new XSDComplexType("emptyType", TNS);
        ct.setContentType(XSDComplexType.ContentType.EMPTY);

        boolean allows = ct.allowsElement(TNS, "any");
        assertFalse(allows);
    }

    @Test
    public void testXSDComplexTypeSimpleContentNoElements() {
        XSDComplexType ct = new XSDComplexType("simpleType", TNS);
        XSDSimpleType stringType = XSDSimpleType.getBuiltInType("string");
        ct.setSimpleContentType(stringType);

        boolean allows = ct.allowsElement(TNS, "any");
        assertFalse(allows);
    }

    // ========== XSDParticle tests ==========

    @Test
    public void testXSDParticleElement() {
        XSDElement elem = new XSDElement("title", TNS);
        XSDParticle p = XSDParticle.element(elem);

        assertEquals(XSDParticle.Kind.ELEMENT, p.getKind());
        assertEquals(elem, p.getElement());
        assertTrue(p.allowsElement(TNS, "title"));
        assertFalse(p.allowsElement(TNS, "other"));
    }

    @Test
    public void testXSDParticleSequence() {
        XSDElement a = new XSDElement("a", TNS);
        XSDElement b = new XSDElement("b", TNS);
        XSDParticle seq = XSDParticle.sequence();
        seq.addChild(XSDParticle.element(a));
        seq.addChild(XSDParticle.element(b));

        assertEquals(XSDParticle.Kind.SEQUENCE, seq.getKind());
        assertTrue(seq.allowsElement(TNS, "a"));
        assertTrue(seq.allowsElement(TNS, "b"));
    }

    @Test
    public void testXSDParticleChoice() {
        XSDElement a = new XSDElement("a", TNS);
        XSDElement b = new XSDElement("b", TNS);
        XSDParticle choice = XSDParticle.choice();
        choice.addChild(XSDParticle.element(a));
        choice.addChild(XSDParticle.element(b));

        assertEquals(XSDParticle.Kind.CHOICE, choice.getKind());
        assertTrue(choice.allowsElement(TNS, "a"));
        assertTrue(choice.allowsElement(TNS, "b"));
    }

    @Test
    public void testXSDParticleAll() {
        XSDElement a = new XSDElement("a", TNS);
        XSDElement b = new XSDElement("b", TNS);
        XSDParticle all = XSDParticle.all();
        all.addChild(XSDParticle.element(a));
        all.addChild(XSDParticle.element(b));

        assertEquals(XSDParticle.Kind.ALL, all.getKind());
        assertTrue(all.allowsElement(TNS, "a"));
        assertTrue(all.allowsElement(TNS, "b"));
    }

    @Test
    public void testXSDParticleAny() {
        XSDParticle any = XSDParticle.any("##any", "strict");
        assertEquals(XSDParticle.Kind.ANY, any.getKind());
        assertTrue(any.allowsElement(TNS, "anything"));
        assertTrue(any.allowsElement(null, "local"));
    }

    @Test
    public void testXSDParticleGetElement() {
        XSDElement elem = new XSDElement("item", TNS);
        XSDParticle p = XSDParticle.element(elem);

        XSDElement found = p.getElement(TNS, "item");
        assertNotNull(found);
        assertEquals("item", found.getName());
    }

    // ========== XSDContentModelValidator tests ==========

    @Test
    public void testContentModelValidatorValidSequence() {
        XSDComplexType ct = new XSDComplexType("rootType", TNS);
        XSDElement a = new XSDElement("a", TNS);
        XSDParticle elemParticle = XSDParticle.element(a);
        ct.addParticle(elemParticle);

        XSDContentModelValidator validator = new XSDContentModelValidator();
        validator.startValidation(ct);

        XSDContentModelValidator.ValidationResult r1 = validator.validateElement(TNS, "a");
        assertTrue(r1.isValid());

        XSDContentModelValidator.ValidationResult end = validator.endValidation();
        assertTrue(end.isValid());
    }

    @Test
    public void testContentModelValidatorWrongOrder() {
        XSDComplexType ct = new XSDComplexType("rootType", TNS);
        XSDElement a = new XSDElement("a", TNS);
        XSDElement b = new XSDElement("b", TNS);
        XSDParticle seq = XSDParticle.sequence();
        seq.addChild(XSDParticle.element(a));
        seq.addChild(XSDParticle.element(b));
        ct.addParticle(seq);

        XSDContentModelValidator validator = new XSDContentModelValidator();
        validator.startValidation(ct);

        XSDContentModelValidator.ValidationResult r1 = validator.validateElement(TNS, "b");
        assertFalse(r1.isValid());
    }

    @Test
    public void testContentModelValidatorExtraElement() {
        XSDComplexType ct = new XSDComplexType("rootType", TNS);
        XSDElement a = new XSDElement("a", TNS);
        XSDParticle elemParticle = XSDParticle.element(a);
        ct.addParticle(elemParticle);

        XSDContentModelValidator validator = new XSDContentModelValidator();
        validator.startValidation(ct);

        XSDContentModelValidator.ValidationResult r1 = validator.validateElement(TNS, "a");
        assertTrue(r1.isValid());

        XSDContentModelValidator.ValidationResult r2 = validator.validateElement(TNS, "extra");
        assertFalse(r2.isValid());
    }

    @Test
    public void testContentModelValidatorMissingRequired() {
        XSDComplexType ct = new XSDComplexType("rootType", TNS);
        XSDElement a = new XSDElement("a", TNS);
        XSDParticle elemParticle = XSDParticle.element(a);
        ct.addParticle(elemParticle);

        XSDContentModelValidator validator = new XSDContentModelValidator();
        validator.startValidation(ct);

        XSDContentModelValidator.ValidationResult end = validator.endValidation();
        assertFalse(end.isValid());
    }

    @Test
    public void testContentModelValidatorEmptyContent() {
        XSDComplexType ct = new XSDComplexType("emptyType", TNS);
        ct.setContentType(XSDComplexType.ContentType.EMPTY);

        XSDContentModelValidator validator = new XSDContentModelValidator();
        validator.startValidation(ct);

        XSDContentModelValidator.ValidationResult r = validator.validateElement(TNS, "any");
        assertFalse(r.isValid());
    }

    // ========== XSDSchema tests ==========

    @Test
    public void testXSDSchemaCreation() {
        XSDSchema schema = new XSDSchema(TNS);
        assertEquals(TNS, schema.getTargetNamespace());
    }

    @Test
    public void testXSDSchemaAddAndGetElement() {
        XSDSchema schema = new XSDSchema(TNS);
        XSDElement elem = new XSDElement("root", TNS);
        schema.addElement(elem);

        XSDElement found = schema.getElement("root");
        assertNotNull(found);
        assertEquals("root", found.getName());
    }

    @Test
    public void testXSDSchemaAddAndGetType() {
        XSDSchema schema = new XSDSchema(TNS);
        XSDComplexType ct = new XSDComplexType("customType", TNS);
        schema.addType("customType", ct);

        XSDType found = schema.getType("customType");
        assertNotNull(found);
        assertEquals("customType", found.getName());
    }

    @Test
    public void testXSDSchemaGetBuiltInType() {
        XSDSchema schema = new XSDSchema(TNS);
        XSDType stringType = schema.getType("string");
        assertNotNull(stringType);
        assertEquals("string", stringType.getName());
    }

    @Test
    public void testXSDSchemaResolveElement() {
        XSDSchema schema = new XSDSchema(TNS);
        XSDElement elem = new XSDElement("root", TNS);
        schema.addElement(elem);

        XSDElement resolved = schema.resolveElement(TNS, "root");
        assertNotNull(resolved);
        assertEquals("root", resolved.getName());
    }

    @Test
    public void testXSDSchemaResolveElementWrongNamespace() {
        XSDSchema schema = new XSDSchema(TNS);
        XSDElement elem = new XSDElement("root", TNS);
        schema.addElement(elem);

        XSDElement resolved = schema.resolveElement("http://other.com/ns", "root");
        assertNull(resolved);
    }

    @Test
    public void testXSDSchemaResolveElementNoNamespace() {
        XSDSchema schema = new XSDSchema(null);
        XSDElement elem = new XSDElement("root", null);
        schema.addElement(elem);

        XSDElement resolved = schema.resolveElement(null, "root");
        assertNotNull(resolved);
    }
}
