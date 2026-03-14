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

# Date structure

[Up to index](index.md)

[//]: # (start:lib/date)
The `Date` structure provides an abstract type for calendar dates and
times, with fields for year, month, day, hour, minute, and second. Dates
can be constructed from `Time.time` values, decomposed into fields,
formatted as strings, and compared.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/date.html).*

## Synopsis

<pre>
eqtype <a id='date' href="#date-impl">date</a>
datatype <a id='month' href="#month-impl">month</a>
  = Jan
  | Feb
  | Mar
  | Apr
  | May
  | Jun
  | Jul
  | Aug
  | Sep
  | Oct
  | Nov
  | Dec
datatype <a id='weekday' href="#weekday-impl">weekday</a> = Mon | Tue | Wed | Thu | Fri | Sat | Sun

exception <a id='Date' href="#Date-impl">Date</a>

val <a id='compare' href="#compare-impl">compare</a> : date * date -> order
val <a id='date' href="#date-impl">date</a> : {day:int, hour:int, minute:int, month:month, offset:time option, second:int, year:int} -> date
val <a id='day' href="#day-impl">day</a> : date -> int
val <a id='fmt' href="#fmt-impl">fmt</a> : string -> date -> string
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> date option
val <a id='fromTimeLocal' href="#fromTimeLocal-impl">fromTimeLocal</a> : time -> date
val <a id='fromTimeUniv' href="#fromTimeUniv-impl">fromTimeUniv</a> : time -> date
val <a id='hour' href="#hour-impl">hour</a> : date -> int
val <a id='isDst' href="#isDst-impl">isDst</a> : date -> bool option
val <a id='localOffset' href="#localOffset-impl">localOffset</a> : unit -> time
val <a id='minute' href="#minute-impl">minute</a> : date -> int
val <a id='month' href="#month-impl">month</a> : date -> month
val <a id='second' href="#second-impl">second</a> : date -> int
val <a id='toString' href="#toString-impl">toString</a> : date -> string
val <a id='toTime' href="#toTime-impl">toTime</a> : date -> time
val <a id='weekDay' href="#weekDay-impl">weekDay</a> : date -> weekday
val <a id='year' href="#year-impl">year</a> : date -> int
val <a id='yearDay' href="#yearDay-impl">yearDay</a> : date -> int
</pre>

<a id="date-impl"></a>
<h3><code><strong>eqtype</strong> date</code></h3>

is an equality type representing a calendar date and time of day,
with an associated timezone offset.

<a id="month-impl"></a>
<h3><code><strong>datatype</strong> month</code></h3>

is the type of month values.

<a id="weekday-impl"></a>
<h3><code><strong>datatype</strong> weekday</code></h3>

is the type of weekday values.

<a id="Date-impl"></a>
<h3><code><strong>exception</strong> Date</code></h3>

is raised when a date cannot be constructed from the given fields
(for example, if the day or month is out of range).

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (d1, d2)` (or `d1.compare d2`) returns `LESS`, `EQUAL`, or `GREATER` depending on whether `d1` is less
than, equal to, or greater than `d2` (comparing instants in time).

<a id="date-impl"></a>
<h3><code>date</code></h3>

`date {year, month, day, hour, minute, second, offset}` constructs a date from the given fields. If `offset` is `NONE`, the date
is in local time; if `SOME t`, the date is in the timezone with offset `t`
from UTC.

<a id="day-impl"></a>
<h3><code>day</code></h3>

`day d` (or `d.day ()`) returns the day of the month of `d`, in the range `[1, 31]`.

<a id="fmt-impl"></a>
<h3><code>fmt</code></h3>

`fmt s d` formats `d` using the strftime-style format string `s`. Recognized
format codes include `%Y` (4-digit year), `%m` (2-digit month),
`%d` (2-digit day), `%H` (hour), `%M` (minute), `%S` (second),
`%a` (abbreviated weekday), `%b` (abbreviated month), and `%%` (literal `%`).

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` parses a date from the string `s`, which should be in the format
produced by `toString` (e.g., `"Thu Jan  1 00:00:00 1970"`).
Returns `SOME d` if successful, `NONE` otherwise.

<a id="fromTimeLocal-impl"></a>
<h3><code>fromTimeLocal</code></h3>

`fromTimeLocal t` converts the time value `t` to a date in the local timezone.

<a id="fromTimeUniv-impl"></a>
<h3><code>fromTimeUniv</code></h3>

`fromTimeUniv t` converts the time value `t` to a date in UTC.

<a id="hour-impl"></a>
<h3><code>hour</code></h3>

`hour d` (or `d.hour ()`) returns the hour of `d`, in the range `[0, 23]`.

<a id="isDst-impl"></a>
<h3><code>isDst</code></h3>

`isDst d` (or `d.isDst ()`) returns `SOME true` if `d` is in daylight saving time, `SOME false` if
not, or `NONE` if the information is not available.

<a id="localOffset-impl"></a>
<h3><code>localOffset</code></h3>

`localOffset ()` returns the offset of the local timezone from UTC as a `time` value
(nanoseconds).

<a id="minute-impl"></a>
<h3><code>minute</code></h3>

`minute d` (or `d.minute ()`) returns the minute of `d`, in the range `[0, 59]`.

<a id="month-impl"></a>
<h3><code>month</code></h3>

`month d` (or `d.month ()`) returns the month of `d`.

<a id="second-impl"></a>
<h3><code>second</code></h3>

`second d` (or `d.second ()`) returns the second of `d`, in the range `[0, 59]`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString d` (or `d.toString ()`) formats `d` as a string in the format `"Www Mmm DD HH:MM:SS YYYY"`,
for example `"Thu Jan  1 00:00:00 1970"`.

<a id="toTime-impl"></a>
<h3><code>toTime</code></h3>

`toTime d` (or `d.toTime ()`) converts `d` to a `time` value (nanoseconds since the Unix epoch).

<a id="weekDay-impl"></a>
<h3><code>weekDay</code></h3>

`weekDay d` (or `d.weekDay ()`) returns the day of the week of `d`.

<a id="year-impl"></a>
<h3><code>year</code></h3>

`year d` (or `d.year ()`) returns the year of `d`.

<a id="yearDay-impl"></a>
<h3><code>yearDay</code></h3>

`yearDay d` (or `d.yearDay ()`) returns the day of the year of `d`, in the range `[0, 365]`.

[//]: # (end:lib/date)
