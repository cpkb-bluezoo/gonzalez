<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="xml" indent="no"/>
  <!-- Free-ranging: each item looks back at root/meta via absolute path -->
  <xsl:template match="/">
    <out>
      <xsl:for-each select="root/item">
        <row id="{@id}" stamp="{/root/meta/@generated}">
          <xsl:value-of select="."/>
        </row>
      </xsl:for-each>
    </out>
  </xsl:template>
</xsl:stylesheet>
