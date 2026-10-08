package com.useceleris.server;

import com.useceleris.client.CelerisException;
import com.useceleris.client.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every string that is signed must be well-formed UTF-16, so its UTF-8 bytes are exactly the text
 * the caller passed (D-003). Failures name the field and the rule, never the value.
 */
final class Validation {
  private Validation() {}

  private static final String REQUIRED = "Required";

  private static final String EMPTY = "Must not be empty";

  private static final String UNPAIRED_SURROGATE = "Must not contain unpaired UTF-16 surrogates";

  static void validateSignerOptions(@Nullable String clientId, @Nullable String signingSecret) {
    List<String> failures = new ArrayList<>();
    String clientIdRule = clientId == null ? REQUIRED : colonFreeIdentifierRule(clientId);
    String signingSecretRule = signingSecret == null ? REQUIRED : textRule(signingSecret);

    if (clientIdRule != null) {
      failures.add(failure("clientId", clientIdRule));
    }

    if (signingSecretRule != null) {
      failures.add(failure("signingSecret", signingSecretRule));
    }

    throwIfFailed("signer options", failures);
  } // end method validateSignerOptions

  static void validateClaims(
      @Nullable ChannelScope channels,
      @Nullable SegmentPermissions permissions,
      @Nullable String reference,
      @Nullable Replay replay) {
    List<String> failures = new ArrayList<>();

    if (channels == null) {
      failures.add(failure("channels", REQUIRED));
    } else if (channels.references != null) {
      addChannelReferenceFailures(failures, channels.references);
    }

    if (permissions == null) {
      failures.add(failure("permissions", REQUIRED));
    } else if (permissions.segments != null) {
      addSegmentFailures(failures, permissions.segments);
    }

    String referenceRule = reference == null ? null : colonFreeIdentifierRule(reference);

    if (referenceRule != null) {
      failures.add(failure("reference", referenceRule));
    }

    String replayRule = replay == null ? REQUIRED : replayRule(replay);

    if (replayRule != null) {
      failures.add(failure("replay", replayRule));
    }

    throwIfFailed("claims", failures);
  } // end method validateClaims

  static void validateCredentialProviderOptions(@Nullable ClaimsFunction claimsFunction) {
    if (claimsFunction == null) {
      throwIfFailed("credential provider options", List.of(failure("claimsFunction", REQUIRED)));
    }
  } // end method validateCredentialProviderOptions

  private static void addChannelReferenceFailures(List<String> failures, List<String> references) {
    if (references.isEmpty()) {
      failures.add(failure("channels.references", "Must list at least one channel reference"));

      return;
    }

    Set<String> seen = new HashSet<>();
    boolean repeated = false;

    for (int index = 0; index < references.size(); index++) {
      String reference = references.get(index);
      String rule = reference == null ? REQUIRED : channelReferenceRule(reference);

      if (rule != null) {
        failures.add(failure("channels.references[" + index + "]", rule));
      } else if (!seen.add(reference)) {
        repeated = true;
      }
    }

    if (repeated) {
      failures.add(failure("channels.references", "Must not repeat a channel reference"));
    }
  } // end method addChannelReferenceFailures

  private static void addSegmentFailures(List<String> failures, List<SegmentClaim> segments) {
    Set<String> seen = new HashSet<>();
    boolean repeated = false;

    for (int index = 0; index < segments.size(); index++) {
      SegmentClaim segment = segments.get(index);
      String path = "permissions.segments[" + index + "]";

      if (segment == null) {
        failures.add(failure(path, REQUIRED));

        continue;
      }

      String segmentId = segment.segmentId();
      String rule = segmentId == null ? REQUIRED : identifierRule(segmentId);

      if (rule != null) {
        failures.add(failure(path + ".segmentId", rule));
      } else if (!seen.add(segmentId)) {
        repeated = true;
      }
    }

    if (repeated) {
      failures.add(failure("permissions.segments", "Must not repeat a segment ID"));
    }
  } // end method addSegmentFailures

  private static @Nullable String replayRule(Replay replay) {
    Duration lookback = replay.lookback;

    if (lookback == null) {
      return null;
    }

    if (lookback.isNegative() || lookback.compareTo(Constants.MAXIMUM_REPLAY_LOOKBACK) > 0) {
      return "Lookback must be between 0 and 4294967295 milliseconds";
    }

    if (lookback.toNanos() % 1_000_000 != 0) {
      return "Lookback must be whole milliseconds";
    }

    return null;
  } // end method replayRule

  /** Returns the rule a signed text breaks, or null when it is valid. */
  private static @Nullable String textRule(String text) {
    if (text.isEmpty()) {
      return EMPTY;
    }

    return hasUnpairedSurrogate(text) ? UNPAIRED_SURROGATE : null;
  } // end method textRule

  /** Returns the rule a segment id breaks, or null when it is valid. */
  private static @Nullable String identifierRule(String identifier) {
    String rule = textRule(identifier);

    if (rule != null) {
      return rule;
    }

    if (identifier.indexOf('\r') >= 0 || identifier.indexOf('\n') >= 0) {
      return "Must not contain CR or LF";
    }

    return null;
  } // end method identifierRule

  /**
   * Returns the rule a client id or token reference breaks, or null when it is valid. The colon
   * separates the client id from the digest in the signature.
   */
  private static @Nullable String colonFreeIdentifierRule(String identifier) {
    String rule = textRule(identifier);

    if (rule != null) {
      return rule;
    }

    if (identifier.indexOf(':') >= 0
        || identifier.indexOf('\r') >= 0
        || identifier.indexOf('\n') >= 0) {
      return "Must not contain a colon, CR or LF";
    }

    return null;
  } // end method colonFreeIdentifierRule

  /** Returns the rule a channel reference breaks, or null when it is valid. */
  private static @Nullable String channelReferenceRule(String reference) {
    if (reference.isEmpty()) {
      return EMPTY;
    }

    if (reference.length() > Constants.MAXIMUM_CHANNEL_REFERENCE_LENGTH) {
      return "Must be at most 255 characters";
    }

    for (int index = 0; index < reference.length(); index++) {
      char character = reference.charAt(index);
      boolean letter =
          (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
      boolean digit = character >= '0' && character <= '9';

      if (!letter && !digit && character != '-' && character != '_') {
        return "Must contain only ASCII letters, digits, hyphens (-) or underscores (_)";
      }
    }

    return null;
  } // end method channelReferenceRule

  /** Reports whether text holds a high surrogate not followed by a low one, or a lone low one. */
  private static boolean hasUnpairedSurrogate(String text) {
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);

      if (Character.isHighSurrogate(character)) {
        if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
          index++;

          continue;
        }

        return true;
      }

      if (Character.isLowSurrogate(character)) {
        return true;
      }
    }

    return false;
  } // end method hasUnpairedSurrogate

  /** Describes one failed field and the rule it broke, never its value. */
  private static String failure(String path, String rule) {
    return path + ": " + rule + ".";
  } // end method failure

  /** Names every failure: "Invalid claims. channels: Required. permissions: Required." */
  private static void throwIfFailed(String subject, List<String> failures) {
    if (!failures.isEmpty()) {
      throw new CelerisException(
          ErrorCode.CONFIGURATION, "Invalid " + subject + ". " + String.join(" ", failures));
    }
  } // end method throwIfFailed
} // end class Validation
