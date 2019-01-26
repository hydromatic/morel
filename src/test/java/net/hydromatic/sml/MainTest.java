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

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.compile.Compiler;
import net.hydromatic.sml.compile.TypeResolver;
import net.hydromatic.sml.eval.Code;
import net.hydromatic.sml.eval.Environment;
import net.hydromatic.sml.eval.Environments;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * Kick the tires.
 */
public class MainTest {
  private void withParser(String ml, Consumer<SmlParserImpl> action) {
    final SmlParserImpl parser = new SmlParserImpl(new StringReader(ml));
    action.accept(parser);
  }

  private void checkParseLiteral(String ml, Matcher<Ast.Literal> matcher) {
    withParser(ml, parser -> {
      try {
        final Ast.Literal literal = parser.literal();
        assertThat(literal, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void checkParseDecl(String ml, Matcher<Ast.VarDecl> matcher) {
    withParser(ml, parser -> {
      try {
        final Ast.VarDecl varDecl = parser.varDecl();
        assertThat(varDecl, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private void checkStmt(String ml, Matcher<AstNode> matcher) {
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
    checkStmt(ml, isAst(AstNode.class, ml));
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
        return Ast.toString(t).equals(expected);
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

  private void checkEval(String ml, Matcher<Object> matcher) {
    try {
      final Ast.Exp expression =
          new SmlParserImpl(new StringReader(ml)).expression();
      final TypeResolver.TypeSystem typeSystem = new TypeResolver.TypeSystem();
      final Environment env = Environments.empty();
      final TypeResolver.TypeMap typeMap =
          TypeResolver.deduceType(env, expression, typeSystem);
      final Code code = new Compiler(typeMap).compile(env, expression);
      final Object value = code.eval(env);
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
        + "it + 1;\n";
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(ml.getBytes());
      new Main(args, in, ps).run();
    }
    final String expected = "val x = 5 : int\n"
        + "val it = 5 : int\n"
        + "val it = 6 : int\n";
    assertThat(out.toString(), is(expected));
  }

  @Test public void testParse() {
    checkParseLiteral("1", isLiteral(BigDecimal.ONE));
    checkParseLiteral("~3.5", isLiteral(new BigDecimal("-3.5")));
    checkParseLiteral("\"a string\"", isLiteral("a string"));

    // true and false are variables, not actually literals
    checkStmt("true", isAst(Ast.Id.class, "true"));
    checkStmt("false", isAst(Ast.Id.class, "false"));

    checkParseDecl("val x = 5", isAst(Ast.VarDecl.class, "val x = 5"));
    checkParseDecl("val x : int = 5",
        isAst(Ast.VarDecl.class, "val x : int = 5"));

    checkParseDecl("val succ = fn x => x + 1",
        isAst(Ast.VarDecl.class, "val succ = fn x => x + 1"));

    checkParseDecl("val plus = fn x => fn y => x + y",
        isAst(Ast.VarDecl.class, "val plus = fn x => fn y => x + y"));

    // parentheses creating left precedence, which is the natural precedence for
    // '+', can be removed
    checkStmt("((1 + 2) + 3) + 4",
        isAst(AstNode.class, "1 + 2 + 3 + 4"));

    // parentheses creating right precedence can not be removed
    checkStmt("1 + (2 + (3 + (4)))",
        isAst(AstNode.class, "1 + (2 + (3 + 4))"));

    assertParseSame("let val x = 2 in x + (3 + x) + x end");

    assertParseSame("let val x = 2 and y = 3 in x + y end");

    // if
    assertParseSame("if true then 1 else 2");

    // if ... else if
    assertParseSame("if true then 1 else if false then 2 else 3");
  }

  @Test public void testType() {
    assertType("1", is("int"));
    assertType("0e0", is("real"));
    assertType("1 + 2", is("int"));
    assertType("1.0 + ~2.0", is("real"));
    assertType("\"\"", is("string"));
    assertType("true andalso false", is("bool"));
    assertType("if true then 1.0 else 2.0", is("real"));
  }

  @Test public void testTypeFn() {
    assertType("fn x => x + 1", is("int -> int"));
  }

  @Test public void testTypeFn2() {
    assertType("fn x => fn y => x + y", is("int -> int -> int"));
  }

  @Ignore // enable this test when we have polymorphic type resolution
  @Test public void testType2() {
    // cannot be typed, since the parameter f is in a monomorphic position
    assertType("fn f => (f true, f 0)", is("invalid"));
    // f has been introduced in a let-expression and is therefore treated as
    // polymorphic.
    assertType("let val f = fn x => x in (f true, f 0) end", is("bool * int"));
  }

  @Test public void testEval() {
    // literals
    checkEval("1", is(1));
    checkEval("~2", is(-2));
    checkEval("\"a string\"", is("a string"));
    checkEval("true", is(true));
    checkEval("~10.25", is(-10.25f));
    checkEval("~10.25e3", is(-10_250f));
    checkEval("~1.25e~3", is(-0.001_25f));
    checkEval("~1.25E~3", is(-0.001_25f));
    checkEval("0e0", is(0f));

    // operators
    checkEval("2 + 3", is(5));
    checkEval("2 + 3 * 4", is(14));
    checkEval("2 * 3 + 4 * 5", is(26));
    checkEval("2 - 3", is(-1));
    checkEval("2 * 3", is(6));
    checkEval("20 / 3", is(6));
    checkEval("20 / ~3", is(-6));
    checkEval("true andalso false", is(false));
    checkEval("true orelse false", is(true));
    checkEval("false andalso false orelse true", is(true));
    checkEval("false andalso true orelse true", is(true));

    // if
    checkEval("if true then 1 else 2", is(1));
    checkEval("if false then 1 else if true then 2 else 3", is(2));
    checkEval("if false\n"
        + "then\n"
        + "  if true then 2 else 3\n"
        + "else 4", is(4));
    checkEval("if false\n"
        + "then\n"
        + "  if true then 2 else 3\n"
        + "else\n"
        + "  if false then 4 else 5", is(5));

    // let
    checkEval("let val x = 1 in x + 2 end", is(3));

    // let with multiple variables
    checkEval("let val x = 1 and y = 2 in x + y end", is(3));

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
    checkEval(letNested, is(2 * 3 + 1));
  }

  @Test public void testEvalFn() {
    checkEval("(fn x => x + 1) 2", is(3));
  }

  @Test public void testEvalFnCurried() {
    checkEval("(fn x => fn y => x + y) 2 3", is(5));
  }
}

// End MainTest.java
