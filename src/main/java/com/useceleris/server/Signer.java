package com.useceleris.server;

import com.useceleris.client.CelerisException;
import com.useceleris.client.CredentialProvider;
import com.useceleris.client.Credentials;
import com.useceleris.client.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints credentials on a trusted server. It keeps the signing secret only inside the digest it
 * computes, so printing a signer never shows the secret. It is safe for concurrent use and holds no
 * resources.
 *
 * <pre>{@code
 * Signer signer = Signer.create(options);
 * Credentials credentials = signer.sign(claims);
 * }</pre>
 */
public final class Signer {
  private final String clientId;
  private final Clock clock;
  private final UnaryOperator<byte[]> digest;

  private Signer(String clientId, Clock clock, UnaryOperator<byte[]> digest) {
    this.clientId = clientId;
    this.clock = clock;
    this.digest = digest;
  } // end constructor Signer

  /**
   * Creates a signer.
   *
   * @param options the validated options
   * @return the signer
   */
  public static Signer create(SignerOptions options) {
    Objects.requireNonNull(options, "options");
    SecretKeySpec key =
        new SecretKeySpec(
            options.signingSecret.getBytes(StandardCharsets.UTF_8), Constants.SIGNATURE_ALGORITHM);

    return new Signer(options.clientId, options.clock, message -> digest(key, message));
  } // end method create

  /**
   * Mints credentials for the claims, stamped with the clock's current time. Sign afresh for every
   * connection attempt; never cache, reuse or backdate credentials (D-001).
   *
   * @param claims the claims your server decided
   * @return the credentials
   * @throws CelerisException with {@link ErrorCode#CONFIGURATION} when the clock's time is outside
   *     the range the server accepts
   */
  public Credentials sign(Claims claims) {
    Objects.requireNonNull(claims, "claims");
    Instant now = clock.instant();

    // Seconds first: converting a time far outside the range to milliseconds would overflow.
    long seconds = now.getEpochSecond();

    if (seconds < 0 || seconds > Constants.MAXIMUM_TIMESTAMP / 1000) {
      throw invalidTimestamp();
    }

    // Whole milliseconds: a clock reading finer than that is truncated.
    long timestamp = now.toEpochMilli();

    if (timestamp < 1) {
      throw invalidTimestamp();
    }

    Base64.Encoder base64 = Base64.getEncoder();
    String payload =
        base64.encodeToString(
            TokenPayload.json(timestamp, claims).getBytes(StandardCharsets.UTF_8));

    // The digest covers the encoded payload text, as the server checks it.
    String hexadecimalDigest =
        HexFormat.of().formatHex(digest.apply(payload.getBytes(StandardCharsets.UTF_8)));
    String signature =
        base64.encodeToString(
            (clientId + ":" + hexadecimalDigest).getBytes(StandardCharsets.UTF_8));

    return new Credentials(payload, signature);
  } // end method sign

  /**
   * Returns a credential provider for a trusted server that consumes realtime itself. Every attempt
   * calls the claims function afresh, on the calling thread, and signs with a fresh timestamp.
   *
   * <p>Cancelling the returned stage before the claims arrive cancels the claims function's stage
   * too, and claims that arrive after cancellation are never signed. A failure of the claims
   * function, a throw or a failed stage, fails the returned stage unchanged; the client reports it
   * as {@link ErrorCode#TRANSPORT}.
   *
   * @param claimsFunction decides the claims for each attempt
   * @return the credential provider
   * @throws CelerisException with {@link ErrorCode#CONFIGURATION} when the claims function is null
   */
  public CredentialProvider credentialProvider(ClaimsFunction claimsFunction) {
    Validation.validateCredentialProviderOptions(claimsFunction);

    return request -> {
      CompletionStage<Claims> claims;

      try {
        claims = claimsFunction.claimsFor(request);
      } catch (RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }

      CompletableFuture<Credentials> credentials = new CompletableFuture<>();

      var unused =
          credentials.whenComplete(
              (ignoredCredentials, ignoredFailure) -> {
                if (credentials.isCancelled()) {
                  // A stage that cannot be cancelled throws here, to no effect: its claims are
                  // ignored when they arrive.
                  claims.toCompletableFuture().cancel(true);
                }
              });

      claims.whenComplete(
          (decided, failure) -> {
            if (failure != null) {
              credentials.completeExceptionally(unwrap(failure));
            } else if (!credentials.isDone()) {
              try {
                credentials.complete(sign(decided));
              } catch (RuntimeException | Error signingFailure) {
                // An Error too, even a VirtualMachineError: rethrown here it would only be lost in
                // this dependent stage, and the attempt would wait out its connect timeout.
                credentials.completeExceptionally(signingFailure);
              }
            }
          });

      return credentials;
    };
  } // end method credentialProvider

  private static byte[] digest(SecretKeySpec key, byte[] message) {
    try {
      Mac mac = Mac.getInstance(Constants.SIGNATURE_ALGORITHM);
      mac.init(key);

      return mac.doFinal(message);
    } catch (GeneralSecurityException unavailable) {
      throw new CelerisException(
          ErrorCode.CONFIGURATION,
          "Signing requires HmacSHA512, which this runtime does not provide.");
    }
  } // end method digest

  private static CelerisException invalidTimestamp() {
    return new CelerisException(
        ErrorCode.CONFIGURATION,
        "Invalid timestamp from Clock. Must be between 1 and 253402300799999 milliseconds.");
  } // end method invalidTimestamp

  /** A stage that depends on another fails with the other's failure wrapped; pass on the cause. */
  private static Throwable unwrap(Throwable failure) {
    Throwable cause = failure.getCause();

    return failure instanceof CompletionException && cause != null ? cause : failure;
  } // end method unwrap
} // end class Signer
