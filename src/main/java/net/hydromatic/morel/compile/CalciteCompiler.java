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
package net.hydromatic.morel.compile;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.EvalEnvs;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.CalciteFunctions;
import net.hydromatic.morel.foreign.Converters;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.ThreadLocals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.externalize.RelJson;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.JsonBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getLast;

import static java.util.Objects.requireNonNull;

/** Compiles an expression to code that can be evaluated. */
public class CalciteCompiler extends Compiler {
  /** Morel prefix and suffix operators and their exact equivalents in Calcite. */
  static final Map<BuiltIn, SqlOperator> UNARY_OPERATORS =
      ImmutableMap.<BuiltIn, SqlOperator>builder()
          .put(BuiltIn.NOT, SqlStdOperatorTable.NOT)
          .put(BuiltIn.LIST_NULL, SqlStdOperatorTable.EXISTS)
          .put(BuiltIn.RELATIONAL_EXISTS, SqlStdOperatorTable.EXISTS)
          .put(BuiltIn.RELATIONAL_NOT_EXISTS, SqlStdOperatorTable.EXISTS)
          .put(BuiltIn.RELATIONAL_ONLY, SqlStdOperatorTable.SCALAR_QUERY)
          .build();

  /** Morel infix operators and their exact equivalents in Calcite. */
  static final Map<BuiltIn, SqlOperator> BINARY_OPERATORS =
      ImmutableMap.<BuiltIn, SqlOperator>builder()
          .put(BuiltIn.OP_EQ, SqlStdOperatorTable.EQUALS)
          .put(BuiltIn.OP_NE, SqlStdOperatorTable.NOT_EQUALS)
          .put(BuiltIn.OP_LT, SqlStdOperatorTable.LESS_THAN)
          .put(BuiltIn.OP_LE, SqlStdOperatorTable.LESS_THAN_OR_EQUAL)
          .put(BuiltIn.OP_GT, SqlStdOperatorTable.GREATER_THAN)
          .put(BuiltIn.OP_GE, SqlStdOperatorTable.GREATER_THAN_OR_EQUAL)
          .put(BuiltIn.OP_NEGATE, SqlStdOperatorTable.UNARY_MINUS)
          .put(BuiltIn.OP_ELEM, SqlStdOperatorTable.IN)
          .put(BuiltIn.OP_NOT_ELEM, SqlStdOperatorTable.NOT_IN)
          .put(BuiltIn.Z_NEGATE_INT, SqlStdOperatorTable.UNARY_MINUS)
          .put(BuiltIn.Z_NEGATE_REAL, SqlStdOperatorTable.UNARY_MINUS)
          .put(BuiltIn.OP_PLUS, SqlStdOperatorTable.PLUS)
          .put(BuiltIn.Z_PLUS_INT, SqlStdOperatorTable.PLUS)
          .put(BuiltIn.Z_PLUS_REAL, SqlStdOperatorTable.PLUS)
          .put(BuiltIn.OP_MINUS, SqlStdOperatorTable.MINUS)
          .put(BuiltIn.Z_MINUS_INT, SqlStdOperatorTable.MINUS)
          .put(BuiltIn.Z_MINUS_REAL, SqlStdOperatorTable.MINUS)
          .put(BuiltIn.OP_TIMES, SqlStdOperatorTable.MULTIPLY)
          .put(BuiltIn.Z_TIMES_INT, SqlStdOperatorTable.MULTIPLY)
          .put(BuiltIn.Z_TIMES_REAL, SqlStdOperatorTable.MULTIPLY)
          .put(BuiltIn.OP_DIVIDE, SqlStdOperatorTable.DIVIDE)
          .put(BuiltIn.Z_DIVIDE_INT, SqlStdOperatorTable.DIVIDE)
          .put(BuiltIn.Z_DIVIDE_REAL, SqlStdOperatorTable.DIVIDE)
          .put(BuiltIn.OP_DIV, SqlStdOperatorTable.DIVIDE)
          .put(BuiltIn.OP_MOD, SqlStdOperatorTable.MOD)
          .put(BuiltIn.Z_ANDALSO, SqlStdOperatorTable.AND)
          .put(BuiltIn.Z_ORELSE, SqlStdOperatorTable.OR)
          .build();

