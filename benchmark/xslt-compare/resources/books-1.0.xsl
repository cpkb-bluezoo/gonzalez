<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="xml" indent="yes"/>
  <xsl:template match="/">
    <titles>
      <xsl:for-each select="catalog/book">
        <title author="{author}"><xsl:value-of select="title"/></title>
      </xsl:for-each>
    </titles>
  </xsl:template>
</xsl:stylesheet>
