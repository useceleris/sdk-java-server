package com.useceleris.server;

import static com.useceleris.server.Fixtures.assertConfiguration;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.useceleris.client.CelerisException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Claims, signer options and provider options name every failed field and the rule it broke, never
 * the value, so invalid ones cannot exist.
 */
class ValidationTest {
  private static final String CHARACTER_RULE =
      "Must contain only ASCII letters, digits, hyphens (-) or underscores (_)";

  private static final String MARKER = "synthetic-sensitive-marker";

  private static Claims.Builder valid() {
    return Claims.builder()
        .channels(ChannelScope.all())
        .permissions(SegmentPermissions.all(true, true));
  } // end method valid

  private static Claims.Builder channels(String... references) {
    return valid().channels(ChannelScope.restricted(references));
  } // end method channels

  private static Claims.Builder segments(SegmentClaim... segments) {
    return valid().permissions(SegmentPermissions.restricted(segments));
  } // end method segments

  private static Claims.Builder lookback(Duration lookback) {
    return valid().replay(Replay.lookback(lookback));
  } // end method lookback

  @Test
  void channelsAndPermissionsAreRequired() {
    assertConfiguration(
        "Invalid claims. channels: Required. permissions: Required.",
        () -> Claims.builder().build());
    assertConfiguration(
        "Invalid claims. permissions: Required.",
        () -> Claims.builder().channels(ChannelScope.all()).build());
    assertConfiguration(
        "Invalid claims. channels: Required.", () -> valid().channels(null).build());
    assertConfiguration(
        "Invalid claims. permissions: Required.", () -> valid().permissions(null).build());
  } // end method channelsAndPermissionsAreRequired

  @Test
  void channelReferencesFollowTheServersRules() {
    Map<Claims.Builder, String> cases =
        Map.of(
            channels(),
            "channels.references: Must list at least one channel reference.",
            channels("a", "a"),
            "channels.references: Must not repeat a channel reference.",
            channels("a", "a", "b", "a", "b"),
            "channels.references: Must not repeat a channel reference.",
            channels("ok", ""),
            "channels.references[1]: Must not be empty.",
            channels("ok", null),
            "channels.references[1]: Required.",
            channels("x:y"),
            "channels.references[0]: " + CHARACTER_RULE + ".",
            channels("é"),
            "channels.references[0]: " + CHARACTER_RULE + ".",
            channels("x".repeat(256)),
            "channels.references[0]: Must be at most 255 characters.",
            channels("", "", "a"),
            "channels.references[0]: Must not be empty. channels.references[1]: Must not be"
                + " empty.");

    cases.forEach(
        (builder, failure) -> assertConfiguration("Invalid claims. " + failure, builder::build));
  } // end method channelReferencesFollowTheServersRules

  @Test
  void channelReferenceCharacterClassHasExactEdges() {
    assertDoesNotThrow(() -> channels("azAZ09-_", "x".repeat(255), "x").build());

    for (String character : List.of("`", "{", "@", "[", "/", ":", " ", ".", "٠", "\n")) {
      assertConfiguration(
          "Invalid claims. channels.references[0]: " + CHARACTER_RULE + ".",
          () -> channels("a" + character + "b").build());
    }
  } // end method channelReferenceCharacterClassHasExactEdges

  @Test
  void segmentIdsFollowTheIdentifierRules() {
    Map<Claims.Builder, String> cases =
        Map.of(
            segments(new SegmentClaim("a\n", true, true)),
            "permissions.segments[0].segmentId: Must not contain CR or LF.",
            segments(new SegmentClaim("a\r", true, true)),
            "permissions.segments[0].segmentId: Must not contain CR or LF.",
            segments(new SegmentClaim("", true, true)),
            "permissions.segments[0].segmentId: Must not be empty.",
            segments(new SegmentClaim(null, true, true)),
            "permissions.segments[0].segmentId: Required.",
            segments(new SegmentClaim("s", true, true), null),
            "permissions.segments[1]: Required.",
            segments(new SegmentClaim("chat\ud800", true, false)),
            "permissions.segments[0].segmentId: Must not contain unpaired UTF-16 surrogates.",
            segments(new SegmentClaim("\udc00chat", true, false)),
            "permissions.segments[0].segmentId: Must not contain unpaired UTF-16 surrogates.",
            segments(new SegmentClaim("\ud800𐀀", true, false)),
            "permissions.segments[0].segmentId: Must not contain unpaired UTF-16 surrogates.",
            segments(new SegmentClaim("s", true, false), new SegmentClaim("s", false, true)),
            "permissions.segments: Must not repeat a segment ID.",
            segments(new SegmentClaim("", true, false), new SegmentClaim("", false, true)),
            "permissions.segments[0].segmentId: Must not be empty."
                + " permissions.segments[1].segmentId: Must not be empty.");

    cases.forEach(
        (builder, failure) -> assertConfiguration("Invalid claims. " + failure, builder::build));
  } // end method segmentIdsFollowTheIdentifierRules

