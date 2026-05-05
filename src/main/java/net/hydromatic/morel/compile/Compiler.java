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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.str;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.Applicable4;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.RowSink;
import net.hydromatic.morel.eval.RowSinks;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.CalciteFunctions;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.TailList;
import net.hydromatic.morel.util.ThreadLocals;
import org.apache.calcite.util.TryThreadLocal;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  protected static final EvalEnv EMPTY_ENV = Codes.emptyEnv();

  private static final TryThreadLocal<int[]> ORDINAL_CODE =
      TryThreadLocal.withInitial(() -> new int[] {0});

  protected final TypeSystem typeSystem;

  public Compiler(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
  }

  CompiledStatement compileStatement(
      Environment env,
      Core.Decl decl,
      Core.@Nullable NamedPat skipPat,
      Set<Core.Exp> queriesToWrap) {
    final List<Code> matchCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    final Context cx = Context.of(env);
    compileDecl(
        cx, decl, skipPat, queriesToWrap, matchCodes, bindings, actions);
    final Type type =
        decl instanceof Core.NonRecValDecl
            ? ((Core.NonRecValDecl) decl).pat.type
            : PrimitiveType.UNIT;
    final CalciteFunctions.Context context = createContext(env);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public void eval(
          Session session,
          Environment env,
          Consumer<String> outLines,
          Consumer<Binding> outBindings) {
        ThreadLocals.let(
            CalciteFunctions.THREAD_CX,
            context,
            () -> {
              final EvalEnv evalEnv = Codes.emptyEnvWith(session, env);
              for (Action action : actions) {
                action.apply(outLines, outBindings, evalEnv);
              }
            });
      }

      public void getBindings(Consumer<Binding> outBindings) {
        bindings.forEach(outBindings);
      }
    };
  }

  /**
   * Creates a context.
   *
   * <p>The whole way we provide compilation environments (including
   * Environment) to generated code is a mess:
   *
   * <ul>
   *   <li>This method is protected so that CalciteCompiler can override and get
   *       a Calcite type factory.
   *   <li>User-defined functions should have a 'prepare' phase, where they use
   *       a type factory and environment, that is distinct from the 'eval'
   *       phase.
   *   <li>We should pass compile and runtime environments via parameters, not
   *       thread-locals.
   *   <li>The dummy session is there because session is mandatory, but we have
   *       not created a session yet. Lifecycle confusion.
   * </ul>
   */
  protected CalciteFunctions.Context createContext(Environment env) {
    final Session dummySession = new Session(ImmutableMap.of());
    return new CalciteFunctions.Context(dummySession, env, typeSystem, null);
  }

  /**
   * Something that needs to happen when a declaration is evaluated.
   *
   * <p>Usually involves placing a type or value into the bindings that will
   * make up the environment in which the next statement will be executed, and
   * printing some text on the screen.
   */
  interface Action {
    void apply(
        Consumer<String> outLines,
        Consumer<Binding> outBindings,
        EvalEnv evalEnv);
  }

  /** Compilation context. */
  public static class Context {
    final Environment env;

    /** The current stack layout, mapping named patterns to slot indices. */
    final StackLayout layout;

    /**
     * The number of local variable slots currently live on the stack above the
     * frame base.
     *
     * <p>This counter is incremented when pushing a binding (e.g., {@code let}
     * or {@code fn} argument) and decremented when the scope exits. It must
     * equal the number of entries pushed onto {@link
     * net.hydromatic.morel.eval.Stack#slots} at every code point.
     */
    final int localDepth;

    /**
     * Maps pre-existing global names to their stack slot indices (0-based).
     *
     * <p>Populated at the outermost REPL-statement level by {@link
     * #compileValDecl} after {@link GlobalFreeVarCollector} identifies which
     * globals are referenced. A {@link Codes#globalMarshal} prologue pushes
     * those values from {@link Stack#currentEnv()} before the body runs, so the
     * body can use fast {@link Codes#stackGet} reads instead of {@link
     * Codes#get(String)} lookups.
     *
     * <p>Empty inside function bodies: function bodies are compiled with a
     * fresh {@link Context} (in {@link #compileMatchListImpl}) whose {@code
     * globalSlotMap} is {@link ImmutableMap#of()}.
     */
    final ImmutableMap<String, Integer> globalSlotMap;

    /**
     * Named patterns of all bindings in the current mutually-recursive {@code
     * fun}/{@code val rec} group, or empty if not compiling a rec group.
     *
     * <p>Set by {@link #compileLet} for {@code REC_VAL_DECL} and threaded into
     * {@link #compileMatchListImpl}. Each closure body compiled in this context
     * gets the rec peers added to its inner {@link StackLayout} so that
     * recursive self-references and mutual references compile to {@link
     * Codes#stackGet} rather than {@link Codes#get(String)}.
     *
     * <p>Always empty inside function bodies (the fresh {@link Context} in
     * {@link #compileMatchListImpl} does not inherit this field).
     */
    final ImmutableList<Core.NamedPat> recPeers;

    Context(Environment env) {
      this(env, StackLayout.EMPTY, 0, ImmutableMap.of(), ImmutableList.of());
    }

    Context(Environment env, StackLayout layout, int localDepth) {
      this(env, layout, localDepth, ImmutableMap.of(), ImmutableList.of());
    }

    Context(
        Environment env,
        StackLayout layout,
        int localDepth,
        Map<String, Integer> globalSlotMap) {
      this(env, layout, localDepth, globalSlotMap, ImmutableList.of());
    }

    Context(
        Environment env,
        StackLayout layout,
        int localDepth,
        Map<String, Integer> globalSlotMap,
        List<Core.NamedPat> recPeers) {
      this.env = env;
      this.layout = layout;
      this.localDepth = localDepth;
      this.globalSlotMap = ImmutableMap.copyOf(globalSlotMap);
      this.recPeers = ImmutableList.copyOf(recPeers);
    }

    static Context of(Environment env) {
      return new Context(env, StackLayout.EMPTY, 0);
    }

    Context bindAll(Iterable<Binding> bindings) {
      return new Context(
          env.bindAll(bindings), layout, localDepth, globalSlotMap, recPeers);
    }

    /** Returns a copy of this context with the given rec-group peers. */
    Context withRecPeers(List<Core.NamedPat> recPeers) {
      return new Context(env, layout, localDepth, globalSlotMap, recPeers);
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
  public List<Code> compileArgs(
      Context cx, List<? extends Core.Exp> expressions) {
    return transformEager(expressions, e -> compile(cx, e));
  }

  /** Compiles the tuple arguments to "apply". */
  public PairList<Code, Type> compileArgTypes(
      Context cx, List<? extends Core.Exp> expressions) {
    PairList.BiTransformer<Core.Exp, Code, Type> transformer =
        (exp, consumer) -> {
          final Code code = compileArg(cx, exp);
          consumer.accept(code, exp.type);
        };
    return ImmutablePairList.fromTransformed(expressions, transformer);
  }

  /** Compiles an expression that is evaluated once per row. */
  public Code compileRow(Context cx, Core.Exp expression) {
    final int[] ordinalSlots = {0};
    try (TryThreadLocal.Memo ignored = ORDINAL_CODE.push(ordinalSlots)) {
      Code code = compile(cx, expression);
      if (ordinalSlots[0] == 0) {
        return code;
      }
      // The ordinal was used in at least one place.
      // Create a wrapper that will increment the ordinal each time.
      ordinalSlots[0] = -1;
      return Codes.ordinalInc(ordinalSlots, code);
    }
  }

  /**
   * Compiles a collection of expressions that are evaluated once per row.
   *
   * <p>If one or more of those expressions references {@code ordinal}, add a
   * wrapper around the first expression that increments the ordinal, similar to
   * how {@link #compileRow(Context, Core.Exp)} does it.
   */
  private ImmutableSortedMap<String, Code> compileRowMap(
      Context cx, List<? extends Map.Entry<String, Core.Exp>> nameExps) {
    final int[] ordinalSlots = {0};
    try (TryThreadLocal.Memo ignored = ORDINAL_CODE.push(ordinalSlots)) {
      final PairList<String, Code> mapCodes = PairList.of();
      forEach(nameExps, (name, exp) -> mapCodes.add(name, compile(cx, exp)));
      if (ordinalSlots[0] > 0) {
        // The ordinal was used in at least one place.
        // Create a wrapper that will increment the ordinal each time.
        ordinalSlots[0] = -1;
        final List<Code> codes = mapCodes.rightList();
        codes.set(0, Codes.ordinalInc(ordinalSlots, codes.get(0)));
      }
      return ImmutableSortedMap.copyOf(mapCodes, RecordType.ORDERING);
    }
  }

  public Code compile(Context cx, Core.Exp expression) {
    final Core.Literal literal;
    final Code argCode;
    final List<Code> codes;
    switch (expression.op) {
      case BOOL_LITERAL:
        literal = (Core.Literal) expression;
        final Boolean boolValue = literal.unwrap(Boolean.class);
        return Codes.constant(boolValue);

      case CHAR_LITERAL:
        literal = (Core.Literal) expression;
        final Character charValue = literal.unwrap(Character.class);
        return Codes.constant(charValue);

      case INT_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.unwrap(Integer.class));

      case REAL_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.unwrap(Float.class));

      case STRING_LITERAL:
        literal = (Core.Literal) expression;
        final String stringValue = literal.unwrap(String.class);
        return Codes.constant(stringValue);

      case UNIT_LITERAL:
        return Codes.constant(Unit.INSTANCE);

      case FN_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.toBuiltIn(typeSystem, null));

      case INTERNAL_LITERAL:
      case VALUE_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.unwrap(Object.class));

      case LET:
        return compileLet(cx, (Core.Let) expression);

      case LOCAL:
        return compileLocal(cx, (Core.Local) expression);

      case FN:
        final Core.Fn fn = (Core.Fn) expression;
        return compileMatchListTail(
            cx, ImmutableList.of(core.match(fn.pos, fn.idPat, fn.exp)));

      case CASE:
        final Core.Case case_ = (Core.Case) expression;
        final Code matchCode = compileMatchList(cx, case_.matchList);
        argCode = compile(cx, case_.exp);
        return Codes.apply(matchCode, argCode);

      case RAISE:
        final Core.Raise raise = (Core.Raise) expression;
        return Codes.raise(compile(cx, raise.exp), raise.pos);

      case RECORD_SELECTOR:
        final Core.RecordSelector recordSelector =
            (Core.RecordSelector) expression;
        return Codes.nth(recordSelector.slot).asCode();

      case APPLY:
        return compileApply(cx, (Core.Apply) expression);

      case FROM:
        return compileFrom(cx, (Core.From) expression);

      case ID:
        final Core.Id id = (Core.Id) expression;
        return compileFieldName(cx, id.idPat);

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

  private Code compileFieldName(Context cx, Core.NamedPat idPat) {
    final int slotIndex = cx.layout.get(idPat);
    if (slotIndex >= 0) {
      // Offset is 1-based from the current top of the stack.
      // localDepth is the number of slots currently allocated;
      // slot i (0-based) is at offset (localDepth - i) from the top.
      return Codes.stackGet(cx.localDepth - slotIndex, idPat.name);
    }
    // Check globalSlotMap: pre-marshaled globals pushed at statement start.
    // Only use the stack slot if the binding in the current context is still
    // the original global value (not shadowed by a step output or current-stmt
    // placeholder with Unit.INSTANCE).
    final Integer globalSlot = cx.globalSlotMap.get(idPat.name);
    if (globalSlot != null) {
      final Binding slotBinding = cx.env.getOpt(idPat);
      if (slotBinding != null
          && !(slotBinding.value instanceof Code)
          && slotBinding.value != Unit.INSTANCE) {
        return Codes.stackGet(cx.localDepth - globalSlot, idPat.name);
      }
    }
    final Binding binding = cx.env.getOpt(idPat);
    if (binding != null && binding.value instanceof Code) {
      // Don't inline LinkCode (recursive bindings): the code will be a
      // StackMatchCode whose capture offsets were computed at the outer
      // context's depth. Inlining it here (at a different stack depth)
      // produces wrong captures. Use a runtime lookup instead.
      if (binding.value instanceof LinkCode) {
        return Codes.get(idPat.name);
      }
      return (Code) binding.value;
    }
    return Codes.get(idPat.name);
  }

  protected Code compileApply(Context cx, Core.Apply apply) {
    return compileApply(cx, apply, false);
  }

  private Code compileApply(Context cx, Core.Apply apply, boolean tailPos) {
    // Is this is a call to a built-in operator?
    switch (apply.fn.op) {
      case FN_LITERAL:
        final BuiltIn builtIn = ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
        final List<Code> argCodes;
        switch (builtIn) {
          case Z_ANDALSO:
            // Argument for a built-in infix operator such as "andalso" is
            // always a tuple; operators are never curried, nor do they evaluate
            // an expression to yield the tuple of arguments.
            argCodes = compileArgs(cx, ((Core.Tuple) apply.arg).args);
            return Codes.andAlso(argCodes.get(0), argCodes.get(1));
          case Z_ORELSE:
            argCodes = compileArgs(cx, ((Core.Tuple) apply.arg).args);
            return Codes.orElse(argCodes.get(0), argCodes.get(1));
          case Z_LIST:
            argCodes = compileArgs(cx, ((Core.Tuple) apply.arg).args);
            return Codes.list(argCodes);
          case Z_ORDINAL:
            int[] ordinalSlots = ORDINAL_CODE.get();
            ordinalSlots[0]++; // signal that we are using an ordinal
            return Codes.ordinalGet(ordinalSlots);
          default:
            if (true) {
              break;
            }
            final Object o =
                ((Core.Literal) apply.fn).toBuiltIn(typeSystem, apply.pos);
            if (o instanceof Applicable) {
              final Code argCode = compile(cx, apply.arg);
              if (argCode instanceof Codes.TupleCode) {
                final Codes.TupleCode tupleCode = (Codes.TupleCode) argCode;
                if (tupleCode.codes.size() == 2 && o instanceof Applicable2) {
                  //noinspection rawtypes
                  return Codes.apply2(
                      (Applicable2) o,
                      tupleCode.codes.get(0),
                      tupleCode.codes.get(1));
                }
                if (tupleCode.codes.size() == 3 && o instanceof Applicable3) {
                  //noinspection rawtypes
                  return Codes.apply3(
                      (Applicable3) o,
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
    final Gather gather = Gather.of(apply);
    if (gather != null) {
      // If we have a gather, we can compile the argument and return the
      // gather code.
      final @Nullable Applicable1 applicable1;
      final @Nullable Applicable2 applicable2;
      final @Nullable Applicable3 applicable3;
      final @Nullable Applicable4 applicable4;
      switch (gather.args.size()) {
        case 1:
          if (gather.argIsTuple(0, 2)) {
            applicable2 = gather.fnLiteral.toApplicable2(typeSystem, apply.pos);
            if (applicable2 != null) {
              final List<Code> argCodes = compileArgs(cx, gather.args);
              return Codes.apply2Tuple(applicable2, argCodes.get(0));
            }
          }
          if (gather.argIsTuple(0, 3)) {
            applicable3 = gather.fnLiteral.toApplicable3(typeSystem, apply.pos);
            if (applicable3 != null) {
              final List<Code> argCodes = compileArgs(cx, gather.args);
              return Codes.apply3Tuple(applicable3, argCodes.get(0));
            }
          }
          // Compile for partial evaluation, e.g. applying "String.isPrefix:
          // string -> string -> bool", a curried function with two arguments,
          // to just one argument.
          applicable1 = gather.fnLiteral.toApplicable1(typeSystem, apply.pos);
          if (applicable1 != null) {
            final List<Code> argCodes = compileArgs(cx, gather.args);
            return Codes.apply1(applicable1, argCodes.get(0));
          }
          break;
        case 2:
          applicable2 = gather.fnLiteral.toApplicable2(typeSystem, apply.pos);
          if (applicable2 != null) {
            final PairList<Code, Type> argCodes =
                compileArgTypes(cx, gather.args);
            return finishCompileApply2(cx, applicable2, argCodes);
          }
          break;
        case 3:
          applicable3 = gather.fnLiteral.toApplicable3(typeSystem, apply.pos);
          if (applicable3 != null) {
            final List<Code> argCodes = compileArgs(cx, gather.args);
            return Codes.apply3(
                applicable3, argCodes.get(0), argCodes.get(1), argCodes.get(2));
          }
          break;
        case 4:
          applicable4 = gather.fnLiteral.toApplicable4(typeSystem, apply.pos);
          if (applicable4 != null) {
            final List<Code> argCodes = compileArgs(cx, gather.args);
            return Codes.apply4(
                applicable4,
                argCodes.get(0),
                argCodes.get(1),
                argCodes.get(2),
                argCodes.get(3));
          }
          break;
        default:
          throw new UnsupportedOperationException(
              "arity " + gather.args.size());
      }
    }

    final Code argCode = compileArg(cx, apply.arg);
    final Type argType = apply.arg.type;
    final Applicable fnValue =
        compileApplicable(cx, apply.fn, argType, apply.pos);
    if (fnValue != null) {
      return tailPos
          ? Codes.tailApply(fnValue, argCode)
          : finishCompileApply(cx, fnValue, argCode, argType);
    }
    final Code fnCode = compile(cx, apply.fn);
    return tailPos
        ? Codes.tailApply(fnCode, argCode)
        : finishCompileApply(cx, fnCode, argCode, argType);
  }

  /**
   * Returns the arity of a function.
   *
   * <ul>
   *   <li>{@code 5: int} has arity -1;
   *   <li>{@code Sys.env: unit &rarr; string list} has arity 0;
   *   <li>{@code String.explode: string &rarr; char list} has arity 1;
   *   <li>{@code Math.atan2: real * real &rarr; real} has arity 2;
   *   <li>{@code Real.fromManExp: {exp:int, man:real} &rarr; real} has arity 2.
   * </ul>
   */
  private static int arity(Core.Exp fn) {
    Type type = fn.type;
    while (type instanceof ForallType) {
      type = ((ForallType) type).type;
    }
    if (!(type instanceof FnType)) {
      return -1;
    }
    final FnType fnType = (FnType) type;
    if (fnType.paramType instanceof TupleType) {
      return ((TupleType) fnType.paramType).argTypes.size();
    } else if (fnType.paramType instanceof RecordType) {
      return ((RecordType) fnType.paramType).argNameTypes.size();
    } else {
      return 1;
    }
  }

  protected Code finishCompileApply(
      Context cx, Applicable fnValue, Code argCode, Type argType) {
    return Codes.apply(fnValue, argCode);
  }

  protected Code finishCompileApply(
      Context cx, Code fnCode, Code argCode, Type argType) {
    return Codes.apply(fnCode, argCode);
  }

  @SuppressWarnings("rawtypes")
  protected Code finishCompileApply2(
      Context cx, Applicable2 applicable2, PairList<Code, Type> argCodes) {
    return Codes.apply2(applicable2, argCodes.left(0), argCodes.left(1));
  }

  protected Code compileFrom(Context cx, Core.From from) {
    Supplier<RowSink> rowSinkFactory =
        createRowSinkFactory(
            cx, Core.StepEnv.EMPTY, from.steps, from.type().elementType());
    Supplier<RowSink> firstRowSinkFactory =
        () -> RowSinks.first(rowSinkFactory.get());
    return RowSinks.from(firstRowSinkFactory);
  }

  protected Supplier<RowSink> createRowSinkFactory(
      Context cx0,
      Core.StepEnv stepEnv,
      List<Core.FromStep> steps,
      Type elementType) {
    return createRowSinkFactory(
        cx0, cx0, ImmutableMap.of(), stepEnv, steps, elementType);
  }

  /**
   * Shadow-merges two binding maps. Later bindings win by name.
   *
   * @param old The existing map
   * @param newBindings The new bindings to add (overwriting by name)
   * @return A new map with newBindings merged in
   */
  private static ImmutableMap<String, Binding> shadowMerge(
      ImmutableMap<String, Binding> old, List<Binding> newBindings) {
    if (newBindings.isEmpty()) {
      return old;
    }
    final LinkedHashMap<String, Binding> map = new LinkedHashMap<>(old);
    newBindings.forEach(b -> map.put(b.id.name, b));
    return ImmutableMap.copyOf(map);
  }

  private Supplier<RowSink> createRowSinkFactory(
      Context cx0,
      Context cxFrom,
      ImmutableMap<String, Binding> allScopeBindings,
      Core.StepEnv stepEnv,
      List<Core.FromStep> steps,
      Type elementType) {
    final Context cx = cx0.bindAll(stepEnv.bindings);
    final ImmutableMap<String, Binding> allScope2 =
        shadowMerge(allScopeBindings, stepEnv.bindings);
    if (steps.isEmpty()) {
      // Terminal collect: use compileFieldName so scan vars use StackCode.
      final List<Binding> sortedBindings =
          stepEnv.bindings.stream()
              .sorted(Comparator.comparing(b -> b.id.name))
              .collect(toImmutableList());
      final Code code;
      if (sortedBindings.size() == 1
          && sortedBindings.get(0).id.type.equals(elementType)) {
        code = compileFieldName(cx, sortedBindings.get(0).id);
      } else {
        final List<Code> codes =
            transformEager(sortedBindings, b -> compileFieldName(cx, b.id));
        code = Codes.tuple(codes);
      }
      return () -> RowSinks.collect(code);
    }
    final Core.FromStep firstStep = steps.get(0);
    final Code code;
    switch (firstStep.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) firstStep;
        code = compileRow(cx, scan.exp);
        // Extend the layout with scan variable patterns at stack slots.
        StackLayout scanLayout = cx.layout;
        int depth = cx.localDepth;
        for (Core.NamedPat scanPat : scan.pat.expand()) {
          scanLayout = scanLayout.with(scanPat, depth++);
        }
        final List<Binding> scanBindings = new ArrayList<>();
        Compiles.acceptBinding(typeSystem, scan.pat, scanBindings);
        final Context cxScan =
            new Context(cx.env.bindAll(scanBindings), scanLayout, depth);
        final Code conditionCode = compile(cxScan, scan.condition);
        final ImmutableMap<String, Binding> scanAllScope =
            shadowMerge(allScope2, firstStep.env.bindings);
        final Supplier<RowSink> scanNextFactory =
            createRowSinkFactory(
                cxScan,
                cxFrom,
                scanAllScope,
                firstStep.env,
                skip(steps),
                elementType);
        final int scanVarCount = depth - cx.localDepth;
        return () ->
            RowSinks.scan(
                firstStep.op,
                scan.pat,
                scanVarCount,
                code,
                conditionCode,
                scanNextFactory.get());

      case WHERE:
        final Core.Where where = (Core.Where) firstStep;
        final Code filterCode = compileRow(cx, where.exp);
        final Supplier<RowSink> whereNextFactory =
            createRowSinkFactory(
                cx, cxFrom, allScope2, firstStep.env, skip(steps), elementType);
        return () -> RowSinks.where(filterCode, whereNextFactory.get());

      case SKIP:
        final Core.Skip skip = (Core.Skip) firstStep;
        final Code skipCode = compile(cxFrom, skip.exp);
        final Supplier<RowSink> skipNextFactory =
            createRowSinkFactory(
                cx, cxFrom, allScope2, firstStep.env, skip(steps), elementType);
        return () -> RowSinks.skip(skipCode, skipNextFactory.get());

      case TAKE:
        final Core.Take take = (Core.Take) firstStep;
        final Code takeCode = compile(cxFrom, take.exp);
        final Supplier<RowSink> takeNextFactory =
            createRowSinkFactory(
                cx, cxFrom, allScope2, firstStep.env, skip(steps), elementType);
        return () -> RowSinks.take(takeCode, takeNextFactory.get());

      case EXCEPT:
        final Core.Except except = (Core.Except) firstStep;
        return compileSetSink(
            cx,
            cxFrom,
            allScope2,
            stepEnv,
            except.args,
            except.distinct,
            Op.EXCEPT,
            skip(steps),
            elementType);

      case INTERSECT:
        final Core.Intersect intersect = (Core.Intersect) firstStep;
        return compileSetSink(
            cx,
            cxFrom,
            allScope2,
            stepEnv,
            intersect.args,
            intersect.distinct,
            Op.INTERSECT,
            skip(steps),
            elementType);

      case UNION:
        final Core.Union union = (Core.Union) firstStep;
        return compileSetSink(
            cx,
            cxFrom,
            allScope2,
            stepEnv,
            union.args,
            union.distinct,
            Op.UNION,
            skip(steps),
            elementType);

      case YIELD:
        final Core.Yield yield = (Core.Yield) firstStep;
        final ImmutableMap<String, Binding> yieldAllScope =
            shadowMerge(allScope2, firstStep.env.bindings);
        if (steps.size() == 1) {
          // Last step. Use a Collect row sink, and we're done.
          // Note that we don't use nextFactory.
          final Code yieldCode = compileRow(cx, yield.exp);
          return () -> RowSinks.collect(yieldCode);
        } else if (yield.exp instanceof Core.Tuple) {
          final Core.Tuple tuple = (Core.Tuple) yield.exp;
          final RecordLikeType recordType = tuple.type();
          final Map<String, Code> codeMap =
              compileRowMap(cx, Pair.zip(recordType.argNames(), tuple.args));
          // Extend layout: assign yield output vars to stack slots in the same
          // order as codeMap.keySet() (alphabetical) so that the push order in
          // YieldRowSink.accept(Stack) matches the slot indices.
          final Context cxYield =
              yieldContext(cx, firstStep.env.bindings, codeMap.keySet());
          final Supplier<RowSink> yieldNextFactory =
              createRowSinkFactory(
                  cxYield,
                  cxFrom,
                  yieldAllScope,
                  firstStep.env,
                  skip(steps),
                  elementType);
          return () -> RowSinks.yield(codeMap, yieldNextFactory.get());
        } else {
          final Binding binding = yield.env.bindings.get(0);
          Map<String, Code> codeMap =
              compileRowMap(cx, PairList.of(binding.id.name, yield.exp));
          // Single yield output var: extend layout with one more stack slot.
          final Context cxYield =
              yieldContext(cx, firstStep.env.bindings, codeMap.keySet());
          final Supplier<RowSink> yieldNextFactory =
              createRowSinkFactory(
                  cxYield,
                  cxFrom,
                  yieldAllScope,
                  firstStep.env,
                  skip(steps),
                  elementType);
          return () -> RowSinks.yield(codeMap, yieldNextFactory.get());
        }

      case ORDER:
        return compileOrderSink(
            cx,
            cxFrom,
            allScope2,
            (Core.Order) firstStep,
            stepEnv,
            skip(steps),
            elementType);

      case UNORDER:
        // No explicit step is required. Unorder is a change in type, but
        // ordered and unordered streams have the same representation.
        return createRowSinkFactory(
            cx, cxFrom, allScope2, firstStep.env, skip(steps), elementType);

      case GROUP:
        return compileGroupSink(
            cx,
            cxFrom,
            allScope2,
            (Core.Group) firstStep,
            stepEnv,
            skip(steps),
            elementType);

      default:
        throw new AssertionError("unknown step type " + firstStep.op);
    }
  }

  /** Compiles an ORDER step into a {@link RowSink} factory. */
  private Supplier<RowSink> compileOrderSink(
      Context cx,
      Context cxFrom,
      ImmutableMap<String, Binding> allScopeBindings,
      Core.Order order,
      Core.StepEnv stepEnv,
      List<Core.FromStep> remainingSteps,
      Type elementType) {
    final ImmutablePairList<String, Code> inSlots =
        buildInSlots(cx, allScopeBindings.values());
    // cxResult: extends cx so that env-based allScopeBindings vars (those not
    // yet in the stack layout, e.g. GROUP output vars) are assigned new slot
    // indices starting at cx.localDepth. This allows sort key and downstream
    // code to reference all scope vars via StackCode (no GetCode/env lookup).
    final Context cxResult =
        buildEnvSlotsContext(cx, allScopeBindings.values());
    // Sort key compiled with cxResult: all scope vars use StackCode.
    final Code code = compile(cxResult, order.exp);
    final Comparator comparator =
        Comparators.comparatorFor(typeSystem, order.exp.type);
    // scanDepth: number of allScopeBindings entries that are in the original cx
    // stack layout (stack-based vars). Used only to partition inSlots for the
    // row sink; after cxResult extension all vars become stack-based.
    final int scanDepth = countStackBased(cx, allScopeBindings.values());
    // Downstream compiled with cxResult so StackCode offsets match the
    // push-back of all inSlots values (including formerly env-based vars).
    final Supplier<RowSink> nextFactory =
        createRowSinkFactory(
            cxResult,
            cxFrom,
            ImmutableMap.of(),
            order.env,
            remainingSteps,
            elementType);
    return () ->
        RowSinks.order(code, comparator, inSlots, scanDepth, nextFactory.get());
  }

  /** Compiles a GROUP step into a {@link RowSink} factory. */
  private Supplier<RowSink> compileGroupSink(
      Context cx,
      Context cxFrom,
      ImmutableMap<String, Binding> allScopeBindings,
      Core.Group group,
      Core.StepEnv stepEnv,
      List<Core.FromStep> remainingSteps,
      Type elementType) {
    final ImmutableList.Builder<Code> groupCodesB = ImmutableList.builder();
    for (Core.Exp exp : group.groupExps.values()) {
      // Group key codes evaluated at accept() time (scan vars on stack), use
      // cx.
      groupCodesB.add(compile(cx, exp));
    }
    // Compute inSlots first so we can use inSlots.leftList() for
    // Codes.aggregate. The name order in which values are captured (by slot
    // index) must match what Codes.aggregate uses to rebind rows.
    final ImmutablePairList<String, Code> inSlots =
        buildInSlots(cx, allScopeBindings.values());
    // scanDepth: number of allScopeBindings entries in the stack layout.
    // These are pushed back onto the stack at result() time inside
    // Codes.aggregate so that argumentCode (compiled with cx) can read them
    // via StackCode.
    final int scanDepth = countStackBased(cx, allScopeBindings.values());
    final ImmutableList.Builder<Applicable> aggregateCodesB =
        ImmutableList.builder();
    for (Core.Aggregate aggregate : group.aggregates.values()) {
      final Code argumentCode;
      if (aggregate.argument == null) {
        argumentCode = null;
      } else {
        // Argument code compiled with cx: scan variables use StackCode.
        // Codes.aggregate.apply() pushes stored row values back onto the
        // stack so that StackCode nodes resolve correctly at result() time.
        argumentCode = compile(cx, aggregate.argument);
      }
      Type aggType = aggregate.aggregate.type;
      if (aggType instanceof ForallType) {
        aggType = ((ForallType) aggType).type;
      }
      final Type aggParamType = ((FnType) aggType).paramType;
      final Applicable aggregateApplicable =
          compileApplicable(
              cx, aggregate.aggregate, aggParamType, aggregate.pos);
      final Code aggregateCode;
      if (aggregateApplicable == null) {
        // Compile with cxFrom so scan variables use GetCode (read from
        // session.globalEnv) rather than StackCode, because aggregate
        // functions are evaluated at result() time when scan vars are no
        // longer on stack.
        aggregateCode = compile(cxFrom, aggregate.aggregate);
      } else {
        aggregateCode = aggregateApplicable.asCode();
      }
      // Use inSlots.leftList() (not sorted names) so the row-rebinding order
      // in Codes.aggregate matches the capture order used in inSlots.
      aggregateCodesB.add(
          Codes.aggregate(
              cx.env,
              aggregateCode,
              inSlots.leftList(),
              argumentCode,
              scanDepth));
    }
    final ImmutableList<Code> groupCodes = groupCodesB.build();
    final Code keyCode = Codes.tuple(groupCodes);
    final ImmutableList<Applicable> aggregateCodes = aggregateCodesB.build();
    final ImmutableList<String> outNames = bindingNames(group.env.bindings);
    final ImmutableList<String> keyNames =
        outNames.subList(0, group.groupExps.size());
    // Downstream uses cxFrom with GROUP output names stripped from the layout,
    // so those names compile to GetCode (reading from groupEnvs in globalEnv)
    // rather than StackCode (reading the pre-GROUP slot value, e.g. a closure).
    final Context cxResult =
        new Context(
            cxFrom.env,
            cxFrom.layout.without(outNames),
            cxFrom.localDepth,
            cxFrom.globalSlotMap);
    final Supplier<RowSink> groupNextFactory =
        createRowSinkFactory(
            cxResult,
            cxResult,
            ImmutableMap.of(),
            group.env,
            remainingSteps,
            elementType);
    return () ->
        RowSinks.group(
            keyCode,
            aggregateCodes,
            inSlots,
            scanDepth,
            keyNames,
            outNames,
            groupNextFactory.get());
  }

  /** Compiles an EXCEPT/INTERSECT/UNION step into a {@link RowSink} factory. */
  private Supplier<RowSink> compileSetSink(
      Context cx,
      Context cxFrom,
      ImmutableMap<String, Binding> allScopeBindings,
      Core.StepEnv stepEnv,
      List<Core.Exp> args,
      boolean distinct,
      Op op,
      List<Core.FromStep> remainingSteps,
      Type elementType) {
    // RHS codes: non-distinct EXCEPT/INTERSECT evaluate codes in accept() when
    // scan vars ARE on the stack, so use cx. UNION and distinct
    // EXCEPT/INTERSECT evaluate codes in result() when scan vars are NOT on the
    // stack, so use cxFrom (which has the correct offsets without scan vars).
    final boolean codesAtAcceptTime =
        !distinct && (op == Op.EXCEPT || op == Op.INTERSECT);
    final Context codeCx;
    if (codesAtAcceptTime) {
      // When a preceding UNION (or other SET step) resets context to cxFrom,
      // scan vars from allScopeBindings that are absent from cx.layout are
      // still present on the stack at accept() time. Bump localDepth so that
      // StackCode offsets in the RHS expression are computed correctly.
      int lostScanVarCount =
          (int)
              allScopeBindings.values().stream()
                  .filter(b -> cx.layout.get(b.id) < 0)
                  .count();
      codeCx =
          lostScanVarCount == 0
              ? cx
              : new Context(
                  cx.env, cx.layout, cx.localDepth + lostScanVarCount);
    } else {
      codeCx = cxFrom;
    }
    final ImmutableList<Code> codes =
        transformEager(args, a -> compile(codeCx, a));
    final ImmutableList<String> names = bindingNames(stepEnv.bindings);
    // inSlots: capture all scope vars during accept() using cx.
    final ImmutablePairList<String, Code> inSlots =
        buildInSlots(cx, allScopeBindings.values());
    // scanDepth: how many of the SET step's output vars are in the stack
    // layout. These are pushed back at result() time via withRowFromKey().
    final int scanDepth =
        (int)
            stepEnv.bindings.stream()
                .filter(b -> cx.layout.get(b.id) >= 0)
                .count();
    // cxResult: extends cx so that env-based stepEnv output vars (those not
    // yet in the stack layout) are assigned new slot indices. This allows
    // downstream code to reference all SET output vars via StackCode.
    final Context cxResult = buildEnvSlotsContext(cx, stepEnv.bindings);
    // Downstream compiled with ImmutableMap.of() as allScopeBindings: the SET
    // step is a scope boundary (like GROUP), so outer scan vars (e.g. the
    // SCAN var 'e' that fed into the SET step) must not appear in the
    // downstream's inSlots.  If they did, deferred sinks (GROUP, ORDER) would
    // try to capture/restore them via StackCode at result() time when the scan
    // vars are no longer on the stack, causing an AIOBE.
    final Supplier<RowSink> nextFactory =
        createRowSinkFactory(
            cxResult,
            cxFrom,
            ImmutableMap.of(),
            stepEnv,
            remainingSteps,
            elementType);
    switch (op) {
      case EXCEPT:
        return () ->
            RowSinks.except(
                distinct, codes, names, inSlots, scanDepth, nextFactory.get());
      case INTERSECT:
        return () ->
            RowSinks.intersect(
                distinct, codes, names, inSlots, scanDepth, nextFactory.get());
      case UNION:
        return () ->
            RowSinks.union(
                distinct, codes, names, inSlots, scanDepth, nextFactory.get());
      default:
        throw new AssertionError(op);
    }
  }

  /**
   * Builds a new {@link Context} that extends {@code cx} with yield output
   * variables as stack slots.
   *
   * <p>Slot indices are assigned in {@code nameOrder} order (which matches the
   * push order used by {@link net.hydromatic.morel.eval.RowSinks}'s yield
   * implementation), starting at {@code cx.localDepth}.
   */
  private Context yieldContext(
      Context cx, List<Binding> yieldBindings, Iterable<String> nameOrder) {
    // Build name → NamedPat index so we can look up pats by name.
    final Map<String, Core.NamedPat> nameToPatMap = new LinkedHashMap<>();
    for (Binding b : yieldBindings) {
      nameToPatMap.put(b.id.name, b.id);
    }
    StackLayout layout = cx.layout;
    int count = 0;
    for (String name : nameOrder) {
      final Core.NamedPat pat = nameToPatMap.get(name);
      if (pat != null) {
        layout = layout.with(pat, cx.localDepth + count++);
      }
    }
    return new Context(
        cx.env.bindAll(yieldBindings), layout, cx.localDepth + count);
  }

  /**
   * Builds inSlots sorted by slot index (stack vars first, env vars last). Each
   * slot is a (name, code) pair.
   */
  private ImmutablePairList<String, Code> buildInSlots(
      Context cx, Collection<Binding> bindings) {
    return ImmutablePairList.fromTransformed(
        sortedInBindings(cx, bindings),
        (b, add) -> add.accept(b.id.name, compileFieldName(cx, b.id)));
  }

  /**
   * Returns a context that extends {@code cx} by assigning stack slot indices
   * to any bindings in {@code bindings} that are not already in the layout.
   *
   * <p>Stack-based bindings keep their existing slot indices. Env-based
   * bindings are assigned new slots starting at {@code cx.localDepth}, in the
   * order returned by {@link #sortedInBindings} (which matches the capture
   * order in {@link #buildInSlots}).
   *
   * <p>If all bindings are already in the layout, returns {@code cx} unchanged.
   */
  private Context buildEnvSlotsContext(
      Context cx, Collection<Binding> bindings) {
    StackLayout extLayout = cx.layout;
    int extLocalDepth = cx.localDepth;
    for (Binding b : bindings) {
      if (cx.layout.get(b.id) < 0) {
        extLayout = extLayout.with(b.id, extLocalDepth++);
      }
    }
    if (extLocalDepth == cx.localDepth) {
      return cx;
    }
    return new Context(cx.env, extLayout, extLocalDepth, cx.globalSlotMap);
  }

  /** Sorts bindings: those in the layout by slot index, then the rest. */
  private List<Binding> sortedInBindings(
      Context cx, Collection<Binding> bindings) {
    return ImmutableList.sortedCopyOf(
        Comparator.comparingInt(
            b -> {
              int slot = cx.layout.get(b.id);
              return slot >= 0 ? slot : Integer.MAX_VALUE;
            }),
        bindings);
  }

  private ImmutableList<String> bindingNames(List<Binding> bindings) {
    return transformEager(bindings, b -> b.id.name);
  }

  /** Returns the number of bindings whose pattern is in {@code cx.layout}. */
  private static int countStackBased(Context cx, Collection<Binding> bindings) {
    return (int)
        bindings.stream().filter(b -> cx.layout.get(b.id) >= 0).count();
  }

  /**
   * Compiles a function value to an {@link Applicable}, if possible, or returns
   * null.
   */
  private @Nullable Applicable compileApplicable(
      Context cx, Core.Exp fn, Type argType, Pos pos) {
    final Core.Literal literal;
    switch (fn.op) {
      case FN_LITERAL:
        literal = (Core.Literal) fn;
        return toApplicable(
            cx, literal.toBuiltIn(typeSystem, null), argType, pos);

      case VALUE_LITERAL:
        literal = (Core.Literal) fn;
        return toApplicable(cx, literal.unwrap(Object.class), argType, pos);

      case ID:
        final Pair<Binding, Environment> bindingPair =
            cx.env.getOpt2(((Core.Id) fn).idPat);
        Binding binding = bindingPair == null ? null : bindingPair.left;
        if (binding == null
            || binding.value instanceof LinkCode
            || binding.value == Unit.INSTANCE) {
          return null;
        }
        if (binding.kind != Binding.Kind.VAL) {
          final List<Binding> bindings = new ArrayList<>();
          cx.env.collect(
              ((Core.Id) fn).idPat,
              instBinding -> {
                if (instBinding.id.type.canCallArgOf(argType)) {
                  bindings.add(instBinding);
                }
              });
          if (bindings.size() == 1) {
            binding = bindings.get(0);
          } else {
            throw new AssertionError(
                argType + " matches functions with arguments " + bindings);
          }
        }
        return toApplicable(cx, binding.value, argType, pos);

      case RECORD_SELECTOR:
        final Core.RecordSelector recordSelector = (Core.RecordSelector) fn;
        return Codes.nth(recordSelector.slot);

      default:
        return null;
    }
  }

  private @Nullable Applicable toApplicable(
      Context cx, Object o, Type argType, Pos pos) {
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
          return (Applicable) literal.toBuiltIn(typeSystem, null);
      }
      final Code code = compile(cx, exp);
      return new Applicable() {
        @Override
        public Describer describe(Describer describer) {
          return code.describe(describer);
        }

        @Override
        public Object apply(EvalEnv evalEnv, Object arg) {
          throw new UnsupportedOperationException("use apply(Stack, Object)");
        }

        @Override
        public Object apply(Stack stack, Object arg) {
          return code.eval(stack);
        }
      };
    }
    return null;
  }

  private Code compileLet(Context cx, Core.Let let) {
    if (let.decl.op == Op.VAL_DECL) {
      final Code stackLetCode =
          tryCompileLetStack(cx, (Core.NonRecValDecl) let.decl, let.exp);
      if (stackLetCode != null) {
        return stackLetCode;
      }
    }
    final List<Code> matchCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    // For REC_VAL_DECL, collect peer pats and thread them into the compile
    // context so that closure bodies compile recursive refs as StackCode.
    final Context cxForDecl =
        let.decl.op == Op.REC_VAL_DECL
            ? cx.withRecPeers(let.decl.boundPats())
            : cx;
    compileValDecl(
        cxForDecl,
        let.decl,
        null,
        ImmutableSet.of(),
        matchCodes,
        bindings,
        null);
    // Assign stack slots to binding names so the result body can access them
    // via StackCode rather than GetCode (for both REC_VAL_DECL and VAL_DECL).
    final Context cx2 = buildLetContext(cx, bindings, matchCodes);
    final Code resultCode = compile(cx2, let.exp);
    return finishCompileLet(cx2, matchCodes, resultCode, let.type);
  }

  /**
   * Tries to compile a {@code let val x = expr in body} as a stack-based
   * push/pop. Returns null if the pattern is too complex (fall back to old-mode
   * let).
   */
  protected @Nullable Code tryCompileLetStack(
      Context cx, Core.NonRecValDecl valDecl, Core.Exp bodyExp) {
    if (valDecl.pat.op == Op.WILDCARD_PAT) {
      // "val _ = expr in body": evaluate expr for side effects, ignore result.
      final Code expCode = compile(cx, valDecl.exp);
      final Code bodyCode = compile(cx, bodyExp);
      return Codes.stackLet1(
          expCode, postProcessLetBody(cx, bodyCode, bodyExp.type));
    }
    if (valDecl.pat.op != Op.ID_PAT) {
      return null;
    }
    final Core.NamedPat xPat = (Core.NamedPat) valDecl.pat;
    // Detect the form "let val $tmp = expr in case $tmp of pat => body end",
    // which is how the Resolver desugars "let val (x, y) = expr in body end".
    // Compile directly as a multi-variable stack push, avoiding an intermediate
    // closure.
    if (bodyExp.op == Op.CASE) {
      final Core.Case case_ = (Core.Case) bodyExp;
      if (case_.matchList.size() == 1
          && case_.exp.op == Op.ID
          && ((Core.Id) case_.exp).idPat == xPat) {
        return compileLetStackPat(
            cx, valDecl.exp, case_.matchList.get(0), false);
      }
    }
    final Code expCode = compile(cx, valDecl.exp);
    final StackLayout newLayout = cx.layout.with(xPat, cx.localDepth);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeSystem, valDecl.pat, bindings);
    final Context cx2 =
        new Context(cx.env.bindAll(bindings), newLayout, cx.localDepth + 1);
    final Code bodyCode = compile(cx2, bodyExp);
    return Codes.stackLet1(
        expCode, postProcessLetBody(cx2, bodyCode, bodyExp.type));
  }

  /**
   * Tries to compile a {@code let val x = expr in body} as a stack-based
   * push/pop, with the body in tail position. Returns null if the pattern is
   * too complex (fall back to old-mode let).
   */
  protected @Nullable Code tryCompileLetStackTail(
      Context cx, Core.NonRecValDecl valDecl, Core.Exp bodyExp) {
    if (valDecl.pat.op == Op.WILDCARD_PAT) {
      // "val _ = expr in body": evaluate expr for side effects, ignore result.
      final Code expCode = compile(cx, valDecl.exp);
      final Code bodyCode = compileTail(cx, bodyExp);
      return Codes.stackLet1(
          expCode, postProcessLetBody(cx, bodyCode, bodyExp.type));
    }
    if (valDecl.pat.op != Op.ID_PAT) {
      return null;
    }
    final Core.NamedPat xPat = (Core.NamedPat) valDecl.pat;
    // Detect the desugared form (tail position); see tryCompileLetStack.
    if (bodyExp.op == Op.CASE) {
      final Core.Case case_ = (Core.Case) bodyExp;
      if (case_.matchList.size() == 1
          && case_.exp.op == Op.ID
          && ((Core.Id) case_.exp).idPat == xPat) {
        return compileLetStackPat(
            cx, valDecl.exp, case_.matchList.get(0), true);
      }
    }
    final Code expCode = compile(cx, valDecl.exp);
    final StackLayout newLayout = cx.layout.with(xPat, cx.localDepth);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeSystem, valDecl.pat, bindings);
    final Context cx2 =
        new Context(cx.env.bindAll(bindings), newLayout, cx.localDepth + 1);
    final Code bodyCode = compileTail(cx2, bodyExp);
    return Codes.stackLet1(
        expCode, postProcessLetBody(cx2, bodyCode, bodyExp.type));
  }

  /**
   * Compiles a let binding with a general pattern (tuple, record, etc.) as
   * direct stack pushes, avoiding an intermediate closure.
   *
   * <p>Called when the Resolver's desugared form {@code let val $tmp = expr in
   * case $tmp of pat => body end} is detected. Emits a {@link
   * Codes#stackLetPat} node that evaluates {@code valueExp}, calls {@link
   * Closure.StackClosure#pushBindings} to push each pattern-bound variable,
   * evaluates the body, then restores the stack.
   */
  private Code compileLetStackPat(
      Context cx, Core.Exp valueExp, Core.Match match, boolean tailPos) {
    final Core.Pat pat = match.pat;
    final Code expCode = compile(cx, valueExp);
    // Assign sequential slots starting at cx.localDepth, in the same order
    // that pushBindings pushes them.
    StackLayout newLayout = cx.layout;
    int depth = cx.localDepth;
    for (Core.NamedPat namedPat : pat.expand()) {
      newLayout = newLayout.with(namedPat, depth++);
    }
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeSystem, pat, bindings);
    final Context cx2 = new Context(cx.env.bindAll(bindings), newLayout, depth);
    final Code bodyCode =
        tailPos ? compileTail(cx2, match.exp) : compile(cx2, match.exp);
    final Code processedBody =
        postProcessLetBody(cx2, bodyCode, match.exp.type);
    final int varCount = depth - cx.localDepth;
    return Codes.stackLetPat(pat, varCount, expCode, processedBody, match.pos);
  }

  /** Hook for subclasses to post-process a compiled let body. */
  protected Code postProcessLetBody(Context cx, Code bodyCode, Type bodyType) {
    return bodyCode;
  }

  /**
   * Builds a let-body context with stack-slot assignments for all named
   * patterns in the match codes. The named patterns are assigned consecutive
   * slots starting at {@code cx.localDepth}, in the order that {@link
   * Closure.StackClosure#pushBindings} pushes them.
   *
   * <p>The returned context has {@code localDepth = cx.localDepth + numSlots}
   * so that body code compiled in it can access each binding via {@link
   * Codes#stackGet}.
   */
  private Context buildLetContext(
      Context cx, List<Binding> bindings, List<Code> matchCodes) {
    StackLayout newLayout = cx.layout;
    int depth = cx.localDepth;
    for (Code mc : matchCodes) {
      for (Core.Pat pat : ((MatchCode) mc).patCodes.leftList()) {
        for (Core.NamedPat namedPat : pat.expand()) {
          newLayout = newLayout.with(namedPat, depth++);
        }
      }
    }
    return new Context(
        cx.env.bindAll(bindings), newLayout, depth, cx.globalSlotMap);
  }

  protected Code finishCompileLet(
      Context cx, List<Code> matchCodes, Code resultCode, Type resultType) {
    // Extract (pat, expCode) pairs from the MatchCode wrappers and emit a
    // stack-based multi-let.
    final PairList<Core.Pat, Code> pairs = PairList.of();
    for (Code mc : matchCodes) {
      pairs.addAll(((MatchCode) mc).patCodes);
    }
    return Codes.stackMultiLet(pairs.immutable(), resultCode);
  }

  private Code compileLocal(Context cx, Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    compileDatatypeDecl(ImmutableList.of(local.dataType), bindings, null);
    Context cx2 = cx.bindAll(bindings);
    return compile(cx2, local.exp);
  }

  void compileDecl(
      Context cx,
      Core.Decl decl,
      Core.@Nullable NamedPat skipPat,
      Set<Core.Exp> queriesToWrap,
      List<Code> matchCodes,
      List<Binding> bindings,
      List<Action> actions) {
    switch (decl.op) {
      case VAL_DECL:
      case REC_VAL_DECL:
        final Core.ValDecl valDecl = (Core.ValDecl) decl;
        compileValDecl(
            cx, valDecl, skipPat, queriesToWrap, matchCodes, bindings, actions);
        break;

      case OVER_DECL:
        final Core.OverDecl overDecl = (Core.OverDecl) decl;
        compileOverDecl(overDecl, bindings, actions);
        break;

      case TYPE_DECL:
        final Core.TypeDecl typeDecl = (Core.TypeDecl) decl;
        compileTypeDecl(typeDecl.types, bindings, actions);
        break;

      case DATATYPE_DECL:
        final Core.DatatypeDecl datatypeDecl = (Core.DatatypeDecl) decl;
        compileDatatypeDecl(datatypeDecl.dataTypes, bindings, actions);
        break;

      default:
        throw new AssertionError("unknown " + decl.op + " [" + decl + "]");
    }
  }

  private void compileOverDecl(
      Core.OverDecl overDecl,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    bindings.add(Binding.over(overDecl.pat));
    if (actions != null) {
      actions.add(
          (outLines, outBindings, evalEnv) ->
              outLines.accept("over " + overDecl.pat));
    }
  }

  private void compileTypeDecl(
      List<AliasType> types,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    if (actions != null) {
      for (AliasType type : types) {
        actions.add(
            (outLines, outBindings, evalEnv) ->
                outLines.accept("type " + type.name + " = " + type.type.key()));
      }
    }
  }

  private void compileDatatypeDecl(
      List<DataType> dataTypes,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    for (DataType dataType : dataTypes) {
      final List<Binding> newBindings = new TailList<>(bindings);
      dataType
          .typeConstructors
          .keySet()
          .forEach(name -> bindings.add(typeSystem.bindTyCon(dataType, name)));
      if (actions != null) {
        final List<Binding> immutableBindings =
            ImmutableList.copyOf(newBindings);
        actions.add(
            (outLines, outBindings, evalEnv) -> {
              String line = dataType.describe(new StringBuilder()).toString();
              outLines.accept(line);
              immutableBindings.forEach(outBindings);
            });
      }
    }
  }

  /**
   * Compiles a {@code match} expression.
   *
   * @param cx Compile context
   * @param matchList List of Match
   * @return Code for match
   */
  private Code compileMatchList(Context cx, List<Core.Match> matchList) {
    return compileMatchListImpl(cx, matchList, false);
  }

  /**
   * Compiles an expression in tail position, emitting {@link Codes#tailApply}
   * at tail-call sites so that the trampoline in {@code Closure#bindEval} can
   * execute them in O(1) stack space.
   */
  protected Code compileTail(Context cx, Core.Exp expression) {
    switch (expression.op) {
      case APPLY:
        return compileApply(cx, (Core.Apply) expression, true);

      case CASE:
        final Core.Case case_ = (Core.Case) expression;
        final Code matchCode = compileMatchListTail(cx, case_.matchList);
        final Code argCode = compile(cx, case_.exp);
        return Codes.tailApply(matchCode, argCode);

      case LET:
        final Core.Let let = (Core.Let) expression;
        if (let.decl.op == Op.VAL_DECL) {
          final Code stackLetCode =
              tryCompileLetStackTail(
                  cx, (Core.NonRecValDecl) let.decl, let.exp);
          if (stackLetCode != null) {
            return stackLetCode;
          }
        }
        final List<Code> matchCodes = new ArrayList<>();
        final List<Binding> bindings = new ArrayList<>();
        final Context cxForDecl =
            let.decl.op == Op.REC_VAL_DECL
                ? cx.withRecPeers(let.decl.boundPats())
                : cx;
        compileValDecl(
            cxForDecl,
            let.decl,
            null,
            ImmutableSet.of(),
            matchCodes,
            bindings,
            null);
        final Context cx2 = buildLetContext(cx, bindings, matchCodes);
        final Code resultCode = compileTail(cx2, let.exp);
        return finishCompileLet(cx2, matchCodes, resultCode, let.type);

      default:
        return compile(cx, expression);
    }
  }

  /** Compiles a match list where each arm is in tail position. */
  private Code compileMatchListTail(Context cx, List<Core.Match> matchList) {
    return compileMatchListImpl(cx, matchList, true);
  }

  /**
   * Core implementation for compiling a match list.
   *
   * <p>Emits a {@link Codes.StackMatchCode} that captures currently live stack
   * variables and assigns inner slots for the pattern-bound variables.
   *
   * @param cx Outer compile context
   * @param matchList The match arms to compile
   * @param tailPos Whether the arm bodies are in tail position
   */
  private Code compileMatchListImpl(
      Context cx, List<Core.Match> matchList, boolean tailPos) {
    // Stack-mode: collect variables currently live in the outer stack layout.
    // These become the captured variables in the new StackClosure.
    // We use a LinkedHashMap to maintain a stable insertion order.
    final LinkedHashMap<Core.NamedPat, Integer> captureMap =
        new LinkedHashMap<>();
    // Collect variables referenced in each arm's body that are live in the
    // outer layout. Exclude variables bound by each arm's own pattern, because
    // those are freshly bound (not captured) at the inner level.
    for (Core.Match match : matchList) {
      final List<Core.NamedPat> patVars = match.pat.expand();
      collectReferencedStackVars(
          cx, match.exp, ImmutableSet.copyOf(patVars), captureMap);
    }

    // Build capture offsets: for each captured var, compute 1-based offset from
    // the current stack top.
    final int[] captureOffsets = new int[captureMap.size()];
    for (Map.Entry<Core.NamedPat, Integer> e : captureMap.entrySet()) {
      final int outerSlot = cx.layout.get(e.getKey());
      // offset = localDepth - outerSlot (1-based from top = localDepth)
      captureOffsets[e.getValue()] = cx.localDepth - outerSlot;
    }

    // Compile each arm with a fresh inner context.
    final PairList<Core.Pat, Code> patCodes = PairList.of();
    for (Core.Match match : matchList) {
      // Build inner layout:
      //   slots 0..numCapture-1: captured outer vars
      //   slots numCapture..numCapture+numRecPeers-1: rec-group peers (if any)
      //   slots numCapture+numRecPeers..: arg vars
      StackLayout innerLayout = StackLayout.EMPTY;
      for (Map.Entry<Core.NamedPat, Integer> e : captureMap.entrySet()) {
        innerLayout = innerLayout.with(e.getKey(), e.getValue());
      }
      int depth = captureMap.size();
      for (final Core.NamedPat namedPat : cx.recPeers) {
        innerLayout = innerLayout.with(namedPat, depth++);
      }
      for (Core.NamedPat argPat : match.pat.expand()) {
        innerLayout = innerLayout.with(argPat, depth++);
      }

      final List<Binding> bindings = new ArrayList<>();
      Compiles.acceptBinding(typeSystem, match.pat, bindings);
      // Fresh context: no globalSlotMap, no recPeers (nested closures start
      // a new scope).
      final Context innerCx =
          new Context(cx.env.bindAll(bindings), innerLayout, depth);

      final Code bodyCode =
          tailPos
              ? compileTail(innerCx, match.exp)
              : compile(innerCx, match.exp);
      patCodes.add(match.pat, bodyCode);
    }

    // Compute minimum slots needed when this closure is invoked fresh
    // (no pre-existing Stack). Max over all arms of:
    //   captureOffsets.length + numRecPeers + numArgVars + bodyCode.maxSlots()
    int capacity = 0;
    for (Map.Entry<Core.Pat, Code> e : patCodes) {
      final int numArgs = e.getKey().expand().size();
      capacity =
          Math.max(
              capacity,
              captureOffsets.length
                  + ((List<Core.NamedPat>) cx.recPeers).size()
                  + numArgs
                  + e.getValue().maxSlots());
    }

    return new Codes.StackMatchCode(
        captureOffsets,
        cx.recPeers.size(),
        patCodes.immutable(),
        capacity,
        last(matchList).pos);
  }

  /**
   * Collects stack variables from {@code exp} that are in {@code cx.layout}
   * into {@code captureMap}, assigning each a unique capture index.
   *
   * <p>Walks the expression tree (including into nested {@code fn} bodies) and
   * records any ID reference whose pattern is allocated in the outer layout.
   */
  private static void collectReferencedStackVars(
      Context cx,
      Core.Exp exp,
      Set<Core.NamedPat> excludePats,
      LinkedHashMap<Core.NamedPat, Integer> captureMap) {
    collectReferencedStackVarsRec(cx.layout, exp, excludePats, captureMap);
  }

  private static void collectReferencedStackVarsRec(
      StackLayout layout,
      Core.Exp exp,
      Set<Core.NamedPat> excludePats,
      LinkedHashMap<Core.NamedPat, Integer> captureMap) {
    switch (exp.op) {
      case ID:
        final Core.Id id = (Core.Id) exp;
        if (layout.get(id.idPat) >= 0 && !excludePats.contains(id.idPat)) {
          captureMap.computeIfAbsent(id.idPat, k -> captureMap.size());
        }
        break;
      case LET:
        final Core.Let let = (Core.Let) exp;
        // Visit the decl's expressions
        let.decl.forEachBinding(
            (pat, e, overloadPat, pos) ->
                collectReferencedStackVarsRec(
                    layout, e, excludePats, captureMap));
        collectReferencedStackVarsRec(layout, let.exp, excludePats, captureMap);
        break;
      case FN:
        final Core.Fn fn = (Core.Fn) exp;
        // The fn body uses a new scope; we still need to check references in it
        // that point to the outer layout.
        collectReferencedStackVarsRec(layout, fn.exp, excludePats, captureMap);
        break;
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        collectReferencedStackVarsRec(
            layout, apply.fn, excludePats, captureMap);
        collectReferencedStackVarsRec(
            layout, apply.arg, excludePats, captureMap);
        break;
      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        tuple.args.forEach(
            e ->
                collectReferencedStackVarsRec(
                    layout, e, excludePats, captureMap));
        break;
      case CASE:
        final Core.Case case_ = (Core.Case) exp;
        collectReferencedStackVarsRec(
            layout, case_.exp, excludePats, captureMap);
        case_.matchList.forEach(
            m ->
                collectReferencedStackVarsRec(
                    layout, m.exp, excludePats, captureMap));
        break;
      case LOCAL:
        final Core.Local local = (Core.Local) exp;
        collectReferencedStackVarsRec(
            layout, local.exp, excludePats, captureMap);
        break;
      case FROM:
        final Core.From from = (Core.From) exp;
        for (Core.FromStep step : from.steps) {
          if (step instanceof Core.Scan) {
            final Core.Scan scan = (Core.Scan) step;
            collectReferencedStackVarsRec(
                layout, scan.exp, excludePats, captureMap);
            collectReferencedStackVarsRec(
                layout, scan.condition, excludePats, captureMap);
          } else if (step instanceof Core.Where) {
            collectReferencedStackVarsRec(
                layout, ((Core.Where) step).exp, excludePats, captureMap);
          } else if (step instanceof Core.Skip) {
            collectReferencedStackVarsRec(
                layout, ((Core.Skip) step).exp, excludePats, captureMap);
          } else if (step instanceof Core.Take) {
            collectReferencedStackVarsRec(
                layout, ((Core.Take) step).exp, excludePats, captureMap);
          } else if (step instanceof Core.Order) {
            collectReferencedStackVarsRec(
                layout, ((Core.Order) step).exp, excludePats, captureMap);
          } else if (step instanceof Core.Group) {
            final Core.Group group = (Core.Group) step;
            group
                .groupExps
                .values()
                .forEach(
                    e ->
                        collectReferencedStackVarsRec(
                            layout, e, excludePats, captureMap));
            group
                .aggregates
                .values()
                .forEach(
                    agg -> {
                      collectReferencedStackVarsRec(
                          layout, agg.aggregate, excludePats, captureMap);
                      if (agg.argument != null) {
                        collectReferencedStackVarsRec(
                            layout, agg.argument, excludePats, captureMap);
                      }
                    });
          } else if (step instanceof Core.Yield) {
            collectReferencedStackVarsRec(
                layout, ((Core.Yield) step).exp, excludePats, captureMap);
          } else if (step instanceof Core.SetStep) {
            // Handles Core.Union, Core.Intersect, Core.Except
            ((Core.SetStep) step)
                .args.forEach(
                    arg ->
                        collectReferencedStackVarsRec(
                            layout, arg, excludePats, captureMap));
          }
          // Unorder has no sub-expressions.
        }
        break;

      default:
        // Literals and other leaf nodes: no ID references.
        break;
    }
  }

  private void compileValDecl(
      Context cx,
      Core.ValDecl valDecl,
      Core.@Nullable Pat skipPat,
      Set<Core.Exp> queriesToWrap,
      List<Code> matchCodes,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    Compiles.bindPattern(typeSystem, bindings, valDecl);
    final List<Binding> newBindings = new TailList<>(bindings);
    final Map<Core.NamedPat, LinkCode> linkCodes = new HashMap<>();
    if (valDecl.op == Op.REC_VAL_DECL) {
      valDecl.forEachBinding(
          (pat, exp, overloadPat, pos) ->
              addLinkCodes(pat, linkCodes, bindings));
    }

    final Context cx1 = cx.bindAll(newBindings);
    valDecl.forEachBinding(
        (pat, exp, overloadPat, pos) -> {
          // Using 'compileArg' rather than 'compile' encourages CalciteCompiler
          // to use a pure Calcite implementation if possible, and has no effect
          // in the basic Compiler.
          final Code code0;
          if (actions != null) {
            // Top-level REPL statement: marshal referenced globals onto the
            // stack so the body can access them via fast StackCode reads.
            final Map<String, Integer> m = new LinkedHashMap<>();
            GlobalFreeVarCollector.collect(
                typeSystem, cx1.env, exp, s -> m.putIfAbsent(s, m.size()));
            if (!m.isEmpty()) {
              final Context cx1g =
                  new Context(
                      cx1.env, cx1.layout, cx1.localDepth + m.size(), m);
              code0 =
                  Codes.globalMarshal(
                      cx1g.globalSlotMap, compileArg(cx1g, exp));
            } else {
              code0 = compileArg(cx1, exp);
            }
          } else {
            code0 = compileArg(cx1, exp);
          }
          final Code code =
              queriesToWrap.contains(exp) ? Codes.wrapRelList(code0) : code0;
          if (!linkCodes.isEmpty()) {
            link(linkCodes, pat, code);
          }
          matchCodes.add(new MatchCode(ImmutablePairList.of(pat, code), pos));

          if (actions != null) {
            actions.add(
                new ActionImpl(
                    typeSystem, code, pat, exp, overloadPat, pos, skipPat));
          }
        });

    newBindings.clear();
  }

  /**
   * Recursively creates {@link LinkCode}s for all named patterns in {@code
   * pat}, adding them to {@code linkCodes} and {@code bindings}.
   *
   * <p>Handles {@link Core.IdPat}, {@link Core.AsPat}, and {@link
   * Core.TuplePat} (so that mutual-recursion patterns like {@code it as (f, g)}
   * correctly expose {@code f} and {@code g} in the compile context).
   */
  private void addLinkCodes(
      Core.Pat pat,
      Map<Core.NamedPat, LinkCode> linkCodes,
      List<Binding> bindings) {
    if (pat instanceof Core.IdPat || pat instanceof Core.AsPat) {
      final Core.NamedPat namedPat = (Core.NamedPat) pat;
      final LinkCode linkCode = new LinkCode();
      linkCodes.put(namedPat, linkCode);
      bindings.add(Binding.of(namedPat, linkCode));
      if (pat instanceof Core.AsPat) {
        addLinkCodes(((Core.AsPat) pat).pat, linkCodes, bindings);
      }
    } else if (pat instanceof Core.TuplePat) {
      ((Core.TuplePat) pat)
          .args.forEach(p -> addLinkCodes(p, linkCodes, bindings));
    }
  }

  private void link(
      Map<Core.NamedPat, LinkCode> linkCodes, Core.Pat pat, Code code) {
    if (pat instanceof Core.IdPat) {
      final LinkCode linkCode = linkCodes.get(pat);
      if (linkCode != null) {
        linkCode.refCode = code; // link the reference to the definition
      }
    } else if (pat instanceof Core.AsPat) {
      final Core.AsPat asPat = (Core.AsPat) pat;
      final LinkCode linkCode = linkCodes.get(asPat);
      if (linkCode != null) {
        linkCode.refCode = code;
      }
      link(linkCodes, asPat.pat, code);
    } else if (pat instanceof Core.TuplePat) {
      if (code instanceof Codes.TupleCode) {
        // Recurse into the tuple, binding names to code in parallel
        final List<Code> codes = ((Codes.TupleCode) code).codes;
        final List<Core.Pat> pats = ((Core.TuplePat) pat).args;
        forEach(codes, pats, (code1, pat1) -> link(linkCodes, pat1, code1));
      }
    }
  }

  /**
   * A piece of code that is references another piece of code. It is useful when
   * defining recursive functions. The reference is mutable, and is fixed up
   * when the function has been compiled.
   */
  private static class LinkCode implements Code {
    private @Nullable Code refCode;

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "link",
          d -> {
            if (false) {
              // Don't recurse into refCode... or we'll never get out alive.
              d.arg("refCode", refCode);
            }
          });
    }

    @Override
    public Object eval(Stack stack) {
      assert refCode != null;
      return refCode.eval(stack);
    }
  }

  /** Code that creates a {@link Closure} for a match expression. */
  private static class MatchCode implements Code {
    private final ImmutablePairList<Core.Pat, Code> patCodes;
    private final Pos pos;

    MatchCode(ImmutablePairList<Core.Pat, Code> patCodes, Pos pos) {
      this.patCodes = patCodes;
      this.pos = pos;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "match",
          d ->
              patCodes.forEach(
                  (pat, code) ->
                      d.arg("", pat.describe(describer)).arg("", code)));
    }
  }

  /** Application of a function to a sequence or tuple of arguments. */
  private static class Gather {
    final Core.Literal fnLiteral;
    final List<Core.Exp> args;

    private Gather(Core.Literal fnLiteral, List<Core.Exp> args) {
      this.fnLiteral = requireNonNull(fnLiteral);
      this.args = ImmutableList.copyOf(args);
    }

    private Gather(Core.Literal fnLiteral, Core.Exp... args) {
      this(fnLiteral, ImmutableList.copyOf(args));
    }

    @Override
    public String toString() {
      return format("Gather{fnLiteral=%s, args=%s}", fnLiteral, args);
    }

    static @Nullable Gather of(Core.Apply apply) {
      switch (apply.fn.op) {
        case FN_LITERAL:
          if (apply.arg instanceof Core.Tuple) {
            Core.Tuple arg = (Core.Tuple) apply.arg;
            final int a = arity(apply.fn);
            if (a == arg.args.size() && a <= 4) {
              return new Gather((Core.Literal) apply.fn, arg.args);
            }
          }
          return new Gather((Core.Literal) apply.fn, apply.arg);
        default:
          return of2(apply.fn, ImmutableList.of(apply.arg));
      }
    }

    static @Nullable Gather of2(Core.Exp fn, List<Core.Exp> args) {
      switch (fn.op) {
        case FN_LITERAL:
          return new Gather((Core.Literal) fn, args);
        case APPLY:
          final Core.Apply apply = (Core.Apply) fn;
          if (apply.arg.op == Op.TUPLE || apply.arg.op == Op.RECORD) {
            // A mixture of tuples and chained arguments,
            // such as "General.o (f, g) arg", is not a valid Gather.
            return null;
          }
          return of2(apply.fn, plus(apply.arg, args));
        default:
          return null;
      }
    }

    /**
     * Returns whether the argument at index {@code i} is a tuple of {@code
     * arity}.
     */
    public boolean argIsTuple(int i, int arity) {
      return arity(fnLiteral) == arity
          && i < args.size()
          && (args.get(i).type instanceof TupleType
              || args.get(i).type instanceof RecordType)
          && ((RecordLikeType) args.get(i).type).argTypes().size() == arity;
    }
  }

  private static class ActionImpl implements Action {
    private final TypeSystem typeSystem;
    private final Code code;
    private final Core.NamedPat pat;
    private final Core.Exp exp;
    private final Type type;
    private final Core.@Nullable IdPat overloadPat;
    private final Pos pos;
    private final Core.@Nullable Pat skipPat;

    ActionImpl(
        TypeSystem typeSystem,
        Code code,
        Core.NamedPat pat,
        Core.Exp exp,
        Core.@Nullable IdPat overloadPat,
        Pos pos,
        Core.@Nullable Pat skipPat) {
      this.typeSystem = typeSystem;
      this.code = code;
      this.pat = pat;
      this.exp = exp;
      final Type type0 = pat.type.containsProgressive() ? exp.type : pat.type;
      this.type = typeSystem.ensureClosed(type0);
      this.overloadPat = overloadPat;
      this.pos = pos;
      this.skipPat = skipPat;
    }

    @Override
    public void apply(
        Consumer<String> outLines,
        Consumer<Binding> outBindings,
        EvalEnv evalEnv) {
      final Session session = evalEnv.getSession();
      final StringBuilder buf = new StringBuilder();
      final List<String> outs = new ArrayList<>();
      // Rebuild session.globalEnv as a flat map from the current compilation
      // env so closures created during eval (which capture session) start with
      // a valid globalEnv.
      session.globalEnv = Codes.globalEnvOf(evalEnv);
      try {
        final Object o =
            code.eval(new Stack(session, Math.max(code.maxSlots(), 256)));
        final List<Binding> outBindings0 = new ArrayList<>();
        // For simple IdPat bindings, store the expression so it can be inlined
        // in subsequent compile units. For compound patterns (tuples), we don't
        // track sub-expressions.
        final Core.@Nullable Exp expForBinding =
            pat instanceof Core.IdPat ? exp : null;
        if (!Closure.bindRecurse(
            pat.withType(type),
            o,
            (pat2, o2) ->
                outBindings0.add(
                    overloadPat == null
                        ? Binding.of(pat2, expForBinding, o2)
                        : Binding.inst(
                            pat2, overloadPat, expForBinding, o2)))) {
          throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
        }
        // Add the new bindings to session.globalEnv so closures created by
        // this statement automatically see the latest bindings (including
        // themselves) when they are eventually invoked. The synthetic 'it'
        // binding produced for composite val declarations (skipPat) is
        // intentionally omitted: it must not become user-visible.
        for (Binding b : outBindings0) {
          if (b.id == skipPat) {
            continue;
          }
          session.globalEnv.put(b.id.name, b.value);
        }
        for (Binding binding : outBindings0) {
          if (binding.id == skipPat) {
            continue;
          }
          outBindings.accept(binding);
          final Pretty pretty = getPretty(session.map, session.bagPrinter());
          final Core.NamedPat id =
              binding.overloadId != null ? binding.overloadId : binding.id;
          final Pretty.TypedVal typedVal = getTypedVal(binding, id);
          pretty.pretty(buf, binding.id.type, typedVal);
          final String line = str(buf);
          outs.add(line);
          outLines.accept(line);
        }
      } catch (Codes.MorelRuntimeException e) {
        session.handle(e, buf);
        final String line = str(buf);
        outs.add(line);
        outLines.accept(line);
      }
      session.code = code;
      session.out = ImmutableList.copyOf(outs);
    }

    private Pretty.TypedVal getTypedVal(Binding binding, Core.NamedPat id) {
      if (binding.value instanceof TypedValue) {
        TypedValue typedValue = (TypedValue) binding.value;
        return new Pretty.TypedVal(
            id.name,
            typedValue.valueAs(Object.class),
            Keys.toProgressive(binding.id.type().key()).toType(typeSystem));
      } else {
        return new Pretty.TypedVal(id.name, binding.value, binding.id.type);
      }
    }

    private Pretty getPretty(Map<Prop, Object> map, BagPrinter bagPrinter) {
      int stringDepth = Prop.STRING_DEPTH.intValue(map);
      int lineWidth = Prop.LINE_WIDTH.intValue(map);
      Prop.Output output = Prop.OUTPUT.enumValue(map, Prop.Output.class);
      int printDepth = Prop.PRINT_DEPTH.intValue(map);
      int printLength = Prop.PRINT_LENGTH.intValue(map);
      return new Pretty(
          typeSystem,
          lineWidth,
          output,
          printLength,
          printDepth,
          stringDepth,
          bagPrinter);
    }
  }
}

// End Compiler.java
