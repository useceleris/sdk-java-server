package com.useceleris.server;

import java.time.Clock;
import java.util.Objects;

/**
 * Configures a {@link Signer}. The options hold the signing secret, so they print as redacted.
 *
 * <pre>{@code
 * SignerOptions options =
 *     SignerOptions.builder()
 *         .clientId(System.getenv("CELERIS_CLIENT_ID"))
 *         .signingSecret(System.getenv("CELERIS_SIGNING_SECRET"))
 *         .build();
 * }</pre>
 */
public final class SignerOptions {
  final String clientId;
  final String signingSecret;
  final Clock clock;

  private SignerOptions(String clientId, String signingSecret, Clock clock) {
    this.clientId = clientId;
    this.signingSecret = signingSecret;
    this.clock = clock;
  } // end constructor SignerOptions

  /**
   * Starts options. The client id and signing secret are required.
   *
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  } // end method builder

  /**
   * Returns a fixed redacted text.
   *
   * @return {@code "SignerOptions[redacted]"}
   */
  @Override
  public String toString() {
    return "SignerOptions[redacted]";
  } // end method toString

  /** Builds {@link SignerOptions}. It holds the signing secret, so it prints as redacted too. */
  public static final class Builder {
    private @Nullable String clientId;
    private @Nullable String signingSecret;
    private @Nullable Clock clock;

    private Builder() {}

    /**
     * Sets your application's client id.
     *
     * @param clientId non-empty, without a colon, CR, LF or unpaired UTF-16 surrogates
     * @return this builder
     */
    public Builder clientId(@Nullable String clientId) {
      this.clientId = clientId;

      return this;
    } // end method clientId

    /**
     * Sets your application's signing secret. Keep it on trusted servers only.
     *
     * @param signingSecret non-empty, without unpaired UTF-16 surrogates
     * @return this builder
     */
    public Builder signingSecret(@Nullable String signingSecret) {
      this.signingSecret = signingSecret;

      return this;
    } // end method signingSecret

    /**
     * Sets the clock credentials are stamped with. Defaults to the system clock.
     *
     * @param clock the clock; null restores the default
     * @return this builder
     */
    public Builder clock(@Nullable Clock clock) {
      this.clock = clock;

      return this;
    } // end method clock

    /**
     * Validates and builds the options.
     *
     * @return the options
     * @throws com.useceleris.client.CelerisException with {@link
     *     com.useceleris.client.ErrorCode#CONFIGURATION}, naming every field that failed and the
     *     rule it broke, never the value
     */
    public SignerOptions build() {
      Validation.validateSignerOptions(clientId, signingSecret);

      return new SignerOptions(
          Objects.requireNonNull(clientId),
          Objects.requireNonNull(signingSecret),
          clock == null ? Clock.systemUTC() : clock);
    } // end method build

    /**
     * Returns a fixed redacted text.
     *
     * @return {@code "SignerOptions.Builder[redacted]"}
     */
    @Override
    public String toString() {
      return "SignerOptions.Builder[redacted]";
    } // end method toString
  } // end class Builder
} // end class SignerOptions