  @Test
  void segmentIdsStartingWithCrOrLfAreRefused() {
    for (String leading : List.of("\rchat", "\nchat")) {
      assertConfiguration(
          "Invalid claims. permissions.segments[0].segmentId: Must not contain CR or LF.",
          () -> segments(new SegmentClaim(leading, true, true)).build());
    }
  } // end method segmentIdsStartingWithCrOrLfAreRefused

  @Test
  void validSegmentIdsKeepColonsSpacesControlsAndPairedSurrogates() {
    assertDoesNotThrow(
        () ->
            segments(
                    new SegmentClaim("topic:part", true, false),
                    new SegmentClaim(" 雪 \u0000 \t 😀", false, true),
                    new SegmentClaim("😀", false, false))
                .build());
    assertDoesNotThrow(() -> segments().build());
  } // end method validSegmentIdsKeepColonsSpacesControlsAndPairedSurrogates

  @Test
  void referencesFollowTheColonFreeIdentifierRules() {
    Map<String, String> cases =
        Map.of(
            "user:123", "reference: Must not contain a colon, CR or LF.",
            "user\r", "reference: Must not contain a colon, CR or LF.",
            "user\n", "reference: Must not contain a colon, CR or LF.",
            "", "reference: Must not be empty.",
            "user\udc00", "reference: Must not contain unpaired UTF-16 surrogates.",
            "user\ud800", "reference: Must not contain unpaired UTF-16 surrogates.",
            "a:b\ud800", "reference: Must not contain unpaired UTF-16 surrogates.");

    cases.forEach(
        (reference, failure) ->
            assertConfiguration(
                "Invalid claims. " + failure, () -> valid().reference(reference).build()));

    for (String leading : List.of(":user", "\ruser", "\nuser")) {
      assertConfiguration(
          "Invalid claims. reference: Must not contain a colon, CR or LF.",
          () -> valid().reference(leading).build());
    }

    assertDoesNotThrow(() -> valid().reference("身分 😀\u0000").build());
    assertDoesNotThrow(() -> valid().reference(null).build());
  } // end method referencesFollowTheColonFreeIdentifierRules

  @Test
  void replayLookbackIsWholeMillisecondsWithinTheServersRange() {
    String range =
        "Invalid claims. replay: Lookback must be between 0 and 4294967295 milliseconds.";

    assertConfiguration(range, () -> lookback(Duration.ofMillis(-1)).build());
    assertConfiguration(range, () -> lookback(Duration.ofNanos(-1)).build());
    assertConfiguration(range, () -> lookback(Duration.ofMillis(4_294_967_296L)).build());
    assertConfiguration(
        range, () -> lookback(Duration.ofMillis(4_294_967_295L).plusNanos(1)).build());
    assertConfiguration(range, () -> lookback(Duration.ofSeconds(Long.MAX_VALUE)).build());
    assertConfiguration(
        "Invalid claims. replay: Lookback must be whole milliseconds.",
        () -> lookback(Duration.ofNanos(1_500_000)).build());
    assertConfiguration(
        "Invalid claims. replay: Lookback must be whole milliseconds.",
        () -> lookback(Duration.ofNanos(1)).build());
    assertConfiguration("Invalid claims. replay: Required.", () -> valid().replay(null).build());

    for (Duration accepted :
        List.of(Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(4_294_967_295L))) {
      assertDoesNotThrow(() -> lookback(accepted).build());
    }
  } // end method replayLookbackIsWholeMillisecondsWithinTheServersRange

  @Test
  void everyFailureIsReportedTogetherInFieldOrder() {
    assertConfiguration(
        "Invalid claims. channels.references[1]: Must not be empty."
            + " channels.references: Must not repeat a channel reference."
            + " permissions.segments[0].segmentId: Must not contain CR or LF."
            + " permissions.segments: Must not repeat a segment ID."
            + " reference: Must not contain a colon, CR or LF."
            + " replay: Lookback must be between 0 and 4294967295 milliseconds.",
        () ->
            Claims.builder()
                .channels(ChannelScope.restricted("a", "", "a"))
                .permissions(
                    SegmentPermissions.restricted(
                        new SegmentClaim("s\n", true, true),
                        new SegmentClaim("t", true, true),
                        new SegmentClaim("t", true, true)))
                .reference("user:8317")
                .replay(Replay.lookback(Duration.ofMillis(-1)))
                .build());
  } // end method everyFailureIsReportedTogetherInFieldOrder

