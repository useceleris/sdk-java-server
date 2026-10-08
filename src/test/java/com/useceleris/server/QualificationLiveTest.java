package com.useceleris.server;

import static com.useceleris.server.LiveStack.allPermissionClaims;
import static com.useceleris.server.LiveStack.assertRefused;
import static com.useceleris.server.LiveStack.client;
import static com.useceleris.server.LiveStack.connected;
import static com.useceleris.server.LiveStack.fixedClaims;
import static com.useceleris.server.LiveStack.next;
import static com.useceleris.server.LiveStack.signerOptions;
import static com.useceleris.server.LiveStack.uniqueChannelReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.MessageMetadata;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresenceEvent;
import com.useceleris.client.PresencePage;
import com.useceleris.client.Segment;
import com.useceleris.client.ServerErrorException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Server-signed credentials against a real Celeris stack, through the real client. */
@Tag("live")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class QualificationLiveTest {
  private static final Pattern GENERATED_MESSAGE_ID = Pattern.compile("[0-9a-f]{32}");

  private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

  // Subscriptions are not acknowledged; this gives one time to take effect.
  private static final long SUBSCRIPTION_SETTLE_MILLISECONDS = 1_500;

  private static final String ACTOR = "javaqual-server-actor";

  record Delivery(byte[] payload, MessageMetadata metadata) {
    String text() {
      return new String(payload, StandardCharsets.UTF_8);
    } // end method text
  } // end record Delivery

  @BeforeAll
  static void requireStack() {
    LiveStack.requireReachableStack();
  } // end method requireStack

  private static BlockingQueue<Delivery> deliveries(Segment segment) {
    BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
    segment.onMessage((payload, metadata) -> deliveries.add(new Delivery(payload, metadata)));

    return deliveries;
  } // end method deliveries

  private static BlockingQueue<ServerErrorException> permissionDenials(Channel channel) {
    BlockingQueue<ServerErrorException> denials = new LinkedBlockingQueue<>();
    channel
        .events()
        .onError(
            error -> {
              if (error instanceof ServerErrorException serverError
                  && ServerErrorException.PERMISSION_DENIED_ERROR.equals(serverError.type())) {
                denials.add(serverError);
              }
            });

    return denials;
  } // end method permissionDenials

  @Test
  void connectsWithServerSignedCredentialsAndIsGreeted() throws Exception {
    String reference = uniqueChannelReference("auth");
    CelerisClient client =
        client(signerOptions(), fixedClaims(allPermissionClaims(reference).build()));
    BlockingQueue<String> notices = new LinkedBlockingQueue<>();

    try (Channel channel = client.channel(reference)) {
      channel
          .events()
          .onNotice(notice -> notices.add(new String(notice.payload(), StandardCharsets.UTF_8)));
      channel.connect().get(30, TimeUnit.SECONDS);
      String greeting = next(notices, DELIVERY_TIMEOUT, "a greeting");

      assertTrue(greeting.contains("Successfully connected"), greeting);
    }
  } // end method connectsWithServerSignedCredentialsAndIsGreeted

  @Test
  void refusesAWrongSecretAndAnUnknownClientAsTransport() throws Exception {
    String reference = uniqueChannelReference("reject");
    Claims claims = allPermissionClaims(reference).build();

    for (SignerOptions.Builder options :
        List.of(
            signerOptions().signingSecret("wrong-signing-secret"),
            signerOptions().clientId("javaqual-unknown"))) {
      try (Channel channel = client(options, fixedClaims(claims)).channel(reference)) {
        CelerisException refused = assertRefused(channel);

        assertEquals(ChannelState.FAILED, channel.state());
        assertFalse(refused.getMessage().contains("wrong-signing-secret"));
      }
    }
  } // end method refusesAWrongSecretAndAnUnknownClientAsTransport

  // D-001: the server accepts a signed timestamp that is at most 60 seconds old and not in the
  // future.
  @Test
  void refusesTimestampsOutsideTheSixtySecondWindow() throws Exception {
    String reference = uniqueChannelReference("window");
    Claims claims = allPermissionClaims(reference).build();

    for (Duration offset :
        List.of(Duration.ofSeconds(-61), Duration.ofMinutes(-59), Duration.ofMinutes(5))) {
      SignerOptions.Builder options =
          signerOptions().clock(Clock.offset(Clock.systemUTC(), offset));

      try (Channel channel = client(options, fixedClaims(claims)).channel(reference)) {
        assertRefused(channel);
      }
    }

    SignerOptions.Builder recent =
        signerOptions().clock(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(-30)));

    try (Channel channel = connected(client(recent, fixedClaims(claims)), reference)) {
      assertEquals(ChannelState.CONNECTED, channel.state());
    }
  } // end method refusesTimestampsOutsideTheSixtySecondWindow

  @Test
  void enforcesTheTokensChannelRestriction() throws Exception {
    String allowed = uniqueChannelReference("scope-allowed");
    CelerisClient client =
        client(signerOptions(), fixedClaims(allPermissionClaims(allowed).build()));

    try (Channel denied = client.channel(uniqueChannelReference("scope-denied"))) {
      assertRefused(denied);
    }

    try (Channel channel = connected(client, allowed)) {
      assertEquals(ChannelState.CONNECTED, channel.state());
    }
  } // end method enforcesTheTokensChannelRestriction

  @Test
  void roundTripsAPayloadWithItsIdAndNoSelfEcho() throws Exception {
    String reference = uniqueChannelReference("msg");
    CelerisClient client =
        client(signerOptions(), fixedClaims(allPermissionClaims(reference).build()));

    try (Channel publisher = connected(client, reference);
        Channel receiver = connected(client, reference)) {
      Segment published = publisher.segment("chat");
      BlockingQueue<Delivery> echoes = deliveries(published);
      BlockingQueue<Delivery> received = deliveries(receiver.segment("chat"));
      published.subscribe();
      receiver.segment("chat").subscribe();
      Thread.sleep(SUBSCRIPTION_SETTLE_MILLISECONDS);

      published.publish("hello-서버".getBytes(StandardCharsets.UTF_8)).get(15, TimeUnit.SECONDS);
      Delivery delivery = next(received, DELIVERY_TIMEOUT, "the cross-connection delivery");

      assertTrue(GENERATED_MESSAGE_ID.matcher(delivery.metadata().messageId()).matches());
      assertEquals("hello-서버", delivery.text());
      assertNull(echoes.poll(1_500, TimeUnit.MILLISECONDS), "echoed without allowEcho");
    }
  } // end method roundTripsAPayloadWithItsIdAndNoSelfEcho

  @Test
  void echoesToThePublisherWhenClaimsAllowEcho() throws Exception {
    String reference = uniqueChannelReference("echo");
    Claims claims = allPermissionClaims(reference).allowEcho(true).build();

    try (Channel channel = connected(client(signerOptions(), fixedClaims(claims)), reference)) {
      Segment chat = channel.segment("chat");
      BlockingQueue<Delivery> echoes = deliveries(chat);
      chat.subscribe();
      Thread.sleep(SUBSCRIPTION_SETTLE_MILLISECONDS);

      chat.publish(Payloads.text("self")).get(15, TimeUnit.SECONDS);

      assertEquals("self", next(echoes, DELIVERY_TIMEOUT, "an echoed publish").text());
    }
  } // end method echoesToThePublisherWhenClaimsAllowEcho

  @Test
  void deniesAReadOnlyRestrictedSegmentsPublish() throws Exception {
    String reference = uniqueChannelReference("perm");
    Claims claims =
        Claims.builder()
            .channels(ChannelScope.restricted(reference))
            .permissions(SegmentPermissions.restricted(new SegmentClaim("chat", true, false)))
            .build();

    try (Channel readOnly = connected(client(signerOptions(), fixedClaims(claims)), reference)) {
      BlockingQueue<ServerErrorException> denials = permissionDenials(readOnly);

      // Publish completes locally; the denial arrives later, naming the segment.
      readOnly.segment("chat").publish(Payloads.text("denied")).get(15, TimeUnit.SECONDS);
      ServerErrorException denial = next(denials, DELIVERY_TIMEOUT, "the PermissionDeniedError");

      assertEquals(Optional.of("PUB"), denial.subType());
      assertEquals(Optional.of("chat"), denial.resource());
      assertEquals(ChannelState.CONNECTED, readOnly.state());
    }
  } // end method deniesAReadOnlyRestrictedSegmentsPublish

  @Test
  void replaysRecentMessagesThroughTheClaimsFunction() throws Exception {
    String reference = uniqueChannelReference("replay");
    CelerisClient liveClient =
        client(signerOptions(), fixedClaims(allPermissionClaims(reference).build()));
    Map<String, String> liveIds = new HashMap<>();

    try (Channel publisher = connected(liveClient, reference)) {
      try (Channel liveReceiver = connected(liveClient, reference)) {
        Segment history = liveReceiver.segment("history");
        BlockingQueue<Delivery> live = deliveries(history);
        history.subscribe();
        Thread.sleep(SUBSCRIPTION_SETTLE_MILLISECONDS);

        for (String body : List.of("one", "two", "three")) {
          publisher.segment("history").publish(Payloads.text(body)).get(15, TimeUnit.SECONDS);
        }

        while (liveIds.size() < 3) {
          Delivery delivery = next(live, Duration.ofSeconds(20), "the three live deliveries");
          liveIds.put(delivery.text(), delivery.metadata().messageId());
        }
      }

      // The claims function applies the request's replay lookback, or a minute on a first
      // connect.
      ClaimsFunction replaying =
          request ->
              CompletableFuture.completedFuture(
                  allPermissionClaims(reference)
                      .replay(
                          Replay.lookback(request.replayLookback().orElse(Duration.ofMinutes(1))))
                      .build());
      Map<String, String> replayedIds = new HashMap<>();

      try (Channel replayReceiver = connected(client(signerOptions(), replaying), reference)) {
        Segment history = replayReceiver.segment("history");
        BlockingQueue<Delivery> replayed = deliveries(history);
        history.subscribe();

        while (replayedIds.size() < 3) {
          Delivery delivery = next(replayed, Duration.ofSeconds(25), "the replayed history");
          replayedIds.put(delivery.text(), delivery.metadata().messageId());
        }

        assertEquals(liveIds, replayedIds);
      }
    }
  } // end method replaysRecentMessagesThroughTheClaimsFunction

  @Test
  void surfacesPresenceForAServerSignedReference() throws Exception {
    String reference = uniqueChannelReference("presence");
    Claims claims = allPermissionClaims(reference).build();
    Claims actorClaims = allPermissionClaims(reference).reference(ACTOR).build();

    try (Channel watcher = connected(client(signerOptions(), fixedClaims(claims)), reference)) {
      Segment watched = watcher.segment("chat");
      BlockingQueue<PresenceEvent> joins = new LinkedBlockingQueue<>();
      watched.onPresence(
          event -> {
            if (event.joined() && ACTOR.equals(event.tokenReference())) {
              joins.add(event);
            }
          });

      watched.subscribePresence();
      Thread.sleep(SUBSCRIPTION_SETTLE_MILLISECONDS);

      try (Channel actor =
          connected(client(signerOptions(), fixedClaims(actorClaims)), reference)) {
        actor.segment("chat").subscribe();
        PresenceEvent join = next(joins, Duration.ofSeconds(20), "the actor's presence join");

        assertEquals("chat", join.segmentId());
        assertFalse(join.connectionId().isEmpty());

        PresencePage page = watched.presenceList(1, 10).get(15, TimeUnit.SECONDS);

        assertTrue(page.total() >= 1);
        assertTrue(
            page.connections().stream()
                .anyMatch(connection -> ACTOR.equals(connection.tokenReference())));
      }
    }
  } // end method surfacesPresenceForAServerSignedReference
} // end class QualificationLiveTest
