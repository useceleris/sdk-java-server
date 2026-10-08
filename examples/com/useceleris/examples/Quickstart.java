package com.useceleris.examples;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.Channel;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.CredentialRequest;
import com.useceleris.client.Payloads;
import com.useceleris.client.Segment;
import com.useceleris.server.ChannelScope;
import com.useceleris.server.Claims;
import com.useceleris.server.Replay;
import com.useceleris.server.SegmentPermissions;
import com.useceleris.server.Signer;
import com.useceleris.server.SignerOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A trusted backend worker that signs its own credentials and consumes realtime through the client:
 * the pattern for a backend process, not a browser. The signing secret never leaves this process.
 *
 * <p>Run it with CELERIS_WS_URL, CELERIS_CLIENT_ID and CELERIS_SIGNING_SECRET set. It runs until
 * interrupted.
 */
public final class Quickstart {
  private Quickstart() {}

  /**
   * Decides fresh claims for every connection attempt. It is authoritative: nothing in the request
   * widens its scope.
   */
  static CompletionStage<Claims> claimsFor(CredentialRequest request) {
    Claims.Builder claims =
        Claims.builder()
            .channels(ChannelScope.restricted(request.channelReference()))
            .permissions(SegmentPermissions.all(true, true))
            .reference("server-quickstart")
            // This connection sees its own publishes.
            .allowEcho(true);

    // On reconnect, catch up on what the outage missed.
    request.replayLookback().ifPresent(lookback -> claims.replay(Replay.lookback(lookback)));

    return CompletableFuture.completedFuture(claims.build());
  } // end method claimsFor

  public static void main(String[] arguments) throws InterruptedException {
    // From trusted configuration, never from code.
    Signer signer =
        Signer.create(
            SignerOptions.builder()
                .clientId(System.getenv("CELERIS_CLIENT_ID"))
                .signingSecret(System.getenv("CELERIS_SIGNING_SECRET"))
                .build());
    CelerisClient client =
        CelerisClient.create(
            ClientOptions.builder(signer.credentialProvider(Quickstart::claimsFor))
                .baseUrl(System.getenv("CELERIS_WS_URL"))
                // A local ws:// stack; production uses the built-in wss:// endpoint.
                .allowInsecureLoopback(true)
                .build());
    Channel channel = client.channel("server-quickstart-" + System.currentTimeMillis());

    // Interrupting the process, as Ctrl-C does, closes the channel.
    Runtime.getRuntime().addShutdownHook(new Thread(channel::close));
    channel.events().onError(error -> System.out.println("error: " + error.getMessage()));
    channel.connect().join();

    Segment jobs = channel.segment("jobs");
    jobs.onMessage(
        (payload, metadata) -> System.out.println("received " + Payloads.readText(payload)));
    jobs.subscribe();

    // Subscriptions are not acknowledged; give this one a moment before publishing to it.
    Thread.sleep(1000);
    jobs.publish(Payloads.text("job-1 done")).join();
    System.out.println("example: running until interrupted");
    Thread.currentThread().join();
  } // end method main
} // end class Quickstart
