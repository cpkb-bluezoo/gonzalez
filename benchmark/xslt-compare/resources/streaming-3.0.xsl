<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:mode streamable="yes" on-no-match="shallow-skip"/>
  <xsl:output method="xml" indent="no"/>
  <xsl:template match="/">
    <summary>
      <count><xsl:value-of select="count(root/item)"/></count>
    </summary>
  </xsl:template>
</xsl:stylesheet>
