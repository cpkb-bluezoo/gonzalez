<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="xml" indent="no"/>
  <!-- Many specific templates to stress TemplateMatcher indexing -->
  <xsl:template match="item[@id='0']"><a0/></xsl:template>
  <xsl:template match="item[@id='1']"><a1/></xsl:template>
  <xsl:template match="item[@id='2']"><a2/></xsl:template>
  <xsl:template match="item[@id='3']"><a3/></xsl:template>
  <xsl:template match="item[@id='4']"><a4/></xsl:template>
  <xsl:template match="item[@id='5']"><a5/></xsl:template>
  <xsl:template match="item[@id='6']"><a6/></xsl:template>
  <xsl:template match="item[@id='7']"><a7/></xsl:template>
  <xsl:template match="item[@id='8']"><a8/></xsl:template>
  <xsl:template match="item[@id='9']"><a9/></xsl:template>
  <xsl:template match="item"><xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy></xsl:template>
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>
</xsl:stylesheet>
