<!--
{% comment %}
Licensed to Julian Hyde under one or more contributor license
agreements.  See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
Julian Hyde licenses this file to you under the Apache
License, Version 2.0 (the "License"); you may not use this
file except in compliance with the License.  You may obtain a
copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
{% endcomment %}
-->
# Morel HOWTO

## How to make a release (for committers)

Make sure `mvn clean install`, `mvn site`, and
`mvn javadoc:javadoc javadoc:test-javadoc` pass under JDK 8 - 25.

Upgrade dependencies to their latest release: run
```bash
./mvnw versions:display-dependency-updates
./mvnw versions:update-properties
```
and commit the modified `pom.xml`.

Write release notes. Run the
[relNotes](https://github.com/julianhyde/share/blob/master/tools/relNotes)
script and append the output to [HISTORY.md](HISTORY.md), laid out as
the commented-out template there. Then edit the entries:

* One entry per issue; merge those marked "continued" or "part N".
* Drop entries too vague to tell from the change that subsumes them.
* List breaking changes as bullets, just before the contributors.
* Date the release in UTC.

Update version numbers in
`src/main/java/net/hydromatic/morel/util/JavaVersion.java`, `README`,
`README.md` and `docs/reference.md`, and the copyright year in
`NOTICE`. To check that you have missed none of them:

```bash
./mvnw test -Dtest=LintTest -Dmorel.releaseVersion=x.y.0
```

Switch to JDK 21.

Check that the sandbox is clean:

```bash
git clean -nx
mvn clean
```

Prepare:

```bash
export GPG_TTY=$(tty)
mvn -Prelease -DreleaseVersion=x.y.0 -DdevelopmentVersion=x.(y+1).0-SNAPSHOT release:prepare
```

Perform:

```bash
mvn -Prelease -DskipTests release:perform
```

`release:perform` uploads the artifacts to the
[Central Portal](https://central.sonatype.com), authenticating as the
`<server>` whose `id` is `central` in your `settings.xml`, and
publishes them as soon as they pass validation. Watch progress at
https://central.sonatype.com/publishing/deployments.

To verify the artifacts by hand before they become public (see
"Manually verify a release" below), first set `autoPublish` to `false`
in `pom.xml`; then publish from the portal when you are satisfied.

Wait a couple of hours for the artifacts to appear on Maven Central,
and announce the release.

Update the [github release list](https://github.com/hydromatic/morel/releases).

## Manually verify a release (for committers)

A few shell behaviors involve an interactive terminal and are not
covered by the automated tests, so check them by hand before publishing
a staged release. Run the following against the release artifact, or
against a clean build (`./mvnw clean install`).

Start the shell, and confirm that it reports the release version:

```bash
$ ./morel
morel-java version x.y.0 (java version "21", JLine terminal, xterm-256color)
```

Execute a command, and confirm that the result is printed:

```
- "Hello, world!";
val it = "Hello, world!" : string
```

Quit the shell (type `Ctrl-D`), and confirm that the command was saved
to the history file:

```bash
$ cat ~/.morel/history
```

The file should contain the command you typed; each line is a Unix
timestamp, a colon, and the command. If you have not run the shell
before, confirm that the `~/.morel` directory and the `history` file
were created.

Start the shell again, press the up-arrow key, and confirm that the
previous command is recalled. Execute another command, quit, and confirm
that `~/.morel/history` has grown: the new command is appended, and the
earlier history is preserved.

## Cleaning up after a failed release attempt (for committers)

```bash
# Make sure that the tag you are about to generate does not already
# exist (due to a failed release attempt)
git tag

# If the tag exists, delete it locally and remotely
git tag -d morel-X.Y.Z
git push origin :refs/tags/morel-X.Y.Z

# Remove modified files
mvn release:clean

# Check whether there are modified files and if so, go back to the
# original git commit
git status
git reset --hard HEAD
```