  final Calcite calcite;

  public CalciteCompiler(TypeSystem typeSystem, Calcite calcite) {
    super(typeSystem);
    this.calcite = requireNonNull(calcite, "calcite");
  }

  public @Nullable RelNode toRel(Environment env, Core.Exp expression) {
    return toRel2(
        new RelContext(env, null, calcite.relBuilder(),
            ImmutableSortedMap.of(), 0),
        expression);
  }

  private RelNode toRel2(RelContext cx, Core.Exp expression) {
    if (toRel3(cx, expression, false)) {
      return cx.relBuilder.build();
    } else {
      return null;
    }
  }

  boolean toRel3(RelContext cx, Core.Exp expression, boolean aggressive) {
    final Code code = compile(cx, expression);
    return code instanceof RelCode
        && ((RelCode) code).toRel(cx, aggressive);
  }

  Code toRel4(Environment env, Code code, Type type) {
    if (!(code instanceof RelCode)) {
      return code;
    }
    RelContext rx =
        new RelContext(env, null, calcite.relBuilder(),
            ImmutableSortedMap.of(), 0);
    if (((RelCode) code).toRel(rx, false)) {
      return calcite.code(rx.env, rx.relBuilder.build(), type);
    }
    return code;
  }

  @Override protected CalciteFunctions.Context createContext(
      Environment env) {
    final Session dummySession = new Session(ImmutableMap.of());
    return new CalciteFunctions.Context(dummySession, env, typeSystem,
        calcite.dataContext.getTypeFactory());
  }

  @Override public Code compileArg(Context cx, Core.Exp expression) {
    Code code = super.compileArg(cx, expression);
    if (code instanceof RelCode && !(cx instanceof RelContext)) {
      final RelBuilder relBuilder = calcite.relBuilder();
      final RelContext rx =
          new RelContext(cx.env, null, relBuilder, ImmutableSortedMap.of(), 0);
      if (toRel3(rx, expression, false)) {
        return calcite.code(rx.env, rx.relBuilder.build(), expression.type);
      }
    }
    return code;
  }

  @Override protected Code finishCompileLet(Context cx, List<Code> matchCodes_,
      Code resultCode_, Type resultType) {
    final Code resultCode = toRel4(cx.env, resultCode_, resultType);
    final List<Code> matchCodes = ImmutableList.copyOf(matchCodes_);
    final Code code =
        super.finishCompileLet(cx, matchCodes, resultCode, resultType);
    return new RelCode() {
      @Override public boolean toRel(RelContext cx, boolean aggressive) {
        return false;
      }

      @Override public Object eval(EvalEnv evalEnv) {
        return code.eval(evalEnv);
      }

      @Override public Describer describe(Describer describer) {
        return describer.start("let", d -> {
          forEachIndexed(matchCodes, (matchCode, i) ->
              d.arg("matchCode" + i, matchCode));
          d.arg("resultCode", resultCode);
        });
      }
    };
  }

