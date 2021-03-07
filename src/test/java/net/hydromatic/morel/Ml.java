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

import com.google.common.collect.ImmutableMap;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiler;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.TypeMap;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.TypeSystem;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;

import java.io.StringReader;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/** Fluent test helper. */
class Ml {
  private final String ml;
  private final Map<String, DataSet> dataSetMap;

  Ml(String ml, Map<String, DataSet> dataSetMap) {
    this.ml = ml;
    this.dataSetMap = ImmutableMap.copyOf(dataSetMap);
  }

  /** Creates an {@code Ml}. */
  static Ml ml(String ml) {
    return new Ml(ml, ImmutableMap.of());
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
    return assertParseDecl(MainTest.isAst(clazz, expected));
  }

  Ml assertStmt(Matcher<AstNode> matcher) {
    try {
      final AstNode statement =
          new MorelParserImpl(new StringReader(ml)).statement();
      assertThat(statement, matcher);
      return this;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  Ml assertStmt(Class<? extends AstNode> clazz,
      String expected) {
    return assertStmt(MainTest.isAst(clazz, expected));
  }

  /** Checks that an expression can be parsed and returns the given string
   * when unparsed. */
  Ml assertParse(String expected) {
    return assertStmt(AstNode.class, expected);
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
        final Ast.Exp resolvedExp =
            Compiles.toExp((Ast.ValDecl) resolved.node);
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
        final CompiledStatement compiled =
            Compiles.prepareStatement(typeSystem, env, statement);
        action.accept(compiled);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    });
  }

  <E> Ml assertEvalIter(Matcher<Iterable<E>> matcher) {
    return assertEval((Matcher) matcher);
  }

  Ml assertEval(Matcher<Object> matcher) {
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
      final Code code =
          new Compiler(resolved.typeMap)
              .compile(env, Compiles.toExp(valDecl2));
      final EvalEnv evalEnv = Codes.emptyEnvWith(new Session(), env);
      final Object value = code.eval(evalEnv);
      assertThat(value, matcher);
      return this;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  private Object eval(Environment env, TypeResolver.Resolved resolved,
      Ast.Exp e) {
    final Code code = new Compiler(resolved.typeMap).compile(env, e);
    final EvalEnv evalEnv = Codes.emptyEnvWith(new Session(), env);
    return code.eval(evalEnv);
  }

  Ml assertEvalError(Matcher<Throwable> matcher) {
    try {
      assertEval(CoreMatchers.notNullValue());
      fail("expected error");
    } catch (Throwable e) {
      assertThat(e, matcher);
    }
    return this;
  }

  Ml assertError(Matcher<String> matcher) {
    // TODO: execute code, and check error occurs
    return this;
  }

  Ml assertError(String expected) {
    return assertError(is(expected));
  }

  Ml withBinding(String name, DataSet dataSet) {
    return new Ml(ml, plus(dataSetMap, name, dataSet));
  }

  /** Returns a map plus one (key, value) entry. */
  private static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    return ImmutableMap.<K, V>builder().putAll(map).put(k, v).build();
  }
}

// End Ml.java
