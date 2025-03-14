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
package net.hydromatic.morel.foreign;

import static net.hydromatic.morel.util.Static.transform;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.Compiler;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.calcite.DataContext;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.rel.externalize.RelJsonReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.schema.impl.TableFunctionImpl;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableFunction;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Calcite table-valued user-defined function that evaluates a Morel expression
 * and returns the result as a relation.
 */
public class CalciteFunctions {
  public static final ThreadLocal<Context> THREAD_ENV = new ThreadLocal<>();

  /**
   * Used to pass Morel's evaluation environment into Calcite, so that it is
   * available if Calcite calls back into Morel.
   *
   * <p>It would be better if we passed the environment, or variables we know
   * are needed, as an argument at the Calcite-to-Morel (see {@link
   * Calcite#code}) and Morel-to-Calcite (see {@link #TABLE_OPERATOR} and {@link
   * #SCALAR_OPERATOR}) boundaries.
   */
  public static final ThreadLocal<EvalEnv> THREAD_EVAL_ENV =
      new ThreadLocal<>();

  private CalciteFunctions() {}

  public static final SqlOperator TABLE_OPERATOR =
      new SqlUserDefinedTableFunction(
          new SqlIdentifier("morelTable", SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.CURSOR,
          InferTypes.ANY_NULLABLE,
          Arg.metadata(
              Arg.of(
                  "code",
                  f -> f.createSqlType(SqlTypeName.VARCHAR),
                  SqlTypeFamily.STRING,
                  false),
              Arg.of(
                  "typeJson",
                  f -> f.createSqlType(SqlTypeName.VARCHAR),
                  SqlTypeFamily.STRING,
                  false)),
          TableFunctionImpl.create(
              CalciteFunctions.MorelTableFunction.class, "eval"));

  public static final SqlOperator SCALAR_OPERATOR =
      new SqlUserDefinedFunction(
          new SqlIdentifier("morelScalar", SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION,
          CalciteFunctions::inferReturnType,
          InferTypes.ANY_NULLABLE,
          Arg.metadata(
              Arg.of(
                  "code",
                  f -> f.createSqlType(SqlTypeName.VARCHAR),
                  SqlTypeFamily.STRING,
                  false),
              Arg.of(
                  "typeJson",
                  f -> f.createSqlType(SqlTypeName.VARCHAR),
                  SqlTypeFamily.STRING,
                  false)),
          ScalarFunctionImpl.create(
              CalciteFunctions.MorelScalarFunction.class, "eval"));

  public static final SqlOperator APPLY_OPERATOR =
      new SqlUserDefinedFunction(
          new SqlIdentifier("morelScalar", SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION,
          CalciteFunctions::inferReturnType,
          InferTypes.ANY_NULLABLE,
          Arg.metadata(
              Arg.of(
                  "typeJson",
                  f -> f.createSqlType(SqlTypeName.VARCHAR),
                  SqlTypeFamily.STRING,
                  false),
              Arg.of(
                  "fn",
                  f -> f.createSqlType(SqlTypeName.INTEGER),
                  SqlTypeFamily.INTEGER,
                  false),
              Arg.of(
                  "arg",
                  f -> f.createSqlType(SqlTypeName.VARCHAR),
                  SqlTypeFamily.STRING,
                  false)),
          ScalarFunctionImpl.create(
              CalciteFunctions.MorelApplyFunction.class, "eval"));

  private static RelDataType inferReturnType(SqlOperatorBinding b) {
    return b.getTypeFactory().createSqlType(SqlTypeName.INTEGER);
  }

  /**
   * Calcite user-defined function that evaluates a Morel string and returns a
   * table.
   */
  public static class MorelTableFunction {
    private final Context cx = THREAD_ENV.get();
    private final Compiled compiled;

    public MorelTableFunction(org.apache.calcite.plan.Context context) {
      final List<Object> args = context.unwrap(List.class);
      if (args != null) {
        String ml = (String) args.get(0);
        String typeJson = (String) args.get(1);
        compiled =
            Compiled.create(
                ml,
                typeJson,
                cx.typeFactory,
                cx.env,
                cx.typeSystem,
                cx.session);
      } else {
        compiled = null;
      }
    }

    @SuppressWarnings("unused") // called via reflection
    public MorelTableFunction() {
      this(Contexts.EMPTY_CONTEXT);
    }

    @SuppressWarnings("unused") // called via reflection
    public ScannableTable eval(String ml, String typeJson) {
      final Compiled compiled =
          this.compiled != null
              ? this.compiled
              : Compiled.create(
                  ml,
                  typeJson,
                  cx.typeFactory,
                  cx.env,
                  cx.typeSystem,
                  cx.session);
      return new ScannableTable() {
        @Override
        public RelDataType getRowType(RelDataTypeFactory factory) {
          try {
            return RelJsonReader.readType(factory, typeJson);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }

        @Override
        public Enumerable<Object[]> scan(DataContext root) {
          Object v = compiled.code.eval(compiled.evalEnv);
          return compiled.f.apply(v);
        }

        @Override
        public Statistic getStatistic() {
          return Statistics.UNKNOWN;
        }

        @Override
        public Schema.TableType getJdbcTableType() {
          return Schema.TableType.OTHER;
        }

        @Override
        public boolean isRolledUp(String column) {
          return false;
        }

        @Override
        public boolean rolledUpColumnValidInsideAgg(
            String column,
            SqlCall call,
            SqlNode parent,
            CalciteConnectionConfig config) {
          return false;
        }
      };
    }

    /** Compiled state. */
    private static class Compiled {
      final Code code;
      final EvalEnv evalEnv;
      final Function<Object, Enumerable<Object[]>> f;

      Compiled(
          String ml,
          Code code,
          EvalEnv evalEnv,
          Function<Object, Enumerable<Object[]>> f) {
        this.code = code;
        this.evalEnv = evalEnv;
        this.f = f;
      }

      static Compiled create(
          String ml,
          String typeJson,
          RelDataTypeFactory typeFactory,
          Environment env,
          TypeSystem typeSystem,
          Session session) {
        final Ast.Exp exp;
        try {
          exp = new MorelParserImpl(new StringReader(ml)).expression();
        } catch (ParseException pe) {
          throw new RuntimeException("Error while parsing\n" + ml, pe);
        }
        final Ast.ValDecl valDecl = Compiles.toValDecl(exp);
        final TypeResolver.Resolved resolved =
            TypeResolver.deduceType(env, valDecl, typeSystem);
        final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
        final Core.NonRecValDecl valDecl3 =
            (Core.NonRecValDecl)
                Resolver.of(resolved.typeMap, env, session).toCore(valDecl2);
        final Core.Exp e3 = Compiles.toExp(valDecl3);
        final Compiler compiler = new Compiler(typeSystem);
        return new Compiled(
            ml,
            compiler.compile(env, e3),
            Codes.emptyEnvWith(session, env),
            Converters.toCalciteEnumerable(e3.type, typeFactory));
      }
    }
  }

  /**
   * Calcite user-defined function that evaluates a Morel string and returns a
   * scalar value.
   */
  public static class MorelScalarFunction {
    private final Context cx = THREAD_ENV.get();
    private final Compiled compiled;

    public MorelScalarFunction(org.apache.calcite.plan.Context context) {
      final List<Object> args = context.unwrap(List.class);
      if (args != null) {
        compiled =
            new Compiled(
                cx.env,
                cx.typeSystem,
                cx.typeFactory,
                (String) args.get(0),
                (String) args.get(1));
      } else {
        compiled = null;
      }
    }

    @SuppressWarnings("unused") // called via reflection
    public MorelScalarFunction() {
      this(Contexts.EMPTY_CONTEXT);
    }

    @SuppressWarnings("unused") // called via reflection
    public Object eval(String ml, String typeJson) {
      final Compiled compiled =
          this.compiled != null
              ? this.compiled
              : new Compiled(
                  cx.env, cx.typeSystem, cx.typeFactory, ml, typeJson);
      final EvalEnv evalEnv = THREAD_EVAL_ENV.get();
      Object v = compiled.code.eval(evalEnv);
      return compiled.f.apply(v);
    }

    /** Compiled state. */
    private static class Compiled {
      final Code code;
      final Function<Object, Object> f;

      Compiled(
          Environment env,
          TypeSystem typeSystem,
          RelDataTypeFactory typeFactory,
          String ml,
          String typeJson) {
        final Ast.Exp exp;
        try {
          exp = new MorelParserImpl(new StringReader(ml)).expression();
        } catch (ParseException pe) {
          throw new RuntimeException(pe);
        }
        final Ast.ValDecl valDecl = Compiles.toValDecl(exp);
        final TypeResolver.Resolved resolved =
            TypeResolver.deduceType(env, valDecl, typeSystem);
        final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
        final Core.NonRecValDecl valDecl3 =
            (Core.NonRecValDecl)
                Resolver.of(resolved.typeMap, env, null).toCore(valDecl2);
        final Core.Exp e3 = Compiles.toExp(valDecl3);
        code = new Compiler(typeSystem).compile(env, e3);
        f = Converters.toCalcite(e3.type, typeFactory);
      }
    }
  }

  /**
   * Calcite user-defined function that applies a Morel function (or closure) to
   * an argument.
   */
  public static class MorelApplyFunction {
    final Context cx = THREAD_ENV.get();
    final Compiled compiled;

    public MorelApplyFunction(org.apache.calcite.plan.Context context) {
      final List<Object> args = context.unwrap(List.class);
      if (args != null) {
        final String morelArgTypeJson = (String) args.get(0);
        compiled =
            new Compiled(morelArgTypeJson, cx.typeFactory, cx.typeSystem);
      } else {
        compiled = null;
      }
    }

    @SuppressWarnings("unused") // called via reflection
    public MorelApplyFunction() {
      this(Contexts.EMPTY_CONTEXT);
    }

    @SuppressWarnings("unused") // called via reflection
    public Object eval(String morelArgTypeJson, Object closure, Object arg) {
      final Compiled compiled =
          this.compiled != null
              ? this.compiled
              : new Compiled(morelArgTypeJson, cx.typeFactory, cx.typeSystem);
      final Closure fn = (Closure) closure;
      final EvalEnv evalEnv = THREAD_EVAL_ENV.get();
      final Object o = compiled.converter.apply(arg);
      return fn.apply(evalEnv, o);
    }

    /** Compiled state. */
    private static class Compiled {
      final Function<Object, Object> converter;

      Compiled(
          String morelArgType,
          RelDataTypeFactory typeFactory,
          TypeSystem typeSystem) {
        Ast.Type typeAst;
        try {
          typeAst = new MorelParserImpl(new StringReader(morelArgType)).type();
        } catch (ParseException pe) {
          throw new RuntimeException(pe);
        }
        final Type argType = TypeResolver.toType(typeAst, typeSystem);
        converter = Converters.toMorel(argType, typeFactory);
      }
    }
  }

  /** Operand to a user-defined function. */
  private interface Arg {
    String name();

    RelDataType type(RelDataTypeFactory typeFactory);

    SqlTypeFamily family();

    boolean optional();

    static SqlOperandMetadata metadata(Arg... args) {
      final List<Arg> argList = Arrays.asList(args);
      return OperandTypes.operandMetadata(
          transform(argList, Arg::family),
          typeFactory -> transform(argList, arg -> arg.type(typeFactory)),
          i -> args[i].name(),
          i -> args[i].optional());
    }

    static Arg of(
        String name,
        Function<RelDataTypeFactory, RelDataType> protoType,
        SqlTypeFamily family,
        boolean optional) {
      return new Arg() {
        @Override
        public String name() {
          return name;
        }

        @Override
        public RelDataType type(RelDataTypeFactory typeFactory) {
          return protoType.apply(typeFactory);
        }

        @Override
        public SqlTypeFamily family() {
          return family;
        }

        @Override
        public boolean optional() {
          return optional;
        }
      };
    }
  }

  /** Execution context. */
  public static class Context {
    public final Session session;
    public final Environment env;
    public final TypeSystem typeSystem;
    public final RelDataTypeFactory typeFactory;

    public Context(
        Session session,
        Environment env,
        TypeSystem typeSystem,
        @Nullable RelDataTypeFactory typeFactory) {
      this.session = session;
      this.env = env;
      this.typeSystem = typeSystem;
      this.typeFactory = typeFactory;
    }

    public Context withEnv(Environment env) {
      return new Context(session, env, typeSystem, typeFactory);
    }
  }
}

// End CalciteFunctions.java
