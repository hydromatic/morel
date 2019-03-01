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
# smlj
Standard ML interpreter, implemented in Java

## Requirements

Java version 8 or higher.

## Get smlj

### From Maven

Get smlj from
<a href="https://search.maven.org/#search%7Cga%7C1%7Cg%3Anet.hydromatic%20a%3Asmlj">Maven Central</a>:

```xml
<dependency>
  <groupId>net.hydromatic</groupId>
  <artifactId>smlj</artifactId>
  <version>0.1</version>
</dependency>
```

### Download and build

```bash
$ git clone git://github.com/julianhyde/smlj.git
$ cd smlj
$ ./mvnw install
```

On Windows, the last line is

```bash
> mvnw install
```
## Status

Implemented:
* Literals
* Variables
* `let`
* `val` (including `val rec`)
* Operators: `=` `<>` `<` `>` `<=` `>=`
  `~` `+` `-` `*` `/` `div` `mod` `^`
  `andalso` `orelse`
  `::`
* Built-in constants and functions:
  `it` `true` `false` `not`
* Type derivation
* `fn`, function values, and function application
* `if`
* `case`
* Primitive, list, tuple and record types
* Tuples and unit
* Lists
* Patterns (destructuring) in `val` and `case`,
  matching constants, wildcards, lists, records and tuples

Not implemented:
* Type variables (polymorphism)
* `fun` declaration
* `type`
* `datatype`
* `local`
* `raise`, `handle`
* `exception`
* `while`
* References, and operators `!` and `:=`
* Operators: `@` `before`
* Constants: `nil`
* User-defined operators (`infix`, `infixr`)
* Type annotations in expressions and patterns

Bugs:
* Prevent user from overriding built-in constants and functions:
  `true`, `false`, `nil`, `ref`, `it`, `::`; they should not be reserved
* Access parameters and variables by offset into a fixed-size array;
  currently we address them by name, in a map that is copied far too often
* Runtime should throw when divide by zero
* Validator should give good user error when it cannot type an expression

## More information

* License: <a href="LICENSE">Apache License, Version 2.0</a>
* Author: Julian Hyde (<a href="https://twitter.com/julianhyde">@julianhyde</a>)
* Project page: http://www.hydromatic.net/smlj
* API: http://www.hydromatic.net/smlj/apidocs
* Source code: http://github.com/julianhyde/smlj
* Developers list:
  <a href="mailto:dev@calcite.apache.org">dev at calcite.apache.org</a>
  (<a href="http://mail-archives.apache.org/mod_mbox/calcite-dev/">archive</a>,
  <a href="mailto:dev-subscribe@calcite.apache.org">subscribe</a>)
* Issues: https://github.com/julianhyde/smlj/issues
* <a href="HISTORY.md">Release notes and history</a>
