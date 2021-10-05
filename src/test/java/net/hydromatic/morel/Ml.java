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
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.Analyzer;
import net.hydromatic.morel.compile.CalciteCompiler;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Inliner;
import net.hydromatic.morel.compile.Relationalizer;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.hamcrest.Matcher;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import static net.hydromatic.morel.Matchers.isAst;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import static java.util.Objects.requireNonNull;

/** Fluent test helper. */
class Ml {
  private final String ml;
  private final Map<String, DataSet> dataSetMap;
  private final Map<Prop, Object> propMap;

  Ml(String ml, Map<String, DataSet> dataSetMap,
      Map<Prop, Object> propMap) {
    this.ml = ml;
    this.dataSetMap = ImmutableMap.copyOf(dataSetMap);
    this.propMap = ImmutableMap.copyOf(propMap);
  }

  /** Creates an {@code Ml}. */
  static Ml ml(String ml) {
    return new Ml(ml, ImmutableMap.of(), ImmutableMap.of());
  }

  /** Runs a task and checks that it throws an exception.
   *
   * @param runnable Task to run
   * @param matcher Checks whether exception is as expected
   */
  static void assertError(Runnable runnable,
      Matcher<Throwable> matcher) {
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
    return withParser(parser -> {
      try {
        final Ast.Literal literal = parser.literal();
        assertThat(literal, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertParseDecl(Matcher<Ast.Decl> matcher) {
    return withParser(parser -> {
      try {
        final Ast.Decl decl = parser.decl();
        assertThat(decl, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertParseDecl(Class<? extends Ast.Decl> clazz,
      String expected) {
    return assertParseDecl(isAst(clazz, false, expected));
  }

  Ml assertParseStmt(Matcher<AstNode> matcher) {
    return withParser(parser -> {
      try {
        final AstNode statement = parser.statement();
        assertThat(statement, matcher);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertParseStmt(Class<? extends AstNode> clazz,
      String expected) {
    return assertParseStmt(isAst(clazz, false, expected));
  }

  /** Checks that an expression can be parsed and returns the given string
   * when unparsed. */
  Ml assertParse(String expected) {
    return assertParse(false, expected);
  }

  /** Checks that an expression can be parsed and returns the given string
   * when unparsed, optionally with full parentheses. */
  Ml assertParse(boolean parenthesized, String expected) {
    return assertParseStmt(isAst(AstNode.class, parenthesized, expected));
  }

  /** Checks that an expression can be parsed and returns the identical
   * expression when unparsed. */
  Ml assertParseSame() {
    return assertParse(ml.replaceAll("[\n ]+", " "));
  }

  Ml assertParseThrows(Matcher<Throwable> matcher) {
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statement();
      fail("expected error, got " + statement);
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
    return this;
  }

  private Ml withValidate(BiConsumer<TypeResolver.Resolved, Calcite> action) {
    return withParser(parser -> {
      try {
        final AstNode statement = parser.statement();
        final Calcite calcite = Calcite.withDataSets(dataSetMap);
        final TypeResolver.Resolved resolved =
            Compiles.validateExpression(statement, calcite.foreignValues());
        action.accept(resolved, calcite);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertType(Matcher<String> matcher) {
    return withValidate((resolved, calcite) ->
        assertThat(resolved.typeMap.getType(resolved.exp()).moniker(),
            matcher));
  }

  Ml assertType(String expected) {
    return assertType(is(expected));
  }

  Ml assertTypeThrows(Matcher<Throwable> matcher) {
    assertError(() ->
            withValidate((resolved, calcite) ->
                fail("expected error")),
        matcher);
    return this;
  }

  Ml withPrepare(Consumer<CompiledStatement> action) {
    return withParser(parser -> {
      try {
        final TypeSystem typeSystem = new TypeSystem();
        final AstNode statement = parser.statement();
        final Environment env = Environments.empty();
        final Session session = new Session();
        final CompiledStatement compiled =
            Compiles.prepareStatement(typeSystem, session, env, statement,
                null);
        action.accept(compiled);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertCalcite(Matcher<String> matcher) {
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statement();
      final TypeSystem typeSystem = new TypeSystem();

      final Calcite calcite = Calcite.withDataSets(dataSetMap);
      final TypeResolver.Resolved resolved =
          Compiles.validateExpression(statement, calcite.foreignValues());
      final Environment env = resolved.env;
      final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
      final Resolver resolver = Resolver.of(resolved.typeMap, env);
      final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
      final RelNode rel =
          new CalciteCompiler(typeSystem, calcite)
              .toRel(env, Compiles.toExp(valDecl3));
      requireNonNull(rel);
      final String relString = RelOptUtil.toString(rel);
      assertThat(relString, matcher);
      return this;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  /** Asserts that after parsing the current expression and converting it to
   * Core, the Core string converts to the expected value. Which is usually
   * the original string. */
  public Ml assertCoreString(Matcher<String> matcher) {
    return assertCoreString(null, matcher, null);
  }

  /** As {@link #assertCoreString(Matcher)} but also checks how the Core
   * string has changed after inlining. */
  public Ml assertCoreString(@Nullable Matcher<String> beforeMatcher,
      Matcher<String> matcher,
      @Nullable Matcher<String> inlinedMatcher) {
    final AstNode statement;
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      statement = parser.statement();
    } catch (ParseException parseException) {
      throw new RuntimeException(parseException);
    }

    final Calcite calcite = Calcite.withDataSets(dataSetMap);
    final TypeResolver.Resolved resolved =
        Compiles.validateExpression(statement, calcite.foreignValues());
    final TypeSystem typeSystem = resolved.typeMap.typeSystem;
    final Environment env = resolved.env;
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Resolver resolver = Resolver.of(resolved.typeMap, env);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);

    if (beforeMatcher != null) {
      // "beforeMatcher", if present, checks the expression before any inlining
      assertThat(valDecl3.exp.toString(), beforeMatcher);
    }

    final int inlineCount = inlinedMatcher == null ? 1 : 10;
    final Relationalizer relationalizer = Relationalizer.of(typeSystem, env);
    Core.ValDecl valDecl4 = valDecl3;
    for (int i = 0; i < inlineCount; i++) {
      final Analyzer.Analysis analysis =
          Analyzer.analyze(typeSystem, env, valDecl4);
      final Inliner inliner = Inliner.of(typeSystem, env, analysis);
      final Core.ValDecl valDecl5 = valDecl4;
      valDecl4 = valDecl5.accept(inliner);
      valDecl4 = valDecl4.accept(relationalizer);
      if (i == 0) {
        // "matcher" checks the expression after one inlining pass
        assertThat(valDecl4.exp.toString(), matcher);
      }
      if (valDecl4 == valDecl5) {
        break;
      }
    }
    if (inlinedMatcher != null) {
      // "inlinedMatcher", if present, checks the expression after all inlining
      // passes
      assertThat(valDecl4.exp.toString(), inlinedMatcher);
    }
    return this;
  }

  Ml assertAnalyze(Matcher<Object> matcher) {
    final AstNode statement;
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      statement = parser.statement();
    } catch (ParseException parseException) {
      throw new RuntimeException(parseException);
    }
    final TypeSystem typeSystem = new TypeSystem();

    final Environment env =
        Environments.env(typeSystem, ImmutableMap.of());
    final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, valDecl, typeSystem);
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Resolver resolver = Resolver.of(resolved.typeMap, env);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
    final Analyzer.Analysis analysis =
        Analyzer.analyze(typeSystem, env, valDecl3);
    assertThat(ImmutableSortedMap.copyOf(analysis.map).toString(), matcher);
    return this;
  }

  Ml assertPlan(Matcher<Code> planMatcher) {
    return assertEval(null, planMatcher);
  }

  <E> Ml assertEvalIter(Matcher<Iterable<E>> matcher) {
    return assertEval((Matcher) matcher);
  }

  Ml assertEval(Matcher<Object> resultMatcher) {
    return assertEval(resultMatcher, null);
  }

  Ml assertEval(Matcher<Object> resultMatcher, Matcher<Code> planMatcher) {
    return withValidate((resolved, calcite) -> {
      final Session session = new Session();
      session.map.putAll(propMap);
      eval(session, resolved.env, resolved.typeMap.typeSystem, resolved.node,
          calcite, resultMatcher, planMatcher);
    });
  }

  @CanIgnoreReturnValue
  private Object eval(Session session, Environment env,
      TypeSystem typeSystem, AstNode statement, Calcite calcite,
      @Nullable Matcher<Object> resultMatcher,
      @Nullable Matcher<Code> planMatcher) {
    CompiledStatement compiledStatement =
        Compiles.prepareStatement(typeSystem, session, env, statement, calcite);
    final List<String> output = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    compiledStatement.eval(session, env, output, bindings);
    final Object result;
    if (statement instanceof Ast.Exp) {
      result = bindingValue(bindings, "it");
    } else {
      result = bindings.get(0).value;
    }
    if (resultMatcher != null) {
      assertThat(result, resultMatcher);
    }
    if (planMatcher != null) {
      final String plan = Codes.describe(session.code);
      assertThat(session.code, planMatcher);
    }
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

  Ml assertEvalError(Matcher<Throwable> matcher) {
    try {
      assertEval(notNullValue());
      fail("expected error");
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
    return this;
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
    return new Ml(ml, plus(dataSetMap, name, dataSet), propMap);
  }

  Ml with(Prop prop, Object value) {
    return new Ml(ml, dataSetMap, plus(propMap, prop, value));
  }

  /** Returns a map plus (adding or overwriting) one (key, value) entry. */
  private static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    final ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
    if (map.containsKey(k)) {
      map.forEach((k2, v2) -> {
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
}

// End Ml.java
