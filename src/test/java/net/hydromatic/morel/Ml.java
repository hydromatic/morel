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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.Matchers.hasMoniker;
import static net.hydromatic.morel.Matchers.isAst;
import static net.hydromatic.morel.Matchers.throwsA;
import static net.hydromatic.morel.util.Static.anyMatch;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.Analyzer;
import net.hydromatic.morel.compile.CalciteCompiler;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.parse.MorelParseException;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;

/** Fluent test helper. */
class Ml {
  private final String ml;
  @Nullable private final Pos pos;
  private final Map<String, DataSet> dataSetMap;
  private final Map<Prop, Object> propMap;
  private final Tracer tracer;

  Ml(
      String ml,
      @Nullable Pos pos,
      Map<String, DataSet> dataSetMap,
      Map<Prop, Object> propMap,
      Tracer tracer) {
    this.ml = ml;
    this.pos = pos;
    this.dataSetMap = ImmutableMap.copyOf(dataSetMap);
    this.propMap = ImmutableMap.copyOf(propMap);
    this.tracer = tracer;
  }

  /** Creates an {@code Ml}. */
  static Ml ml(String ml) {
    return new Ml(
        ml, null, ImmutableMap.of(), ImmutableMap.of(), Tracers.empty());
  }

  /** Creates an {@code Ml} containing an error position delimited by '$'. */
  static Ml mlE(String ml) {
    return ml(ml, '$');
  }

  /** Creates an {@code Ml} with an error position in it. */
  static Ml ml(String ml, char delimiter) {
    Pair<String, Pos> pair = Pos.split(ml, delimiter, "stdIn");
    return new Ml(
        pair.left,
        pair.right,
        ImmutableMap.of(),
        ImmutableMap.of(),
        Tracers.empty());
  }

