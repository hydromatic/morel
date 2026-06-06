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
 * The STRING signature, per the Standard ML Basis Library.
 *)
(**
 * The `String` structure provides the `string` type and a comprehensive
 * set of operations for constructing, examining, searching, and converting
 * strings.
 *)
signature STRING =
sig

  (** is the type of character strings. *)
  eqtype string
  eqtype char

  (** is the longest allowed size of a string. *)
  val maxSize : int [@@prototype "maxSize"]

  (** returns |`s`|, the number of characters in string `s`. *)
  val size : string -> int [@@method] [@@prototype "size s"]

  (**
   * returns the `i`(th) character of `s`, counting from
   * zero. This raises `Subscript` if `i` < 0 or |`s`| &le; `i`.
   *)
  val sub : string * int -> char [@@method] [@@prototype "sub (s, i)"]

  (**
   * and "extract (s, i, SOME j)"
   * return substrings
   * of `s`. The first returns the substring of `s` from the `i`(th)
   * character to the end of the string, i.e., the string
   * `s`[`i`..|`s`|-1]. This raises `Subscript` if `i` < 0 or |`s`| < `i`.
   *
   * <p>The second form returns the substring of size `j` starting at
   * index `i`, i.e., the string `s`[`i`..`i`+`j`-1]. Raises `Subscript` if
   * `i` < 0 or `j` < 0 or |`s`| < `i` + `j`. Note that, if defined,
   * `extract` returns the empty string when `i` = |`s`|.
   *)
  val extract : string * int * int option -> string
      [@@method] [@@prototype "extract (s, i, NONE)"]

  (**
   * returns the substring `s`[`i`..`i`+`j`-1], i.e.,
   * the substring of size `j` starting at index `i`. This is equivalent to
   * `extract(s, i, SOME j)`.
   *)
  val substring : string * int * int -> string
      [@@method] [@@prototype "substring (s, i, j)"]

  (**
   * is the concatenation of the strings `s` and `t`. This raises
   * `Size` if `|s| + |t| > maxSize`.
   *)
  val `^` : string * string -> string [@@prototype "s ^ t"]

  (**
   * is the concatenation of all the strings in `l`. This raises
   * `Size` if the sum of all the sizes is greater than `maxSize`.
   *)
  val concat : string list -> string [@@prototype "concat l"]

  (**
   * returns the concatenation of the strings in the list
   * `l` using the string `s` as a separator. This raises `Size` if the
   * size of the resulting string would be greater than `maxSize`.
   *)
  val concatWith : string -> string list -> string
      [@@prototype "concatWith s l"]

  (** is the string of size one containing the character `c`. *)
  val str : char -> string [@@prototype "str c"]

  (**
   * generates the string containing the characters in the list
   * `l`. This is equivalent to `concat (List.map str l)`. This raises
   * `Size` if the resulting string would have size greater than `maxSize`.
   *)
  val implode : char list -> string [@@prototype "implode l"]

  (** is the list of characters in the string `s`. *)
  val explode : string -> char list [@@method] [@@prototype "explode s"]

  (**
   * applies `f` to each element of `s` from left to right,
   * returning the resulting string. It is equivalent to `implode(List.map
   * f (explode s))`.
   *)
  val map : (char -> char) -> string -> string [@@prototype "map f s"]

  (**
   * returns the string generated from `s` by mapping each
   * character in `s` by `f`. It is equivalent to `concat(List.map f
   * (explode s))`.
   *)
  val translate : (char -> string) -> string -> string
      [@@prototype "translate f s"]

  (**
   * returns a list of tokens derived from `s` from left to
   * right. A token is a non-empty maximal substring of `s` not containing
   * any delimiter. A delimiter is a character satisfying the predicate
   * `f`.
   *
   * Two tokens may be separated by more than one delimiter, whereas
   * two fields are separated by exactly one delimiter. For example, if
   * the only delimiter is the character `#"|"`, then the string
   * `"|abc||def"` contains two tokens `"abc"` and `"def"`, whereas it
   * contains the four fields `""`, `"abc"`, `""` and `"def"`.
   *)
  val tokens : (char -> bool) -> string -> string list
      [@@prototype "tokens f s"]

  (**
   * returns a list of fields derived from `s` from left to
   * right. A field is a (possibly empty) maximal substring of `s` not
   * containing any delimiter. A delimiter is a character satisfying the
   * predicate `f`.
   *
   * Two tokens may be separated by more than one delimiter, whereas
   * two fields are separated by exactly one delimiter. For example, if
   * the only delimiter is the character `#"|"`, then the string
   * `"|abc||def"` contains two tokens `"abc"` and `"def"`, whereas it
   * contains the four fields `""`, `"abc"`, `""` and `"def"`.
   *)
  val fields : (char -> bool) -> string -> string list
      [@@prototype "fields f s"]

  (**
   * returns `true` if the string `s1` is a prefix of the
   * string `s2`. Note that the empty string is a prefix of any string, and
   * that a string is a prefix of itself.
   *)
  val isPrefix : string -> string -> bool [@@prototype "isPrefix s1 s2"]

  (**
   * returns `true` if the string `s1` is a substring
   * of the string `s2`. Note that the empty string is a substring of any
   * string, and that a string is a substring of itself.
   *)
  val isSubstring : string -> string -> bool [@@prototype "isSubstring s1 s2"]

  (**
   * returns `true` if the string `s1` is a suffix of the
   * string `s2`. Note that the empty string is a suffix of any string, and
   * that a string is a suffix of itself.
   *)
  val isSuffix : string -> string -> bool [@@prototype "isSuffix s1 s2"]

  (**
   * does a lexicographic comparison of the two strings
   * using the ordering `Char.compare` on the characters. It returns
   * `LESS`, `EQUAL`, or `GREATER`, if `s` is less than, equal to, or
   * greater than `t`, respectively.
   *)
  val compare : string * string -> `order`
      [@@method] [@@prototype "compare (s, t)"]

  (**
   * performs lexicographic comparison of the two
   * strings using the given ordering `f` on characters.
   *)
  val collate : (char * char -> `order`) -> string * string -> `order`
      [@@prototype "collate (f, (s, t))"]

  (** returns true if `s` is less than `t` in the string ordering. *)
  val `<`  : string * string -> bool [@@prototype "s < t"] [@@syntax "infix"]
  (**
   * returns true if `s` is less than or equal to `t` in the string ordering.
   *)
  val `<=` : string * string -> bool [@@prototype "s <= t"] [@@syntax "infix"]
  (**
   * returns true if `s` is greater than `t` in the string ordering.
   *)
  val `>`  : string * string -> bool [@@prototype "s > t"] [@@syntax "infix"]
  (**
   * returns true if `s` is greater than or equal to `t` in the string
   * ordering.
   *)
  val `>=` : string * string -> bool [@@prototype "s >= t"] [@@syntax "infix"]
  (** returns true if `s` and `t` are equal. *)
  val `=`  : string * string -> bool [@@prototype "s = t"] [@@syntax "infix"]
  (** returns true if `s` and `t` are not equal. *)
  val `<>` : string * string -> bool [@@prototype "s <> t"] [@@syntax "infix"]

