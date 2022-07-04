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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.CalciteFunctions;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.TailList;
import net.hydromatic.morel.util.ThreadLocals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.util.Util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.Ast.Direction.DESC;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.toImmutableList;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;

import static java.util.Objects.requireNonNull;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  protected static final EvalEnv EMPTY_ENV = Codes.emptyEnv();

  protected final TypeSystem typeSystem;

  public Compiler(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
  }

  CompiledStatement compileStatement(Environment env, Core.Decl decl,
      boolean isDecl, Set<Core.Exp> queriesToWrap) {
    final List<Code> matchCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    final Context cx = Context.of(env);
    compileDecl(cx, decl, isDecl, queriesToWrap, matchCodes, bindings, actions);
    final Type type = decl instanceof Core.NonRecValDecl
        ? ((Core.NonRecValDecl) decl).pat.type
        : PrimitiveType.UNIT;
    final CalciteFunctions.Context context = createContext(env);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public void eval(Session session, Environment env,
          Consumer<String> outLines, Consumer<Binding> outBindings) {
        ThreadLocals.let(CalciteFunctions.THREAD_ENV, context,
            () -> {
              final EvalEnv evalEnv = Codes.emptyEnvWith(session, env);
              for (Action action : actions) {
                action.apply(outLines, outBindings, evalEnv);
              }
            });
      }
    };
  }

  /** Creates a context.
   *
   * <p>The whole way we provide compilation environments (including
   * Environment) to generated code is a mess:
   * <ul>
   * <li>This method is protected so that CalciteCompiler can override and get
   * a Calcite type factory.
   * <li>User-defined functions should have a 'prepare' phase, where they use
   * a type factory and environment, that is distinct from the 'eval' phase.
   * <li>We should pass compile and runtime environments via parameters, not
   * thread-locals.
   * <li>The dummy session is there because session is mandatory, but we have
   * not created a session yet. Lifecycle confusion.
   * </ul> */
  protected CalciteFunctions.Context createContext(Environment env) {
    final Session dummySession = new Session();
    return new CalciteFunctions.Context(dummySession, env, typeSystem, null);
  }

  /** Something that needs to happen when a declaration is evaluated.
   *
   * <p>Usually involves placing a type or value into the bindings that will
   * make up the environment in which the next statement will be executed, and
   * printing some text on the screen. */
  interface Action {
    void apply(Consumer<String> outLines, Consumer<Binding> outBindings,
        EvalEnv evalEnv);
  }

  /** Compilation context. */
  static class Context {
    final Environment env;

    Context(Environment env) {
      this.env = env;
    }

    static Context of(Environment env) {
      return new Context(env);
    }

    Context bindAll(Iterable<Binding> bindings) {
      return of(env.bindAll(bindings));
    }
  }

  public final Code compile(Environment env, Core.Exp expression) {
    return compile(Context.of(env), expression);
  }

  /** Compiles the argument to "apply". */
  public Code compileArg(Context cx, Core.Exp expression) {
    return compile(cx, expression);
  }

  /** Compiles the tuple arguments to "apply". */
  public List<Code> compileArgs(Context cx,
      Iterable<? extends Core.Exp> expressions) {
    final ImmutableList.Builder<Code> b = ImmutableList.builder();
    expressions.forEach(e -> b.add(compile(cx, e)));
    return b.build();
  }

  public Code compile(Context cx, Core.Exp expression) {
    final Core.Literal literal;
    final Code argCode;
    final List<Code> codes;
    switch (expression.op) {
    case BOOL_LITERAL:
      literal = (Core.Literal) expression;
      final Boolean boolValue = (Boolean) literal.value;
      return Codes.constant(boolValue);

    case CHAR_LITERAL:
      literal = (Core.Literal) expression;
      final Character charValue = (Character) literal.value;
      return Codes.constant(charValue);

    case INT_LITERAL:
      literal = (Core.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).intValue());

    case REAL_LITERAL:
      literal = (Core.Literal) expression;
      return Codes.constant(((Number) literal.value).floatValue());

    case STRING_LITERAL:
      literal = (Core.Literal) expression;
      final String stringValue = (String) literal.value;
      return Codes.constant(stringValue);

    case UNIT_LITERAL:
      return Codes.constant(Unit.INSTANCE);

    case FN_LITERAL:
      literal = (Core.Literal) expression;
      final BuiltIn builtIn = (BuiltIn) literal.value;
      return Codes.constant(Codes.BUILT_IN_VALUES.get(builtIn));

    case VALUE_LITERAL:
      literal = (Core.Literal) expression;
      return Codes.constant(literal.unwrap());

    case LET:
      return compileLet(cx, (Core.Let) expression);

    case LOCAL:
      return compileLocal(cx, (Core.Local) expression);

    case FN:
      final Core.Fn fn = (Core.Fn) expression;
      return compileMatchList(cx,
          ImmutableList.of(core.match(fn.idPat, fn.exp, fn.pos)));

    case CASE:
      final Core.Case case_ = (Core.Case) expression;
      final Code matchCode = compileMatchList(cx, case_.matchList);
      argCode = compile(cx, case_.exp);
      return Codes.apply(matchCode, argCode);

    case RECORD_SELECTOR:
      final Core.RecordSelector recordSelector = (Core.RecordSelector) expression;
      return Codes.nth(recordSelector.slot).asCode();

    case APPLY:
      return compileApply(cx, (Core.Apply) expression);

    case FROM:
      return compileFrom(cx, (Core.From) expression);

    case ID:
      final Core.Id id = (Core.Id) expression;
      final Binding binding = cx.env.getOpt(id.idPat.name);
      if (binding != null && binding.value instanceof Code) {
        return (Code) binding.value;
      }
      return Codes.get(id.idPat.name);

    case TUPLE:
      final Core.Tuple tuple = (Core.Tuple) expression;
      codes = new ArrayList<>();
      for (Core.Exp arg : tuple.args) {
        codes.add(compile(cx, arg));
      }
      return Codes.tuple(codes);

    default:
      throw new AssertionError("op not handled: " + expression.op);
    }
  }

  protected Code compileApply(Context cx, Core.Apply apply) {
    // Is this is a call to a built-in operator?
    switch (apply.fn.op) {
    case FN_LITERAL:
      final Core.Literal literal = (Core.Literal) apply.fn;
      final BuiltIn builtIn = (BuiltIn) literal.value;
      return compileCall(cx, builtIn, apply.arg, apply.pos);
    }
    final Code argCode = compileArg(cx, apply.arg);
    final Type argType = apply.arg.type;
    final Applicable fnValue =
        compileApplicable(cx, apply.fn, argType, apply.pos);
    if (fnValue != null) {
      return finishCompileApply(cx, fnValue, argCode, argType);
    }
    final Code fnCode = compile(cx, apply.fn);
    return finishCompileApply(cx, fnCode, argCode, argType);
  }

  protected Code finishCompileApply(Context cx, Applicable fnValue,
      Code argCode, Type argType) {
    return Codes.apply(fnValue, argCode);
  }

  protected Code finishCompileApply(Context cx, Code fnCode, Code argCode,
      Type argType) {
    return Codes.apply(fnCode, argCode);
  }

  protected Code compileFrom(Context cx, Core.From from) {
    Supplier<Codes.RowSink> rowSinkFactory =
        createRowSinkFactory(cx, ImmutableList.of(), from.steps,
            from.type().elementType);
    return Codes.from(rowSinkFactory);
  }

  protected Supplier<Codes.RowSink> createRowSinkFactory(Context cx0,
      ImmutableList<Binding> bindings, List<Core.FromStep> steps,
      Type elementType) {
    final Context cx = cx0.bindAll(bindings);
    if (steps.isEmpty()) {
      final List<String> fieldNames =
          bindings.stream().map(b -> b.id).sorted()
              .map(id -> id.name).collect(Collectors.toList());
      final Code code;
      if (fieldNames.size() == 1
          && getOnlyElement(bindings).id.type.equals(elementType)) {
        code = Codes.get(fieldNames.get(0));
      } else {
        code = Codes.getTuple(fieldNames);
      }
      return () -> Codes.collectRowSink(code);
    }
    final Core.FromStep firstStep = steps.get(0);
    final Supplier<Codes.RowSink> nextFactory =
        createRowSinkFactory(cx, firstStep.bindings, Util.skip(steps),
            elementType);
    switch (firstStep.op) {
    case INNER_JOIN:
      final Core.Scan scan = (Core.Scan) firstStep;
      final Code code = compile(cx, scan.exp);
      final Code conditionCode = compile(cx, scan.condition);
      return () -> Codes.scanRowSink(firstStep.op, scan.pat, code,
          conditionCode, nextFactory.get());

    case WHERE:
      final Core.Where where = (Core.Where) firstStep;
      final Code filterCode = compile(cx, where.exp);
      return () -> Codes.whereRowSink(filterCode, nextFactory.get());

    case YIELD:
      final Core.Yield yield = (Core.Yield) firstStep;
      if (steps.size() == 1) {
        // Last step. Use a Collect row sink, and we're done.
        // Note that we don't use nextFactory.
        final Code yieldCode = compile(cx, yield.exp);
        return () -> Codes.collectRowSink(yieldCode);
      } else {
        final Core.Tuple tuple = (Core.Tuple) yield.exp;
        final RecordLikeType recordType = tuple.type();
        final ImmutableSortedMap.Builder<String, Code> mapCodes =
            ImmutableSortedMap.orderedBy(RecordType.ORDERING);
        Pair.forEach(tuple.args, recordType.argNameTypes().keySet(),
            (exp, name) ->
                mapCodes.put(name, compile(cx, exp)));
        return () -> Codes.yieldRowSink(mapCodes.build(), nextFactory.get());
      }

    case ORDER:
      final Core.Order order = (Core.Order) firstStep;
      final ImmutableList<Pair<Code, Boolean>> codes =
          order.orderItems.stream()
              .map(i -> Pair.of(compile(cx, i.exp), i.direction == DESC))
              .collect(toImmutableList());
      return () -> Codes.orderRowSink(codes, bindings, nextFactory.get());

    case GROUP:
      final Core.Group group = (Core.Group) firstStep;
      final ImmutableList.Builder<Code> groupCodesB = ImmutableList.builder();
      for (Core.Exp exp : group.groupExps.values()) {
        groupCodesB.add(compile(cx, exp));
      }
      final ImmutableList<String> names = bindingNamesSorted(bindings);
      final ImmutableList.Builder<Applicable> aggregateCodesB =
          ImmutableList.builder();
      for (Core.Aggregate aggregate : group.aggregates.values()) {
        final Code argumentCode;
        final Type argumentType;
        if (aggregate.argument == null) {
          final SortedMap<String, Type> argNameTypes =
              new TreeMap<>(RecordType.ORDERING);
          bindings.forEach(b -> argNameTypes.put(b.id.name, b.id.type));
          argumentType = typeSystem.recordOrScalarType(argNameTypes);
          argumentCode = null;
        } else {
          argumentType = aggregate.argument.type;
          argumentCode = compile(cx, aggregate.argument);
        }
        final Applicable aggregateApplicable =
            compileApplicable(cx, aggregate.aggregate,
                typeSystem.listType(argumentType), aggregate.pos);
        final Code aggregateCode;
        if (aggregateApplicable == null) {
          aggregateCode = compile(cx, aggregate.aggregate);
        } else {
          aggregateCode = aggregateApplicable.asCode();
        }
        aggregateCodesB.add(
            Codes.aggregate(cx.env, aggregateCode, names, argumentCode));
      }
      final ImmutableList<Code> groupCodes = groupCodesB.build();
      final Code keyCode = Codes.tuple(groupCodes);
      final ImmutableList<Applicable> aggregateCodes = aggregateCodesB.build();
      final ImmutableList<String> outNames = bindingNames(firstStep.bindings);
      final ImmutableList<String> keyNames =
          outNames.subList(0, group.groupExps.size());
      return () -> Codes.groupRowSink(keyCode, aggregateCodes, names, keyNames,
          outNames, nextFactory.get());

    default:
      throw new AssertionError("unknown step type " + firstStep.op);
    }
  }

  private ImmutableList<String> bindingNamesSorted(List<Binding> bindings) {
    return RecordType.ORDERING.immutableSortedCopy(bindingNames(bindings));
  }

  private ImmutableList<String> bindingNames(List<Binding> bindings) {
    return bindings.stream().map(b -> b.id.name)
        .collect(toImmutableList());
  }

  /** Compiles a function value to an {@link Applicable}, if possible, or
   * returns null. */
  private Applicable compileApplicable(Context cx, Core.Exp fn, Type argType,
      Pos pos) {
    switch (fn.op) {
    case FN_LITERAL:
      final BuiltIn builtIn = (BuiltIn) ((Core.Literal) fn).value;
      final Object o = Codes.BUILT_IN_VALUES.get(builtIn);
      return toApplicable(cx, o, argType, pos);

    case VALUE_LITERAL:
      final Core.Literal literal = (Core.Literal) fn;
      return toApplicable(cx, literal.unwrap(), argType, pos);

    case ID:
      final Binding binding = cx.env.getOpt(((Core.Id) fn).idPat.name);
      if (binding == null
          || binding.value instanceof LinkCode
          || binding.value == Unit.INSTANCE) {
        return null;
      }
      return toApplicable(cx, binding.value, argType, pos);

    case RECORD_SELECTOR:
      final Core.RecordSelector recordSelector = (Core.RecordSelector) fn;
      return Codes.nth(recordSelector.slot);

    default:
      return null;
    }
  }

  private @Nullable Applicable toApplicable(Context cx, Object o,
      Type argType, Pos pos) {
    if (o instanceof Applicable) {
      final Applicable applicable = (Applicable) o;
      if (applicable instanceof Codes.Positioned) {
        return ((Codes.Positioned) applicable).withPos(pos);
      }
      return applicable;
    }
    if (o instanceof Macro) {
      final Macro value = (Macro) o;
      final Core.Exp exp = value.expand(typeSystem, cx.env, argType);
      switch (exp.op) {
      case FN_LITERAL:
        final Core.Literal literal = (Core.Literal) exp;
        final BuiltIn builtIn = (BuiltIn) literal.value;
        return (Applicable) Codes.BUILT_IN_VALUES.get(builtIn);
      }
      final Code code = compile(cx, exp);
      return new Applicable() {
        @Override public Describer describe(Describer describer) {
          return code.describe(describer);
        }

        @Override public Object apply(EvalEnv evalEnv, Object arg) {
          return code.eval(evalEnv);
        }
      };
    }
    return null;
  }

  private Code compileLet(Context cx, Core.Let let) {
    final List<Code> matchCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    compileValDecl(cx, let.decl, true, ImmutableSet.of(), matchCodes, bindings,
        null);
    Context cx2 = cx.bindAll(bindings);
    final Code resultCode = compile(cx2, let.exp);
    return finishCompileLet(cx2, matchCodes, resultCode, let.type);
  }

  protected Code finishCompileLet(Context cx, List<Code> matchCodes,
      Code resultCode, Type resultType) {
    return Codes.let(matchCodes, resultCode);
  }

  private Code compileLocal(Context cx, Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    compileDatatypeDecl(ImmutableList.of(local.dataType), bindings, null);
    Context cx2 = cx.bindAll(bindings);
    return compile(cx2, local.exp);
  }

  void compileDecl(Context cx, Core.Decl decl, boolean isDecl,
      Set<Core.Exp> queriesToWrap, List<Code> matchCodes,
      List<Binding> bindings, List<Action> actions) {
    switch (decl.op) {
    case VAL_DECL:
    case REC_VAL_DECL:
      final Core.ValDecl valDecl = (Core.ValDecl) decl;
      compileValDecl(cx, valDecl, isDecl, queriesToWrap, matchCodes, bindings,
          actions);
      break;

    case DATATYPE_DECL:
      final Core.DatatypeDecl datatypeDecl = (Core.DatatypeDecl) decl;
      compileDatatypeDecl(datatypeDecl.dataTypes, bindings, actions);
      break;

    default:
      throw new AssertionError("unknown " + decl.op + " [" + decl + "]");
    }
  }

  private void compileDatatypeDecl(List<DataType> dataTypes,
      List<Binding> bindings, List<Action> actions) {
    for (DataType dataType : dataTypes) {
      final List<Binding> newBindings = new TailList<>(bindings);
      dataType.typeConstructors.keySet().forEach(name ->
          bindings.add(typeSystem.bindTyCon(dataType, name)));
      if (actions != null) {
        final List<Binding> immutableBindings =
            ImmutableList.copyOf(newBindings);
        actions.add((outLines, outBindings, evalEnv) -> {
          final StringBuilder buf =
              new StringBuilder().append("datatype ")
                  .append(dataType.moniker).append(" = ");
          dataType.def().describe(buf);
          outLines.accept(buf.toString());
          immutableBindings.forEach(outBindings);
        });
      }
    }
  }

  private Code compileCall(Context cx, BuiltIn builtIn, Core.Exp arg, Pos pos) {
    final List<Code> argCodes;
    switch (builtIn) {
    case Z_ANDALSO:
      // Argument for a built-in infix operator such as "andalso" is always a
      // tuple; operators are never curried, nor do they evaluate an expression
      // to yield the tuple of arguments.
      argCodes = compileArgs(cx, ((Core.Tuple) arg).args);
      return Codes.andAlso(argCodes.get(0), argCodes.get(1));
    case Z_ORELSE:
      argCodes = compileArgs(cx, ((Core.Tuple) arg).args);
      return Codes.orElse(argCodes.get(0), argCodes.get(1));
    case Z_LIST:
      argCodes = compileArgs(cx, ((Core.Tuple) arg).args);
      return Codes.list(argCodes);
    default:
      final Object o0 = Codes.BUILT_IN_VALUES.get(builtIn);
      final Object o;
      if (o0 instanceof Codes.Positioned) {
        o = ((Codes.Positioned) o0).withPos(pos);
      } else {
        o = o0;
      }
      if (o instanceof Applicable) {
        final Code argCode = compile(cx, arg);
        if (argCode instanceof Codes.TupleCode) {
          final Codes.TupleCode tupleCode = (Codes.TupleCode) argCode;
          if (tupleCode.codes.size() == 2
              && o instanceof Applicable2) {
            //noinspection rawtypes
            return Codes.apply2((Applicable2) o,
                tupleCode.codes.get(0),
                tupleCode.codes.get(1));
          }
          if (tupleCode.codes.size() == 3
              && o instanceof Applicable3) {
            //noinspection rawtypes
            return Codes.apply3((Applicable3) o,
                tupleCode.codes.get(0),
                tupleCode.codes.get(1),
                tupleCode.codes.get(2));
          }
        }
        return Codes.apply((Applicable) o, argCode);
      }
      throw new AssertionError("unknown " + builtIn);
    }
  }

  /** Compiles a {@code match} expression.
   *
   * @param cx Compile context
   * @param matchList List of Match
   * @return Code for match
   */
  private Code compileMatchList(Context cx,
      List<Core.Match> matchList) {
    final ImmutableList<Pair<Core.Pat, Code>> patCodes =
        matchList.stream()
            .map(match -> compileMatch(cx, match))
            .collect(toImmutableList());
    return new MatchCode(patCodes, getLast(matchList).pos);
  }

  private Pair<Core.Pat, Code> compileMatch(Context cx, Core.Match match) {
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, match.pat);
    final Code code = compile(cx.bindAll(bindings), match.exp);
    return Pair.of(match.pat, code);
  }

  private void compileValDecl(Context cx, Core.ValDecl valDecl, boolean isDecl,
      Set<Core.Exp> queriesToWrap, List<Code> matchCodes,
      List<Binding> bindings, List<Action> actions) {
    Compiles.bindPattern(typeSystem, bindings, valDecl);
    final List<Binding> newBindings = new TailList<>(bindings);
    final Map<Core.NamedPat, LinkCode> linkCodes = new HashMap<>();
    if (valDecl.op == Op.REC_VAL_DECL) {
      valDecl.forEachBinding((pat, exp, pos) -> {
        final LinkCode linkCode = new LinkCode();
        linkCodes.put(pat, linkCode);
        bindings.add(Binding.of(pat, linkCode));
      });
    }

    final Context cx1 = cx.bindAll(newBindings);
    valDecl.forEachBinding((pat, exp, pos) -> {
      // Using 'compileArg' rather than 'compile' encourages CalciteCompiler
      // to use a pure Calcite implementation if possible, and has no effect
      // in the basic Compiler.
      final Code code0 = compileArg(cx1, exp);
      final Code code =
          queriesToWrap.contains(exp) ? Codes.wrapRelList(code0) : code0;
      if (!linkCodes.isEmpty()) {
        link(linkCodes, pat, code);
      }
      matchCodes.add(new MatchCode(ImmutableList.of(Pair.of(pat, code)), pos));

      if (actions != null) {
        final Type type0 = exp.type;
        final Type type = typeSystem.ensureClosed(type0);
        actions.add((outLines, outBindings, evalEnv) -> {
          final Session session = (Session) evalEnv.getOpt(EvalEnv.SESSION);
          final StringBuilder buf = new StringBuilder();
          final List<String> outs = new ArrayList<>();
          try {
            final Object o = code.eval(evalEnv);
            final Map<Core.NamedPat, Object> pairs = new LinkedHashMap<>();
            if (!Closure.bindRecurse(pat.withType(type), o, pairs::put)) {
              throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
            }
            pairs.forEach((pat2, o2) -> {
              outBindings.accept(Binding.of(pat2, o2));
              if (!isDecl || !pat2.name.equals("it")) {
                int stringDepth = Prop.STRING_DEPTH.intValue(session.map);
                int lineWidth = Prop.LINE_WIDTH.intValue(session.map);
                int printDepth = Prop.PRINT_DEPTH.intValue(session.map);
                int printLength = Prop.PRINT_LENGTH.intValue(session.map);
                final Pretty pretty =
                    new Pretty(typeSystem, lineWidth, printLength, printDepth,
                        stringDepth);
                pretty.pretty(buf, pat2.type,
                    new Pretty.TypedVal(pat2.name, o2, pat2.type));
                final String out = buf.toString();
                buf.setLength(0);
                outs.add(out);
                outLines.accept(out);
              }
            });
          } catch (Codes.MorelRuntimeException e) {
            session.handle(e, buf);
            final String out = buf.toString();
            buf.setLength(0);
            outs.add(out);
            outLines.accept(out);
          }
          session.code = code;
          session.out = ImmutableList.copyOf(outs);
        });
      }
    });

    newBindings.clear();
  }

  private void shredValue(Core.Pat pat, Object o,
      BiConsumer<Core.NamedPat, Object> consumer) {
    switch (pat.op) {
    case ID_PAT:
      consumer.accept((Core.IdPat) pat, o);
      return;
    case AS_PAT:
      final Core.AsPat asPat = (Core.AsPat) pat;
      consumer.accept(asPat, o);
      shredValue(asPat.pat, o, consumer);
      return;
    case TUPLE_PAT:
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      Pair.forEach(tuplePat.args, (List) o, (pat2, o2) ->
          shredValue(pat2, o2, consumer));
      return;
    case RECORD_PAT:
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      Pair.forEach(recordPat.args, (List) o, (pat2, o2) ->
          shredValue(pat2, o2, consumer));
      return;
    case CON_PAT:
      final Core.ConPat conPat = (Core.ConPat) pat;
      shredValue(conPat.pat, ((List) o).get(1), consumer);
      return;
    case CONS_PAT:
      final Core.ConPat consPat = (Core.ConPat) pat;
      final List list = (List) o;
      final Object head = list.get(0);
      final List tail = list.subList(1, list.size());
      shredValue(consPat.pat, ImmutableList.of(head, tail), consumer);
      return;
    }
  }

  private void link(Map<Core.NamedPat, LinkCode> linkCodes, Core.Pat pat,
      Code code) {
    if (pat instanceof Core.IdPat) {
      final LinkCode linkCode = linkCodes.get(pat);
      if (linkCode != null) {
        linkCode.refCode = code; // link the reference to the definition
      }
    } else if (pat instanceof Core.TuplePat) {
      if (code instanceof Codes.TupleCode) {
        // Recurse into the tuple, binding names to code in parallel
        final List<Code> codes = ((Codes.TupleCode) code).codes;
        final List<Core.Pat> pats = ((Core.TuplePat) pat).args;
        Pair.forEach(codes, pats, (code1, pat1) ->
            link(linkCodes, pat1, code1));
      }
    }
  }

  /** A piece of code that is references another piece of code.
   * It is useful when defining recursive functions.
   * The reference is mutable, and is fixed up when the
   * function has been compiled.
   */
  private static class LinkCode implements Code {
    private Code refCode;

    @Override public Describer describe(Describer describer) {
      return describer.start("link", d -> {
        if (false) {
          // Don't recurse into refCode... or we'll never get out alive.
          d.arg("refCode", refCode);
        }
      });
    }

    public Object eval(EvalEnv env) {
      assert refCode != null; // link should have completed by now
      return refCode.eval(env);
    }
  }

  /** Code that implements {@link Compiler#compileMatchList(Context, List)}. */
  private static class MatchCode implements Code {
    private final ImmutableList<Pair<Core.Pat, Code>> patCodes;
    private final Pos pos;

    MatchCode(ImmutableList<Pair<Core.Pat, Code>> patCodes, Pos pos) {
      this.patCodes = patCodes;
      this.pos = pos;
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("match", d ->
          patCodes.forEach(p ->
              d.arg("", p.left.toString()).arg("", p.right)));
    }

    @Override public Object eval(EvalEnv evalEnv) {
      return new Closure(evalEnv, patCodes, pos);
    }
  }
}

// End Compiler.java
