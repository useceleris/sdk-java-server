package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.CelerisException;
import com.useceleris.client.CredentialProvider;
import com.useceleris.client.CredentialRequest;
import com.useceleris.client.Credentials;
import com.useceleris.client.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The provider calls the claims function afresh per attempt, signs only claims nobody abandoned,
 * and passes failures on unchanged.
 */
@Timeout(10)
class CredentialProviderTest {
  private static final CredentialRequest INITIAL =
      new CredentialRequest("room-1", false, Optional.empty(), Optional.empty());

  /** Counts its reads, so a test can tell whether signing happened. */
  private final AtomicInteger clockReads = new AtomicInteger();

  private final Signer signer =
      Fixtures.signer(
          new Clock() {
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
              return Instant.ofEpochMilli(1_000 + clockReads.incrementAndGet());
            } // end method instant
          });

  private static CredentialRequest reconnect(Duration replayLookback) {
    return new CredentialRequest(
        "room-1", true, Optional.of(Instant.ofEpochMilli(1_000)), Optional.of(replayLookback));
  } // end method reconnect

  private static CompletableFuture<Credentials> provide(
      CredentialProvider provider, CredentialRequest request) {
    return provider.provide(request).toCompletableFuture();
  } // end method provide

  /** Returns what the stage failed with, exactly as stored. */
  private static Throwable failureOf(CompletableFuture<Credentials> credentials) {
    return credentials.handle((ignored, failure) -> failure).join();
  } // end method failureOf

  @Test
  void everyAttemptCallsTheClaimsFunctionAndSignsAfresh() {
    AtomicInteger calls = new AtomicInteger();
    CredentialProvider provider =
        signer.credentialProvider(
            request -> {
              calls.incrementAndGet();

              return CompletableFuture.completedFuture(Fixtures.scopedClaims());
            });

    Credentials first = provide(provider, INITIAL).join();
    Credentials second = provide(provider, INITIAL).join();

    assertNotEquals(first, second);
    assertEquals(2, calls.get());
    assertEquals(2, clockReads.get());
  } // end method everyAttemptCallsTheClaimsFunctionAndSignsAfresh

  @Test
  void theClaimsFunctionRunsOnTheCallingThreadWithTheRequest() {
    AtomicReference<Thread> thread = new AtomicReference<>();
    AtomicReference<CredentialRequest> received = new AtomicReference<>();
    CredentialProvider provider =
        signer.credentialProvider(
            request -> {
              thread.set(Thread.currentThread());
              received.set(request);

              return new CompletableFuture<>();
            });

    provide(provider, INITIAL);

    assertSame(Thread.currentThread(), thread.get());
    assertSame(INITIAL, received.get());
  } // end method theClaimsFunctionRunsOnTheCallingThreadWithTheRequest

  @Test
  void claimsDecideReplayFromTheReconnectRequest() {
    CredentialProvider provider =
        signer.credentialProvider(
            request -> {
              Claims.Builder claims =
                  Claims.builder()
                      .channels(ChannelScope.restricted("room-1"))
                      .permissions(SegmentPermissions.all(true, true));
              request
                  .replayLookback()
                  .ifPresent(
                      lookback ->
                          claims.replay(
                              Replay.lookback(
                                  lookback.compareTo(Duration.ofSeconds(30)) > 0
                                      ? Duration.ofSeconds(30)
                                      : lookback)));

              return CompletableFuture.completedFuture(claims.build());
            });

    assertTrue(
        Fixtures.decodedPayload(provide(provider, INITIAL).join()).contains("\"replay\":false"));
    assertTrue(
        Fixtures.decodedPayload(provide(provider, reconnect(Duration.ofMillis(6_500))).join())
            .contains("\"replay\":6500"));
    assertTrue(
        Fixtures.decodedPayload(provide(provider, reconnect(Duration.ofHours(1))).join())
            .contains("\"replay\":30000"));
  } // end method claimsDecideReplayFromTheReconnectRequest

  @Test
  void requestFieldsNeverWidenTheClaims() {
    CredentialProvider provider =
        signer.credentialProvider(
            request -> CompletableFuture.completedFuture(Fixtures.scopedClaims()));
    CredentialRequest hostile =
        new CredentialRequest(
            "admin-room", true, Optional.of(Instant.EPOCH), Optional.of(Duration.ofHours(1)));

    String payload = Fixtures.decodedPayload(provide(provider, hostile).join());

    assertFalse(payload.contains("admin-room"), payload);
    assertTrue(payload.contains("\"replay\":false"), payload);
  } // end method requestFieldsNeverWidenTheClaims

  @Test
  void cancellingBeforeTheClaimsArriveCancelsTheClaimsAndNeverSigns() {
    CompletableFuture<Claims> pending = new CompletableFuture<>();
    CredentialProvider provider = signer.credentialProvider(request -> pending);
    CompletableFuture<Credentials> credentials = provide(provider, INITIAL);

    assertTrue(credentials.cancel(true));
    assertTrue(pending.isCancelled());
    assertEquals(0, clockReads.get());
  } // end method cancellingBeforeTheClaimsArriveCancelsTheClaimsAndNeverSigns

  @Test
  void aStageThatCannotBeCancelledIsIgnoredWhenItsClaimsArrive() {
    CompletableFuture<Claims> pending =
        new CompletableFuture<>() {
          @Override
          public CompletableFuture<Claims> toCompletableFuture() {
            throw new UnsupportedOperationException();
          } // end method toCompletableFuture
        };

    CompletableFuture<Credentials> credentials =
        provide(signer.credentialProvider(request -> pending), INITIAL);

    assertTrue(credentials.cancel(true));

    pending.complete(Fixtures.scopedClaims());

    assertTrue(credentials.isCancelled());
    assertEquals(0, clockReads.get());
  } // end method aStageThatCannotBeCancelledIsIgnoredWhenItsClaimsArrive

  @Test
  void claimsArrivingAfterCancellationAreNeverSigned() {
    // A minimal stage cannot be cancelled through the provider, so its claims still arrive.
    CompletableFuture<Claims> pending = new CompletableFuture<>();
    CredentialProvider provider =
        signer.credentialProvider(request -> pending.minimalCompletionStage());
    CompletableFuture<Credentials> credentials = provide(provider, INITIAL);

    credentials.cancel(true);
    pending.complete(Fixtures.scopedClaims());

    assertTrue(credentials.isCancelled());
    assertFalse(pending.isCancelled());
    assertEquals(0, clockReads.get());
  } // end method claimsArrivingAfterCancellationAreNeverSigned

  @Test
  void cancellingAfterSigningChangesNothing() {
    CompletableFuture<Claims> pending = new CompletableFuture<>();
    CredentialProvider provider = signer.credentialProvider(request -> pending);
    CompletableFuture<Credentials> credentials = provide(provider, INITIAL);

    pending.complete(Fixtures.scopedClaims());

    assertFalse(credentials.cancel(true));
    assertFalse(credentials.isCancelled());
    assertEquals(1, clockReads.get());
  } // end method cancellingAfterSigningChangesNothing

  @Test
  void aThrowingClaimsFunctionFailsTheStageWithItsOwnFailure() {
    IllegalStateException failure = new IllegalStateException("synthetic-claims-failure");
    CredentialProvider provider =
        signer.credentialProvider(
            request -> {
              throw failure;
            });

    assertSame(failure, failureOf(provide(provider, INITIAL)));
    assertEquals(0, clockReads.get());
  } // end method aThrowingClaimsFunctionFailsTheStageWithItsOwnFailure

  @Test
  void aFailedClaimsStageFailsTheStageWithItsOwnFailure() {
    IllegalStateException failure =
        new IllegalStateException("synthetic-claims-failure", new RuntimeException("cause"));
    CredentialProvider provider =
        signer.credentialProvider(request -> CompletableFuture.failedFuture(failure));

    assertSame(failure, failureOf(provide(provider, INITIAL)));
    assertEquals(0, clockReads.get());
  } // end method aFailedClaimsStageFailsTheStageWithItsOwnFailure

  @Test
  void aDependentClaimsStagePassesOnItsCauseUnwrapped() {
    IllegalStateException failure = new IllegalStateException("synthetic-claims-failure");
    CompletableFuture<Claims> upstream = new CompletableFuture<>();
    CredentialProvider provider =
        signer.credentialProvider(
            request ->
                upstream.thenApply(
                    claims -> {
                      throw failure;
                    }));

    CompletableFuture<Credentials> credentials = provide(provider, INITIAL);

    upstream.complete(Fixtures.scopedClaims());

    assertSame(failure, failureOf(credentials));
    assertEquals(0, clockReads.get());
  } // end method aDependentClaimsStagePassesOnItsCauseUnwrapped

  @Test
  void aCompletionExceptionWithoutACauseIsPassedOnAsItIs() {
    CompletionException failure = new CompletionException("synthetic", null);
    CredentialProvider provider =
        signer.credentialProvider(request -> CompletableFuture.failedFuture(failure));

    assertSame(failure, failureOf(provide(provider, INITIAL)));
  } // end method aCompletionExceptionWithoutACauseIsPassedOnAsItIs

  @Test
  void invalidClaimsFailTheStageWithTheConfigurationError() {
    CredentialProvider provider =
        signer.credentialProvider(
            request -> CompletableFuture.completedFuture(Claims.builder().build()));

    CelerisException failure =
        assertInstanceOf(CelerisException.class, failureOf(provide(provider, INITIAL)));

    assertEquals(ErrorCode.CONFIGURATION, failure.code());
    assertEquals(
        "Invalid claims. channels: Required. permissions: Required.", failure.getMessage());
  } // end method invalidClaimsFailTheStageWithTheConfigurationError

  @Test
  void aSigningFailureFailsTheStage() {
    Signer stopped = Fixtures.signer(Fixtures.clockAt(0));
    CredentialProvider provider =
        stopped.credentialProvider(
            request -> CompletableFuture.completedFuture(Fixtures.scopedClaims()));

    CelerisException failure =
        assertInstanceOf(CelerisException.class, failureOf(provide(provider, INITIAL)));

    assertEquals(ErrorCode.CONFIGURATION, failure.code());
  } // end method aSigningFailureFailsTheStage

  @Test
  void anErrorThrownWhileSigningFailsTheStage() {
    Clock failing =
        new Clock() {
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
            throw new AssertionError("clock failed");
          } // end method instant
        };

    CredentialProvider provider =
        Fixtures.signer(failing)
            .credentialProvider(
                request -> CompletableFuture.completedFuture(Fixtures.scopedClaims()));

    // Claims already decided are signed before provide returns, so the stage is done by now.
    CompletableFuture<Credentials> credentials = provide(provider, INITIAL);

    assertTrue(credentials.isDone());
    assertInstanceOf(AssertionError.class, failureOf(credentials));
  } // end method anErrorThrownWhileSigningFailsTheStage

  @Test
  void nullClaimsFailTheStage() {
    CredentialProvider provider =
        signer.credentialProvider(request -> CompletableFuture.completedFuture(null));

    assertInstanceOf(NullPointerException.class, failureOf(provide(provider, INITIAL)));
  } // end method nullClaimsFailTheStage

  @Test
  void asynchronousClaimsAreSignedWhenTheyArrive() {
    CompletableFuture<Claims> pending = new CompletableFuture<>();
    CredentialProvider provider = signer.credentialProvider(request -> pending);
    CompletionStage<Credentials> stage = provider.provide(INITIAL);

    assertFalse(stage.toCompletableFuture().isDone());

    pending.complete(Fixtures.scopedClaims());

    assertTrue(Fixtures.decodedPayload(stage.toCompletableFuture().join()).contains("room-1"));
  } // end method asynchronousClaimsAreSignedWhenTheyArrive
} // end class CredentialProviderTest
