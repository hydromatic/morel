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

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.TypeVar;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.util.Util;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Collections;

import static net.hydromatic.morel.Matchers.equalsOrdered;
import static net.hydromatic.morel.Matchers.equalsUnordered;
import static net.hydromatic.morel.Matchers.isLiteral;
import static net.hydromatic.morel.Matchers.isUnordered;
import static net.hydromatic.morel.Matchers.list;
import static net.hydromatic.morel.Matchers.throwsA;
import static net.hydromatic.morel.Matchers.whenAppliedTo;
import static net.hydromatic.morel.Ml.assertError;
import static net.hydromatic.morel.Ml.ml;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Kick the tires.
 */
public class MainTest {

  @Test void testEmptyRepl() {
    final String[] args = new String[0];
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(new byte[0]);
      new Main(args, in, ps, ImmutableMap.of()).run();
    }
    assertThat(out.size(), is(0));
  }

  @Test void testRepl() {
    final String[] args = new String[0];
    final String ml = "val x = 5;\n"
        + "x;\n"
        + "it + 1;\n"
        + "val f = fn x => x + 1;\n"
        + "f;\n"
        + "it x;\n";
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(ml.getBytes());
      new Main(args, in, ps, ImmutableMap.of()).run();
    }
    final String expected = "val x = 5 : int\n"
        + "val it = 5 : int\n"
        + "val it = 6 : int\n"
        + "val f = fn : int -> int\n"
        + "val it = fn : int -> int\n"
        + "val it = 6 : int\n";
    assertThat(out.toString(), is(expected));
  }

  @Test void testParse() {
    ml("1").assertParseLiteral(isLiteral(BigDecimal.ONE, "1"));
    ml("~3.5").assertParseLiteral(isLiteral(new BigDecimal("-3.5"), "~3.5"));
    ml("\"a string\"")
        .assertParseLiteral(isLiteral("a string", "\"a string\""));
    ml("\"\"").assertParseLiteral(isLiteral("", "\"\""));
    ml("#\"a\"").assertParseLiteral(isLiteral('a', "#\"a\""));

    // true and false are variables, not actually literals
    ml("true").assertParseStmt(Ast.Id.class, "true");
    ml("false").assertParseStmt(Ast.Id.class, "false");

    ml("val x = 5").assertParseDecl(Ast.ValDecl.class, "val x = 5");
    ml("val x : int = 5")
        .assertParseDecl(Ast.ValDecl.class, "val x : int = 5");

    ml("val succ = fn x => x + 1")
        .assertParseDecl(Ast.ValDecl.class, "val succ = fn x => x + 1");

    ml("val plus = fn x => fn y => x + y")
        .assertParseDecl(Ast.ValDecl.class, "val plus = fn x => fn y => x + y");

    ml("fun plus x y = x + y")
        .assertParseDecl(Ast.FunDecl.class, "fun plus x y = x + y");

    ml("datatype 'a option = NONE | SOME of 'a")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype 'a option = NONE | SOME of 'a");

    ml("datatype color = RED | GREEN | BLUE")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype color = RED | GREEN | BLUE");
    ml("datatype 'a tree = Empty | Node of 'a * 'a forest\n"
        + "and      'a forest = Nil | Cons of 'a tree * 'a forest")
        .assertParseDecl(Ast.DatatypeDecl.class, "datatype 'a tree = Empty"
            + " | Node of 'a * 'a forest "
            + "and 'a forest = Nil"
            + " | Cons of 'a tree * 'a forest");

    final String ml = "datatype ('a, 'b) choice ="
        + " NEITHER"
        + " | LEFT of 'a"
        + " | RIGHT of 'b"
        + " | BOTH of {a: 'a, b: 'b}";
    ml(ml).assertParseSame();

    // -> is right-associative
    ml("datatype x = X of int -> int -> int").assertParseSame();
    ml("datatype x = X of (int -> int) -> int")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype x = X of (int -> int) -> int");
    ml("datatype x = X of int -> (int -> int)")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype x = X of int -> int -> int");

    ml("datatype x = X of int * int list")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype x = X of int * int list");
    ml("datatype x = X of int * (int list)")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype x = X of int * int list");
    ml("datatype x = X of (int * int) list")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype x = X of (int * int) list");
    ml("datatype x = X of (int, int) pair")
        .assertParseDecl(Ast.DatatypeDecl.class,
            "datatype x = X of (int, int) pair");

    // "*" is non-associative; parentheses cannot be removed
    ml("datatype ('a, 'b, 'c) foo = Triple of 'a * 'b * 'c").assertParseSame();
    ml("datatype ('a, 'b, 'c) foo = Triple of 'a * ('b * 'c)")
        .assertParseSame();
    ml("datatype ('a, 'b, 'c) foo = Triple of ('a * 'b) * 'c")
        .assertParseSame();

    // parentheses creating left precedence, which is the natural precedence for
    // '+', can be removed
    ml("((1 + 2) + 3) + 4")
        .assertParse("1 + 2 + 3 + 4");

    // parentheses creating right precedence can not be removed
    ml("1 + (2 + (3 + (4)))")
        .assertParse("1 + (2 + (3 + 4))");

    ml("1 + (2 + (3 + 4)) = 5 + 5")
        .assertParse("1 + (2 + (3 + 4)) = 5 + 5");

    // :: is right-associative
    ml("1 :: 2 :: 3 :: []")
        .assertParse("1 :: 2 :: 3 :: []");
    ml("((1 :: 2) :: 3) :: []")
        .assertParse("((1 :: 2) :: 3) :: []");
    ml("1 :: (2 :: (3 :: []))")
        .assertParse("1 :: 2 :: 3 :: []");
    ml("1 + 2 :: 3 + 4 * 5 :: 6").assertParseSame();

    // o is left-associative;
    // lower precedence than "=" (4), higher than "andalso" (2)
    ml("f o g").assertParseSame();
    ml("f o g o h").assertParseSame();
    ml("f o (g o h)").assertParseSame();
    ml("(f o g) o h").assertParse("f o g o h");

    ml("a = f o g andalso c = d").assertParseSame();
    ml("a = (f o g) andalso (c = d)").assertParse("a = (f o g) andalso c = d");
    ml("(a = f) o g andalso (c = d)").assertParse("a = f o g andalso c = d");

    // @ is right-associative;
    // lower precedence than "+" (6), higher than "=" (4)
    ml("f @ g").assertParseSame();
    ml("f @ g @ h").assertParseSame();
    ml("f @ (g @ h)").assertParse("f @ g @ h");
    ml("(f @ g) @ h").assertParseSame();

    // ^ is left-associative;
    // lower precedence than "*" (7), higher than "@" (5)
    ml("a * f ^ g @ b").assertParseSame();
    ml("(a * f) ^ (g @ b)").assertParse("a * f ^ (g @ b)");

    ml("(1 + 2, 3, true, (5, 6), 7 = 8)").assertParseSame();

    ml("let val x = 2 in x + (3 + x) + x end").assertParseSame();

    ml("let val x = 2 and y = 3 in x + y end").assertParseSame();
    ml("let val rec x = 2 and y = 3 in x + y end").assertParseSame();
    ml("let val x = 2 and rec y = 3 in x + y end").assertParseSame();

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

    // fn
    ml("fn x => x + 1").assertParseSame();
    ml("fn x => x + (1 + 2)").assertParseSame();
    ml("fn (x, y) => x + y").assertParseSame();
    ml("fn _ => 42").assertParseSame();
    ml("fn x => case x of 0 => 1 | _ => 2").assertParseSame();
    ml("fn () => 42").assertParseSame();

    // apply
    ml("(fn x => x + 1) 3").assertParseSame();
  }

  @Test void testParseComment() {
    ml("1 + (* 2 + *) 3")
        .assertParse("1 + 3");
    ml("1 +\n"
        + "(* 2 +\n"
        + " *) 3").assertParse("1 + 3");
    ml("(* 1 +\n"
        + "2 +\n"
        + "3 *) 5 + 6").assertParse("5 + 6");
  }

  /** Tests that the syntactic sugar "exp.field" is de-sugared to
   * "#field exp". */
  @Test void testParseDot() {
    ml("a . b")
        .assertParse("#b a");
    ml("a . b . c")
        .assertParse("#c (#b a)");
    ml("a . b + c . d")
        .assertParse("#b a + #d c");
    ml("a.b+c.d")
        .assertParse("#b a + #d c");
    ml("(a.b+c.d*e.f.g).h")
        .assertParse("#h (#b a + #d c * #g (#f e))");
    ml("a b")
        .assertParse("a b");
    ml("a b.c")
        .assertParse("a (#c b)");
    ml("a.b c.d e.f")
        .assertParse("#b a (#d c) (#f e)");
    ml("(a.b) (c.d) (e.f)")
        .assertParse("#b a (#d c) (#f e)");
    ml("(a.(b (c.d) (e.f))")
        .assertParseThrows(
            throwsA(ParseException.class,
                containsString("Encountered \" \"(\" \"( \"\" at line 1, column 4.")));
    ml("(a.b c.(d (e.f)))")
        .assertParseThrows(
            throwsA(ParseException.class,
                containsString("Encountered \" \"(\" \"( \"\" at line 1, column 8.")));
  }

  /** Tests that the abbreviated record syntax "{a, e.b, #c e, d = e}"
   * is expanded to "{a = a, b = e.b, c = #c e, d = e}". */
  @Test void testParseAbbreviatedRecord() {
    ml("{a, e.b, #c e, #d (e + 1), e = f + g}")
        .assertParse("{a = a, b = #b e, c = #c e, d = #d (e + 1), e = f + g}");
    ml("{v = a, w = e.b, x = #c e, y = #d (e + 1), z = (#f 2)}")
        .assertParse("{v = a, w = #b e, x = #c e, y = #d (e + 1), z = #f 2}");
    ml("{w = a = b + c, a = b + c}")
        .assertParse("{a = b + c, w = a = b + c}");
    ml("{1}")
        .assertParseThrows(
            throwsA(IllegalArgumentException.class,
                is("cannot derive label for expression 1")));
    ml("{a, b + c}")
        .assertParseThrows(
            throwsA(IllegalArgumentException.class,
                is("cannot derive label for expression b + c")));

    ml("case x of {a = a, b} => a + b")
        .assertParse("case x of {a = a, b = b} => a + b");
    ml("case x of {a, b = 2, ...} => a + b")
        .assertParse("case x of {a = a, b = 2, ...} => a + b");
    ml("fn {a, b = 2, ...} => a + b")
        .assertParse("fn {a = a, b = 2, ...} => a + b");
  }

  /** Tests the name of {@link TypeVar}. */
  @Test void testTypeVarName() {
    assertError(() -> new TypeVar(-1).description(),
        throwsA(IllegalArgumentException.class, nullValue()));
    assertThat(new TypeVar(0).description(), is("'a"));
    assertThat(new TypeVar(1).description(), is("'b"));
    assertThat(new TypeVar(2).description(), is("'c"));
    assertThat(new TypeVar(25).description(), is("'z"));
    assertThat(new TypeVar(26).description(), is("'ba"));
    assertThat(new TypeVar(27).description(), is("'bb"));
    assertThat(new TypeVar(51).description(), is("'bz"));
    assertThat(new TypeVar(52).description(), is("'ca"));
    assertThat(new TypeVar(53).description(), is("'cb"));
    assertThat(new TypeVar(26 * 26 - 1).description(), is("'zz"));
    assertThat(new TypeVar(26 * 26).description(), is("'baa"));
    assertThat(new TypeVar(27 * 26 - 1).description(), is("'baz"));
    assertThat(new TypeVar(26 * 26 * 26 - 1).description(), is("'zzz"));
    assertThat(new TypeVar(26 * 26 * 26).description(), is("'baaa"));
  }

  @Test void testType() {
    ml("1").assertType("int");
    ml("0e0").assertType("real");
    ml("1 + 2").assertType("int");
    ml("1 - 2").assertType("int");
    ml("1 * 2").assertType("int");
    ml("1 / 2").assertType("int");
    ml("1 / ~2").assertType("int");
    ml("1.0 + ~2.0").assertType("real");
    ml("\"\"").assertType("string");
    ml("true andalso false").assertType("bool");
    ml("if true then 1.0 else 2.0").assertType("real");
    ml("(1, true)").assertType("int * bool");
    ml("(1, true, false andalso false)").assertType("int * bool * bool");
    ml("(1)").assertType("int");
    ml("()").assertType("unit");
    ml("{a = 1, b = true}").assertType("{a:int, b:bool}");
    ml("(fn x => x + 1, fn y => y + 1)")
        .assertType("(int -> int) * (int -> int)");
    ml("let val x = 1.0 in x + 2.0 end").assertType("real");

    final String ml = "let val x = 1 in\n"
        + "  let val y = 2 in\n"
        + "    x + y\n"
        + "  end\n"
        + "end";
    ml(ml).assertType("int");
  }

  @Test void testTypeFn() {
    ml("fn x => x + 1").assertType("int -> int");
    ml("fn x => fn y => x + y").assertType("int -> int -> int");
    ml("fn x => case x of 0 => 1 | _ => 2").assertType("int -> int");
    ml("fn x => case x of 0 => \"zero\" | _ => \"nonzero\"")
        .assertType("int -> string");
  }

  @Test void testTypeFnTuple() {
    ml("fn (x, y) => (x + 1, y + 1)")
        .assertType("int * int -> int * int");
    ml("(fn x => x + 1, fn y => y + 1)")
        .assertType("(int -> int) * (int -> int)");
    ml("fn x => fn (y, z) => x + y + z")
        .assertType("int -> int * int -> int");
    ml("fn (x, y) => (x + 1, fn z => (x + z, y + z), y)")
        .assertType("int * int -> int * (int -> int * int) * int");
    ml("fn {a = x, b = y, c} => x + y")
        .assertType("{a:int, b:int, c:'a} -> int");
  }

  @Test void testTypeLetRecFn() {
    final String ml = "let\n"
        + "  val rec f = fn n => if n = 0 then 1 else n * (f (n - 1))\n"
        + "in\n"
        + "  f 5\n"
        + "end";
    ml(ml).assertType("int");

    final String ml2 = ml.replace(" rec", "");
    assertThat(ml2, not(is(ml)));
    ml(ml2).assertError(is("f not found"));

    ml("let val rec x = 1 and y = 2 in x + y end")
        .assertError("Error: fn expression required on rhs of val rec");
  }

  @Test void testRecordType() {
    final String ml = "map #empno [{empno = 10, name = \"Shaggy\"}]";
    ml(ml).assertType("int list");
  }

  @Test void testApply() {
    ml("List.hd [\"abc\"]")
        .assertType("string");
  }

  @Test void testApply2() {
    ml("List.map (fn x => String.size x) [\"abc\", \"de\"]")
        .assertType("int list");
  }

  @Test void testApplyIsMonomorphic() {
    // cannot be typed, since the parameter f is in a monomorphic position
    ml("fn f => (f true, f 0)")
        .assertTypeThrows(
            throwsA(RuntimeException.class,
                is("Cannot deduce type: conflict: int vs bool")));
  }

  @Disabled("disable failing test - enable when we have polymorphic types")
  @Test void testLetIsPolymorphic() {
    // f has been introduced in a let-expression and is therefore treated as
    // polymorphic.
    ml("let val f = fn x => x in (f true, f 0) end")
        .assertType("bool * int");
  }

  @Disabled("disable failing test - enable when we have polymorphic types")
  @Test void testHdIsPolymorphic() {
    ml("(List.hd [1, 2], List.hd [false, true])")
        .assertType("int * bool");
    ml("let\n"
        + "  val h = List.hd\n"
        + "in\n"
        + "   (h [1, 2], h [false, true])\n"
        + "end")
        .assertType("int * bool");
  }

  @Test void testTypeVariable() {
    // constant
    ml("fn _ => 42").assertType("'a -> int");
    ml("(fn _ => 42) 2").assertEval(is(42));
    ml("fn _ => fn _ => 42").assertType("'a -> 'b -> int");

    // identity
    ml("fn x => x").assertType("'a -> 'a");
    ml("(fn x => x) 2").assertEval(is(2));
    ml("(fn x => x) \"foo\"").assertEval(is("foo"));
    ml("(fn x => x) true").assertEval(is(true));
    ml("let fun id x = x in id end").assertType("'a -> 'a");
    ml("fun id x = x").assertType("'a -> 'a");

    // first/second
    ml("fn x => fn y => x").assertType("'a -> 'b -> 'a");
    ml("let fun first x y = x in first end")
        .assertType("'a -> 'b -> 'a");
    ml("fun first x y = x")
        .assertType("'a -> 'b -> 'a");
    ml("fun second x y = y")
        .assertType("'a -> 'b -> 'b");
    ml("fun choose b x y = if b then x else y")
        .assertType("bool -> 'a -> 'a -> 'a");
    ml("fun choose b (x, y) = if b then x else y")
        .assertType("bool -> 'a * 'a -> 'a");
  }

  @Disabled("disable failing test - enable when we have polymorphic types")
  @Test void testExponentialType0() {
    final String ml = "let\n"
        + "  fun f x = (x, x)\n"
        + "in\n"
        + "  f (f 0)\n"
        + "end";
    ml(ml).assertType("xx");
  }

  @Disabled("until type-inference bug is fixed")
  @Test void testExponentialType() {
    final String ml = "let\n"
        + "  fun f x = (x, x, x)\n"
        + "in\n"
        + "   f (f (f (f (f 0))))\n"
        + "end";
    ml(ml).assertType("xx");
  }

  @Disabled("until type-inference bug is fixed")
  @Test void testExponentialType2() {
    final String ml = "fun f x y z = (x, y, z)\n"
        + "val p1 = (f, f, f)\n"
        + "val p2 = (p1, p1, p1)\n"
        + "val p3 = (p2, p2, p2)\n";
    ml(ml).assertType("xx");
  }

  @Test void testEval() {
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
        .assertError("operator and operand don't agree [tycon mismatch]\n"
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
    ml("20 / 3").assertEval(is(6));
    ml("20 / ~3").assertEval(is(-6));

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
        .assertError("operator and operand don't agree [overload conflict]\n"
            + "  operator domain: string * string\n"
            + "  operand:         [int ty] * [int ty]\n");

    // if
    ml("if true then 1 else 2").assertEval(is(1));
    ml("if false then 1 else if true then 2 else 3").assertEval(is(2));
    ml("if false\n"
        + "then\n"
        + "  if true then 2 else 3\n"
        + "else 4").assertEval(is(4));
    ml("if false\n"
        + "then\n"
        + "  if true then 2 else 3\n"
        + "else\n"
        + "  if false then 4 else 5").assertEval(is(5));

    // case
    ml("case 1 of 0 => \"zero\" | _ => \"nonzero\"")
        .assertType("string")
        .assertEval(is("nonzero"));
    ml("case 1 of x => x | y => y")
        .assertError("Error: match redundant\n"
            + "          x => ...\n"
            + "    -->   y => ...\n");
    ml("case 1 of 1 => 2")
        .assertError("Warning: match nonexhaustive\n"
            + "          1 => ...\n");
    ml("let val f = fn x => case x of x => x + 1 in f 2 end").assertEval(is(3));

    // let
    ml("let val x = 1 in x + 2 end").assertEval(is(3));
    ml("let val x = 1 in ~x end").assertEval(is(-1));
    ml("let val x = 1 in ~(abs(~x)) end").assertEval(is(-1));

    // let with a tuple pattern
    ml("let val (x, y) = (1, 2) in x + y end").assertEval(is(3));

    // let with multiple variables
    ml("let val x = 1 and y = 2 in x + y end").assertEval(is(3));
    // let with multiple variables
    ml("let val x = 1 and y = 2 and z = false in\n"
        + "  if z then x else y\n"
        + "end").assertEval(is(2));
    ml("let val x = 1 and y = 2 and z = true in\n"
        + "  if z then x else y\n"
        + "end").assertEval(is(1));
    ml("let val x = 1 and y = 2 and z = true and a = \"foo\" in\n"
        + "  if z then x else y\n"
        + "end").assertEval(is(1));

    // let where variables shadow
    final String letNested = "let\n"
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

  @Test void testLetSequentialDeclarations() {
    // let with sequential declarations
    ml("let val x = 1; val y = x + 1 in x + y end").assertEval(is(3));

    // semicolon is optional
    ml("let val x = 1; val y = x + 1; in x + y end").assertEval(is(3));
    ml("let val x = 1 val y = x + 1 in x + y end").assertEval(is(3));

    // 'and' is executed in parallel, therefore 'x + 1' evaluates to 2, not 4
    ml("let val x = 1; val x = 3 and y = x + 1 in x + y end").assertEval(is(5));

    ml("let val x = 1 and y = x + 2 in x + y end")
        .assertEvalError(throwsA("unbound variable or constructor: x"));

    // let with val and fun
    ml("let fun f x = 1 + x; val x = 2 in f x end").assertEval(is(3));
  }

  /** Tests that in a {@code let} clause, we can see previously defined
   * variables. */
  @Test void testLet2() {
    final String ml = "let\n"
        + "  val x = 1\n"
        + "  val y = x + 2\n"
        + "in\n"
        + "  y + x + 3\n"
        + "end";
    ml(ml).assertEval(is(7));
  }

  /** As {@link #testLet2()}, but using 'and'. */
  @Test void testLet3() {
    final String ml = "let\n"
        + "  val x = 1\n"
        + "  and y = 2\n"
        + "in\n"
        + "  y + x + 3\n"
        + "end";
    ml(ml).assertEval(is(6));
  }

  /** As {@link #testLet3()}, but a tuple is being assigned. */
  @Test void testLet3b() {
    // The intermediate form will have nested tuples, something like this:
    //   val v = (1, (2, 4)) in case v of (x, (y, z)) => y + 3 + x
    final String ml = "let\n"
        + "  val x = 1\n"
        + "  and (y, z) = (2, 4)\n"
        + "in\n"
        + "  y + x + 3\n"
        + "end";
    ml(ml).assertEval(is(6));
  }

  @Test void testLet3c() {
    // The intermediate form will have nested tuples, something like this:
    //   val v = (1, (2, 4)) in case v of (x, (y, z)) => y + 3 + x
    final String ml = "let\n"
        + "  val x1 :: x2 :: xs = [1, 5, 9, 13, 17]\n"
        + "  and (y, z) = (2, 4)\n"
        + "in\n"
        + "  y + x1 + x2 + 3\n"
        + "end";
    ml(ml).assertEval(is(11));
  }

  /** Tests that 'and' assignments occur simultaneously. */
  @Test void testLet4() {
    final String ml = "let\n"
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
  @Test void testLet5() {
    final String ml = "let\n"
        + "  val plus = fn x => fn y => x + y\n"
        + "  val plusTwo = plus 2\n"
        + "in\n"
        + "  plusTwo 3\n"
        + "end";
    ml(ml).assertEval(is(5));
  }

  /** Tests a predicate in a let. */
  @Test void testLet6() {
    final String ml = "let\n"
        + "  fun isZero x = x = 0\n"
        + "in\n"
        + "  fn i => i = 10 andalso isZero i\n"
        + "end";
    // With inlining, we want the plan to simplify to "fn i => false"
    final String plan = "match(i, andalso(apply("
        + "fnValue =, argCode tuple(get(name i), constant(10))), "
        + "apply(fnValue =, argCode tuple(get(name i), constant(0)))))";
    ml(ml)
        .assertEval(whenAppliedTo(0, is(false)))
        .assertEval(whenAppliedTo(10, is(false)))
        .assertEval(whenAppliedTo(15, is(false)))
        .assertPlan(is(plan));
  }

  /** Tests a function in a let. (From <a
   * href="https://www.microsoft.com/en-us/research/wp-content/uploads/2002/07/inline.pdf">Secrets
   * of the Glasgow Haskell Compiler inliner</a> (GHC inlining), section 2.3. */
  @Test void testLet7() {
    final String ml = "fun g (a, b, c) =\n"
        + "  let\n"
        + "    fun f x = x * 3\n"
        + "  in\n"
        + "    f (a + b) - c\n"
        + "  end";
    // With inlining, we want the plan to simplify to
    // "fn (a, b, c) => (a + b) * 3 - c"
    final String plan = "match(v0, apply(fnCode match((a, b, c), "
        + "apply(fnValue -, argCode tuple(apply(fnValue *, argCode "
        + "tuple(apply(fnValue +, argCode "
        + "tuple(get(name a), get(name b))), constant(3))), get(name c)))), "
        + "argCode get(name v0)))";
    ml(ml)
        // g (4, 3, 2) = (4 + 3) * 3 - 2 = 19
        .assertEval(whenAppliedTo(list(4, 3, 2), is(19)))
        .assertPlan(is(plan));
  }

  /** Tests that name capture does not occur during inlining.
   * (Example is from GHC inlining, section 3.) */
  @Test void testNameCapture() {
    final String ml = "fn (a, b) =>\n"
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

  @Disabled("until mutual recursion bug is fixed")
  @Test void testMutualRecursion() {
    final String ml = "let\n"
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

  /** Tests that inlining of mutually recursive functions does not prevent
   * compilation from terminating.
   *
   * <p>Per GHC inlining, (f, g, h), (g, p, q) are strongly connected components
   * of the dependency graph. In each group, the inliner should choose one
   * function as 'loop-breaker' that will not be inlined; say f and q. */
  @Disabled("until mutual recursion bug is fixed")
  @Test void testMutualRecursionComplex() {
    final String ml0 = "let\n"
        + "  fun f i = g (i + 1)\n"
        + "  and g i = h (i + 2) + p (i + 4)\n"
        + "  and h i = if i > 100 then i + 8 else f (i + 16)\n"
        + "  and p i = q (i + 32)\n"
        + "  and q i = if i > 200 then i + 64 else g (i + 128)\n"
        + "in\n"
        + "  g 7\n"
        + "end";
    final String ml = "let\n"
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

  /** Tests that you can use the same variable name in different parts of the
   * program without the types getting confused. */
  @Test void testSameVariableName() {
    final String ml = "List.filter\n"
        + " (fn e => e.x + 2 * e.y > 16)\n"
        + " (List.map\n"
        + "   (fn e => {x = e - 1, y = 10 - e})\n"
        + "   [1, 2, 3, 4, 5])";
    ml(ml).assertEval(isUnordered(list(list(0, 9), list(1, 8))));
  }

  /** As {@link #testSameVariableName()} but both variables are records. */
  @Test void testSameVariableName2() {
    final String ml = "List.filter\n"
        + " (fn e => e.x + 2 * e.y > 16)\n"
        + " (List.map\n"
        + "   (fn e => {x = e.a - 1, y = 10 - e.a})\n"
        + "   [{a=1}, {a=2}, {a=3}, {a=4}, {a=5}])";
    ml(ml).assertEval(isUnordered(list(list(0, 9), list(1, 8))));
  }

  /** Tests a closure that uses one variable "x", called in an environment
   * with a different value of "x" (of a different type, to flush out bugs). */
  @Test void testClosure() {
    final String ml = "let\n"
        + "  val x = \"abc\";\n"
        + "  fun g y = String.size x + y;\n"
        + "  val x = 10\n"
        + "in\n"
        + "  g x\n"
        + "end";
    ml(ml).assertEval(is(13));
  }

  @Test void testEvalFn() {
    ml("(fn x => x + 1) 2").assertEval(is(3));
  }

  @Test void testEvalFnCurried() {
    ml("(fn x => fn y => x + y) 2 3").assertEval(is(5));
  }

  @Test void testEvalFnTuple() {
    ml("(fn (x, y) => x + y) (2, 3)").assertEval(is(5));
  }

  @Test void testEvalFnRec() {
    final String ml = "let\n"
        + "  val rec f = fn n => if n = 0 then 1 else n * f (n - 1)\n"
        + "in\n"
        + "  f 5\n"
        + "end";
    ml(ml).assertEval(is(120));
  }

  @Test void testEvalFnTupleGeneric() {
    ml("(fn (x, y) => x) (2, 3)").assertEval(is(2));
    ml("(fn (x, y) => y) (2, 3)").assertEval(is(3));
  }

  @Test void testRecord() {
    ml("{a = 1, b = {c = true, d = false}}").assertParseSame();
    ml("{a = 1, 1 = 2}").assertParseStmt(Ast.Record.class, "{1 = 2, a = 1}");
    ml("#b {a = 1, b = {c = true, d = false}}").assertParseSame();
    ml("{0=1}").assertError(is("label must be positive"));
    ml("{a = 1, b = true}").assertType("{a:int, b:bool}");
    ml("{b = true, a = 1}").assertType("{a:int, b:bool}");
    ml("{a = 1, b = 2}").assertEval(is(list(1, 2)));
    ml("{a = true, b = ~2}").assertEval(is(list(true, -2)));
    ml("{a = true, b = ~2, c = \"c\"}").assertEval(is(list(true, -2, "c")));
    ml("let val ab = {a = true, b = ~2} in #a ab end").assertEval(is(true));
    ml("{a = true, b = {c = 1, d = 2}}")
        .assertEval(is(list(true, list(1, 2))));
    ml("#a {a = 1, b = true}")
        .assertType("int")
        .assertEval(is(1));
    ml("#b {a = 1, b = true}")
        .assertType("bool")
        .assertEval(is(true));
    ml("#b {a = 1, b = 2}").assertEval(is(2));
    ml("#b {a = 1, b = {x = 3, y = 4}, z = true}").assertEval(is(list(3, 4)));
    ml("#x (#b {a = 1, b = {x = 3, y = 4}, z = true})").assertEval(is(3));
  }

  @Test void testEquals() {
    ml("{b = true, a = 1} = {a = 1, b = true}").assertEval(is(true));
    ml("{b = true, a = 0} = {a = 1, b = true}").assertEval(is(false));
  }

  @Disabled("deduce type of #label")
  @Test void testRecord2() {
    ml("#x #b {a = 1, b = {x = 3, y = 4}, z = true}")
        .assertError("Error: operator and operand don't agree [type mismatch]\n"
            + "  operator domain: {x:'Y; 'Z}\n"
            + "  operand:         {b:'W; 'X} -> 'W\n"
            + "  in expression:\n"
            + "    (fn {x=x,...} => x) (fn {b=b,...} => b)\n");
  }

  @Test void testRecordFn() {
    ml("(fn {a=a1,b=b1} => a1) {a = 1, b = true}")
        .assertType("int")
        .assertEval(is(1));
    ml("(fn {a=a1,b=b1} => b1) {a = 1, b = true}")
        .assertType("bool")
        .assertEval(is(true));
  }

  @Test void testRecordMatch() {
    final String ml = "case {a=1, b=2, c=3}\n"
        + "  of {a=2, b=2, c=3} => 0\n"
        + "   | {a=1, c=x, ...} => x\n"
        + "   | _ => ~1";
    ml(ml).assertEval(is(3));
    ml("fn {} => 0").assertParseSame();
    ml("fn {a=1, ..., c=2}")
        .assertParseThrows(
            throwsA(ParseException.class,
                containsString("Encountered \" \",\" \", \"\" at line 1, "
                    + "column 13.")));
    ml("fn {...} => 0").assertParseSame();
    ml("fn {a = a, ...} => 0").assertParseSame();
    ml("fn {a, b = {c, d}, ...} => 0")
        .assertParse("fn {a = a, b = {c = c, d = d}, ...} => 0");
  }

  @Test void testRecordCase() {
    ml("case {a=2,b=3} of {a=x,b=y} => x * y").assertEval(is(6));
    ml("case {a=2,b=3,c=4} of {a=x,b=y,c=z} => x * y").assertEval(is(6));
    ml("case {a=2,b=3,c=4} of {a=x,b=y,...} => x * y").assertEval(is(6));
    // resolution of flex records is more lenient in case than in fun
    ml("case {a=2,b=3,c=4} of {a=3,...} => 1 | {b=2,...} => 2 | _ => 3")
        .assertEval(is(3));
  }

  @Test void testRecordTuple() {
    ml("{ 1 = true, 2 = 0}").assertType("bool * int");
    ml("{2=0,1=true}").assertType("bool * int");
    ml("{3=0,1=true,11=false}").assertType("{1:bool, 3:int, 11:bool}");
    ml("#1 {1=true,2=0}").assertType("bool");
    ml("#1 (true, 0)").assertType("bool");
    ml("#2 (true, 0)")
        .assertType("int")
        .assertEval(is(0));

    // empty record = () = unit
    ml("()").assertType("unit");
    ml("{}")
        .assertType("unit")
        .assertEval(is(ImmutableList.of()));
  }

  @Test void testList() {
    ml("[1]").assertType("int list");
    ml("[[1]]").assertType("int list list");
    ml("[(1, true), (2, false)]").assertType("(int * bool) list");
    ml("1 :: [2]").assertType("int list");
    ml("1 :: [2, 3]").assertType("int list");
    ml("[1] :: [[2], [3]]").assertType("int list list");
    ml("1 :: []").assertType("int list");
    ml("1 :: 2 :: []")
        .assertType("int list")
        .assertEval(is(list(1, 2)));
    ml("fn [] => 0").assertType("'a list -> int");
  }

  @Disabled("need type annotations")
  @Test void testList2() {
    ml("fn x: 'b list => 0").assertType("'a list -> int");
  }

  /** List length function exercises list pattern-matching and recursion. */
  @Test void testListLength() {
    final String ml = "let\n"
        + "  val rec len = fn x =>\n"
        + "    case x of [] => 0\n"
        + "            | head :: tail => 1 + len tail\n"
        + "in\n"
        + "  len [1, 2, 3]\n"
        + "end";
    ml(ml).assertEval(is(3));
  }

  /** As {@link #testListLength()} but match reversed, which requires
   * cautious matching of :: pattern. */
  @Test void testListLength2() {
    final String ml = "let\n"
        + "  val rec len = fn x =>\n"
        + "    case x of head :: tail => 1 + len tail\n"
        + "            | [] => 0\n"
        + "in\n"
        + "  len [1, 2, 3]\n"
        + "end";
    ml(ml).assertEval(is(3));
  }

  /** As {link {@link #testListLength()} but using {@code fun}. */
  @Test void testListLength3() {
    final String ml = "let\n"
        + "  fun len [] = 0\n"
        + "     | len (head :: tail) = 1 + len tail\n"
        + "in\n"
        + "  len [1, 2, 3]\n"
        + "end";
    ml(ml).assertEval(is(3));
  }

  @Test void testFunUnit() {
    final String ml = "let\n"
        + "  fun one () = 1\n"
        + "in\n"
        + "  one () + 2\n"
        + "end";
    ml(ml).assertEval(is(3));
  }

  @Test void testMatchTuple() {
    final String ml = "let\n"
        + "  val rec sumIf = fn v =>\n"
        + "    case v of (true, n) :: tail => n + sumIf tail\n"
        + "            | (false, _) :: tail => sumIf tail\n"
        + "            | _ => 0\n"
        + "in\n"
        + "  sumIf [(true, 2), (false, 3), (true, 5)]\n"
        + "end";
    ml(ml).assertEval(is(7));
  }

  /** Function declaration. */
  @Test void testFun() {
    final String ml = "let\n"
        + "  fun fact n = if n = 0 then 1 else n * fact (n - 1)\n"
        + "in\n"
        + "  fact 5\n"
        + "end";
    ml(ml).assertEval(is(120));
  }

  /** As {@link #testFun()} but not applied to a value. */
  @Test void testFunValue() {
    final String ml = "let\n"
        + "  fun fact n = if n = 0 then 1 else n * fact (n - 1)\n"
        + "in\n"
        + "  fact\n"
        + "end";
    ml(ml).assertEval(whenAppliedTo(5, is(120)));
  }

  /** As {@link #testFunValue()} but without "let".
   *
   * <p>This is mainly a test for the test framework. We want to handle bindings
   * ({@code fun}) as well as values ({@code let} and {@code fn}). So people can
   * write tests more concisely. */
  @Test void testFunValueSansLet() {
    final String ml = "fun fact n = if n = 0 then 1 else n * fact (n - 1)";
    ml(ml).assertEval(whenAppliedTo(5, is(120)));
  }

  /** As {@link #testFun} but uses case. */
  @Test void testFun2() {
    final String ml = "let\n"
        + "  fun fact n = case n of 0 => 1 | _ => n * fact (n - 1)\n"
        + "in\n"
        + "  fact 5\n"
        + "end";
    ml(ml).assertEval(is(120));
  }

  /** As {@link #testFun} but uses a multi-clause function. */
  @Test void testFun3() {
    final String ml = "let\n"
        + "  fun fact 1 = 1 | fact n = n * fact (n - 1)\n"
        + "in\n"
        + "  fact 5\n"
        + "end";
    final String expected = "let"
        + " fun fact 1 = 1 "
        + "| fact n = n * fact (n - 1) "
        + "in fact 5 end";
    ml(ml).assertParse(expected)
        .assertEval(is(120));
  }

  /** Simultaneous functions. */
  @Test void testFun4() {
    final String ml = "let\n"
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
    ml(ml).assertType("int")
        .assertEval(is(20));
  }

  /** Mutually recursive functions: the definition of 'even' references 'odd'
   * and the definition of 'odd' references 'even'. */
  @Disabled("not working yet")
  @Test void testMutuallyRecursiveFunctions() {
    final String ml = "let\n"
        + "  fun even 0 = true\n"
        + "    | even n = odd (n - 1)\n"
        + "  and odd 0 = false\n"
        + "    | odd n = even (n - 1)\n"
        + "in\n"
        + "  odd 7\n"
        + "end";
    ml(ml).assertType("boolean")
        .assertEval(is(true));
  }

  /** A function with two arguments. */
  @Test void testFunTwoArgs() {
    final String ml = "let\n"
        + "  fun sum x y = x + y\n"
        + "in\n"
        + "  sum 5 3\n"
        + "end";
    ml(ml).assertEval(is(8));
  }

  @Test void testFunRecord() {
    final String ml = ""
        + "fun f {a=x,b=1,...} = x\n"
        + "  | f {b=y,c=2,...} = y\n"
        + "  | f {a=x,b=y,c=z} = x+y+z";
    ml(ml).assertType("{a:int, b:int, c:int} -> int")
        .assertEval(whenAppliedTo(list(1, 2, 3), is(6)));

    final String ml2 = "let\n"
        + "  fun f {a=x,b=1,...} = x\n"
        + "    | f {b=y,c=2,...} = y\n"
        + "    | f {a=x,b=y,c=z} = x+y+z\n"
        + "in\n"
        + "  f {a=1,b=2,c=3}\n"
        + "end";
    ml(ml2).assertEval(is(6));
  }

  @Disabled("not working yet")
  @Test void testDatatype() {
    final String ml = "let\n"
        + "  datatype 'a tree = NODE of 'a tree * 'a tree | LEAF of 'a\n"
        + "in\n"
        + "  NODE (LEAF 1, NODE (LEAF 2, LEAF 3))\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("(INTEGER of int | RATIONAL of int * int | ZERO)")
        .assertEval(is(ImmutableList.of("RATIONAL", ImmutableList.of(2, 3))));
  }

  @Test void testDatatype2() {
    final String ml = "let\n"
        + "  datatype number = ZERO | INTEGER of int | RATIONAL of int * int\n"
        + "in\n"
        + "  RATIONAL (2, 3)\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("number")
        .assertEval(is(ImmutableList.of("RATIONAL", ImmutableList.of(2, 3))));
  }

  @Test void testDatatype3() {
    final String ml = "let\n"
        + "  datatype intoption = NONE | SOME of int;\n"
        + "  val score = fn z => case z of NONE => 0 | SOME x => x\n"
        + "in\n"
        + "  score (SOME 5)\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("int")
        .assertEval(is(5));
  }

  /** As {@link #testDatatype3()} but with {@code fun} rather than {@code fn}
   *  ... {@code case}. */
  @Test void testDatatype3b() {
    final String ml = "let\n"
        + "  datatype intoption = NONE | SOME of int;\n"
        + "  fun score NONE = 0\n"
        + "    | score (SOME x) = x\n"
        + "in\n"
        + "  score (SOME 5)\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("int")
        .assertEval(is(5));
  }

  /** As {@link #testDatatype3b()} but use a nilary type constructor (NONE). */
  @Test void testDatatype3c() {
    final String ml = "let\n"
        + "  datatype intoption = NONE | SOME of int;\n"
        + "  fun score NONE = 0\n"
        + "    | score (SOME x) = x\n"
        + "in\n"
        + "  score NONE\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("int")
        .assertEval(is(0));
  }

  @Test void testDatatype4() {
    final String ml = "let\n"
        + " datatype intlist = NIL | CONS of int * intlist;\n"
        + " fun depth NIL = 0\n"
        + "   | depth CONS (x, y) = 1 + depth y\n"
        + "in\n"
        + " depth NIL\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("int")
        .assertEval(is(0));
  }

  /** As {@link #testDatatype4()} but with deeper expression. */
  @Test void testDatatype4a() {
    final String ml = "let\n"
        + " datatype intlist = NIL | CONS of int * intlist;\n"
        + " fun depth NIL = 0\n"
        + "   | depth CONS (x, y) = 1 + depth y\n"
        + "in\n"
        + " depth (CONS (5, CONS (2, NIL)))\n"
        + "end";
    ml(ml).assertParseSame()
        .assertType("int")
        .assertEval(is(2));
  }

  /** Tests set operators (union, except, intersect).
   * These are Morel extensions to Standard ML,
   * intended to help relational expressions,
   * but not part of the {@code from} expression. */
  @Test void testSetOp() {
    ml("a union b").assertParseSame();
    ml("a union b union c").assertParseSame();
    ml("(a union b) union c").assertParse("a union b union c");
    ml("a union (b union c)").assertParseSame();
    final String ueui = "a union b except c union d intersect e";
    ml(ueui).assertParseSame();
    ml("((a union b) except c) union (d intersect e)")
        .assertParse(ueui);
    ml("from x in emps union depts where deptno = 10")
        .assertParseSame();
    final String fuf =
        "(from x in emps) union (from x in depts where #deptno x = 10)";
    ml(fuf).assertParseSame();
    ml("(from x in emps) union from x in depts where #deptno x = 10")
        .assertParse(fuf);

    ml("[1, 2, 3] union [2, 3, 4]")
        .assertEvalIter(equalsUnordered(1, 2, 3, 2, 3, 4));
  }

  @Test void testFrom() {
    final String ml = "let\n"
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

  @Test void testFromYieldExpression() {
    final String ml = "let\n"
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

  @Test void testFromWhere() {
    final String ml = "let\n"
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

  @Test void testFromNoYield() {
    final String ml = "let\n"
        + "  val emps =\n"
        + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
        + "     {id = 103, name = \"Scooby\", deptno = 30}]\n"
        + "in\n"
        + "  from e in emps where #deptno e = 30\n"
        + "end";
    ml(ml).assertEvalIter(equalsOrdered(list(30, 103, "Scooby")));
  }

  @Test void testFromJoinNoYield() {
    final String ml = "let\n"
        + "  val emps =\n"
        + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
        + "     {id = 101, name = \"Velma\", deptno = 20}]\n"
        + "  val depts =\n"
        + "    [{deptno = 10, name = \"Sales\"}]\n"
        + "in\n"
        + "  from e in emps, d in depts where #deptno e = #deptno d\n"
        + "end";
    ml(ml)
        .assertType("{d:{deptno:int, name:string},"
            + " e:{deptno:int, id:int, name:string}} list")
        .assertEvalIter(
            equalsOrdered(list(list(10, "Sales"), list(10, 100, "Fred"))));
  }

  /** Analogous to SQL "CROSS APPLY" which calls a table-valued function
   * for each row in an outer loop. */
  @Test void testCrossApply() {
    final String ml = "from s in [\"abc\", \"\", \"d\"],\n"
        + "    c in String.explode s\n"
        + "  yield s ^ \":\" ^ String.str c";
    ml(ml).assertEvalIter(equalsOrdered("abc:a", "abc:b", "abc:c", "d:d"));
  }

  @Test void testCrossApplyGroup() {
    final String ml = "from s in [\"abc\", \"\", \"d\"],\n"
        + "    c in String.explode s\n"
        + "  group s compute count = sum of 1";
    ml(ml).assertEvalIter(equalsOrdered(list(3, "abc"), list(1, "d")));
  }

  @Test void testJoinLateral() {
    final String ml = "let\n"
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

  @Test void testFromGroupWithoutCompute() {
    final String ml = "let\n"
        + "  val emps =\n"
        + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
        + "     {id = 101, name = \"Velma\", deptno = 20},\n"
        + "     {id = 102, name = \"Shaggy\", deptno = 10}]\n"
        + "in\n"
        + "  from e in emps group #deptno e\n"
        + "end";
    final String expected = "let val emps = "
        + "[{deptno = 10, id = 100, name = \"Fred\"},"
        + " {deptno = 20, id = 101, name = \"Velma\"},"
        + " {deptno = 10, id = 102, name = \"Shaggy\"}] "
        + "in"
        + " from e in emps"
        + " group deptno = #deptno e "
        + "end";
    ml("val x = " + ml)
        .assertParseDecl(Ast.ValDecl.class, "val x = " + expected);
    // The implicit yield expression is "deptno". It is not a record,
    // "{deptno = deptno}", because there is only one variable defined (the
    // "group" clause defines "deptno" and hides the "e" from the "from"
    // clause).
    ml(ml).assertType("int list")
        .assertEvalIter(equalsUnordered(10, 20));
  }

  /** As {@link #testFromGroupWithoutCompute()} but composite key, therefore
   * result is a list of records. */
  @Test void testFromGroupWithoutCompute2() {
    final String ml = "let\n"
        + "  val emps =\n"
        + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
        + "     {id = 101, name = \"Velma\", deptno = 20},\n"
        + "     {id = 102, name = \"Shaggy\", deptno = 10}]\n"
        + "in\n"
        + "  from e in emps group #deptno e, parity = e.id mod 2\n"
        + "end";
    ml(ml).assertType("{deptno:int, parity:int} list")
        .assertEvalIter(equalsUnordered(list(10, 0), list(20, 1)));
  }

  @Test void testFromGroup() {
    final String ml = "let\n"
        + "  val emps =\n"
        + "    [{id = 100, name = \"Fred\", deptno = 10},\n"
        + "     {id = 101, name = \"Velma\", deptno = 20},\n"
        + "     {id = 102, name = \"Shaggy\", deptno = 10}]\n"
        + "  fun sum [] = 0 | sum (h::t) = h + (sum t)\n"
        + "in\n"
        + "  from e in emps\n"
        + "    group #deptno e\n"
        + "    compute sumId = sum of #id e\n"
        + "end";
    final String expected = "let val emps = "
        + "[{deptno = 10, id = 100, name = \"Fred\"},"
        + " {deptno = 20, id = 101, name = \"Velma\"},"
        + " {deptno = 10, id = 102, name = \"Shaggy\"}]; "
        + "fun sum ([]) = 0 | sum (h :: t) = h + sum t "
        + "in"
        + " from e in emps"
        + " group deptno = #deptno e"
        + " compute sumId = sum of #id e "
        + "end";
    ml("val x = " + ml)
        .assertParseDecl(Ast.ValDecl.class, "val x = " + expected);
    ml(ml).assertType("{deptno:int, sumId:int} list")
        .assertEvalIter(equalsUnordered(list(10, 202), list(20, 101)));
  }

  @Test void testGroupAs() {
    final String ml0 = "from e in emp\n"
        + "group deptno = e.deptno";
    final String ml1 = "from e in emp\n"
        + "group e.deptno";
    final String ml2 = "from e in emp\n"
        + "group #deptno e";
    final String expected = "from e in emp group deptno = #deptno e";
    ml(ml0).assertParse(expected);
    ml(ml1).assertParse(expected);
    ml(ml2).assertParse(expected);

    final String ml3 = "from e in emp\n"
        + "group e, h = f + e.g";
    final String expected3 = "from e in emp group e = e, h = f + #g e";
    ml(ml3).assertParse(expected3);
  }

  @Test void testGroupAs2() {
    ml("from e in emp group e.deptno, e.deptno + e.empid")
        .assertParseThrows(
            throwsA(IllegalArgumentException.class,
                is("cannot derive label for expression #deptno e + #empid e")));
    ml("from e in emp group 1")
        .assertParseThrows(
            throwsA(IllegalArgumentException.class,
                is("cannot derive label for expression 1")));
    ml("from e in emp group e.deptno compute (fn x => x) of e.job")
        .assertParseThrows(
            throwsA(IllegalArgumentException.class,
                is("cannot derive label for expression fn x => x")));
    // Require that we can derive a name for the expression even though there
    // is only one, and therefore we would not use the name.
    // (We could revisit this requirement.)
    ml("from e in emp group compute (fn x => x) of e.job")
        .assertParseThrows(
            throwsA(IllegalArgumentException.class,
                is("cannot derive label for expression fn x => x")));
    ml("from e in [{x = 1, y = 5}]\n"
        + "  group compute sum of e.x")
        .assertType(is("int list"));
    ml("from e in [1, 2, 3]\n"
        + "  group compute sum of e")
        .assertType(is("int list"));
  }

  @Test void testGroupSansOf() {
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "  group compute c = count")
        .assertType(is("int list"))
        .assertEvalIter(equalsUnordered(3));

    ml("from e in [{a = 1, b = 5}, {a = 0, b = 1}, {a = 1, b = 1}]\n"
        + "  group e.a compute rows = (fn x => x)")
        .assertType(is("{a:int, rows:{a:int, b:int} list} list"))
        .assertEvalIter(
            equalsUnordered(
                list(1, list(list(1, 5), list(1, 1))),
                list(0, list(list(0, 1)))));
  }

  /** Tests that Morel throws if there are duplicate names in 'group' or
   * 'compute' clauses. */
  @Test void testGroupDuplicates() {
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x")
        .assertEvalIter(equalsUnordered(0, 1));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x, b = e.x")
        .assertEvalIter(equalsUnordered(list(0, 0), list(1, 1)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x, a = e.y")
        .assertTypeThrows(
            throwsA(RuntimeException.class,
                is("Duplicate field name 'a' in group")));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group e.x, x = e.y")
        .assertTypeThrows(
            throwsA(RuntimeException.class,
                is("Duplicate field name 'x' in group")));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x\n"
        + "compute b = sum of e.y")
        .assertEvalIter(equalsUnordered(list(0, 1), list(1, 6)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x\n"
        + "compute a = sum of e.y")
        .assertTypeThrows(
            throwsA(RuntimeException.class,
                is("Duplicate field name 'a' in group")));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group sum = e.x\n"
        + "compute sum of e.y")
        .assertTypeThrows(
            throwsA(RuntimeException.class,
                is("Duplicate field name 'sum' in group")));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x\n"
        + "compute b = sum of e.y, c = sum of e.x")
        .assertEvalIter(equalsUnordered(list(0, 1, 0), list(1, 6, 2)));
    ml("from e in [{x = 1, y = 5}, {x = 0, y = 1}, {x = 1, y = 1}]\n"
        + "group a = e.x\n"
        + "compute c = sum of e.y, c = sum of e.x")
        .assertTypeThrows(
            throwsA(RuntimeException.class,
                is("Duplicate field name 'c' in group")));
  }

  @Test void testGroupYield() {
    final String ml = "from r in [{a=2,b=3}]\n"
        + "group r.a compute sb = sum of r.b\n"
        + "yield {a, a2 = a + a, sb}";
    final String expected = "from r in [{a = 2, b = 3}]"
        + " group a = #a r compute sb = sum of #b r"
        + " yield {a = a, a2 = a + a, sb = sb}";
    final String plan = "from(r, tuple(tuple(constant(2), constant(3))), "
        + "sink group(key tuple(apply(fnValue nth:0, argCode get(name r))), "
        + "agg aggregate, "
        + "sink collect(tuple(get(name a), "
        + "apply(fnValue +, argCode tuple(get(name a), get(name a))), "
        + "get(name sb)))))";
    ml(ml).assertParse(expected)
        .assertEvalIter(equalsOrdered(list(2, 4, 3)))
        .assertPlan(is(plan));
  }

  @Test void testJoinGroup() {
    final String ml = "from e in [{empno=100,deptno=10}],\n"
        + "  d in [{deptno=10,altitude=3500}]\n"
        + "group e.deptno compute s = sum of e.empno + d.altitude";
    final String expected = "from e in [{deptno = 10, empno = 100}],"
        + " d in [{altitude = 3500, deptno = 10}]"
        + " group deptno = #deptno e"
        + " compute s = sum of #empno e + #altitude d";
    ml(ml).assertParse(expected)
        .assertType("{deptno:int, s:int} list")
        .assertEvalIter(equalsOrdered(list(10, 3600)));
  }

  @Test void testGroupGroup() {
    final String ml = "from r in [{a=2,b=3}]\n"
        + "group a1 = r.a, b1 = r.b\n"
        + "group c2 = a1 + b1 compute s2 = sum of a1";
    final String expected = "from r in [{a = 2, b = 3}]"
        + " group a1 = #a r, b1 = #b r"
        + " group c2 = a1 + b1 compute s2 = sum of a1";
    ml(ml).assertParse(expected)
        .assertType(is("{c2:int, s2:int} list"))
        .assertEvalIter(equalsOrdered(list(5, 2)));
  }

  @Test void testFromOrderYield() {
    final String ml = "from r in [{a=1,b=2},{a=1,b=0},{a=2,b=1}]\n"
        + "  order r.a desc, r.b\n"
        + "  yield {r.a, b10 = r.b * 10}";
    final String expected = "from r in"
        + " [{a = 1, b = 2}, {a = 1, b = 0}, {a = 2, b = 1}]"
        + " order #a r desc, #b r"
        + " yield {a = #a r, b10 = #b r * 10}";
    ml(ml).assertParse(expected)
        .assertType(is("{a:int, b10:int} list"))
        .assertEvalIter(equalsOrdered(list(2, 10), list(1, 0), list(1, 20)));
  }

  @Test void testFromEmpty() {
    final String ml = "from";
    final String expected = "from";
    ml(ml).assertParse(expected)
        .assertType(is("unit list"))
        .assertEvalIter(equalsOrdered(list()));
  }

  @Test void testFromPattern() {
    final String ml = "from (x, y) in [(1,2),(3,4),(3,0)] group sum = x + y";
    final String expected = "from (x, y) in [(1, 2), (3, 4), (3, 0)] "
        + "group sum = x + y";
    ml(ml).assertParse(expected)
        .assertType(is("int list"))
        .assertEvalIter(equalsUnordered(3, 7));
  }

  @Test void testFunFrom() {
    final String ml = "let\n"
        + "  fun query emp =\n"
        + "    from e in emp\n"
        + "    yield {e.deptno,e.empno,e.ename}\n"
        + "in\n"
        + "  query scott.emp\n"
        + "end";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{deptno:int, empno:int, ename:string} list");
  }

  @Test void testToCoreAndBack() {
    final String[] expressions = {
        "()", null,
        "true andalso not false", null,
        "true orelse false", null,
        "1", null,
        "[1, 2]", null,
        "1 :: 2 :: []", null,
        "1 + ~2", null,
        "(\"hello\", 2, 3)", null,
        "String.substring (\"hello\", 2, 3)",
        "#substring String (\"hello\", 2, 3)",
        "{a = 1, b = true, c = \"d\"}", "(1, true, \"d\")",
        "fn x => 1 + x + 3", null,
        "List.tabulate (6, fn i =>"
            + " {i, j = i + 3, s = String.substring (\"morel\", 0, i)})",
        "#tabulate List (6, fn i =>"
            + " (i, i + 3, #substring String (\"morel\", 0, i)))",
    };
    for (int i = 0; i < expressions.length / 2; i++) {
      String ml = expressions[i * 2];
      String expected = Util.first(expressions[i  * 2 + 1], ml);
      ml(ml).assertCoreString(is(expected));
    }
  }

  @Test void testError() {
    ml("fn x y => x + y")
        .assertError(
            "Error: non-constructor applied to argument in pattern: x");
    ml("- case {a=1,b=2,c=3} of {a=x,b=y} => x + y")
        .assertError("Error: case object and rules do not agree [tycon "
            + "mismatch]\n"
            + "  rule domain: {a:[+ ty], b:[+ ty]}\n"
            + "  object: {a:[int ty], b:[int ty], c:[int ty]}\n"
            + "  in expression:\n"
            + "    (case {a=1,b=2,c=3}\n"
            + "      of {a=x,b=y} => x + y)\n");
    ml("fun f {a=x,b=y,...} = x+y")
        .assertError("Error: unresolved flex record (need to know the names of "
            + "ALL the fields\n"
            + " in this context)\n"
            + "  type: {a:[+ ty], b:[+ ty]; 'Z}\n");
    ml("fun f {a=x,...} = x | {b=y,...} = y;")
        .assertError("stdIn:1.24-1.33 Error: can't find function arguments in "
            + "clause\n"
            + "stdIn:1.24-1.33 Error: illegal function symbol in clause\n"
            + "stdIn:1.6-1.37 Error: clauses do not all have same function "
            + "name\n"
            + "stdIn:1.36 Error: unbound variable or constructor: y\n"
            + "stdIn:1.2-1.37 Error: unresolved flex record\n"
            + "   (can't tell what fields there are besides #a)\n");
    ml("fun f {a=x,...} = x | f {b=y,...} = y")
        .assertError("Error: unresolved flex record (need to know the names of "
            + "ALL the fields\n"
            + " in this context)\n"
            + "  type: {a:'Y, b:'Y; 'Z}\n");
    ml("fun f {a=x,...} = x\n"
        + "  | f {b=y,...} = y\n"
        + "  | f {a=x,b=y,c=z} = x+y+z")
        .assertError("stdIn:1.6-3.20 Error: match redundant\n"
            + "          {a=x,b=_,c=_} => ...\n"
            + "    -->   {a=_,b=y,c=_} => ...\n"
            + "    -->   {a=x,b=y,c=z} => ...\n");
    ml("fun f 1 = 1 | f n = n * f (n - 1) | g 2 = 2")
        .assertError("stdIn:3.5-3.46 Error: clauses don't all have same "
            + "function name");
  }

}

// End MainTest.java
