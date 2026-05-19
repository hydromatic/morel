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

# String structure

[Up to index](index.md)

[//]: # (start:lib/string)
The `String` structure provides the `string` type and a comprehensive
set of operations for constructing, examining, searching, and converting
strings.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/string.html).*

## Synopsis

<pre>
type <a id='string' href="#string-impl">string</a>
type <a id='char' href="#char-impl">char</a>

val <a id='maxSize' href="#maxSize-impl">maxSize</a> : int
val <a id='size' href="#size-impl">size</a> : string -> int
val <a id='sub' href="#sub-impl">sub</a> : string * int -> char
val <a id='extract' href="#extract-impl">extract</a> : string * int * int option -> string
val <a id='substring' href="#substring-impl">substring</a> : string * int * int -> string
val <a id='^' href="#^-impl">^</a> : string * string -> string
val <a id='concat' href="#concat-impl">concat</a> : string list -> string
val <a id='concatWith' href="#concatWith-impl">concatWith</a> : string -> string list -> string
val <a id='str' href="#str-impl">str</a> : char -> string
val <a id='implode' href="#implode-impl">implode</a> : char list -> string
val <a id='explode' href="#explode-impl">explode</a> : string -> char list
val <a id='map' href="#map-impl">map</a> : (char -> char) -> string -> string
val <a id='translate' href="#translate-impl">translate</a> : (char -> string) -> string -> string
val <a id='tokens' href="#tokens-impl">tokens</a> : (char -> bool) -> string -> string list
val <a id='fields' href="#fields-impl">fields</a> : (char -> bool) -> string -> string list
val <a id='isPrefix' href="#isPrefix-impl">isPrefix</a> : string -> string -> bool
val <a id='isSubstring' href="#isSubstring-impl">isSubstring</a> : string -> string -> bool
val <a id='isSuffix' href="#isSuffix-impl">isSuffix</a> : string -> string -> bool
val <a id='compare' href="#compare-impl">compare</a> : string * string -> order
val <a id='collate' href="#collate-impl">collate</a> : (char * char -> order) -> string * string -> order
val <a id='<' href="#<-impl"><</a> : string * string -> bool
val <a id='<=' href="#<=-impl"><=</a> : string * string -> bool
val <a id='>' href="#>-impl">></a> : string * string -> bool
val <a id='>=' href="#>=-impl">>=</a> : string * string -> bool
</pre>

<a id="string-impl"></a>
<h3><code><strong>type</strong> string</code></h3>

is the type of character strings.

<a id="char-impl"></a>
<h3><code><strong>type</strong> char</code></h3>



<a id="maxSize-impl"></a>
<h3><code>maxSize</code></h3>

`maxSize` is the longest allowed size of a string.

<a id="size-impl"></a>
<h3><code>size</code></h3>

`size s` (or `s.size ()`) returns |`s`|, the number of characters in string `s`.

<a id="sub-impl"></a>
<h3><code>sub</code></h3>

`sub (s, i)` (or `s.sub i`) returns the `i`(th) character of `s`, counting from
zero. This raises `Subscript` if `i` < 0 or |`s`| &le; `i`.

<a id="extract-impl"></a>
<h3><code>extract</code></h3>

`extract (s, i, NONE)` (or `s.extract (i, NONE)`) and "extract (s, i, SOME j)"
return substrings
of `s`. The first returns the substring of `s` from the `i`(th)
character to the end of the string, i.e., the string
`s`[`i`..|`s`|-1]. This raises `Subscript` if `i` < 0 or |`s`| < `i`.

The second form returns the substring of size `j` starting at
index `i`, i.e., the string `s`[`i`..`i`+`j`-1]. Raises `Subscript` if
`i` < 0 or `j` < 0 or |`s`| < `i` + `j`. Note that, if defined,
`extract` returns the empty string when `i` = |`s`|.

<a id="substring-impl"></a>
<h3><code>substring</code></h3>

`substring (s, i, j)` (or `s.substring (i, j)`) returns the substring `s`[`i`..`i`+`j`-1], i.e.,
the substring of size `j` starting at index `i`. This is equivalent to
`extract(s, i, SOME j)`.

<a id="^-impl"></a>
<h3><code>^</code></h3>

`s ^ t` is the concatenation of the strings `s` and `t`. This raises
`Size` if `|s| + |t| > maxSize`.