  @Test
  void theReadmesExampleFailsAsDocumented() {
    assertConfiguration(
        "Invalid claims. channels.references: Must not repeat a channel reference."
            + " reference: Must not contain a colon, CR or LF.",
        () ->
            Claims.builder()
                .channels(ChannelScope.restricted("room-42", "room-42"))
                .permissions(SegmentPermissions.all(true, false))
                .reference("user:8317")
                .build());
  } // end method theReadmesExampleFailsAsDocumented

  @Test
  void failuresNeverRepeatTheValue() {
    List<Claims.Builder> invalid =
        List.of(
            channels(MARKER + ":"),
            segments(new SegmentClaim(MARKER + "\n", true, true)),
            valid().reference(MARKER + ":"),
            valid().reference(MARKER + "\ud800"));

    for (Claims.Builder builder : invalid) {
      CelerisException failure = assertThrows(CelerisException.class, builder::build);
      assertFalse(failure.getMessage().contains(MARKER), failure.getMessage());
    }
  } // end method failuresNeverRepeatTheValue

  @Test
  void aBuilderCanBeReusedWithoutChangingClaimsAlreadyBuilt() {
    Claims.Builder builder = channels("room-1").reference("user-1");
    Claims first = builder.build();
    builder.reference("user-2").allowEcho(true);
    Claims second = builder.build();

    assertEquals("user-1", first.reference);
    assertFalse(first.allowEcho);
    assertEquals("user-2", second.reference);
  } // end method aBuilderCanBeReusedWithoutChangingClaimsAlreadyBuilt

  @Test
  void callerArraysAreCopied() {
    String[] references = {"room-1"};
    SegmentClaim[] segments = {new SegmentClaim("messages", true, false)};
    Claims claims =
        Claims.builder()
            .channels(ChannelScope.restricted(references))
            .permissions(SegmentPermissions.restricted(segments))
            .build();
    String before = TokenPayload.json(1, claims);

    references[0] = "other";
    segments[0] = new SegmentClaim("messages", true, true);

    assertEquals(before, TokenPayload.json(1, claims));
  } // end method callerArraysAreCopied

  @Test
  void factoriesRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> ChannelScope.restricted((String[]) null));
    assertThrows(
        NullPointerException.class, () -> SegmentPermissions.restricted((SegmentClaim[]) null));
    assertThrows(NullPointerException.class, () -> Replay.lookback(null));
  } // end method factoriesRefuseNullArguments

  @Test
  void signerOptionsNameEveryFailedField() {
    Map<SignerOptions.Builder, String> cases =
        Map.of(
            SignerOptions.builder(),
            "clientId: Required. signingSecret: Required.",
            SignerOptions.builder().clientId("").signingSecret(""),
            "clientId: Must not be empty. signingSecret: Must not be empty.",
            SignerOptions.builder().clientId("a:b").signingSecret("s"),
            "clientId: Must not contain a colon, CR or LF.",
            SignerOptions.builder().clientId("a\r").signingSecret("s"),
            "clientId: Must not contain a colon, CR or LF.",
            SignerOptions.builder().clientId("a\n").signingSecret("s"),
            "clientId: Must not contain a colon, CR or LF.",
            SignerOptions.builder().clientId("client\ud800").signingSecret("s"),
            "clientId: Must not contain unpaired UTF-16 surrogates.",
            SignerOptions.builder().clientId("c").signingSecret(Fixtures.SIGNING_SECRET + "\udc00"),
            "signingSecret: Must not contain unpaired UTF-16 surrogates.",
            SignerOptions.builder().clientId("c"),
            "signingSecret: Required.");

    cases.forEach(
        (builder, failure) -> {
          CelerisException error =
              assertConfiguration("Invalid signer options. " + failure, builder::build);
          assertFalse(error.getMessage().contains(Fixtures.SIGNING_SECRET));
        });

    assertDoesNotThrow(
        () -> SignerOptions.builder().clientId("client-雪").signingSecret("secret:\r\n雪").build());
  } // end method signerOptionsNameEveryFailedField

  @Test
  void theCredentialProviderNeedsAClaimsFunction() {
    Signer signer = Fixtures.signer(Fixtures.clockAt(1));

    assertConfiguration(
        "Invalid credential provider options. claimsFunction: Required.",
        () -> signer.credentialProvider(null));
  } // end method theCredentialProviderNeedsAClaimsFunction
} // end class ValidationTest
