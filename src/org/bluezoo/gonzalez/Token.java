/*
 * Token.java
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

/**
 * A token in an XML stream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum Token {

    LT, // '<'
    GT, // '>'
    AMP, // '&'
    APOS, // "'"
    QUOT, // '"'
    S, // whitespace
    NAME, // name token
    CDATA, // character data
    ENTITYREF, // entity reference replacement text (e.g., &amp; -> '&', &lt; -> '<')
    COLON, // ':'
    BANG, // '!'
    QUERY, // '?'
    EQ, // '='
    PERCENT, // '%'
    SEMICOLON, // ';'
    HASH, // '#'
    PIPE, // '|'
    START_END_ELEMENT, // "</"
    END_EMPTY_ELEMENT, // "/>"
    START_COMMENT, // "<!--"
    END_COMMENT, // "-->"
    START_CDATA, // "<[CDATA["
    END_CDATA, // "]]>"
    START_XMLDECL, // "<?xml"
    START_PI, // "<?"
    END_PI, // "?>"
    START_DOCTYPE, // "<!DOCTYPE"
    START_ELEMENTDECL, // "<!ELEMENT"
    START_ATTLISTDECL, // "<!ATTLIST"
    START_ENTITYDECL, // "<!ENTITY"
    START_NOTATIONDECL, // "<!NOTATION"
    START_CONDITIONAL, // "<!["
    OPEN_BRACKET, // '['
    CLOSE_BRACKET, // ']'
    OPEN_PAREN, // '('
    CLOSE_PAREN, // ')'
    STAR, // '*'
    PLUS, // '+'
    COMMA, // ','
    
    // DOCTYPE keywords
    SYSTEM, // "SYSTEM"
    PUBLIC, // "PUBLIC"
    NDATA, // "NDATA"
    
    // ELEMENT content model keywords
    EMPTY, // "EMPTY"
    ANY, // "ANY"
    PCDATA, // "#PCDATA"
    
    // ATTLIST type keywords
    CDATA_TYPE, // "CDATA" (attribute type, distinct from CDATA token for character data)
    ID, // "ID"
    IDREF, // "IDREF"
    IDREFS, // "IDREFS"
    ENTITY, // "ENTITY"
    ENTITIES, // "ENTITIES"
    NMTOKEN, // "NMTOKEN"
    NMTOKENS, // "NMTOKENS"
    NOTATION, // "NOTATION"
    
    // ATTLIST default value keywords
    REQUIRED, // "#REQUIRED"
    IMPLIED, // "#IMPLIED"
    FIXED, // "#FIXED"
    
    // Conditional section keywords
    INCLUDE, // "INCLUDE"
    IGNORE, // "IGNORE"
    END_CONDITIONAL, // "]]>"

}
