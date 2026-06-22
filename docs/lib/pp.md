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

# PP structure

[Up to index](index.md)

[//]: # (start:lib/pp)
The `PP` structure is a combinator library for pretty-printing, in the
style of Wadler and Leijen. A value of type `doc` is an abstract document
that describes a piece of text together with the points at which it may
be broken across lines. The `render` function lays a `doc` out into a
`string`, choosing the most compact layout that fits a given line width.

Documents are built from `text`, the line-break primitives (`line`,
`lineBreak`, `softLine`, `softBreak`, `hardLine`), and combinators such as
`beside`, `nest`, `group`, and the list operators `sep`, `cat`, `fillSep`,
and `fillCat`. A `group` lays its contents out on a single line if they
fit, and otherwise breaks them.

The combinators follow Philip Wadler's
[_A prettier printer_](https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf),
extended with the alignment and fill operators of Daan Leijen's
[`wl-pprint`](https://hackage.haskell.org/package/wl-pprint) library. The
renderer follows Christian Lindig's
[_Strictly Pretty_](https://lindig.github.io/papers/strictly-pretty-2000.pdf),
an eager implementation that suits a strict language such as Standard ML:
it decides each `group` in a single forward pass, rather than relying on
the laziness of Wadler's original Haskell version.

## Synopsis

<pre>
type <a id='doc' href="#doc-impl">doc</a>

val <a id='empty' href="#empty-impl">empty</a> : doc
val <a id='line' href="#line-impl">line</a> : doc
val <a id='lineBreak' href="#lineBreak-impl">lineBreak</a> : doc
val <a id='softLine' href="#softLine-impl">softLine</a> : doc
val <a id='softBreak' href="#softBreak-impl">softBreak</a> : doc
val <a id='hardLine' href="#hardLine-impl">hardLine</a> : doc
val <a id='text' href="#text-impl">text</a> : string -> doc
val <a id='beside' href="#beside-impl">beside</a> : doc * doc -> doc
val <a id='nest' href="#nest-impl">nest</a> : int * doc -> doc
val <a id='group' href="#group-impl">group</a> : doc -> doc
val <a id='align' href="#align-impl">align</a> : doc -> doc
val <a id='hang' href="#hang-impl">hang</a> : int * doc -> doc
val <a id='indent' href="#indent-impl">indent</a> : int * doc -> doc
val <a id='hsep' href="#hsep-impl">hsep</a> : doc list -> doc
val <a id='vsep' href="#vsep-impl">vsep</a> : doc list -> doc
val <a id='sep' href="#sep-impl">sep</a> : doc list -> doc
val <a id='hcat' href="#hcat-impl">hcat</a> : doc list -> doc
val <a id='vcat' href="#vcat-impl">vcat</a> : doc list -> doc
val <a id='cat' href="#cat-impl">cat</a> : doc list -> doc
val <a id='fillSep' href="#fillSep-impl">fillSep</a> : doc list -> doc
val <a id='fillCat' href="#fillCat-impl">fillCat</a> : doc list -> doc
val <a id='punctuate' href="#punctuate-impl">punctuate</a> : doc * doc list -> doc list
val <a id='encloseSep' href="#encloseSep-impl">encloseSep</a> : doc * doc * doc * doc list -> doc
val <a id='parens' href="#parens-impl">parens</a> : doc -> doc
val <a id='braces' href="#braces-impl">braces</a> : doc -> doc
val <a id='brackets' href="#brackets-impl">brackets</a> : doc -> doc
val <a id='render' href="#render-impl">render</a> : int * doc -> string
</pre>

<a id="doc-impl"></a>
<h3><code><strong>type</strong> doc</code></h3>

is the type of a pretty-printer document.

<a id="empty-impl"></a>
<h3><code>empty</code></h3>

`empty` is the empty document.

<a id="line-impl"></a>
<h3><code>line</code></h3>

`line` is a line break. It is rendered as a single space when the enclosing
group fits on one line, and as a newline otherwise.

<a id="lineBreak-impl"></a>
<h3><code>lineBreak</code></h3>

`lineBreak` is a line break. It is rendered as nothing when the enclosing group fits
on one line, and as a newline otherwise.

<a id="softLine-impl"></a>
<h3><code>softLine</code></h3>

`softLine` is a line break that is rendered as a single space when flattened.

<a id="softBreak-impl"></a>
<h3><code>softBreak</code></h3>

`softBreak` is a line break that is rendered as nothing when flattened.

<a id="hardLine-impl"></a>
<h3><code>hardLine</code></h3>

`hardLine` is a line break that is always rendered as a newline, even inside a
group that would otherwise fit on one line.

<a id="text-impl"></a>
<h3><code>text</code></h3>

`text s` is a document containing the literal string `s`.

<a id="beside-impl"></a>
<h3><code>beside</code></h3>

`beside (a, b)` is `a` placed directly to the left of `b`, with nothing between them.

<a id="nest-impl"></a>
<h3><code>nest</code></h3>

`nest (i, d)` increases the indentation of `d` by `i` columns.

<a id="group-impl"></a>
<h3><code>group</code></h3>

`group d` lays `d` out on one line if it fits, otherwise lays it out broken across
lines.

<a id="align-impl"></a>
<h3><code>align</code></h3>

`align d` sets the indentation of `d` to the current column.

<a id="hang-impl"></a>
<h3><code>hang</code></h3>

`hang (i, d)` renders `d` with its nesting set to the current column plus `i`,
placing the first line at the current column.

<a id="indent-impl"></a>
<h3><code>indent</code></h3>

`indent (i, d)` indents `d` by `i` columns, including its first line.

<a id="hsep-impl"></a>
<h3><code>hsep</code></h3>

`hsep ds` concatenates the documents `ds`, separating them with spaces.

<a id="vsep-impl"></a>
<h3><code>vsep</code></h3>

`vsep ds` concatenates the documents `ds`, separating them with line breaks.

<a id="sep-impl"></a>
<h3><code>sep</code></h3>

`sep ds` concatenates the documents `ds`, separating them with spaces if the
result fits on one line, and with line breaks otherwise.

<a id="hcat-impl"></a>
<h3><code>hcat</code></h3>

`hcat ds` concatenates the documents `ds` with nothing between them.

<a id="vcat-impl"></a>
<h3><code>vcat</code></h3>

`vcat ds` concatenates the documents `ds`, separating them with line breaks.

<a id="cat-impl"></a>
<h3><code>cat</code></h3>

`cat ds` concatenates the documents `ds` with nothing between them if the result
fits on one line, and with line breaks otherwise.

<a id="fillSep-impl"></a>
<h3><code>fillSep</code></h3>

`fillSep ds` concatenates the documents `ds`, separating them with spaces, and
inserting a line break whenever the next document does not fit.

<a id="fillCat-impl"></a>
<h3><code>fillCat</code></h3>

`fillCat ds` concatenates the documents `ds` with nothing between them, inserting a
line break whenever the next document does not fit.

<a id="punctuate-impl"></a>
<h3><code>punctuate</code></h3>

`punctuate (sep, ds)` inserts the separator `sep` between each of the documents `ds`,
returning the resulting list of documents.

<a id="encloseSep-impl"></a>
<h3><code>encloseSep</code></h3>

`encloseSep (open_, close, sep, ds)` concatenates the documents `ds`, separating them with `sep` and
enclosing the result between `open_` and `close`.

<a id="parens-impl"></a>
<h3><code>parens</code></h3>

`parens d` encloses `d` between parentheses.

<a id="braces-impl"></a>
<h3><code>braces</code></h3>

`braces d` encloses `d` between braces.

<a id="brackets-impl"></a>
<h3><code>brackets</code></h3>

`brackets d` encloses `d` between square brackets.

<a id="render-impl"></a>
<h3><code>render</code></h3>

`render (w, d)` renders `d` to a string, choosing the best layout for line width `w`.

[//]: # (end:lib/pp)
