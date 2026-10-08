package com.useceleris.server;

import static com.useceleris.server.Fixtures.assertConfiguration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.Credentials;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Signing stamps the clock's time, refuses times the server cannot accept, and shares nothing. */
class SignerTest {
  private static final String TIMESTAMP_FAILURE =
      "Invalid timestamp from Clock. Must be between 1 and 253402300799999 milliseconds.";

  /** The reference's "scoped" vector. */
  private static final Credentials SCOPED =
      new Credentials(
          "eyJ0aW1lc3RhbXAiOjEyMzQ1Njc4OSwiY2hhbm5lbF9yZWZlcmVuY2VzIjpbInJvb20tMSJdLCJ0b2tlbl9wZXJt"
              + "aXNzaW9uIjpbeyJzZWdtZW50X2lkIjoibWVzc2FnZXMiLCJyZWFkIjp0cnVlLCJ3cml0ZSI6ZmFsc2V9XSwi"
              + "cmVwbGF5IjpmYWxzZSwiYWxsb3dfZWNobyI6ZmFsc2V9",
          "c3ludGhldGljLWNsaWVudDphMjU4ZDM5ZGZiNjgyODlkMzE2NjdlMDVjMGRhNzZmMzAyOTFmMmEwMzBmZmFl"
              + "ZDE0NjU1ZjcyZjNjMDhhNDQ5MWNkZjhiYjQ2Yjk3ZmZiNzY1ZjMyOWM0MWJjZDVlM2ZkMzM2YjY0YTY2NTIz"
              + "MjdkNTdmZDY1ZDg1YjkwMjhjZQ==");

  @Test
  void signsTheReferenceVector() {
    assertEquals(
        SCOPED, Fixtures.signer(Fixtures.clockAt(123456789)).sign(Fixtures.scopedClaims()));
  } // end method signsTheReferenceVector

  @Test
  void theSignatureIsTheClientIdAndALowercaseHexDigest() {
    Credentials credentials = Fixtures.signer(Fixtures.clockAt(1)).sign(Fixtures.scopedClaims());
    String signature =
        new String(Base64.getDecoder().decode(credentials.signature()), StandardCharsets.UTF_8);

    assertTrue(
        Pattern.matches("synthetic-client:[0-9a-f]{128}", signature), "signature " + signature);
  } // end method theSignatureIsTheClientIdAndALowercaseHexDigest

  @Test
  void everySigningReadsTheClockAfresh() {
    AtomicLong now = new AtomicLong(123456789);
    Signer signer = Fixtures.signer(steppingClock(now));
    Credentials first = signer.sign(Fixtures.scopedClaims());
    Credentials second = signer.sign(Fixtures.scopedClaims());

    assertNotEquals(first, second);
    assertTrue(Fixtures.decodedPayload(first).startsWith("{\"timestamp\":123456790,"));
    assertTrue(Fixtures.decodedPayload(second).startsWith("{\"timestamp\":123456791,"));
  } // end method everySigningReadsTheClockAfresh

  @Test
  void changedClaimsOrSecretsSignDifferently() {
    Signer signer = Fixtures.signer(Fixtures.clockAt(123456789));
    Claims echo =
        Claims.builder()
            .channels(ChannelScope.restricted("room-1"))
            .permissions(SegmentPermissions.restricted(new SegmentClaim("messages", true, false)))
            .allowEcho(true)
            .build();
    Signer otherSecret =
        Signer.create(
            SignerOptions.builder()
                .clientId(Fixtures.CLIENT_ID)
                .signingSecret("different-secret")
                .clock(Fixtures.clockAt(123456789))
                .build());

    assertNotEquals(SCOPED, signer.sign(echo));
    assertEquals(SCOPED.payload(), otherSecret.sign(Fixtures.scopedClaims()).payload());
    assertNotEquals(SCOPED.signature(), otherSecret.sign(Fixtures.scopedClaims()).signature());
  } // end method changedClaimsOrSecretsSignDifferently

