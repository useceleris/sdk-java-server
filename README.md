# Celeris Java server

[![Maven Central](https://img.shields.io/maven-central/v/com.useceleris/celeris-server.svg)](https://central.sonatype.com/artifact/com.useceleris/celeris-server)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Mints Celeris credentials on a trusted server. Your server authenticates a user, decides what they may do, and signs those claims; the user's application connects with the result through [sdk-java-client](https://github.com/useceleris/sdk-java-client) or any other Celeris SDK, and never sees the signing secret.

## Install

```xml
<dependency>
  <groupId>com.useceleris</groupId>
  <artifactId>celeris-server</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.useceleris:celeris-server:0.1.0")
```

Java 17 or newer, module `com.useceleris.server`. It signs with the JDK alone and brings in the client, `com.useceleris:celeris-client`, for the credential types and errors; `requires com.useceleris.server;` reads the client too.

## Sign credentials

```java
// From trusted configuration, never from code.
Signer signer =
    Signer.create(
        SignerOptions.builder()
            .clientId(System.getenv("CELERIS_CLIENT_ID"))
            .signingSecret(System.getenv("CELERIS_SIGNING_SECRET"))
            .build());

// Claims your server decided for a user it authenticated.
Credentials credentials =
    signer.sign(
        Claims.builder()
            .channels(ChannelScope.restricted("room-42"))
            .permissions(SegmentPermissions.restricted(new SegmentClaim("chat", true, true)))
            .reference("user-8317")
            .replay(Replay.lookback(Duration.ofSeconds(30)))
            .build());

// Your credential endpoint's response body: {"payload":"...","signature":"..."}
byte[] body = mapper.writeValueAsBytes(credentials);
```

`sign` is synchronous and stamps the current time. Sign afresh for every request: never cache, reuse or backdate credentials. A `Signer` is safe for concurrent use and holds no resources, so create one and share it. `Credentials` serializes the same way with Gson.

Keep the signing secret on trusted servers, and never ship it, or this library, in an application. `Signer`, `SignerOptions` and `Credentials` print without their secrets; [SECURITY.md](SECURITY.md) lists what else the library guarantees.

## Claims

Channels and permissions have no default, so unrestricted access is always explicit:

```java
Claims readEverything =
    Claims.builder()
        .channels(ChannelScope.all())
        .permissions(SegmentPermissions.all(true, false))
        .replay(Replay.backlog())
        .build();
```

| Builder method | Values                                                                                                                                                                | Default         |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| `channels`     | `ChannelScope.all()` or `ChannelScope.restricted(references...)`: at least one, none repeated, each 1–255 ASCII letters, digits, `-` or `_`                          | required        |
| `permissions`  | `SegmentPermissions.all(read, write)` or `SegmentPermissions.restricted(segmentClaims...)`, segment ids non-empty without CR or LF, none repeated; none listed grants nothing | required        |
| `reference`    | the identity peers see in deliveries and presence: non-empty, without a colon, CR or LF                                                                               | omitted         |
| `replay`       | `Replay.none()`, `Replay.backlog()` for all the server retains, or `Replay.lookback(duration)` in whole milliseconds, 0–4294967295 ms                                 | `Replay.none()` |
| `allowEcho`    | whether a connection receives its own publishes                                                                                                                       | `false`         |

`build()` checks every rule, so claims that exist are valid; every signed string must also be well-formed UTF-16. Replay applies each time the connection joins a segment, and `Replay.lookback(Duration.ZERO)` differs from `Replay.none()`: it replays nothing older than the join.

## A credential endpoint

Every browser or mobile application needs one. Authenticate the user with your own session, check they may use the channel they asked for, decide their claims yourself (never sign permissions a client asked for), sign, and return the credentials as JSON. A reconnecting client sends the replay lookback it wants; clamp it to your own policy.

[examples/CredentialEndpoint.java](examples/com/useceleris/examples/CredentialEndpoint.java) is a whole endpoint on the JDK's `HttpServer`: it authenticates a bearer token, reads at most 4096 bytes of request, authorizes the requested channel, clamps the requested lookback to thirty seconds, and bounds request time and threads. Replace its authentication and replay policy with yours.

## A backend that consumes realtime

A trusted server that connects itself skips the endpoint: `credentialProvider` turns the signer into the client's `CredentialProvider`.

```java
CredentialProvider provider =
    signer.credentialProvider(
        request -> {
          Claims.Builder claims =
              Claims.builder()
                  .channels(ChannelScope.restricted(request.channelReference()))
                  .permissions(SegmentPermissions.all(true, true));

          // On reconnect, catch up on what the outage missed.
          request.replayLookback().ifPresent(lookback -> claims.replay(Replay.lookback(lookback)));

          return CompletableFuture.completedFuture(claims.build());
        });

CelerisClient client = CelerisClient.create(ClientOptions.builder(provider).build());
```

Every attempt calls your claims function afresh, on the thread that asks for credentials, and signs with a fresh timestamp; nothing in the request widens what the function returns. It returns a `CompletionStage`, so it may look claims up asynchronously. Cancelling an attempt cancels the claims stage, and claims that arrive afterwards are never signed. If the function fails or its claims do not build, the client's `connect()` reports a generic `TRANSPORT`; build the claims directly to see which rule failed. [examples/Quickstart.java](examples/com/useceleris/examples/Quickstart.java) is the whole flow as a program.

## Errors

Invalid options or claims throw `CelerisException` with `ErrorCode.CONFIGURATION`, naming every field and rule that failed but never the value:

```java
try {
  Claims.builder()
      .channels(ChannelScope.restricted("room-42", "room-42"))
      .permissions(SegmentPermissions.all(true, false))
      .reference("user:8317")
      .build();
} catch (CelerisException failure) {
  // failure.getMessage() is "Invalid claims. channels.references: Must not repeat a channel
  // reference. reference: Must not contain a colon, CR or LF."
}
```

`sign` fails the same way when the clock reads a time outside the range the server accepts.

## More documentation

- [Java server guide](https://useceleris.com/docs/sdks/java/server) and [Java server API reference](https://useceleris.com/docs/api-reference/java-server)
- [Authentication](https://useceleris.com/docs/getting-started/authentication), for how credentials work across SDKs

Releases follow semantic versioning; before 1.0.0, a minor release may change the API. To contribute, read [CONVENTIONS.md](CONVENTIONS.md); to report a vulnerability, [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE).
