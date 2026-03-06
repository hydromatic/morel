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

# Interact structure

[Up to index](index.md)

[//]: # (start:lib/interact)
The `Interact` structure provides functions for interacting with the
Morel REPL, such as loading source files.

## Synopsis

<pre>
val <a id='use' href="#use-impl">use</a> : string -> unit
val <a id='useSilently' href="#useSilently-impl">useSilently</a> : string -> unit
</pre>

<a id="use-impl"></a>
<h3><code>use</code></h3>

`use f` loads source text from the file named `f`.

<a id="useSilently-impl"></a>
<h3><code>useSilently</code></h3>

`useSilently f` loads source text from the file named `f`, without
printing to stdout.

[//]: # (end:lib/interact)
