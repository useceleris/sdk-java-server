package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The token payload is written byte for byte as the reference's {@code JSON.stringify}. */
class TokenPayloadTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  void writesTheReferenceLayoutInTheReferenceKeyOrder() {
    Map<Claims, String> layouts =
        Map.of(
            Claims.builder()
                .channels(ChannelScope.restricted("room-1"))
                .permissions(SegmentPermissions.restricted())
                .build(),
            "{\"timestamp\":5,\"channel_references\":[\"room-1\"],\"token_permission\":[],"
                + "\"replay\":false,\"allow_echo\":false}",
            Claims.builder()
                .channels(ChannelScope.all())
                .permissions(SegmentPermissions.all(true, false))
                .reference("<u&>")
                .replay(Replay.backlog())
                .allowEcho(true)
                .build(),
            "{\"timestamp\":5,\"reference\":\"<u&>\",\"channel_references\":null,"
                + "\"token_permission\":{\"read\":true,\"write\":false},\"replay\":true,"
                + "\"allow_echo\":true}",
            Claims.builder()
                .channels(ChannelScope.restricted("a", "b"))
                .permissions(
                    SegmentPermissions.restricted(
                        new SegmentClaim("a", false, true), new SegmentClaim("b", true, false)))
                .replay(Replay.lookback(Duration.ZERO))
                .build(),
            "{\"timestamp\":5,\"channel_references\":[\"a\",\"b\"],\"token_permission\":"
                + "[{\"segment_id\":\"a\",\"read\":false,\"write\":true},"
                + "{\"segment_id\":\"b\",\"read\":true,\"write\":false}],\"replay\":0,"
                + "\"allow_echo\":false}",
            Claims.builder()
                .channels(ChannelScope.all())
                .permissions(SegmentPermissions.all(false, true))
                .replay(Replay.lookback(Duration.ofMillis(4_294_967_295L)))
                .build(),
            "{\"timestamp\":5,\"channel_references\":null,"
                + "\"token_permission\":{\"read\":false,\"write\":true},\"replay\":4294967295,"
                + "\"allow_echo\":false}");

    layouts.forEach((claims, json) -> assertEquals(json, TokenPayload.json(5, claims)));
  } // end method writesTheReferenceLayoutInTheReferenceKeyOrder

  @Test
  void writesTheTimestampAsAnInteger() {
    assertEquals(
        "{\"timestamp\":253402300799999,\"channel_references\":[\"room-1\"],\"token_permission\":"
            + "[{\"segment_id\":\"messages\",\"read\":true,\"write\":false}],\"replay\":false,"
            + "\"allow_echo\":false}",
        TokenPayload.json(253_402_300_799_999L, Fixtures.scopedClaims()));
  } // end method writesTheTimestampAsAnInteger

  @Test
  void escapesExactlyWhatJsonStringifyEscapes() {
    Map<String, String> quoted =
        Map.ofEntries(
            Map.entry("\"", "\"\\\"\""),
            Map.entry("\\", "\"\\\\\""),
            Map.entry("\b\f\n\r\t", "\"\\b\\f\\n\\r\\t\""),
            Map.entry("/", "\"/\""),
            Map.entry("</script>&", "\"</script>&\""),
            Map.entry("\u007f\u0085 ", "\"\u007f\u0085 \""),
            Map.entry("  ", "\"  \""),
            Map.entry("﻿", "\"﻿\""),
            Map.entry("😀", "\"😀\""),
            Map.entry("雪 身分", "\"雪 身分\""),
            Map.entry("", "\"\""));

    quoted.forEach((text, json) -> assertEquals(json, quote(text)));
  } // end method escapesExactlyWhatJsonStringifyEscapes

  @Test
  void escapesEveryOtherControlCharacterAsLowercaseHexadecimal() {
    for (char character = 0; character < 0x20; character++) {
      String expected =
          switch (character) {
            case '\b' -> "\\b";
            case '\t' -> "\\t";
            case '\n' -> "\\n";
            case '\f' -> "\\f";
            case '\r' -> "\\r";
            default -> String.format("\\u%04x", (int) character);
          };

      assertEquals("\"" + expected + "\"", quote(String.valueOf(character)));
    }

    assertEquals("\" \"", quote(" "));
  } // end method escapesEveryOtherControlCharacterAsLowercaseHexadecimal

  @Test
  void quotedTextDecodesBackUnchangedAndGrowsOnlyByItsEscapes() {
    Random random = new Random(20261004);

    for (int round = 0; round < 2_000; round++) {
      StringBuilder text = new StringBuilder();
      int escapes = 0;

      for (int length = random.nextInt(12); length > 0; length--) {
        int codePoint =
            switch (random.nextInt(5)) {
              case 0 -> random.nextInt(0x20);
              case 1 -> 0x20 + random.nextInt(0x60);
              case 2 -> 0x80 + random.nextInt(0xd800 - 0x80);
              case 3 -> 0xe000 + random.nextInt(0x2000);
              default -> 0x10000 + random.nextInt(0x100000);
            };

        if (codePoint < 0x20 || codePoint == '"' || codePoint == '\\') {
          escapes++;
        }

        text.appendCodePoint(codePoint);
      }

      String json = quote(text.toString());
      assertEquals(text.toString(), MAPPER.readValue(json, String.class));

      if (escapes == 0) {
        assertEquals(text.length() + 2, json.length());
      }
    }
  } // end method quotedTextDecodesBackUnchangedAndGrowsOnlyByItsEscapes

  @Test
  void signsNonAsciiTextAsItsUtf8Bytes() {
    Claims claims =
        Claims.builder()
            .channels(ChannelScope.all())
            .permissions(SegmentPermissions.restricted(new SegmentClaim("雪 ", true, true)))
            .reference("身分😀")
            .build();
    String json = TokenPayload.json(1, claims);

    assertArrayEquals(
        json.getBytes(StandardCharsets.UTF_8),
        Fixtures.decodedPayload(Fixtures.signer(Fixtures.clockAt(1)).sign(claims))
            .getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "{\"timestamp\":1,\"reference\":\"身分😀\",\"channel_references\":null,"
            + "\"token_permission\":[{\"segment_id\":\"雪 \",\"read\":true,\"write\":true}],"
            + "\"replay\":false,\"allow_echo\":false}",
        json);
  } // end method signsNonAsciiTextAsItsUtf8Bytes

  private static String quote(String text) {
    StringBuilder json = new StringBuilder();
    TokenPayload.appendString(json, text);

    return json.toString();
  } // end method quote
} // end class TokenPayloadTest
