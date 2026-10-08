# Security policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Report it privately through GitHub's [Security Advisories](https://github.com/useceleris/sdk-java-server/security/advisories/new) on this repository, so a fix can be prepared before anything is disclosed.

Include the affected version, the Java version, and the smallest example that reproduces the problem; a suggested fix is welcome but not required. You should get an acknowledgement within a few days, then what we found, what we intend to do and when. We credit reporters when the fix is published unless they would rather we did not.

Fixes land on the latest release; there is no long-term support branch.

## Scope

In scope: anything in this library that signs credentials granting more than the claims say, any leak of the signing secret or of credentials, and any input that can crash or corrupt a consuming application.

Out of scope: the Celeris service itself, reported through the same channel on its own repository, and findings that require the signing secret. That secret is the trust boundary, and its compromise is total by design.

## What this library does

This library holds the signing secret, so it belongs on trusted servers only.

- **The secret is never shown.** A `Signer` keeps it only inside the key its digest uses, so printing a signer never shows it. `SignerOptions`, their builder and `Credentials` print as redacted. The library prints and logs nothing.
- **Errors carry no values.** Validation errors name the failed field and rule, never the value, and carry no cause.
- **Signed bytes are exactly the claims.** Every signed string is checked to be well-formed UTF-16, and the token payload is written without a JSON library, so the output equals the JavaScript reference signer's byte for byte, proven by shared signing vectors.
- **Signing uses only the JDK** (`javax.crypto.Mac` with HmacSHA512).
