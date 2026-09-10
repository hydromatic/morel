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
# Test scripts: the `.smli` format

Most of Morel's tests are scripts in `src/test/resources/script`.
A script is a sequence of Morel statements, each followed by the
output it is expected to produce. The suite runs every script and
fails if any statement's output differs from what the script says.

This document describes the format, the harness that runs it, and
the rules by which output is matched and generated. It is the
contract for other implementations of Morel (morel-rust, morel-go),
which run the same scripts.

## A script

```sml
(*) Composite declarations
val x = 5
 and y = 6;
> val x = 5 : int
> val y = 6 : int

from e in scott.emps where e.deptno = 10 yield e.ename;
> val it = ["CLARK","KING","MILLER"] : string bag
```

A script has three kinds of line.

* A **statement** is Morel source. It may span several lines, and
  ends with `;`.
* An **output line** begins with `>`. The lines following a
  statement, up to the next line that does not begin with `>`, are
  the statement's expected output, with the `> ` prefix removed. An
  empty output line is a bare `>`.
* A **comment** is either a block comment, `(* ... *)`, or a line
  comment, `(*) ...`. Line comments are a Morel extension.

The file is *idempotent*: running it through the harness produces a
file with the same statements and comments, and with each
statement's output replaced by what the statement actually produced.
If the script was correct, the output is identical to the input.

Scripts end with a line `(*) End name.smli`, which the linter checks.

A statement may be prefixed with `:t`, in which case only the type
of its bindings is printed, as `val x : type`, and the statement is
not evaluated.

A `.sml` script (rather than `.smli`) is not idempotent: its
statements are run, and the whole output is compared with a
separate file `name.sml.out`.

## Output

Each binding prints as `val name = value : type`. If the value does
not fit on that line it is wrapped onto the next line, indented by
two spaces, and a type that does not fit follows on a third line. In
tabular mode (see below) a collection of records prints as a table,
followed by `val name : type` with no value.

Warnings and errors print before the value, for example

```
> stdIn:1.5-1.12 Warning: match nonexhaustive
>   raised at: stdIn:1.5-1.12
> val f = fn : int -> int
f 2;
> uncaught exception Bind [nonexhaustive binding failure]
```

### Raw string literals

A string value that contains a newline, and has no space or tab
before any newline, is written as a *raw string literal*:

```
"a\nb";
> val it = {|a
> b|} : string
```

The content between the fences `{|` and `|}` is verbatim: there are
no escapes, a newline in the content is a newline in the script, and
lines after the first start at column 0 (immediately after the `> `
prefix). A trailing newline in the content leaves the closing fence
alone on the last line.

If the content's second line starts with a space, the harness starts
the content on the line after the opening fence, so that the content's
lines line up in the script. The tag then starts with an underscore,
`{_|`, which is what tells the reader that the newline right after the
opening fence is not content:

```
"root {\n  common {\n    plan_id: 0\n  }\n}";
> val it = {_|
> root {
>   common {
>     plan_id: 0
>   }
> }|_} : string
```

If the content contains `|}` (or `|_}`), the fences carry a longer
tag, `{a|...|a}` or `{_a|...|_a}`. A tag consists of lower-case
letters `a` to `z` and underscores, and starts with an underscore
exactly when the content starts on the next line; the harness chooses
the shortest (`a`, `b`, ..., `z`, `aa`, ...) such that `|tag}` does
not occur in the content.

Only a top-level string value is written this way. A string inside a
list, record or other value keeps the regular escaped form. So does a
string with a space before a newline (which would be invisible, and
easily lost), or with any character that is not printable ASCII: a
tab, a carriage return, a control character or a non-ASCII character
would be invisible or fragile in the script, and a tab would fail the
linter. If the printer would have wrapped the value onto
the line after `val name =`, the raw literal starts on that line
too, indented by two spaces.

Raw string literals are a feature of the script format only. They
are not part of the Morel language.

### Properties that affect output

Scripts often set properties at the top, via `Sys.set`, to make
output deterministic or compact. The most common:

| Property      | Effect                                                 |
| ------------- | ------------------------------------------------------ |
| `lineWidth`   | Width at which values are wrapped (default 79)         |
| `printLength` | Elements of a collection printed before `...`          |
| `printDepth`  | Nesting depth printed before `#`                       |
| `stringDepth` | Characters of a string printed before `#`; `~1` is all |
| `output`      | `"classic"` (default) or `"tabular"`                   |
| `matchStrict` | Compare output verbatim (see below)                    |

`Sys.unset` restores a property's default.

## The harness

`Main`, run in idempotent mode, is the harness. It reads the script,
strips the output lines (remembering each statement's expected
output by position), runs the statements one by one, and writes the
regenerated script. For each statement it buffers the output, then
compares it with the expected output:

* If the two are identical, or the mode is lenient and they are
  equivalent (see the next section), the harness writes the expected
  output verbatim, so the script keeps the form it was written in.
* Otherwise it writes the actual output.

A statement that has no expected output is always echoed, wherever
it occurs, so that a script cannot pass by omission.

`ScriptTest` is the JUnit test that runs every `.smli` and `.sml`
file under `src/test/resources/script`, in parallel, with a timeout
per script. Each script is run by `Script`, which writes the
regenerated script to a `surefire` directory under
`target/test-classes` and fails the test with a diff if the file
differs from the source. `ScriptTest.testScriptDual` runs `dual.smli`
a second time with the `hybrid` property on, so that every query is
pushed down to Calcite, and a tracer asserts that it was; the same
expected output must hold in both modes.

To run one script and see its output:

```bash
./morel --echo src/test/resources/script/simple.smli
```

This writes the regenerated script to `simple.smli.out` next to the
source, and prints a diff if they differ. To write a new test, put a
placeholder `> x` after each statement, run the script, check the
generated output, and copy `.out` back over the source.

### Running scripts in the other implementations

The Rust and Go implementations keep copies of the scripts and run
them the same way; a script that they run should keep the same text
in every implementation.

[Morel Rust](https://github.com/hydromatic/morel-rust) keeps its
scripts in `tests/script`. To run one, writing the regenerated script
to `.out` next to the source and printing a diff if they differ:

```bash
cargo run -- test tests/script/simple.smli
```

Without `test`, `cargo run -- tests/script/simple.smli` prints the
regenerated script to standard output. The suite is `cargo test`, and
the script tests alone are `cargo test --test smile`.

[Morel Go](https://github.com/hydromatic/morel-go) keeps its scripts
in `testdata/script`. Running a script prints the regenerated script to
standard output, so compare it with the source:

```bash
diff testdata/script/simple.smli <(./morel testdata/script/simple.smli)
```

The suite is `go test ./...`; one script is `go test -run
'TestScripts/simple.smli' .`

## Matching

`OutputMatcher` decides whether an actual output and an expected
output are equivalent. It is used only in lenient mode, which is the
default; setting `matchStrict` to `true` requires the two to be
identical, which is useful when a script is testing the printer
itself.

The matcher parses both outputs, guided by the statement's type, into
values, and compares the values:

* Whitespace, including line breaks, is ignored outside string
  literals. So a value wrapped differently is equivalent.
* A bag is compared as a multiset: the same elements in any order.
  So a query whose output order is not deterministic can still have
  expected output.
* A list, tuple, record or datatype value is compared element by
  element.
* A string is compared by content, so a raw string literal is
  equivalent to the escaped literal with the same content.
* A word literal is compared by value, so `0w255` equals `0wxFF`.

Everything before the value (the `val name =` prefix, and any
warnings) must be the same apart from whitespace, and the type must
be identical. If the matcher cannot parse an output it reports "not
equivalent": a false negative costs a regenerated line, but a false
positive would hide a bug.

The matcher does not run in strict mode, and it never alters the
Morel language, lexer, parser or pretty-printer: the harness converts
a multi-line string to a raw literal after the printer has produced
the escaped form.

## Testing the harness

A script cannot test what the harness generates, because a script
conforms to whatever the harness generated when it was written, and
in lenient mode an equivalent expectation is kept as written. So
those tests are in Java, in `MainTest`: strict and lenient matching,
a statement without expected output, and raw string generation and
matching. `TypeTest` tests `OutputMatcher` directly. The script
`idempotent.smli` tests the parts of the format that can be tested
from within a script, such as which bindings are printed and the raw
string literal cases.
