# Publishing to Maven Central

Coordinates: `io.github.kenny516:zkteco-biometric-lib:1.0.0-beta.3`.
The existing Java packages and APIs are unchanged. GitHub keeps the standard,
sources, Javadoc and `all` JARs; Central receives the standard JAR, sources,
Javadoc and POM, with signatures and checksums.

## Configure the publisher once

1. Sign in to [Sonatype Central](https://central.sonatype.com/) with the GitHub
   account `kenny516`. Confirm the namespace `io.github.kenny516` is verified in
   the portal. See the [namespace instructions](https://central.sonatype.org/register/namespace/).
2. Generate a [Central user token](https://central.sonatype.org/publish/generate-portal-token/).
   Its username/password are publishing credentials, not your GitHub password.
3. Install GnuPG and generate a signing key using `gpg --full-generate-key`.
   Choose a signing-capable RSA key and a strong passphrase. Keep a backup of
   the private key outside the repository. List the fingerprint with
   `gpg --list-secret-keys --keyid-format LONG`.
4. Publish the public key and export the private key locally (replace `FINGERPRINT`):

   ```shell
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys FINGERPRINT
   gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys FINGERPRINT
   gpg --armor --export-secret-keys FINGERPRINT
   ```

   Copy the entire armored private key into the GitHub secret below. Never put
   it, the token or the passphrase into a tracked file. See Sonatype's
   [GPG requirements](https://central.sonatype.org/publish/requirements/gpg/).
5. In the repository's **Settings → Secrets and variables → Actions**, add:

   | Secret | Value |
   | --- | --- |
   | `CENTRAL_USERNAME` | Central token username |
   | `CENTRAL_PASSWORD` | Central token password |
   | `GPG_PRIVATE_KEY` | Full armored private signing key |
   | `GPG_PASSPHRASE` | Signing key passphrase |

6. Set the repository **variable** `MAVEN_CENTRAL_ENABLED` to `true` once all
   credentials and the public key are ready. When absent or not `true`, the
   Central job is skipped and GitHub builds/releases continue normally.

## Release beta.3

After the publishing changes are committed and pushed, run:

```shell
git tag -a v1.0.0-beta.3 -m "Release 1.0.0-beta.3"
git push origin v1.0.0-beta.3
```

The workflow checks the tag against `project.version`, runs the tests and creates
GitHub downloads. Its Central job imports the GPG key, signs the four artifacts
and runs `mvn -P release,central clean deploy`. The Sonatype plugin validates and
automatically publishes the deployment, waiting until it is published.
Finally, a separate Maven project resolves the dependency and its transitive
dependencies using a fresh local repository and no extra repository declaration.

Inspect both GitHub Actions and the Central portal before announcing availability.
If Central succeeded but the final resolution check failed, allow propagation and
rerun only that check locally; do not republish the version. Central versions are
[immutable](https://central.sonatype.org/publish/requirements/immutability/).
For a subsequent release, change the POM version and use the matching `v` tag.
Older GitHub releases are retained.

## Local verification

```shell
mvn -P release clean verify
```

This runs tests and produces all four GitHub JARs without requiring credentials.
Use Maven 3.9.x for Central publishing. The workflow pins Maven 3.9.16 because
Maven 3.10 adds staging metadata that this publisher does not remove.
To verify signatures locally with your GPG key, set the `GPG_PASSPHRASE`
environment variable and run:

```shell
mvn -P release,central clean verify
```

The `central` profile disables the `all` JAR. Signing happens during `verify`;
upload happens only during `deploy`. Verify each generated `.asc` with
`gpg --verify SIGNATURE ARTIFACT` (use `pom.xml` as the artifact for the POM
signature). Actual Central validation also requires the public signing key
to be accessible. The publisher builds its deployment bundle during `deploy`.

After publication, verify from a separate project with the dependency shown in
the README and run `mvn -U dependency:resolve` with a fresh local repository.
Before publication, a local `mvn -P release install` only proves local consumption.

The integration follows Sonatype's [Maven publishing plugin documentation](https://central.sonatype.org/publish/publish-portal-maven/).
