/*
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
 */
package net.hydromatic.morel.compile;

import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.DummyType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.Static;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;

import static net.hydromatic.morel.type.PrimitiveType.BOOL;
import static net.hydromatic.morel.type.PrimitiveType.CHAR;
import static net.hydromatic.morel.type.PrimitiveType.INT;
import static net.hydromatic.morel.type.PrimitiveType.REAL;
import static net.hydromatic.morel.type.PrimitiveType.STRING;
import static net.hydromatic.morel.type.PrimitiveType.UNIT;

/** Built-in constants and functions. */
public enum BuiltIn {
  /** Literal "true", of type "bool". */
  TRUE(null, "true", ts -> BOOL),

  /** Literal "false", of type "bool". */
  FALSE(null, "false", ts -> BOOL),

  /** Function "not", of type "bool &rarr; bool". */
  NOT(null, "not", ts -> ts.fnType(BOOL, BOOL)),

  /** Function "abs", of type "int &rarr; int". */
  ABS(null, "abs", ts -> ts.fnType(INT, INT)),

  /** Infix operator "^", of type "string * string &rarr; string". */
  OP_CARET(null, "op ^", ts -> ts.fnType(ts.tupleType(STRING, STRING), STRING)),

  /** Infix operator "except", of type "&alpha; list * &alpha; list &rarr;
   * &alpha; list". */
  OP_EXCEPT(null, "op except", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(ts.listType(h.get(0)), ts.listType(h.get(0))),
              ts.listType(h.get(0))))),

  /** Infix operator "intersect", of type "&alpha; list * &alpha; list &rarr;
   * &alpha; list". */
  OP_INTERSECT(null, "op intersect", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(ts.listType(h.get(0)), ts.listType(h.get(0))),
              ts.listType(h.get(0))))),

  /** Infix operator "union", of type "&alpha; list * &alpha; list &rarr;
   * &alpha; list". */
  OP_UNION(null, "op union", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(ts.listType(h.get(0)), ts.listType(h.get(0))),
              ts.listType(h.get(0))))),

  /** Infix operator "::" (list cons), of type
   * "&alpha; * &alpha; list &rarr; &alpha; list". */
  OP_CONS(null, "op ::", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), ts.listType(h.get(0))),
              ts.listType(h.get(0))))),

  /** Infix operator "div", of type "int * int &rarr; int". */
  OP_DIV(null, "op div", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Infix operator "/", of type "&alpha; * &alpha; &rarr; &alpha;"
   * (where &alpha; must be numeric). */
  OP_DIVIDE(null, "op /", PrimitiveType.INT, ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), h.get(0)))),

  /** Infix operator "=", of type "&alpha; * &alpha; &rarr; bool". */
  OP_EQ(null, "op =", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), BOOL))),

  /** Infix operator "&ge;", of type "&alpha; * &alpha; &rarr; bool"
   * (where &alpha; must be comparable). */
  OP_GE(null, "op >=", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), BOOL))),

  /** Infix operator "&gt;", of type "&alpha; * &alpha; &rarr; bool"
   * (where &alpha; must be comparable). */
  OP_GT(null, "op >", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), BOOL))),

  /** Infix operator "&le;", of type "&alpha; * &alpha; &rarr; bool"
   * (where &alpha; must be comparable). */
  OP_LE(null, "op <=", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), BOOL))),

  /** Infix operator "&lt;", of type "&alpha; * &alpha; &rarr; bool"
   * (where &alpha; must be comparable). */
  OP_LT(null, "op <", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), BOOL))),

  /** Infix operator "&lt;&gt;", of type "&alpha; * &alpha; &rarr; bool". */
  OP_NE(null, "op <>", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), BOOL))),

  /** Infix operator "elem", of type "&alpha; * &alpha; list; &rarr; bool". */
  OP_ELEM(null, "op elem", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), ts.listType(h.get(0))), BOOL))),

  /** Infix operator "notelem", of type "&alpha; * &alpha; list; &rarr;
   * bool". */
  OP_NOT_ELEM(null, "op notelem", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), ts.listType(h.get(0))), BOOL))),

  /** Infix operator "-", of type "&alpha; * &alpha; &rarr; &alpha;"
   * (where &alpha; must be numeric). */
  OP_MINUS(null, "op -", PrimitiveType.INT, ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), h.get(0)))),

  /** Infix operator "mod", of type "int * int &rarr; int". */
  OP_MOD(null, "op mod", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Infix operator "+", of type "&alpha; * &alpha; &rarr; &alpha;"
   * (where &alpha; must be numeric). */
  OP_PLUS(null, "op +", PrimitiveType.INT, ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), h.get(0)))),

  /** Prefix operator "~", of type "&alpha; &rarr; &alpha;"
   * (where &alpha; must be numeric). */
  OP_NEGATE(null, "op ~", ts ->
      ts.forallType(1, h -> ts.fnType(h.get(0), h.get(0)))),

  /** Infix operator "-", of type "&alpha; * &alpha; &rarr; &alpha;"
   * (where &alpha; must be numeric). */
  OP_TIMES(null, "op *", PrimitiveType.INT, ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.get(0), h.get(0)), h.get(0)))),

  /** Function "General.ignore", of type "&alpha; &rarr; unit". */
  IGNORE("General", "ignore", "ignore", ts ->
      ts.forallType(1, h -> ts.fnType(h.get(0), UNIT))),

  /** Operator "General.op o", of type "(&beta; &rarr; &gamma;) *
   * (&alpha; &rarr; &beta;) &rarr; &alpha; &rarr; &gamma;"
   *
   * <p>"f o g" is the function composition of "f" and "g". Thus, "(f o g) a"
   * is equivalent to "f (g a)". */
  GENERAL_OP_O("General", "op o", "op o", ts ->
      ts.forallType(3, h ->
          ts.fnType(
              ts.tupleType(ts.fnType(h.get(1), h.get(2)),
                  ts.fnType(h.get(0), h.get(1))),
              ts.fnType(h.get(0), h.get(2))))),

  /** Function "Interact.use" of type "string &rarr; unit"
   *
   * <p>"use f" loads source text from the file named `f`. */
  INTERACT_USE("Interact", "use", "use", ts ->
      ts.fnType(STRING, UNIT)),

  /** Constant "String.maxSize", of type "int".
   *
   * <p>"The longest allowed size of a string". */
  STRING_MAX_SIZE("String", "maxSize", ts -> INT),

  /** Function "String.size", of type "string &rarr; int".
   *
   * <p>"size s" returns |s|, the number of characters in string s. */
  STRING_SIZE("String", "size", "size", ts -> ts.fnType(STRING, INT)),

  /** Function "String.sub", of type "string * int &rarr; char".
   *
   * <p>"sub (s, i)" returns the {@code i}<sup>th</sup> character of s, counting
   * from zero. This raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * if i &lt; 0 or |s| &le; i. */
  STRING_SUB("String", "sub", ts -> ts.fnType(ts.tupleType(STRING, INT), CHAR)),

  /** Function "String.extract", of type "string * int * int option &rarr;
   * string".
   *
   * <p>"extract (s, i, NONE)" and "extract (s, i, SOME j)" return substrings of
   * {@code s}. The first returns the substring of {@code s} from the
   * {@code i}<sup>th</sup> character to the end of the string, i.e., the string
   * {@code s[i..|s|-1]}. This raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * if {@code i < 0} or {@code |s| < i}.
   *
   * <p>The second form returns the substring of size {@code j} starting at
   * index {@code i}, i.e., the {@code string s[i..i+j-1]}. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * if {@code i < 0} or {@code j < 0} or {@code |s| < i + j}. Note that, if
   * defined, extract returns the empty string when {@code i = |s|}. */
  STRING_EXTRACT("String", "extract", ts ->
      ts.fnType(ts.tupleType(STRING, INT, ts.option(INT)), STRING)),

  /** Function "String.substring", of type "string * int * int &rarr; string".
   *
   * <p>"substring (s, i, j)" returns the substring s[i..i+j-1], i.e., the
   * substring of size j starting at index i. This is equivalent to
   * extract(s, i, SOME j). */
  STRING_SUBSTRING("String", "substring", "substring", ts ->
      ts.fnType(ts.tupleType(STRING, INT, INT), STRING)),

  /** Function "String.concat", of type "string list &rarr; string".
   *
   * <p>"concat l" is the concatenation of all the strings in l. This raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * if the sum of all the sizes is greater than maxSize.  */
  STRING_CONCAT("String", "concat", "concat", ts ->
      ts.fnType(ts.listType(STRING), STRING)),

  /** Function "String.concatWith", of type "string &rarr; string list &rarr;
   * string".
   *
   * <p>"concatWith s l" returns the concatenation of the strings in the list l
   * using the string s as a separator. This raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * if the size of the resulting string would be greater than maxSize. */
  STRING_CONCAT_WITH("String", "concatWith", ts ->
      ts.fnType(STRING, ts.listType(STRING), STRING)),

  /** Function "String.str", of type "char &rarr; string".
   *
   * <p>"str c" is the string of size one containing the character c. */
  STRING_STR("String", "str", "str", ts -> ts.fnType(CHAR, STRING)),

  /** Function "String.implode", of type "char list &rarr; string".
   *
   * <p>"implode l" generates the string containing the characters in the list
   * l. This is equivalent to {@code concat (List.map str l)}. This raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * if the resulting string would have size greater than maxSize. */
  STRING_IMPLODE("String", "implode", "implode", ts ->
      ts.fnType(ts.listType(CHAR), STRING)),

  /** Function "String.explode", of type "string &rarr; char list".
   *
   * <p>"explode s" is the list of characters in the string s. */
  STRING_EXPLODE("String", "explode", "explode", ts ->
      ts.fnType(STRING, ts.listType(CHAR))),

  /** Function "String.map", of type "(char &rarr; char) &rarr; string
   * &rarr; string".
   *
   * <p>"map f s" applies f to each element of s from left to right, returning
   * the resulting string. It is equivalent to
   * {@code implode(List.map f (explode s))}.  */
  STRING_MAP("String", "map", ts ->
      ts.fnType(ts.fnType(CHAR, CHAR), STRING, STRING)),

  /** Function "String.translate", of type "(char &rarr; string) &rarr; string
   * &rarr; string".
   *
   * <p>"translate f s" returns the string generated from s by mapping each
   * character in s by f. It is equivalent to
   * {code concat(List.map f (explode s))}. */
  STRING_TRANSLATE("String", "translate", ts ->
      ts.fnType(ts.fnType(CHAR, STRING), STRING, STRING)),

  /** Function "String.isPrefix", of type "string &rarr; string &rarr; bool".
   *
   * <p>"isPrefix s1 s2" returns true if the string s1 is a prefix of the string
   * s2. Note that the empty string is a prefix of any string, and that a string
   * is a prefix of itself. */
  STRING_IS_PREFIX("String", "isPrefix", ts -> ts.fnType(STRING, STRING, BOOL)),

  /** Function "String.isSubstring", of type "string &rarr; string &rarr; bool".
   *
   * <p>"isSubstring s1 s2" returns true if the string s1 is a substring of the
   * string s2. Note that the empty string is a substring of any string, and
   * that a string is a substring of itself. */
  STRING_IS_SUBSTRING("String", "isSubstring", ts ->
      ts.fnType(STRING, STRING, BOOL)),

  /** Function "String.isSuffix", of type "string &rarr; string &rarr; bool".
   *
   * <p>"isSuffix s1 s2" returns true if the string s1 is a suffix of the string
   * s2. Note that the empty string is a suffix of any string, and that a string
   * is a suffix of itself. */
  STRING_IS_SUFFIX("String", "isSuffix", ts -> ts.fnType(STRING, STRING, BOOL)),

  /** Constant "List.nil", of type "&alpha; list".
   *
   * <p>"nil" is the empty list.
   */
  LIST_NIL("List", "nil", ts -> ts.forallType(1, h -> h.list(0))),

  /** Function "List.null", of type "&alpha; list &rarr; bool".
   *
   * <p>"null l" returns true if the list l is empty.
   */
  LIST_NULL("List", "null", "null", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), BOOL))),

  /** Function "List.length", of type "&alpha; list &rarr; int".
   *
   * <p>"length l" returns the number of elements in the list l.
   */
  LIST_LENGTH("List", "length", "length", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), INT))),

  /** Function "List.at", of type "&alpha; list * &alpha; list &rarr; &alpha;
   * list".
   *
   * <p>"l1 @ l2" returns the list that is the concatenation of l1 and l2.
   */
  // TODO: remove
  LIST_AT("List", "at", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)))),

  /** Operator "List.op @", of type "&alpha; list * &alpha; list &rarr; &alpha;
   * list".
   *
   * <p>"l1 @ l2" returns the list that is the concatenation of l1 and l2.
   */
  LIST_OP_AT("List", "op @", "op @", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)))),

  /** Function "List.hd", of type "&alpha; list &rarr; &alpha;".
   *
   * <p>"hd l" returns the first element of l. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#EMPTY Empty}
   * if l is nil.
   */
  LIST_HD("List", "hd", "hd", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.get(0)))),

  /** Function "List.tl", of type "&alpha; list &rarr; &alpha; list".
   *
   * <p>"tl l" returns all but the first element of l. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#EMPTY empty}
   * if l is nil.
   */
  LIST_TL("List", "tl", "tl", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.list(0)))),

  /** Function "List.last", of type "&alpha; list &rarr; &alpha;".
   *
   * <p>"last l" returns the last element of l. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#EMPTY empty}
   * if l is nil.
   */
  LIST_LAST("List", "last", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.get(0)))),

  /** Function "List.getItem", of type "&alpha; list &rarr;
   * (&alpha; * &alpha; list) option".
   *
   * <p>"getItem l" returns {@code NONE} if the list is empty, and
   * {@code SOME(hd l,tl l)} otherwise. This function is particularly useful for
   * creating value readers from lists of characters. For example,
   * {@code Int.scan StringCvt.DEC getItem} has the type
   * {@code (int, char list) StringCvt.reader}
   * and can be used to scan decimal integers from lists of characters.
   */
  LIST_GET_ITEM("List", "getItem", ts ->
      ts.forallType(1, h ->
          ts.fnType(h.list(0),
              ts.option(ts.tupleType(h.get(0), h.list(0)))))),

  /** Function "List.nth", of type "&alpha; list * int &rarr; &alpha;".
   *
   * <p>"nth (l, i)" returns the {@code i}<sup>th</sup> element of the list
   * {@code l}, counting from 0. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * if {@code i < 0} or {@code i >= length l}.
   * We have {@code nth(l,0) = hd l}, ignoring exceptions.
   */
  LIST_NTH("List", "nth", ts ->
      ts.forallType(1, h -> ts.fnType(ts.tupleType(h.list(0), INT), h.get(0)))),

  /** Function "List.take", of type "&alpha; list * int &rarr; &alpha; list".
   *
   * <p>"take (l, i)" returns the first i elements of the list l. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * if i &lt; 0 or i &gt; length l.
   * We have {@code take(l, length l) = l}.
   */
  LIST_TAKE("List", "take", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), INT), h.list(0)))),

  /** Function "List.drop", of type "&alpha; list * int &rarr; &alpha; list".
   *
   * <p>"drop (l, i)" returns what is left after dropping the first i elements
   * of the list l.
   *
   * <p>It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * if i &lt; 0 or i &gt; length l.
   *
   * <p>It holds that
   * {@code take(l, i) @ drop(l, i) = l} when 0 &le; i &le; length l.
   *
   * <p>We also have {@code drop(l, length l) = []}.
   */
  LIST_DROP("List", "drop", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), INT), h.list(0)))),

  /** Function "List.rev", of type "&alpha; list &rarr; &alpha; list".
   *
   * <p>"rev l" returns a list consisting of l's elements in reverse order.
   */
  LIST_REV("List", "rev", "rev", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.list(0)))),

  /** Function "List.concat", of type "&alpha; list list &rarr; &alpha; list".
   *
   * <p>"concat l" returns the list that is the concatenation of all the lists
   * in l in order.
   * {@code concat[l1,l2,...ln] = l1 @ l2 @ ... @ ln}
   */
  LIST_CONCAT("List", "concat", ts ->
      ts.forallType(1, h -> ts.fnType(ts.listType(h.list(0)), h.list(0)))),

  /** Function "List.revAppend", of type "&alpha; list * &alpha; list &rarr;
   * &alpha; list".
   *
   * <p>"revAppend (l1, l2)" returns (rev l1) @ l2.
   */
  LIST_REV_APPEND("List", "revAppend", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)))),

  /** Function "List.app", of type "(&alpha; &rarr; unit) &rarr; &alpha; list
   * &rarr; unit".
   *
   * <p>"app f l" applies f to the elements of l, from left to right.
   */
  LIST_APP("List", "app", "app", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), UNIT), h.list(0), UNIT))),

  /** Function "List.map", of type
   * "(&alpha; &rarr; &beta;) &rarr; &alpha; list &rarr; &beta; list".
   *
   * <p>"map f l" applies f to each element of l from left to right, returning
   * the list of results.
   */
  LIST_MAP("List", "map", "map", ts ->
      ts.forallType(2, t ->
          ts.fnType(ts.fnType(t.get(0), t.get(1)),
              ts.listType(t.get(0)), ts.listType(t.get(1))))),

  /** Function "List.mapPartial", of type
   * "(&alpha; &rarr; &beta; option) &rarr; &alpha; list &rarr; &beta; list".
   *
   * <p>"mapPartial f l" applies f to each element of l from left to right,
   * returning a list of results, with SOME stripped, where f was defined. f is
   * not defined for an element of l if f applied to the element returns NONE.
   * The above expression is equivalent to:
   * {@code ((map valOf) o (filter isSome) o (map f)) l}
   */
  LIST_MAP_PARTIAL("List", "mapPartial", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(h.get(0), h.option(1)), h.list(0), h.list(1)))),

  /** Function "List.find", of type "(&alpha; &rarr; bool) &rarr; &alpha; list
   * &rarr; &alpha; option".
   *
   * <p>"find f l" applies f to each element x of the list l, from left to
   * right, until {@code f x} evaluates to true. It returns SOME(x) if such an x
   * exists; otherwise it returns NONE.
   */
  LIST_FIND("List", "find", ts ->
      ts.forallType(1, h ->
          ts.fnType(h.predicate(0), h.list(0), h.option(0)))),

  /** Function "List.filter", of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list".
   *
   * <p>"filter f l" applies f to each element x of l, from left to right, and
   * returns the list of those x for which {@code f x} evaluated to true, in the
   * same order as they occurred in the argument list.
   */
  LIST_FILTER("List", "filter", ts ->
      ts.forallType(1, h -> ts.fnType(h.predicate(0), h.list(0), h.list(0)))),

  /** Function "List.partition", of type "(&alpha; &rarr; bool) &rarr;
   * &alpha; list &rarr; &alpha; list * &alpha; list".
   *
   * <p>"partition f l" applies f to each element x of l, from left to right,
   * and returns a pair (pos, neg) where pos is the list of those x for which
   * {@code f x} evaluated to true, and neg is the list of those for which
   * {@code f x} evaluated to false. The elements of pos and neg retain the same
   * relative order they possessed in l.
   */
  LIST_PARTITION("List", "partition", ts ->
      ts.forallType(1, h ->
          ts.fnType(h.predicate(0), h.list(0),
              ts.tupleType(h.list(0), h.list(0))))),

  /** Function "List.foldl", of type "(&alpha; * &beta; &rarr; &beta;) &rarr;
   *  &beta; &rarr; &alpha; list &rarr; &beta;".
   *
   * <p>"foldl f init [x1, x2, ..., xn]" returns
   * {@code f(xn,...,f(x2, f(x1, init))...)}
   * or {@code init} if the list is empty.
   */
  LIST_FOLDL("List", "foldl", "foldl", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(h.get(0), h.get(1)), h.get(1)),
              h.get(1), h.list(0), h.get(1)))),

  /** Function "List.foldr", of type "(&alpha; * &beta; &rarr; &beta;) &rarr;
   *  &beta; &rarr; &alpha; list &rarr; &beta;".
   *
   * <p>"foldr f init [x1, x2, ..., xn]" returns
   * {@code f(x1, f(x2, ..., f(xn, init)...))}
   * or {@code init} if the list is empty.
   */
  LIST_FOLDR("List", "foldr", "foldr", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(h.get(0), h.get(1)), h.get(1)),
              h.get(1), h.list(0), h.get(1)))),

  /** Function "List.exists", of type "(&alpha; &rarr; bool) &rarr; &alpha; list
   * &rarr; bool".
   *
   * <p>"exists f l" applies f to each element x of the list l, from left to
   * right, until {@code f x} evaluates to true; it returns true if such an x
   * exists and false otherwise.
   */
  LIST_EXISTS("List", "exists", ts ->
      ts.forallType(1, h -> ts.fnType(h.predicate(0), h.list(0), BOOL))),

  /** Function "List.all", of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool".
   *
   * <p>"all f l" applies f to each element x of the list l, from left to right,
   * until {@code f x} evaluates to false; it returns false if such an x exists
   * and true otherwise. It is equivalent to not(exists (not o f) l)).
   */
  LIST_ALL("List", "all", ts ->
      ts.forallType(1, h -> ts.fnType(h.predicate(0), h.list(0), BOOL))),

  /** Function "List.tabulate", of type
   * "int * (int &rarr; &alpha;) &rarr; &alpha; list".
   *
   * <p>"tabulate (n, f)" returns a list of length n equal to
   * {@code [f(0), f(1), ..., f(n-1)]}, created from left to right. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * if n &lt; 0.
   */
  LIST_TABULATE("List", "tabulate", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(INT, ts.fnType(INT, h.get(0))), h.list(0)))),

  /** Function "List.collate", of type "(&alpha; * &alpha; &rarr; order)
   * &rarr; &alpha; list * &alpha; list &rarr; order".
   *
   * <p>"collate f (l1, l2)" performs lexicographic comparison of the two lists
   * using the given ordering f on the list elements.
   */
  LIST_COLLATE("List", "collate", ts ->
    ts.forallType(1, h ->
        ts.fnType(
            ts.fnType(ts.tupleType(h.get(0), h.get(0)), ts.lookup("order")),
            ts.tupleType(h.list(0), h.list(0)),
            ts.lookup("order")))),

  /** Function "Math.acos", of type "real &rarr; real".
   *
   * <p>"acos x" returns the arc cosine of x. acos is the inverse of
   * cos. Its result is guaranteed to be in the closed interval [0,pi]. If
   * the magnitude of x exceeds 1.0, returns NaN. */
  MATH_ACOS("Math", "acos", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.asin", of type "real &rarr; real".
   *
   * <p>"asin x" returns the arc sine of x. asin is the inverse of sin. Its
   * result is guaranteed to be in the closed interval [-pi / 2, pi /
   * 2]. If the magnitude of x exceeds 1.0, returns NaN. */
  MATH_ASIN("Math", "asin", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.atan", of type "real &rarr; real".
   *
   * <p>"atan x" returns the arc tangent of x. atan is the inverse of
   * tan. For finite arguments, the result is guaranteed to be in the
   * open interval (-pi / 2, pi / 2). If x is +infinity, it returns pi /
   * 2; if x is -infinity, it returns -pi / 2. */
  MATH_ATAN("Math", "atan", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.atan2", of type "real *real &rarr; real".
   *
   * <p>"atan2 (x, y)" returns the arc tangent of (y / x) in the closed
   * interval [-pi, pi], corresponding to angles within +-180
   * degrees. The quadrant of the resulting angle is determined using
   * the signs of both x and y, and is the same as the quadrant of the
   * point (x, y). When x = 0, this corresponds to an angle of 90
   * degrees, and the result is (real (sign y)) * pi / 2.0. It holds
   * that
   *
   * <pre> sign (cos (atan2 (y, x))) = sign (x)</pre>
   *
   * <p>and
   *
   * <pre>sign (sin (atan2 (y, x))) = sign (y)</pre>
   *
   * <p>except for inaccuracies incurred by the finite precision of real
   * and the approximation algorithms used to compute the mathematical
   * functions.  Rules for exceptional cases are specified in the
   * following table.
   *
   * <pre>{@code
   *    y                 x         atan2(y, x)
   *    ================= ========= ==========
   *    +-0               0 < x     +-0
   *    +-0               +0        +-0
   *    +-0               x < 0     +-pi
   *    +-0               -0        +-pi
   *    y, 0 < y          +-0       pi/2
   *    y, y < 0          +-0       -pi/2
   *    +-y, finite y > 0 +infinity +-0
   *    +-y, finite y > 0 -infinity +-pi
   *    +-infinity        finite x  +-pi/2
   *    +-infinity        +infinity +-pi/4
   *    +-infinity        -infinity +-3pi/4
   * }</pre>
   */
  MATH_ATAN2("Math", "atan2", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Function "Math.cos", of type "real &rarr; real".
   *
   * <p>"cos x" returns the cosine of x, measured in radians. If x is an
   * infinity, returns NaN. */
  MATH_COS("Math", "cos", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.cosh", of type "real &rarr; real".
   *
   * <p>"cosh x" returns the hyperbolic cosine of x, that is, (e(x) +
   * e(-x)) / 2. It has the properties cosh +-0 = 1, cosh +-infinity =
   * +-infinity. */
  MATH_COSH("Math", "cosh", ts -> ts.fnType(REAL, REAL)),

  /** Constant "Math.e", of type "real", is the base e (2.718281828...) of the
   * natural logarithm. */
  MATH_E("Math", "e", ts -> REAL),

  /** Function "Math.exp", of type "real &rarr; real".
   *
   * <p>"exp x" returns e(x), i.e., e raised to the x(th) power. If x is
   * +infinity, it returns +infinity; if x is -infinity, it returns 0. */
  MATH_EXP("Math", "exp", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.ln", of type "real &rarr; real".
   *
   * <p>"ln x" returns the natural logarithm (base e) of x. If {@code x < 0},
   * returns NaN; if {@code x = 0}, returns -infinity; if {@code x} is infinity,
   * returns infinity. */
  MATH_LN("Math", "ln", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.log10", of type "real &rarr; real".
   *
   * <p>"log10 x" returns the decimal logarithm (base 10) of x.
   * If {@code x < 0}, returns NaN; if {@code x = 0}, returns -infinity;
   * if {@code x} is infinity, returns infinity. */
  MATH_LOG10("Math", "log10", ts -> ts.fnType(REAL, REAL)),

  /** Constant "Math.pi", of type "real" is the constant pi (3.141592653...). */
  MATH_PI("Math", "pi", ts -> REAL),

  /** Function "Math.pow", of type "real * real &rarr; real".
   *
   * <p>"pow x" returns x(y), i.e., x raised to the y(th) power. For
   * finite {@code x} and {@code y}, this is well-defined when {@code x > 0},
   * or when {@code x < 0} and {@code y} is integral. Rules for exceptional
   * cases are specified below.
   *
   * <pre>{@code
   *    x                 y                             pow(x,y)
   *    ================= ============================= ==========
   *    x, including NaN  0                             1
   *    |x| > 1           +infinity                     +infinity
   *    |x| < 1           +infinity                     +0
   *    |x| > 1           -infinity                     +0
   *    |x| < 1           -infinity                     +infinity
   *    +infinity         y > 0                         +infinity
   *    +infinity         y < 0                         +0
   *    -infinity         y > 0, odd integer            -infinity
   *    -infinity         y > 0, not odd integer        +infinity
   *    -infinity         y < 0, odd integer            -0
   *    -infinity         y < 0, not odd integer        +0
   *    x                 NaN                           NaN
   *    NaN               y <> 0                        NaN
   *    +-1               +-infinity                    NaN
   *    finite x < 0      finite non-integer y          NaN
   *    +-0               y < 0, odd integer            +-infinity
   *    +-0               finite y < 0, not odd integer +infinity
   *    +-0               y > 0, odd integer            +-0
   *    +-0               y > 0, not odd integer        +0
   * }</pre>
   */
  MATH_POW("Math", "pow", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Function "Math.sin", of type "real &rarr; real".
   *
   * <p>"sin x" returns the sine of x, measured in radians. If x is an infinity,
   * returns NaN. */
  MATH_SIN("Math", "sin", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.sinh", of type "real &rarr; real".
   *
   * <p>"sinh x" returns the hyperbolic sine of x, that is, (e(x) - e(-x))
   * / 2. It has the property sinh +-0 = +-0, sinh +-infinity =
   * +-infinity. */
  MATH_SINH("Math", "sinh", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.sqrt", of type "real &rarr; real".
   *
   * <p>"sqrt x" returns the square root of {@code x}. sqrt (~0.0) = ~0.0.
   * If {@code x < 0}, it returns NaN. */
  MATH_SQRT("Math", "sqrt", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.tan", of type "real &rarr; real".
   *
   * <p>"tan x" returns the tangent of x, measured in radians. If x is an
   * infinity, these returns NaN. Produces infinities at various finite
   * values, roughly corresponding to the singularities of the tangent
   * function. */
  MATH_TAN("Math", "tan", ts -> ts.fnType(REAL, REAL)),

  /** Function "Math.tanh", of type "real &rarr; real".
   *
   * <p>"tanh x" returns the hyperbolic tangent of x, that is, (sinh x) /
   * (cosh x). It has the properties tanh +-0 = +-0, tanh +-infinity =
   * +-1. */
  MATH_TANH("Math", "tanh", ts -> ts.fnType(REAL, REAL)),

  /** Function "Option.getOpt", of type
   * "&alpha; option * &alpha; &rarr; &alpha;".
   *
   * <p>{@code getOpt(opt, a)} returns v if opt is SOME(v); otherwise it
   * returns a. */
  OPTION_GET_OPT("Option", "getOpt", "getOpt", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.option(0), h.get(0)),
              h.get(0)))),

  /** Function "Option.isSome", of type
   * "&alpha; option &rarr; bool".
   *
   * <p>{@code isSome opt} returns true if opt is SOME(v); otherwise it returns
   * false. */
  OPTION_IS_SOME("Option", "isSome", "isSome", ts ->
      ts.forallType(1, h -> ts.fnType(h.option(0), BOOL))),

  /** Function "Option.valOf", of type
   * "&alpha; option &rarr; &alpha;".
   *
   * <p>{@code valOf opt} returns v if opt is SOME(v); otherwise it raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#OPTION Option}. */
  OPTION_VAL_OF("Option", "valOf", "valOf", ts ->
      ts.forallType(1, h -> ts.fnType(h.option(0), h.get(0)))),

  /** Function "Option.filter", of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; &rarr; &alpha; option".
   *
   * <p>{@code filter f a} returns SOME(a) if f(a) is true and NONE
   * otherwise. */
  OPTION_FILTER("Option", "filter", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), BOOL), h.get(0), h.option(0)))),

  /** Function "Option.flatten", of type
   * "&alpha; option option &rarr; &alpha; option".
   *
   * <p>{@code flatten opt} maps NONE to NONE and SOME(v) to v.
   *
   * <p>Note: In the Standard ML basis library, this function is called
   * "{@code join}". We cannot use that name, because {@code join} is a keyword
   * in Morel. */
  OPTION_FLATTEN("Option", "flatten", ts ->
      ts.forallType(1, h -> ts.fnType(ts.option(h.option(0)), h.option(0)))),

  /** Function "Option.app", of type
   * "(&alpha; &rarr; unit) &rarr; &alpha; option &rarr; unit".
   *
   * <p>{@code app f opt} applies the function f to the value v if opt is
   * SOME(v), and otherwise does nothing. */
  OPTION_APP("Option", "app", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.option(0), UNIT), h.option(0), UNIT))),

  /** Function "Option.map", of type
   * "(&alpha; &rarr; &beta;) &rarr; &alpha; option &rarr; &beta; option".
   *
   * <p>{@code map f opt} maps NONE to NONE and SOME(v) to SOME(f v). */
  OPTION_MAP("Option", "map", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(h.get(0), h.get(1)),
              h.option(0), h.option(1)))),

  /** Function "Option.mapPartial", of type
   * "(&alpha; &rarr; &beta; option) &rarr; &alpha; option &rarr; &beta;
   * option".
   *
   * <p>{@code mapPartial f opt} maps NONE to NONE and SOME(v) to f (v). */
  OPTION_MAP_PARTIAL("Option", "mapPartial", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(h.get(0), h.option(1)),
              h.option(0), h.option(1)))),

  /** Function "Option.compose", of type
   * "(&alpha; &rarr; &beta;) * (&gamma; &rarr; &alpha; option)
   * &rarr; &gamma; &rarr; &beta; option".
   *
   * <p>{@code compose (f, g) a} returns NONE if g(a) is NONE; otherwise, if
   * g(a) is SOME(v), it returns SOME(f v).
   *
   * <p>Thus, the compose function composes {@code f} with the partial
   * function {@code g} to produce another partial function. The expression
   * compose {@code (f, g)} is equivalent to {@code (map f) o g}. */
  OPTION_COMPOSE("Option", "compose", ts ->
      ts.forallType(3, h ->
          ts.fnType(
              ts.tupleType(ts.fnType(h.get(0), h.get(1)),
                  ts.fnType(h.get(2), h.option(0))),
              h.get(2), h.option(1)))),

  /** Function "Option.composePartial", of type
   * "(&alpha; &rarr; &beta; option) * (&gamma; &rarr; &alpha; option)
   * &rarr; &gamma; &rarr; &beta; option".
   *
   * <p>{@code composePartial (f, g) a} returns NONE if g(a) is NONE; otherwise,
   * if g(a) is SOME(v), it returns f(v).
   *
   * <p>Thus, the {@code composePartial} function composes the two partial
   * functions {@code f} and {@code g} to produce another partial function.
   * The expression {@code composePartial (f, g)} is equivalent to
   * {@code (mapPartial f) o g}. */
  OPTION_COMPOSE_PARTIAL("Option", "composePartial", ts ->
      ts.forallType(3, h ->
          ts.fnType(
              ts.tupleType(ts.fnType(h.get(0), h.option(1)),
                  ts.fnType(h.get(2), h.option(0))),
              h.get(2), h.option(1)))),

  /** Function "Real.abs", of type "real &rarr; real".
   *
   * <p>Returns the absolute value of {@code r}. */
  REAL_ABS("Real", "abs", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.ceil", of type "real &rarr; int".
   *
   * <p>Returns largest int not larger than {@code r}. */
  REAL_CEIL("Real", "ceil", "ceil", ts -> ts.fnType(REAL, INT)),

  /** Function "Real.checkFloat", of type "real &rarr; real".
   *
   * <p>"checkFloat x" raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#OVERFLOW Overflow}
   * if {@code x} is an infinity, and raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#DIV Div}
   * if {@code x} is NaN. Otherwise, it returns its argument. */
  REAL_CHECK_FLOAT("Real", "checkFloat", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.compare", of type "real * real &rarr; real".
   *
   * <p>Returns {@code x} with the sign of {@code y}, even if y is NaN. */
  REAL_COMPARE("Real", "compare", ts ->
      ts.fnType(ts.tupleType(REAL, REAL), ts.lookup("order"))),

  /** Function "Real.copySign", of type "real * real &rarr; real".
   *
   * <p>Returns {@code x} with the sign of {@code y}, even if y is NaN. */
  REAL_COPY_SIGN("Real", "copySign", ts ->
      ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Function "Real.floor", of type "real &rarr; int".
   *
   * <p>Returns smallest int not less than {@code r}. */
  REAL_FLOOR("Real", "floor", "floor", ts -> ts.fnType(REAL, INT)),

  /** Function "Real.fromInt", of type "int &rarr; real". Converts the integer
   * {@code i} to a {@code real} value. If the absolute value of {@code i} is
   * larger than {@code maxFinite}, then the appropriate infinity is returned.
   * If {@code i} cannot be exactly represented as a {@code real} value, then
   * the current rounding mode is used to determine the resulting value. The
   * top-level function {@code real} is an alias for {@code Real.fromInt}. */
  REAL_FROM_INT("Real", "fromInt", "real", ts -> ts.fnType(INT, REAL)),

  /** Function "Real.fromManExp r", of type "{exp:int, man:real} &rarr; real"
   * returns <code>{man, exp}</code>, where {@code man} and {@code exp} are the
   * mantissa and exponent of r, respectively. */
  REAL_FROM_MAN_EXP("Real", "fromManExp", ts ->
      ts.fnType(
          ts.recordType(
              ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
                  .put("exp", INT)
                  .put("man", REAL)
                  .build()), REAL)),

  /** Function "Real.fromString s", of type "string &rarr; real option",
   * scans a {@code real} value from a {@code string}. Returns {@code SOME(r)}
   * if a {@code real} value can be scanned from a prefix of {@code s}, ignoring
   * any initial whitespace; otherwise, it returns {@code NONE}. This function
   * is equivalent to {@code StringCvt.scanString scan}. */
  REAL_FROM_STRING("Real", "fromString", ts ->
      ts.fnType(STRING, ts.option(REAL))),

  /** Function "Real.isFinite", of type "real &rarr; bool".
   *
   * <p>"isFinite x" returns true if {@code x} is neither NaN nor an
   * infinity. */
  REAL_IS_FINITE("Real", "isFinite", ts -> ts.fnType(REAL, BOOL)),

  /** Function "Real.isNan", of type "real &rarr; bool".
   *
   * <p>"isNan x" returns true if {@code x} is NaN. */
  REAL_IS_NAN("Real", "isNan", ts -> ts.fnType(REAL, BOOL)),

  /** Function "Real.isNormal", of type "real &rarr; bool".
   *
   * <p>"isNormal x" returns true if {@code x} is normal, i.e., neither zero,
   * subnormal, infinite nor NaN. */
  REAL_IS_NORMAL("Real", "isNormal", ts -> ts.fnType(REAL, BOOL)),

  /** Constant "Real.negInf", of type "real".
   *
   * <p>The negative infinity value. */
  REAL_NEG_INF("Real", "negInf", ts -> REAL),

  /** Constant "Real.posInf", of type "real".
   *
   * <p>The positive infinity value. */
  REAL_POS_INF("Real", "posInf", ts -> REAL),

  /** Constant "Real.radix", of type "int".
   *
   * <p>The base of the representation, e.g., 2 or 10 for IEEE floating
   * point. */
  REAL_RADIX("Real", "radix", ts -> INT),

  /** Constant "Real.precision", of type "int".
   *
   * <p>The number of digits, each between 0 and {@code radix - 1}, in the
   * mantissa. Note that the precision includes the implicit (or hidden) bit
   * used in the IEEE representation (e.g., the value of Real64.precision is
   * 53). */
  REAL_PRECISION("Real", "precision", ts -> INT),

  /** Function "Real.max", of type "real * real &rarr; real".
   *
   * <p>Returns the returns the larger of the arguments. If exactly one argument
   * is NaN, returns the other argument. If both arguments are NaN, returns
   * NaN. */
  REAL_MAX("Real", "max", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Constant "Real.maxFinite", of type "real".
   *
   * <p>The maximum finite number. */
  REAL_MAX_FINITE("Real", "maxFinite", ts -> REAL),

  /** Function "Real.min", of type "real * real &rarr; real".
   *
   * <p>Returns the returns the larger of the arguments. If exactly one argument
   * is NaN, returns the other argument. If both arguments are NaN, returns
   * NaN. */
  REAL_MIN("Real", "min", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Constant "Real.minPos", of type "real".
   *
   * <p>The minimum non-zero positive number. */
  REAL_MIN_POS("Real", "minPos", ts -> REAL),

  /** Constant "Real.minNormalPos", of type "real".
   *
   * <p>The minimum non-zero normalized number. */
  REAL_MIN_NORMAL_POS("Real", "minNormalPos", ts -> REAL),

  /** Function "Real.realMod", of type "real * real &rarr; real".
   *
   * <p>Returns the fractional part of r. "realMod" is equivalent to
   * "#frac o split". */
  REAL_REAL_MOD("Real", "realMod", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.realCeil", of type "real &rarr; real".
   *
   * <p>Returns the smallest integer-valued real not less than {@code r}. */
  REAL_REAL_CEIL("Real", "realCeil", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.realFloor", of type "real &rarr; real".
   *
   * <p>Returns the largest integer-valued real not larger than {@code r}. */
  REAL_REAL_FLOOR("Real", "realFloor", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.realRound", of type "real &rarr; real".
   *
   * <p>Returns the integer-valued real nearest to {@code r}. In the case of a
   * tie, it rounds to the nearest even integer. */
  REAL_REAL_ROUND("Real", "realRound", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.realTrunc", of type "real &rarr; real".
   *
   * <p>Returns the {@code r} rounded towards zero. */
  REAL_REAL_TRUNC("Real", "realTrunc", ts -> ts.fnType(REAL, REAL)),

  /** Function "Real.rem", of type "real * real &rarr; real".
   *
   * <p>Returns the remainder {@code x - n * y}, where
   * {@code n = trunc (x / y)}. The result has the same sign as {@code x} and
   * has absolute value less than the absolute value of {@code y}. If {@code x}
   * is an infinity or {@code y} is 0, returns NaN. If {@code y} is an infinity,
   * returns {@code x}. */
  REAL_REM("Real", "rem", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Function "Real.round", of type "real &rarr; int".
   *
   * <p>Returns the integer nearest to {@code r}. In the case of a tie, it
   * rounds to the nearest even integer. */
  REAL_ROUND("Real", "round", "round", ts -> ts.fnType(REAL, INT)),

  /** Function "Real.sameSign", of type "real * real &rarr; bool".
   *
   * <p>Returns true if and only if {@code signBit r1} equals
   * {@code signBit r2}. */
  REAL_SAME_SIGN("Real", "sameSign", ts ->
      ts.fnType(ts.tupleType(REAL, REAL), BOOL)),

  /** Function "Real.sign", of type "real &rarr; int".
   *
   * <p>Returns ~1 if r is negative, 0 if r is zero, or 1 if r is positive.
   * An infinity returns its sign; a zero returns 0 regardless of its sign.
   * It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#DOMAIN Domain}
   * on NaN. */
  REAL_SIGN("Real", "sign", ts -> ts.fnType(REAL, INT)),

  /** Function "Real.signBit", of type "real &rarr; bool".
   *
   * <p>Returns true if and only if the sign of {@code r} (infinities, zeros,
   * and NaN, included) is negative. */
  REAL_SIGN_BIT("Real", "signBit", ts -> ts.fnType(REAL, BOOL)),

  /** Function "Real.split", of type "real &rarr; {frac:real, whole:real}".
   *
   * <p>Returns <code>{frac, whole}</code>, where {@code frac} and {@code whole}
   * are the fractional and integral parts of {@code r}, respectively.
   * Specifically, {@code whole} is integral, {@code |frac| < 1.0},
   * {@code whole} and {@code frac} have the same sign as {@code r}, and
   * {@code r = whole + frac}.
   *
   * <p>This function is comparable to {@code modf} in the C library. If
   * {@code r} is +-infinity, {@code whole} is +-infinity and {@code frac} is
   * +-0. If {@code r} is NaN, both {@code whole} and {@code frac} are NaN. */
  REAL_SPLIT("Real", "split", ts ->
      ts.fnType(REAL,
          ts.recordType(
              ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
                  .put("frac", REAL)
                  .put("whole", REAL)
                  .build()))),

  /** Function "Real.fromManExp r", of type "{exp:int, man:real} &rarr; real"
   * returns <code>{man, exp}</code>, where {@code man} and {@code exp} are the
   * mantissa and exponent of r, respectively. */
  REAL_TO_MAN_EXP("Real", "toManExp", ts ->
      ts.fnType(REAL,
          ts.recordType(
              ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
                  .put("exp", INT)
                  .put("man", REAL)
                  .build()))),

  /** Function "Real.toString", of type "real &rarr; string".
   *
   * <p>"toString r" converts reals into strings. The value returned by
   * {@code toString t} is equivalent to:
   *
   * <pre>{@code
   * (fmt (StringCvt.GEN NONE) r)
   * }</pre>
   */
  REAL_TO_STRING("Real", "toString", ts -> ts.fnType(REAL, STRING)),

  /** Function "Real.trunc", of type "real &rarr; int".
   *
   * <p>Returns {@code r} rounded towards zero. */
  REAL_TRUNC("Real", "trunc", "trunc", ts -> ts.fnType(REAL, INT)),

  /** Function "Real.unordered", of type "real * real &rarr; bool".
   *
   * <p>"unordered (x, y) returns true if {@code x} and {@code y} are unordered,
   * i.e., at least one of {@code x} and {@code y} is NaN. */
  REAL_UNORDERED("Real", "unordered", ts ->
      ts.fnType(ts.tupleType(REAL, REAL), BOOL)),

  /** Function "Relational.count", aka "count", of type "int list &rarr; int".
   *
   * <p>Often used with {@code group}:
   *
   * <blockquote>
   *   <pre>
   *     from e in emps
   *     group deptno = (#deptno e)
   *       compute sumId = sum of (#id e)
   *   </pre>
   * </blockquote>
   */
  RELATIONAL_COUNT("Relational", "count", "count", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), INT))),

  /** Function "Relational.exists", aka "exists", of type "&alpha; list
   * &rarr; bool".
   *
   * <p>For example,
   *
   * <pre>{@code
   * from d in depts
   * where exists (
   *   from e in emps
   *   where e.deptno = d.deptno
   *   andalso e.job = 'CLERK')
   * }</pre>
   */
  RELATIONAL_EXISTS("Relational", "exists", "exists", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), BOOL))),

  /** Function "Relational.notExists", aka "notExists", of type "&alpha; list
   * &rarr; bool".
   *
   * <p>For example,
   *
   * <pre>{@code
   * from d in depts
   * where notExists (
   *   from e in emps
   *   where e.deptno = d.deptno
   *   andalso e.job = 'CLERK')
   * }</pre>
   *
   * <p>{@code notExists list} is equivalent to {@code not (exists list)},
   * but the former may be more convenient, because it requires fewer
   * parentheses.
   */
  RELATIONAL_NOT_EXISTS("Relational", "notExists", "notExists", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), BOOL))),

  /** Function "Relational.only", aka "only", of type "&alpha; list
   * &rarr; &alpha;".
   *
   * <p>"only list" returns the only element of {@code list}. It raises
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#EMPTY Empty}
   * if {@code list} is nil,
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * if {@code list} has more than one element.
   *
   * <p>"only" allows you to write the equivalent of a scalar sub-query:
   *
   * <pre>{@code
   * from e in emps
   * yield {e.ename, dname = only (from d in depts
   *                               where d.deptno = e.deptno
   *                               yield d.dname)}
   * }</pre>
   */
  RELATIONAL_ONLY("Relational", "only", "only", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.get(0)))),

  /** Function "Relational.iterate", aka "iterate", of type "&alpha; list
   * &rarr; (&alpha; list &rarr; &alpha; list &rarr; &alpha; list)
   * &rarr; &alpha; list".
   *
   * <p>"iterate initialList listUpdate" computes a fixed point, starting
   * with a list and iterating by passing it to a function. */
  RELATIONAL_ITERATE("Relational", "iterate", "iterate", ts ->
      ts.forallType(1, h ->
          ts.fnType(h.list(0),
              ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)),
              h.list(0)))),

  /** Function "Relational.sum", aka "sum", of type
   *  "&alpha; list &rarr; &alpha;" (where &alpha; must be numeric).
   *
   * <p>Often used with {@code group}:
   *
   * <blockquote>
   *   <pre>
   *     from e in emps
   *     group deptno = (#deptno e)
   *       compute sumId = sum of (#id e)
   *   </pre>
   * </blockquote>
   */
  RELATIONAL_SUM("Relational", "sum", "sum", ts ->
      ts.forallType(1, h -> ts.fnType(ts.listType(h.get(0)), h.get(0)))),

  /** Function "Relational.max", aka "max", of type
   *  "&alpha; list &rarr; &alpha;" (where &alpha; must be comparable). */
  RELATIONAL_MAX("Relational", "max", "max", ts ->
      ts.forallType(1, h -> ts.fnType(ts.listType(h.get(0)), h.get(0)))),

  /** Function "Relational.min", aka "min", of type
   *  "&alpha; list &rarr; &alpha;" (where &alpha; must be comparable). */
  RELATIONAL_MIN("Relational", "min", "min", ts ->
      ts.forallType(1, h -> ts.fnType(ts.listType(h.get(0)), h.get(0)))),

  /** Function "Sys.env", aka "env", of type "unit &rarr; string list". */
  SYS_ENV("Sys", "env", "env", ts ->
      ts.fnType(UNIT, ts.listType(ts.tupleType(STRING, STRING)))),

  /** Function "Sys.plan", aka "plan", of type "unit &rarr; string". */
  SYS_PLAN("Sys", "plan", "plan", ts -> ts.fnType(UNIT, STRING)),

  /** Function "Sys.set", aka "set", of type "string * &alpha; &rarr; unit". */
  SYS_SET("Sys", "set", "set", ts ->
      ts.forallType(1, h -> ts.fnType(ts.tupleType(STRING, h.get(0)), UNIT))),

  /** Function "Sys.show", aka "set", of type "string &rarr; string option". */
  SYS_SHOW("Sys", "show", "show", ts -> ts.fnType(STRING, ts.option(STRING))),

  /** Function "Sys.unset", aka "unset", of type "string &rarr; unit". */
  SYS_UNSET("Sys", "unset", "unset", ts -> ts.fnType(STRING, UNIT)),

  /** Constant "Vector.maxLen" of type "int".
   *
   * <p>The maximum length of vectors supported by this implementation. Attempts
   * to create larger vectors will result in the
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * exception being raised. */
  VECTOR_MAX_LEN("Vector", "maxLen", ts -> INT),

  /** Function "Vector.fromList" of type "&alpha; list &rarr; &alpha; vector".
   *
   * <p>{@code fromList l} creates a new vector from {@code l}, whose length is
   * {@code length l} and with the {@code i}<sup>th</sup> element of {@code l}
   * used as the {@code i}<sup>th</sup> element of the vector. If the length of
   * the list is greater than {@code maxLen}, then the
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * exception is raised. */
  VECTOR_FROM_LIST("Vector", "fromList", "vector", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.vector(0)))),

  /** Function "Vector.tabulate" of type
   * "int * (int &rarr; &alpha;) &rarr; &alpha; vector".
   *
   * <p>{@code tabulate (n, f)} creates a vector of {@code n} elements, where
   * the elements are defined in order of increasing index by applying {@code f}
   * to the element's index. This is equivalent to the expression:
   *
   * <blockquote>{@code fromList (List.tabulate (n, f))}</blockquote>
   *
   * <p>If {@code n < 0} or {@code maxLen < n}, then the
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * exception is raised. */
  VECTOR_TABULATE("Vector", "tabulate", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(INT, ts.fnType(INT, h.get(0))), h.vector(0)))),

  /** Function "Vector.length" of type "&alpha; vector &rarr; int".
   *
   * <p>{@code length vec} returns {@code |vec|}, the length of the vector
   * {@code vec}. */
  VECTOR_LENGTH("Vector", "length", ts ->
      ts.forallType(1, h -> ts.fnType(h.vector(0), INT))),

  /** Function "Vector.sub" of type "&alpha; vector * int &rarr; &alpha;".
   *
   * <p>{@code sub (vec, i)} returns the {@code i}<sup>th</sup> element of the
   * vector {@code vec}. If {@code i < 0} or {@code |vec| <= i}, then the
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * exception is raised. */
  VECTOR_SUB("Vector", "sub", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.vector(0), INT), h.get(0)))),

  /** Function "Vector.update" of type
   * "&alpha; vector * int * &alpha; &rarr; &alpha; vector".
   *
   * <p>{@code update (vec, i, x)} returns a new vector, identical to
   * {@code vec}, except the {@code i}<sup>th</sup> element of {@code vec} is
   * set to {@code x}. If {@code i < 0} or {@code |vec| <= i}, then the
   * {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SUBSCRIPT Subscript}
   * exception is raised. */
  VECTOR_UPDATE("Vector", "update", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.vector(0), INT, h.get(0)), h.vector(0)))),

  /** Function "Vector.concat" of type
   * "&alpha; vector list &rarr; &alpha; vector".
   *
   * <p>{@code concat l}
   * returns the vector that is the concatenation of the vectors in the list
   * {@code l}. If the total length of these vectors exceeds {@code maxLen},
   * then the {@link net.hydromatic.morel.eval.Codes.BuiltInExn#SIZE Size}
   * exception is raised. */
  VECTOR_CONCAT("Vector", "concat", ts ->
      ts.forallType(1, h -> ts.fnType(ts.listType(h.vector(0)), h.vector(0)))),

  /** Function "Vector.appi" of type
   * "(int * &alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit".
   *
   * <p>{@code appi f vec} applies the function {@code f} to the elements of
   * a vector in left to right order (i.e., in order of increasing indices).
   * The {@code appi} function is more general than {@code app}, and supplies
   * both the element and the element's index to the function {@code f}.
   * Equivalent to:
   *
   * <blockquote>
   *   {@code List.app f (foldri (fn (i,a,l) => (i,a)::l) [] vec)}
   * </blockquote> */
  VECTOR_APPI("Vector", "appi", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(ts.tupleType(INT, h.get(0)), UNIT),
              h.vector(0), UNIT))),

  /** Function "Vector.app" of type
   * "(&alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit".
   *
   * <p>{@code app f vec} applies the function {@code f} to the elements of
   * a vector in left to right order (i.e., in order of increasing indices).
   * Equivalent to:
   *
   * <blockquote>
   *   {@code List.app f (foldr (fn (a,l) => a::l) [] vec)}
   * </blockquote> */
  VECTOR_APP("Vector", "app", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), UNIT), h.vector(0), UNIT))),

  /** Function "Vector.mapi" of type "(int * &alpha; &rarr; &beta;) &rarr;
   * &alpha; vector &rarr; &beta; vector".
   *
   * <p>{@code mapi f vec} produces a new vector by mapping the function
   * {@code f} from left to right over the argument vector. The form
   * {@code mapi} is more general, and supplies {@code f} with the vector
   * index of an element along with the element. Equivalent to:
   *
   * <blockquote>
   * {@code fromList (List.map f (foldri (fn (i,a,l) => (i,a)::l) [] vec))}
   * </blockquote> */
  VECTOR_MAPI("Vector", "mapi", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(INT, h.get(0)), h.get(1)),
              h.vector(0), h.vector(1)))),

  /** Function "Vector.map" of type "(&alpha; &rarr; &beta;) &rarr;
   * &alpha; vector &rarr; &beta; vector".
   *
   * <p>{@code map f vec} produces a new vector by mapping the function
   * {@code f} from left to right over the argument vector. Equivalent to:
   *
   * <blockquote>
   * {@code fromList (List.map f (foldr (fn (a,l) => a::l) [] vec))}
   * </blockquote> */
  VECTOR_MAP("Vector", "map", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(h.get(0), h.get(1)), h.vector(0), h.vector(1)))),

  /** Function "Vector.foldli" of type "(int * &alpha; * &beta; &rarr; &beta;)
   * &rarr; &beta; &rarr; &alpha; vector &rarr; &beta;".
   *
   * <p>{@code foldli f init vec} folds the function {@code f} over all the
   * elements of a vector, using the value {@code init} as the initial value.
   * Applies the function {@code f} from left to right (increasing indices).
   * The functions {@code foldli} and {@code foldri} are more general, and
   * supply both the element and the element's index to the function f. */
  VECTOR_FOLDLI("Vector", "foldli", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(INT, h.get(0), h.get(1)), h.get(1)),
              h.get(1), h.vector(0), h.get(1)))),

  /** Function "Vector.foldri" of type "(int * &alpha; * &beta; &rarr; &beta;)
   * &rarr; &beta; &rarr; &alpha; vector &rarr; &beta;".
   *
   * <p>{@code foldri f init vec} folds the function {@code f} over all the
   * elements of a vector, using the value {@code init} as the initial value.
   * Applies the function {@code f} from right to left (decreasing indices).
   * The functions {@code foldli} and {@code foldri} are more general, and
   * supply both the element and the element's index to the function f. */
  VECTOR_FOLDRI("Vector", "foldri", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(INT, h.get(0), h.get(1)), h.get(1)),
              h.get(1), h.vector(0), h.get(1)))),

  /** Function "Vector.foldl" of type "(&alpha; * &beta; &rarr; &beta;) &rarr;
   * &beta; &rarr; &alpha; vector &rarr; &beta;".
   *
   * <p>{@code foldl f init vec} folds the function {@code f} over all the
   * elements of a vector, using the value {@code init} as the initial value.
   * Applies the function {@code f} from left to right (increasing indices).
   * Equivalent to
   *
   * <blockquote>
   *   {@code foldli (fn (_, a, x) => f(a, x)) init vec}
   * </blockquote> */
  VECTOR_FOLDL("Vector", "foldl", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(h.get(0), h.get(1)), h.get(1)),
              h.get(1), h.vector(0), h.get(1)))),

  /** Function "Vector.foldr" of type "(&alpha; * &beta; &rarr; &beta;) &rarr;
   * &beta; &rarr; &alpha; vector &rarr; &beta;".
   *
   * <p>{@code foldr f init vec} folds the function {@code f} over all the
   * elements of a vector, using the value {@code init} as the initial value.
   * Applies the function {@code f} from right to left (decreasing indices).
   * Equivalent to
   *
   * <blockquote>
   *   {@code foldri (fn (_, a, x) => f(a, x)) init vec}
   * </blockquote> */
  VECTOR_FOLDR("Vector", "foldr", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(h.get(0), h.get(1)), h.get(1)),
              h.get(1), h.vector(0), h.get(1)))),

  /** Function "Vector.findi" of type "(int * &alpha; &rarr; bool) &rarr;
   * &alpha; vector &rarr; (int * &alpha;) option".
   *
   * <p>{@code findi f vec} applies {@code f} to each element of the vector
   * {@code vec}, from left to right (i.e., increasing indices), until a
   * {@code true} value is returned. If this occurs, the function returns the
   * element; otherwise, it return {@code NONE}. The function {@code findi} is
   * more general than {@code find}, and also supplies {@code f} with the vector
   * index of the element and, upon finding an entry satisfying the predicate,
   * returns that index with the element. */
  VECTOR_FINDI("Vector", "findi", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(ts.tupleType(INT, h.get(0)), BOOL),
              h.vector(0), ts.option(ts.tupleType(INT, h.get(0)))))),

  /** Function "Vector.find" of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; &alpha; option".
   *
   * <p>{@code find f vec} applies {@code f} to each element of the vector
   * {@code vec}, from left to right (i.e., increasing indices), until a
   * {@code true} value is returned. If this occurs, the function returns the
   * element; otherwise, it returns {@code NONE}. */
  VECTOR_FIND("Vector", "find", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), BOOL), h.vector(0), h.option(0)))),

  /** Function "Vector.exists" of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; bool".
   *
   * <p>{@code exists f vec} applies {@code f} to each element {@code x} of the
   * vector {@code vec}, from left to right (i.e., increasing indices), until
   * {@code f(x)} evaluates to {@code true}; it returns {@code true} if such
   * an {@code x} exists and {@code false} otherwise. */
  VECTOR_EXISTS("Vector", "exists", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), BOOL), h.vector(0), BOOL))),

  /** Function "Vector.all" of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; bool".
   *
   * <p>{@code all f vec} applies {@code f} to each element {@code x} of the
   * vector {@code vec}, from left to right (i.e., increasing indices), until
   * {@code f(x)} evaluates to {@code false}; it returns {@code false} if such
   * an {@code x} exists and {@code true} otherwise. It is equivalent to
   * {@code not (exists (not o f) vec))}. */
  VECTOR_ALL("Vector", "all", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), BOOL), h.vector(0), BOOL))),

  /** Function "Vector.collate" of type "(&alpha; * &alpha; &rarr; order) &rarr;
   * &alpha; vector * &alpha; vector &rarr; order".
   *
   * <p>{@code collate f (v1, v2)} performs lexicographic comparison of the two
   * vectors using the given ordering {@code f} on elements. */
  VECTOR_COLLATE("Vector", "collate", ts ->
      ts.forallType(1, h ->
          ts.fnType(
              ts.fnType(ts.tupleType(h.get(0), h.get(0)), ts.lookup("order")),
              ts.tupleType(h.vector(0), h.vector(0)),
              ts.lookup("order")))),

  /** Internal operator "andalso", of type "bool * bool &rarr; bool". */
  Z_ANDALSO("$", "andalso", ts ->
      ts.fnType(ts.tupleType(BOOL, BOOL), BOOL)),

  /** Internal operator "orelse", of type "bool * bool &rarr; bool". */
  Z_ORELSE("$", "orelse", ts ->
      ts.fnType(ts.tupleType(BOOL, BOOL), BOOL)),

  /** Internal unary negation operator "~", of type "int &rarr; int". */
  Z_NEGATE_INT("$", "~:int", ts -> ts.fnType(INT, INT)),

  /** Internal unary negation operator "~", of type "real &rarr; real". */
  Z_NEGATE_REAL("$", "~:real", ts -> ts.fnType(REAL, REAL)),

  /** Internal divide operator "/", of type "int * int &rarr; int". */
  Z_DIVIDE_INT("$", "/:int", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Internal divide operator "/", of type "real * real &rarr; real". */
  Z_DIVIDE_REAL("$", "/:real", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Internal minus operator "-", of type "int * int &rarr; int". */
  Z_MINUS_INT("$", "-:int", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Internal minus operator "-", of type "real * real &rarr; real". */
  Z_MINUS_REAL("$", "-:real", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Internal plus operator "+", of type "int * int &rarr; int". */
  Z_PLUS_INT("$", "+:int", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Internal plus operator "+", of type "real * real &rarr; real". */
  Z_PLUS_REAL("$", "+:real", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Internal times operator "*", of type "int * int &rarr; int". */
  Z_TIMES_INT("$", "*:int", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Internal times operator "*", of type "real * real &rarr; real". */
  Z_TIMES_REAL("$", "*:real", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Internal relational sum operator "sum", of type "int * int &rarr; int". */
  Z_SUM_INT("$", "sum:int", ts -> ts.fnType(ts.tupleType(INT, INT), INT)),

  /** Internal relational sum operator "sum", of type "real * real &rarr; real". */
  Z_SUM_REAL("$", "sum:real", ts -> ts.fnType(ts.tupleType(REAL, REAL), REAL)),

  /** Internal list constructor, e.g. "list (1 + 2, 3)" implements "[1 + 2, 3]".
   * It cannot be assigned a type, because the tuple is variadic. */
  Z_LIST("$", "list", ts -> UNIT);

  /** Name of the structure (e.g. "List", "String"), or null. */
  public final String structure;

  /** Unqualified name, e.g. "map" (for "List.map") or "true". */
  public final String mlName;

  /** An alias, or null. For example, "List.map" has an alias "map". */
  public final String alias;

  /** Derives a type, in a particular type system, for this constant or
   * function. */
  public final Function<TypeSystem, Type> typeFunction;

  private final PrimitiveType preferredType;

  public static final ImmutableMap<String, BuiltIn> BY_ML_NAME;

  public static final SortedMap<String, Structure> BY_STRUCTURE;

  static {
    ImmutableMap.Builder<String, BuiltIn> byMlName = ImmutableMap.builder();
    final SortedMap<String, ImmutableSortedMap.Builder<String, BuiltIn>> map =
        new TreeMap<>();
    for (BuiltIn builtIn : values()) {
      if (builtIn.alias != null) {
        byMlName.put(builtIn.alias, builtIn);
      }
      if (builtIn.structure == null) {
        byMlName.put(builtIn.mlName, builtIn);
      } else if (builtIn.structure.equals("$")) {
        // ignore internal operators such as "list"
      } else {
        map.compute(builtIn.structure, (name, mapBuilder) -> {
          if (mapBuilder == null) {
            mapBuilder = ImmutableSortedMap.naturalOrder();
          }
          return mapBuilder.put(builtIn.mlName, builtIn);
        });
      }
    }
    BY_ML_NAME = byMlName.build();
    final ImmutableSortedMap.Builder<String, Structure> b =
        ImmutableSortedMap.naturalOrder();
    map.forEach((structure, mapBuilder) ->
        b.put(structure, new Structure(structure, mapBuilder.build())));
    BY_STRUCTURE = b.build();
  }

  BuiltIn(@Nullable String structure, String mlName,
      Function<TypeSystem, Type> typeFunction) {
    this(structure, mlName, null, typeFunction, null);
  }

  BuiltIn(@Nullable String structure, String mlName,
      @Nonnull PrimitiveType preferredType,
      Function<TypeSystem, Type> typeFunction) {
    this(structure, mlName, null, typeFunction, preferredType);
  }

  BuiltIn(@Nullable String structure, String mlName,
      @Nullable String alias, Function<TypeSystem, Type> typeFunction) {
    this(structure, mlName, alias, typeFunction, null);
  }

  BuiltIn(@Nullable String structure, String mlName,
      @Nullable String alias, Function<TypeSystem, Type> typeFunction,
      @Nullable PrimitiveType preferredType) {
    this.structure = structure;
    this.mlName = Objects.requireNonNull(mlName);
    this.alias = alias;
    this.typeFunction = Objects.requireNonNull(typeFunction);
    this.preferredType = preferredType;
  }

  /** Calls a consumer once per value. */
  public static void forEach(TypeSystem typeSystem,
      BiConsumer<BuiltIn, Type> consumer) {
    if (Static.SKIP) {
      return;
    }
    for (BuiltIn builtIn : values()) {
      final Type type = builtIn.typeFunction.apply(typeSystem);
      consumer.accept(builtIn, type);
    }
  }

  /** Calls a consumer once per structure. */
  public static void forEachStructure(TypeSystem typeSystem,
      BiConsumer<Structure, Type> consumer) {
    if (Static.SKIP) {
      return;
    }
    final TreeMap<String, Type> nameTypes = new TreeMap<>(RecordType.ORDERING);
    BY_STRUCTURE.values().forEach(structure -> {
      nameTypes.clear();
      structure.memberMap.forEach((name, builtIn) ->
          nameTypes.put(name, builtIn.typeFunction.apply(typeSystem)));
      consumer.accept(structure, typeSystem.recordType(nameTypes));
    });
  }

  /** Defines built-in {@code datatype} and {@code eqtype} instances, e.g.
   *  {@code option}, {@code vector}. */
  public static void dataTypes(TypeSystem typeSystem, List<Binding> bindings) {
    defineDataType(typeSystem, bindings, "order", false, 0, h ->
        h.tyCon("LESS").tyCon("EQUAL").tyCon("GREATER"));
    defineDataType(typeSystem, bindings, "option", false, 1, h ->
        h.tyCon("NONE").tyCon("SOME", h.get(0)));
    defineEqType(typeSystem, "vector", 1);

    // Define two internal datatypes:
    //   datatype 'a list = NIL | CONS of ('a * 'a list);
    //   datatype bool = FALSE | TRUE;
    // These are not available from within programs but are used by the
    // match coverage checker.
    defineDataType(typeSystem, bindings, "$list", true, 1, h ->
        h.tyCon("NIL").tyCon("CONS", h.get(0)));
    defineDataType(typeSystem, bindings, "$bool", true, 0, h ->
        h.tyCon("FALSE").tyCon("TRUE"));
  }

  private static void defineEqType(TypeSystem ts, String name, int varCount) {
    defineDataType(ts, new ArrayList<>(), name, false, varCount, h -> h);
  }

  private static void defineDataType(TypeSystem ts, List<Binding> bindings,
      String name, boolean internal, int varCount,
      UnaryOperator<DataTypeHelper> transform) {
    final List<TypeVar> tyVars = new ArrayList<>();
    for (int i = 0; i < varCount; i++) {
      tyVars.add(ts.typeVariable(i));
    }
    final SortedMap<String, Type> tyCons = new TreeMap<>();
    transform.apply(new DataTypeHelper() {
      public DataTypeHelper tyCon(String name, Type type) {
        tyCons.put(name, type);
        return this;
      }

      public DataTypeHelper tyCon(String name) {
        return tyCon(name, DummyType.INSTANCE);
      }

      public TypeVar get(int i) {
        return tyVars.get(i);
      }
    });
    final Type type = ts.dataTypeScheme(name, tyVars, tyCons);
    final DataType dataType = (DataType) (type instanceof DataType ? type
        : ((ForallType) type).type);
    if (internal) {
      ts.setInternal(name);
    } else {
      tyCons.keySet().forEach(tyConName ->
          bindings.add(ts.bindTyCon(dataType, tyConName)));
    }
  }

  /** Callback used when defining a datatype. */
  private interface DataTypeHelper {
    DataTypeHelper tyCon(String name);
    DataTypeHelper tyCon(String name, Type type);
    TypeVar get(int i);
  }

  /** Built-in structure. */
  public static class Structure {
    public final String name;
    public final SortedMap<String, BuiltIn> memberMap;

    Structure(String name, SortedMap<String, BuiltIn> memberMap) {
      this.name = Objects.requireNonNull(name);
      this.memberMap = ImmutableSortedMap.copyOf(memberMap);
    }
  }

  public void prefer(Consumer<PrimitiveType> consumer) {
    if (preferredType != null) {
      consumer.accept(preferredType);
    }
  }
}

// End BuiltIn.java
