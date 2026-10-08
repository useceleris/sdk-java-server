package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.useceleris.client.CelerisException;
import com.useceleris.client.Credentials;
import com.useceleris.client.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.function.Executable;

/** Synthetic credentials and assertions the unit suites share. */
final class Fixtures {
  static final String CLIENT_ID = "synthetic-client";

  static final String SIGNING_SECRET = "synthetic-secret";

  private Fixtures() {}

  static Clock clockAt(long epochMilliseconds) {
    return Clock.fixed(Instant.ofEpochMilli(epochMilliseconds), ZoneOffset.UTC);
  } // end method clockAt

  static Signer signer(Clock clock) {
    return Signer.create(
        SignerOptions.builder()
            .clientId(CLIENT_ID)
            .signingSecret(SIGNING_SECRET)
            .clock(clock)
            .build());
  } // end method signer

  /** The reference's "scoped" vector claims. */
  static Claims scopedClaims() {
    return Claims.builder()
        .channels(ChannelScope.restricted("room-1"))
        .permissions(SegmentPermissions.restricted(new SegmentClaim("messages", true, false)))
        .build();
  } // end method scopedClaims

  static String decodedPayload(Credentials credentials) {
    return new String(Base64.getDecoder().decode(credentials.payload()), StandardCharsets.UTF_8);
  } // end method decodedPayload

  /** Asserts a configuration error with exactly this message, carrying no cause. */
  static CelerisException assertConfiguration(String message, Executable executable) {
    CelerisException failure = assertThrows(CelerisException.class, executable);
    assertEquals(ErrorCode.CONFIGURATION, failure.code());
    assertEquals(message, failure.getMessage());
    assertNull(failure.getCause());
    assertFalse(failure.field().isPresent());

    return failure;
  } // end method assertConfiguration
} // end class Fixtures
