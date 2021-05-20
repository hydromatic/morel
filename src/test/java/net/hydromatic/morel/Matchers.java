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

import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/** Matchers for use in Morel tests. */
public abstract class Matchers {
  private Matchers() {}

  /** Matches a literal by value. */
  @SuppressWarnings("rawtypes")
  static Matcher<Ast.Literal> isLiteral(Comparable comparable, String ml) {
    return new TypeSafeMatcher<Ast.Literal>() {
      protected boolean matchesSafely(Ast.Literal literal) {
        final String actualMl = Ast.toString(literal);
        return literal.value.equals(comparable)
            && actualMl.equals(ml);
      }

      public void describeTo(Description description) {
        description.appendText("literal with value " + comparable
            + " and ML " + ml);
      }
    };
  }

  /** Matches an AST node by its string representation. */
  static <T extends AstNode> Matcher<T> isAst(Class<? extends T> clazz,
      String expected) {
    return new CustomTypeSafeMatcher<T>("ast with value " + expected) {
      protected boolean matchesSafely(T t) {
        assertThat(clazz.isInstance(t), is(true));
        final String s = Ast.toString(t);
        return s.equals(expected) && s.equals(t.toString());
      }
    };
  }

  static List<Object> list(Object... values) {
    return Arrays.asList(values);
  }

  @SafeVarargs
  static <E> Matcher<Iterable<E>> equalsUnordered(E... elements) {
    final Set<E> expectedSet = Sets.newHashSet(elements);
    return new TypeSafeMatcher<Iterable<E>>() {
      protected boolean matchesSafely(Iterable<E> item) {
        //noinspection rawtypes
        return Sets.newHashSet((Iterable) item).equals(expectedSet);
      }

      public void describeTo(Description description) {
        description.appendText("equalsUnordered").appendValue(expectedSet);
      }
    };
  }

  @SafeVarargs
  static <E> Matcher<Iterable<E>> equalsOrdered(E... elements) {
    final List<E> expectedList = Arrays.asList(elements);
    return new TypeSafeMatcher<Iterable<E>>() {
      protected boolean matchesSafely(Iterable<E> item) {
        return Lists.newArrayList(item).equals(expectedList);
      }

      public void describeTo(Description description) {
        description.appendText("equalsOrdered").appendValue(expectedList);
      }
    };
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static <E> Matcher<E> isUnordered(E expected) {
    final E expectedMultiset = expected instanceof Iterable
        ? (E) ImmutableMultiset.copyOf((Iterable) expected)
        : expected;
    return new TypeSafeMatcher<E>() {
      @Override public void describeTo(Description description) {
        description.appendText("equalsOrdered").appendValue(expectedMultiset);
      }

      @Override protected boolean matchesSafely(E actual) {
        final E actualMultiset = expectedMultiset instanceof Multiset
            && (actual instanceof Iterable)
            && !(actual instanceof Multiset)
            ? (E) ImmutableMultiset.copyOf((Iterable) actual)
            : actual;
        return expectedMultiset.equals(actualMultiset);
      }
    };
  }

  static Matcher<Throwable> throwsA(String message) {
    return new CustomTypeSafeMatcher<Throwable>("throwable: " + message) {
      @Override protected boolean matchesSafely(Throwable item) {
        return item.toString().contains(message);
      }
    };
  }

  static <T extends Throwable> Matcher<Throwable> throwsA(Class<T> clazz,
      Matcher<?> messageMatcher) {
    return new CustomTypeSafeMatcher<Throwable>(clazz + " with message "
        + messageMatcher) {
      @Override protected boolean matchesSafely(Throwable item) {
        return clazz.isInstance(item)
            && messageMatcher.matches(item.getMessage());
      }
    };
  }
}

// End Matchers.java
