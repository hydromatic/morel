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

import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Literal;
import net.hydromatic.sml.ast.VarDecl;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Assert;
import org.junit.Test;

import java.io.StringReader;
import java.math.BigDecimal;

/**
 * Kick the tires.
 */
public class MainTest {
  @Test public void test() {
    Main.main(new String[0]);
  }

  @Test public void testParse() {
    checkParseLiteral("1", isLiteral(BigDecimal.ONE));
    checkParseLiteral("~3.5", isLiteral(new BigDecimal("-3.5")));
    checkParseLiteral("true", isLiteral(true));
    checkParseLiteral("false", isLiteral(false));
    checkParseLiteral("\"a string\"", isLiteral("a string"));

    checkParseDecl("val x = 5", isAst(VarDecl.class, "val x = 5"));
  }

  private void checkParseLiteral(String ml, Matcher<Literal> matcher) {
    try {
      final Literal literal =
          new SmlParserImpl(new StringReader(ml)).literal();
      Assert.assertThat(literal, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  private void checkParseDecl(String ml, Matcher<VarDecl> matcher) {
    try {
      final VarDecl varDecl =
          new SmlParserImpl(new StringReader(ml)).varDecl();
      Assert.assertThat(varDecl, matcher);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  private static Matcher<Literal> isLiteral(Comparable comparable) {
    return new TypeSafeMatcher<Literal>() {
      protected boolean matchesSafely(Literal literal) {
        return literal.value.equals(comparable);
      }

      @Override public void describeTo(Description description) {
        description.appendText("literal with value " + comparable);
      }
    };
  }

  private static <T extends AstNode> Matcher<T> isAst(Class<T> clazz,
      String expected) {
    return new TypeSafeMatcher<T>() {
      protected boolean matchesSafely(T t) {
        return t.toString().equals(expected);
      }

      @Override public void describeTo(Description description) {
        description.appendText("ast with value " + expected);
      }
    };
  }

}

// End MainTest.java