  @Override protected RelCode compileApply(Context cx, Core.Apply apply) {
    final Code code = super.compileApply(cx, apply);
    return new RelCode() {
      @Override public Describer describe(Describer describer) {
        return code.describe(describer);
      }

      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public boolean toRel(RelContext cx, boolean aggressive) {
        if (!(apply.type instanceof ListType)) {
          return false;
        }
        switch (apply.fn.op) {
        case RECORD_SELECTOR:
          if (apply.arg instanceof Core.Id) {
            // Something like '#emp scott', 'scott' is a foreign value
            final Object o = code.eval(evalEnvOf(cx.env));
            if (o instanceof RelList) {
              cx.relBuilder.push(((RelList) o).rel);
              return true;
            }
          }
          break;

        case FN_LITERAL:
          final BuiltIn builtIn =
              ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
          switch (builtIn) {
          case Z_LIST:
            final List<Core.Exp> args = apply.args();
            if (args.isEmpty()) {
              final RelDataType calciteType =
                  Converters.toCalciteType(removeTypeVars(apply.type),
                      cx.relBuilder.getTypeFactory());
              cx.relBuilder.values(calciteType.getComponentType());
            } else {
              for (Core.Exp arg : args) {
                cx.relBuilder.values(new String[]{"T"}, true);
                yield_(cx, ImmutableList.of(), arg);
              }
              cx.relBuilder.union(true, args.size());
            }
            return true;

          case OP_UNION:
          case OP_EXCEPT:
          case OP_INTERSECT:
            // For example, '[1, 2, 3] union (from scott.dept yield deptno)'
            final Core.Tuple tuple = (Core.Tuple) apply.arg;
            for (Core.Exp arg : tuple.args) {
              if (!CalciteCompiler.this.toRel3(cx, arg, false)) {
                return false;
              }
            }
            harmonizeRowTypes(cx.relBuilder, tuple.args.size());
            switch (builtIn) {
            case OP_UNION:
              cx.relBuilder.union(true, tuple.args.size());
              return true;
            case OP_EXCEPT:
              cx.relBuilder.minus(false, tuple.args.size());
              return true;
            case OP_INTERSECT:
              cx.relBuilder.intersect(false, tuple.args.size());
              return true;
            default:
              throw new AssertionError(builtIn);
            }
          }
        }
        final RelDataTypeFactory typeFactory = cx.relBuilder.getTypeFactory();
        final RelDataType calciteType =
            Converters.toCalciteType(apply.type, typeFactory);
        final RelDataType rowType = calciteType.getComponentType();
        if (rowType == null) {
          return false;
        }
        if (!aggressive) {
          return false;
        }
        final JsonBuilder jsonBuilder = new JsonBuilder();
        final RelJson relJson = RelJson.create().withJsonBuilder(jsonBuilder);
        final String jsonRowType =
            jsonBuilder.toJsonString(relJson.toJson(rowType));
        final String morelCode = apply.toString();
        ThreadLocals.let(CalciteFunctions.THREAD_ENV,
            new CalciteFunctions.Context(new Session(ImmutableMap.of()), cx.env,
                typeSystem, cx.relBuilder.getTypeFactory()), () ->
            cx.relBuilder.functionScan(CalciteFunctions.TABLE_OPERATOR, 0,
                cx.relBuilder.literal(morelCode),
                cx.relBuilder.literal(jsonRowType)));
        return true;
      }
    };
  }

  /** Converts each type variable in a type to a dummy record type,
   * {@code {b: bool}}. */
  private Type removeTypeVars(Type type) {
    return type.copy(typeSystem,
        t -> t instanceof TypeVar
            ? typeSystem.recordType(RecordType.map("b", PrimitiveType.BOOL))
            : t);
  }

  @Override protected Code finishCompileApply(Context cx, Code fnCode,
      Code argCode, Type argType) {
    if (argCode instanceof RelCode && cx instanceof RelContext) {
      final RelContext rx = (RelContext) cx;
      if (((RelCode) argCode).toRel(rx, false)) {
        final Code argCode2 =
            calcite.code(rx.env, rx.relBuilder.build(), argType);
        return finishCompileApply(cx, fnCode, argCode2, argType);
      }
    }
    return super.finishCompileApply(cx, fnCode, argCode, argType);
  }

  @Override protected Code finishCompileApply(Context cx, Applicable fnValue,
      Code argCode, Type argType) {
    if (argCode instanceof RelCode && cx instanceof RelContext) {
      final RelContext rx = (RelContext) cx;
      if (((RelCode) argCode).toRel(rx, false)) {
        final Code argCode2 =
            calcite.code(rx.env, rx.relBuilder.build(), argType);
        return finishCompileApply(cx, fnValue, argCode2, argType);
      }
    }
    return super.finishCompileApply(cx, fnValue, argCode, argType);
  }

