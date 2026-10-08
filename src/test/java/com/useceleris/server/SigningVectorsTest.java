package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.useceleris.client.Credentials;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Credentials signed by independent implementations, every expected payload and signature copied
 * unchanged: shared-signing-vectors.json holds the reference SDK's five vectors, generated with
 * Python's json, base64, hmac and hashlib, and the escapes vector the Python port generated with
 * the reference signer; reference-signing-vectors.json is the Go port's copy of eighteen vectors
 * the JavaScript reference signer generated. Claims use the reference's JSON shape.
 */
class SigningVectorsTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @TestFactory
  Stream<DynamicTest> signerMatchesIndependentVectors() throws IOException {
    List<JsonNode> vectors = new ArrayList<>();
    vectors.addAll(read("/shared-signing-vectors.json"));
    vectors.addAll(read("/reference-signing-vectors.json"));
    assertEquals(24, vectors.size());

    return vectors.stream()
        .map(vector -> DynamicTest.dynamicTest(vector.get("name").asString(), () -> check(vector)));
  } // end method signerMatchesIndependentVectors

  private static void check(JsonNode vector) {
    Signer signer =
        Signer.create(
            SignerOptions.builder()
                .clientId(vector.get("clientId").asString())
                .signingSecret(vector.get("signingSecret").asString())
                .clock(Fixtures.clockAt(vector.get("timestamp").longValue()))
                .build());
    Credentials credentials = signer.sign(claims(vector.get("claims")));
    JsonNode expected = vector.get("expected");
    assertEquals(expected.get("payload").asString(), credentials.payload());
    assertEquals(expected.get("signature").asString(), credentials.signature());
  } // end method check

  private static Claims claims(JsonNode claims) {
    JsonNode channels = claims.get("channels");
    JsonNode permissions = claims.get("permissions");
    Claims.Builder builder = Claims.builder();

    if ("all".equals(channels.get("kind").asString())) {
      builder.channels(ChannelScope.all());
    } else {
      List<String> references = new ArrayList<>();
      channels.get("references").forEach(reference -> references.add(reference.asString()));
      builder.channels(ChannelScope.restricted(references.toArray(String[]::new)));
    }

    if ("all".equals(permissions.get("kind").asString())) {
      builder.permissions(
          SegmentPermissions.all(
              permissions.get("read").booleanValue(), permissions.get("write").booleanValue()));
    } else {
      List<SegmentClaim> segments = new ArrayList<>();
      permissions
          .get("segments")
          .forEach(
              segment ->
                  segments.add(
                      new SegmentClaim(
                          segment.get("segmentId").asString(),
                          segment.get("read").booleanValue(),
                          segment.get("write").booleanValue())));
      builder.permissions(SegmentPermissions.restricted(segments.toArray(SegmentClaim[]::new)));
    }

    if (claims.has("reference")) {
      builder.reference(claims.get("reference").asString());
    }

    JsonNode replay = claims.path("replay");

    if (replay.isObject()) {
      builder.replay(Replay.lookback(Duration.ofMillis(replay.get("lookbackMs").longValue())));
    } else if (replay.asBoolean(false)) {
      builder.replay(Replay.backlog());
    }

    return builder.allowEcho(claims.path("allowEcho").asBoolean(false)).build();
  } // end method claims

  private static List<JsonNode> read(String resource) throws IOException {
    try (InputStream input =
        Objects.requireNonNull(SigningVectorsTest.class.getResourceAsStream(resource))) {
      List<JsonNode> vectors = new ArrayList<>();
      MAPPER.readTree(input).forEach(vectors::add);

      return vectors;
    }
  } // end method read
} // end class SigningVectorsTest
