# Conventions

Simplicity and maintainability are paramount. The surface contract lives in the specifications repository, and the JavaScript server package is the reference implementation this package mirrors.

## Names

Use full domain words: `addChannelReferenceFailures`, `credentialProvider`, `signingSecret`. No abbreviations, and no single-letter names outside tight loops. A comment exists only to state a constraint the code cannot show.

## Simplicity over abstraction

Solve the problem in front of you with the simplest readable structure. No registries, factories, event frameworks, dependency-injection containers or wrapper layers. Prefer a method on an existing type over a new type.

## Maintainability

- One exported package, `com.useceleris.server`; package-private classes are its internals. Small files with one responsibility, named for it.
- Every fixed value lives in `Constants` as a package-private `static final` in SCREAMING_SNAKE_CASE. Validation rules live in `Validation`.
- Delete code in the same change that obsoletes it.
- Every public identifier traces to a requirement or recorded decision. The surface is pinned in one list in `PackageTest`.
- Errors are `CelerisException` with `ErrorCode.CONFIGURATION`, naming every failed field and the rule it broke; never the value, the secret or a cause.
- Golden vectors come from independent implementations, never from the code under test.
- Tests are grouped by behaviour in topic files. The programs in `examples/` compile with the tests.
- Before completion, review the full diff for anything deletable without weakening behaviour or tests.

## Layout

Leave one blank line after every block (`if`, `for`, `switch`, `try`, a lambda spanning lines) before the next statement, except before `else`, `catch` or `finally`, or at the end of an enclosing block. Separate the members of a type with a blank line, except consecutive fields.

Every method, constructor and named type whose body spans lines ends with a marker that names it: `} // end method name`, `} // end constructor Name`, `} // end class Name`, and likewise `interface`, `enum`, `record` and `annotation`. Lambdas and anonymous classes carry none. A marker must fit in 100 columns, or google-java-format moves it to the next line; shorten the name instead.

`LayoutTest` checks these rules on every source, test and example file and names each violation by file and line. google-java-format owns everything else; `./mvnw spotless:apply` applies it.

## Checks

- `./mvnw verify` runs everything, and CI repeats it on Java 17, 21 and 25: compilation with every lint as an error; the linters Error Prone and NullAway and the formatter google-java-format on Java 21 and newer; the layout rules (`LayoutTest`); the unit suites, including the signing vectors and the package checks (pinned surface, nothing printed, every Java block in the README compiled by `ReadmeTest`); and the Javadoc.
- `./mvnw verify -Plive` runs the acceptance suites against a real Celeris stack, reading `CELERIS_WS_URL`, `CELERIS_CLIENT_ID` and `CELERIS_SIGNING_SECRET` from a gitignored `.env` or the environment.
- `./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage` runs mutation testing.
- The build resolves the client at the exact version `pom.xml` names; until that version is on Maven Central, install it from `sdk-java-client` with `./mvnw install`.