  private static void harmonizeRowTypes(RelBuilder relBuilder, int inputCount) {
    final List<RelNode> inputs = new ArrayList<>();
    for (int i = 0; i < inputCount; i++) {
      inputs.add(relBuilder.build());
    }
    final RelDataType rowType = relBuilder.getTypeFactory()
        .leastRestrictive(transform(inputs, RelNode::getRowType));
    for (RelNode input : Lists.reverse(inputs)) {
      relBuilder.push(input)
          .convert(rowType, false);
    }
  }

  @Override protected Code compileFrom(Context cx, Core.From from) {
    final Code code = super.compileFrom(cx, from);
    return new RelCode() {
      @Override public Describer describe(Describer describer) {
        return code.describe(describer);
      }

      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public boolean toRel(RelContext cx, boolean aggressive) {
        if (from.steps.isEmpty()
            || !(from.steps.get(0) instanceof Core.Scan)) {
          // One row, zero columns
          cx.relBuilder.values(ImmutableList.of(ImmutableList.of()),
              cx.relBuilder.getTypeFactory().builder().build());
        }
        cx =
            new RelContext(cx.env, cx, cx.relBuilder,
                ImmutableSortedMap.of(), 1);
        for (Ord<Core.FromStep> fromStep : Ord.zip(from.steps)) {
          cx = step(cx, fromStep.i, fromStep.e);
          if (cx == null) {
            return false;
          }
        }
        if (from.steps.isEmpty()
            || getLast(from.steps).op != Op.YIELD) {
          final Core.Exp implicitYieldExp =
              core.implicitYieldExp(typeSystem, from.steps);
          cx = yield_(cx, ImmutableList.of(), implicitYieldExp);
        }
        return true;
      }

      private RelContext step(RelContext cx, int i, Core.FromStep fromStep) {
        switch (fromStep.op) {
        case SCAN:
          return join(cx, i, (Core.Scan) fromStep);
        case WHERE:
          return where(cx, (Core.Where) fromStep);
        case SKIP:
          return skip(cx, (Core.Skip) fromStep);
        case TAKE:
          return take(cx, (Core.Take) fromStep);
        case ORDER:
          return order(cx, (Core.Order) fromStep);
        case GROUP:
          return group(cx, (Core.Group) fromStep);
        case YIELD:
          return yield_(cx, (Core.Yield) fromStep);
        default:
          throw new AssertionError(fromStep);
        }
      }
    };
  }

  private RelContext yield_(RelContext cx, Core.Yield yield) {
    return yield_(cx, yield.bindings, yield.exp);
  }

  private RelContext yield_(RelContext cx, List<Binding> bindings,
      Core.Exp exp) {
    final Core.Tuple tuple;
    switch (exp.op) {
    case ID:
      final Core.Id id = (Core.Id) exp;
      tuple = toRecord(cx, id);
      if (tuple != null) {
        return yield_(cx, bindings, tuple);
      }
      break;

    case TUPLE:
      tuple = (Core.Tuple) exp;
      final List<String> names =
          ImmutableList.copyOf(tuple.type().argNameTypes().keySet());
      cx.relBuilder.project(transform(tuple.args, e -> translate(cx, e)),
          names);
      return getRelContext(cx, cx.env.bindAll(bindings), names);
    }
    RexNode rex = translate(cx, exp);
    cx.relBuilder.project(rex);
    return cx;
  }

