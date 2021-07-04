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

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.externalize.RelJson;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.JsonBuilder;
import org.apache.calcite.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.CoreBuilder.core;

import static java.util.Objects.requireNonNull;

/** Compiles an expression to code that can be evaluated. */
public class CalciteCompiler extends Compiler {
  /** Morel prefix and suffix operators and their exact equivalents in Calcite. */
  static final Map<BuiltIn, SqlOperator> UNARY_OPERATORS =
      ImmutableMap.<BuiltIn, SqlOperator>builder()
          .put(BuiltIn.NOT, SqlStdOperatorTable.NOT)
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
        new RelContext(env, calcite.relBuilder(), ImmutableMap.of(), 0),
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
        new RelContext(env, calcite.relBuilder(), ImmutableMap.of(), 0);
    if (((RelCode) code).toRel(rx, false)) {
      return calcite.code(rx.env, rx.relBuilder.build(), type);
    }
    return code;
  }

  @Override protected CalciteFunctions.Context createContext(
      Environment env) {
    final Session dummySession = new Session();
    return new CalciteFunctions.Context(dummySession, env, typeSystem,
        calcite.dataContext.getTypeFactory());
  }

  @Override public Code compileArg(Context cx, Core.Exp expression) {
    Code code = super.compileArg(cx, expression);
    if (code instanceof RelCode && !(cx instanceof RelContext)) {
      final RelBuilder relBuilder = calcite.relBuilder();
      final RelContext rx =
          new RelContext(cx.env, relBuilder, ImmutableMap.of(), 0);
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
          Ord.forEach(matchCodes, (matchCode, i) ->
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
          final Core.Literal literal = (Core.Literal) apply.fn;
          final BuiltIn builtIn = (BuiltIn) literal.value;
          switch (builtIn) {
          case Z_LIST:
            final List<Core.Exp> args = ((Core.Tuple) apply.arg).args;
            if (args.isEmpty()) {
              final RelDataType calciteType =
                  Converters.toCalciteType(removeTypeVars(apply.type),
                      cx.relBuilder.getTypeFactory());
              cx.relBuilder.values(calciteType.getComponentType());
            } else {
              for (Core.Exp arg : args) {
                cx.relBuilder.values(new String[]{"T"}, true);
                yield_(cx, arg);
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
        final String jsonRowType =
            jsonBuilder.toJsonString(
                new RelJson(jsonBuilder).toJson(rowType));
        final String morelCode = apply.toString();
        ThreadLocals.let(CalciteFunctions.THREAD_ENV,
            new CalciteFunctions.Context(new Session(), cx.env,
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
            ? typeSystem.recordType(
            ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
                .put("b", PrimitiveType.BOOL)
                .build())
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
        .leastRestrictive(Util.transform(inputs, RelNode::getRowType));
    for (RelNode input : Lists.reverse(inputs)) {
      relBuilder.push(input)
          .convert(rowType, false);
    }
  }

  @Override protected RelCode compileFrom(Context cx, Core.From from) {
    final Code code = super.compileFrom(cx, from);
    return new RelCode() {
      @Override public Describer describe(Describer describer) {
        return code.describe(describer);
      }

      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public boolean toRel(RelContext cx, boolean aggressive) {
        final Environment env = cx.env;
        final RelBuilder relBuilder = cx.relBuilder;
        final Map<Core.Pat, RelNode> sourceCodes = new LinkedHashMap<>();
        final List<Binding> bindings = new ArrayList<>();
        for (Map.Entry<Core.Pat, Core.Exp> patExp : from.sources.entrySet()) {
          final RelContext cx2 =
              new RelContext(env.bindAll(bindings), calcite.relBuilder(),
                  cx.map, 0);
          if (!toRel3(cx2, patExp.getValue(), true)) {
            return false;
          }
          final RelNode expCode = cx2.relBuilder.build();
          final Core.Pat pat = patExp.getKey();
          sourceCodes.put(pat, expCode);
          Compiles.bindPattern(typeSystem, bindings, pat);
        }
        final Map<String, Function<RelBuilder, RexNode>> map = new HashMap<>();
        if (sourceCodes.size() == 0) {
          // One row, zero columns
          relBuilder.values(ImmutableList.of(ImmutableList.of()),
              relBuilder.getTypeFactory().builder().build());
        } else {
          final SortedMap<String, VarData> varOffsets = new TreeMap<>();
          int i = 0;
          int offset = 0;
          for (Map.Entry<Core.Pat, RelNode> pair : sourceCodes.entrySet()) {
            final Core.Pat pat = pair.getKey();
            final RelNode r = pair.getValue();
            relBuilder.push(r);
            if (pat instanceof Core.IdPat) {
              relBuilder.as(((Core.IdPat) pat).name);
              final int finalOffset = offset;
              map.put(((Core.IdPat) pat).name, b ->
                  b.getRexBuilder().makeRangeReference(r.getRowType(),
                      finalOffset, false));
              varOffsets.put(((Core.IdPat) pat).name,
                  new VarData(pat.type, offset, r.getRowType()));
            }
            offset += r.getRowType().getFieldCount();
            if (++i == 2) {
              relBuilder.join(JoinRelType.INNER);
              --i;
            }
          }
          final BiMap<Integer, Integer> biMap = HashBiMap.create();
          int k = 0;
          offset = 0;
          map.clear();
          for (Map.Entry<String, VarData> entry : varOffsets.entrySet()) {
            final String var = entry.getKey();
            final VarData data = entry.getValue();
            for (int j = 0; j < data.rowType.getFieldCount(); j++) {
              biMap.put(k++, data.offset + j);
            }
            final int finalOffset = offset;
            if (data.type instanceof RecordType) {
              map.put(var, b ->
                  b.getRexBuilder().makeRangeReference(data.rowType,
                      finalOffset, false));
            } else {
              map.put(var, b -> b.field(finalOffset));
            }
            offset += data.rowType.getFieldCount();
          }
          relBuilder.project(relBuilder.fields(list(biMap)));
        }
        cx = new RelContext(env.bindAll(bindings), relBuilder, map, 1);
        for (Core.FromStep fromStep : from.steps) {
          switch (fromStep.op) {
          case WHERE:
            cx = where(cx, (Core.Where) fromStep);
            break;
          case ORDER:
            cx = order(cx, (Core.Order) fromStep);
            break;
          case GROUP:
            cx = group(cx, (Core.Group) fromStep);
            break;
          case YIELD:
            cx = yield_(cx, (Core.Yield) fromStep);
            break;
          default:
            throw new AssertionError(fromStep);
          }
        }
        if (from.steps.isEmpty()
            || Util.last(from.steps).op != Op.YIELD) {
          final Core.Exp implicitYieldExp =
              core.implicitYieldExp(typeSystem, from.initialBindings,
                  from.steps);
          cx = yield_(cx, implicitYieldExp);
        }
        return true;
      }
    };
  }

  private ImmutableList<Integer> list(BiMap<Integer, Integer> biMap) {
    // Assume that biMap has keys 0 .. size() - 1; each key occurs once.
    final List<Integer> list =
        new ArrayList<>(Collections.nCopies(biMap.size(), null));
    biMap.forEach(list::set);
    // Will throw if there are any nulls left.
    return ImmutableList.copyOf(list);
  }

  private RelContext yield_(RelContext cx, Core.Yield yield) {
    return yield_(cx, yield.exp);
  }

  private RelContext yield_(RelContext cx, Core.Exp exp) {
    final Core.Tuple tuple;
    switch (exp.op) {
    case ID:
      final Core.Id id = (Core.Id) exp;
      tuple = toRecord(cx, id);
      if (tuple != null) {
        return yield_(cx, tuple);
      }
      break;

    case TUPLE:
      tuple = (Core.Tuple) exp;
      cx.relBuilder.project(
          Util.transform(tuple.args, e -> translate(cx, e)),
          ImmutableList.copyOf(tuple.type().argNameTypes().keySet()));
      return cx;
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
      final Binding binding = cx.env.getOpt(id.idPat.name);
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
        final Function<RelBuilder, RexNode> fn = cx.map.get(id.idPat.name);
        return fn.apply(cx.relBuilder);
      }
      break;

    case APPLY:
      final Core.Apply apply = (Core.Apply) exp;
      switch (apply.fn.op) {
      case FN_LITERAL:
        BuiltIn op = (BuiltIn) ((Core.Literal) apply.fn).value;

        // Is it a unary operator with a Calcite equivalent? E.g. not => NOT
        final SqlOperator unaryOp = UNARY_OPERATORS.get(op);
        if (unaryOp != null) {
          return cx.relBuilder.call(unaryOp, translate(cx, apply.arg));
        }

        // Is it a binary operator with a Calcite equivalent? E.g. + => PLUS
        final SqlOperator binaryOp = BINARY_OPERATORS.get(op);
        if (binaryOp != null) {
          assert apply.arg.op == Op.TUPLE;
          final List<Core.Exp> args = ((Core.Tuple) apply.arg).args;
          switch (op) {
          case OP_ELEM:
          case OP_NOT_ELEM:
            final RelNode r = toRel2(cx, args.get(1));
            if (r != null) {
              final RexNode e = translate(cx, args.get(0));
              final RexSubQuery in = RexSubQuery.in(r, ImmutableList.of(e));
              switch (op) {
              case OP_NOT_ELEM:
                return cx.relBuilder.not(in);
              default:
                return in;
              }
            }
          }
          return cx.relBuilder.call(binaryOp, translateList(cx, args));
        }
      }
      if (apply.fn instanceof Core.RecordSelector
          && apply.arg instanceof Core.Id
          && cx.map.containsKey(((Core.Id) apply.arg).idPat.name)) {
        // Something like '#deptno e',
        final RexNode range =
            cx.map.get(((Core.Id) apply.arg).idPat.name).apply(cx.relBuilder);
        final Core.RecordSelector selector = (Core.RecordSelector) apply.fn;
        return cx.relBuilder.field(range, selector.fieldName());
      }
      final Set<String> vars = getRelationalVariables(cx.env, cx.map, apply);
      if (vars.isEmpty()) {
        return morelScalar(cx, apply);
      }
      final RexNode fnRex = translate(cx, apply.fn);
      final RexNode argRex = translate(cx, apply.arg);
      return morelApply(cx, apply.type, apply.arg.type, fnRex, argRex);

    case TUPLE:
      final Core.Tuple tuple = (Core.Tuple) exp;
      builder = cx.relBuilder.getTypeFactory().builder();
      operands = new ArrayList<>();
      Ord.forEach(tuple.args, (arg, i) -> {
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

  private Set<String> getRelationalVariables(Environment env,
      Map<String, Function<RelBuilder, RexNode>> map,
      AstNode node) {
    final Set<String> varNames = new LinkedHashSet<>();
    node.accept(new Visitor() {
      @Override protected void visit(Core.Id id) {
        if (map.containsKey(id.idPat.name)) {
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
    final String jsonType =
        jsonBuilder.toJsonString(
            new RelJson(jsonBuilder).toJson(calciteType));
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
    final Type type = cx.env.get(id.idPat.name).id.type;
    if (type instanceof RecordType) {
      final RecordType recordType = (RecordType) type;
      final List<Core.Exp> args = new ArrayList<>();
      recordType.argNameTypes.forEach((field, fieldType) ->
          args.add(
              core.apply(fieldType,
                  core.recordSelector(typeSystem, recordType, field),
                  id)));
      return core.tuple(recordType, args);
    }
    return null;
  }

  private List<RexNode> translateList(RelContext cx, List<Core.Exp> exps) {
    final ImmutableList.Builder<RexNode> list = ImmutableList.builder();
    for (Core.Exp exp : exps) {
      list.add(translate(cx, exp));
    }
    return list.build();
  }

  private RelContext where(RelContext cx, Core.Where where) {
    cx.relBuilder.filter(translate(cx, where.exp));
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
    final Map<String, Function<RelBuilder, RexNode>> map = new HashMap<>();
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

    // Permute the fields so that they are sorted by name, per Morel records.
    final List<String> sortedNames =
        Ordering.natural().immutableSortedCopy(names);
    cx.relBuilder.rename(names)
        .project(cx.relBuilder.fields(sortedNames));
    sortedNames.forEach(name -> {
      final int i = map.size();
      map.put(name, b -> b.field(1, 0, i));
    });

    // Return a context containing a variable for each output field.
    return new RelContext(cx.env.bindAll(bindings), cx.relBuilder, map, 1);
  }

  /** Returns the Calcite operator corresponding to a Morel built-in aggregate
   * function.
   *
   * <p>Future work: rather than resolving by name, look up aggregate function
   * in environment, and compare with standard implementation of "sum" etc.;
   * support aggregate functions defined by expressions (e.g. lambdas). */
  @Nonnull private SqlAggFunction aggOp(Core.Exp aggregate) {
    if (aggregate instanceof Core.Literal) {
      final Core.Literal literal = (Core.Literal) aggregate;
      switch ((BuiltIn) literal.value) {
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
    final Map<String, Object> map = new HashMap<>();
    env.forEachValue(map::put);
    EMPTY_ENV.visit(map::putIfAbsent);
    return EvalEnvs.copyOf(map);
  }

  /** Translation context. */
  static class RelContext extends Context {
    final RelBuilder relBuilder;
    final Map<String, Function<RelBuilder, RexNode>> map;
    final int inputCount;

    RelContext(Environment env, RelBuilder relBuilder,
        Map<String, Function<RelBuilder, RexNode>> map, int inputCount) {
      super(env);
      this.relBuilder = relBuilder;
      this.map = map;
      this.inputCount = inputCount;
    }

    @Override RelContext bindAll(Iterable<Binding> bindings) {
      final Environment env2 = env.bindAll(bindings);
      return env2 == env ? this
          : new RelContext(env2, relBuilder, map, inputCount);
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
  }
}

// End CalciteCompiler.java
