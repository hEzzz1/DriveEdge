package com.driveedge.app.edge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class EdgeActivationCodeParserTest {
  private final EdgeActivationCodeParser parser = new EdgeActivationCodeParser();

  @Test
  public void parseReturnsRawCodeWhenQrContainsPlainText() {
    assertEquals("EDGE-ACT-001", parser.parse(" EDGE-ACT-001 "));
  }

  @Test
  public void parseReturnsActivationCodeFromUrlQuery() {
    assertEquals(
      "EDGE-ACT-002",
      parser.parse("https://driveedge.example.com/claim?enterpriseActivationCode=EDGE-ACT-002")
    );
  }

  @Test
  public void parseReturnsBindCodeAliasFromUrlQuery() {
    assertEquals(
      "EDGE-ACT-003",
      parser.parse("driveedge://bind?bindCode=EDGE-ACT-003")
    );
  }

  @Test
  public void parseReturnsNullForBlankValue() {
    assertNull(parser.parse("   "));
  }
}
