package com.useceleris.server;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Read and write access to segments. {@link Claims.Builder#build()} validates it.
 *
 * <pre>{@code
 * SegmentPermissions chat = SegmentPermissions.restricted(new SegmentClaim("chat", true, false));
 * }</pre>
 */
public final class SegmentPermissions {
  /** The segments granted, or null for the same access to every segment. */
  final @Nullable List<SegmentClaim> segments;

  // The access every segment has; meaningful only without segments.
  final boolean read;
  final boolean write;

  private SegmentPermissions(@Nullable List<SegmentClaim> segments, boolean read, boolean write) {
    this.segments = segments;
    this.read = read;
    this.write = write;
  } // end constructor SegmentPermissions

  /**
   * Grants the same access to every segment.
   *
   * @param read whether the credentials may receive messages
   * @param write whether the credentials may publish
   * @return the permissions
   */
  public static SegmentPermissions all(boolean read, boolean write) {
    return new SegmentPermissions(null, read, write);
  } // end method all

  /**
   * Grants access to the listed segments only, each listed once. With none listed, the credentials
   * can connect but neither read nor write. The claims are copied.
   *
   * @param segments the segment claims
   * @return the permissions
   */
  public static SegmentPermissions restricted(SegmentClaim... segments) {
    Objects.requireNonNull(segments, "segments");

    return new SegmentPermissions(Arrays.asList(segments.clone()), false, false);
  } // end method restricted
} // end class SegmentPermissions