  private RexNode translate(RelContext cx, Core.Exp exp) {
    final Core.Tuple record;
    final RelDataTypeFactory.Builder builder;
    final List<RexNode> operands;
    switch (exp.op) {
    case BOOL_LITERAL:
    case CHAR_LITERAL:
    case INT_LITERAL:
    case REAL_LITERAL:
    case STRING_LITERAL:
    case UNIT_LITERAL:
      final Core.Literal literal = (Core.Literal) exp;
      switch (exp.op) {
      case CHAR_LITERAL:
        // convert from Character to singleton String
        return cx.relBuilder.literal(literal.value + "");
      case UNIT_LITERAL:
        return cx.relBuilder.call(SqlStdOperatorTable.ROW);
      default:
        return cx.relBuilder.literal(literal.value);
      }

    case ID:
      // In 'from e in emps yield e', 'e' expands to a record,
      // '{e.deptno, e.ename}'
      final Core.Id id = (Core.Id) exp;
      final Binding binding = cx.env.getOpt(id.idPat);
      if (binding != null && binding.value != Unit.INSTANCE) {
        final Core.Literal coreLiteral =
            core.literal((PrimitiveType) binding.id.type, binding.value);
        return translate(cx, coreLiteral);
      }
      record = toRecord(cx, id);
      if (record != null) {
        return translate(cx, record);
      }
      if (cx.map.containsKey(id.idPat.name)) {
        // Not a record, so must be a scalar. It is represented in Calcite
        // as a record with one field.
        final VarData fn = requireNonNull(cx.map.get(id.idPat.name));
        return fn.apply(cx.relBuilder);
      }
      break;

    case APPLY:
      final Core.Apply apply = (Core.Apply) exp;
      switch (apply.fn.op) {
      case FN_LITERAL:
        BuiltIn op = ((Core.Literal) apply.fn).unwrap(BuiltIn.class);

        // Is it a unary operator with a Calcite equivalent? E.g. not => NOT
        final SqlOperator unaryOp = UNARY_OPERATORS.get(op);
        if (unaryOp != null) {
          switch (op) {
          case LIST_NULL:
          case RELATIONAL_EXISTS:
          case RELATIONAL_NOT_EXISTS:
          case RELATIONAL_ONLY:
            final RelNode r = toRel2(cx, apply.arg);
            if (r != null) {
              switch (op) {
              case LIST_NULL:
              case RELATIONAL_NOT_EXISTS:
                return cx.relBuilder.not(RexSubQuery.exists(r));
              case RELATIONAL_EXISTS:
                return RexSubQuery.exists(r);
              case RELATIONAL_ONLY:
                return RexSubQuery.scalar(r);
              default:
                throw new AssertionError("unknown " + op);
              }
            }
          }
          return cx.relBuilder.call(unaryOp, translate(cx, apply.arg));
        }

        // Is it a binary operator with a Calcite equivalent? E.g. + => PLUS
        final SqlOperator binaryOp = BINARY_OPERATORS.get(op);
        if (binaryOp != null) {
          assert apply.arg.op == Op.TUPLE;
          switch (op) {
          case OP_ELEM:
          case OP_NOT_ELEM:
            final RelNode r = toRel2(cx, apply.args().get(1));
            if (r != null) {
              final RexNode e = translate(cx, apply.args().get(0));
              final RexSubQuery in = RexSubQuery.in(r, ImmutableList.of(e));
              return maybeNot(cx, in, op == BuiltIn.OP_NOT_ELEM);
            }
          }
          return cx.relBuilder.call(binaryOp, translateList(cx, apply.args()));
        }
      }
      if (apply.fn instanceof Core.RecordSelector
          && apply.arg instanceof Core.Id) {
        // Something like '#deptno e'
        final Core.NamedPat idPat = ((Core.Id) apply.arg).idPat;
        final @Nullable RexNode range = cx.var(idPat.name);
        if (range != null) {
          final Core.RecordSelector selector = (Core.RecordSelector) apply.fn;
          return cx.relBuilder.field(range, selector.fieldName());
        }
      }
      final Set<String> vars =
          getRelationalVariables(cx.env, cx.map.keySet(), apply);
      if (vars.isEmpty()) {
        return morelScalar(cx, apply);
      }
      final RexNode fnRex = translate(cx, apply.fn);
      final RexNode argRex = translate(cx, apply.arg);
      return morelApply(cx, apply.type, apply.arg.type, fnRex, argRex);

    case FROM:
      final Core.From from = (Core.From) exp;
      final RelNode r = toRel2(cx, from);
      if (r != null && 1 != 2) {
        // TODO: add RexSubQuery.array and RexSubQuery.multiset methods
        return cx.relBuilder.call(SqlStdOperatorTable.ARRAY_QUERY,
            RexSubQuery.scalar(r));
      }
      break;

    case TUPLE:
      final Core.Tuple tuple = (Core.Tuple) exp;
      builder = cx.relBuilder.getTypeFactory().builder();
      operands = new ArrayList<>();
      forEachIndexed(tuple.args, (arg, i) -> {
        final RexNode e = translate(cx, arg);
        operands.add(e);
        builder.add(Integer.toString(i), e.getType());
      });
      return cx.relBuilder.getRexBuilder().makeCall(builder.build(),
          SqlStdOperatorTable.ROW, operands);
    }

    // Translate as a call to a scalar function
    return morelScalar(cx, exp);
  }

