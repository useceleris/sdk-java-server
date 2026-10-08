package com.useceleris.server;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The channels credentials may connect to. {@link Claims.Builder#build()} validates it.
 *
 * <pre>{@code
 * ChannelScope room = ChannelScope.restricted("room-42");
 * }</pre>
 */
public final class ChannelScope {
  private static final ChannelScope ALL = new ChannelScope(null);

  /** The channels granted, or null for every channel of the application. */
  final @Nullable List<String> references;

  private ChannelScope(@Nullable List<String> references) {
    this.references = references;
  } // end constructor ChannelScope

  /**
   * Grants every channel of the application.
   *
   * @return the scope
   */
  public static ChannelScope all() {
    return ALL;
  } // end method all

  /**
   * Grants only the listed channels: at least one, each listed once, each 1 to 255 ASCII letters,
   * digits, hyphens or underscores. The references are copied.
   *
   * @param references the channel references
   * @return the scope
   */
  public static ChannelScope restricted(String... references) {
    Objects.requireNonNull(references, "references");

    return new ChannelScope(Arrays.asList(references.clone()));
  } // end method restricted
} // end class ChannelScope
