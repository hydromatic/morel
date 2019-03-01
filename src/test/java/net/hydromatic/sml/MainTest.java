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
package net.hydromatic.sml;

import com.google.common.collect.ImmutableList;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.compile.Compiler;
import net.hydromatic.sml.compile.Environment;
import net.hydromatic.sml.compile.Environments;
import net.hydromatic.sml.compile.TypeResolver;
import net.hydromatic.sml.eval.Code;
import net.hydromatic.sml.eval.Codes;
import net.hydromatic.sml.eval.EvalEnv;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

/**
 * Kick the tires.
 */
public class MainTest {
  private void withParser(String ml, Consumer<SmlParserImpl> action) {
    final SmlParserImpl parser = new SmlParserImpl(new StringReader(ml));
    action.accept(parser);
  }

  private void assertParseLiteral(String ml, Matcher<Ast.Literal> matcher) {
    withParser(ml, parser -> {
      try {
        final Ast.Literal literal = parser.literal();
        assertThat(literal, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void assertParseDecl(String ml, Matcher<Ast.VarDecl> matcher) {
    withParser(ml, parser -> {
      try {
        final Ast.VarDecl varDecl = parser.varDecl();
        assertThat(varDecl, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void assertStmt(String ml, Matcher<AstNode> matcher) {
    try {
      final AstNode statement =
          new SmlParserImpl(new StringReader(ml)).statement();
      assertThat(statement, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  /** Checks that an expression can be parsed and returns the identical
   * expression when unparsed. */
  private void assertParseSame(String ml) {
    assertStmt(ml, isAst(AstNode.class, ml));
  }

  /** Matches a literal by value. */
  private static Matcher<Ast.Literal> isLiteral(Comparable comparable) {
    return new TypeSafeMatcher<Ast.Literal>() {
      protected boolean matchesSafely(Ast.Literal literal) {
        return literal.value.equals(comparable);
      }

      public void describeTo(Description description) {
        description.appendText("literal with value " + comparable);
      }
    };
  }

  /** Matches an AST node by its string representation. */
  private static <T extends AstNode> Matcher<T> isAst(Class<? extends T> clazz,
      String expected) {
    return new TypeSafeMatcher<T>() {
      protected boolean matchesSafely(T t) {
        assertThat(clazz.isInstance(t), is(true));
        final String s = Ast.toString(t);
        return s.equals(expected) && s.equals(t.toString());
      }

      public void describeTo(Description description) {
        description.appendText("ast with value " + expected);
      }
    };
  }

  private void withValidate(String ml,
      BiConsumer<Ast.Exp, TypeResolver.TypeMap> action) {
    withParser(ml, parser -> {
      try {
        final Ast.Exp expression = parser.expression();
        final TypeResolver.TypeMap typeMap =
            Compiler.validateExpression(expression);
        action.accept(expression, typeMap);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void withPrepare(String ml,
      Consumer<Compiler.CompiledStatement> action) {
    withParser(ml, parser -> {
      try {
        final AstNode statement = parser.statement();
        final Environment env = Environments.empty();
        final Compiler.CompiledStatement compiled =
            Compiler.prepareStatement(env, statement);
        action.accept(compiled);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void assertType(String ml, Matcher<String> matcher) {
    withValidate(ml, (exp, typeMap) ->
        assertThat(typeMap.getType(exp).description(), matcher));
  }

  private void assertEval(String ml, Matcher<Object> matcher) {
    try {
      final Ast.Exp expression =
          new SmlParserImpl(new StringReader(ml)).expression();
      final TypeResolver.TypeSystem typeSystem = new TypeResolver.TypeSystem();
      final Environment env = Environments.empty();
      final TypeResolver.TypeMap typeMap =
          TypeResolver.deduceType(env, expression, typeSystem);
      final Code code = new Compiler(typeMap).compile(env, expression);
      final EvalEnv evalEnv = Codes.emptyEnv();
      final Object value = code.eval(evalEnv);
      assertThat(value, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @Test public void testEmptyRepl() {
    final String[] args = new String[0];
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(new byte[0]);
      new Main(args, in, ps).run();
    }
    assertThat(out.size(), is(0));
  }

  @Test public void testRepl() {
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
      new Main(args, in, ps).run();
    }
    final String expected = "val x = 5 : int\n"
        + "val it = 5 : int\n"
        + "val it = 6 : int\n"
        + "val f = fn : int -> int\n"
        + "val it = fn : int -> int\n"
        + "val it = 6 : int\n";
    assertThat(out.toString(), is(expected));
  }

  @Test public void testParse() {
    assertParseLiteral("1", isLiteral(BigDecimal.ONE));
    assertParseLiteral("~3.5", isLiteral(new BigDecimal("-3.5")));
    assertParseLiteral("\"a string\"", isLiteral("a string"));

    // true and false are variables, not actually literals
    assertStmt("true", isAst(Ast.Id.class, "true"));
    assertStmt("false", isAst(Ast.Id.class, "false"));

    assertParseDecl("val x = 5", isAst(Ast.VarDecl.class, "val x = 5"));
    assertParseDecl("val x : int = 5",
        isAst(Ast.VarDecl.class, "val x : int = 5"));

    assertParseDecl("val succ = fn x => x + 1",
        isAst(Ast.VarDecl.class, "val succ = fn x => x + 1"));

    assertParseDecl("val plus = fn x => fn y => x + y",
        isAst(Ast.VarDecl.class, "val plus = fn x => fn y => x + y"));

    // parentheses creating left precedence, which is the natural precedence for
    // '+', can be removed
    assertStmt("((1 + 2) + 3) + 4",
        isAst(AstNode.class, "1 + 2 + 3 + 4"));

    // parentheses creating right precedence can not be removed
    assertStmt("1 + (2 + (3 + (4)))",
        isAst(AstNode.class, "1 + (2 + (3 + 4))"));

    assertStmt("1 + (2 + (3 + 4)) = 5 + 5",
        isAst(AstNode.class, "1 + (2 + (3 + 4)) = 5 + 5"));

    // :: is right-associative
    assertStmt("1 :: 2 :: 3 :: []",
        isAst(AstNode.class, "1 :: 2 :: 3 :: []"));
    assertStmt("((1 :: 2) :: 3) :: []",
        isAst(AstNode.class, "((1 :: 2) :: 3) :: []"));
    assertStmt("1 :: (2 :: (3 :: []))",
        isAst(AstNode.class, "1 :: 2 :: 3 :: []"));
    assertParseSame("1 + 2 :: 3 + 4 * 5 :: 6");

    assertParseSame("(1 + 2, 3, true, (5, 6), 7 = 8)");

    assertParseSame("let val x = 2 in x + (3 + x) + x end");

    assertParseSame("let val x = 2 and y = 3 in x + y end");
    assertParseSame("let val rec x = 2 and y = 3 in x + y end");
    assertParseSame("let val x = 2 and rec y = 3 in x + y end");

    // record
    assertParseSame("{a = 1}");
    assertParseSame("{a = 1, b = 2}");
    assertParseSame("{a = 1, b = {c = 2, d = true}, e = true}");

    // if
    assertParseSame("if true then 1 else 2");

    // if ... else if
    assertParseSame("if true then 1 else if false then 2 else 3");

    // case
    assertParseSame("case 1 of 0 => \"zero\" | _ => \"nonzero\"");
    assertParseSame("case {a = 1, b = 2} of {a = 1, ...} => 1");
    assertParseSame("case {a = 1, b = 2} of {...} => 1");
    assertStmt("case {a = 1, b = 2} of {..., a = 3} => 1",
        isAst(AstNode.class, "case {a = 1, b = 2} of {a = 3, ...} => 1"));

    // fn
    assertParseSame("fn x => x + 1");
    assertParseSame("fn x => x + (1 + 2)");
    assertParseSame("fn (x, y) => x + y");
    assertParseSame("fn _ => 42");
    assertParseSame("fn x => case x of 0 => 1 | _ => 2");

    // apply
    assertParseSame("(fn x => x + 1) 3");
  }

  @Test public void testType() {
    assertType("1", is("int"));
    assertType("0e0", is("real"));
    assertType("1 + 2", is("int"));
    assertType("1 - 2", is("int"));
    assertType("1 * 2", is("int"));
    assertType("1 / 2", is("int"));
    assertType("1 / ~2", is("int"));
    assertType("1.0 + ~2.0", is("real"));
    assertType("\"\"", is("string"));
    assertType("true andalso false", is("bool"));
    assertType("if true then 1.0 else 2.0", is("real"));
    assertType("(1, true)", is("int * bool"));
    assertType("(1, true, false andalso false)", is("int * bool * bool"));
    assertType("(1)", is("int"));
    assertType("()", is("unit"));
    assertType("{a = 1, b = true}", is("{a:int, b:bool}"));
    assertType("(fn x => x + 1, fn y => y + 1)",
        is("(int -> int) * (int -> int)"));
    assertType("let val x = 1.0 in x + 2.0 end", is("real"));
    final String ml = "let val x = 1 in\n"
        + "  let val y = 2 in\n"
        + "    x + y\n"
        + "  end\n"
        + "end";
    assertType(ml, is("int"));
  }

  @Test public void testTypeFn() {
    assertType("fn x => x + 1", is("int -> int"));
    assertType("fn x => fn y => x + y", is("int -> int -> int"));
    assertType("fn x => case x of 0 => 1 | _ => 2", is("int -> int"));
    assertType("fn x => case x of 0 => \"zero\" | _ => \"nonzero\"",
        is("int -> string"));
  }

  @Test public void testTypeFnTuple() {
    assertType("fn (x, y) => (x + 1, y + 1)",
        is("int * int -> int * int"));
    assertType("(fn x => x + 1, fn y => y + 1)",
        is("(int -> int) * (int -> int)"));
    assertType("fn x => fn (y, z) => x + y + z",
        is("int -> int * int -> int"));
    assertType("fn (x, y) => (x + 1, fn z => (x + z, y + z), y)",
        is("int * int -> int * (int -> int * int) * int"));
  }

  @Test public void testTypeLetRecFn() {
    final String ml = "let\n"
        + "  val rec f = fn n => if n = 0 then 1 else n * (f (n - n))\n"
        + "in\n"
        + "  f 5\n"
        + "end";
    assertType(ml, is("int"));

    final String ml2 = ml.replace(" rec", "");
    assertThat(ml2, not(is(ml)));
    assertError(ml2, is("fact not found"));

    assertError("let val rec x = 1 and y = 2 in x + y end",
        is("Error: fn expression required on rhs of val rec"));
  }

  @Ignore // enable this test when we have polymorphic type resolution
  @Test public void testType2() {
    // cannot be typed, since the parameter f is in a monomorphic position
    assertType("fn f => (f true, f 0)", is("invalid"));
    // f has been introduced in a let-expression and is therefore treated as
    // polymorphic.
    assertType("let val f = fn x => x in (f true, f 0) end", is("bool * int"));
    assertType("fn _ => 42", is("'a -> int"));
    assertEval("(fn _ => 42) 2", is(42));
  }

  @Test public void testEval() {
    // literals
    assertEval("1", is(1));
    assertEval("~2", is(-2));
    assertEval("\"a string\"", is("a string"));
    assertEval("true", is(true));
    assertEval("~10.25", is(-10.25f));
    assertEval("~10.25e3", is(-10_250f));
    assertEval("~1.25e~3", is(-0.001_25f));
    assertEval("~1.25E~3", is(-0.001_25f));
    assertEval("0e0", is(0f));

    // boolean operators
    assertEval("true andalso false", is(false));
    assertEval("true orelse false", is(true));
    assertEval("false andalso false orelse true", is(true));
    assertEval("false andalso true orelse true", is(true));
    assertEval("(not (true andalso false))", is(true));
    assertEval("not true", is(false));
    assertError("not not true",
        is("operator and operand don't agree [tycon mismatch]\n"
            + "  operator domain: bool\n"
            + "  operand:         bool -> bool"));
    assertEval("not (not true)", is(true));

    // comparisons
    assertEval("1 = 1", is(true));
    assertEval("1 = 2", is(false));
    assertEval("\"a\" = \"a\"", is(true));
    assertEval("\"a\" = \"ab\"", is(false));
    assertEval("1 < 1", is(false));
    assertEval("1 < 2", is(true));
    assertEval("\"a\" < \"a\"", is(false));
    assertEval("\"a\" < \"ab\"", is(true));
    assertEval("1 > 1", is(false));
    assertEval("1 > 2", is(false));
    assertEval("1 > ~2", is(true));
    assertEval("\"a\" > \"a\"", is(false));
    assertEval("\"a\" > \"ab\"", is(false));
    assertEval("\"ac\" > \"ab\"", is(true));
    assertEval("1 <= 1", is(true));
    assertEval("1 <= 2", is(true));
    assertEval("\"a\" <= \"a\"", is(true));
    assertEval("\"a\" <= \"ab\"", is(true));
    assertEval("1 >= 1", is(true));
    assertEval("1 >= 2", is(false));
    assertEval("1 >= ~2", is(true));
    assertEval("\"a\" >= \"a\"", is(true));
    assertEval("\"a\" >= \"ab\"", is(false));
    assertEval("\"ac\" >= \"ab\"", is(true));
    assertEval("1 + 4 = 2 + 3", is(true));
    assertEval("1 + 2 * 2 = 2 + 3", is(true));
    assertEval("1 + 2 * 2 < 2 + 3", is(false));
    assertEval("1 + 2 * 2 > 2 + 3", is(false));

    // arithmetic operators
    assertEval("2 + 3", is(5));
    assertEval("2 + 3 * 4", is(14));
    assertEval("2 * 3 + 4 * 5", is(26));
    assertEval("2 - 3", is(-1));
    assertEval("2 * 3", is(6));
    assertEval("20 / 3", is(6));
    assertEval("20 / ~3", is(-6));

    assertEval("10 mod 3", is(1));
    assertEval("~10 mod 3", is(2));
    assertEval("~10 mod ~3", is(-1));
    assertEval("10 mod ~3", is(-2));
    assertEval("0 mod 3", is(-0));
    assertEval("0 mod ~3", is(0));
    assertEval("19 div 3", is(6));
    assertEval("20 div 3", is(6));
    assertEval("~19 div 3", is(-7));
    assertEval("~18 div 3", is(-6));
    assertEval("19 div ~3", is(-7));
    assertEval("~21 div 3", is(-7));
    assertEval("~21 div ~3", is(7));
    assertEval("0 div 3", is(0));

    // string operators
    assertEval("\"\" ^ \"\"", is(""));
    assertEval("\"1\" ^ \"2\"", is("12"));
    assertError("1 ^ 2",
        is("operator and operand don't agree [overload conflict]\n"
            + "  operator domain: string * string\n"
            + "  operand:         [int ty] * [int ty]\n"));

    // if
    assertEval("if true then 1 else 2", is(1));
    assertEval("if false then 1 else if true then 2 else 3", is(2));
    assertEval("if false\n"
        + "then\n"
        + "  if true then 2 else 3\n"
        + "else 4", is(4));
    assertEval("if false\n"
        + "then\n"
        + "  if true then 2 else 3\n"
        + "else\n"
        + "  if false then 4 else 5", is(5));

    // case
    assertType("case 1 of 0 => \"zero\" | _ => \"nonzero\"", is("string"));
    assertEval("case 1 of 0 => \"zero\" | _ => \"nonzero\"", is("nonzero"));
    assertError("case 1 of x => x | y => y",
        is("Error: match redundant\n"
            + "          x => ...\n"
            + "    -->   y => ...\n"));
    assertError("case 1 of 1 => 2",
        is("Warning: match nonexhaustive\n"
            + "          1 => ...\n"));

    // let
    assertEval("let val x = 1 in x + 2 end", is(3));
    // let with a tuple pattern
    assertEval("let val (x, y) = (1, 2) in x + y end", is(3));

    // let with multiple variables
    assertEval("let val x = 1 and y = 2 in x + y end", is(3));
    // let with multiple variables
    assertEval("let val x = 1 and y = 2 and z = false in\n"
        + "  if z then x else y\n"
        + "end", is(2));
    assertEval("let val x = 1 and y = 2 and z = true in\n"
        + "  if z then x else y\n"
        + "end", is(1));
    assertEval("let val x = 1 and y = 2 and z = true and a = \"foo\" in\n"
        + "  if z then x else y\n"
        + "end", is(1));

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
    assertEval(letNested, is(2 * 3 + 1));

    // let with match
    assertEval("(fn z => let val (x, y) = (z + 1, z + 2) in x + y end) 3",
        is(9));

    // tuple
    assertEval("(1, 2)", is(Arrays.asList(1, 2)));
    assertEval("(1, (2, true))", is(Arrays.asList(1, Arrays.asList(2, true))));
    assertEval("()", is(Collections.emptyList()));
    assertEval("(1, 2, 1, 4)", is(Arrays.asList(1, 2, 1, 4)));
  }

  @Ignore("requires 'let ... ;' and 'fun', not implemented yet")
  @Test public void testLetSequentialDeclarations() {
    // let with sequential declarations
    assertEval("let val x = 1; val y = x + 1 in x + y end", is(3));

    // let with val and fun
    assertEval("let fun f x = 1 + x; val x = 2 in f x end", is(3));
  }

  @Test public void testEvalFn() {
    assertEval("(fn x => x + 1) 2", is(3));
  }

  @Test public void testEvalFnCurried() {
    assertEval("(fn x => fn y => x + y) 2 3", is(5));
  }

  @Test public void testEvalFnTuple() {
    assertEval("(fn (x, y) => x + y) (2, 3)", is(5));
  }

  @Test public void testEvalFnRec() {
    final String ml = "let\n"
        + "  val rec f = fn n => if n = 0 then 1 else n * (f (n - 1))\n"
        + "in\n"
        + "  f 5\n"
        + "end";
    assertEval(ml, is(120));
  }

  @Ignore("requires generics")
  @Test public void testEvalFnTupleGeneric() {
    assertEval("(fn (x, y) => x) (2, 3)", is(2));
    assertEval("(fn (x, y) => y) (3)", is(3));
  }

  @Test public void testRecord() {
    assertParseSame("{a = 1, b = {c = true, d = false}}");
    assertParseSame("{a = 1, 1 = 2}");
    assertParseSame("#b {a = 1, b = {c = true, d = false}}");
    assertError("{0=1}", is("label must be positive"));
    assertType("{a = 1, b = true}", is("{a:int, b:bool}"));
    assertType("{b = true, a = 1}", is("{a:int, b:bool}"));
    assertEval("{a = 1, b = 2}", is(Arrays.asList(1, 2)));
    assertEval("{a = true, b = ~2}", is(Arrays.asList(true, -2)));
    assertEval("{a = true, b = ~2, c = \"c\"}",
        is(Arrays.asList(true, -2, "c")));
    assertEval("{a = true, b = {c = 1, d = 2}}",
        is(Arrays.asList(true, Arrays.asList(1, 2))));
    assertType("#a {a = 1, b = true}", is("int"));
    assertType("#b {a = 1, b = true}", is("bool"));
    assertEval("#a {a = 1, b = true}", is(1));
    assertEval("#b {a = 1, b = true}", is(true));
    assertEval("#b {a = 1, b = 2}", is(2));
    assertEval("#b {a = 1, b = {x = 3, y = 4}, z = true}",
        is(Arrays.asList(3, 4)));
    assertEval("#x (#b {a = 1, b = {x = 3, y = 4}, z = true})",
        is(3));
  }

  @Ignore
  @Test public void testEquals() {
    assertEval("{b = true, a = 1} = {a = 1, b = true}", is(true));
    assertEval("{b = true, a = 0} = {a = 1, b = true}", is(false));
  }

  @Ignore("deduce type of #label")
  @Test public void testRecord2() {
    assertError("#x #b {a = 1, b = {x = 3, y = 4}, z = true}",
        is("Error: operator and operand don't agree [type mismatch]\n"
            + "  operator domain: {x:'Y; 'Z}\n"
            + "  operand:         {b:'W; 'X} -> 'W\n"
            + "  in expression:\n"
            + "    (fn {x=x,...} => x) (fn {b=b,...} => b)\n"));
  }

  @Ignore("deduce type of #label")
  @Test public void testRecordFn() {
    assertType("(fn {a=a1,b=b1} => a1) {a = 1, b = true}", is("int"));
    assertType("(fn {a=a1,b=b1} => b1) {a = 1, b = true}", is("bool"));
    assertEval("(fn {a=a1,b=b1} => a1) {a = 1, b = true}", is("1"));
    assertEval("(fn {a=a1,b=b1} => b1) {a = 1, b = true}", is("true"));
  }

  @Test public void testRecordMatch() {
    final String ml = "case {a=1, b=2, c=3}\n"
        + "  of {a=2, b=2, c=3} => 0\n"
        + "   | {a=1, ..., c=x} => x\n"
        + "   | _ => ~1";
    assertEval(ml, is(3));
  }

  @Test public void testRecordCase() {
    assertEval("case {a=2,b=3} of {a=x,b=y} => x * y",
        is(6));
    assertEval("case {a=2,b=3,c=4} of {a=x,b=y,c=z} => x * y",
        is(6));
    assertEval("case {a=2,b=3,c=4} of {a=x,b=y,...} => x * y",
        is(6));
    // resolution of flex records is more lenient in case than in fun
    assertEval("case {a=2,b=3,c=4} of {a=3,...} => 1 | {b=2,...} => 2 | _ => 3",
        is(3));
  }

  @Test public void testRecordTuple() {
    assertType("{ 1 = true, 2 = 0}", is("bool * int"));
    assertType("{2=0,1=true}", is("bool * int"));
    assertType("{3=0,1=true,11=false}", is("{1:bool, 3:int, 11:bool}"));
    assertType("#1 {1=true,2=0}", is("bool"));
    assertType("#1 (true, 0)", is("bool"));
    assertType("#2 (true, 0)", is("int"));
    assertEval("#2 (true, 0)", is(0));

    // empty record = () = unit
    assertType("()", is("unit"));
    assertType("{}", is("unit"));
    assertEval("{}", is(ImmutableList.of()));
  }

  @Test public void testList() {
    assertType("[1]", is("int list"));
    assertType("[[1]]", is("int list list"));
    assertType("[(1, true), (2, false)]", is("(int * bool) list"));
    assertType("1 :: [2]", is("int list"));
    assertType("1 :: [2, 3]", is("int list"));
    assertType("[1] :: [[2], [3]]", is("int list list"));
    assertType("1 :: []", is("int list"));
    assertType("1 :: 2 :: []", is("int list"));
  }

  @Ignore("need patterns as arguments and generics")
  @Test public void testList2() {
    assertType("fn [] => 0", is("'a list -> int"));
    assertType("fn x: 'b list => 0", is("'a list -> int"));
  }

  /** List length function exercises list pattern-matching and recursion. */
  @Test public void testListLength() {
    final String ml = "let\n"
        + "  val rec len = fn x =>\n"
        + "    case x of [] => 0\n"
        + "            | head :: tail => 1 + (len tail)\n"
        + "in\n"
        + "  len [1, 2, 3]\n"
        + "end";
    assertEval(ml, is(3));
  }

  /** As {@link #testListLength()} but match reversed, which requires
   * cautious matching of :: pattern. */
  @Test public void testListLength2() {
    final String ml = "let\n"
        + "  val rec len = fn x =>\n"
        + "    case x of head :: tail => 1 + (len tail)\n"
        + "            | [] => 0\n"
        + "in\n"
        + "  len [1, 2, 3]\n"
        + "end";
    assertEval(ml, is(3));
  }

  @Test public void testMatchTuple() {
    final String ml = "let\n"
        + "  val rec sumIf = fn v =>\n"
        + "    case v of (true, n) :: tail => n + (sumIf tail)\n"
        + "            | (false, _) :: tail => sumIf tail\n"
        + "            | _ => 0\n"
        + "in\n"
        + "  sumIf [(true, 2), (false, 3), (true, 5)]\n"
        + "end";
    assertEval(ml, is(7));
  }

  @Ignore("fun is not implemented")
  @Test public void testFun() {
    assertType("fun f {a=x,b=1,...} = x\n"
            + "  | f {b=y,c=2,...} = y\n"
            + "  | f {a=x,b=y,c=z} = x+y+z",
        is("val f = fn : {a:int, b:int, c:int} -> int"));
    assertEval("let\n"
            + "  fun f {a=x,b=1,...} = x\n"
            + "    | f {b=y,c=2,...} = y\n"
            + "    | f {a=x,b=y,c=z} = x+y+z\n"
            + "in\n"
            + "  f {a=1,b=2,c=3}\n"
            + "end",
        is(6));
  }

  @Test public void testError() {
    assertError("fn x y => x + y",
        is("Error: non-constructor applied to argument in pattern: x"));
    assertError("let val x = 1 and y = x + 2 in x + y end",
        is("Error: unbound variable or constructor: x"));
    assertError("- case {a=1,b=2,c=3} of {a=x,b=y} => x + y",
        is("Error: case object and rules do not agree [tycon mismatch]\n"
            + "  rule domain: {a:[+ ty], b:[+ ty]}\n"
            + "  object: {a:[int ty], b:[int ty], c:[int ty]}\n"
            + "  in expression:\n"
            + "    (case {a=1,b=2,c=3}\n"
            + "      of {a=x,b=y} => x + y)\n"));
    assertError("fun f {a=x,b=y,...} = x+y",
        is("Error: unresolved flex record (need to know the names of ALL the "
            + "fields\n"
            + " in this context)\n"
            + "  type: {a:[+ ty], b:[+ ty]; 'Z}\n"));
    assertError("fun f {a=x,...} = x | {b=y,...} = y;",
        is("stdIn:1.24-1.33 Error: can't find function arguments in clause\n"
        + "stdIn:1.24-1.33 Error: illegal function symbol in clause\n"
        + "stdIn:1.6-1.37 Error: clauses do not all have same function name\n"
        + "stdIn:1.36 Error: unbound variable or constructor: y\n"
        + "stdIn:1.2-1.37 Error: unresolved flex record\n"
        + "   (can't tell what fields there are besides #a)\n"));
    assertError("fun f {a=x,...} = x | f {b=y,...} = y",
        is("Error: unresolved flex record (need to know the names of ALL the "
            + "fields\n"
            + " in this context)\n"
            + "  type: {a:'Y, b:'Y; 'Z}\n"));
    assertError("fun f {a=x,...} = x\n"
        + "  | f {b=y,...} = y\n"
        + "  | f {a=x,b=y,c=z} = x+y+z",
        is("stdIn:1.6-3.20 Error: match redundant\n"
            + "          {a=x,b=_,c=_} => ...\n"
            + "    -->   {a=_,b=y,c=_} => ...\n"
            + "    -->   {a=x,b=y,c=z} => ...\n"));
  }

  private void assertError(String ml, Matcher<String> matcher) {
    // TODO: execute code, and check error occurs
  }
}

// End MainTest.java