(*
  (**
   * returns a string corresponding to `s`, with non-printable
   * characters replaced by SML escape sequences. This is equivalent to
   *
   * <pre>translate Char.toString s</pre>
   *)
  val toString : string -> string
*) [@@prototype "toString s"]

(*
  (**
   * scans its character source as a sequence of printable
   * characters, converting SML escape sequences into the appropriate
   * characters. It does not skip leading whitespace. It returns as many
   * characters as can successfully be scanned, stopping when it reaches
   * the end of the string or a non-printing character (i.e., one not
   * satisfying `isPrint`), or if it encounters an improper escape
   * sequence. It returns the remaining characters as the rest of the
   * stream.
   *)
  val scan : (char, 'a) StringCvt.reader -> (string, 'a) StringCvt.reader
*) [@@prototype "scan getc strm"]

(*
  (**
   * scans the string `s` as a sequence of printable
   * characters, converting SML escape sequences into the appropriate
   * characters. It does not skip leading whitespace. It returns as many
   * characters as can successfully be scanned, stopping when it reaches
   * the end of the string or a non-printing character (i.e., one not
   * satisfying `isPrint`), or if it encounters an improper escape
   * sequence. It ignores the remaining characters.
   *
   * If no conversion is possible, e.g., if the first character is
   * non-printable or begins an illegal escape sequence, `NONE` is
   * returned. Note, however, that `fromString ""` returns `SOME("")`.
   *
   * For more information on the allowed escape sequences, see the entry
   * for `CHAR.fromString`. SML source also allows escaped formatting
   * sequences, which are ignored during conversion. The rule is that if
   * any prefix of the input is successfully scanned, including an escaped
   * formatting sequence, the function returns some string. It only
   * returns `NONE` in the case where the prefix of the input cannot be
   * scanned at all. Here are some sample conversions:
   *
   * <pre>
   * Input string s fromString s
   * ============== ============
   * "\\q"
   *          NONE
   * "a\^D"
   *         SOME "a"
   * "a\\ \\\\q"
   * SOME "a"
   * "\\ \\"
   *      SOME ""
   * ""
   *             SOME ""
   * "\\ \\\^D"
   *     SOME ""
   * "\\ a"
   *         NONE
   * </pre>
   *
   * *Implementation note*: Because of the special cases, such as
   * `fromString ""` = `SOME ""`,
   * `fromString "\\ \\\^D"` = `SOME ""`, and
   * `fromString "\^D"
   * = NONE`,
   * the function cannot be implemented as a simple iterative application
   * of `CHAR.scan`.
   *)
  val fromString : string -> string option
*) [@@prototype "fromString s"]

(*
  (**
   * returns a string corresponding to `s`, with non-printable
   * characters replaced by C escape sequences. This is equivalent to
   *
   * <pre>translate Char.toCString s</pre>
   *)
  val toCString : string -> string
*) [@@prototype "toCString s"]

(*
  (**
   * scans the string `s` as a string in the C language,
   * converting C escape sequences into the appropriate characters. The
   * semantics are identical to `fromString` above, except that C escape
   * sequences are used (see ISO C standard ISO/IEC 9899:1990).
   *
   * For more information on the allowed escape sequences, see the entry
   * for `CHAR.fromCString`. Note that `fromCString` accepts an unescaped
   * single quote character, but does not accept an unescaped double
   * quote character.
   *)
  val fromCString : string -> string option
*) [@@prototype "fromCString s"]
end
[@@description "String operations."]

(*) End string.sig
