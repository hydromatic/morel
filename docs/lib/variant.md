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

# Variant structure

[Up to index](index.md)

[//]: # (start:lib/variant)
The `Variant` structure provides operations for working with the
`variant` type, which can hold values of any type in a dynamically-typed
fashion.

## Synopsis

<pre>
datatype <a id='variant' href="#variant-impl">variant</a>
  = UNIT
  | BOOL of bool
  | INT of int
  | REAL of real
  | CHAR of char
  | STRING of string
  | LIST of variant list
  | BAG of variant list
  | VECTOR of variant list
  | VARIANT_NONE
  | VARIANT_SOME of variant
  | RECORD of (string * variant) list
  | CONSTANT of string
  | CONSTRUCT of string * variant

val <a id='parse' href="#parse-impl">parse</a> : string -> variant
val <a id='print' href="#print-impl">print</a> : variant -> string
</pre>

<a id="variant-impl"></a>
<h3><code><strong>datatype</strong> variant</code></h3>

is a dynamically-typed value that can hold any Morel value.

<a id="parse-impl"></a>
<h3><code>parse</code></h3>

`parse s` parses a variant from its string representation.

The string is in the format produced by the `print` function,
and therefore `parse (print v) = v` for all variant values `v`.

<a id="print-impl"></a>
<h3><code>print</code></h3>

`print v` converts a variant to a string.

For example,
`print (BOOL true)` returns `"BOOL true"`;
`print (LIST [INT 1, INT 2])` returns `"LIST [INT 1, INT 2]"`.

[//]: # (end:lib/variant)
