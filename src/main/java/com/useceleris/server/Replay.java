package com.useceleris.server;

import java.time.Duration;
import java.util.Objects;

/**
 * The history replayed each time a connection joins a segment. {@link Claims.Builder#build()}
 * validates it.
 *
 * <pre>{@code
 * Replay recent = Replay.lookback(Duration.ofSeconds(30));
 * }</pre>
 */
public final class Replay {
  private static final Replay NONE = new Replay(false, null);
  private static final Replay BACKLOG = new Replay(true, null);

  /** Whether everything the server retains is replayed; meaningful only without a lookback. */
  final boolean backlog;

  /** How far back to replay, or null for no lookback. */
  final @Nullable Duration lookback;

  private Replay(boolean backlog, @Nullable Duration lookback) {
    this.backlog = backlog;
    this.lookback = lookback;
  } // end constructor Replay

  /**
   * Replays nothing. This is the default.
   *
   * @return the replay
   */
  public static Replay none() {
    return NONE;
  } // end method none

  /**
   * Replays everything the server still retains for a segment.
   *
   * @return the replay
   */
  public static Replay backlog() {
    return BACKLOG;
  } // end method backlog

  /**
   * Replays what a segment received within the lookback before the join. Zero replays nothing older
   * than the join, which differs from {@link #none()}.
   *
   * @param lookback whole milliseconds, from 0 to 4294967295
   * @return the replay
   */
  public static Replay lookback(Duration lookback) {
    Objects.requireNonNull(lookback, "lookback");

    return new Replay(false, lookback);
  } // end method lookback
} // end class Replay