<a id="concat-impl"></a>
<h3><code>concat</code></h3>

`concat l` is the concatenation of all the strings in `l`. This raises
`Size` if the sum of all the sizes is greater than `maxSize`.

<a id="concatWith-impl"></a>
<h3><code>concatWith</code></h3>

`concatWith s l` returns the concatenation of the strings in the list
`l` using the string `s` as a separator. This raises `Size` if the
size of the resulting string would be greater than `maxSize`.

<a id="str-impl"></a>
<h3><code>str</code></h3>

`str c` is the string of size one containing the character `c`.

<a id="implode-impl"></a>
<h3><code>implode</code></h3>

`implode l` generates the string containing the characters in the list
`l`. This is equivalent to `concat (List.map str l)`. This raises
`Size` if the resulting string would have size greater than `maxSize`.

<a id="explode-impl"></a>
<h3><code>explode</code></h3>

`explode s` (or `s.explode ()`) is the list of characters in the string `s`.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f s` applies `f` to each element of `s` from left to right,
returning the resulting string. It is equivalent to `implode(List.map
f (explode s))`.

<a id="translate-impl"></a>
<h3><code>translate</code></h3>

`translate f s` returns the string generated from `s` by mapping each
character in `s` by `f`. It is equivalent to `concat(List.map f
(explode s))`.

<a id="tokens-impl"></a>
<h3><code>tokens</code></h3>

`tokens f s` returns a list of tokens derived from `s` from left to
right. A token is a non-empty maximal substring of `s` not containing
any delimiter. A delimiter is a character satisfying the predicate
`f`.

Two tokens may be separated by more than one delimiter, whereas
two fields are separated by exactly one delimiter. For example, if
the only delimiter is the character `#"|"`, then the string
`"|abc||def"` contains two tokens `"abc"` and `"def"`, whereas it
contains the four fields `""`, `"abc"`, `""` and `"def"`.

<a id="fields-impl"></a>
<h3><code>fields</code></h3>

`fields f s` returns a list of fields derived from `s` from left to
right. A field is a (possibly empty) maximal substring of `s` not
containing any delimiter. A delimiter is a character satisfying the
predicate `f`.

Two tokens may be separated by more than one delimiter, whereas
two fields are separated by exactly one delimiter. For example, if
the only delimiter is the character `#"|"`, then the string
`"|abc||def"` contains two tokens `"abc"` and `"def"`, whereas it
contains the four fields `""`, `"abc"`, `""` and `"def"`.

<a id="isPrefix-impl"></a>
<h3><code>isPrefix</code></h3>

`isPrefix s1 s2` returns `true` if the string `s1` is a prefix of the
string `s2`. Note that the empty string is a prefix of any string, and
that a string is a prefix of itself.

<a id="isSubstring-impl"></a>
<h3><code>isSubstring</code></h3>

`isSubstring s1 s2` returns `true` if the string `s1` is a substring
of the string `s2`. Note that the empty string is a substring of any
string, and that a string is a substring of itself.

<a id="isSuffix-impl"></a>
<h3><code>isSuffix</code></h3>

`isSuffix s1 s2` returns `true` if the string `s1` is a suffix of the
string `s2`. Note that the empty string is a suffix of any string, and
that a string is a suffix of itself.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (s, t)` (or `s.compare t`) does a lexicographic comparison of the two strings
using the ordering `Char.compare` on the characters. It returns
`LESS`, `EQUAL`, or `GREATER`, if `s` is less than, equal to, or
greater than `t`, respectively.

<a id="collate-impl"></a>
<h3><code>collate</code></h3>

`collate (f, (s, t))` performs lexicographic comparison of the two
strings using the given ordering `f` on characters.

<a id="<-impl"></a>
<h3><code><</code></h3>

`s < t` returns true if `s` is less than `t` in the string ordering.

<a id="<=-impl"></a>
<h3><code><=</code></h3>

`s <= t` returns true if `s` is less than or equal to `t` in the string ordering.

<a id=">-impl"></a>
<h3><code>></code></h3>

`s > t` returns true if `s` is greater than `t` in the string ordering.

<a id=">=-impl"></a>
<h3><code>>=</code></h3>

`s >= t` returns true if `s` is greater than or equal to `t` in the string
ordering.

[//]: # (end:lib/string)
