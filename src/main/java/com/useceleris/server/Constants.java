package com.useceleris.server;

import java.time.Duration;

/**
 * Every fixed value the package uses, in one place. None of these is part of the public surface.
 */
final class Constants {
  private Constants() {}

  // The latest timestamp the server accepts: the last millisecond of the year 9999.
  static final long MAXIMUM_TIMESTAMP = 253_402_300_799_999L;

  // The server's largest replay lookback: an unsigned 32-bit millisecond count.
  static final Duration MAXIMUM_REPLAY_LOOKBACK = Duration.ofMillis(4_294_967_295L);

  // The longest channel reference the server accepts.
  static final int MAXIMUM_CHANNEL_REFERENCE_LENGTH = 255;

  // The signature is the hex HMAC-SHA512 of the encoded payload, keyed with the signing secret.
  static final String SIGNATURE_ALGORITHM = "HmacSHA512";
} // end class Constants
