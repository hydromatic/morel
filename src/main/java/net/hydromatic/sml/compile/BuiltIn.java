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
package net.hydromatic.sml.compile;

import com.google.common.collect.ImmutableMap;

import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.type.TypeSystem;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.hydromatic.sml.type.PrimitiveType.BOOL;
import static net.hydromatic.sml.type.PrimitiveType.CHAR;
import static net.hydromatic.sml.type.PrimitiveType.INT;
import static net.hydromatic.sml.type.PrimitiveType.STRING;
import static net.hydromatic.sml.type.PrimitiveType.UNIT;

/** Built-in constants and functions. */
public enum BuiltIn {
  /** Literal "true", of type "bool". */
  TRUE("true", ts -> BOOL),

  /** Literal "false", of type "bool". */
  FALSE("false", ts -> BOOL),

  /** Function "not", of type "bool &rarr; bool". */
  NOT("not", ts -> ts.fnType(BOOL, BOOL)),

  /** Function "abs", of type "int &rarr; int". */
  ABS("abs", ts -> ts.fnType(INT, INT)),

  /** Function "ignore", of type "&alpha; &rarr; unit". */
  IGNORE("ignore", ts -> ts.forallType(1, h -> ts.fnType(h.get(0), UNIT))),

  /** Constant "String.maxSize", of type "int".
   *
   * <p>"The longest allowed size of a string". */
  STRING_MAX_SIZE("String.maxSize", ts -> INT),

  /** Function "String.size", of type "string &rarr; int".
   *
   * <p>"size s" returns |s|, the number of characters in string s. */
  STRING_SIZE("String.size", ts -> ts.fnType(STRING, INT)),

  /** Function "String.sub", of type "string * int &rarr; char".
   *
   * <p>"sub (s, i)" returns the i(th) character of s, counting from zero. This
   * raises {@code Subscript} if i &lt; 0 or |s| &le; i. */
  STRING_SUB("String.sub", ts -> ts.fnType(ts.tupleType(STRING, INT), CHAR)),

  /** Function "String.extract", of type "string * int * int option &rarr;
   * string".
   *
   * <p>TODO: The current implementation ignores the "int option" last argument.
   *
   * <p>"extract (s, i, NONE)" and "extract (s, i, SOME j)" return substrings of
   * s. The first returns the substring of s from the i(th) character to the end
   * of the string, i.e., the string s[i..|s|-1]. This raises {@code Subscript}
   * if i &lt; 0 * or |s| &lt; i.
   *
   * <p>The second form returns the substring of size j starting at index i,
   * i.e., the string s[i..i+j-1]. It raises {@code Subscript} if i &lt; 0 or j
   * &lt; 0 or |s| &lt; i + j. Note that, if defined, extract returns the empty
   * string when i = |s|. */
  STRING_EXTRACT("String.extract", ts ->
      ts.fnType(ts.tupleType(STRING, INT), STRING)),

  /** Function "String.substring", of type "string * int * int &rarr; string".
   *
   * <p>"substring (s, i, j)" returns the substring s[i..i+j-1], i.e., the
   * substring of size j starting at index i. This is equivalent to
   * extract(s, i, SOME j). */
  STRING_SUBSTRING("String.substring", ts ->
      ts.fnType(ts.tupleType(STRING, INT, INT), STRING)),

  /** Function "String.concat", of type "string list &rarr; string".
   *
   * <p>"concat l" is the concatenation of all the strings in l. This raises
   * {@code Size} if the sum of all the sizes is greater than maxSize.  */
  STRING_CONCAT("String.concat", ts -> ts.fnType(ts.listType(STRING), STRING)),

  /** Function "String.concatWith", of type "string &rarr; string list &rarr;
   * string".
   *
   * <p>"concatWith s l" returns the concatenation of the strings in the list l
   * using the string s as a separator. This raises {@code Size} if the size of
   * the resulting string would be greater than maxSize. */
  STRING_CONCAT_WITH("String.concatWith", ts ->
      ts.fnType(STRING, ts.listType(STRING), STRING)),

  /** Function "String.str", of type "char &rarr; string".
   *
   * <p>"str c" is the string of size one containing the character c. */
  STRING_STR("String.str", ts -> ts.fnType(CHAR, STRING)),