  @Test
  void timesOutsideTheServersRangeAreRefused() {
    List<Instant> refused =
        List.of(
            Instant.ofEpochMilli(0),
            Instant.ofEpochMilli(-5),
            Instant.ofEpochSecond(0, 999_999),
            Instant.ofEpochSecond(-1, 999_999_999),
            Instant.ofEpochMilli(253_402_300_800_000L),
            Instant.ofEpochSecond(253_402_300_800L),
            // Far beyond the range milliseconds can express, which must not wrap into it.
            Instant.MAX,
            Instant.MIN,
            Instant.ofEpochSecond(Long.MAX_VALUE / 1000 + 1),
            Instant.ofEpochSecond(Long.MIN_VALUE / 1000 - 1));

    for (Instant instant : refused) {
      Signer signer = Fixtures.signer(Clock.fixed(instant, ZoneOffset.UTC));
      assertConfiguration(TIMESTAMP_FAILURE, () -> signer.sign(Fixtures.scopedClaims()));
    }
  } // end method timesOutsideTheServersRangeAreRefused

  @Test
  void theRangesEndpointsAreAccepted() {
    for (Instant instant :
        List.of(
            Instant.ofEpochMilli(1),
            Instant.ofEpochSecond(0, 1_000_000),
            Instant.ofEpochMilli(253_402_300_799_999L),
            Instant.ofEpochSecond(253_402_300_799L, 999_999_999))) {
      Credentials credentials =
          Fixtures.signer(Clock.fixed(instant, ZoneOffset.UTC)).sign(Fixtures.scopedClaims());

      assertTrue(
          Fixtures.decodedPayload(credentials)
              .startsWith("{\"timestamp\":" + instant.toEpochMilli() + ","));
    }
  } // end method theRangesEndpointsAreAccepted

  @Test
  void theDefaultClockIsTheSystemClock() {
    Signer signer =
        Signer.create(
            SignerOptions.builder()
                .clientId(Fixtures.CLIENT_ID)
                .signingSecret(Fixtures.SIGNING_SECRET)
                .build());
    long before = System.currentTimeMillis();
    String payload = Fixtures.decodedPayload(signer.sign(Fixtures.scopedClaims()));
    long after = System.currentTimeMillis();
    long timestamp =
        Long.parseLong(payload.substring("{\"timestamp\":".length(), payload.indexOf(',')));

    assertTrue(timestamp >= before && timestamp <= after, payload);
  } // end method theDefaultClockIsTheSystemClock

  @Test
  void nullArgumentsAreRefused() {
    Signer signer = Fixtures.signer(Fixtures.clockAt(1));

    assertThrows(NullPointerException.class, () -> Signer.create(null));
    assertThrows(NullPointerException.class, () -> signer.sign(null));
  } // end method nullArgumentsAreRefused

  @Test
  void concurrentSigningSharesNothing() throws Exception {
    Signer signer = Fixtures.signer(Fixtures.clockAt(123456789));
    List<Claims> claims = new ArrayList<>();
    List<Credentials> expected = new ArrayList<>();

    for (int index = 0; index < 16; index++) {
      Claims userClaims =
          Claims.builder()
              .channels(ChannelScope.restricted("room-" + index))
              .permissions(SegmentPermissions.all(true, index % 2 == 0))
              .reference("user-" + index)
              .build();
      claims.add(userClaims);
      expected.add(signer.sign(userClaims));
    }

    ExecutorService executor = Executors.newFixedThreadPool(8);

    try {
      List<Future<Boolean>> results = new ArrayList<>();

      for (int task = 0; task < 64; task++) {
        int index = task % claims.size();
        results.add(
            executor.submit(
                () -> {
                  for (int round = 0; round < 200; round++) {
                    if (!signer.sign(claims.get(index)).equals(expected.get(index))) {
                      return false;
                    }
                  }

                  return true;
                }));
      }

      for (Future<Boolean> result : results) {
        assertTrue(result.get());
      }
    } finally {
      executor.shutdownNow();
    }
  } // end method concurrentSigningSharesNothing

  /** A clock that moves one millisecond forward each time it is read. */
  private static Clock steppingClock(AtomicLong now) {
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      } // end method getZone

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      } // end method withZone

      @Override
      public Instant instant() {
        return Instant.ofEpochMilli(now.incrementAndGet());
      } // end method instant
    };
  } // end method steppingClock
} // end class SignerTest
