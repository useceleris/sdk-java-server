/**
 * Mints Celeris credentials on a trusted server. A {@link com.useceleris.server.Signer} holds your
 * application's signing secret and signs {@link com.useceleris.server.Claims} your server decides
 * for a user it has authenticated. Never ship the signing secret, or this module, to a client.
 */
module com.useceleris.server {
  requires transitive com.useceleris.client;

  exports com.useceleris.server;
}