  /** Function "String.implode", of type "char list &rarr; string".
   *
   * <p>"implode l" generates the string containing the characters in the list
   * l. This is equivalent to concat (List.map str l). This raises {@code Size}
   * if the resulting string would have size greater than maxSize. */
  STRING_IMPLODE("String.implode", ts -> ts.fnType(ts.listType(CHAR), STRING)),

  /** Function "String.explode", of type "string &rarr; char list".
   *
   * <p>"explode s" is the list of characters in the string s. */
  STRING_EXPLODE("String.explode", ts -> ts.fnType(STRING, ts.listType(CHAR))),

  /** Function "String.map", of type "(char &rarr; char) &rarr; string
   * &rarr; string".
   *
   * <p>"map f s" applies f to each element of s from left to right, returning
   * the resulting string. It is equivalent to
   * {@code implode(List.map f (explode s))}.  */
  STRING_MAP("String.map", ts ->
      ts.fnType(ts.fnType(CHAR, CHAR), STRING, STRING)),

  /** Function "String.translate", of type "(char &rarr; string) &rarr; string
   * &rarr; string".
   *
   * <p>"translate f s" returns the string generated from s by mapping each
   * character in s by f. It is equivalent to
   * {code concat(List.map f (explode s))}. */
  STRING_TRANSLATE("String.translate", ts ->
      ts.fnType(ts.fnType(CHAR, STRING), STRING, STRING)),

  /** Function "String.isPrefix", of type "string &rarr; string &rarr; bool".
   *
   * <p>"isPrefix s1 s2" returns true if the string s1 is a prefix of the string
   * s2. Note that the empty string is a prefix of any string, and that a string
   * is a prefix of itself. */
  STRING_IS_PREFIX("String.isPrefix", ts -> ts.fnType(STRING, STRING, BOOL)),

  /** Function "String.isSubstring", of type "string &rarr; string &rarr; bool".
   *
   * <p>"isSubstring s1 s2" returns true if the string s1 is a substring of the
   * string s2. Note that the empty string is a substring of any string, and
   * that a string is a substring of itself. */
  STRING_IS_SUBSTRING("String.isSubstring", ts ->
      ts.fnType(STRING, STRING, BOOL)),

  /** Function "String.isSuffix", of type "string &rarr; string &rarr; bool".
   *
   * <p>"isSuffix s1 s2" returns true if the string s1 is a suffix of the string
   * s2. Note that the empty string is a suffix of any string, and that a string
   * is a suffix of itself. */
  STRING_IS_SUFFIX("String.isSuffix", ts -> ts.fnType(STRING, STRING, BOOL)),

  /** Constant "List.nil", of type "&alpha; list".
   *
   * <p>"nil" is the empty list.
   */
  LIST_NIL("List.nil", ts -> ts.forallType(1, h -> h.list(0))),

