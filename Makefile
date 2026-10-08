# Builds, checks and publishes com.useceleris:celeris-server. Run from the
# repository root. JAVA_HOME selects the JDK; 21 or newer also runs Error
# Prone, NullAway and the format check.
#
#   make check                        the unit and integration suites, with analysis
#   make format                       apply google-java-format
#   make live                         the acceptance suites against a real Celeris stack, from .env
#   make version VERSION=x.y.z        set the project version
#   make client-version VERSION=x.y.z set the celeris-client version this artifact uses
#   make release-check                build and sign the release artifacts, without upload
#   make publish                      sign and upload a release version to Maven Central
#
# Publish the client first: a release of this artifact needs a released
# celeris-client version. Publishing needs a GPG signing key and a Central
# Portal token in ~/.m2/settings.xml under the server id "central". The upload
# waits in the Central Portal until you publish it there.

MVN := ./mvnw
VERSIONS_PLUGIN := org.codehaus.mojo:versions-maven-plugin:2.22.0

.PHONY: check format live version client-version release-check publish require-release-versions

check:
	$(MVN) verify

format:
	$(MVN) spotless:apply

live:
	$(MVN) verify -Plive

version:
	@test -n "$(VERSION)" || { echo "Usage: make version VERSION=x.y.z"; exit 1; }
	$(MVN) $(VERSIONS_PLUGIN):set -DnewVersion=$(VERSION) -DgenerateBackupPoms=false

client-version:
	@test -n "$(VERSION)" || { echo "Usage: make client-version VERSION=x.y.z"; exit 1; }
	$(MVN) $(VERSIONS_PLUGIN):set-property -Dproperty=celeris-client.version -DnewVersion=$(VERSION) -DgenerateBackupPoms=false

release-check: require-release-versions
	$(MVN) -Prelease clean verify

publish: require-release-versions
	$(MVN) -Prelease clean deploy

require-release-versions:
	@for expression in project.version celeris-client.version; do \
	  version="$$($(MVN) -q help:evaluate -Dexpression=$$expression -DforceStdout)"; \
	  case "$$version" in \
	    *-SNAPSHOT) echo "$$expression is $$version, a snapshot. Set release versions first."; exit 1;; \
	  esac; \
	done
