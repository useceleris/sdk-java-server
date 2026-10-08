package com.useceleris.server;

import java.time.Duration;
import java.util.List;

/**
 * Writes the token payload exactly as the reference signer's {@code JSON.stringify} does, key order
 * included, so the same claims sign to the same bytes in every SDK. JSON libraries cannot be used:
 * they escape characters such as {@code <}, {@code >}, {@code &}, U+2028 and U+2029, which {@code
 * JSON.stringify} writes as they are.
 */
final class TokenPayload {
  private static final char[] HEXADECIMAL = "0123456789abcdef".toCharArray();

  private TokenPayload() {}

  static String json(long timestamp, Claims claims) {
    StringBuilder json = new StringBuilder(256);
    json.append("{\"timestamp\":").append(timestamp);

    if (claims.reference != null) {
      json.append(",\"reference\":");
      appendString(json, claims.reference);
    }

    json.append(",\"channel_references\":");
    List<String> references = claims.channels.references;

    if (references == null) {
      json.append("null");
    } else {
      json.append('[');

      for (int index = 0; index < references.size(); index++) {
        if (index > 0) {
          json.append(',');
        }

        appendString(json, references.get(index));
      }

      json.append(']');
    }

    json.append(",\"token_permission\":");
    List<SegmentClaim> segments = claims.permissions.segments;

    if (segments == null) {
      json.append('{');
      appendAccess(json, claims.permissions.read, claims.permissions.write);
      json.append('}');
    } else {
      json.append('[');

      for (int index = 0; index < segments.size(); index++) {
        SegmentClaim segment = segments.get(index);

        if (index > 0) {
          json.append(',');
        }

        json.append("{\"segment_id\":");
        appendString(json, segment.segmentId());
        json.append(',');
        appendAccess(json, segment.read(), segment.write());
        json.append('}');
      }

      json.append(']');
    }

    json.append(",\"replay\":");
    Duration lookback = claims.replay.lookback;

    if (lookback == null) {
      json.append(claims.replay.backlog);
    } else {
      json.append(lookback.toMillis());
    }

    return json.append(",\"allow_echo\":").append(claims.allowEcho).append('}').toString();
  } // end method json

  private static void appendAccess(StringBuilder json, boolean read, boolean write) {
    json.append("\"read\":").append(read).append(",\"write\":").append(write);
  } // end method appendAccess

  /**
   * Quotes text as {@code JSON.stringify} does: it escapes the quote, the backslash and control
   * characters below U+0020, and writes everything else as it is. The text has no unpaired
   * surrogates, checked before signing.
   */
  static void appendString(StringBuilder json, String text) {
    json.append('"');

    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);

      switch (character) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (character < 0x20) {
            json.append("\\u00")
                .append(HEXADECIMAL[character >> 4])
                .append(HEXADECIMAL[character & 0xf]);
          } else {
            json.append(character);
          }
        }
      }
    }

    json.append('"');
  } // end method appendString
} // end class TokenPayload