  /** Function "List.null", of type "&alpha; list &rarr; bool".
   *
   * <p>"null l" returns true if the list l is empty.
   */
  LIST_NULL("List.null", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), BOOL))),

  /** Function "List.length", of type "&alpha; list &rarr; int".
   *
   * <p>"length l" returns the number of elements in the list l.
   */
  LIST_LENGTH("List.length", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), INT))),

  /** Function "List.at", of type "&alpha; list * &alpha; list &rarr; &alpha;
   * list".
   *
   * <p>"l1 @ l2" returns the list that is the concatenation of l1 and l2.
   */
  // TODO: make this infix "@" rather than prefix "at"
  LIST_AT("List.at", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)))),

  /** Function "List.hd", of type "&alpha; list &rarr; &alpha;".
   *
   * <p>"hd l" returns the first element of l. It raises {@code Empty} if l is
   * nil.
   */
  LIST_HD("List.hd", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.get(0)))),

  /** Function "List.tl", of type "&alpha; list &rarr; &alpha; list".
   *
   * <p>"tl l" returns all but the first element of l. It raises {@code Empty}
   * if l is nil.
   */
  LIST_TL("List.tl", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.list(0)))),

  /** Function "List.last", of type "&alpha; list &rarr; &alpha;".
   *
   * <p>"last l" returns the last element of l. It raises {@code Empty} if l is
   * nil.
   */
  LIST_LAST("List.last", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.get(0)))),

  /** Function "List.getItem", of type "&alpha; list &rarr;
   * (&alpha; * &alpha; list) option".
   *
   * <p>"getItem l" returns {@code NONE} if the list is empty, and
   * {@code SOME(hd l,tl l)} otherwise. This function is particularly useful for
   * creating value readers from lists of characters. For example, Int.scan
   * StringCvt.DEC getItem has the type {@code (int,char list) StringCvt.reader}
   * and can be used to scan decimal integers from lists of characters.
   */
  // TODO: make it return an option
  LIST_GET_ITEM("List.getItem", ts ->
      ts.forallType(1, h ->
          ts.fnType(h.list(0), ts.tupleType(h.get(0), h.list(0))))),

  /** Function "List.nth", of type "&alpha; list * int &rarr; &alpha;".
   *
   * <p>"nth (l, i)" returns the i(th) element of the list l, counting from 0.
   * It raises {@code Subscript} if i &lt; 0 or i &ge; length l. We have
   * nth(l,0) = hd l, ignoring exceptions.
   */
  LIST_NTH("List.nth", ts ->
      ts.forallType(1, h -> ts.fnType(ts.tupleType(h.list(0), INT), h.get(0)))),

  /** Function "List.take", of type "&alpha; list * int &rarr; &alpha; list".
   *
   * <p>"take (l, i)" returns the first i elements of the list l. It raises
   * {@code Subscript} if i &lt; 0 or i &gt; length l.
   * We have take(l, length l) = l.
   */
  LIST_TAKE("List.take", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), INT), h.list(0)))),

  /** Function "List.drop", of type "&alpha; list * int &rarr; &alpha; list".
   *
   * <p>"drop (l, i)" returns what is left after dropping the first i elements
   * of the list l.
   *
   * <p>It raises {@code Subscript} if i &lt; 0 or i &gt; length l.
   *
   * <p>It holds that
   * {@code take(l, i) @ drop(l, i) = l} when 0 &le; i &le; length l.
   *
   * <p>We also have {@code drop(l, length l) = []}.
   */
  LIST_DROP("List.drop", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), INT), h.list(0)))),

  /** Function "List.rev", of type "&alpha; list &rarr; &alpha; list".
   *
   * <p>"rev l" returns a list consisting of l's elements in reverse order.
   */
  LIST_REV("List.rev", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), h.list(0)))),

  /** Function "List.concat", of type "&alpha; list list &rarr; &alpha; list".
   *
   * <p>"concat l" returns the list that is the concatenation of all the lists
   * in l in order.
   * {@code concat[l1,l2,...ln] = l1 @ l2 @ ... @ ln}
   */
  LIST_CONCAT("List.concat", ts ->
      ts.forallType(1, h -> ts.fnType(ts.listType(h.list(0)), h.list(0)))),

  /** Function "List.revAppend", of type "&alpha; list * &alpha; list &rarr;
   * &alpha; list".
   *
   * <p>"revAppend (l1, l2)" returns (rev l1) @ l2.
   */
  LIST_REV_APPEND("List.revAppend", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)))),

  /** Function "List.app", of type "(&alpha; &rarr; unit) &rarr; &alpha; list
   * &rarr; unit".
   *
   * <p>"app f l" applies f to the elements of l, from left to right.
   */
  LIST_APP("List.app", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.fnType(h.get(0), UNIT), h.list(0), UNIT))),

  /** Function "List.map", of type
   * "(&alpha; &rarr; &beta;) &rarr; &alpha; list &rarr; &beta; list".
   *
   * <p>"map f l" applies f to each element of l from left to right, returning
   * the list of results.
   */
  LIST_MAP("List.map", "map", ts ->
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
  // TODO: make this take option
  LIST_MAP_PARTIAL("List.mapPartial", ts ->
      ts.forallType(2, h ->
          ts.fnType(ts.fnType(h.get(0), h.get(1)), h.list(0), h.list(1)))),

  /** Function "List.find", of type "(&alpha; &rarr; bool) &rarr; &alpha; list
   * &rarr; &alpha; option".
   *
   * <p>"find f l" applies f to each element x of the list l, from left to
   * right, until {@code f x} evaluates to true. It returns SOME(x) if such an x
   * exists; otherwise it returns NONE.
   */
  LIST_FIND("List.find", ts ->
      ts.forallType(1, h -> ts.fnType(h.predicate(0), h.list(0), h.get(0)))),

  /** Function "List.filter", of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list".
   *
   * <p>"filter f l" applies f to each element x of l, from left to right, and
   * returns the list of those x for which {@code f x} evaluated to true, in the
   * same order as they occurred in the argument list.
   */
  LIST_FILTER("List.filter", ts ->
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
  LIST_PARTITION("List.partition", ts ->
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
  LIST_FOLDL("List.foldl", ts ->
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
  LIST_FOLDR("List.foldr", ts ->
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
  LIST_EXISTS("List.exists", ts ->
      ts.forallType(1, h -> ts.fnType(h.predicate(0), h.list(0), BOOL))),

  /** Function "List.all", of type
   * "(&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool".
   *
   * <p>"all f l" applies f to each element x of the list l, from left to right,
   * until {@code f x} evaluates to false; it returns false if such an x exists
   * and true otherwise. It is equivalent to not(exists (not o f) l)).
   */
  LIST_ALL("List.all", ts ->
      ts.forallType(1, h -> ts.fnType(h.predicate(0), h.list(0), BOOL))),

  /** Function "List.tabulate", of type
   * "int * (int &rarr; &alpha;) &rarr; &alpha; list".
   *
   * <p>"tabulate (n, f)" returns a list of length n equal to
   * {@code [f(0), f(1), ..., f(n-1)]}, created from left to right. It raises
   * {@code Size} if n &lt; 0.
   */
  LIST_TABULATE("List.tabulate", ts ->
      ts.forallType(1, h ->
          ts.fnType(ts.tupleType(INT, ts.fnType(INT, h.get(0))), h.list(0)))),

  /** Function "List.collate", of type "(&alpha; * &alpha; &rarr; order)
   * &rarr; &alpha; list * &alpha; list &rarr; order".
   *
   * <p>"collate f (l1, l2)" performs lexicographic comparison of the two lists
   * using the given ordering f on the list elements.
   */
  LIST_COLLATE("List.collate", ts -> {
    final Type order = INT; // TODO:
    return ts.forallType(1, h ->
        ts.fnType(ts.fnType(ts.tupleType(h.get(0), h.get(0)), order),
            ts.tupleType(h.list(0), h.list(0)),
            order));
  }),

  /** Function "Relational.count", aka "count", of type "int list &rarr; int".
   *
   * <p>Often used with {@code group}:
   *
   * <blockquote>
   *   <pre>
   *     from e in emps
   *     group (#deptno e) as deptno
   *       compute sum of (#id e) as sumId
   *   </pre>
   * </blockquote>
   */
  RELATIONAL_COUNT("Relational.count", "count", ts ->
      ts.forallType(1, h -> ts.fnType(h.list(0), INT))),

  /** Function "Relational.sum", aka "sum", of type "int list &rarr; int".
   *
   * <p>Often used with {@code group}:
   *
   * <blockquote>
   *   <pre>
   *     from e in emps
   *     group (#deptno e) as deptno
   *       compute sum of (#id e) as sumId
   *   </pre>
   * </blockquote>
   */
  RELATIONAL_SUM("Relational.sum", "sum", ts ->
      ts.fnType(ts.listType(INT), INT));

  /** The name as it appears in ML's symbol table. */
  public final String mlName;

  /** An alias, or null. For example, "List_map" has an alias "map". */
  public final String alias;

  /** Derives a type, in a particular type system, for this constant or
   * function. */
  public final Function<TypeSystem, Type> typeFunction;

  public static final ImmutableMap<String, BuiltIn> BY_ML_NAME;

  static {
    ImmutableMap.Builder<String, BuiltIn> byMlName = ImmutableMap.builder();
    for (BuiltIn builtIn : values()) {
      byMlName.put(builtIn.mlName, builtIn);
      if (builtIn.alias != null) {
        byMlName.put(builtIn.alias, builtIn);
      }
    }
    BY_ML_NAME = byMlName.build();
  }

  BuiltIn(String mlName, Function<TypeSystem, Type> typeFunction) {
    this(mlName, null, typeFunction);
  }

  BuiltIn(String mlName, String alias,
      Function<TypeSystem, Type> typeFunction) {
    this.mlName = mlName.replace('.', '_'); // until we can parse long-ids
    this.alias = alias;
    this.typeFunction = typeFunction;
  }

  /** Calls a consumer once per value. */
  public static void forEachType(TypeSystem typeSystem,
      BiConsumer<String, Type> consumer) {
    for (BuiltIn builtIn : values()) {
      final Type type = builtIn.typeFunction.apply(typeSystem);
      consumer.accept(builtIn.mlName, type);
      if (builtIn.alias != null) {
        consumer.accept(builtIn.alias, type);
      }
    }
  }
}

// End BuiltIn.java