  private RexNode maybeNot(RelContext cx, RexNode e, boolean not) {
    return not ? cx.relBuilder.not(e) : e;
  }

  private Set<String> getRelationalVariables(Environment env,
      Set<String> nameSet, AstNode node) {
    final Set<String> varNames = new LinkedHashSet<>();
    node.accept(new Visitor() {
      @Override protected void visit(Core.Id id) {
        if (nameSet.contains(id.idPat.name)) {
          varNames.add(id.idPat.name);
        }
      }
    });
    return varNames;
  }

  private RexNode morelScalar(RelContext cx, Core.Exp exp) {
    final RelDataTypeFactory typeFactory = cx.relBuilder.getTypeFactory();
    final RelDataType calciteType =
        Converters.toCalciteType(exp.type, typeFactory);
    final JsonBuilder jsonBuilder = new JsonBuilder();
    final RelJson relJson = RelJson.create().withJsonBuilder(jsonBuilder);
    final String jsonType =
        jsonBuilder.toJsonString(relJson.toJson(calciteType));
    final String morelCode = exp.toString();
    return cx.relBuilder.getRexBuilder().makeCall(calciteType,
        CalciteFunctions.SCALAR_OPERATOR,
        Arrays.asList(cx.relBuilder.literal(morelCode),
            cx.relBuilder.literal(jsonType)));
  }

  private RexNode morelApply(RelContext cx, Type type, Type argType, RexNode fn,
      RexNode arg) {
    final RelDataTypeFactory typeFactory = cx.relBuilder.getTypeFactory();
    final RelDataType calciteType =
        Converters.toCalciteType(type, typeFactory);
    final String morelArgType = argType.toString();
    return cx.relBuilder.getRexBuilder().makeCall(calciteType,
        CalciteFunctions.APPLY_OPERATOR,
        Arrays.asList(cx.relBuilder.literal(morelArgType), fn, arg));
  }

  private Core.Tuple toRecord(RelContext cx, Core.Id id) {
    final Binding binding = cx.env.getOpt(id.idPat);
    checkNotNull(binding, "not found", id);
    final Type type = binding.id.type;
    if (type instanceof RecordType) {
      final RecordType recordType = (RecordType) type;
      final List<Core.Exp> args = new ArrayList<>();
      recordType.argNameTypes.forEach((field, fieldType) ->
          args.add(
              core.apply(Pos.ZERO, fieldType,
                  core.recordSelector(typeSystem, recordType, field),
                  id)));
      return core.tuple(recordType, args);
    }
    return null;
  }

  private List<RexNode> translateList(RelContext cx, List<Core.Exp> exps) {
    return transformEager(exps, exp -> translate(cx, exp));
  }

  private RelContext join(RelContext cx, int i, Core.Scan scan) {
    if (!toRel3(cx, scan.exp, true)) {
      return null;
    }

    final SortedMap<String, VarData> varOffsets = new TreeMap<>(cx.map);
    int offset = 0;
    for (VarData varData : cx.map.values()) {
      offset += varData.rowType.getFieldCount();
    }
    final Core.Pat pat = scan.pat;
    final RelNode r = cx.relBuilder.peek();
    if (pat instanceof Core.IdPat) {
      final Core.IdPat idPat = (Core.IdPat) pat;
      cx.relBuilder.as(idPat.name);
      varOffsets.put(idPat.name, new VarData(pat.type, offset, r.getRowType()));
    }
    cx =
        new RelContext(cx.env.bindAll(scan.bindings), cx, cx.relBuilder,
            ImmutableSortedMap.copyOfSorted(varOffsets), cx.inputCount + 1);

    if (i > 0) {
      final JoinRelType joinRelType = joinRelType(scan.op);
      cx.relBuilder.join(joinRelType, translate(cx, scan.condition));
    }
    return cx;
  }

