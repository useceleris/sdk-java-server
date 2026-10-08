package com.useceleris.server;

import java.util.Objects;

/**
 * What one set of credentials grants. Your server decides them for a user it has authenticated;
 * nothing from the client widens them. Claims are always valid: {@link Builder#build()} refuses
 * anything else.
 *
 * <pre>{@code
 * Claims claims =
 *     Claims.builder()
 *         .channels(ChannelScope.restricted("room-42"))
 *         .permissions(SegmentPermissions.all(true, false))
 *         .build();
 * }</pre>
 */
public final class Claims {
  final ChannelScope channels;
  final SegmentPermissions permissions;
  final @Nullable String reference;
  final Replay replay;
  final boolean allowEcho;

  private Claims(
      ChannelScope channels,
      SegmentPermissions permissions,
      @Nullable String reference,
      Replay replay,
      boolean allowEcho) {
    this.channels = channels;
    this.permissions = permissions;
    this.reference = reference;
    this.replay = replay;
    this.allowEcho = allowEcho;
  } // end constructor Claims

  /**
   * Starts claims. Channels and permissions must be chosen explicitly, so unrestricted access is
   * never granted by omission (AUTH-02).
   *
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  } // end method builder

  /** Builds {@link Claims}. */
  public static final class Builder {
    private @Nullable ChannelScope channels;
    private @Nullable SegmentPermissions permissions;
    private @Nullable String reference;
    private @Nullable Replay replay = Replay.none();
    private boolean allowEcho;

    private Builder() {}

    /**
     * Sets the channels the credentials may connect to. Required.
     *
     * @param channels {@link ChannelScope#all()} or {@link ChannelScope#restricted(String...)}
     * @return this builder
     */
    public Builder channels(@Nullable ChannelScope channels) {
      this.channels = channels;

      return this;
    } // end method channels

    /**
     * Sets the permissions on the segments of those channels. Required.
     *
     * @param permissions {@link SegmentPermissions#all(boolean, boolean)} or {@link
     *     SegmentPermissions#restricted(SegmentClaim...)}
     * @return this builder
     */
    public Builder permissions(@Nullable SegmentPermissions permissions) {
      this.permissions = permissions;

      return this;
    } // end method permissions

    /**
     * Sets the identity peers see in deliveries and presence, such as a user id. Omitted by
     * default.
     *
     * @param reference non-empty, without a colon, CR, LF or unpaired UTF-16 surrogates; null
     *     leaves it out
     * @return this builder
     */
    public Builder reference(@Nullable String reference) {
      this.reference = reference;

      return this;
    } // end method reference

    /**
     * Sets the history replayed each time the connection joins a segment. Defaults to {@link
     * Replay#none()}.
     *
     * @param replay the replay
     * @return this builder
     */
    public Builder replay(@Nullable Replay replay) {
      this.replay = replay;

      return this;
    } // end method replay

    /**
     * Sets whether a connection receives its own publishes. Defaults to false.
     *
     * @param allowEcho whether to echo
     * @return this builder
     */
    public Builder allowEcho(boolean allowEcho) {
      this.allowEcho = allowEcho;

      return this;
    } // end method allowEcho

    /**
     * Validates and builds the claims.
     *
     * @return the claims
     * @throws com.useceleris.client.CelerisException with {@link
     *     com.useceleris.client.ErrorCode#CONFIGURATION}, naming every field that failed and the
     *     rule it broke, never the value
     */
    public Claims build() {
      Validation.validateClaims(channels, permissions, reference, replay);

      return new Claims(
          Objects.requireNonNull(channels),
          Objects.requireNonNull(permissions),
          reference,
          Objects.requireNonNull(replay),
          allowEcho);
    } // end method build
  } // end class Builder
} // end class Claims
