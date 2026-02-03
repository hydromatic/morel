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
package net.hydromatic.morel;

import static net.hydromatic.morel.Matchers.equalsOrdered;
import static net.hydromatic.morel.Matchers.equalsUnordered;
import static net.hydromatic.morel.Matchers.hasMoniker;
import static net.hydromatic.morel.Matchers.hasTypeConstructors;
import static net.hydromatic.morel.Matchers.instanceOfAnd;
import static net.hydromatic.morel.Matchers.isCode;
import static net.hydromatic.morel.Matchers.isLiteral;
import static net.hydromatic.morel.Matchers.isUnordered;
import static net.hydromatic.morel.Matchers.list;
import static net.hydromatic.morel.Matchers.map;
import static net.hydromatic.morel.Matchers.throwsA;
import static net.hydromatic.morel.Matchers.whenAppliedTo;
import static net.hydromatic.morel.Ml.MatchCoverage.NON_EXHAUSTIVE;
import static net.hydromatic.morel.Ml.MatchCoverage.OK;
import static net.hydromatic.morel.Ml.MatchCoverage.REDUNDANT;
import static net.hydromatic.morel.Ml.assertError;
import static net.hydromatic.morel.Ml.ml;
import static net.hydromatic.morel.Ml.mlE;
import static net.hydromatic.morel.TestUtils.first;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.TypeVar;
import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Kick the tires. */
public class MainTest {
  @Test
  void testEmptyRepl() {
    final List<String> argList = ImmutableList.of();
    final Map<String, ForeignValue> valueMap = ImmutableMap.of();
    final Map<Prop, Object> propMap = ImmutableMap.of();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(new byte[0]);
      final Main main = new Main(argList, in, ps, valueMap, propMap, false);
      main.run();
    }
    assertThat(out.size(), is(0));
  }

  @Test
  void testRepl() {
    final List<String> argList = ImmutableList.of();
    final String ml =
        "val x = 5;\n"
            + "x;\n"
            + "it + 1;\n"
            + "val f = fn x => x + 1;\n"
            + "f;\n"
            + "it x;\n";
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(ml.getBytes());
      final Map<String, ForeignValue> valueMap = ImmutableMap.of();
      final Map<Prop, Object> propMap = ImmutableMap.of();
      final Main main = new Main(argList, in, ps, valueMap, propMap, false);
      main.run();
    }
    final String expected =
        "val x = 5 : int\n"
            + "val it = 5 : int\n"
            + "val it = 6 : int\n"
            + "val f = fn : int -> int\n"
            + "val it = fn : int -> int\n"
            + "val it = 6 : int\n";
    assertThat(out, hasToString(expected));
  }

  @Test
  void testParse() {
    ml("1").assertParseLiteral(isLiteral(BigDecimal.ONE, "1"));
    ml("~3.5").assertParseLiteral(isLiteral(new BigDecimal("-3.5"), "~3.5"));
    ml("\"a string\"")
        .assertParseLiteral(isLiteral("a string", "\"a string\""));
    ml("\"\"").assertParseLiteral(isLiteral("", "\"\""));
    ml("\"a\\\\b\\\"c\"")
        .assertParseLiteral(isLiteral("a\\b\"c", "\"a\\\\b\\\"c\""));
    ml("#\"a\"").assertParseLiteral(isLiteral('a', "#\"a\""));
    ml("#\"\\\"\"").assertParseLiteral(isLiteral('"', "#\"\\\"\""));
    ml("#\"\\\\\"").assertParseLiteral(isLiteral('\\', "#\"\\\\\""));

    // true and false are variables, not actually literals
    ml("true").assertParseStmt(Ast.Id.class, "true");
    ml("false").assertParseStmt(Ast.Id.class, "false");
  }

  @Test
  void testParseDecl() {
    ml("val x = 5").assertParseDecl(Ast.ValDecl.class, "val x = 5");
    ml("val `x` = 5").assertParseDecl(Ast.ValDecl.class, "val x = 5");
    ml("val x : int = 5")
        .assertParseDecl(Ast.ValDecl.class, "val (x : int) = 5");
    ml("val (x : int) = 5")
        .assertParseDecl(Ast.ValDecl.class, "val (x : int) = 5");
    ml("val x : `order` = LESS")
        .assertParseDecl(Ast.ValDecl.class, "val (x : order) = LESS");
    ml("val x : `order` list = [LESS]")
        .assertParseDecl(Ast.ValDecl.class, "val (x : order list) = [LESS]");

    // other valid identifiers
    ml("val x' = 5").assertParseDecl(Ast.ValDecl.class, "val x' = 5");
    ml("val x'' = 5").assertParseDecl(Ast.ValDecl.class, "val x'' = 5");
    ml("val x'y = 5").assertParseDecl(Ast.ValDecl.class, "val x'y = 5");
    ml("val ABC123 = 5").assertParseDecl(Ast.ValDecl.class, "val ABC123 = 5");
    ml("val Abc_123 = 6").assertParseDecl(Ast.ValDecl.class, "val Abc_123 = 6");
    ml("val Abc_ = 7").assertParseDecl(Ast.ValDecl.class, "val Abc_ = 7");

    ml("val succ = fn x => x + 1")
        .assertParseDecl(Ast.ValDecl.class, "val succ = fn x => x + 1");

    ml("val plus = fn x => fn y => x + y")
        .assertParseDecl(Ast.ValDecl.class, "val plus = fn x => fn y => x + y");

    ml("fun plus x y = x + y")
        .assertParseDecl(Ast.FunDecl.class, "fun plus x y = x + y");
  }

  /** Tests parsing types (including declarations). */
  @Test
  void testParseType() {
    ml("over x").assertParseDecl(Ast.OverDecl.class, "over x");

    ml("datatype 'a option = NONE | SOME of 'a")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype 'a option = NONE | SOME of 'a");

    ml("datatype color = RED | GREEN | BLUE")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype color = RED | GREEN | BLUE");
    ml("datatype 'a tree = Empty | Node of 'a * 'a forest\n"
            + "and      'a forest = Nil | Cons of 'a tree * 'a forest")
        .assertParseDecl(
            Ast.DatatypeDecl.class,
            "datatype 'a tree = Empty"
                + " | Node of 'a * 'a forest "
                + "and 'a forest = Nil"
                + " | Cons of 'a tree * 'a forest");

    String ml =
        "datatype ('a, 'b) choice ="
            + " NEITHER"
            + " | LEFT of 'a"
            + " | RIGHT of 'b"
            + " | BOTH of {a: 'a, b: 'b}";
    ml(ml).assertParseSame();

    // -> is right-associative
    ml("datatype x = X of int -> int -> int").assertParseSame();
    ml("datatype x = X of (int -> int) -> int")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype x = X of (int -> int) -> int");
    ml("datatype x = X of int -> (int -> int)")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype x = X of int -> int -> int");

    ml("datatype x = X of int * int list")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype x = X of int * int list");
    ml("datatype x = X of int * (int list)")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype x = X of int * int list");
    ml("datatype x = X of (int * int) list")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype x = X of (int * int) list");
    ml("datatype x = X of (int, int) pair")
        .assertParseDecl(
            Ast.DatatypeDecl.class, "datatype x = X of (int, int) pair");

    // "*" is non-associative; parentheses cannot be removed
    ml("datatype ('a, 'b, 'c) foo = Triple of 'a * 'b * 'c").assertParseSame();
    ml("datatype ('a, 'b, 'c) foo = Triple of 'a * ('b * 'c)")
        .assertParseSame();
    ml("datatype ('a, 'b, 'c) foo = Triple of ('a * 'b) * 'c")
        .assertParseSame();

    ml("type myInt = int").assertParseSame();
    ml("type myInt = int and myRealList = real list").assertParseSame();
    ml("type emp = {empno: int, pets: string list}").assertParseSame();

    // various types as annotations
    ml("fn x : int => 0").assertParseSame();
    ml("fn x : boolean => 0").assertParseSame();
    ml("fn x : string => 0").assertParseSame();
    ml("fn x : unit => 0").assertParseSame();
    ml("fn x : int * string => 0").assertParseSame();
    ml("fn x : int * int -> string => 0")
        .assertParseEquivalent("fn x : (int * int) -> string => 0");
    ml("fn x : int * (int -> string) => 0").assertParseSame();
    ml("fn x : (int * int) -> string => 0")
        .assertParse("fn x : int * int -> string => 0");
    ml("fn x : {} => 0").assertParseSame();
    ml("fn x : int list => 0").assertParseSame();
    ml("fn x : int * string list option => 0")
        .assertParseEquivalent("fn x : int * (string list) option => 0");
    ml("fn x : (int * string) list option => 0")
        .assertParseEquivalent("fn x : ((int * string) list) option => 0");
    ml("fn x : {a: int} => 0").assertParseSame();
    ml("fn x : {a: int, b: boolean} => 0").assertParseSame();
    ml("fn x : {a: int list * unit, b: boolean} => 0").assertParseSame();
    ml("fn x : typeof 1 => 0").assertParseSame();
    ml("fn x : typeof (1 + 2) => 0").assertParseSame();

    // case has lower precedence than over typeof
    ml("fn x : typeof (case x of 0 => true | _ => false) => ()")
        .assertParseEquivalent(
            "fn x : typeof case x of 0 => true | _ => false => ()");

    ml("fn x : typeof {a = 1, b = bool} => ()").assertParseSame();
    mlE("fn x : typeof {$a: int$} => ()")
        .assertTypeThrowsCompileException(
            "cannot derive label for expression a : int");
    ml("fn x : typeof [1, 2, 3] => ()").assertParseSame();
    ml("let val (v : typeof (hd [1, 2, 3])) = 0 in v + 1 end")
        .assertParseEquivalent(
            "let val (v : typeof (hd ([1, 2, 3]))) = 0 in v + 1 end")
        .assertParseEquivalent(
            "let val v : typeof (hd [1, 2, 3]) = 0 in v + 1 end");

    mlE("let val v : typeof hd $[$1, 2, 3] = 0 in v + 1 end")
        .assertParseThrowsParseException("Encountered \" \"[\" \"[ \"\"");

    ml("let val (v : typeof x) = (y = 0) in v + 1 end")
        .assertParseEquivalent("let val v : typeof x = y = 0 in v + 1 end");

    // '=' has lower precedence than 'typeof'
    ml("let val (v : typeof (x = y)) = false in v orelse true end")
        .assertParseEquivalent(
            "let val v : typeof (x = y) = false in v orelse true end");

    ml("let val (v : typeof x) = (y = false) in v orelse true end")
        .assertParseEquivalent(
            "let val v : typeof x = (y = false) in v orelse true end")
        .assertParseEquivalent(
            "let val v : typeof x = y = false in v orelse true end");
  }

  /**
   * Tests parsing a signature declaration. More tests in {@code
   * signature.smli}.
   */
  @Test
  void testParseSignature() {
    // Simple signature with abstract type and value specs
    ml("signature ORDERED = sig type t val lt : t * t -> bool end")
        .assertParseDecl(
            Ast.SignatureDecl.class,
            "signature ORDERED = sig type t val lt : t * t -> bool end");
  }

  @Test
  void testParse1b() {
    // parentheses creating left precedence, which is the natural precedence for
    // '+', can be removed
    ml("((1 + 2) + 3) + 4").assertParse("1 + 2 + 3 + 4");

    // parentheses creating right precedence can not be removed
    ml("1 + (2 + (3 + (4)))").assertParse("1 + (2 + (3 + 4))");

    ml("1 + (2 + (3 + 4)) = 5 + 5").assertParse("1 + (2 + (3 + 4)) = 5 + 5");

    // :: is right-associative
    ml("1 :: 2 :: 3 :: []").assertParse("1 :: 2 :: 3 :: []");
    ml("((1 :: 2) :: 3) :: []").assertParse("((1 :: 2) :: 3) :: []");
    ml("1 :: (2 :: (3 :: []))").assertParse("1 :: 2 :: 3 :: []");
    ml("1 + 2 :: 3 + 4 * 5 :: 6").assertParseSame();

    // o is left-associative;
    // lower precedence than "=" (4), higher than "andalso" (2)
    ml("f o g").assertParseSame();
    ml("f o g o h").assertParseEquivalent("(f o g) o h");
    ml("f o (g o h)").assertParseSame();

    ml("a = f o g andalso c = d")
        .assertParseEquivalent("(a = f) o g andalso (c = d)");
    ml("a = (f o g) andalso (c = d)").assertParse("a = (f o g) andalso c = d");

    // implies is left-associative
    ml("x > 5 implies y > 3").assertParseSame();
    ml("a implies b implies c")
        .assertParseEquivalent("(a implies b) implies c");
    ml("a implies (b implies c)").assertParseSame();

    // implies has lower precedence than andalso and orelse
    ml("a andalso b implies c").assertParseSame();
    ml("a orelse b implies c orelse d").assertParseSame();
    ml("a andalso b implies c orelse d andalso e")
        .assertParseEquivalent(
            "(a andalso b) implies (c orelse (d andalso e))");

    // @ is right-associative;
    // lower precedence than "+" (6), higher than "=" (4)
    ml("f @ g").assertParseSame();
    ml("f @ g @ h").assertParseEquivalent("f @ (g @ h)");
    ml("(f @ g) @ h").assertParseSame();

    // ^ is left-associative;
    // lower precedence than "*" (7), higher than "@" (5)
    ml("a * f ^ g @ b").assertParseSame();
    ml("a * f ^ (g @ b)").assertParseEquivalent("(a * f) ^ (g @ b)");

    ml("(1 + 2, 3, true, (5, 6), 7 = 8)").assertParseSame();

    ml("let val x = 2 in x + (3 + x) + x end").assertParseSame();

    ml("let val x = 2 and y = 3 in x + y end").assertParseSame();
    ml("let val rec x = 2 and y = 3 in x + y end").assertParseSame();
    mlE("let val x = 2 and $rec$ y = 3 in x + y end")
        .assertParseThrowsParseException("Encountered \" \"rec\" \"rec \"\"");

    ml("let val inst first = fn (x, y) => x in x + y end").assertParseSame();

    // : is right-associative and low precedence
    ml("1 : int : int").assertParseSame();
    ml("(2 : int) + 1 : int").assertParseSame();
    ml("(2 : int) + (1 : int) : int")
        .assertParseEquivalent("((2 : int) + (1 : int)) : int");

    // pattern
    ml("let val (x, y) = (1, 2) in x + y end").assertParseSame();
    ml("let val (w as (x, y)) = (1, 2) in #1 w + #2 w + x + y end")
        .assertParseEquivalent(
            "let val w as (x, y) = (1, 2) in #1 w + #2 w + x + y end");

    // record
    ml("{a = 1}").assertParseSame();
    ml("{a = 1, b = 2}").assertParseSame();
    ml("{a = 1, b = {c = 2, d = true}, e = true}").assertParseSame();

    // if
    ml("if true then 1 else 2").assertParseSame();

    // if ... else if
    ml("if true then 1 else if false then 2 else 3").assertParseSame();

    // case
    ml("case 1 of 0 => \"zero\" | _ => \"nonzero\"").assertParseSame();
    ml("case {a = 1, b = 2} of {a = 1, ...} => 1").assertParseSame();
    ml("case {a = 1, b = 2} of {...} => 1").assertParseSame();
    ml("case {a = 1, b = 2} of {a = 3, ...} => 1")
        .assertParse("case {a = 1, b = 2} of {a = 3, ...} => 1");
  }

  @Test
  void testParse2() {
    // fn
    ml("fn x => x + 1").assertParseSame();
    ml("fn x => x + (1 + 2)").assertParseSame();
    ml("fn (x, y) => x + y").assertParseSame();
    ml("fn _ => 42").assertParseSame();
    ml("fn x => case x of 0 => 1 | _ => 2").assertParseSame();
    ml("fn () => 42").assertParseSame();
    ml("fn [] => 0 | x :: _ => x + 1").assertParseSame();

    // apply
    ml("(fn x => x + 1) 3").assertParseSame();
    ml("hd [1, 2, 3]").assertParseSame();

    // with
    ml("{e with deptno = 10}").assertParseSame();
    ml("{e with deptno = 10, empno = 100}").assertParseSame();
    ml("{hd scott.emps with deptno = 10, empno = 100}")
        .assertParse("{hd (#emps scott) with deptno = 10, empno = 100}");
  }

  @Test
  void testParseComment() {
    ml("1 + (* 2 + *) 3").assertParse("1 + 3");
    ml("1 +\n" //
            + "(* 2 +\n"
            + " *) 3")
        .assertParse("1 + 3");
    ml("(* 1 +\n" //
            + "2 +\n"
            + "3 *) 5 + 6")
        .assertParse("5 + 6");

    // Block comment inside block comment
    ml("(* Start block\n" //
            + "  (* nested block *)\n"
            + "  2 +\n"
            + "*)\n"
            + "1")
        .assertParse("1");

    // Line comment inside block comment
    ml("(* Start block\n"
            + "  3 + (*) Line comment\n"
            + "  2 +\n"
            + "*)\n"
            + "1")
        .assertParse("1");

    // Block comment inside line comment
    ml("1 +\n"
            + "2 + (*) Line (* comment *) 3 +\n"
            + "4 +\n"
            + "5 + (*) Line 2 (* comment\n"
            + "6 + (*) Line 3 comment *) + 7 +\n"
            + "8")
        .assertParse("1 + 2 + 4 + 5 + 6 + 8");
  }

  @Test
  void testParseCase() {
    // SML/NJ allows 'e' and 'E'
    ml("1E2").assertParseEquivalent("1e2");
    ml("0.01").assertParseEquivalent("1E~2").assertParseEquivalent("1e~2");
    ml("~0.01").assertParseEquivalent("~1E~2").assertParseEquivalent("~1e~2");

    // keywords such as 'val' and 'in' are case-sensitive
    ml("let val x = 1 in x + 1 end").assertParseSame();
    mlE("let $VAL$ x = 1 in x + 1 end")
        .assertParseThrowsParseException(
            "Encountered \" <IDENTIFIER> \"VAL \"");
    mlE("let val x = 1 IN x + 1 $end$")
        .assertParseThrowsParseException("Encountered \" \"end\" \"end \"");

    // 'notelem' is an infix operator;
    // 'notElem' and 'NOTELEM' are ordinary identifiers
    ml("1 + f notelem g * 2")
        .assertParse(true, "((((1) + (f))) notelem (((g) * (2))))");
    ml("1 + f notElem g * 2")
        .assertParse(true, "((1) + (((((((f) (notElem))) (g))) * (2))))");
    ml("1 + f NOTELEM g * 2")
        .assertParse(true, "((1) + (((((((f) (NOTELEM))) (g))) * (2))))");

    // 'o' is an infix operator;
    // 'O' is presumed to be just another value.
    ml("1 + f o g + 2").assertParse(true, "((((1) + (f))) o (((g) + (2))))");
    ml("1 + F o G + 2").assertParse(true, "((((1) + (F))) o (((G) + (2))))");
    ml("1 + f O g + 2")
        .assertParse(true, "((((1) + (((((f) (O))) (g))))) + (2))");
  }

  /**
   * Tests that the syntactic sugar "exp.field" is de-sugared to "#field exp".
   */
  @Test
  void testParseDot() {
    ml("#b a").assertParseEquivalent("a . b");
    ml("#c (#b a)").assertParseEquivalent("a . b . c");
    ml("#b a + #d c").assertParseEquivalent("a . b + c . d");
    ml("#b a + #d c").assertParseEquivalent("a.b+c.d");
    ml("#h (#b a + #d c * #g (#f e))")
        .assertParseEquivalent("(a.b+c.d*e.f.g).h");
    ml("a b").assertParseEquivalent("a(b)");
    ml("a (#c b)").assertParseEquivalent("a b.c");
    ml("#b a (#d c) (#f e)").assertParseEquivalent("a.b c.d e.f");
    ml("#b a (#d c) (#f e)").assertParseEquivalent("(a.b) (c.d) (e.f)");
    mlE("(a.$($b (c.d) (e.f))")
        .assertParseThrowsParseException("Encountered \" \"(\" \"( \"\"");
    mlE("(a.b c.$($d (e.f)))")
        .assertParseThrowsParseException("Encountered \" \"(\" \"( \"\"");
  }

  /** Tests that "tuple.1" is de-sugared to "#1 tuple". */
  @Test
  void testParseTupleDot() {
    ml("#1 t").assertParseEquivalent("t.1");
    // Chained numeric access requires parentheses because '1.2' is a real
    ml("#2 (#1 t)").assertParseEquivalent("(t.1).2");
    ml("#x (#1 t)").assertParseEquivalent("t.1.x");
    ml("#3 (#x (#1 t))").assertParseEquivalent("(t.1).x.3");
    mlE("(1, 2).$0$")
        .assertParseThrowsParseException(
            "Encountered \" "
                + "<NON_NEGATIVE_INTEGER_LITERAL> \"0 \"\" at line 1, column 8.\n"
                + "Was expecting one of:\n"
                + "    <NATURAL_LITERAL> ...\n"
                + "    <IDENTIFIER> ...\n"
                + "    <QUOTED_IDENTIFIER> ...\n"
                + "    ");
  }

  /**
   * Tests that the abbreviated record syntax "{a, e.b, #c e, d = e}" is
   * expanded to "{a = a, b = e.b, c = #c e, d = e}".
   *
   * <p>The test is a mixture of tests for parsing and type-checking because
   * expansion used to happen during parsing, but now happens during
   * type-checking.
   */
  @Test
  void testAbbreviatedRecord() {
    ml("{a, #b e, #c e, #d (e + 1), e = f + g}")
        .assertParseEquivalent("{a, e.b, #c e, #d (e + 1), e = f + g}");
    ml("{v = a, w = #b e, x = #c e, y = #d (e + 1), z = #f 2}")
        .assertParseEquivalent(
            "{v = a, w = e.b, x = #c e, y = #d (e + 1), z = (#f 2)}");
    ml("{w = a = b + c, a = b + c}").assertParseSame();
    mlE("{$1$}")
        .assertTypeThrowsCompileException(
            "cannot derive label for expression 1");
    mlE("{a, $b + c$}")
        .assertTypeThrowsCompileException(
            "cannot derive label for expression b + c");

    ml("fn (x, a) => case x of {a = a, b} => a + b")
        .assertParse("fn (x, a) => case x of {a = a, b = b} => a + b")
        .assertType("{a:int, b:int} * 'a -> int");
    ml("fn x => case x of {a, b = 2, ...} => a + 5")
        .assertParse("fn x => case x of {a = a, b = 2, ...} => a + 5")
        .assertType("'a -> int");
    ml("fn {a, b = 2, ...} => a + 5")
        .assertParse("fn {a = a, b = 2, ...} => a + 5")
        .assertType("'a -> int");
  }

  @Test
  void testParseErrorPosition() {
    mlE("let val x = 1 and y = $x$ + 2 in x + y end")
        .assertCompileException("unbound variable or constructor: x");
  }

  @Test
  void testRuntimeErrorPosition() {
    mlE("\"x\" ^\n"
            + "  $String.substring(\"hello\",\n"
            + "    1, 15)$ ^\n"
            + "  \"y\"\n")
        .assertEvalError(
            pos -> throwsA(Codes.BuiltInExn.SUBSCRIPT.mlName, pos));
  }

  /** Tests the name of {@link TypeVar}. */
  @Test
  void testTypeVarName() {
    assertError(
        () -> new TypeVar(-1).key(),
        throwsA(IllegalArgumentException.class, nullValue()));
    assertThat(new TypeVar(0), hasToString("'a"));
    assertThat(new TypeVar(1), hasToString("'b"));
    assertThat(new TypeVar(2), hasToString("'c"));
    assertThat(new TypeVar(25), hasToString("'z"));
    assertThat(new TypeVar(26), hasToString("'ba"));
    assertThat(new TypeVar(27), hasToString("'bb"));
    assertThat(new TypeVar(51), hasToString("'bz"));
    assertThat(new TypeVar(52), hasToString("'ca"));
    assertThat(new TypeVar(53), hasToString("'cb"));
    assertThat(new TypeVar(26 * 26 - 1), hasToString("'zz"));
    assertThat(new TypeVar(26 * 26), hasToString("'baa"));
    assertThat(new TypeVar(27 * 26 - 1), hasToString("'baz"));
    assertThat(new TypeVar(26 * 26 * 26 - 1), hasToString("'zzz"));
    assertThat(new TypeVar(26 * 26 * 26), hasToString("'baaa"));
  }

  // testOverload, testOverload1, testApply, testApply2 migrated to
  // type-inference.smli

  @Disabled("disable failing test - enable when we have polymorphic types")
  @Test
  void testLetIsPolymorphic() {
    // f has been introduced in a let-expression and is therefore treated as
    // polymorphic.
    ml("let val f = fn x => x in (f true, f 0) end").assertType("bool * int");
  }

  @Disabled("disable failing test - enable when we have polymorphic types")
  @Test
  void testHdIsPolymorphic() {
    ml("(hd [1, 2], hd [false, true])").assertType("int * bool");
    ml("let\n"
            + "  val h = hd\n"
            + "in\n"
            + "   (h [1, 2], h [false, true])\n"
            + "end")
        .assertType("int * bool");
  }

  @Test
  void testTypeVariable() {
    // Type tests migrated to type-inference.smli; keeping eval tests here
    ml("(fn _ => 42) 2").assertEval(is(42));
    ml("(fn x => x) 2").assertEval(is(2));
    ml("(fn x => x) \"foo\"").assertEval(is("foo"));
    ml("(fn x => x) true").assertEval(is(true));
  }

  @Disabled("disable failing test - enable when we have polymorphic types")
  @Test
  void testExponentialType0() {
    final String ml =
        "let\n" //
            + "  fun f x = (x, x)\n"
            + "in\n"
            + "  f (f 0)\n"
            + "end";
    ml(ml).assertType("xx");
  }

  @Disabled("until type-inference bug is fixed")
  @Test
  void testExponentialType() {
    final String ml =
        "let\n"
            + "  fun f x = (x, x, x)\n"
            + "in\n"
            + "   f (f (f (f (f 0))))\n"
            + "end";
    ml(ml).assertType("xx");
  }

  @Disabled("until type-inference bug is fixed")
  @Test
  void testExponentialType2() {
    final String ml =
        "fun f x y z = (x, y, z)\n"
            + "val p1 = (f, f, f)\n"
            + "val p2 = (p1, p1, p1)\n"
            + "val p3 = (p2, p2, p2)\n";
    ml(ml).assertType("xx");
  }

  @SuppressWarnings("ConstantConditions")
  @Test
  void testDummy() {
    switch (0) {
      case 0:
        ml("1").assertEval(is(1));
        // fall through
      case 1:
        ml("1 + 2").assertEval(is(3));
        // fall through
      case 2:
        ml("1 + 2 + 3").assertEval(is(6));
        // fall through
      case 3:
        ml("1 * 2 + 3 * 4").assertEval(is(14));
        // fall through
      case 4:
        ml("let val x = 1 in x + 2 end")
            .with(Prop.INLINE_PASS_COUNT, 0)
            .assertEval(is(3));
        // fall through
      case 5:
        ml("let val x = 1 and y = 2 in 7 end")
            .with(Prop.INLINE_PASS_COUNT, 0)
            .assertEval(is(7));
        // fall through
      case 6:
        ml("let val x = 1 and y = 2 in x + y + 4 end")
            .with(Prop.INLINE_PASS_COUNT, 0)
            .assertEval(is(7));
        // fall through
      case 7:
        ml("(not (true andalso false))").assertEval(is(true));
        // fall through
      case 8:
        ml("let val x = 1 and y = 2 and z = true and a = \"foo\" in\n"
                + "  if z then x else y\n"
                + "end")
            .assertEval(is(1));
    }
  }

  @Test
  void testEval() {
    // literals
    ml("1").assertEval(is(1));
    ml("~2").assertEval(is(-2));
    ml("\"a string\"").assertEval(is("a string"));
    ml("true").assertEval(is(true));
    ml("~10.25").assertEval(is(-10.25f));
    ml("~10.25e3").assertEval(is(-10_250f));
    ml("~1.25e~3").assertEval(is(-0.001_25f));
    ml("~1.25E~3").assertEval(is(-0.001_25f));
    ml("0e0").assertEval(is(0f));

    // boolean operators
    ml("true andalso false").assertEval(is(false));
    ml("true orelse false").assertEval(is(true));
    ml("false andalso false orelse true").assertEval(is(true));
    ml("false andalso true orelse true").assertEval(is(true));
    ml("(not (true andalso false))").assertEval(is(true));
    ml("not true").assertEval(is(false));
    ml("not not true")
        .assertError(
            "operator and operand don't agree [tycon mismatch]\n"
                + "  operator domain: bool\n"
                + "  operand:         bool -> bool");
    ml("not (not true)").assertEval(is(true));

    // comparisons
    ml("1 = 1").assertEval(is(true));
    ml("1 = 2").assertEval(is(false));
    ml("\"a\" = \"a\"").assertEval(is(true));
    ml("\"a\" = \"ab\"").assertEval(is(false));
    ml("1 < 1").assertEval(is(false));
    ml("1 < 2").assertEval(is(true));
    ml("\"a\" < \"a\"").assertEval(is(false));
    ml("\"a\" < \"ab\"").assertEval(is(true));
    ml("1 > 1").assertEval(is(false));
    ml("1 > 2").assertEval(is(false));
    ml("1 > ~2").assertEval(is(true));
    ml("\"a\" > \"a\"").assertEval(is(false));
    ml("\"a\" > \"ab\"").assertEval(is(false));
    ml("\"ac\" > \"ab\"").assertEval(is(true));
    ml("1 <= 1").assertEval(is(true));
    ml("1 <= 2").assertEval(is(true));
    ml("\"a\" <= \"a\"").assertEval(is(true));
    ml("\"a\" <= \"ab\"").assertEval(is(true));
    ml("1 >= 1").assertEval(is(true));
    ml("1 >= 2").assertEval(is(false));
    ml("1 >= ~2").assertEval(is(true));
    ml("\"a\" >= \"a\"").assertEval(is(true));
    ml("\"a\" >= \"ab\"").assertEval(is(false));
    ml("\"ac\" >= \"ab\"").assertEval(is(true));
    ml("1 + 4 = 2 + 3").assertEval(is(true));
    ml("1 + 2 * 2 = 2 + 3").assertEval(is(true));
    ml("1 + 2 * 2 < 2 + 3").assertEval(is(false));
    ml("1 + 2 * 2 > 2 + 3").assertEval(is(false));

    // arithmetic operators
    ml("2 + 3").assertEval(is(5));
    ml("2 + 3 * 4").assertEval(is(14));
    ml("2 * 3 + 4 * 5").assertEval(is(26));
    ml("2 - 3").assertEval(is(-1));
    ml("2 * 3").assertEval(is(6));
    ml("20.0 / 4.0").assertEval(is(5f));
    ml("20.0 / ~5.0").assertEval(is(-4f));

    ml("10 mod 3").assertEval(is(1));
    ml("~10 mod 3").assertEval(is(2));
    ml("~10 mod ~3").assertEval(is(-1));
    ml("10 mod ~3").assertEval(is(-2));
    ml("0 mod 3").assertEval(is(-0));
    ml("0 mod ~3").assertEval(is(0));
    ml("19 div 3").assertEval(is(6));
    ml("20 div 3").assertEval(is(6));
    ml("~19 div 3").assertEval(is(-7));
    ml("~18 div 3").assertEval(is(-6));
    ml("19 div ~3").assertEval(is(-7));
    ml("~21 div 3").assertEval(is(-7));
    ml("~21 div ~3").assertEval(is(7));
    ml("0 div 3").assertEval(is(0));

    // string operators
    ml("\"\" ^ \"\"").assertEval(is(""));
    ml("\"1\" ^ \"2\"").assertEval(is("12"));
    ml("1 ^ 2")
        .assertError(
            "operator and operand don't agree [overload conflict]\n"
                + "  operator domain: string * string\n"
                + "  operand:         [int ty] * [int ty]\n");

    // if
    ml("if true then 1 else 2").assertEval(is(1));
    ml("if false then 1 else if true then 2 else 3").assertEval(is(2));
    ml("if false\n" //
            + "then\n"
            + "  if true then 2 else 3\n"
            + "else 4")
        .assertEval(is(4));
    ml("if false\n" //
            + "then\n"
            + "  if true then 2 else 3\n"
            + "else\n"
            + "  if false then 4 else 5")
        .assertEval(is(5));

    // case
    ml("case 1 of 0 => \"zero\" | _ => \"nonzero\"")
        .assertType("string")
        .assertEval(is("nonzero"));
    ml("case 1 of x => x | y => y")
        .assertError(
            "Error: match redundant\n"
                + "          x => ...\n"
                + "    -->   y => ...\n");
    ml("case 1 of 1 => 2")
        .assertError(
            "Warning: match nonexhaustive\n" //
                + "          1 => ...\n");
    ml("let val f = fn x => case x of x => x + 1 in f 2 end").assertEval(is(3));

    // let
    ml("let val x = 1 in x + 2 end").assertEval(is(3));
    ml("let val x = 1 in ~x end").assertEval(is(-1));
    ml("let val x = 1 in ~(abs(~x)) end").assertEval(is(-1));

    // let with a tuple pattern
    ml("let val (x, y) = (1, 2) in x + y end").assertEval(is(3));
    ml("let val w as (x, y) = (1, 2) in #1 w + #2 w + x + y end")
        .assertEval(is(6));

    // composite val
    ml("val x = 1 and y = 2").assertEval(is(map("x", 1, "y", 2)));
    ml("val (x, y) = (1, true)").assertEval(is(map("x", 1, "y", true)));
    ml("val w as (x, y) = (2, false)")
        .assertEval(is(map("x", 2, "y", false, "w", list(2, false))));

    // let with multiple variables
    ml("let val x = 1 and y = 2 in x + y end").assertEval(is(3));
    // let with multiple variables
    ml("let val x = 1 and y = 2 and z = false in\n"
            + "  if z then x else y\n"
            + "end")
        .assertEval(is(2));
    ml("let val x = 1 and y = 2 and z = true in\n"
            + "  if z then x else y\n"
            + "end")
        .assertEval(is(1));
    ml("let val x = 1 and y = 2 and z = true and a = \"foo\" in\n"
            + "  if z then x else y\n"
            + "end")
        .assertEval(is(1));

    // let where variables shadow
    final String letNested =
        "let\n"
            + "  val x = 1\n"
            + "in\n"
            + "  let\n"
            + "    val x = 2\n"
            + "  in\n"
            + "    x * 3\n"
            + "  end + x\n"
            + "end";
    ml(letNested).assertEval(is(2 * 3 + 1));

    // let with match
    ml("(fn z => let val (x, y) = (z + 1, z + 2) in x + y end) 3")
        .assertEval(is(9));

    // tuple
    ml("(1, 2)").assertEval(is(list(1, 2)));
    ml("(1, (2, true))").assertEval(is(list(1, list(2, true))));
    ml("()").assertEval(is(Collections.emptyList()));
    ml("(1, 2, 1, 4)").assertEval(is(list(1, 2, 1, 4)));
  }

  @Test
  void testLetSequentialDeclarations() {
    // let with sequential declarations
    ml("let val x = 1; val y = x + 1 in x + y end").assertEval(is(3));

    // semicolon is optional
    ml("let val x = 1; val y = x + 1; in x + y end").assertEval(is(3));
    ml("let val x = 1 val y = x + 1 in x + y end").assertEval(is(3));

    // 'and' is executed in parallel, therefore 'x + 1' evaluates to 2, not 4
    ml("let val x = 1; val x = 3 and y = x + 1 in x + y end").assertEval(is(5));

    mlE("let val x = 1 and y = $x$ + 2 in x + y end")
        .assertCompileException("unbound variable or constructor: x");

    // let with val and fun
    ml("let fun f x = 1 + x; val x = 2 in f x end").assertEval(is(3));
  }

  /**
   * Tests that in a {@code let} clause, we can see previously defined
   * variables.
   */
  @Test
  void testLet2() {
    final String ml =
        "let\n"
            + "  val x = 1\n"
            + "  val y = x + 2\n"
            + "in\n"
            + "  y + x + 3\n"
            + "end";
    ml(ml).assertEval(is(7));
  }

  /** As {@link #testLet2()}, but using 'and'. */
  @Test
  void testLet3() {
    final String ml =
        "let\n"
            + "  val x = 1\n"
            + "  and y = 2\n"
            + "in\n"
            + "  y + x + 3\n"
            + "end";
    ml(ml).assertEval(is(6));
  }

  /** As {@link #testLet3()}, but a tuple is being assigned. */
  @Test
  void testLet3b() {
    // The intermediate form will have nested tuples, something like this:
    //   val v = (1, (2, 4)) in case v of (x, (y, z)) => y + 3 + x
    final String ml =
        "let\n"
            + "  val x = 1\n"
            + "  and (y, z) = (2, 4)\n"
            + "in\n"
            + "  y + x + 3\n"
            + "end";
    ml(ml).assertEval(is(6));
  }

  @Test
  void testLet3c() {
    // The intermediate form will have nested tuples, something like this:
    //   val v = (1, (2, 4)) in case v of (x, (y, z)) => y + 3 + x
    final String ml =
        "let\n"
            + "  val x1 :: x2 :: xs = [1, 5, 9, 13, 17]\n"
            + "  and (y, z) = (2, 4)\n"
            + "in\n"
            + "  y + x1 + x2 + 3\n"
            + "end";
    ml(ml).with(Prop.MATCH_COVERAGE_ENABLED, false).assertEval(is(11));
  }

  /** Tests that 'and' assignments occur simultaneously. */
  @Test
  void testLet4() {
    final String ml =
        "let\n"
            + "  val x = 5\n"
            + "  and y = 1\n"
            + "in\n"
            + "  let\n"
            + "    val x = y (* new x = old y = 1 *)\n"
            + "    and y = x + 2 (* new y = old x + 2 = 5 + 2 = 7 *)\n"
            + "  in\n"
            + "    y + x + 3 (* new y + new x + 3 = 7 + 1 + 3 = 11 *)\n"
            + "  end\n"
            + "end";
    ml(ml).assertEval(is(11));
  }

  /** Tests a closure in a let. */
  @Test
  void testLet5() {
    final String ml =
        "let\n"
            + "  val plus = fn x => fn y => x + y\n"
            + "  val plusTwo = plus 2\n"
            + "in\n"
            + "  plusTwo 3\n"
            + "end";
    ml(ml).assertEval(is(5));
  }

  /** Tests a predicate in a let. */
  @Test
  void testLet6() {
    final String ml =
        "let\n"
            + "  fun isZero x = x = 0\n"
            + "in\n"
            + "  fn i => i = 10 andalso isZero i\n"
            + "end";
    // With inlining, we want the plan to simplify to "fn i => false"
    final String plan =
        "match(i, andalso(apply2("
            + "fnValue =, get(name i), constant(10)), "
            + "apply2(fnValue =, get(name i), constant(0))))";
    ml(ml)
        .assertEval(whenAppliedTo(0, is(false)))
        .assertEval(whenAppliedTo(10, is(false)))
        .assertEval(whenAppliedTo(15, is(false)))
        .assertPlan(isCode(plan));
  }

  /**
   * Tests a function in a let. (From <a
   * href="https://www.microsoft.com/en-us/research/wp-content/uploads/2002/07/inline.pdf">Secrets
   * of the Glasgow Haskell Compiler inliner</a> (GHC inlining), section 2.3.
   */
  @Test
  void testLet7() {
    final String ml =
        "fun g (a, b, c) =\n"
            + "  let\n"
            + "    fun f x = x * 3\n"
            + "  in\n"
            + "    f (a + b) - c\n"
            + "  end";
    // With inlining, we want the plan to simplify to
    // "fn (a, b, c) => (a + b) * 3 - c"
    final String plan =
        "match(v, apply(fnCode match((a, b, c), "
            + "apply2(fnValue -, apply2(fnValue *, "
            + "apply2(fnValue +, get(name a), get(name b)), "
            + "constant(3)), get(name c))), argCode get(name v)))";
    ml(ml)
        // g (4, 3, 2) = (4 + 3) * 3 - 2 = 19
        .assertEval(whenAppliedTo(list(4, 3, 2), is(19)))
        .assertPlan(isCode(plan));
  }

  /**
   * Tests that a simple eager function ({@code Math.pow}) uses direct
   * application ({@code apply2}) when its arguments are a tuple.
   */
  @Test
  void testEvalApply2() {
    final String ml2 = "Math.pow (2.0, 3.0)";
    final String plan2 =
        "apply2(fnValue Math.pow, constant(2.0), constant(3.0))";
    ml(ml2).assertEval(is(8f)).assertPlan(isCode(plan2));

    // When the argument tuple is returned from a function call, we evaluate
    // the long way ('apply').
    final String ml2b = "Math.pow (hd [(2.0, 3.0)])";
    final String plan2b =
        "apply2Tuple(fnValue Math.pow,"
            + " apply(fnValue List.hd, "
            + "argCode tuple(tuple(constant(2.0), constant(3.0)))))";
    ml(ml2b).assertEval(is(8f)).assertPlan(isCode(plan2b));

    // As above with 3 arguments.
    final String ml3 = "String.substring (\"morel\", 3, 2)";
    final String plan3 =
        "apply3(fnValue String.substring, constant(morel), constant(3), constant(2))";
    ml(ml3).assertEval(is("el")).assertPlan(isCode(plan3));

    final String ml3b = "String.substring (hd [(\"morel\", 3, 2)])";
    final String plan3b =
        "apply3Tuple(fnValue String.substring,"
            + " apply(fnValue List.hd, "
            + "argCode tuple(tuple(constant(morel), constant(3), constant(2)))))";
    ml(ml3b).assertEval(is("el")).assertPlan(isCode(plan3b));

    // Invoke a function that has two curried arguments on one argument.
    final String ml1 = "String.isPrefix \"mo\"";
    final String plan1 = "apply1(fnValue String.isPrefix, constant(mo))";
    ml(ml1).assertEval(instanceOf(Applicable1.class)).assertPlan(isCode(plan1));
  }

  /**
   * Tests that name capture does not occur during inlining. (Example is from
   * GHC inlining, section 3.)
   */
  @Test
  void testNameCapture() {
    final String ml =
        "fn (a, b) =>\n"
            + "  let val x = a + b in\n"
            + "    let val a = 7 in\n"
            + "      x + a\n"
            + "    end\n"
            + "  end";
    ml(ml)
        // result should be x + a = (1 + 2) + 7 = 10
        // if 'a' were wrongly captured, result would be (7 + 2) + 7 = 16
        .assertEval(whenAppliedTo(list(1, 2), is(10)));
  }

  @Test
  void testMutualRecursion() {
    final String ml =
        "let\n"
            + "  fun f i = g (i * 2)\n"
            + "  and g i = if i > 10 then i else f (i + 3)\n"
            + "in\n"
            + "  f\n"
            + "end";
    ml(ml)
        // answers checked on SMLJ
        .assertEval(whenAppliedTo(1, is(26)))
        .assertEval(whenAppliedTo(2, is(14)))
        .assertEval(whenAppliedTo(3, is(18)));
  }

  @Test
  void testMutualRecursion3() {
    final String ml =
        "let\n"
            + "  fun isZeroMod3 0 = true | isZeroMod3 n = isTwoMod3 (n - 1)\n"
            + "  and isOneMod3 0 = false | isOneMod3 n = isZeroMod3 (n - 1)\n"
            + "  and isTwoMod3 0 = false | isTwoMod3 n = isOneMod3 (n - 1)\n"
            + "in\n"
            + "  fn n => (isZeroMod3 n, isOneMod3 n, isTwoMod3 n)\n"
            + "end";
    ml(ml)
        .assertEval(whenAppliedTo(17, is(list(false, false, true))))
        .assertEval(whenAppliedTo(18, is(list(true, false, false))));
  }

  /**
   * Tests a recursive {@code let} that includes a pattern. I'm not sure whether
   * this is valid Standard ML; SML-NJ doesn't like it.
   */
  @Disabled("until mutual recursion bug is fixed")
  @Test
  void testCompositeRecursiveLet() {
    final String ml =
        "let\n"
            + "  val rec (x, y) = (1, 2)\n"
            + "  and f = fn n => if n = 1 then 1 else n * f (n - 1)\n"
            + "in\n"
            + "  x + f 5 + y\n"
            + "end";
    ml(ml).assertEval(is(123));
  }

  /**
   * Tests that inlining of mutually recursive functions does not prevent
   * compilation from terminating.
   *
   * <p>Per GHC inlining, (f, g, h), (g, p, q) are strongly connected components
   * of the dependency graph. In each group, the inliner should choose one
   * function as 'loop-breaker' that will not be inlined; say f and q.
   */
  @Disabled("until mutual recursion bug is fixed")
  @Test
  void testMutualRecursionComplex() {
    final String ml0 =
        "let\n"
            + "  fun f i = g (i + 1)\n"
            + "  and g i = h (i + 2) + p (i + 4)\n"
            + "  and h i = if i > 100 then i + 8 else f (i + 16)\n"
            + "  and p i = q (i + 32)\n"
            + "  and q i = if i > 200 then i + 64 else g (i + 128)\n"
            + "in\n"
            + "  g 7\n"
            + "end";
    final String ml =
        "let\n"
            + "  val rec f = fn i => g (i + 1)\n"
            + "  and g = fn i => h (i + 2) + p (i + 4)\n"
            + "  and h = fn i => if i > 100 then i + 8 else f (i + 16)\n"
            + "  and p = fn i => q (i + 32)\n"
            + "  and q = fn i => if i > 200 then i + 64 else g (i + 128)\n"
            + "in\n"
            + "  g 7\n"
            + "end";
    ml(ml)
        // answers checked on SMLJ
        .assertEval(whenAppliedTo(1, is(4003)))
        .assertEval(whenAppliedTo(6, is(3381)))
        .assertEval(whenAppliedTo(7, is(3394)));
  }

  /**
   * Tests that you can use the same variable name in different parts of the
   * program without the types getting confused.
   */
  @Test
  void testSameVariableName() {
    final String ml =
        "List.filter\n"
            + " (fn e => e.x + 2 * e.y > 16)\n"
            + " (map\n"
            + "   (fn e => {x = e - 1, y = 10 - e})\n"
            + "   [1, 2, 3, 4, 5])";
    ml(ml).assertEval(isUnordered(list(list(0, 9), list(1, 8))));
  }

  /** As {@link #testSameVariableName()} but both variables are records. */
  @Test
  void testSameVariableName2() {
    final String ml =
        "List.filter\n"
            + " (fn e => e.x + 2 * e.y > 16)\n"
            + " (map\n"
            + "   (fn e => {x = e.a - 1, y = 10 - e.a})\n"
            + "   [{a=1}, {a=2}, {a=3}, {a=4}, {a=5}])";
    ml(ml).assertEval(isUnordered(list(list(0, 9), list(1, 8))));
  }

  /**
   * Tests a closure that uses one variable "x", called in an environment with a
   * different value of "x" (of a different type, to flush out bugs).
   */
  @Test
  void testClosure() {
    final String ml =
        "let\n"
            + "  val x = \"abc\";\n"
            + "  fun g y = size x + y;\n"
            + "  val x = 10\n"
            + "in\n"
            + "  g x\n"
            + "end";
    ml(ml).assertEval(is(13));
  }

  @Test
  void testEvalFn() {
    ml("(fn x => x + 1) 2").assertEval(is(3));
  }

  @Test
  void testEvalFnCurried() {
    ml("(fn x => fn y => x + y) 2 3").assertEval(is(5));
  }

  @Test
  void testEvalFnTuple() {
    ml("(fn (x, y) => x + y) (2, 3)").assertEval(is(5));
  }

  @Test
  void testEvalFnRec() {
    final String ml =
        "let\n"
            + "  val rec f = fn n => if n = 0 then 1 else n * f (n - 1)\n"
            + "in\n"
            + "  f 5\n"
            + "end";
    ml(ml).assertEval(is(120));
  }

  @Test
  void testEvalFnTupleGeneric() {
    ml("(fn (x, y) => x) (2, 3)").assertEval(is(2));
    ml("(fn (x, y) => y) (2, 3)").assertEval(is(3));
  }

  @Test
  void testRecord() {
    ml("{a = 1, b = {c = true, d = false}}").assertParseSame();
    ml("{a = 1, 1 = 2}").assertParseStmt(Ast.Record.class, "{a = 1, 1 = 2}");
    ml("#b {a = 1, b = {c = true, d = false}}").assertParseSame();
    ml("{0=1}").assertError(is("label must be positive"));
    ml("{a = 1, b = true}").assertType("{a:int, b:bool}");
    ml("{b = true, a = 1}").assertType("{a:int, b:bool}");
    ml("{a = 1, b = 2}").assertEval(is(list(1, 2)));
    ml("{a = true, b = ~2}").assertEval(is(list(true, -2)));
    ml("{a = true, b = ~2, c = \"c\"}").assertEval(is(list(true, -2, "c")));
    ml("let val ab = {a = true, b = ~2} in #a ab end").assertEval(is(true));
    ml("{a = true, b = {c = 1, d = 2}}").assertEval(is(list(true, list(1, 2))));
    ml("#a {a = 1, b = true}").assertType("int").assertEval(is(1));
    ml("#b {a = 1, b = true}").assertType("bool").assertEval(is(true));
    ml("#b {a = 1, b = 2}").assertEval(is(2));
    ml("#b {a = 1, b = {x = 3, y = 4}, z = true}").assertEval(is(list(3, 4)));
    ml("#x (#b {a = 1, b = {x = 3, y = 4}, z = true})").assertEval(is(3));
  }

  @Test
  void testEquals() {
    ml("{b = true, a = 1} = {a = 1, b = true}").assertEval(is(true));
    ml("{b = true, a = 0} = {a = 1, b = true}").assertEval(is(false));
  }

  @Disabled("deduce type of #label")
  @Test
  void testRecord2() {
    ml("#x #b {a = 1, b = {x = 3, y = 4}, z = true}")
        .assertError(
            "Error: operator and operand don't agree [type mismatch]\n"
                + "  operator domain: {x:'Y; 'Z}\n"
                + "  operand:         {b:'W; 'X} -> 'W\n"
                + "  in expression:\n"
                + "    (fn {x=x,...} => x) (fn {b=b,...} => b)\n");
  }

  @Test
  void testRecordFn() {
    ml("(fn {a=a1,b=b1} => a1) {a = 1, b = true}")
        .assertType("int")
        .assertEval(is(1));
    ml("(fn {a=a1,b=b1} => b1) {a = 1, b = true}")
        .assertType("bool")
        .assertEval(is(true));
  }

  @Test
  void testRecordMatch() {
    final String ml =
        "case {a=1, b=2, c=3}\n"
            + "  of {a=2, b=2, c=3} => 0\n"
            + "   | {a=1, c=x, ...} => x\n"
            + "   | _ => ~1";
    ml(ml).assertEval(is(3));
    ml("fn {} => 0").assertParseSame();
    mlE("fn {a=1, ...$,$ c=2}")
        .assertParseThrowsParseException("Encountered \" \",\" \", \"\"");
    ml("fn {...} => 0").assertParseSame();
    ml("fn {a = a, ...} => 0").assertParseSame();
    ml("fn {a, b = {c, d}, ...} => 0")
        .assertParse("fn {a = a, b = {c = c, d = d}, ...} => 0");
  }

  @Test
  void testRecordCase() {
    ml("case {a=2,b=3} of {a=x,b=y} => x * y").assertEval(is(6));
    ml("case {a=2,b=3,c=4} of {a=x,b=y,c=z} => x * y").assertEval(is(6));
    ml("case {a=2,b=3,c=4} of {a=x,b=y,...} => x * y").assertEval(is(6));
    // resolution of flex records is more lenient in case than in fun
    ml("case {a=2,b=3,c=4} of {a=3,...} => 1 | {b=2,...} => 2 | _ => 3")
        .assertEval(is(3));
  }

  @Test
  void testRecordTuple() {
    // Type tests migrated to type-inference.smli; keeping eval tests here
    ml("#2 (true, 0)").assertType("int").assertEval(is(0));
    ml("{}").assertType("unit").assertEval(is(ImmutableList.of()));
  }

  @Test
  void testList() {
    // Type tests migrated to type-inference.smli; keeping eval test here
    ml("1 :: 2 :: []").assertType("int list").assertEval(is(list(1, 2)));
  }

  @Disabled("need type annotations")
  @Test
  void testList2() {
    ml("fn x: 'b list => 0").assertType("'a list -> int");
  }

  /** List length function exercises list pattern-matching and recursion. */
  @Test
  void testListLength() {
    final String ml =
        "let\n"
            + "  val rec len = fn x =>\n"
            + "    case x of [] => 0\n"
            + "            | head :: tail => 1 + len tail\n"
            + "in\n"
            + "  len [1, 2, 3]\n"
            + "end";
    ml(ml).assertEval(is(3));
  }

  /**
   * As {@link #testListLength()} but match reversed, which requires cautious
   * matching of :: pattern.
   */
  @Test
  void testListLength2() {
    final String ml =
        "let\n"
            + "  val rec len = fn x =>\n"
            + "    case x of head :: tail => 1 + len tail\n"
            + "            | [] => 0\n"
            + "in\n"
            + "  len [1, 2, 3]\n"
            + "end";
    ml(ml).assertEval(is(3));
  }

  /** As {link {@link #testListLength()} but using {@code fun}. */
  @Test
  void testListLength3() {
    final String ml =
        "let\n"
            + "  fun len [] = 0\n"
            + "     | len (head :: tail) = 1 + len tail\n"
            + "in\n"
            + "  len [1, 2, 3]\n"
            + "end";
    ml(ml).assertEval(is(3));
  }

  @Test
  void testFunUnit() {
    final String ml =
        "let\n" //
            + "  fun one () = 1\n"
            + "in\n"
            + "  one () + 2\n"
            + "end";
    ml(ml).assertEval(is(3));
  }

  @Test
  void testMatchTuple() {
    final String ml =
        "let\n"
            + "  val rec sumIf = fn v =>\n"
            + "    case v of (true, n) :: tail => n + sumIf tail\n"
            + "            | (false, _) :: tail => sumIf tail\n"
            + "            | _ => 0\n"
            + "in\n"
            + "  sumIf [(true, 2), (false, 3), (true, 5)]\n"
            + "end";
    ml(ml).assertEval(is(7));
  }

  /**
   * The algorithm is described in <a
   * href="https://stackoverflow.com/questions/7883023/algorithm-for-type-checking-ml-like-pattern-matching">
   * Stack overflow</a> and in Lennart Augustsson's 1985 paper "Compiling
   * Pattern Matching".
   */
  @Test
  void testMatchRedundant() {
    final String ml =
        "fun f x = case x > 0 of\n"
            + "   true => \"positive\"\n"
            + " | false => \"non-positive\"\n"
            + " | $true => \"oops\"$";
    mlE(ml)
        .assertMatchCoverage(REDUNDANT)
        .assertEvalThrows(pos -> throwsA("match redundant", pos));

    // similar, but 'fun' rather than 'case'
    final String ml2 =
        ""
            + "fun f true = \"positive\"\n"
            + "  | f false = \"non-positive\"\n"
            + "  | $f true = \"oops\"$";
    mlE(ml2)
        .assertMatchCoverage(REDUNDANT)
        .assertEvalThrows(pos -> throwsA("match redundant", pos));
  }

  @Test
  void testMatchCoverage1() {
    final String ml = "fun f (x, y) = x + y + 1";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage2() {
    final String ml =
        ""
            + "fun f (1, y) = y\n"
            + "  | f (x, 2) = x\n"
            + "  | f (x, y) = x + y + 1";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage3() {
    final String ml = "fun f 1 = 2 | f x = x + 3";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage4() {
    final String ml =
        "" //
            + "fun f 1 = 2\n"
            + "  | f _ = 1";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage5() {
    final String ml =
        "" //
            + "fun f [] = 0\n"
            + "  | f (h :: t) = 1 + (f t)";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage6() {
    final String ml =
        "" //
            + "fun f (0, y) = y\n"
            + "  | f (x, y) = x + y + 1";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage7() {
    final String ml =
        "" //
            + "fun f (x, y, 0) = y\n"
            + "  | f (x, y, z) = x + z";
    ml(ml).assertMatchCoverage(OK);
  }

  @Test
  void testMatchCoverage8() {
    // The last case is redundant because we know that bool has two values.
    final String ml =
        ""
            + "fun f (true, y, z) = y\n"
            + "  | f (false, y, z) = z\n"
            + "  | $f _ = 0$";
    mlE(ml)
        .assertMatchCoverage(REDUNDANT)
        .assertEvalError(pos -> throwsA("match redundant", pos));
  }

  @Test
  void testMatchCoverage9() {
    // The last case is redundant because we know that unit has only one value.
    final String ml =
        "" //
            + "fun f () = 1\n"
            + "  | $f _ = 0$";
    mlE(ml).assertMatchCoverage(REDUNDANT);
  }

  @Test
  void testMatchCoverage10() {
    final String ml =
        "fun maskToString m =\n"
            + "  let\n"
            + "    fun maskToString2 (m, s, 0) = s\n"
            + "      | maskToString2 (m, s, k) =\n"
            + "        maskToString2 (m div 3,\n"
            + "          ($case (m mod 3) of\n"
            + "              0 => \"b\"\n"
            + "            | 1 => \"y\"\n"
            + "            | 2 => \"g\"$) ^ s,\n"
            + "          k - 1)\n"
            + "  in\n"
            + "    maskToString2 (m, \"\", 5)\n"
            + "  end";
    mlE(ml).assertMatchCoverage(NON_EXHAUSTIVE);
  }

  @Test
  void testMatchCoverage12() {
    // two "match nonexhaustive" warnings
    final String ml =
        "fun f x =\n"
            + "  let\n"
            + "    fun g 1 = 1\n"
            + "    and h 2 = 2\n"
            + "  in\n"
            + "    (g x) + (h 2)\n"
            + "  end";
    ml(ml)
        .assertMatchCoverage(NON_EXHAUSTIVE)
        .assertEvalWarnings(
            new CustomTypeSafeMatcher<List<? extends Throwable>>(
                "two warnings") {
              @Override
              protected boolean matchesSafely(List<? extends Throwable> list) {
                return list.size() == 2
                    && list.get(0) instanceof CompileException
                    && list.get(0).getMessage().equals("match nonexhaustive")
                    && list.get(1) instanceof CompileException
                    && list.get(1).getMessage().equals("match nonexhaustive");
              }
            });
  }

  /**
   * Test case for "[MOREL-205] Pattern that uses nested type-constructors
   * should not be considered redundant".
   */
  @Test
  void testMatchCoverage13() {
    // Even though "SOME i" is seen at depth 1 in the first line,
    // the "SOME" at depth 0 in the second line is not the same pattern,
    // therefore the pattern is not redundant.
    final String ml =
        "fun f (SOME (SOME i)) = i + 1\n"
            + "  | f (SOME NONE) = 0\n"
            + "  | f NONE = ~1\n";
    ml(ml).assertMatchCoverage(OK);
  }

  /** Function declaration. */
  @Test
  void testFun() {
    final String ml =
        "let\n"
            + "  fun fact n = if n = 0 then 1 else n * fact (n - 1)\n"
            + "in\n"
            + "  fact 5\n"
            + "end";
    ml(ml).assertEval(is(120));
  }

  /** As {@link #testFun()} but not applied to a value. */
  @Test
  void testFunValue() {
    final String ml =
        "let\n"
            + "  fun fact n = if n = 0 then 1 else n * fact (n - 1)\n"
            + "in\n"
            + "  fact\n"
            + "end";
    ml(ml).assertEval(whenAppliedTo(5, is(120)));
  }

  /**
   * As {@link #testFunValue()} but without "let".
   *
   * <p>This is mainly a test for the test framework. We want to handle bindings
   * ({@code fun}) as well as values ({@code let} and {@code fn}). So people can
   * write tests more concisely.
   */
  @Test
  void testFunValueSansLet() {
    final String ml = "fun fact n = if n = 0 then 1 else n * fact (n - 1)";
    ml(ml).assertEval(whenAppliedTo(5, is(120)));
  }

  /** As {@link #testFun} but uses case. */
  @Test
  void testFun2() {
    final String ml =
        "let\n"
            + "  fun fact n = case n of 0 => 1 | _ => n * fact (n - 1)\n"
            + "in\n"
            + "  fact 5\n"
            + "end";
    ml(ml).assertEval(is(120));
  }

  /** As {@link #testFun} but uses a multi-clause function. */
  @Test
  void testFun3() {
    final String ml =
        "let\n"
            + "  fun fact 1 = 1 | fact n = n * fact (n - 1)\n"
            + "in\n"
            + "  fact 5\n"
            + "end";
    final String expected =
        "let"
            + " fun fact 1 = 1 "
            + "| fact n = n * fact (n - 1) "
            + "in fact 5 end";
    ml(ml).assertParse(expected).assertEval(is(120));
  }

  /** Simultaneous functions. */
  @Test
  void testFun4() {
    final String ml =
        "let\n"
            + "  val x = 1\n"
            + "in\n"
            + "  let\n"
            + "    val x = 17\n"
            + "    and inc1 = fn n => n + x\n"
            + "    and inc2 = fn n => n + x + x\n"
            + "  in\n"
            + "    inc2 (inc1 x)\n"
            + "  end\n"
            + "end";
    ml(ml).assertType("int").assertEval(is(20));
  }

  /**
   * Mutually recursive functions: the definition of 'even' references 'odd' and
   * the definition of 'odd' references 'even'.
   */
  @Disabled("not working yet")
  @Test
  void testMutuallyRecursiveFunctions() {
    final String ml =
        "let\n"
            + "  fun even 0 = true\n"
            + "    | even n = odd (n - 1)\n"
            + "  and odd 0 = false\n"
            + "    | odd n = even (n - 1)\n"
            + "in\n"
            + "  odd 7\n"
            + "end";
    ml(ml).assertType("boolean").assertEval(is(true));
  }

  /** A function with two arguments. */
  @Test
  void testFunTwoArgs() {
    final String ml =
        "let\n" //
            + "  fun plus x y = x + y\n"
            + "in\n"
            + "  plus 5 3\n"
            + "end";
    ml(ml).assertEval(is(8));
  }

  @Test
  void testFunRecord() {
    final String ml =
        ""
            + "fun f {a=x,b=1,...} = x\n"
            + "  | f {b=y,c=2,...} = y\n"
            + "  | f {a=x,b=y,c=z} = x+y+z";
    ml(ml)
        .assertType("{a:int, b:int, c:int} -> int")
        .assertEval(whenAppliedTo(list(1, 2, 3), is(6)));

    final String ml2 =
        "let\n"
            + "  fun f {a=x,b=1,...} = x\n"
            + "    | f {b=y,c=2,...} = y\n"
            + "    | f {a=x,b=y,c=z} = x+y+z\n"
            + "in\n"
            + "  f {a=1,b=2,c=3}\n"
            + "end";
    ml(ml2).assertEval(is(6));
  }

  @Test
  void testDatatype() {
    final String ml =
        "let\n"
            + "  datatype 'a tree = NODE of 'a tree * 'a tree | LEAF of 'a\n"
            + "in\n"
            + "  NODE (LEAF 1, NODE (LEAF 2, LEAF 3))\n"
            + "end";
    ml(ml)
        .assertParseSame()
        .assertType(hasMoniker("'a tree"))
        .assertType(
            instanceOfAnd(
                DataType.class,
                hasTypeConstructors("{NODE='a tree * 'a tree, LEAF='a}")))
        .assertEval(is(node(leaf(1), node(leaf(2), leaf(3)))));
  }

  private static List<Object> leaf(Object arg) {
    return list("LEAF", arg);
  }

  private static List<Object> node(Object... args) {
    return list("NODE", list(args));
  }

  @Test
  void testDatatype2() {
    final String ml =
        "let\n"
            + "  datatype number = ZERO | INTEGER of int | RATIONAL of int * int\n"
            + "in\n"
            + "  RATIONAL (2, 3)\n"
            + "end";
    ml(ml)
        .assertParseSame()
        .assertType("number")
        .assertEval(is(ImmutableList.of("RATIONAL", ImmutableList.of(2, 3))));
  }

  @Test
  void testDatatype3() {
    final String ml =
        "let\n"
            + "  datatype intoption = NONE | SOME of int;\n"
            + "  val score = fn z => case z of NONE => 0 | SOME x => x\n"
            + "in\n"
            + "  score (SOME 5)\n"
            + "end";
    ml(ml).assertParseSame().assertType("int").assertEval(is(5));
  }

  /**
   * As {@link #testDatatype3()} but with {@code fun} rather than {@code fn} ...
   * {@code case}.
   */
  @Test
  void testDatatype3b() {
    final String ml =
        "let\n"
            + "  datatype intoption = NONE | SOME of int;\n"
            + "  fun score NONE = 0\n"
            + "    | score (SOME x) = x\n"
            + "in\n"
            + "  score (SOME 5)\n"
            + "end";
    ml(ml).assertParseSame().assertType("int").assertEval(is(5));
  }

  /** As {@link #testDatatype3b()} but use a nilary type constructor (NONE). */
  @Test
  void testDatatype3c() {
    final String ml =
        "let\n"
            + "  datatype intoption = NONE | SOME of int;\n"
            + "  fun score NONE = 0\n"
            + "    | score (SOME x) = x\n"
            + "in\n"
            + "  score NONE\n"
            + "end";
    ml(ml).assertParseSame().assertType("int").assertEval(is(0));
  }

  @Test
  void testDatatype4() {
    final String ml =
        "let\n"
            + " datatype intlist = NIL | CONS of int * intlist;\n"
            + " fun depth NIL = 0\n"
            + "   | depth CONS (x, y) = 1 + depth y\n"
            + "in\n"
            + " depth NIL\n"
            + "end";
    ml(ml).assertParseSame().assertType("int").assertEval(is(0));
  }

  /** As {@link #testDatatype4()} but with deeper expression. */
  @Test
  void testDatatype4a() {
    final String ml =
        "let\n"
            + " datatype intlist = NIL | CONS of int * intlist;\n"
            + " fun depth NIL = 0\n"
            + "   | depth CONS (x, y) = 1 + depth y\n"
            + "in\n"
            + " depth (CONS (5, CONS (2, NIL)))\n"
            + "end";
    ml(ml).assertParseSame().assertType("int").assertEval(is(2));
  }

  /**
   * Tests set operators (union, except, intersect).
   *
   * <p>In Morel 0.6 and earlier, {@code union}, {@code intersect} and {@code
   * except} were binary operators. From Morel 0.7, they are now step in a
   * "from" pipeline.
   */
  @Test
  void testSetOp() {
    ml("from i in a union b").assertParseSame();
    ml("from i in a union b union c").assertParseSame();
    ml("from j in (from i in a union b) union c").assertParseSame();
    ml("from i in a union (from j in b union c)").assertParseSame();
    ml("from i in a union b except c union d intersect e").assertParseSame();
    ml("from i in a union distinct b except distinct c"
            + " union distinct d intersect e")
        .assertParseSame();
    ml("from x in emps union depts where deptno = 10").assertParseSame();
    ml("List.concat [[1, 2, 3], [2, 3, 4]]")
        .assertEvalIter(equalsUnordered(1, 2, 3, 2, 3, 4));
  }

  @Test
  void testFrom() {
    final String ml =
        "let\n"
            + "  val emps = [\n"
            + "    {id = 100, name = \"Fred\", deptno = 10},\n"
            + "    {id = 101, name = \"Velma\", deptno = 20},\n"
            + "    {id = 102, name = \"Shaggy\", deptno = 30},\n"
            + "    {id = 103, name = \"Scooby\", deptno = 30}]\n"
            + "in\n"
            + "  from e in emps yield #deptno e\n"
            + "end";
    ml(ml).assertEvalIter(equalsOrdered(10, 20, 30, 30));
  }

  @Test
  void testFromIntegers() {
    //    ml("from i in [1]")
    //        .assertType("int list")
    //        .assertEvalIter(equalsOrdered(1))
    //    ;
    ml("from i in [1] where i > 0")
        .assertType("int list")
        .assertEvalIter(equalsOrdered(1));
    ml("from i in [1] yield i")
        .assertType("int list")
        .assertEvalIter(equalsOrdered(1));
    //    ml("from i in bag [1]").assertType("int
    // list").assertEvalIter(equalsOrdered(1));
    //    ml("from i in bag [1] where i > 0").assertType("int
    // list").assertEvalIter(equalsOrdered(1));
  }

  @Test
  void testParseFrom() {
    ml("from").assertParseSame();
    ml("from e in emps").assertParseSame();
    ml("from e in emps where c").assertParseSame();
    ml("from e in emps, d in depts").assertParseSame();
    ml("from e in emps, d where hasEmp e").assertParseSame();
    ml("from e, d where hasEmp e").assertParseSame();
    ml("from e in emps, job, d where hasEmp (e, d, job)").assertParseSame();
    ml("from a, b in emps where a > b join c join d in depts where c > d")
        .assertParse(
            "from a, b in emps where a > b "
                + "join c, d in depts where c > d");
    ml("from a, b in emps where a > b join c, d in depts where c > d")
        .assertParseSame();
    ml("from e in emps, d in depts on e.deptno = d.deptno")
        .assertParse("from e in emps, d in depts on #deptno e = #deptno d");
    mlE("from e in emps $on$ true, d in depts on e.deptno = d.deptno")
        .assertParseThrowsParseException("Encountered \" \"on\" \"on \"\"");
    ml("from e, d in depts on e.deptno = d.deptno")
        .assertParse("from e, d in depts on #deptno e = #deptno d");
    ml("from , d in depts").assertError("Xx");
    ml("from join d in depts on c").assertError("Xx");
    ml("from left join d in depts on c").assertError("Xx");
    ml("from right join d in depts on c").assertError("Xx");
    ml("from full join d in depts on c").assertError("Xx");
    ml("from e in emps join d in depts").assertError("Xx");
    ml("from e in emps join d in depts where c").assertError("Xx");
    ml("from e in emps join d in depts on c")
        .assertParse("from e in emps, d in depts on c");
    if ("TODO".isEmpty()) {
      ml("from e in emps left join d in depts on c").assertParseSame();
      ml("from e in emps right join d in depts on c").assertParseSame();
      ml("from e in emps full join d in depts on c").assertParseSame();
    }
    ml("from e in (from z in emps), d in (from y in depts) on c")
        .assertParseSame();
    ml("from e in emps distinct").assertParseSame();
    ml("from e in emps distinct where deptno > 10").assertParseSame();

    // Until [MOREL-317], "on" after singleton "join =" was allowed.
    mlE("from x in [1, 2]\n" //
            + "  join y = 3 $on$ y = x + 1")
        .assertParseThrowsParseException("Encountered \" \"on\" \"on \"\"");
    mlE("from x = 3 $on$ false")
        .assertParseThrowsParseException("Encountered \" \"on\" \"on \"\"");

    ml("from e in emps\n"
            + " group e.deptno\n"
            + " join d in depts on deptno = d.deptno\n"
            + " group d.location\n")
        .assertParse(
            "from e in emps"
                + " group #deptno e"
                + " join d in depts on deptno = #deptno d"
                + " group #location d");
    // As previous, but use 'group e = {...}' so that we can write 'e.deptno'
    // later in the query.
    ml("from e in emps\n"
            + " group e = {e.deptno}\n"
            + " join d in depts on e.deptno = d.deptno\n"
            + " group d.location")
        .assertParse(
            "from e in emps"
                + " group e = {#deptno e}"
                + " join d in depts on #deptno e = #deptno d"
                + " group #location d");
    mlE("(from e in emps where e.id = 101, d $in$ depts)")
        .assertParseThrowsParseException("Encountered \" \"in\" \"in \"\"");
    ml("from e in emps where e.id = 101 join d in depts")
        .assertParse("from e in emps where #id e = 101 join d in depts");
    // after 'group', you have to use 'join' not ','
    mlE("(from e in emps\n"
            + " group e.id compute count over (),\n"
            + " d $in$ depts\n"
            + " where false)")
        .assertParseThrowsParseException("Encountered \" \"in\" \"in \"\"");
    ml("(from e in emps\n"
            + " group e.id compute count over ()\n"
            + " join d in depts\n"
            + " where false)")
        .assertParse(
            "from e in emps"
                + " group #id e compute count over ()"
                + " join d in depts where false");
    ml("from e in emps skip 1 take 2").assertParseSame();
    ml("from e in emps order (DESC e.empno, e.deptno)")
        .assertParse("from e in emps order (DESC (#empno e), #deptno e)");
    ml("from e in emps yield e.deptno order current mod 2")
        .assertParse("from e in emps yield #deptno e order current mod 2");
    ml("from e in emps yield e.empno order (ordinal mod 2, ordinal div 2)")
        .assertParse(
            "from e in emps yield #empno e "
                + "order (ordinal mod 2, ordinal div 2)");
    ml("from e in emps order e.empno take 2")
        .assertParse("from e in emps order #empno e take 2");
    ml("from e in emps order e.empno take 2 skip 3 skip 1+1 take 2")
        .assertParse(
            "from e in emps order #empno e take 2 skip 3 skip 1 + 1 "
                + "take 2");
    ml("from i in [1, 2] unorder").assertParseSame();
    ml("from i in [1, 2] unorder where i > 1").assertParseSame();
    ml("from i in integers unorder").assertParseSame();
    mlE("from i in (integers $unorder$)")
        .assertParseThrowsParseException(
            "Encountered \" \"unorder\" \"unorder \"\"");
    ml("fn f => from i in [1, 2, 3] where f i").assertParseSame();
    ml("fn f => from i in [1, 2, 3] join j in [3, 4] on f (i, j) yield i + j")
        .assertParse(
            "fn f => from i in [1, 2, 3],"
                + " j in [3, 4] on f (i, j) yield i + j");

    // In "from p in exp" and "from p = exp", p can be any pattern
    // but in "from v" v can only be an identifier.
    ml("from x, y in [1, 2], z").assertParseSame();
    ml("from {x, y} in [{x=1, y=2}], z")
        .assertParse("from {x = x, y = y} in [{x = 1, y = 2}], z");
    mlE("from {x, y}$,$ z")
        .assertParseThrowsParseException("Encountered \" \",\" \", \"\"");
    mlE("from {x, y} $group$")
        .assertParseThrowsParseException(
            "Encountered \" \"group\" \"group \"\"");
    mlE("from {x, y} $where$ true")
        .assertParseThrowsParseException(
            "Encountered \" \"where\" \"where \"\"");
    mlE("from (x, y) $where$ true")
        .assertParseThrowsParseException(
            "Encountered \" \"where\" \"where \"\"");
    mlE("from w as (x, y) $order$ x")
        .assertParseThrowsParseException(
            "Encountered \" \"order\" \"order \"\"");
    mlE("from (x, y$)$")
        .assertParseThrowsParseException("Encountered \"<EOF>\"");
    ml("from e in emps\n" //
            + "through e in empsInDept 20\n"
            + "yield e.sal")
        .assertParse("from e in emps through e in empsInDept 20 yield #sal e");
    ml("from e in emps\n" //
            + "yield e.empno\n"
            + "into sum")
        .assertParse("from e in emps yield #empno e into sum");
    ml("from e in emps\n" //
            + "yield e.empno\n"
            + "compute {sum over current, count over current}")
        .assertParse(
            "from e in emps "
                + "yield #empno e "
                + "compute {sum over current, count over current}");
    ml("from i in [1, 2] union [3, 4]").assertParseSame();
    ml("from i in [0, 1, 2]\n"
            + "where i > 0\n"
            + "union [3, 4], [5]\n"
            + "except [2], [0]\n"
            + "intersect [1, 3, 5, 7]\n"
            + "yield i + 3")
        .assertParseSame();
  }

  /** Tests parsing "from ... group". */
  @Test
  void testParseFromGroup() {
    ml("from e in emps group {e.deptno, e.job}")
        .assertParse("from e in emps group {#deptno e, #job e}");
    ml("from e in emps\n"
            + "group {e.deptno, e.job}\n"
            + "  compute {sumSal = sum over e.sal}")
        .assertParse(
            "from e in emps "
                + "group {#deptno e, #job e}"
                + " compute {sumSal = sum over #sal e}");
    ml("from e in emps\n" //
            + "group {} compute 1 + sum over 2 * e.sal")
        .assertParse(
            "from e in emps group {} compute 1 + (sum over 2 * #sal e)");
    ml("from e in emps\n"
            + "group e.deptno\n"
            + "  compute {s = from x in elements compute sum over e.sal}\n"
            + "order deptno")
        .assertParse(
            "from e in emps "
                + "group #deptno e"
                + " compute {s = from x in elements compute sum over #sal e} "
                + "order deptno");
  }

  /**
   * This test is a copy of {@link #testParseFrom()}, replacing "from" with
   * "exists".
   */
  @Test
  void testParseExists() {
    ml("exists").assertParseSame();
    ml("exists e in emps").assertParseSame();
    ml("exists e in emps where c").assertParseSame();
    ml("exists e in emps, d in depts").assertParseSame();
    ml("exists e in emps, d where hasEmp e").assertParseSame();
    ml("exists e, d where hasEmp e").assertParseSame();
    ml("exists e in emps, job, d where hasEmp (e, d, job)").assertParseSame();
    ml("exists a, b in emps where a > b join c join d in depts where c > d")
        .assertParse(
            "exists a, b in emps where a > b "
                + "join c, d in depts where c > d");
    ml("exists a, b in emps where a > b join c, d in depts where c > d")
        .assertParseSame();
    ml("exists e in emps, d in depts on e.deptno = d.deptno")
        .assertParse("exists e in emps, d in depts on #deptno e = #deptno d");
    mlE("exists e in emps $on$ true, d in depts on e.deptno = d.deptno")
        .assertParseThrowsParseException("Encountered \" \"on\" \"on \"\"");
    ml("exists e, d in depts on e.deptno = d.deptno")
        .assertParse("exists e, d in depts on #deptno e = #deptno d");
    ml("exists , d in depts").assertError("Xx");
    ml("exists join d in depts on c").assertError("Xx");
    ml("exists left join d in depts on c").assertError("Xx");
    ml("exists right join d in depts on c").assertError("Xx");
    ml("exists full join d in depts on c").assertError("Xx");
    ml("exists e in emps join d in depts").assertError("Xx");
    ml("exists e in emps join d in depts where c").assertError("Xx");
    ml("exists e in emps join d in depts on c")
        .assertParse("exists e in emps, d in depts on c");
    if ("TODO".isEmpty()) {
      ml("exists e in emps left join d in depts on c").assertParseSame();
      ml("exists e in emps right join d in depts on c").assertParseSame();
      ml("exists e in emps full join d in depts on c").assertParseSame();
    }
    ml("exists e in (exists z in emps), d in (exists y in depts) on c")
        .assertParseSame();
    ml("exists e in emps distinct").assertParseSame();
    ml("exists e in emps distinct where deptno > 10").assertParseSame();
    ml("exists e in emps\n"
            + " group e.deptno\n"
            + " join d in depts on deptno = d.deptno\n"
            + " group d.location\n")
        .assertParse(
            "exists e in emps"
                + " group #deptno e"
                + " join d in depts on deptno = #deptno d"
                + " group #location d");
    // As previous, but use 'group e = {...}' so that we can write 'e.deptno'
    // later in the query.
    ml("exists e in emps\n"
            + " group e = {e.deptno}\n"
            + " join d in depts on e.deptno = d.deptno\n"
            + " group d.location")
        .assertParse(
            "exists e in emps"
                + " group e = {#deptno e}"
                + " join d in depts on #deptno e = #deptno d"
                + " group #location d");
    mlE("(exists e in emps where e.id = 101, d $in$ depts)")
        .assertParseThrowsParseException("Encountered \" \"in\" \"in \"\"");
    ml("exists e in emps where e.id = 101 join d in depts")
        .assertParse("exists e in emps where #id e = 101 join d in depts");
    // after 'group', you have to use 'join' not ','
    mlE("(exists e in emps\n"
            + " group e.id compute count over (),\n"
            + " d $in$ depts\n"
            + " where false)")
        .assertParseThrowsParseException("Encountered \" \"in\" \"in \"\"");
    ml("(exists e in emps\n"
            + " group e.id compute count over ()\n"
            + " join d in depts\n"
            + " where false)")
        .assertParse(
            "exists e in emps"
                + " group #id e compute count over ()"
                + " join d in depts where false");
    ml("exists e in emps skip 1 take 2").assertParseSame();
    ml("exists e in emps order e.empno take 2")
        .assertParse("exists e in emps order #empno e take 2");
    ml("exists e in emps order e.empno take 2 skip 3 skip 1+1 take 2")
        .assertParse(
            "exists e in emps order #empno e take 2 skip 3 skip 1 + 1 "
                + "take 2");
    ml("fn f => exists i in [1, 2, 3] where f i").assertParseSame();
    ml("fn f => exists i in [1, 2, 3] join j in [3, 4] on f (i, j) yield i + j")
        .assertParse(
            "fn f => exists i in [1, 2, 3],"
                + " j in [3, 4] on f (i, j) yield i + j");

    // In "exists p in exp" and "exists p = exp", p can be any pattern
    // but in "exists v" v can only be an identifier.
    ml("exists x, y in [1, 2], z").assertParseSame();
    ml("exists {x, y} in [{x=1, y=2}], z")
        .assertParse("exists {x = x, y = y} in [{x = 1, y = 2}], z");
    mlE("exists {x, y}$,$ z")
        .assertParseThrowsParseException("Encountered \" \",\" \", \"\"");
    mlE("exists {x, y} $group$")
        .assertParseThrowsParseException(
            "Encountered \" \"group\" \"group \"\"");
    mlE("exists {x, y} $where$ true")
        .assertParseThrowsParseException(
            "Encountered \" \"where\" \"where \"\"");
    mlE("exists (x, y) $where$ true")
        .assertParseThrowsParseException(
            "Encountered \" \"where\" \"where \"\"");
    mlE("exists w as (x, y) $order$ x")
        .assertParseThrowsParseException(
            "Encountered \" \"order\" \"order \"\"");
    mlE("exists (x, y$)$")
        .assertParseThrowsParseException("Encountered \"<EOF>\"");
    ml("exists e in emps\n" //
            + "through e in empsInDept 20\n"
            + "yield e.sal")
        .assertParse(
            "exists e in emps through e in empsInDept 20 yield #sal e");
    ml("exists e in emps\n" //
            + "yield e.empno\n"
            + "into sum")
        .assertParse("exists e in emps yield #empno e into sum");
    ml("exists e in emps\n" //
            + "yield e.empno\n"
            + "compute {sum over current, count over current}")
        .assertParse(
            "exists e in emps "
                + "yield #empno e "
                + "compute {sum over current, count over current}");
  }

  /**
   * This test is a copy of {@link #testParseFrom()}, replacing "from" with
   * "forall".
   */
  @Test
  void testParseForall() {
    ml("forall require true").assertParseSame();
    ml("forall e in emps require true").assertParseSame();
    ml("forall e in emps where c").assertParseSame();
    ml("forall e in emps, d in depts").assertParseSame();
    ml("forall e in emps, d where hasEmp e").assertParseSame();
    ml("forall e, d where hasEmp e").assertParseSame();
    ml("forall e in emps, job, d where hasEmp (e, d, job)").assertParseSame();
    ml("forall a, b in emps where a > b join c join d in depts where c > d")
        .assertParse(
            "forall a, b in emps where a > b "
                + "join c, d in depts where c > d");
    ml("forall a, b in emps where a > b join c, d in depts where c > d")
        .assertParseSame();
    ml("forall e in emps, d in depts on e.deptno = d.deptno")
        .assertParse("forall e in emps, d in depts on #deptno e = #deptno d");
    mlE("forall e in emps $on$ true, d in depts on e.deptno = d.deptno")
        .assertParseThrowsParseException("Encountered \" \"on\" \"on \"\"");
    ml("forall e, d in depts on e.deptno = d.deptno")
        .assertParse("forall e, d in depts on #deptno e = #deptno d");
    ml("forall , d in depts").assertError("Xx");
    ml("forall join d in depts on c").assertError("Xx");
    ml("forall left join d in depts on c").assertError("Xx");
    ml("forall right join d in depts on c").assertError("Xx");
    ml("forall full join d in depts on c").assertError("Xx");
    ml("forall e in emps join d in depts").assertError("Xx");
    ml("forall e in emps join d in depts where c").assertError("Xx");
    ml("forall e in emps join d in depts on c")
        .assertParse("forall e in emps, d in depts on c");
    if ("TODO".isEmpty()) {
      ml("forall e in emps left join d in depts on c").assertParseSame();
      ml("forall e in emps right join d in depts on c").assertParseSame();
      ml("forall e in emps full join d in depts on c").assertParseSame();
    }
    ml("forall e in (forall z in emps), d in (forall y in depts) on c")
        .assertParseSame();
    ml("forall e in emps distinct").assertParseSame();
    ml("forall e in emps distinct where deptno > 10").assertParseSame();
    ml("forall e in emps\n"
            + " group e.deptno\n"
            + " join d in depts on deptno = d.deptno\n"
            + " group d.location\n")
        .assertParse(
            "forall e in emps"
                + " group #deptno e"
                + " join d in depts on deptno = #deptno d"
                + " group #location d");
    // As previous, but use 'group e = {...}' so that we can write 'e.deptno'
    // later in the query.
    ml("forall e in emps\n"
            + " group e = {e.deptno}\n"
            + " join d in depts on e.deptno = d.deptno\n"
            + " group d.location")
        .assertParse(
            "forall e in emps"
                + " group e = {#deptno e}"
                + " join d in depts on #deptno e = #deptno d"
                + " group #location d");
    mlE("(forall e in emps where e.id = 101, d $in$ depts)")
        .assertParseThrowsParseException("Encountered \" \"in\" \"in \"\"");
    ml("forall e in emps where e.id = 101 join d in depts")
        .assertParse("forall e in emps where #id e = 101 join d in depts");
    // after 'group', you have to use 'join' not ','
    mlE("(forall e in emps\n"
            + " group e.id compute count over (),\n"
            + " d $in$ depts\n"
            + " where false)")
        .assertParseThrowsParseException("Encountered \" \"in\" \"in \"\"");
    ml("(forall e in emps\n"
            + " group e.id compute count over ()\n"
            + " join d in depts\n"
            + " where false)")
        .assertParse(
            "forall e in emps"
                + " group #id e compute count over ()"
                + " join d in depts where false");
    ml("forall e in emps skip 1 take 2").assertParseSame();
    ml("forall e in emps order e.empno take 2")
        .assertParse("forall e in emps order #empno e take 2");
    ml("forall e in emps order e.empno take 2 skip 3 skip 1+1 take 2")
        .assertParse(
            "forall e in emps order #empno e take 2 skip 3 skip 1 + 1 "
                + "take 2");
    ml("fn f => forall i in [1, 2, 3] where f i").assertParseSame();
    ml("fn f => forall i in [1, 2, 3] join j in [3, 4] on f (i, j) yield i + j")
        .assertParse(
            "fn f => forall i in [1, 2, 3],"
                + " j in [3, 4] on f (i, j) yield i + j");

    // In "forall p in exp" and "forall p = exp", p can be any pattern
    // but in "forall v" v can only be an identifier.
    ml("forall x, y in [1, 2], z").assertParseSame();
    ml("forall {x, y} in [{x=1, y=2}], z")
        .assertParse("forall {x = x, y = y} in [{x = 1, y = 2}], z");
    mlE("forall {x, y}$,$ z")
        .assertParseThrowsParseException("Encountered \" \",\" \", \"\"");
    mlE("forall {x, y} $group$")
        .assertParseThrowsParseException(
            "Encountered \" \"group\" \"group \"\"");
    mlE("forall {x, y} $where$ true")
        .assertParseThrowsParseException(
            "Encountered \" \"where\" \"where \"\"");
    mlE("forall (x, y) $where$ true")
        .assertParseThrowsParseException(
            "Encountered \" \"where\" \"where \"\"");
    mlE("forall w as (x, y) $order$ x")
        .assertParseThrowsParseException(
            "Encountered \" \"order\" \"order \"\"");
    mlE("forall (x, y$)$")
        .assertParseThrowsParseException("Encountered \"<EOF>\"");
    ml("forall e in emps\n" //
            + "through e in empsInDept 20\n"
            + "yield e.sal")
        .assertParse(
            "forall e in emps through e in empsInDept 20 yield #sal e");
    ml("forall e in emps\n" //
            + "yield e.empno\n"
            + "into sum")
        .assertParse("forall e in emps yield #empno e into sum");
    ml("forall e in emps\n" //
            + "yield e.empno\n"
            + "compute {sum over current, count over current}")
        .assertParse(
            "forall e in emps "
                + "yield #empno e "
                + "compute {sum over current, count over current}");
  }

  @Test
  void testFromType() {
    // Most type tests migrated to type-inference.smli.
    // Keeping tests with assertParse, assertEval, or other assertions.
    ml("from (x, y) in [(1,2),(3,4),(3,0)] group x + y")
        .assertParse("from (x, y) in [(1, 2), (3, 4), (3, 0)] group x + y")
        .assertType(hasMoniker("int list"))
        .assertEvalIter(equalsUnordered(3, 7));
    ml("from (x, y) in [(1,2),(3,4),(3,0)] group {sum = x + y}")
        .assertParse(
            "from (x, y) in [(1, 2), (3, 4), (3, 0)] group {sum = x + y}")
        .assertType(hasMoniker("{sum:int} list"))
        .assertEvalIter(equalsUnordered(list(3), list(7)));
    ml("from {c, a, ...} in [{a=1.0,b=true,c=3},{a=1.5,b=true,c=4}]")
        .assertParse(
            "from {a = a, c = c, ...}"
                + " in [{a = 1.0, b = true, c = 3}, {a = 1.5, b = true, c = 4}]")
        .assertType("{a:real, c:int} list");
    ml("from d in [{a=1,b=true}] yield d.a into sum")
        .assertType("int")
        .assertEval(is(1));
    ml("from a in [1], b in [true] yield {b,a} where b")
        .assertType("{a:int, b:bool} list")
        .assertEval(is(list(list(1, true))));
    mlE("from e in [{empno=1,deptno=10,name=\"Fred\"},\n"
            + "         ${$empno=2,deptno=10,name=\"Jane\"}]\n"
            + "  order {e.empno, e.deptno}")
        .withWarningsMatcher(
            hasToString(
                "[net.hydromatic.morel.compile.CompileException: "
                    + "Sorting on a record whose fields are not in alphabetical "
                    + "order. Sort order may not be what you expect. "
                    + "at stdIn:3.9-3.28]"))
        .assertType("{deptno:int, empno:int, name:string} list");
  }

  @Test
  void testFromType2() {
    ml("from d in [{a=1,b=true}] yield d.a into sum")
        .assertType("int")
        .assertEval(is(1));

    // TODO when [MOREL-270] is fixed, we can deduce that the type is
    // constrained, e.g. "multi (int list * int bag -> int bag, int list * int
    // list -> int list)"
    ml("fn (a: int list, b) => from i in a union b")
        .assertType("int list * 'a -> 'b");
    ml("fn (a: int list, b) => from i in a intersect b")
        .assertType("int list * 'a -> 'b");
    ml("fn (a: int list, b) => from i in a except b")
        .assertType("int list * 'a -> 'b");

    ml("from d in [{a=1,b=true},{a=2,b=false}]\n"
            + " yield d.a\n"
            + " through s in (fn ints =>\n"
            + "   from i in ints yield substring (\"abc\", 0, i))")
        .assertType("string list")
        .assertEval(is(list("a", "ab")));
  }

  @Test
  void testFromYieldExpression() {
    final String ml =
        "let\n"
            + "  val emps = [\n"
            + "    {id = 100, name = \"Fred\", deptno = 10},\n"
            + "    {id = 101, name = \"Velma\", deptno = 20},\n"
            + "    {id = 102, name = \"Shaggy\", deptno = 30},\n"
            + "    {id = 103, name = \"Scooby\", deptno = 30}]\n"
            + "in\n"
            + "  from e in emps yield (#id e + #deptno e)\n"
            + "end";
    ml(ml).assertEvalIter(equalsOrdered(110, 121, 132, 133));
  }

  @Test
  void testFromWhere() {
    final String ml =
        "let\n"
            + "  val emps = [\n"
            + "    {id = 100, name = \"Fred\", deptno = 10},\n"
            + "    {id = 101, name = \"Velma\", deptno = 20},\n"
            + "    {id = 102, name = \"Shaggy\", deptno = 30},\n"
            + "    {id = 103, name = \"Scooby\", deptno = 30}]\n"
            + "in\n"
            + "  from e in emps where #deptno e = 30 yield #id e\n"
            + "end";
    ml(ml).assertEvalIter(equalsOrdered(102, 103));
  }

  /**
   * A query with an unconstrained scan that is deduced to be of type {@code
   * bool option} and therefore iterates over {@code [SOME true, SOME false,
   * NONE]}.
   */
  @Test
  void testBooleanExtent() {
    final String ml =
        "from i\n" //
            + "where Option.getOpt (i, false)";
    final String core =
        "val it = "
            + "from i in extent \"bool option\" "
            + "where #getOpt Option (i, false)";
    ml(ml)
        .assertType("bool option bag")
        .assertCore(-1, hasToString(core))
        .assertEval(is(list(list("SOME", true))));
  }

  @Test
  void testFromNoYield() {
    final String ml =
        "let\n"
            + "  val emps =\n"
            + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
            + "     {id = 103, name = \"Scooby\", deptno = 30}]\n"
            + "in\n"
            + "  from e in emps where #deptno e = 30\n"
            + "end";
    ml(ml)
        .assertType("{deptno:int, id:int, name:string} list")
        .assertEvalIter(equalsOrdered(list(30, 103, "Scooby")));
  }

  @Test
  void testFromJoinNoYield() {
    final String ml =
        "let\n"
            + "  val emps =\n"
            + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
            + "     {id = 101, name = \"Velma\", deptno = 20}]\n"
            + "  val depts =\n"
            + "    [{deptno = 10, name = \"Sales\"}]\n"
            + "in\n"
            + "  from e in emps, d in depts where #deptno e = #deptno d\n"
            + "end";
    ml(ml)
        .assertType(
            "{d:{deptno:int, name:string},"
                + " e:{deptno:int, id:int, name:string}} list")
        .assertEvalIter(
            equalsOrdered(list(list(10, "Sales"), list(10, 100, "Fred"))));
  }

  @Test
  void testYieldYield() {
    final String ml =
        "let\n"
            + "  val emps =\n"
            + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
            + "     {id = 101, name = \"Velma\", deptno = 20}]\n"
            + "in\n"
            + "  from e in emps\n"
            + "  yield {x = e.id + e.deptno, y = e.id - e.deptno}\n"
            + "  yield x + y\n"
            + "end";
    ml(ml).assertType("int list").assertEvalIter(equalsOrdered(200, 202));
  }

  @Test
  void testYieldSingletonRecord() {
    final String ml =
        "from e in [{x=1,y=2},{x=3,y=4},{x=5,y=6}]\n"
            + "  yield {z=e.x}\n"
            + "  where z > 2\n"
            + "  order DESC z\n"
            + "  yield {z=z}";
    ml(ml)
        .assertType("{z:int} list")
        .assertEvalIter(equalsOrdered(list(5), list(3)));

    final String ml2 =
        "from e in [{x=1,y=2},{x=3,y=4},{x=5,y=6}]\n" //
            + "  yield {z=e.x}";
    ml(ml2).assertType("{z:int} list");

    final String ml3 =
        "from e in [{x=1,y=2},{x=3,y=4},{x=5,y=6}]\n"
            + "  yield {z=e.x}\n"
            + "  where z > 2";
    ml(ml3).assertType("{z:int} list");

    final String ml4 =
        "from e in [{x=1,y=2},{x=3,y=4},{x=5,y=6}]\n"
            + "  yield {z=e.x}\n"
            + "  where z > 2\n"
            + "  order DESC z";
    ml(ml4).assertType("{z:int} list");
  }

  /**
   * Analogous to SQL "CROSS APPLY" which calls a table-valued function for each
   * row in an outer loop.
   */
  @Test
  void testCrossApply() {
    final String ml =
        "from s in [\"abc\", \"\", \"d\"],\n"
            + "    c in explode s\n"
            + "  yield s ^ \":\" ^ str c";
    ml(ml).assertEvalIter(equalsOrdered("abc:a", "abc:b", "abc:c", "d:d"));
  }

  @Test
  void testCrossApplyGroup() {
    final String ml =
        "from s in [\"abc\", \"\", \"d\"],\n"
            + "    c in explode s\n"
            + "  group s compute {count = sum over 1}";
    ml(ml).assertEvalIter(equalsUnordered(list(3, "abc"), list(1, "d")));
  }

  @Test
  void testJoinLateral() {
    final String ml =
        "let\n"
            + "  val emps = [{name = \"Shaggy\",\n"
            + "               pets = [{name = \"Scooby\", species = \"Dog\"},\n"
            + "                       {name = \"Scrappy\", species = \"Dog\"}]},\n"
            + "              {name = \"Charlie\",\n"
            + "               pets = [{name = \"Snoopy\", species = \"Dog\"}]},\n"
            + "              {name = \"Danny\", pets = []}]"
            + "in\n"
            + "  from e in emps,\n"
            + "      p in e.pets\n"
            + "    yield p.name\n"
            + "end";
    ml(ml).assertEvalIter(equalsOrdered("Scooby", "Scrappy", "Snoopy"));
  }

  @Test
  void testFromGroupWithoutCompute() {
    final String ml =
        "let\n"
            + "  val emps =\n"
            + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
            + "     {id = 101, name = \"Velma\", deptno = 20},\n"
            + "     {id = 102, name = \"Shaggy\", deptno = 10}]\n"
            + "in\n"
            + "  from e in emps group #deptno e\n"
            + "end";
    final String expected =
        "let val emps = "
            + "[{id = 100, name = \"Fred\", deptno = 10},"
            + " {id = 101, name = \"Velma\", deptno = 20},"
            + " {id = 102, name = \"Shaggy\", deptno = 10}] "
            + "in"
            + " from e in emps"
            + " group #deptno e "
            + "end";
    ml("val x = " + ml)
        .assertParseDecl(Ast.ValDecl.class, "val x = (" + expected + ")");
    // The implicit yield expression is "deptno". It is not a record,
    // "{deptno = deptno}", because there is only one variable defined (the
    // "group" clause defines "deptno" and hides the "e" from the "from"
    // clause).
    ml(ml).assertType("int list").assertEvalIter(equalsUnordered(10, 20));
  }

  /**
   * As {@link #testFromGroupWithoutCompute()} but composite key, therefore
   * result is a list of records.
   */
  @Test
  void testFromGroupWithoutCompute2() {
    final String ml =
        "let\n"
            + "  val emps =\n"
            + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
            + "     {id = 101, name = \"Velma\", deptno = 20},\n"
            + "     {id = 102, name = \"Shaggy\", deptno = 10}]\n"
            + "in\n"
            + "  from e in emps group {#deptno e, parity = e.id mod 2}\n"
            + "end";
    ml(ml)
        .assertType("{deptno:int, parity:int} list")
        .assertEvalIter(equalsUnordered(list(10, 0), list(20, 1)));
  }

  @Test
  void testFromGroup() {
    final String ml =
        "let\n"
            + "  val emps =\n"
            + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
            + "     {id = 101, name = \"Velma\", deptno = 20},\n"
            + "     {id = 102, name = \"Shaggy\", deptno = 10}]\n"
            + "  fun sum bag =\n"
            + "    case Bag.getItem bag of\n"
            + "        NONE => 0\n"
            + "      | SOME (h, t) => h + (sum t)\n"
            + "in\n"
            + "  from e in emps\n"
            + "    group #deptno e\n"
            + "    compute {sumId = sum over #id e}\n"
            + "end";
    final String expected =
        "let val emps = "
            + "[{id = 100, name = \"Fred\", deptno = 10},"
            + " {id = 101, name = \"Velma\", deptno = 20},"
            + " {id = 102, name = \"Shaggy\", deptno = 10}]; "
            + "fun sum bag = case #getItem Bag bag of"
            + " NONE => 0"
            + " | SOME (h, t) => h + sum t "
            + "in"
            + " from e in emps"
            + " group #deptno e"
            + " compute {sumId = sum over #id e} "
            + "end";
    ml("val x = " + ml)
        .assertParseDecl(Ast.ValDecl.class, "val x = (" + expected + ")");
    ml(ml)
        .assertType("{deptno:int, sumId:int} list")
        .assertEvalIter(equalsUnordered(list(10, 202), list(20, 101)));
  }

  @Test
  void testGroupAs() {
    final String ml0 =
        "from e in emps\n" //
            + "group {deptno = e.deptno}";
    final String ml1 =
        "from e in emps\n" //
            + "group e.deptno";
    final String ml2 =
        "from e in emps\n" //
            + "group #deptno e";
    final String expected0 = "from e in emps group {#deptno e}";
    final String expected = "from e in emps group #deptno e";
    ml(ml0).assertParse(expected0);
    ml(ml1).assertParse(expected);
    ml(ml2).assertParse(expected);

    final String ml3 =
        "from e in emps\n" //
            + "group {e, h = f + e.g}";
    final String expected3 = "from e in emps group {e, h = f + #g e}";
    ml(ml3).assertParse(expected3);
  }

  @Test
  void testGroupAs2() {
    mlE("from e in [{deptno=10, empid=1}]\n"
            + "  group {e.deptno, $e.deptno + e.empid$}")
        .assertTypeThrowsCompileException(
            "cannot derive label for expression #deptno e + #empid e");
    ml("from e in emps group 1").assertParseSame();
    mlE("from e in [{deptno=10, empid=1}] group {$1$}")
        .assertTypeThrowsCompileException(
            "cannot derive label for expression 1");
    // Nit: error region should start before '('
    mlE("from e in [{deptno=10, empid=1}]\n"
            + "  group e.deptno compute {($fn x => x) over e.job$}")
        .assertTypeThrowsCompileException(
            "cannot derive label for expression fn x => x over #job e");
    // If there is only one expression in the group, we do not require that
    // we can derive a name for the expression.
    ml("from e in emps group {} compute (fn x => x) over e.job")
        .assertParse("from e in emps group {} compute fn x => x over #job e");
    // But if we add a group key, now there are two expressions, and the compute
    // expression must have a derivable name.
    mlE("from e in [{empno=1,deptno=10,job=\"Analyst\"},\n"
            + "        {empno=2,deptno=10,job=\"Manager\"}]\n"
            + "group e.deptno compute ($fn x => x) over e.job$")
        .assertTypeThrowsTypeException(
            "cannot derive label for compute expression");
    // And vice versa.
    mlE("from e in [{empno=1,deptno=10,job=\"Analyst\"},\n"
            + "        {empno=2,deptno=10,job=\"Manager\"}]\n"
            + "group $1 + e.deptno$ compute sum over e.empno")
        .assertTypeThrowsTypeException(
            "cannot derive label for group expression");
    ml("from e in [{x = 1, y = 5}]\n" //
            + "  group {} compute sum over e.x")
        .assertType(hasMoniker("int list"));
    ml("from e in [1, 2, 3]\n" //
            + "  group {} compute sum over e")
        .assertType(hasMoniker("int list"));
  }

  @Test
  void testGroupSansOf() {
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "  group {} compute count over e")
        .assertType(hasMoniker("int list"))
        .assertEvalIter(equalsUnordered(3));

    ml("from e in [{a = 1, b = 5}, {a = 0, b = 1}, {a = 1, b = 1}]\n"
            + "  group e.a compute {rows = (fn x => x) over e}")
        .assertType(hasMoniker("{a:int, rows:{a:int, b:int} list} list"))
        .assertEvalIter(
            equalsUnordered(
                list(1, list(list(1, 5), list(1, 1))),
                list(0, list(list(0, 1)))));

    mlE("from e in [{a = 1, b = 5}, {a = 0, b = 1}, {a = 1, b = 1}]\n"
            + "  group e.a compute ($fn x => x) over e$")
        .assertTypeThrowsTypeException(
            "cannot derive label for compute expression");
  }

  /**
   * Tests that Morel throws if there are duplicate names in 'group' or
   * 'compute' clauses.
   */
  @Test
  void testGroupDuplicates() {
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group e.x")
        .assertType("int list")
        .assertEvalIter(equalsUnordered(0, 1));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {e.x}")
        .assertType("{x:int} list")
        .assertEvalIter(equalsUnordered(list(0), list(1)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x}")
        .assertType("{a:int} list")
        .assertEvalIter(equalsUnordered(list(0), list(1)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x, b = e.x}")
        .assertEvalIter(equalsUnordered(list(0, 0), list(1, 1)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x, a = e.y}")
        .assertTypeThrowsRuntimeException("duplicate field 'a' in record");
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {e.x, x = e.y}")
        .assertTypeThrowsRuntimeException("duplicate field 'x' in record");
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x}\n"
            + "compute {b = sum over e.y}")
        .assertEvalIter(equalsUnordered(list(0, 1), list(1, 6)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x}\n"
            + "compute {a = sum over e.y}")
        .assertTypeThrowsRuntimeException("Duplicate field name 'a' in group");
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {sum = e.x}\n"
            + "compute sum over e.y")
        .assertTypeThrowsRuntimeException(
            "Duplicate field name 'sum' in group");
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x}\n"
            + "compute {b = sum over e.y, c = sum over e.x}")
        .assertEvalIter(equalsUnordered(list(0, 1, 0), list(1, 6, 2)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
            + "group {a = e.x}\n"
            + "compute {c = sum over e.y, c = sum over e.x}")
        .assertTypeThrowsRuntimeException("duplicate field 'c' in record");
  }

  /**
   * Tests query with 'compute' without 'group'. Such a query does not return a
   * collection, but returns the value of the aggregate function. Technically,
   * it is a monoid comprehension, and an aggregate function is a monoid.
   */
  @Test
  void testCompute() {
    ml("from i in [1, 2, 3] compute sum over i")
        .assertParse("from i in [1, 2, 3] compute sum over i")
        .assertType("int")
        .assertEval(is(6));
    ml("from i in [1, 2, 3] compute {sum over i, count over i}")
        .assertParse("from i in [1, 2, 3] compute {sum over i, count over i}")
        .assertType("{count:int, sum:int}");
    // there must be at least one aggregate function
    mlE("from i in [1, 2, 3] comput$e$")
        .assertParseThrowsParseException("Encountered \"<EOF>\" at ");

    // Theoretically a "group" without a "compute" can be followed by a
    // "compute" step. So, the following is ambiguous. We treat it as a single
    // "group ... compute" step. Under the two-step interpretation, the type
    // would have been "int".
    ml("from (i, j) in [(1, 1), (2, 3), (3, 4)]\n"
            + "  group {j = i mod 2}\n"
            + "  compute sum over j")
        .assertType("{j:int, sum:int} list")
        .assertEvalIter(equalsUnordered(list(1, 5), list(0, 3)));

    // "compute" must not be followed by other steps
    mlE("from i in [1, 2, 3] compute sum over i $yield s + 2$")
        .assertCompileException("'compute' step must be last in 'from'");
    // similar, but valid
    ml("(from i in [1, 2, 3] compute sum over i) + 2")
        .assertType(hasMoniker("int"))
        .assertEval(is(8));

    // "compute" must not occur in "exists"
    mlE("exists i in [1, 2, 3] $compute sum over i$")
        .assertCompileException("'compute' step must not occur in 'exists'");
  }

  @Test
  void testGroupYield() {
    final String ml =
        "from r in [{a=2,b=3}]\n"
            + "group r.a compute {sb = sum over r.b}\n"
            + "yield {a, a2 = a + a, sb}";
    final String expected =
        "from r in [{a = 2, b = 3}]"
            + " group #a r compute {sb = sum over #b r}"
            + " yield {a, a2 = a + a, sb}";
    final String plan =
        "from("
            + "sink join(pat r, exp tuple(tuple(constant(2), constant(3))), "
            + "sink group(key tuple(apply(fnValue nth:0, argCode get(name r))), "
            + "agg aggregate, "
            + "sink collect(tuple(get(name a), "
            + "apply2(fnValue +, get(name a), get(name a)), "
            + "get(name sb))))))";
    ml(ml)
        .assertParse(expected)
        .assertEvalIter(equalsOrdered(list(2, 4, 3)))
        .assertPlan(isCode(plan));
  }

  @Test
  void testJoinGroup() {
    final String ml =
        "from e in [{empno=100,deptno=10}],\n"
            + "  d in [{deptno=10,altitude=3500}]\n"
            + "group e.deptno compute {s = sum over e.empno + d.altitude}";
    final String expected =
        "from e in [{empno = 100, deptno = 10}],"
            + " d in [{deptno = 10, altitude = 3500}]"
            + " group #deptno e"
            + " compute {s = sum over #empno e + #altitude d}";
    ml(ml)
        .assertParse(expected)
        .assertType("{deptno:int, s:int} list")
        .assertEvalIter(equalsOrdered(list(10, 3600)));
  }

  @Test
  void testGroupGroup() {
    final String ml =
        "from r in [{a=2,b=3}]\n"
            + "group {a1 = r.a, b1 = r.b}\n"
            + "group {c2 = a1 + b1} compute {s2 = sum over a1}";
    final String expected =
        "from r in [{a = 2, b = 3}]"
            + " group {a1 = #a r, b1 = #b r}"
            + " group {c2 = a1 + b1} compute {s2 = sum over a1}";
    ml(ml)
        .assertParse(expected)
        .assertType(hasMoniker("{c2:int, s2:int} list"))
        .assertEvalIter(equalsOrdered(list(5, 2)));
  }

  @Test
  void testFromOrderYield() {
    final String ml =
        "from r in [{a=1,b=2},{a=1,b=0},{a=2,b=1}]\n"
            + "  order (DESC r.a, r.b)\n"
            + "  skip 0\n"
            + "  take 4 + 6\n"
            + "  yield {r.a, b10 = r.b * 10}";
    final String expected =
        "from r in"
            + " [{a = 1, b = 2}, {a = 1, b = 0}, {a = 2, b = 1}]"
            + " order (DESC (#a r), #b r)"
            + " skip 0"
            + " take 4 + 6"
            + " yield {#a r, b10 = #b r * 10}";
    ml(ml)
        .assertParse(expected)
        .assertType(hasMoniker("{a:int, b10:int} list"))
        .assertEvalIter(equalsOrdered(list(2, 10), list(1, 0), list(1, 20)));
  }

  @Test
  void testFromEmpty() {
    final String ml = "from";
    final String expected = "from";
    ml(ml)
        .assertParse(expected)
        .assertType(hasMoniker("unit list"))
        .assertEvalIter(equalsOrdered(list()));
  }

  @Test
  void testFromDummy() {
    checkFromDummy(true);
  }

  void checkFromDummy(boolean skip) {
    if (!skip) {
      ml("from i in bag [1,2]").assertType(hasMoniker("int bag"));
    }
    ml("from i in [1,2]").assertType(hasMoniker("int list"));
  }

  @Test
  void testFromBag() {
    ml("from i in bag [1,2]").assertType(hasMoniker("int bag"));
    ml("from i in bag [1,2] where i > 1").assertType(hasMoniker("int bag"));
    ml("from i in bag [1,2] distinct").assertType(hasMoniker("int bag"));
    ml("from i in [1,2] distinct").assertType(hasMoniker("int list"));
    ml("from i in [1,2] group i compute count over i")
        .assertType(hasMoniker("{count:int, i:int} list"));
    ml("from i in bag [1,2] group i compute count over i")
        .assertType(hasMoniker("{count:int, i:int} bag"));
    ml("from (i, j) in bag [(1, 1), (2, 3)]").assertType("{i:int, j:int} bag");
    ml("from i in bag [1], j in bag [true]").assertType("{i:int, j:bool} bag");
    ml("from i in bag [1], j in bag [true] group j").assertType("bool bag");
  }

  @Test
  void testFromEquals() {
    final String ml =
        "from x in [\"a\", \"b\"], y = \"c\", z in [\"d\"]\n"
            + "  yield x ^ y ^ z";
    final String expected =
        "from x in [\"a\", \"b\"], y = \"c\", z in [\"d\"]"
            + " yield x ^ y ^ z";
    ml(ml)
        .assertParse(expected)
        .assertType(hasMoniker("string list"))
        .assertEvalIter(equalsUnordered("acd", "bcd"));
  }

  @Test
  void testFunFrom() {
    final String ml =
        "let\n"
            + "  fun query emps =\n"
            + "    from e in emps\n"
            + "    yield {e.deptno,e.empno,e.ename}\n"
            + "in\n"
            + "  query scott.emps\n"
            + "end";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{deptno:int, empno:int, ename:string} bag");
  }

  @Test
  void testToCoreAndBack() {
    final String[] expressions = {
      "()",
      null,
      "true andalso not false",
      null,
      "true orelse false",
      null,
      "1",
      null,
      "[1, 2]",
      null,
      "1 :: 2 :: []",
      null,
      "1 + ~2",
      null,
      "(\"hello\", 2, 3)",
      null,
      "String.substring (\"hello\", 2, 3)",
      "#substring String (\"hello\", 2, 3)",
      "substring (\"hello\", 4, 1)",
      "#substring String (\"hello\", 4, 1)",
      "{a = 1, b = true, c = \"d\"}",
      null,
      "fn x => 1 + x + 3",
      null,
      "List.tabulate (6, fn i =>"
          + " {i, j = i + 3, s = substring (\"morel\", 0, i)})",
      "#tabulate List (6, fn i =>"
          + " {i = i, j = i + 3, s = #substring String (\"morel\", 0, i)})",
    };
    for (int i = 0; i < expressions.length / 2; i++) {
      String ml = expressions[i * 2];
      String expected = "val it = " + first(expressions[i * 2 + 1], ml);
      ml(ml).assertCore(-1, hasToString(expected));
    }
  }

  @Test
  void testError() {
    ml("fn x y => x + y")
        .assertError(
            "Error: non-constructor applied to argument in pattern: x");
    ml("- case {a=1,b=2,c=3} of {a=x,b=y} => x + y")
        .assertError(
            "Error: case object and rules do not agree [tycon "
                + "mismatch]\n"
                + "  rule domain: {a:[+ ty], b:[+ ty]}\n"
                + "  object: {a:[int ty], b:[int ty], c:[int ty]}\n"
                + "  in expression:\n"
                + "    (case {a=1,b=2,c=3}\n"
                + "      of {a=x,b=y} => x + y)\n");
    ml("fun f {a=x,b=y,...} = x+y")
        .assertError(
            "Error: unresolved flex record (need to know the names of "
                + "ALL the fields\n"
                + " in this context)\n"
                + "  type: {a:[+ ty], b:[+ ty]; 'Z}\n");
    ml("fun f {a=x,...} = x | {b=y,...} = y;")
        .assertError(
            "stdIn:1.24-1.33 Error: can't find function arguments in "
                + "clause\n"
                + "stdIn:1.24-1.33 Error: illegal function symbol in clause\n"
                + "stdIn:1.6-1.37 Error: clauses do not all have same function "
                + "name\n"
                + "stdIn:1.36 Error: unbound variable or constructor: y\n"
                + "stdIn:1.2-1.37 Error: unresolved flex record\n"
                + "   (can't tell what fields there are besides #a)\n");
    ml("fun f {a=x,...} = x | f {b=y,...} = y")
        .assertError(
            "Error: unresolved flex record (need to know the names of "
                + "ALL the fields\n"
                + " in this context)\n"
                + "  type: {a:'Y, b:'Y; 'Z}\n");
    ml("fun f {a=x,...} = x\n"
            + "  | f {b=y,...} = y\n"
            + "  | f {a=x,b=y,c=z} = x+y+z")
        .assertError(
            "stdIn:1.6-3.20 Error: match redundant\n"
                + "          {a=x,b=_,c=_} => ...\n"
                + "    -->   {a=_,b=y,c=_} => ...\n"
                + "    -->   {a=x,b=y,c=z} => ...\n");
    ml("fun f 1 = 1 | f n = n * f (n - 1) | g 2 = 2")
        .assertError(
            "stdIn:3.5-3.46 Error: clauses don't all have same "
                + "function name");
  }
}

// End MainTest.java