  /**
   * Runs a task and checks that it throws an exception.
   *
   * @param runnable Task to run
   * @param matcher Checks whether exception is as expected
   */
  static void assertError(Runnable runnable, Matcher<Throwable> matcher) {
    try {
      runnable.run();
      fail("expected error");
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
  }

  Ml withParser(Consumer<MorelParserImpl> action) {
    final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
    action.accept(parser);
    return this;
  }

  Ml assertParseLiteral(Matcher<Ast.Literal> matcher) {
    return withParser(
        parser -> {
          final Ast.Literal literal = parser.literalEofSafe();
          assertThat(literal, matcher);
        });
  }

  Ml assertParseDecl(Matcher<Ast.Decl> matcher) {
    return withParser(
        parser -> {
          final Ast.Decl decl = parser.declEofSafe();
          assertThat(decl, matcher);
        });
  }

  Ml assertParseDecl(Class<? extends Ast.Decl> clazz, String expected) {
    return assertParseDecl(isAst(clazz, false, expected));
  }

  Ml assertParseStmt(Matcher<AstNode> matcher) {
    return withParser(
        parser -> {
          final AstNode statement = parser.statementEofSafe();
          assertThat(statement, matcher);
        });
  }

  Ml assertParseStmt(Class<? extends AstNode> clazz, String expected) {
    return assertParseStmt(isAst(clazz, false, expected));
  }

  /**
   * Checks that an expression can be parsed and returns the given string when
   * unparsed.
   */
  Ml assertParse(String expected) {
    return assertParse(false, expected);
  }

  /**
   * Checks that an expression can be parsed and returns the given string when
   * unparsed, optionally with full parentheses.
   */
  Ml assertParse(boolean parenthesized, String expected) {
    return assertParseStmt(isAst(AstNode.class, parenthesized, expected));
  }

  /**
   * Checks that an expression can be parsed and returns the identical
   * expression when unparsed.
   */
  Ml assertParseSame() {
    return assertParse(ml.replaceAll("[\n ]+", " "));
  }

  @SuppressWarnings("UnusedReturnValue")
  Ml assertParseThrowsParseException(String message) {
    return assertParseThrows(
        pos -> throwsA(MorelParseException.class, message, pos));
  }

  @SuppressWarnings("UnusedReturnValue")
  Ml assertParseThrowsIllegalArgumentException(Matcher<String> matcher) {
    return assertParseThrows(throwsA(IllegalArgumentException.class, matcher));
  }

  <T> Ml assertParseThrows(Function<Pos, Matcher<T>> matcherSupplier) {
    return assertParseThrows(matcherSupplier.apply(pos));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  <T> Ml assertParseThrows(Matcher<T> matcher) {
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      Pos pos = parser.nextTokenPos();
      parser.zero("stdIn");
      final AstNode statement = parser.statementEofSafe();
      fail("expected error, got " + statement);
    } catch (Throwable e) {
      assertThat(e, (Matcher) matcher);
    }
    return this;
  }

  private Ml withValidate(BiConsumer<TypeResolver.Resolved, Calcite> action) {
    return withParser(
        parser -> {
          parser.zero("stdIn");
          final AstNode statement = parser.statementEofSafe();
          final Calcite calcite = Calcite.withDataSets(dataSetMap);
          try {
            final Consumer<CompileException> ignoreWarnings = w -> {};
            final TypeResolver.Resolved resolved =
                Compiles.validateExpression(
                    statement,
                    propMap,
                    calcite.foreignValues(),
                    ignoreWarnings);
            tracer.handleCompileException(null);
            action.accept(resolved, calcite);
          } catch (TypeResolver.TypeException e) {
            if (!tracer.onTypeException(e)) {
              throw e;
            }
          } catch (CompileException e) {
            if (!tracer.handleCompileException(e)) {
              throw e;
            }
          }
        });
  }

  Ml assertType(Matcher<Type> matcher) {
    return withValidate(
        (resolved, calcite) -> {
          final Type type = resolved.typeMap.getType(resolved.exp());
          final Type type2 = resolved.typeMap.typeSystem.unqualified(type);
          assertThat(type2, matcher);
        });
  }

  Ml assertType(String expected) {
    return assertType(hasMoniker(expected));
  }

  @SuppressWarnings("UnusedReturnValue")
  Ml assertTypeThrowsRuntimeException(String s) {
    return assertTypeThrows(throwsA(RuntimeException.class, is(s)));
  }

  @SuppressWarnings("UnusedReturnValue")
  Ml assertTypeThrowsTypeException(String s) {
    return assertTypeThrows(
        pos -> throwsA(TypeResolver.TypeException.class, s, pos));
  }

  <T> Ml assertTypeThrows(Function<Pos, Matcher<T>> matcherSupplier) {
    return assertTypeThrows(matcherSupplier.apply(pos));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  <T> Ml assertTypeThrows(Matcher<T> matcher) {
    assertError(
        () -> withValidate((resolved, calcite) -> fail("expected error")),
        (Matcher) matcher);
    return this;
  }

  Ml withPrepare(Consumer<CompiledStatement> action) {
    return withParser(
        parser -> {
          final TypeSystem typeSystem = new TypeSystem();
          final AstNode statement = parser.statementEofSafe();
          final Environment env = Environments.empty();
          final Session session = new Session(propMap);
          final List<CompileException> warningList = new ArrayList<>();
          final CompiledStatement compiled =
              Compiles.prepareStatement(
                  typeSystem,
                  session,
                  env,
                  statement,
                  null,
                  warningList::add,
                  tracer);
          action.accept(compiled);
        });
  }

  Ml assertCalcite(Matcher<String> matcher) {
    final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
    final AstNode statement = parser.statementEofSafe();
    final TypeSystem typeSystem = new TypeSystem();

    final Calcite calcite = Calcite.withDataSets(dataSetMap);
    final TypeResolver.Resolved resolved =
        Compiles.validateExpression(
            statement, propMap, calcite.foreignValues(), w -> {});
    final Environment env = resolved.env;
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Session session = null;
    final Resolver resolver = Resolver.of(resolved.typeMap, env, session);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
    assertThat(valDecl3, instanceOf(Core.NonRecValDecl.class));
    final RelNode rel =
        new CalciteCompiler(typeSystem, calcite)
            .toRel(env, Compiles.toExp((Core.NonRecValDecl) valDecl3));
    requireNonNull(rel);
    final String relString = RelOptUtil.toString(rel);
    assertThat(relString, matcher);
    return this;
  }

  /**
   * Asserts that the Core string converts to the expected value.
   *
   * <p>For pass = 2, the Core string is generated after parsing the current
   * expression and converting it to Core. Which is usually the original string.
   */
  Ml assertCore(int pass, Matcher<Core.Decl> expected) {
    final AtomicInteger callCount = new AtomicInteger(0);
    final Consumer<Core.Decl> consumer =
        e -> {
          callCount.incrementAndGet();
          assertThat(e, expected);
        };
    final Tracer tracer = Tracers.withOnCore(this.tracer, pass, consumer);

    final Consumer<Object> consumer2 =
        o ->
            assertThat(
                "core(" + pass + ") was never called",
                callCount.get(),
                greaterThan(0));
    final Tracer tracer2 = Tracers.withOnResult(tracer, consumer2);

    return withTracer(tracer2).assertEval();
  }

  /**
   * As {@link #assertCore(int, Matcher)} but also checks how the Core string
   * has changed after inlining.
   */
  public Ml assertCoreString(
      @Nullable Matcher<Core.Decl> beforeMatcher,
      Matcher<Core.Decl> matcher,
      @Nullable Matcher<Core.Decl> inlinedMatcher) {
    return with(Prop.INLINE_PASS_COUNT, 10)
        .with(Prop.RELATIONALIZE, true)
        .assertCore(0, beforeMatcher)
        .assertCore(2, matcher)
        .assertCore(-1, inlinedMatcher);
  }

  Ml assertAnalyze(Matcher<Map<Core.NamedPat, Analyzer.Use>> matcher) {
    final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
    final AstNode statement = parser.statementEofSafe();
    final TypeSystem typeSystem = new TypeSystem();

    final Session session = null;
    final Environment env =
        Environments.env(typeSystem, session, ImmutableMap.of());
    final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
    final Consumer<CompileException> ignoreWarnings = w -> {};
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, valDecl, typeSystem, ignoreWarnings);
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
    final Analyzer.Analysis analysis =
        Analyzer.analyze(typeSystem, env, valDecl3);
    assertThat(ImmutableSortedMap.copyOf(analysis.map), matcher);
    return this;
  }

  Ml assertMatchCoverage(MatchCoverage expectedCoverage) {
    final Function<Pos, Matcher<Throwable>> exceptionMatcherFactory;
    final Matcher<List<? extends Throwable>> warningsMatcher;
    switch (expectedCoverage) {
      case OK:
        // Expect no errors or warnings
        exceptionMatcherFactory = null;
        warningsMatcher = isEmptyList();
        break;
      case REDUNDANT:
        exceptionMatcherFactory = pos -> throwsA("match redundant", pos);
        warningsMatcher = isEmptyList();
        break;
      case NON_EXHAUSTIVE_AND_REDUNDANT:
        exceptionMatcherFactory =
            pos -> throwsA("match nonexhaustive and redundant", pos);
        warningsMatcher = isEmptyList();
        break;
      case NON_EXHAUSTIVE:
        exceptionMatcherFactory = null;
        warningsMatcher =
            new CustomTypeSafeMatcher<List<? extends Throwable>>(
                "non-empty list") {
              @Override
              protected boolean matchesSafely(List<? extends Throwable> list) {
                return anyMatch(
                    list,
                    e ->
                        e instanceof CompileException
                            && e.getMessage().equals("match nonexhaustive"));
              }
            };
        break;
      default:
        // Java doesn't know the switch is exhaustive; how ironic
        throw new AssertionError(expectedCoverage);
    }
    return withResultMatcher(notNullValue())
        .withWarningsMatcher(warningsMatcher)
        .withExceptionMatcher(exceptionMatcherFactory)
        .assertEval();
  }

  private static <E> Matcher<List<? extends E>> isEmptyList() {
    return new CustomTypeSafeMatcher<List<? extends E>>("empty list") {
      @Override
      protected boolean matchesSafely(List<? extends E> list) {
        return list.isEmpty();
      }
    };
  }

  Ml assertPlan(Matcher<Code> planMatcher) {
    final Consumer<Code> consumer = code -> assertThat(code, planMatcher);
    final Tracer tracer = Tracers.withOnPlan(this.tracer, consumer);
    return withTracer(tracer).assertEval();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  <E> Ml assertEvalIter(Matcher<Iterable<E>> matcher) {
    return assertEval((Matcher) matcher);
  }

  Ml assertEval(Matcher<Object> resultMatcher) {
    return withResultMatcher(resultMatcher).assertEval();
  }

  Ml assertEval() {
    return withValidate(
        (resolved, calcite) -> {
          final Session session = new Session(propMap);
          eval(
              session,
              resolved.env,
              resolved.typeMap.typeSystem,
              resolved.node,
              calcite);
        });
  }

  Ml assertEvalThrows(
      Function<Pos, Matcher<Throwable>> exceptionMatcherFactory) {
    return withExceptionMatcher(exceptionMatcherFactory).assertEval();
  }

  @CanIgnoreReturnValue
  private <E extends Throwable> Object eval(
      Session session,
      Environment env,
      TypeSystem typeSystem,
      AstNode statement,
      Calcite calcite) {
    final List<Binding> bindings = new ArrayList<>();
    final List<Throwable> warningList = new ArrayList<>();
    try {
      CompiledStatement compiledStatement =
          Compiles.prepareStatement(
              typeSystem,
              session,
              env,
              statement,
              calcite,
              warningList::add,
              tracer);
      session.withoutHandlingExceptions(
          session1 ->
              compiledStatement.eval(session1, env, line -> {}, bindings::add));
      tracer.onException(null);
    } catch (RuntimeException e) {
      if (!tracer.onException(e)) {
        throw e;
      }
    }
    tracer.onWarnings(warningList);
    final Object result;
    if (statement instanceof Ast.Exp) {
      result = bindingValue(bindings, "it");
    } else if (bindings.size() == 1) {
      result = bindings.get(0).value;
    } else {
      Map<String, Object> map = new LinkedHashMap<>();
      bindings.forEach(
          b -> {
            if (!b.id.name.equals("it")) {
              map.put(b.id.name, b.value);
            }
          });
      result = map;
    }
    tracer.onResult(result);
    tracer.onPlan(session.code);
    return result;
  }

  private Object bindingValue(List<Binding> bindings, String name) {
    for (Binding binding : bindings) {
      if (binding.id.name.equals(name)) {
        return binding.value;
      }
    }
    return null;
  }

  Ml assertCompileException(
      Function<Pos, Matcher<CompileException>> matcherSupplier) {
    assertThat(pos, notNullValue());
    return withResultMatcher(notNullValue())
        .withCompileExceptionMatcher(matcherSupplier)
        .assertEval();
  }

  Ml assertCompileException(String message) {
    return assertCompileException(
        pos -> throwsA(CompileException.class, message, pos));
  }

  Ml assertEvalError(Function<Pos, Matcher<Throwable>> matcherSupplier) {
    assertThat(pos, notNullValue());
    return withResultMatcher(notNullValue())
        .withExceptionMatcher(matcherSupplier)
        .assertEval();
  }

  Ml assertEvalWarnings(Matcher<List<? extends Throwable>> warningsMatcher) {
    return withResultMatcher(notNullValue())
        .withWarningsMatcher(warningsMatcher)
        .assertEval();
  }

  Ml assertEvalSame() {
    final Matchers.LearningMatcher<Object> resultMatcher =
        Matchers.learning(Object.class);
    return with(Prop.HYBRID, false)
        .assertEval(resultMatcher)
        .with(Prop.HYBRID, true)
        .assertEval(Matchers.isUnordered(resultMatcher.get()));
  }

  Ml assertError(Matcher<String> matcher) {
    // TODO: execute code, and check error occurs
    return this;
  }

  Ml assertError(String expected) {
    return assertError(is(expected));
  }

  Ml withBinding(String name, DataSet dataSet) {
    return new Ml(ml, pos, plus(dataSetMap, name, dataSet), propMap, tracer);
  }

  Ml with(Prop prop, Object value) {
    return new Ml(ml, pos, dataSetMap, plus(propMap, prop, value), tracer);
  }

  Ml withTracer(Tracer tracer) {
    return new Ml(ml, pos, dataSetMap, propMap, tracer);
  }

  Ml withTypeExceptionMatcher(Matcher<Throwable> matcher) {
    final Consumer<TypeResolver.TypeException> consumer =
        o -> assertThat(o, matcher);
    return withTracer(Tracers.withOnTypeException(tracer, consumer));
  }

  Ml withResultMatcher(Matcher<Object> matcher) {
    final Consumer<Object> consumer = o -> assertThat(o, matcher);
    return withTracer(Tracers.withOnResult(this.tracer, consumer));
  }

  Ml withWarningsMatcher(Matcher<List<? extends Throwable>> matcher) {
    final Consumer<List<? extends Throwable>> consumer =
        warningList -> assertThat(warningList, matcher);
    return withTracer(Tracers.withOnWarnings(this.tracer, consumer));
  }

  Ml withExceptionMatcher(
      @Nullable Function<Pos, Matcher<Throwable>> matcherFactory) {
    return withTracer(
        Tracers.withOnException(
            this.tracer, exceptionConsumer(matcherFactory)));
  }

  Ml withCompileExceptionMatcher(
      @Nullable Function<Pos, Matcher<CompileException>> matcherFactory) {
    return withTracer(
        Tracers.withOnCompileException(
            this.tracer, exceptionConsumer(matcherFactory)));
  }

  private <T extends Throwable> Consumer<T> exceptionConsumer(
      Function<Pos, Matcher<T>> exceptionMatcherFactory) {
    @Nullable
    Matcher<T> matcher =
        exceptionMatcherFactory == null
            ? null
            : exceptionMatcherFactory.apply(pos);
    return e -> {
      if (e != null) {
        if (matcher != null) {
          assertThat(e, matcher);
        } else {
          if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
          }
          if (e instanceof Error) {
            throw (Error) e;
          }
          throw new RuntimeException(e);
        }
      } else {
        if (matcher != null) {
          fail("expected exception, but none was thrown");
        }
      }
    };
  }

  /** Returns a map plus (adding or overwriting) one (key, value) entry. */
  private static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    final ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
    if (map.containsKey(k)) {
      map.forEach(
          (k2, v2) -> {
            if (!k2.equals(k)) {
              builder.put(k, v);
            }
          });
    } else {
      builder.putAll(map);
    }
    builder.put(k, v);
    return builder.build();
  }

  /**
   * Whether a list of patterns is exhaustive (covers all possible input
   * values), redundant (covers some input values more than once), both or
   * neither.
   */
  enum MatchCoverage {
    NON_EXHAUSTIVE,
    REDUNDANT,
    NON_EXHAUSTIVE_AND_REDUNDANT,
    OK
  }
}

// End Ml.java
