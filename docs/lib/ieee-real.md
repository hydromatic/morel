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

# IEEEReal structure

[Up to index](index.md)

[//]: # (start:lib/ieee-real)
The `IEEEReal` structure provides types and constants related to
IEEE 754 floating-point arithmetic, including rounding modes,
floating-point classes, and a decimal approximation record type.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/ieee-real.html).*

## Synopsis

<pre>
exception <a id='Unordered' href="#Unordered-impl">Unordered</a>
</pre>

<a id="Unordered-impl"></a>
<h3><code><strong>exception</strong> Unordered</code></h3>

is raised by `Real.compare` when either argument is NaN, so that no
order between them can be reported.

[//]: # (end:lib/ieee-real)
