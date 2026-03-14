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

# Time structure

[Up to index](index.md)

[//]: # (start:lib/time)
The `Time` structure provides an abstract type for representing both absolute
times and time intervals, with functions for conversion, arithmetic, comparison,
formatting, and parsing. Time values are measured in nanoseconds internally,
and conversions to/from seconds, milliseconds, microseconds, and nanoseconds
are provided.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/time.html).*

## Synopsis

<pre>
eqtype <a id='time' href="#time-impl">time</a>

exception <a id='Time' href="#Time-impl">Time</a>

val <a id='zeroTime' href="#zeroTime-impl">zeroTime</a> : time
val <a id='fromReal' href="#fromReal-impl">fromReal</a> : real -> time
val <a id='toReal' href="#toReal-impl">toReal</a> : time -> real
val <a id='toSeconds' href="#toSeconds-impl">toSeconds</a> : time -> int
val <a id='toMilliseconds' href="#toMilliseconds-impl">toMilliseconds</a> : time -> int
val <a id='toMicroseconds' href="#toMicroseconds-impl">toMicroseconds</a> : time -> int
val <a id='toNanoseconds' href="#toNanoseconds-impl">toNanoseconds</a> : time -> int
val <a id='fromSeconds' href="#fromSeconds-impl">fromSeconds</a> : int -> time
val <a id='fromMilliseconds' href="#fromMilliseconds-impl">fromMilliseconds</a> : int -> time
val <a id='fromMicroseconds' href="#fromMicroseconds-impl">fromMicroseconds</a> : int -> time
val <a id='fromNanoseconds' href="#fromNanoseconds-impl">fromNanoseconds</a> : int -> time
val <a id='+' href="#+-impl">+</a> : time * time -> time
val <a id='-' href="#--impl">-</a> : time * time -> time
val <a id='compare' href="#compare-impl">compare</a> : time * time -> order
val <a id='<' href="#<-impl"><</a> : time * time -> bool
val <a id='<=' href="#<=-impl"><=</a> : time * time -> bool
val <a id='>' href="#>-impl">></a> : time * time -> bool
val <a id='>=' href="#>=-impl">>=</a> : time * time -> bool
val <a id='now' href="#now-impl">now</a> : unit -> time
val <a id='fmt' href="#fmt-impl">fmt</a> : int -> time -> string
val <a id='toString' href="#toString-impl">toString</a> : time -> string
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> time option
</pre>

<a id="time-impl"></a>
<h3><code><strong>eqtype</strong> time</code></h3>

is an equality type representing both absolute times (relative to the Unix
epoch, 1970-01-01T00:00:00Z) and time durations. Both absolute times and
intervals are represented identically; the interpretation is contextual.
Negative values represent times before the epoch or negative intervals.

<a id="Time-impl"></a>
<h3><code><strong>exception</strong> Time</code></h3>

is raised when a conversion produces a value that cannot be represented as a
`time` value (for example, when `fromReal` is called with `NaN` or infinity).

<a id="zeroTime-impl"></a>
<h3><code>zeroTime</code></h3>

`zeroTime` denotes an empty interval and serves as the reference point for absolute
times. It is equivalent to `fromReal(0.0)`.

<a id="fromReal-impl"></a>
<h3><code>fromReal</code></h3>

`fromReal r` converts `r` (measured in seconds) to a `time` value.
Raises `Time` if `r` is `NaN`, infinite, or otherwise not representable.

<a id="toReal-impl"></a>
<h3><code>toReal</code></h3>

`toReal t` (or `t.toReal ()`) converts the time value `t` to a real number representing seconds.

<a id="toSeconds-impl"></a>
<h3><code>toSeconds</code></h3>

`toSeconds t` (or `t.toSeconds ()`) returns the number of whole seconds in `t`, truncated toward zero.

<a id="toMilliseconds-impl"></a>
<h3><code>toMilliseconds</code></h3>

`toMilliseconds t` (or `t.toMilliseconds ()`) returns the number of whole milliseconds in `t`, truncated toward zero.

<a id="toMicroseconds-impl"></a>
<h3><code>toMicroseconds</code></h3>

`toMicroseconds t` (or `t.toMicroseconds ()`) returns the number of whole microseconds in `t`, truncated toward zero.

<a id="toNanoseconds-impl"></a>
<h3><code>toNanoseconds</code></h3>

`toNanoseconds t` (or `t.toNanoseconds ()`) returns the number of whole nanoseconds in `t`.

<a id="fromSeconds-impl"></a>
<h3><code>fromSeconds</code></h3>

`fromSeconds n` returns the `time` value corresponding to `n` seconds.

<a id="fromMilliseconds-impl"></a>
<h3><code>fromMilliseconds</code></h3>

`fromMilliseconds n` returns the `time` value corresponding to `n` milliseconds.

<a id="fromMicroseconds-impl"></a>
<h3><code>fromMicroseconds</code></h3>

`fromMicroseconds n` returns the `time` value corresponding to `n` microseconds.

<a id="fromNanoseconds-impl"></a>
<h3><code>fromNanoseconds</code></h3>

`fromNanoseconds n` returns the `time` value corresponding to `n` nanoseconds.

<a id="+-impl"></a>
<h3><code>+</code></h3>

`t1 + t2` (or `+.t1 t2`) returns the sum of the two time values `t1` and `t2`.

<a id="--impl"></a>
<h3><code>-</code></h3>

`t1 - t2` (or `-.t1 t2`) returns the difference of the two time values `t1` and `t2`.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (t1, t2)` (or `t1.compare t2`) returns `LESS`, `EQUAL`, or `GREATER` depending on whether `t1` is less than,
equal to, or greater than `t2`.

<a id="<-impl"></a>
<h3><code><</code></h3>

`t1 < t2` (or `<.t1 t2`) returns `true` if `t1` is less than `t2`.

<a id="<=-impl"></a>
<h3><code><=</code></h3>

`t1 <= t2` (or `<=.t1 t2`) returns `true` if `t1` is less than or equal to `t2`.

<a id=">-impl"></a>
<h3><code>></code></h3>

`t1 > t2` (or `>.t1 t2`) returns `true` if `t1` is greater than `t2`.

<a id=">=-impl"></a>
<h3><code>>=</code></h3>

`t1 >= t2` (or `>=.t1 t2`) returns `true` if `t1` is greater than or equal to `t2`.

<a id="now-impl"></a>
<h3><code>now</code></h3>

`now ()` returns the current time.

<a id="fmt-impl"></a>
<h3><code>fmt</code></h3>

`fmt n t` formats `t` as a decimal number of seconds with `n` fractional digits.
For example, `fmt 3 (fromReal 1.5)` returns `"1.500"`. Negative time
values are formatted with a leading `~`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString t` (or `t.toString ()`) formats `t` as a decimal number of seconds with 3 fractional digits.
Equivalent to `fmt 3 t`.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` parses a time value from the string `s`, which should be a decimal number
of seconds. Returns `SOME t` if successful, `NONE` otherwise.

[//]: # (end:lib/time)
