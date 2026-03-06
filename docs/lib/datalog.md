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

# Datalog structure

[Up to index](index.md)

[//]: # (start:lib/datalog)
The `Datalog` structure provides functions to parse, validate, translate,
and execute Datalog programs within Morel.

## Synopsis

<pre>
val <a id='execute' href="#execute-impl">execute</a> : string -> variant
val <a id='translate' href="#translate-impl">translate</a> : string -> string option
val <a id='validate' href="#validate-impl">validate</a> : string -> string
</pre>

<a id="execute-impl"></a>
<h3><code>execute</code></h3>

`execute program` executes a Datalog program and returns formatted output as a variant.

<a id="translate-impl"></a>
<h3><code>translate</code></h3>

`translate program` translates a Datalog program to Morel source code, returning `SOME
code` if valid or `NONE` if invalid.

<a id="validate-impl"></a>
<h3><code>validate</code></h3>

`validate program` validates a Datalog program and returns type information or error
message.

[//]: # (end:lib/datalog)
