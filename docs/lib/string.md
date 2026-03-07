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
eqtype <a id='string' href="#string-impl">string</a>

val <a id='collate' href="#collate-impl">collate</a> : (char * char -> order) -> string * string -> order
val <a id='compare' href="#compare-impl">compare</a> : string * string -> order
val <a id='^' href="#^-impl">^</a> : string * string -> string
val <a id='concat' href="#concat-impl">concat</a> : string list -> string
val <a id='concatWith' href="#concatWith-impl">concatWith</a> : string -> string list -> string
val <a id='explode' href="#explode-impl">explode</a> : string -> char list
val <a id='extract' href="#extract-impl">extract</a> : string * int * int option -> string
val <a id='implode' href="#implode-impl">implode</a> : char list -> string
val <a id='isPrefix' href="#isPrefix-impl">isPrefix</a> : string -> string -> bool
val <a id='isSubstring' href="#isSubstring-impl">isSubstring</a> : string -> string -> bool
val <a id='isSuffix' href="#isSuffix-impl">isSuffix</a> : string -> string -> bool
val <a id='map' href="#map-impl">map</a> : (char -> char) -> string -> string
val <a id='maxSize' href="#maxSize-impl">maxSize</a> : int
val <a id='size' href="#size-impl">size</a> : string -> int
val <a id='str' href="#str-impl">str</a> : char -> string
val <a id='sub' href="#sub-impl">sub</a> : string * int -> char
val <a id='substring' href="#substring-impl">substring</a> : string * int * int -> string
val <a id='translate' href="#translate-impl">translate</a> : (char -> string) -> string -> string
val <a id='fromCString' href="#fromCString-impl">fromCString</a> : string -> string option
val <a id='toString' href="#toString-impl">toString</a> : string -> string
val <a id='scan' href="#scan-impl">scan</a> : (char,'a) StringCvt.reader -> (string,'a) StringCvt.reader
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> string option
val <a id='tokens' href="#tokens-impl">tokens</a> : (char -> bool) -> string -> string list
val <a id='toCString' href="#toCString-impl">toCString</a> : string -> string
val <a id='<' href="#<-impl"><</a> : string * string -> bool
val <a id='<=' href="#<=-impl"><=</a> : string * string -> bool
val <a id='>' href="#>-impl">></a> : string * string -> bool
val <a id='>=' href="#>=-impl">>=</a> : string * string -> bool
val <a id='fields' href="#fields-impl">fields</a> : (char -> bool) -> string -> string list
</pre>

<a id="string-impl"></a>
<h3><code><strong>eqtype</strong> string</code></h3>

is the type of character strings.

<a id="collate-impl"></a>
<h3><code>collate</code></h3>

`collate (f, (s, t))` performs lexicographic comparison of the two
strings using the given ordering `f` on characters.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (s, t)` (or `s.compare t`) does a lexicographic comparison of the two strings
using the ordering `Char.compare` on the characters. It returns
`LESS`, `EQUAL`, or `GREATER`, if `s` is less than, equal to, or
greater than `t`, respectively.

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

<a id="explode-impl"></a>
<h3><code>explode</code></h3>

`explode s` (or `s.explode ()`) is the list of characters in the string `s`.

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

<a id="implode-impl"></a>
<h3><code>implode</code></h3>

`implode l` generates the string containing the characters in the list
`l`. This is equivalent to `concat (List.map str l)`. This raises
`Size` if the resulting string would have size greater than `maxSize`.

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

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f s` applies `f` to each element of `s` from left to right,
returning the resulting string. It is equivalent to `implode(List.map
f (explode s))`.

<a id="maxSize-impl"></a>
<h3><code>maxSize</code></h3>

`maxSize` is the longest allowed size of a string.

<a id="size-impl"></a>
<h3><code>size</code></h3>

`size s` (or `s.size ()`) returns |`s`|, the number of characters in string `s`.

