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

# Sys structure

[Up to index](index.md)

[//]: # (start:lib/sys)
The `Sys` structure provides functions for interacting with the Morel
execution environment, such as reading properties and managing the
environment.

## Synopsis

<pre>
val <a id='clearEnv' href="#clearEnv-impl">clearEnv</a> : unit -> unit
val <a id='env' href="#env-impl">env</a> : unit -> string list
val <a id='plan' href="#plan-impl">plan</a> : unit -> string
val <a id='planEx' href="#planEx-impl">planEx</a> : string -> string
val <a id='set' href="#set-impl">set</a> : string * 'a -> unit
val <a id='show' href="#show-impl">show</a> : string -> string option
val <a id='showAll' href="#showAll-impl">showAll</a> : unit -> string * string option list
val <a id='unset' href="#unset-impl">unset</a> : string -> unit
val <a id='file' href="#file-impl">file</a> : {...}
</pre>

<a id="clearEnv-impl"></a>
<h3><code>clearEnv</code></h3>

`clearEnv ()` restores the environment to the initial environment.

<a id="env-impl"></a>
<h3><code>env</code></h3>

`env ()` prints the environment.

<a id="plan-impl"></a>
<h3><code>plan</code></h3>

`plan ()` prints the plan of the most recently executed expression.

<a id="planEx-impl"></a>
<h3><code>planEx</code></h3>

`planEx phase` re-plans the most recently executed expression and returns the Core
representation at the specified phase. The phase argument can be "0" (initial),
"-1" (final), or a specific pass number.

<a id="set-impl"></a>
<h3><code>set</code></h3>

`set (property, value)` sets the value of `property` to `value`. (See [Properties](#properties) below.)

<a id="show-impl"></a>
<h3><code>show</code></h3>

`show property` returns the current the value of `property`, as a
string, or `NONE` if unset.

<a id="showAll-impl"></a>
<h3><code>showAll</code></h3>

`showAll ()` returns a list of all properties and their current value
as a string, or `NONE` if unset.

<a id="unset-impl"></a>
<h3><code>unset</code></h3>

`unset property` clears the current the value of `property`.

<a id="file-impl"></a>
<h3><code>file</code></h3>

`file` is a view of the file system as a record. The fields of the record
depend on the files and directories under the configured directory.

[//]: # (end:lib/sys)