  private static JoinRelType joinRelType(Op op) {
    switch (op) {
    case SCAN:
      return JoinRelType.INNER;
    default:
      throw new AssertionError(op);
    }
  }

  private RelContext where(RelContext cx, Core.Where where) {
    cx.relBuilder.filter(cx.varList, translate(cx, where.exp));
    return cx;
  }

  private RelContext skip(RelContext cx, Core.Skip skip) {
    if (skip.exp.op != Op.INT_LITERAL) {
      throw new AssertionError("skip requires literal: " + skip.exp);
    }
    int offset = ((Core.Literal) skip.exp).unwrap(Integer.class);
    int fetch = -1; // per Calcite: "negative means no limit"
    cx.relBuilder.limit(offset, fetch);
    return cx;
  }

  private RelContext take(RelContext cx, Core.Take take) {
    if (take.exp.op != Op.INT_LITERAL) {
      throw new AssertionError("take requires literal: " + take.exp);
    }
    int offset = 0;
    int fetch = ((Core.Literal) take.exp).unwrap(Integer.class);
    cx.relBuilder.limit(offset, fetch);
    return cx;
  }

  private RelContext order(RelContext cx, Core.Order order) {
    final List<RexNode> exps = new ArrayList<>();
    order.orderItems.forEach(i -> {
      RexNode exp = translate(cx, i.exp);
      if (i.direction == Ast.Direction.DESC) {
        exp = cx.relBuilder.desc(exp);
      }
      exps.add(exp);
    });
    cx.relBuilder.sort(exps);
    return cx;
  }

  private RelContext group(RelContext cx, Core.Group group) {
    final List<Binding> bindings = new ArrayList<>();
    final List<RexNode> nodes = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    group.groupExps.forEach((idPat, exp) -> {
      bindings.add(Binding.of(idPat));
      nodes.add(translate(cx, exp));
      names.add(idPat.name);
    });
    final RelBuilder.GroupKey groupKey = cx.relBuilder.groupKey(nodes);
    final List<RelBuilder.AggCall> aggregateCalls = new ArrayList<>();
    group.aggregates.forEach((idPat, aggregate) -> {
      bindings.add(Binding.of(idPat));
      final SqlAggFunction op = aggOp(aggregate.aggregate);
      final ImmutableList.Builder<RexNode> args = ImmutableList.builder();
      if (aggregate.argument != null) {
        args.add(translate(cx, aggregate.argument));
      }
      aggregateCalls.add(
          cx.relBuilder.aggregateCall(op, args.build()).as(idPat.name));
      names.add(idPat.name);
    });

    // Create an Aggregate operator.
    cx.relBuilder.aggregate(groupKey, aggregateCalls);
    return getRelContext(cx, cx.env.bindAll(bindings), names);
  }

  private static RelContext getRelContext(RelContext cx, Environment env,
      List<String> names) {
    // Permute the fields so that they are sorted by name, per Morel records.
    final List<String> sortedNames =
        Ordering.natural().immutableSortedCopy(names);
    cx.relBuilder.rename(names)
        .project(cx.relBuilder.fields(sortedNames));
    final RelDataType rowType = cx.relBuilder.peek().getRowType();
    final SortedMap<String, VarData> map = new TreeMap<>();
    sortedNames.forEach(name ->
        map.put(name,
            new VarData(PrimitiveType.UNIT, map.size(), rowType)));

    // Return a context containing a variable for each output field.
    return new RelContext(env, cx, cx.relBuilder,
        ImmutableSortedMap.copyOfSorted(map), 1);
  }

