<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="xml" indent="no"/>
  <!-- Hoist stamp into a variable once (idiomatic rewrite of free-ranging) -->
  <xsl:template match="/">
    <xsl:variable name="stamp" select="/root/meta/@generated"/>
    <out>
      <xsl:for-each select="root/item">
        <row id="{@id}" stamp="{$stamp}">
          <xsl:value-of select="."/>
        </row>
      </xsl:for-each>
    </out>
  </xsl:template>
</xsl:stylesheet>
