package com.useceleris.server;

import com.useceleris.client.CredentialRequest;
import java.util.concurrent.CompletionStage;

/**
 * Decides the claims for one connection attempt, for {@link
 * Signer#credentialProvider(ClaimsFunction)}. It is authoritative: nothing in the request, which
 * comes from the connecting client, widens scope beyond what it returns.
 */
@FunctionalInterface
public interface ClaimsFunction {
  /**
   * Starts deciding the claims for one attempt.
   *
   * @param request the attempt credentials are wanted for
   * @return a stage completing with the claims
   */
  CompletionStage<Claims> claimsFor(CredentialRequest request);
} // end interface ClaimsFunction
