package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.CredentialProvider;
import com.useceleris.client.ErrorCode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live suites' target, read from the repository's .env, with real environment variables taking
 * precedence. A missing variable or an unreachable stack fails the suite; it never skips. Values
 * are never printed.
 */
final class LiveStack {
  static final Map<String, String> ENVIRONMENT = loadEnvironment();

  private static final List<String> REQUIRED =
      List.of("CELERIS_WS_URL", "CELERIS_CLIENT_ID", "CELERIS_SIGNING_SECRET");

  private static final Duration CONNECT_DEADLINE = Duration.ofSeconds(30);

  private static final AtomicLong CHANNEL_COUNTER = new AtomicLong();

  private LiveStack() {}

  private static Map<String, String> loadEnvironment() {
    Map<String, String> environment = new HashMap<>();
    Path file = Path.of(".env");

    if (Files.isRegularFile(file)) {
      try {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          int separator = trimmed.indexOf('=');

          if (trimmed.startsWith("#") || separator < 1) {
            continue;
          }

          String value = trimmed.substring(separator + 1).trim();
          environment.put(
              trimmed.substring(0, separator).trim(), value.replaceAll("^['\"]|['\"]$", ""));
        }
      } catch (IOException unreadable) {
        fail(".env exists but cannot be read.");
      }
    }

    environment.putAll(System.getenv());

    return Map.copyOf(environment);
  } // end method loadEnvironment

  /** Fails at once when the target cannot be used. */
  static void requireReachableStack() {
    for (String name : REQUIRED) {
      if (ENVIRONMENT.getOrDefault(name, "").isEmpty()) {
        fail(
            name
                + " not set. Put CELERIS_WS_URL, CELERIS_CLIENT_ID and CELERIS_SIGNING_SECRET in"
                + " the repository's .env (gitignored) or the environment.");
      }
    }

    URI target = URI.create(websocketUrl());
    int port = target.getPort();

    if (port == -1) {
      port = "wss".equals(target.getScheme()) ? 443 : 80;
    }

    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress(target.getHost(), port), 5_000);
    } catch (IOException unreachable) {
      fail(
          "The realtime service at "
              + target.getHost()
              + ":"
              + port
              + " is not reachable. Start the stack, or point CELERIS_WS_URL elsewhere.");
    }
  } // end method requireReachableStack

  static String websocketUrl() {
    return ENVIRONMENT.get("CELERIS_WS_URL");
  } // end method websocketUrl

  /** The qualification credentials, which a test may change before building. */
  static SignerOptions.Builder signerOptions() {
    return SignerOptions.builder()
        .clientId(ENVIRONMENT.get("CELERIS_CLIENT_ID"))
        .signingSecret(ENVIRONMENT.get("CELERIS_SIGNING_SECRET"));
  } // end method signerOptions

  static String uniqueChannelReference(String label) {
    return "javaqual-server-"
        + label
        + "-"
        + System.currentTimeMillis()
        + "-"
        + CHANNEL_COUNTER.incrementAndGet();
  } // end method uniqueChannelReference

  /** Grants every segment of one channel. */
  static Claims.Builder allPermissionClaims(String channelReference) {
    return Claims.builder()
        .channels(ChannelScope.restricted(channelReference))
        .permissions(SegmentPermissions.all(true, true));
  } // end method allPermissionClaims

  static ClaimsFunction fixedClaims(Claims claims) {
    return request -> CompletableFuture.completedFuture(claims);
  } // end method fixedClaims

  static CelerisClient client(CredentialProvider credentialProvider) {
    return CelerisClient.create(
        ClientOptions.builder(credentialProvider)
            .baseUrl(websocketUrl())
            .allowInsecureLoopback(true)
            .build());
  } // end method client

  static CelerisClient client(SignerOptions.Builder options, ClaimsFunction claimsFunction) {
    return client(Signer.create(options.build()).credentialProvider(claimsFunction));
  } // end method client

  static Channel connected(CelerisClient client, String channelReference) throws Exception {
    Channel channel = client.channel(channelReference);

    try {
      channel.connect().get(CONNECT_DEADLINE.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception failure) {
      channel.close();

      throw failure;
    }

    return channel;
  } // end method connected

  /** Asserts a refused connection: a transport failure, as every refused handshake is (DEV-02). */
  static CelerisException assertRefused(Channel channel) throws Exception {
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> channel.connect().get(CONNECT_DEADLINE.toSeconds(), TimeUnit.SECONDS));
    CelerisException refused = assertInstanceOf(CelerisException.class, failure.getCause());
    assertEquals(ErrorCode.TRANSPORT, refused.code());

    return refused;
  } // end method assertRefused

  static <Value> Value next(BlockingQueue<Value> queue, Duration timeout, String description)
      throws InterruptedException {
    Value value = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);

    if (value == null) {
      fail("timed out waiting for " + description);
    }

    return value;
  } // end method next
} // end class LiveStack
