# Contributing

Thank you so much for making the library better.
Please feel free to open bug reports to discuss new features; PRs are welcome as well :)

Some basic rules for a good PR:

1. A change that moves a seam starts in `design/architecture.md`, before the code; its why goes
   to `design/decisions.md`, along with any research in `design/research.md`. You can use Claude
   to help draft it.
2. The design must be grilled by Claude: see the grill-me skill.
3. New files must follow the project structure and must be placed sensibly.
4. `./gradlew` must pass; it runs `design/verify_design_tripwires.sh` too.

## Tests

Uses JUnit 5. Simply run `./gradlew` to run all tests, with a JDK between 11 and 24; the
official-SDK leg needs 17+ and the Java 11 leg needs `JDK11` set. `AGENTS.md`, "Commands", has
each leg on its own.

### Manual Tests

Connect Claude Code to the weather demo over both transports, as in `README.md`, "The weather
demo", and check that `get_weather` answers, `weather://cities` reads and `plan_outing` renders:

1. `./gradlew :weather-demo:run`, then `claude mcp add --transport http weather http://127.0.0.1:18088/mcp`.
2. `./gradlew :weather-demo:installDist`, then
   `claude mcp add weather -- "$PWD/weather-demo/build/install/weather-demo/bin/weather-demo" --stdio`.

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

To release the library to Maven Central:

1. Run the manual tests above.
2. Edit `build.gradle.kts` and remove `-SNAPSHOT` in the `version =` stanza, e.g. "0.1.0"
3. Edit `README.md`: set the version in the Gradle snippet under "Getting it" to match the release
4. Run `./gradlew clean build publish closeAndReleaseStagingRepositories` on a 17+ JDK with
   `JDK11` set, so every test leg runs before anything is published
5. (Optional) watch [Maven Central Publishing Deployments](https://central.sonatype.com/publishing/deployments) as the deployment is published.
6. Commit with the commit message of simply being the version being released, e.g. "0.1.0"
7. git tag the commit with the same tag name as the commit message above, e.g. `0.1.0`
8. `git push`, `git push --tags`
9. Add the `-SNAPSHOT` back to the `version =` while increasing the version to something which will be released in the future,
   e.g. 0.1.1-SNAPSHOT, then commit with the commit message "0.1.1-SNAPSHOT" and push.
