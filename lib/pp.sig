(*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 *
 * The PP signature, a Wadler-Leijen pretty-printer.
 *)
(**
 * The `PP` structure is a combinator library for pretty-printing, in the
 * style of Wadler and Leijen. A value of type `doc` is an abstract document
 * that describes a piece of text together with the points at which it may
 * be broken across lines. The `render` function lays a `doc` out into a
 * `string`, choosing the most compact layout that fits a given line width.
 *
 * Documents are built from `text`, the line-break primitives (`line`,
 * `lineBreak`, `softLine`, `softBreak`, `hardLine`), and combinators such as
 * `beside`, `nest`, `group`, and the list operators `sep`, `cat`, `fillSep`,
 * and `fillCat`. A `group` lays its contents out on a single line if they
 * fit, and otherwise breaks them.
 *
 * The combinators follow Philip Wadler's
 * [_A prettier printer_](https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf),
 * extended with the alignment and fill operators of Daan Leijen's
 * [`wl-pprint`](https://hackage.haskell.org/package/wl-pprint) library. The
 * renderer follows Christian Lindig's
 * [_Strictly Pretty_](https://lindig.github.io/papers/strictly-pretty-2000.pdf),
 * an eager implementation that suits a strict language such as Standard ML:
 * it decides each `group` in a single forward pass, rather than relying on
 * the laziness of Wadler's original Haskell version.
 *)
signature PP =
sig

  (** is the type of a pretty-printer document. *)
  type doc

  (** is the empty document. *)
  val empty : doc [@@prototype "empty"]

  (**
   * is a line break. It is rendered as a single space when the enclosing
   * group fits on one line, and as a newline otherwise.
   *)
  val line : doc [@@prototype "line"]

  (**
   * is a line break. It is rendered as nothing when the enclosing group fits
   * on one line, and as a newline otherwise.
   *)
  val lineBreak : doc [@@prototype "lineBreak"]

  (** is a line break that is rendered as a single space when flattened. *)
  val softLine : doc [@@prototype "softLine"]

  (** is a line break that is rendered as nothing when flattened. *)
  val softBreak : doc [@@prototype "softBreak"]

  (**
   * is a line break that is always rendered as a newline, even inside a
   * group that would otherwise fit on one line.
   *)
  val hardLine : doc [@@prototype "hardLine"]

  (** is a document containing the literal string `s`. *)
  val text : string -> doc [@@prototype "text s"]

  (** is `a` placed directly to the left of `b`, with nothing between them. *)
  val beside : doc * doc -> doc [@@prototype "beside (a, b)"]

  (** increases the indentation of `d` by `i` columns. *)
  val nest : int * doc -> doc [@@prototype "nest (i, d)"]

  (**
   * lays `d` out on one line if it fits, otherwise lays it out broken across
   * lines.
   *)
  val `group` : doc -> doc [@@prototype "group d"]

  (** sets the indentation of `d` to the current column. *)
  val align : doc -> doc [@@prototype "align d"]

  (**
   * renders `d` with its nesting set to the current column plus `i`,
   * placing the first line at the current column.
   *)
  val hang : int * doc -> doc [@@prototype "hang (i, d)"]

  (** indents `d` by `i` columns, including its first line. *)
  val indent : int * doc -> doc [@@prototype "indent (i, d)"]

  (** concatenates the documents `ds`, separating them with spaces. *)
  val hsep : doc list -> doc [@@prototype "hsep ds"]

  (** concatenates the documents `ds`, separating them with line breaks. *)
  val vsep : doc list -> doc [@@prototype "vsep ds"]

  (**
   * concatenates the documents `ds`, separating them with spaces if the
   * result fits on one line, and with line breaks otherwise.
   *)
  val sep : doc list -> doc [@@prototype "sep ds"]

  (** concatenates the documents `ds` with nothing between them. *)
  val hcat : doc list -> doc [@@prototype "hcat ds"]

  (** concatenates the documents `ds`, separating them with line breaks. *)
  val vcat : doc list -> doc [@@prototype "vcat ds"]

  (**
   * concatenates the documents `ds` with nothing between them if the result
   * fits on one line, and with line breaks otherwise.
   *)
  val cat : doc list -> doc [@@prototype "cat ds"]

  (**
   * concatenates the documents `ds`, separating them with spaces, and
   * inserting a line break whenever the next document does not fit.
   *)
  val fillSep : doc list -> doc [@@prototype "fillSep ds"]

  (**
   * concatenates the documents `ds` with nothing between them, inserting a
   * line break whenever the next document does not fit.
   *)
  val fillCat : doc list -> doc [@@prototype "fillCat ds"]

  (**
   * inserts the separator `sep` between each of the documents `ds`,
   * returning the resulting list of documents.
   *)
  val punctuate : doc * doc list -> doc list
      [@@prototype "punctuate (sep, ds)"]

  (**
   * concatenates the documents `ds`, separating them with `sep` and
   * enclosing the result between `open_` and `close`.
   *)
  val encloseSep : doc * doc * doc * doc list -> doc
      [@@prototype "encloseSep (open_, close, sep, ds)"]

  (** encloses `d` between parentheses. *)
  val parens : doc -> doc [@@prototype "parens d"]

  (** encloses `d` between braces. *)
  val braces : doc -> doc [@@prototype "braces d"]

  (** encloses `d` between square brackets. *)
  val brackets : doc -> doc [@@prototype "brackets d"]

  (**
   * renders `d` to a string, choosing the best layout for line width `w`.
   *)
  val render : int * doc -> string [@@prototype "render (w, d)"]

end
[@@description "Wadler-Leijen pretty-printer."]
[@@specified "morel"]

(*) End pp.sig