  /** Returns the Calcite operator corresponding to a Morel built-in aggregate
   * function.
   *
   * <p>Future work: rather than resolving by name, look up aggregate function
   * in environment, and compare with standard implementation of "sum" etc.;
   * support aggregate functions defined by expressions (e.g. lambdas). */
  @NonNull private SqlAggFunction aggOp(Core.Exp aggregate) {
    if (aggregate instanceof Core.Literal) {
      switch (((Core.Literal) aggregate).unwrap(BuiltIn.class)) {
      case RELATIONAL_SUM:
      case Z_SUM_INT:
      case Z_SUM_REAL:
        return SqlStdOperatorTable.SUM;
      case RELATIONAL_COUNT:
        return SqlStdOperatorTable.COUNT;
      case RELATIONAL_MIN:
        return SqlStdOperatorTable.MIN;
      case RELATIONAL_MAX:
        return SqlStdOperatorTable.MAX;
      }
    }
    throw new AssertionError("unknown aggregate function: " + aggregate);
  }

  private static EvalEnv evalEnvOf(Environment env) {
    final Map<String, Object> map = new LinkedHashMap<>();
    env.forEachValue(map::put);
    EMPTY_ENV.visit(map::putIfAbsent);
    return EvalEnvs.copyOf(map);
  }

  /** Translation context. */
  static class RelContext extends Context {
    final @Nullable RelContext parent;
    final RelBuilder relBuilder;
    final ImmutableSortedMap<String, VarData> map;
    final int inputCount;
    final List<CorrelationId> varList = new ArrayList<>();
    private final RelNode top;

    RelContext(Environment env, RelContext parent, RelBuilder relBuilder,
        ImmutableSortedMap<String, VarData> map, int inputCount) {
      super(env);
      this.parent = parent;
      this.relBuilder = relBuilder;
      this.map = map;
      this.inputCount = inputCount;
      this.top = top(relBuilder);
    }

    private static @Nullable RelNode top(RelBuilder relBuilder) {
      return relBuilder.size() == 0 ? null
          : relBuilder.peek();
    }

    @Override RelContext bindAll(Iterable<Binding> bindings) {
      final Environment env2 = env.bindAll(bindings);
      return env2 == env ? this
          : new RelContext(env2, this, relBuilder, map, inputCount);
    }

    /** Creates a correlation variable with which to reference the current row
     * of a relation in an enclosing loop. */
    public @Nullable RexNode var(String name) {
      final VarData fn = map.get(name);
      if (fn != null) {
        return fn.apply(relBuilder);
      }
      for (RelContext p = parent; p != null; p = p.parent) {
        if (p.map.containsKey(name)) {
          final RelOptCluster cluster = p.top.getCluster();
          final RexCorrelVariable correlVariable = (RexCorrelVariable)
              cluster.getRexBuilder().makeCorrel(p.top.getRowType(),
                  cluster.createCorrel());
          p.varList.add(correlVariable.id);
          return correlVariable;
        }
      }
      return null; // TODO: throw; make this non-nullable
    }
  }

  /** Extension to {@link Code} that can also provide a translation to
   * relational algebra. */
  interface RelCode extends Code {
    boolean toRel(RelContext cx, boolean aggressive);

    static RelCode of(Code code, Predicate<RelContext> c) {
      return new RelCode() {
        @Override public Describer describe(Describer describer) {
          return code.describe(describer);
        }

        @Override public Object eval(EvalEnv env) {
          return code.eval(env);
        }

        @Override public boolean toRel(RelContext cx, boolean aggressive) {
          return c.test(cx);
        }
      };
    }
  }

  /** How a Morel variable maps onto the columns returned from a Join. */
  private static class VarData {
    final Type type;
    final int offset;
    final RelDataType rowType;

    VarData(Type type, int offset, RelDataType rowType) {
      this.type = type;
      this.offset = offset;
      this.rowType = rowType;
    }

    RexNode apply(RelBuilder relBuilder) {
      if (type instanceof RecordType) {
        return relBuilder.getRexBuilder().makeRangeReference(rowType,
            offset, false);
      } else {
        return relBuilder.field(offset);
      }
    }
  }
}

// End CalciteCompiler.java
