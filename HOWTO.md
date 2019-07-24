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
# smlj HOWTO

## How to make a release (for committers)

Make sure `mvn clean install`, `mvn site`, and
`mvn javadoc:javadoc javadoc:test-javadoc` pass under JDK 8 - 12.

Write release notes. Run the
[relNotes](https://github.com/julianhyde/share/blob/master/tools/relNotes)
script and append the output to [HISTORY.md](HISTORY.md).

Update version numbers in README and README.md,
and the copyright date in NOTICE.

Switch to JDK 11.

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

Stage the release:
* Go to https://oss.sonatype.org and log in.
* Under "Build Promotion", click on "Staging Repositories".
* Select the line "smlj-nnnn", and click "Close". You might need to
  click "Refresh" a couple of times before it closes.

After testing, publish the release:
* Go to https://oss.sonatype.org and log in.
* Under "Build Promotion", click on "Staging Repositories".
* Select the line "smlj-nnnn", and click "Release".

Wait a couple of hours for the artifacts to appear on Maven central,
and announce the release.

Update the [github release list](https://github.com/julianhyde/smlj/releases).

## Cleaning up after a failed release attempt (for committers)

```bash
# Make sure that the tag you are about to generate does not already
# exist (due to a failed release attempt)
git tag

# If the tag exists, delete it locally and remotely
git tag -d smlj-X.Y.Z
git push origin :refs/tags/smlj-X.Y.Z

# Remove modified files
mvn release:clean

# Check whether there are modified files and if so, go back to the
# original git commit
git status
git reset --hard HEAD
```
