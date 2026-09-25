# Contributing

Thank you for taking your time to contribute! Here are some basic rules for a good PR:

1. A change that moves a seam starts in `design/architecture.md`, before the code; its why goes
   to `design/decisions.md`, along with any research in `design/research.md`. You can use Claude
   to help draft it.
2. The design must be grilled by Claude: see the grill-me skill.
3. New files must follow the project structure and must be placed sensibly.
4. `./gradlew` and `design/verify_design_tripwires.sh` must pass.

# Releasing

Only `mcp-server` is published; `weather-demo` has no publication and is skipped.

One-time setup: a [Maven Central](https://central.sonatype.com/) user token and a GPG signing
key, in `~/.gradle/gradle.properties`:

```properties
sonatypeUsername=<token username>
sonatypePassword=<token password>
signing.keyId=<last 8 hex digits of the key>
signing.password=<key passphrase>
signing.secretKeyRingFile=/home/<you>/.gnupg/secring.gpg
```

To release:

1. Edit `build.gradle.kts` and remove `-SNAPSHOT` from the `version =` line.
2. Commit with the version as the whole commit message, e.g. `0.1.0`.
3. Tag that commit with the same name: `git tag 0.1.0`.
4. `git push && git push --tags`.
5. `./gradlew clean build publishToSonatype closeAndReleaseSonatypeStagingRepository`
6. Check the deployment at [central.sonatype.com → Deployments](https://central.sonatype.com/publishing/deployments);
   it takes a while to appear in Maven Central.
7. Put `-SNAPSHOT` back on the `version =` line, bumped to the next version, e.g. `0.1.1-SNAPSHOT`.
8. Commit with that version as the message, and push.
