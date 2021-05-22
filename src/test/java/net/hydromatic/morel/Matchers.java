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
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Codes;

import org.hamcrest.BaseMatcher;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.Is;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        final String actualMl = literal.toString();
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
        final String s = t.toString();
        return s.equals(expected) && s.equals(t.toString());
      }
    };
  }

  static List<Object> list(Object... values) {
    return Arrays.asList(values);
  }

  static Map<Object, Object> map(Object... keyValues) {
    final LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length / 2; i++) {
      map.put(keyValues[i * 2], keyValues[i + 2 + 1]);
    }
    return map;
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

  /** Creates a Matcher that behaves the same as a given delegate Matcher,
   * but remembers the value that was compared.
   *
   * @param <T> Type of expected item */
  public static <T> LearningMatcher<T> learning(Class<T> type) {
    return new LearningMatcherImpl<>(Is.isA(type));
  }

  /** Creates a Matcher that matches an Applicable, calls it with the given
   * argument, and checks the result. */
  static Matcher<Object> whenAppliedTo(Object arg,
      Matcher<Object> resultMatcher) {
    return new CallingMatcher(arg, resultMatcher);
  }

  /** Matcher that remembers the actual value it was.
   *
   * @param <T> Type of expected item */
  public interface LearningMatcher<T> extends Matcher<T> {
    T get();
  }

  /** Matcher that performs an action when a value is matched.
   *
   * @param <T> Type of expected item */
  private abstract static class MatcherWithConsumer<T> extends BaseMatcher<T> {
    final Matcher<T> matcher;

    MatcherWithConsumer(Matcher<T> matcher) {
      this.matcher = matcher;
    }

    protected abstract void consume(T t);

    @SuppressWarnings("unchecked")
    @Override public boolean matches(Object o) {
      if (matcher.matches(o)) {
        consume((T) o);
        return true;
      } else {
        return false;
      }
    }

    @Override public void describeMismatch(Object o,
        Description description) {
      matcher.describeMismatch(o, description);
    }

    @Override public void describeTo(Description description) {
      matcher.describeTo(description);
    }
  }

  /** Implementation of {@link LearningMatcher}.
   *
   * @param <T> Type of expected item */
  private static class LearningMatcherImpl<T> extends MatcherWithConsumer<T>
      implements LearningMatcher<T> {
    final List<T> list = new ArrayList<>();

    LearningMatcherImpl(Matcher<T> matcher) {
      super(matcher);
    }

    @Override public T get() {
      return list.get(0);
    }

    @Override protected void consume(T t) {
      list.add(t);
    }
  }

  /** Helper for {@link #whenAppliedTo(Object, Matcher)}. */
  private static class CallingMatcher extends BaseMatcher<Object> {
    private final Object arg;
    private final Matcher<Object> resultMatcher;

    CallingMatcher(Object arg, Matcher<Object> resultMatcher) {
      this.arg = arg;
      this.resultMatcher = resultMatcher;
    }

    @Override public void describeTo(Description description) {
      description.appendText("calls with ")
          .appendValue(arg)
          .appendText(" and returns ")
          .appendDescriptionOf(resultMatcher);
    }

    @Override public boolean matches(Object o) {
      if (!(o instanceof Applicable)) {
        return false;
      }
      final Applicable applicable = (Applicable) o;
      final Object result = applicable.apply(Codes.emptyEnv(), arg);
      return resultMatcher.matches(result);
    }
  }
}

// End Matchers.java
