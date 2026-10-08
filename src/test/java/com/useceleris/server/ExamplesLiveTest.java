package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.Credentials;
import com.useceleris.client.Payloads;
import com.useceleris.client.ServerErrorException;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Both examples, run as their own processes, against a real Celeris stack. */
@Tag("live")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ExamplesLiveTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @BeforeAll
  static void requireStack() {
    LiveStack.requireReachableStack();
  } // end method requireStack

  /** Starts an example's main class with the test classpath and the live environment. */
  private static Process start(String mainClass, Map<String, String> environment)
      throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                mainClass)
            .redirectErrorStream(true);
    builder.environment().putAll(LiveStack.ENVIRONMENT);
    builder.environment().putAll(environment);

    return builder.start();
  } // end method start

  /** Reads a process's output on a thread of its own, one line at a time. */
  private static BlockingQueue<String> output(Process process) {
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader input = process.inputReader(StandardCharsets.UTF_8)) {
                for (String line = input.readLine(); line != null; line = input.readLine()) {
                  lines.add(line);
                }
              } catch (IOException closed) {
                // The process ended.
              }
            });

    reader.setDaemon(true);
    reader.start();

    return lines;
  } // end method output

  private static void awaitLine(
      BlockingQueue<String> lines, Predicate<String> expected, String description)
      throws InterruptedException {
    List<String> seen = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();

    while (System.nanoTime() < deadline) {
      String line = lines.poll(250, TimeUnit.MILLISECONDS);

      if (line != null && expected.test(line)) {
        return;
      }

      if (line != null) {
        seen.add(line);
      }
    }

    fail("timed out waiting for " + description + "; output:\n" + String.join("\n", seen));
  } // end method awaitLine

  /** Interrupts the process as Ctrl-C would, then waits for it to exit. */
  private static void interrupt(Process process) throws InterruptedException {
    process.destroy();

    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("the example did not exit when interrupted");
    }
  } // end method interrupt

  @Test
  void runsTheQuickstartUntilInterrupted() throws Exception {
    Process quickstart = start("com.useceleris.examples.Quickstart", Map.of());

    try {
      awaitLine(output(quickstart), "received job-1 done"::equals, "the quickstart's own job");

      assertTrue(quickstart.isAlive(), "the quickstart stopped before it was interrupted");
    } finally {
      interrupt(quickstart);
    }
  } // end method runsTheQuickstartUntilInterrupted

  // The credential endpoint authenticates and authorizes server-side, and its credentials drive a
  // real connection: the demo user reads "chat" but may not write to it.
  @Test
  void mintsCredentialsThroughTheEndpointAndConnectsWithThem() throws Exception {
    int port;

    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }

    Process endpoint =
        start(
            "com.useceleris.examples.CredentialEndpoint",
            Map.of("CELERIS_EXAMPLE_PORT", String.valueOf(port)));

    try {
      awaitLine(output(endpoint), line -> line.contains("listening"), "the endpoint to listen");
      URI uri = URI.create("http://127.0.0.1:" + port + "/realtime-credentials");
      HttpClient http = HttpClient.newHttpClient();

      assertEquals(
          401, post(http, uri, "Bearer wrong", Map.of("channelReference", "room-42")).statusCode());
      assertEquals(
          403,
          post(http, uri, "Bearer demo-session", Map.of("channelReference", "other-room"))
              .statusCode());
      assertEquals(
          400,
          post(
                  http,
                  uri,
                  "Bearer demo-session",
                  Map.of("channelReference", "room-42", "padding", "x".repeat(4096)))
              .statusCode());

      // A lookback the client asks for is clamped by the endpoint's policy, not refused.
      HttpResponse<byte[]> clamped =
          post(
              http,
              uri,
              "Bearer demo-session",
              Map.of("channelReference", "room-42", "replayLookbackMs", -1));

      assertEquals(200, clamped.statusCode());
      assertTrue(payload(clamped).contains("\"replay\":0,"), "lookback not clamped");

      HttpResponse<byte[]> granted =
          post(http, uri, "Bearer demo-session", Map.of("channelReference", "room-42"));

      assertEquals(200, granted.statusCode());
      assertEquals(
          "application/json", granted.headers().firstValue("Content-Type").orElse("missing"));

      Credentials credentials = MAPPER.readValue(granted.body(), Credentials.class);
      BlockingQueue<ServerErrorException> denials = new LinkedBlockingQueue<>();

      try (Channel reader =
          LiveStack.connected(
              LiveStack.client(request -> CompletableFuture.completedFuture(credentials)),
              "room-42")) {
        reader
            .events()
            .onError(
                error -> {
                  if (error instanceof ServerErrorException serverError
                      && ServerErrorException.PERMISSION_DENIED_ERROR.equals(serverError.type())) {
                    denials.add(serverError);
                  }
                });

        reader.segment("chat").subscribe();
        Thread.sleep(1_500);
        reader.segment("chat").publish(Payloads.text("should be denied")).get(15, TimeUnit.SECONDS);
        LiveStack.next(denials, Duration.ofSeconds(15), "the PermissionDeniedError");

        assertEquals(ChannelState.CONNECTED, reader.state());
      }
    } finally {
      interrupt(endpoint);
    }
  } // end method mintsCredentialsThroughTheEndpointAndConnectsWithThem

  private static HttpResponse<byte[]> post(
      HttpClient http, URI uri, String authorization, Map<String, ?> body)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(15))
            .build();

    return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
  } // end method post

  private static String payload(HttpResponse<byte[]> response) {
    Credentials credentials = MAPPER.readValue(response.body(), Credentials.class);

    return new String(Base64.getDecoder().decode(credentials.payload()), StandardCharsets.UTF_8);
  } // end method payload
} // end class ExamplesLiveTest
