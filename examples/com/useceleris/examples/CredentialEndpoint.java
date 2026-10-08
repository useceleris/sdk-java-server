package com.useceleris.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Credentials;
import com.useceleris.server.ChannelScope;
import com.useceleris.server.Claims;
import com.useceleris.server.Replay;
import com.useceleris.server.SegmentClaim;
import com.useceleris.server.SegmentPermissions;
import com.useceleris.server.Signer;
import com.useceleris.server.SignerOptions;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoint every browser or mobile application needs. It authenticates its own user and decides
 * the claims server-side; a permission the client asks for is never trusted.
 *
 * <p>Run it with CELERIS_CLIENT_ID and CELERIS_SIGNING_SECRET set, then POST {@code
 * {"channelReference": "room-42"}} to {@code /realtime-credentials} with {@code Authorization:
 * Bearer demo-session}.
 */
public final class CredentialEndpoint {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  // A credential request is a few dozen bytes; anything larger is refused unread.
  private static final int MAXIMUM_BODY_BYTES = 4096;

  // The application's replay policy: catch up on at most thirty seconds, whatever the client asks.
  private static final Duration MAXIMUM_REPLAY_LOOKBACK = Duration.ofSeconds(30);

  /** A user your own authentication established. */
  record User(String id, List<String> rooms, boolean moderator) {}

  private CredentialEndpoint() {}

  /**
   * Stands in for YOUR authentication: a session cookie, bearer token or your framework's user. It
   * never comes from the request body.
   */
  static Optional<User> authenticate(HttpExchange exchange) {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");

    if (!"Bearer demo-session".equals(authorization)) {
      return Optional.empty();
    }

    return Optional.of(new User("user-8317", List.of("room-42"), false));
  } // end method authenticate

  /** Decides scope on the server. A moderator may write; everyone else reads. */
  static Claims claimsFor(User user, String channelReference, Duration requestedLookback) {
    Duration lookback = requestedLookback.isNegative() ? Duration.ZERO : requestedLookback;

    return Claims.builder()
        .channels(ChannelScope.restricted(channelReference))
        .permissions(
            SegmentPermissions.restricted(
                new SegmentClaim("chat", true, user.moderator()),
                new SegmentClaim("presence", true, false)))
        // The identity peers see: no colon, CR or LF.
        .reference(user.id())
        // An application policy, not a retention promise.
        .replay(
            Replay.lookback(
                lookback.compareTo(MAXIMUM_REPLAY_LOOKBACK) > 0
                    ? MAXIMUM_REPLAY_LOOKBACK
                    : lookback))
        .build();
  } // end method claimsFor

  static void handle(HttpExchange exchange, Signer signer) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      send(exchange, 405, "method not allowed");

      return;
    }

    Optional<User> user = authenticate(exchange);

    if (user.isEmpty()) {
      send(exchange, 401, "unauthenticated");

      return;
    }

    byte[] bytes;

    try (InputStream input = exchange.getRequestBody()) {
      bytes = input.readNBytes(MAXIMUM_BODY_BYTES + 1);
    }

    JsonNode body = bytes.length > MAXIMUM_BODY_BYTES ? null : parse(bytes);

    if (body == null || !body.isObject()) {
      send(exchange, 400, "invalid request");

      return;
    }

    String channelReference = body.path("channelReference").stringValue(null);
    Duration requestedLookback = Duration.ofMillis(body.path("replayLookbackMs").asLong(0));

    // Authorize the requested channel against what the user may access.
    if (channelReference == null || !user.get().rooms().contains(channelReference)) {
      send(exchange, 403, "forbidden");

      return;
    }

    Credentials credentials;

    try {
      // Sign fresh per request; never cache or backdate credentials.
      credentials = signer.sign(claimsFor(user.get(), channelReference, requestedLookback));
    } catch (CelerisException failure) {
      send(exchange, 500, "signing failed");

      return;
    }

    // {"payload":"...","signature":"..."}, the shape every Celeris client expects.
    byte[] response = MAPPER.writeValueAsBytes(credentials);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
  } // end method handle

  private static JsonNode parse(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (JacksonException invalid) {
      return null;
    }
  } // end method parse

  private static void send(HttpExchange exchange, int status, String message) throws IOException {
    byte[] response = message.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, response.length);
    exchange.getResponseBody().write(response);
  } // end method send

  public static void main(String[] arguments) throws IOException {
    // From trusted configuration, never from code.
    Signer signer =
        Signer.create(
            SignerOptions.builder()
                .clientId(System.getenv("CELERIS_CLIENT_ID"))
                .signingSecret(System.getenv("CELERIS_SIGNING_SECRET"))
                .build());

    // A request must arrive within five seconds, so slow clients cannot hold connections open.
    System.setProperty("sun.net.httpserver.maxReqTime", "5");

    int port = Integer.parseInt(System.getenv().getOrDefault("CELERIS_EXAMPLE_PORT", "8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext(
        "/realtime-credentials",
        exchange -> {
          try {
            handle(exchange, signer);
          } finally {
            exchange.close();
          }
        });

    // A bounded pool: signing is fast, and the request time limit bounds slow clients.
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.err.println("example: credential endpoint listening on 127.0.0.1:" + port);
  } // end method main
} // end class CredentialEndpoint
