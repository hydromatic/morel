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

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.CalciteCompiler;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Inliner;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeMap;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

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
    return assertParseDecl(isAst(clazz, expected));
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
    return assertParseStmt(isAst(clazz, expected));
  }

  /** Checks that an expression can be parsed and returns the given string
   * when unparsed. */
  Ml assertParse(String expected) {
    return assertParseStmt(AstNode.class, expected);
  }

  /** Checks that an expression can be parsed and returns the identical
   * expression when unparsed. */
  Ml assertParseSame() {
    return assertParse(ml.replaceAll("[\n ]+", " "));
  }

  Ml assertParseThrows(Matcher<Throwable> matcher) {
    try {
      final AstNode statement =
          new MorelParserImpl(new StringReader(ml)).statement();
      fail("expected error, got " + statement);
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
    return this;
  }

  private Ml withValidate(BiConsumer<Ast.Exp, TypeMap> action) {
    return withParser(parser -> {
      try {
        final Ast.Exp expression = parser.expression();
        final Calcite calcite = Calcite.withDataSets(dataSetMap);
        final TypeResolver.Resolved resolved =
            Compiles.validateExpression(expression, calcite.foreignValues());
        final Ast.ValDecl valDecl = (Ast.ValDecl) resolved.node;
        final Ast.Exp resolvedExp = valDecl.valBinds.get(0).e;
        action.accept(resolvedExp, resolved.typeMap);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertType(Matcher<String> matcher) {
    return withValidate((exp, typeMap) ->
        assertThat(typeMap.getType(exp).moniker(), matcher));
  }

  Ml assertType(String expected) {
    return assertType(is(expected));
  }

  Ml assertTypeThrows(Matcher<Throwable> matcher) {
    assertError(() ->
            withValidate((exp, typeMap) -> fail("expected error")),
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
            Compiles.prepareStatement(typeSystem, session, env, statement);
        action.accept(compiled);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  Ml assertCalcite(Matcher<String> matcher) {
    try {
      final Ast.Exp e = new MorelParserImpl(new StringReader(ml)).expression();
      final TypeSystem typeSystem = new TypeSystem();

      final Calcite calcite = Calcite.withDataSets(dataSetMap);
      final Environment env =
          Environments.env(typeSystem, calcite.foreignValues());
      final Ast.ValDecl valDecl = Compiles.toValDecl(e);
      final TypeResolver.Resolved resolved =
          TypeResolver.deduceType(env, valDecl, typeSystem);
      final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
      final Resolver resolver = new Resolver(resolved.typeMap);
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
  public void assertCoreString(Matcher<String> matcher) {
    final Ast.Exp e;
    try {
      e = new MorelParserImpl(new StringReader(ml)).expression();
    } catch (ParseException parseException) {
      throw new RuntimeException(parseException);
    }
    final TypeSystem typeSystem = new TypeSystem();

    final Environment env =
        Environments.env(typeSystem, ImmutableMap.of());
    final Ast.ValDecl valDecl = Compiles.toValDecl(e);
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, valDecl, typeSystem);
    final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
    final Resolver resolver = new Resolver(resolved.typeMap);
    final Core.ValDecl valDecl3 = resolver.toCore(valDecl2);
    final Core.ValDecl valDecl4 = valDecl3.accept(Inliner.of(typeSystem, env));
    final String coreString = valDecl4.e.toString();
    assertThat(coreString, matcher);
  }

  Ml assertPlan(Matcher<String> planMatcher) {
    return assertEval(null, planMatcher);
  }

  <E> Ml assertEvalIter(Matcher<Iterable<E>> matcher) {
    return assertEval((Matcher) matcher);
  }

  Ml assertEval(Matcher<Object> resultMatcher) {
    return assertEval(resultMatcher, null);
  }

  Ml assertEval(Matcher<Object> resultMatcher, Matcher<String> planMatcher) {
    try {
      final Ast.Exp e = new MorelParserImpl(new StringReader(ml)).expression();
      final TypeSystem typeSystem = new TypeSystem();
      final Calcite calcite = Calcite.withDataSets(dataSetMap);
      final Environment env =
          Environments.env(typeSystem, calcite.foreignValues());
      final Session session = new Session();
      session.map.putAll(propMap);
      eval(session, env, typeSystem, e, resultMatcher, planMatcher);
      return this;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @CanIgnoreReturnValue
  private Object eval(Session session, Environment env,
      TypeSystem typeSystem, Ast.Exp e,
      @Nullable Matcher<Object> resultMatcher,
      @Nullable Matcher<String> planMatcher) {
    CompiledStatement compiledStatement =
        Compiles.prepareStatement(typeSystem, session, env, e);
    final List<String> output = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    compiledStatement.eval(session, env, output, bindings);
    final Object result = getIt(bindings);
    if (resultMatcher != null) {
      assertThat(result, resultMatcher);
    }
    if (planMatcher != null) {
      final String plan = Codes.describe(session.code);
      assertThat(plan, planMatcher);
    }
    return result;
  }

  private Object getIt(List<Binding> bindings) {
    for (Binding binding : bindings) {
      if (binding.name.equals("it")) {
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
