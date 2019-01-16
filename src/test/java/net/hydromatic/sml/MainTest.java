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
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;

import static org.hamcrest.CoreMatchers.is;

/**
 * Kick the tires.
 */
public class MainTest {
  @Test public void testEmptyRepl() {
    final String[] args = new String[0];
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(new byte[0]);
      new Main(args, in, ps).run();
    }
    Assert.assertThat(out.size(), is(0));
  }

  @Test public void testRepl() {
    final String[] args = new String[0];
    final String ml = "val x = 5;\n"
        + "x;\n";
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(out)) {
      final InputStream in = new ByteArrayInputStream(ml.getBytes());
      new Main(args, in, ps).run();
    }
    final String expected = "val x = 5\n"
        + "x\n";
    Assert.assertThat(out.toString(), is(expected));
  }

  @Test public void testParse() {
    checkParseLiteral("1", isLiteral(BigDecimal.ONE));
    checkParseLiteral("~3.5", isLiteral(new BigDecimal("-3.5")));
    checkParseLiteral("true", isLiteral(true));
    checkParseLiteral("false", isLiteral(false));
    checkParseLiteral("\"a string\"", isLiteral("a string"));

    checkParseDecl("val x = 5", isAst(Ast.VarDecl.class, "val x = 5"));
    checkParseDecl("val x : int = 5", isAst(Ast.VarDecl.class, "val x : int = 5"));
  }

  private void checkParseLiteral(String ml, Matcher<Ast.Literal> matcher) {
    try {
      final Ast.Literal literal =
          new SmlParserImpl(new StringReader(ml)).literal();
      Assert.assertThat(literal, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  private void checkParseDecl(String ml, Matcher<Ast.VarDecl> matcher) {
    try {
      final Ast.VarDecl varDecl =
          new SmlParserImpl(new StringReader(ml)).varDecl();
      Assert.assertThat(varDecl, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
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
  private static <T extends AstNode> Matcher<T> isAst(Class<T> clazz,
      String expected) {
    return new TypeSafeMatcher<T>() {
      protected boolean matchesSafely(T t) {
        return t.toString().equals(expected);
      }

      public void describeTo(Description description) {
        description.appendText("ast with value " + expected);
      }
    };
  }

}

// End MainTest.java