<a id="str-impl"></a>
<h3><code>str</code></h3>

`str c` is the string of size one containing the character `c`.

<a id="sub-impl"></a>
<h3><code>sub</code></h3>

`sub (s, i)` (or `s.sub i`) returns the `i`(th) character of `s`, counting from
zero. This raises `Subscript` if `i` < 0 or |`s`| &le; `i`.

<a id="substring-impl"></a>
<h3><code>substring</code></h3>

`substring (s, i, j)` (or `s.substring (i, j)`) returns the substring `s`[`i`..`i`+`j`-1], i.e.,
the substring of size `j` starting at index `i`. This is equivalent to
`extract(s, i, SOME j)`.

<a id="translate-impl"></a>
<h3><code>translate</code></h3>

`translate f s` returns the string generated from `s` by mapping each
character in `s` by `f`. It is equivalent to `concat(List.map f
(explode s))`.

<a id="fromCString-impl"></a>
<h3><code>fromCString</code></h3>

`fromCString s` scans the string `s` as a string in the C language,
converting C escape sequences into the appropriate characters. The
semantics are identical to `fromString` above, except that C escape
sequences are used (see ISO C standard ISO/IEC 9899:1990).

For more information on the allowed escape sequences, see the entry
for `CHAR.fromCString`. Note that `fromCString` accepts an unescaped
single quote character, but does not accept an unescaped double
quote character.

*Not yet implemented.*

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString s` returns a string corresponding to `s`, with non-printable
characters replaced by SML escape sequences. This is equivalent to

<pre>translate Char.toString s</pre>

*Not yet implemented.*

<a id="scan-impl"></a>
<h3><code>scan</code></h3>

`scan getc strm` scans its character source as a sequence of printable
characters, converting SML escape sequences into the appropriate
characters. It does not skip leading whitespace. It returns as many
characters as can successfully be scanned, stopping when it reaches
the end of the string or a non-printing character (i.e., one not
satisfying `isPrint`), or if it encounters an improper escape
sequence. It returns the remaining characters as the rest of the
stream.

*Not yet implemented.*

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` scans the string `s` as a sequence of printable
characters, converting SML escape sequences into the appropriate
characters. It does not skip leading whitespace. It returns as many
characters as can successfully be scanned, stopping when it reaches
the end of the string or a non-printing character (i.e., one not
satisfying `isPrint`), or if it encounters an improper escape
sequence. It ignores the remaining characters.

If no conversion is possible, e.g., if the first character is
non-printable or begins an illegal escape sequence, `NONE` is
returned. Note, however, that `fromString ""` returns `SOME("")`.

For more information on the allowed escape sequences, see the entry
for `CHAR.fromString`. SML source also allows escaped formatting
sequences, which are ignored during conversion. The rule is that if
any prefix of the input is successfully scanned, including an escaped
formatting sequence, the function returns some string. It only
returns `NONE` in the case where the prefix of the input cannot be
scanned at all. Here are some sample conversions:

<pre>
Input string s fromString s
============== ============
"\\q"
         NONE
"a\^D"
        SOME "a"
"a\\ \\\\q"
SOME "a"
"\\ \\"
     SOME ""
""
            SOME ""
"\\ \\\^D"
    SOME ""
"\\ a"
        NONE
</pre>

*Implementation note*: Because of the special cases, such as
`fromString ""` = `SOME ""`,
`fromString "\\ \\\^D"` = `SOME ""`, and
`fromString "\^D"
= NONE`,
the function cannot be implemented as a simple iterative application
of `CHAR.scan`.

*Not yet implemented.*

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

<a id="toCString-impl"></a>
<h3><code>toCString</code></h3>

`toCString s` returns a string corresponding to `s`, with non-printable
characters replaced by C escape sequences. This is equivalent to

<pre>translate Char.toCString s</pre>

*Not yet implemented.*

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

[//]: # (end:lib/string)
