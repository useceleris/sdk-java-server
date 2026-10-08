package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.useceleris.client.Credentials;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Neither the signing secret nor credentials ever print. */
class RedactionTest {
  private static final String SECRET = "synthetic-printable-secret";

  /** Every common way to print a value; a collection prints each element's own text. */
  private static List<String> renderings(Object value) {
    String inList = List.of(value).toString();

    return List.of(
        value.toString(),
        String.valueOf(value),
        Objects.toString(value),
        String.format("%s", value),
        "" + value,
        inList.substring(1, inList.length() - 1));
  } // end method renderings

  @Test
  void signerOptionsAndTheirBuilderPrintRedacted() {
    SignerOptions.Builder builder =
        SignerOptions.builder().clientId(Fixtures.CLIENT_ID).signingSecret(SECRET);

    for (String rendering : renderings(builder)) {
      assertEquals("SignerOptions.Builder[redacted]", rendering);
    }

    for (String rendering : renderings(builder.build())) {
      assertEquals("SignerOptions[redacted]", rendering);
    }
  } // end method signerOptionsAndTheirBuilderPrintRedacted

  @Test
  void aSignerNeverPrintsTheSecret() {
    Signer signer =
        Signer.create(
            SignerOptions.builder().clientId(Fixtures.CLIENT_ID).signingSecret(SECRET).build());

    for (String rendering : renderings(signer)) {
      assertFalse(rendering.contains(SECRET), rendering);
    }
  } // end method aSignerNeverPrintsTheSecret

  @Test
  void noFieldOfASignerHoldsTheSecret() throws IllegalAccessException {
    Signer signer =
        Signer.create(
            SignerOptions.builder().clientId(Fixtures.CLIENT_ID).signingSecret(SECRET).build());

    for (Field field : Signer.class.getDeclaredFields()) {
      field.setAccessible(true);
      Object value = field.get(signer);

      assertNotEquals(SECRET, value, field.getName());
      assertFalse(String.valueOf(value).contains(SECRET), field.getName());
    }
  } // end method noFieldOfASignerHoldsTheSecret

  @Test
  void credentialsPrintRedacted() {
    Credentials credentials = Fixtures.signer(Fixtures.clockAt(1)).sign(Fixtures.scopedClaims());

    for (String rendering : renderings(credentials)) {
      assertFalse(rendering.contains(credentials.payload()), rendering);
      assertFalse(rendering.contains(credentials.signature()), rendering);
    }
  } // end method credentialsPrintRedacted
} // end class RedactionTest
