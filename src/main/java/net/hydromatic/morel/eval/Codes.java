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
package net.hydromatic.morel.eval;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.eval.Slots.maxOf;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Static.SKIP;
import static net.hydromatic.morel.util.Static.floatToString;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.eval.code.DateCodes;
import net.hydromatic.morel.eval.code.DecimalCodes;
import net.hydromatic.morel.eval.code.GeneralCodes;
import net.hydromatic.morel.eval.code.IntCodes;
import net.hydromatic.morel.eval.code.ListCodes;
import net.hydromatic.morel.eval.code.OptionCodes;
import net.hydromatic.morel.eval.code.RangeCodes;
import net.hydromatic.morel.eval.code.RealCodes;
import net.hydromatic.morel.eval.code.RelationalCodes;
import net.hydromatic.morel.eval.code.StringCodes;
import net.hydromatic.morel.eval.code.SysCodes;
import net.hydromatic.morel.eval.code.VectorCodes;
import net.hydromatic.morel.eval.code.WordCodes;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/** Helpers for {@link Code}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Codes {

  private Codes() {}

  /**
   * Pattern for integers (after '~' has been converted to '-'). ".", ".e",
   * ".e-", ".e5", "e7", "2.", ".5", "2.e5" are invalid; "-2", "5" are valid.
   */
  static final Pattern INT_PATTERN = Pattern.compile("^ *-?[0-9]+");

  /**
   * Converts a Java {@code int} value to the format expected of Standard ML
   * {@code int} values.
   */
  public static String intToString(int i) {
    // Java's formatting is reasonably close to ML's formatting,
    // if we replace minus signs.
    final String s = Integer.toString(i);
    return s.replace('-', '~');
  }

  /**
   * Value of {@link BuiltIn.Constructor#OPTION_NONE}.
   *
   * @see #optionSome(Object)
   */
  public static final List OPTION_NONE = ImmutableList.of("NONE");

  /**
   * Creates a value of {@code SOME v}.
   *
   * @see net.hydromatic.morel.compile.BuiltIn.Constructor#OPTION_SOME
   * @see #OPTION_NONE
   */
  public static List optionSome(Object o) {
    return ImmutableList.of(BuiltIn.Constructor.OPTION_SOME.constructor, o);
  }

  /**
   * Pattern for floating point numbers (after '~' has been converted to '-').
   * ".", ".e", ".e-", ".e5", "e7" are invalid; "2.", ".5", "2.e5", "2.e" are
   * valid.
   */
  static final Pattern FLOAT_PATTERN =
      Pattern.compile("^ *-?([0-9]*\\.)?[0-9]+([Ee]-?[0-9]+)?");

  /**
   * Parses an integer from a string that may use Standard ML negation syntax.
   *
   * @param s String to parse (may use ~ or - for negation)
   * @return Ord containing the parsed integer and character count, or null if
   *     the string does not start with a valid integer
   */
  static @Nullable Ord<Integer> parseInt(String s) {
    final String s2 = s.replace('~', '-');
    final Matcher matcher = INT_PATTERN.matcher(s2);
    if (!matcher.find(0)) {
      return null;
    }
    final String s3 = s2.substring(0, matcher.end());
    try {
      final int value = Integer.parseInt(s3);
      return Ord.of(matcher.end(), value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parses a Morel {@code real} value (Java {@code float}) from a string that
   * may use Standard ML negation syntax.
   *
   * @param s String to parse (may use either "~" or "-" for negation)
   * @param strict Whether the string must be a real, not an integer
   * @return Ord containing the parsed float and character count, or null if the
   *     string does not start with a valid real number
   */
  public static @Nullable Ord<Float> parseReal(String s, boolean strict) {
    final String s2 = s.replace('~', '-');
    final Matcher matcher = FLOAT_PATTERN.matcher(s2);
    if (!matcher.find(0)) {
      return null;
    }
    final String s3 = s2.substring(0, matcher.end());
    if (strict && !s3.contains(".") && !s3.contains("e") && !s3.contains("E")) {
      return null;
    }
    try {
      final float value = Float.parseFloat(s3);
      return Ord.of(matcher.end(), value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Returns whether a {@code float} is negative. This is the same as the
   * specification of {@code Real.signBit}.
   */
  @VisibleForTesting
  public static boolean isNegative(float f) {
    // Return the raw sign bit, even for a NaN (we no longer invert it to make a
    // NaN look negative). The sign of a NaN produced by arithmetic is
    // unspecified, so callers must not assume it; 'abs' forces a definite sign.
    return (Float.floatToRawIntBits(f) & 0x8000_0000) == 0x8000_0000;
  }

  /** Value of {@link BuiltIn.Constructor#VARIANT_UNIT}. */
  public static final List VARIANT_UNIT =
      Variant.of(PrimitiveType.UNIT, Unit.INSTANCE);

  // ---------------------------------------------------------------------------

  private static void populateBuiltIns(Map<String, Object> valueMap) {
    if (SKIP) {
      return;
    }
    // Dummy type system, thrown away after this method
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    BuiltIn.forEach(
        typeSystem,
        (key, type) -> {
          final Object value = BUILT_IN_VALUES.get(key);
          if (value == null) {
            throw new AssertionError("no implementation for " + key);
          }
          if (key.structure.equals("Top")) {
            valueMap.put(key.mlName, value);
          }
          for (String alias : key.aliases()) {
            valueMap.put(alias, value);
          }
        });
    BuiltIn.forEachStructure(
        typeSystem,
        (structure, type) ->
            valueMap.put(
                structure.name,
                transformEager(
                    structure.memberMap.values(), BUILT_IN_VALUES::get)));
  }

  /** Describes a {@link Code}. */
  public static String describe(Code code) {
    final Code code2 = strip(code);
    return code2.describe(new DescriberImpl()).toString();
  }

  /**
   * Removes wrappers, in particular the one due to {@link #wrapRelList(Code)}.
   */
  public static Code strip(Code code) {
    for (; ; ) {
      if (code instanceof WrapRelList) {
        code = ((WrapRelList) code).code;
      } else {
        return code;
      }
    }
  }

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Object value) {
    return new ConstantCode(value);
  }

  /** Returns a Code that evaluates "andalso". */
  public static Code andAlso(Code code0, Code code1) {
    return new AndAlsoCode(code0, code1);
  }

  /** Returns a Code that evaluates "orelse". */
  public static Code orElse(Code code0, Code code1) {
    return new OrElseCode(code0, code1);
  }

  /** Returns a Code that implements a {@code raise} expression. */
  public static Code raise(Code expCode, Pos pos) {
    return new RaiseCode(expCode, pos);
  }

  /** @see BuiltIn#Z_ORDINAL */
  public static Code ordinalGet(int[] ordinalSlots) {
    return new OrdinalGetCode(ordinalSlots);
  }

  /** Helper for {@link #ordinalGet(int[])}. */
  public static Code ordinalInc(int[] ordinalSlots, Code nextCode) {
    return new OrdinalIncCode(ordinalSlots, nextCode);
  }

  /**
   * Returns a Code that returns the value of variable "name" in the current
   * environment.
   */
  public static Code get(String name) {
    return new GetCode(name);
  }

  /**
   * Returns a {@link Code} that pushes a set of global values onto the stack
   * before evaluating {@code body}, then restores the stack top afterward.
   *
   * <p>At statement-evaluation time, the wrapper fetches each name from {@link
   * Session#globalEnv} once and stores the value in a stack slot, so that the
   * body can access those globals via fast {@link StackCode} reads instead of
   * repeated {@link GetCode} / {@code EvalEnv} lookups.
   *
   * <p>If {@code names} is empty the body is returned unchanged.
   */
  public static Code globalMarshal(Map<String, Integer> slotMap, Code body) {
    return slotMap.isEmpty()
        ? body
        : new GlobalMarshalCode(ImmutableList.copyOf(slotMap.keySet()), body);
  }

  /**
   * Returns a Code that retrieves a local variable from the stack at {@code
   * offset} slots below the top.
   *
   * <p>{@code offset} is 1-based relative to {@link Stack#top}: the most
   * recently pushed value is at offset 1, the one before that at offset 2, etc.
   * The {@code name} field is retained for debuggability and appears in {@link
   * net.hydromatic.morel.eval.Describer} / {@code Sys.plan} output.
   */
  public static Code stackGet(int offset, String name) {
    return new StackCode(offset, name);
  }

  /**
   * Generates the code for applying a function (or function value) to an
   * argument.
   */
  public static Code apply(Code fnCode, Code argCode) {
    assert !fnCode.isConstant(); // if constant, use "apply(Closure, Code)"
    return new ApplyCodeCode(fnCode, argCode);
  }

  /** Generates the code for applying a function value to an argument. */
  public static Code apply(Applicable fnValue, Code argCode) {
    return new ApplyCode(fnValue, argCode);
  }

  /** Generates tail-call code for a dynamic function application. */
  public static Code tailApply(Code fnCode, Code argCode) {
    return new TailApplyCodeCode(fnCode, argCode);
  }

  /** Generates tail-call code for a known function application. */
  public static Code tailApply(Applicable fnValue, Code argCode) {
    return new TailApplyCode(fnValue, argCode);
  }

  /** Generates the code for applying a function value to an argument. */
  public static Code apply1(Applicable1 fnValue, Code argCode) {
    return new ApplyCode1(fnValue, argCode);
  }

  /** Generates the code for applying a function value to two arguments. */
  public static Code apply2(Applicable2 fnValue, Code argCode0, Code argCode1) {
    return new ApplyCode2(fnValue, argCode0, argCode1);
  }

  /** Generates the code for applying a function value to a 2-tuple argument. */
  public static Code apply2Tuple(Applicable2 fnValue, Code argCode0) {
    return new ApplyCode2Tuple(fnValue, argCode0);
  }

  /** Generates the code for applying a function value to three arguments. */
  public static Code apply3(
      Applicable3 fnValue, Code argCode0, Code argCode1, Code argCode2) {
    return new ApplyCode3(fnValue, argCode0, argCode1, argCode2);
  }

  /** Generates the code for applying a function value to a 3-tuple argument. */
  public static Code apply3Tuple(Applicable3 fnValue, Code argCode0) {
    return new ApplyCode3Tuple(fnValue, argCode0);
  }

  /** Generates the code for applying a function value to four arguments. */
  public static Code apply4(
      Applicable4 fnValue,
      Code argCode0,
      Code argCode1,
      Code argCode2,
      Code argCode3) {
    return new ApplyCode4(fnValue, argCode0, argCode1, argCode2, argCode3);
  }

  public static Code list(Iterable<? extends Code> codes) {
    return tuple(codes);
  }

  public static Code tuple(Iterable<? extends Code> codes) {
    return new TupleCode(ImmutableList.copyOf(codes));
  }

  public static Code wrapRelList(Code code) {
    return new WrapRelList(code);
  }

  /** Creates an empty evaluation environment. */
  public static EvalEnv emptyEnv() {
    return EMPTY_ENV;
  }

  /**
   * Creates an evaluation environment that contains the bound values from a
   * compilation environment.
   */
  public static EvalEnv emptyEnvWith(Session session, Environment env) {
    final Map<String, Object> map = EMPTY_ENV.valueMap();
    env.forEachValue(map::put);
    map.put(EvalEnv.SESSION, session);
    return EvalEnvs.copyOf(map);
  }

  /**
   * Creates a flat {@link Map} for {@link Session#globalEnv} from an evaluation
   * environment, excluding the internal {@code $session} binding.
   */
  public static Map<String, Object> globalEnvOf(EvalEnv evalEnv) {
    final Map<String, Object> map = new HashMap<>();
    evalEnv.visit(
        (k, v) -> {
          if (!EvalEnv.SESSION.equals(k)) {
            map.put(k, v);
          }
        });
    return map;
  }

  /** Creates a compilation environment. */
  public static Environment env(
      TypeSystem typeSystem, Environment environment) {
    final Environment[] hEnv = {environment};
    BUILT_IN_VALUES.forEach(
        (key, value) -> {
          final Type type = key.typeFunction.apply(typeSystem);
          if (key.structure.equals("Top")) {
            final Core.IdPat idPat =
                core.idPat(type, key.mlName, typeSystem.nameGenerator::inc);
            hEnv[0] = hEnv[0].bind(idPat, value);
          }
          for (String alias : key.aliases()) {
            final Core.IdPat idPat =
                core.idPat(type, alias, typeSystem.nameGenerator::inc);
            hEnv[0] = hEnv[0].bind(idPat, value);
          }
        });

    final List<Object> valueList = new ArrayList<>();
    BuiltIn.forEachStructure(
        typeSystem,
        (structure, type) -> {
          valueList.clear();
          structure
              .memberMap
              .values()
              .forEach(builtIn -> valueList.add(BUILT_IN_VALUES.get(builtIn)));
          final Core.IdPat idPat =
              core.idPat(type, structure.name, typeSystem.nameGenerator::inc);
          hEnv[0] = hEnv[0].bind(idPat, ImmutableList.copyOf(valueList));
        });
    return hEnv[0];
  }

  public static Applicable aggregate(
      Environment env0,
      Code aggregateCode,
      List<String> names,
      @Nullable Code argumentCode,
      int scanDepth) {
    return new Applicable() {
      @Override
      public Describer describe(Describer describer) {
        return describer.start("aggregate", d -> {});
      }

      @Override
      public Object apply(Stack stack, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> rows = (List<Object>) arg;
        final List<Object> argRows;
        if (argumentCode != null) {
          argRows = new ArrayList<>(rows.size());
          final int envCount = names.size() - scanDepth;
          if (names.size() == 1) {
            if (scanDepth == 1) {
              // Single stack-based variable: push value, eval, restore.
              Stack s = stack.ensureSize(1);
              final int savedTop = s.top;
              for (Object row : rows) {
                s.push(row);
                argRows.add(argumentCode.eval(s));
                s.restore(savedTop);
              }
            } else {
              // Single env-based variable (scanDepth == 0): bind into
              // session.globalEnv so GetCode can find it.
              final Map<String, Object> env = stack.currentEnv();
              final String varName = names.get(0);
              final Object savedValue = env.get(varName);
              try {
                for (Object row : rows) {
                  env.put(varName, row);
                  argRows.add(argumentCode.eval(stack));
                }
              } finally {
                if (savedValue == null) {
                  env.remove(varName);
                } else {
                  env.put(varName, savedValue);
                }
              }
            }
          } else if (envCount == 0) {
            // All variables are stack-based: push-back only, no env binding.
            Stack s = stack.ensureSize(scanDepth);
            final int savedTop = s.top;
            for (Object row : rows) {
              final Object[] arr = (Object[]) row;
              for (int j = 0; j < scanDepth; j++) {
                s.push(arr[j]);
              }
              argRows.add(argumentCode.eval(s));
              s.restore(savedTop);
            }
          } else {
            // Mixed: push stack-based vars, bind env-based vars per row via
            // session.globalEnv so GetCode can find them.
            Stack s = stack.ensureSize(scanDepth);
            final int savedTop = s.top;
            final Map<String, Object> env = stack.currentEnv();
            // Save old values for the env-based variables.
            final int envCount2 = names.size() - scanDepth;
            final Object[] savedEnvValues = new Object[envCount2];
            for (int j = 0; j < envCount2; j++) {
              savedEnvValues[j] = env.get(names.get(scanDepth + j));
            }
            try {
              for (Object row : rows) {
                final Object[] arr = (Object[]) row;
                for (int j = 0; j < scanDepth; j++) {
                  s.push(arr[j]);
                }
                for (int j = scanDepth; j < names.size(); j++) {
                  env.put(names.get(j), arr[j]);
                }
                argRows.add(argumentCode.eval(s));
                s.restore(savedTop);
              }
            } finally {
              for (int j = 0; j < envCount2; j++) {
                final Object saved = savedEnvValues[j];
                if (saved == null) {
                  env.remove(names.get(scanDepth + j));
                } else {
                  env.put(names.get(scanDepth + j), saved);
                }
              }
            }
          }
        } else if (names.size() != 1) {
          // Reconcile the fact that we internally represent rows as arrays when
          // we're buffering for "group", lists at other times.
          argRows = transform(rows, row -> Arrays.asList((Object[]) row));
        } else {
          argRows = rows;
        }
        // An aggregate function is usually an Applicable, but a partially
        // applied built-in (say 'maxBy keyFn') is a plain Applicable1.
        final Object aggregate = aggregateCode.eval(stack);
        return aggregate instanceof Applicable
            ? ((Applicable) aggregate).apply(stack, argRows)
            : ((Applicable1) aggregate).apply(argRows);
      }
    };
  }

  public static final ImmutableMap<BuiltIn, Object> BUILT_IN_VALUES;

  static {
    final PairList<BuiltIn, Object> b = PairList.of();
    ListCodes.register(b::add);
    StringCodes.register(b::add);
    VectorCodes.register(b::add);
    IntCodes.register(b::add);
    RealCodes.register(b::add);
    WordCodes.register(b::add);
    DecimalCodes.register(b::add);
    DateCodes.register(b::add);
    OptionCodes.register(b::add);
    RelationalCodes.register(b::add);
    RangeCodes.register(b::add);
    SysCodes.register(b::add);
    GeneralCodes.register(b::add);
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.BAG_NIL, ImmutableList.of());
    b.add(BuiltIn.LIST_NIL, ImmutableList.of());
    // Value of Sys.file comes from Session.file, but initial value must
    // be a List because it has (progressive) record type.
    b.add(BuiltIn.SYS_FILE, ImmutableList.of());
    b.add(BuiltIn.Z_ANDALSO, Unit.INSTANCE);
    b.add(BuiltIn.Z_ATTEMPT, Unit.INSTANCE);
    b.add(BuiltIn.Z_CHECK, Unit.INSTANCE);
    b.add(BuiltIn.Z_CURRENT, Unit.INSTANCE);
    b.add(BuiltIn.Z_ELEMENTS, Unit.INSTANCE);
    b.add(BuiltIn.Z_NTH, Unit.INSTANCE);
    b.add(BuiltIn.Z_ORDINAL, 0);
    b.add(BuiltIn.Z_ORELSE, Unit.INSTANCE);
    b.add(BuiltIn.Z_REQUIRE, Unit.INSTANCE);
    b.add(BuiltIn.Z_TY_CON, Unit.INSTANCE);
    b.add(BuiltIn.Z_VOID, Unit.INSTANCE);
    BUILT_IN_VALUES = b.toImmutableMap();
  }

  @SuppressWarnings("TrivialFunctionalExpressionUsage")
  public static final Map<Applicable, BuiltIn> BUILT_IN_MAP =
      ((Supplier<Map<Applicable, BuiltIn>>) Codes::get).get();

  @SuppressWarnings("TrivialFunctionalExpressionUsage")
  private static final EvalEnv EMPTY_ENV =
      ((Supplier<EvalEnv>) Codes::makeEmptyEnv).get();

  private static Map<Applicable, BuiltIn> get() {
    final IdentityHashMap<Applicable, BuiltIn> b = new IdentityHashMap<>();
    BUILT_IN_VALUES.forEach(
        (builtIn, o) -> {
          if (o instanceof Applicable) {
            b.put((Applicable) o, builtIn);
          }
        });
    return ImmutableMap.copyOf(b);
  }

  private static EvalEnv makeEmptyEnv() {
    final Map<String, Object> map = new HashMap<>();
    populateBuiltIns(map);
    return EvalEnvs.copyOf(map);
  }

  public static StringBuilder appendFloat(StringBuilder buf, float f) {
    return buf.append(realToString(f));
  }

  /**
   * Converts a Java {@code float} to the format expected of Standard ML {@code
   * real} values.
   *
   * <p>Matches Standard ML's {@code Real.toString}, which drops the trailing
   * ".0" from whole-number reals (so {@code 1.0} prints as "1" and {@code
   * 1.0e10} prints as "1E10").
   */
  public static String realToString(float f) {
    return realToString(f, '~');
  }

  /**
   * Converts a float to a string, using {@code negation} for the sign of a
   * negative number or exponent.
   *
   * <p>Standard ML writes negation as a tilde, {@code ~2.5}; tabular output
   * writes it as a minus sign, {@code -2.5}.
   */
  public static String realToString(float f, char negation) {
    if (Float.isFinite(f)) {
      final String s = stripTrailingZero(floatToString(f));
      return negation == '-' ? s : s.replace('-', negation);
    } else if (f == Float.POSITIVE_INFINITY) {
      return "inf";
    } else if (f == Float.NEGATIVE_INFINITY) {
      return negation + "inf";
    } else if (Float.isNaN(f)) {
      return "nan";
    } else {
      throw new AssertionError("unknown float " + f);
    }
  }

  /**
   * If the mantissa part of the given Java float string ends with ".0", removes
   * those two characters. For example, "1.0" becomes "1" and "1.0E10" becomes
   * "1E10". Leaves strings like "1.5" and "1.5E10" unchanged.
   */
  private static String stripTrailingZero(String s) {
    int e = s.indexOf('E');
    int mantissaEnd = e < 0 ? s.length() : e;
    if (mantissaEnd >= 2
        && s.charAt(mantissaEnd - 1) == '0'
        && s.charAt(mantissaEnd - 2) == '.') {
      return s.substring(0, mantissaEnd - 2) + s.substring(mantissaEnd);
    }
    return s;
  }

  /**
   * A code that evaluates expressions and creates a tuple with the results.
   *
   * <p>An inner class so that we can pick apart the results of multiply defined
   * functions: {@code fun f = ... and g = ...}.
   */
  public static class TupleCode implements Code {
    public final List<Code> codes;

    private TupleCode(ImmutableList<Code> codes) {
      this.codes = codes;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "tuple", d -> codes.forEach(code -> d.arg("", code)));
    }

    @Override
    public int maxSlots() {
      return maxOf(codes);
    }

    @Override
    public Object eval(Stack stack) {
      final Object[] values = new Object[codes.size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = codes.get(i).eval(stack);
      }
      return Arrays.asList(values);
    }
  }

  /** Code that retrieves the value of a variable from the environment. */
  private static class GetCode implements Code {
    private final String name;

    GetCode(String name) {
      this.name = requireNonNull(name);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("get", d -> d.arg("name", describer.rename(name)));
    }

    @Override
    public String toString() {
      return "get(" + name + ")";
    }

    @Override
    public Object eval(Stack stack) {
      final Map<String, Object> env = stack.currentEnv();
      return requireNonNull(env.get(name), name);
    }
  }

  /**
   * Code that pushes pre-existing globals onto the stack, evaluates {@code
   * body}, then restores the stack.
   *
   * <p>Emitted at the outermost statement level so that the body can use fast
   * {@link StackCode} reads for globals instead of calling {@link GetCode} on
   * every evaluation.
   */
  private static class GlobalMarshalCode implements Code {
    final ImmutableList<String> names;
    final Code body;

    GlobalMarshalCode(ImmutableList<String> names, Code body) {
      this.names = requireNonNull(names);
      this.body = requireNonNull(body);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "globalMarshal", d -> d.args("globals", names).arg("body", body));
    }

    @Override
    public int maxSlots() {
      return names.size() + body.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final int savedTop = stack.save();
      final Map<String, Object> env = stack.currentEnv();
      for (String name : names) {
        // A global may be absent from the environment -- a datatype
        // constructor, for instance -- and its slot is then null. The slots
        // must still line up with what the body reads, so we push one for
        // every name.
        stack.push(env.get(name));
      }
      final Object result = body.eval(stack);
      stack.restore(savedTop);
      return result;
    }
  }

  /**
   * Code that retrieves a local variable from the stack at a fixed offset below
   * the top.
   *
   * <p>{@code offset} is 1-based: offset 1 is the most recently pushed value,
   * offset 2 is the one before that, etc. The slot accessed is {@code
   * stack.slots[stack.top - offset]}.
   */
  static class StackCode implements Code {
    final int offset;
    final String name;

    StackCode(int offset, String name) {
      this.offset = offset;
      this.name = requireNonNull(name);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "stack",
          d -> d.arg("offset", offset).arg("name", describer.rename(name)));
    }

    @Override
    public String toString() {
      return "stack(offset=" + offset + ", name=" + name + ")";
    }

    @Override
    public Object eval(final Stack stack) {
      return stack.slots[stack.top - offset];
    }
  }

  /** Code that implements a constant. */
  private static class ConstantCode implements Code {
    private final Object value;

    ConstantCode(Object value) {
      this.value = value;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("constant", d -> d.arg("", value));
    }

    @Override
    public Object eval(Stack stack) {
      return value;
    }

    @Override
    public boolean isConstant() {
      return true;
    }
  }

  /** Code that implements {@link #andAlso(Code, Code)}. */
  private static class AndAlsoCode implements Code {
    private final Code code0;
    private final Code code1;

    AndAlsoCode(Code code0, Code code1) {
      this.code0 = code0;
      this.code1 = code1;
    }

    @Override
    public int maxSlots() {
      return maxOf(code0, code1);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("andalso", d -> d.arg("", code0).arg("", code1));
    }

    @Override
    public Object eval(Stack stack) {
      // Lazy evaluation. If code0 returns false, code1 is never evaluated.
      return (boolean) code0.eval(stack) && (boolean) code1.eval(stack);
    }
  }

  /** Code that implements {@link #orElse(Code, Code)}. */
  private static class OrElseCode implements Code {
    private final Code code0;
    private final Code code1;

    OrElseCode(Code code0, Code code1) {
      this.code0 = code0;
      this.code1 = code1;
    }

    @Override
    public int maxSlots() {
      return maxOf(code0, code1);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("orelse", d -> d.arg("", code0).arg("", code1));
    }

    @Override
    public Object eval(Stack stack) {
      // Lazy evaluation. If code0 returns true, code1 is never evaluated.
      return (boolean) code0.eval(stack) || (boolean) code1.eval(stack);
    }
  }

  /** Code that implements {@link #raise(Code, Pos)}. */
  private static class RaiseCode implements Code {
    private final Code expCode;
    private final Pos pos;

    RaiseCode(Code expCode, Pos pos) {
      this.expCode = expCode;
      this.pos = pos;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("raise", d -> d.arg("exp", expCode));
    }

    @Override
    public Object eval(Stack stack) {
      final Object value = expCode.eval(stack);
      // The runtime value of an `exn` is a list whose head is the constructor
      // name; subsequent elements (if any) are the payload.
      final List<?> list = (List<?>) value;
      final String name = (String) list.get(0);
      final BuiltInExn e = BuiltInExn.forMlName(name);
      if (e == null) {
        throw new IllegalStateException("unknown exception: " + name);
      }
      final Object payload = list.size() > 1 ? list.get(1) : null;
      throw new MorelRuntimeException(e, payload, pos);
    }
  }

  /**
   * Code that implements a stack-based {@code let} binding.
   *
   * <p>Evaluates {@code expCode}, pushes the result onto the stack, evaluates
   * {@code resultCode}, then restores the stack top.
   */
  private static class StackLet1Code implements Code {
    private final Code expCode;
    private final Code resultCode;

    StackLet1Code(Code expCode, Code resultCode) {
      this.expCode = expCode;
      this.resultCode = resultCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "let1", d -> d.arg("expCode", expCode).arg("resultCode", resultCode));
    }

    @Override
    public int maxSlots() {
      return 1 + resultCode.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final int savedTop = stack.save();
      stack.push(expCode.eval(stack));
      final Object result = resultCode.eval(stack);
      stack.restore(savedTop);
      return result;
    }
  }

  /** Creates stack-based let code that pushes one value and pops after. */
  public static Code stackLet1(Code expCode, Code resultCode) {
    return new StackLet1Code(expCode, resultCode);
  }

  /**
   * Evaluates {@code expCode}, uses {@link Closure.StackClosure#pushBindings}
   * to push all pattern-bound variables onto the stack, evaluates {@code
   * resultCode}, then restores the stack top.
   *
   * <p>This handles the general case of {@code let val pat = expr in body end},
   * including tuple and record patterns.
   */
  private static class StackLetPatCode implements Code {
    private final Core.Pat pat;
    private final int varCount;
    private final Code expCode;
    private final Code resultCode;
    private final Pos pos;

    StackLetPatCode(
        Core.Pat pat, int varCount, Code expCode, Code resultCode, Pos pos) {
      this.pat = pat;
      this.varCount = varCount;
      this.expCode = expCode;
      this.resultCode = resultCode;
      this.pos = pos;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "stackLetPat",
          d ->
              d.arg("pat", pat.describe(describer))
                  .arg("expCode", expCode)
                  .arg("resultCode", resultCode));
    }

    @Override
    public int maxSlots() {
      return varCount + resultCode.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final int savedTop = stack.save();
      final Object val = expCode.eval(stack);
      if (!Closure.StackClosure.pushBindings(pat, val, stack)) {
        throw new MorelRuntimeException(BuiltInExn.BIND, pos);
      }
      final Object result = resultCode.eval(stack);
      stack.restore(savedTop);
      return result;
    }
  }

  /**
   * Creates stack-based let code for a general pattern.
   *
   * <p>Evaluates {@code expCode}, pushes each pattern-bound variable from
   * {@code pat} onto the stack (in the same order as {@link
   * Closure.StackClosure#pushBindings}), evaluates {@code resultCode}, then
   * restores the stack.
   */
  public static Code stackLetPat(
      Core.Pat pat, int numVars, Code expCode, Code resultCode, Pos pos) {
    return new StackLetPatCode(pat, numVars, expCode, resultCode, pos);
  }

  /**
   * Code that implements a stack-aware multi-binding {@code let}.
   *
   * <p>Each binding evaluates its expression code against the current stack,
   * then binds the result to its pattern by pushing it onto the stack. After
   * all bindings, a second pass re-patches mutual-recursion references in any
   * {@link Closure.StackClosure} values. The result code is then evaluated with
   * the fully-extended environment.
   *
   * <p>This is used for recursive function declarations ({@code fun f x = ...})
   * and mutually recursive bindings ({@code let val f = ... and g = ...}).
   */
  private static class StackMultiLetCode implements Code {
    private final ImmutablePairList<Core.Pat, Code> patCodes;
    private final Code resultCode;
    /** Total number of stack slots pushed for all binding patterns combined. */
    private final int slotCount;

    StackMultiLetCode(
        ImmutablePairList<Core.Pat, Code> patCodes, Code resultCode) {
      this.patCodes = patCodes;
      this.resultCode = resultCode;
      this.slotCount =
          patCodes.leftList().stream().mapToInt(p -> p.expand().size()).sum();
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "let",
          d -> {
            forEachIndexed(
                patCodes,
                (entry, i) -> d.arg("matchCode" + i, entry.getValue()));
            d.arg("resultCode", resultCode);
          });
    }

    @Override
    public int maxSlots() {
      // RHSs evaluated before slots pushed; resultCode runs with N extra
      // slots live, so needs numSlots + resultCode.maxSlots() from base.
      return maxOf(slotCount + resultCode.maxSlots(), patCodes.rightList());
    }

    @Override
    public Object eval(Stack stack) {
      final int savedTop = stack.save();
      // Evaluate all RHSs first at the current stack depth. This ensures
      // StackMatchCode captures use the correct outer offsets, and that RHSs
      // see a consistent environment (mutual recursion handled via RecFrame).
      final Object[] values = new Object[patCodes.size()];
      for (int i = 0; i < patCodes.size(); i++) {
        values[i] = patCodes.get(i).getValue().eval(stack);
      }
      // Extend each StackClosure's captured array with the rec-group peers so
      // that recursive/mutual calls resolve via StackCode (not GetCode).
      for (Object value : values) {
        if (value instanceof Closure.StackClosure) {
          ((Closure.StackClosure) value).extendWithRecPeers(values);
        }
      }
      for (int i = 0; i < patCodes.size(); i++) {
        if (!Closure.StackClosure.pushBindings(
            patCodes.get(i).getKey(), values[i], stack)) {
          throw new AssertionError("no match in StackMultiLetCode");
        }
      }
      // resultCode sees the N pushed slots via StackCode; no env extension.
      final Object result = resultCode.eval(stack);
      stack.restore(savedTop);
      return result;
    }
  }

  /**
   * Creates a stack-aware multi-binding {@code let} code node.
   *
   * <p>Binding values are pushed onto the stack before evaluating {@code
   * resultCode} so that it can read them via {@link StackCode}.
   */
  public static Code stackMultiLet(
      ImmutablePairList<Core.Pat, Code> patCodes, Code resultCode) {
    return patCodes.isEmpty()
        ? resultCode
        : new StackMultiLetCode(patCodes, resultCode);
  }

  /**
   * Code that snapshots live stack slots into a {@link Closure.StackClosure}.
   *
   * <p>At compile time, {@code captureOffsets[i]} is the 1-based offset from
   * the current stack top at which the i-th captured variable lives.
   *
   * <p>At runtime, those slots are copied into a pre-allocated {@code
   * captured[]} array of size {@code captureLen + numRecPeers} and returned as
   * a new {@link Closure.StackClosure}. If the closure belongs to a
   * mutual-recursion group, {@link
   * Closure.StackClosure#extendWithRecPeers(Object[])} fills the tail of that
   * array without further allocation. The shared {@code StackMatchCode}
   * instance also carries the {@link #patCodes}, {@link #capacity}, and {@link
   * #pos} used by every closure it creates, so those fields need not be
   * duplicated per closure.
   */
  public static class StackMatchCode implements Code {
    /** Stack offsets of outer variables to be copied on closure creation. */
    final int[] captureOffsets;
    /**
     * Number of mutual-recursion peers in this closure's rec group; 0 for
     * non-recursive closures. Determines the pre-allocated tail of {@code
     * captured[]} that {@link Closure.StackClosure#extendWithRecPeers} fills.
     */
    private final int recPeerCount;

    final ImmutablePairList<Core.Pat, Code> patCodes;
    /** Minimum slots needed for a fresh {@link Closure.StackClosure} call. */
    final int capacity;

    final Pos pos;

    public StackMatchCode(
        int[] captureOffsets,
        int recPeerCount,
        ImmutablePairList<Core.Pat, Code> patCodes,
        int capacity,
        Pos pos) {
      this.captureOffsets = captureOffsets;
      this.recPeerCount = recPeerCount;
      this.patCodes = patCodes;
      this.capacity = capacity;
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

    @Override
    public Object eval(Stack stack) {
      // Pre-allocate with room for rec-group peers (filled later by
      // extendWithRecPeers); for non-recursive closures numRecPeers == 0.
      final Object[] captured =
          new Object[captureOffsets.length + recPeerCount];
      for (int i = 0; i < captureOffsets.length; i++) {
        captured[i] = stack.slots[stack.top - captureOffsets[i]];
      }
      return new Closure.StackClosure(stack.session, captured, this);
    }
  }

  /** Applies an {@link Applicable} to a {@link Code}. */
  private static class ApplyCode implements Code {
    private final Applicable fnValue;
    private final Code argCode;

    ApplyCode(Applicable fnValue, Code argCode) {
      this.fnValue = fnValue;
      this.argCode = argCode;
    }

    @Override
    public int maxSlots() {
      return argCode.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final Object arg = argCode.eval(stack);
      return fnValue.apply(stack, arg);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply", d -> d.arg("fnValue", fnValue).arg("argCode", argCode));
    }
  }

  /** Applies an {@link Applicable1} to one {@link Code} argument. */
  private static class ApplyCode1 implements Code {
    private final Applicable1 fnValue;
    private final Code argCode0;

    ApplyCode1(Applicable1 fnValue, Code argCode0) {
      this.fnValue = fnValue;
      this.argCode0 = argCode0;
    }

    @Override
    public int maxSlots() {
      return argCode0.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      return fnValue.apply(argCode0.eval(stack));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply1", d -> d.arg("fnValue", fnValue).arg("", argCode0));
    }
  }

  /** Applies an {@link Applicable2} to two {@link Code} arguments. */
  private static class ApplyCode2 implements Code {
    private final Applicable2 fnValue;
    private final Code argCode0;
    private final Code argCode1;

    ApplyCode2(Applicable2 fnValue, Code argCode0, Code argCode1) {
      this.fnValue = fnValue;
      this.argCode0 = argCode0;
      this.argCode1 = argCode1;
    }

    @Override
    public int maxSlots() {
      return maxOf(argCode0, argCode1);
    }

    @Override
    public Object eval(Stack stack) {
      return fnValue.apply(argCode0.eval(stack), argCode1.eval(stack));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply2",
          d -> d.arg("fnValue", fnValue).arg("", argCode0).arg("", argCode1));
    }
  }

  /** Applies an {@link Applicable2} to an argument that yields a 2-tuple. */
  private static class ApplyCode2Tuple implements Code {
    private final Applicable2 fnValue;
    private final Code argCode;

    ApplyCode2Tuple(Applicable2 fnValue, Code argCode) {
      this.fnValue = fnValue;
      this.argCode = argCode;
    }

    @Override
    public int maxSlots() {
      return argCode.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final List<?> args = (List<?>) argCode.eval(stack);
      return fnValue.apply(args.get(0), args.get(1));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply2Tuple", d -> d.arg("fnValue", fnValue).arg("", argCode));
    }
  }

  /** Applies an {@link Applicable3} to three {@link Code} arguments. */
  private static class ApplyCode3 implements Code {
    private final Applicable3 fnValue;
    private final Code argCode0;
    private final Code argCode1;
    private final Code argCode2;

    ApplyCode3(
        Applicable3 fnValue, Code argCode0, Code argCode1, Code argCode2) {
      this.fnValue = fnValue;
      this.argCode0 = argCode0;
      this.argCode1 = argCode1;
      this.argCode2 = argCode2;
    }

    @Override
    public int maxSlots() {
      return maxOf(argCode0, argCode1, argCode2);
    }

    @Override
    public Object eval(Stack stack) {
      return fnValue.apply(
          argCode0.eval(stack), argCode1.eval(stack), argCode2.eval(stack));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply3",
          d ->
              d.arg("fnValue", fnValue)
                  .arg("", argCode0)
                  .arg("", argCode1)
                  .arg("", argCode2));
    }
  }

  /** Applies an {@link Applicable3} to an argument that yields a 3-tuple. */
  private static class ApplyCode3Tuple implements Code {
    private final Applicable3 fnValue;
    private final Code argCode;

    ApplyCode3Tuple(Applicable3 fnValue, Code argCode) {
      this.fnValue = fnValue;
      this.argCode = argCode;
    }

    @Override
    public int maxSlots() {
      return argCode.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final List<?> args = (List<?>) argCode.eval(stack);
      return fnValue.apply(args.get(0), args.get(1), args.get(2));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply3Tuple", d -> d.arg("fnValue", fnValue).arg("", argCode));
    }
  }

  /** Applies an {@link Applicable4} to four {@link Code} arguments. */
  private static class ApplyCode4 implements Code {
    private final Applicable4 fnValue;
    private final Code argCode0;
    private final Code argCode1;
    private final Code argCode2;
    private final Code argCode3;

    ApplyCode4(
        Applicable4 fnValue,
        Code argCode0,
        Code argCode1,
        Code argCode2,
        Code argCode3) {
      this.fnValue = fnValue;
      this.argCode0 = argCode0;
      this.argCode1 = argCode1;
      this.argCode2 = argCode2;
      this.argCode3 = argCode3;
    }

    @Override
    public int maxSlots() {
      return maxOf(argCode0, argCode1, argCode2, argCode3);
    }

    @Override
    public Object eval(Stack stack) {
      return fnValue.apply(
          argCode0.eval(stack),
          argCode1.eval(stack),
          argCode2.eval(stack),
          argCode3.eval(stack));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply4",
          d ->
              d.arg("fnValue", fnValue)
                  .arg("", argCode0)
                  .arg("", argCode1)
                  .arg("", argCode2)
                  .arg("", argCode3));
    }
  }

  /**
   * Applies a {@link Code} to a {@link Code}.
   *
   * <p>If {@link #fnCode} is constant, you should use {@link ApplyCode}
   * instead.
   */
  static class ApplyCodeCode implements Code {
    public final Code fnCode;
    public final Code argCode;

    ApplyCodeCode(Code fnCode, Code argCode) {
      this.fnCode = fnCode;
      this.argCode = argCode;
    }

    @Override
    public int maxSlots() {
      return maxOf(fnCode, argCode);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply", d -> d.arg("fnCode", fnCode).arg("argCode", argCode));
    }

    @Override
    public Object eval(Stack stack) {
      final Object fn = fnCode.eval(stack);
      final Object arg = argCode.eval(stack);
      if (fn instanceof Applicable1) {
        return ((Applicable1) fn).apply(arg);
      }
      return ((Applicable) fn).apply(stack, arg);
    }
  }

  /** Sentinel value returned from tail-call positions. */
  static final class TailCall {
    final Applicable fn;
    final Object arg;

    private TailCall(Applicable fn, Object arg) {
      this.fn = fn;
      this.arg = arg;
    }
  }

  /** Tail-call variant of {@link ApplyCode}: returns {@link TailCall}. */
  private static class TailApplyCode implements Code {
    private final Applicable fnValue;
    private final Code argCode;

    TailApplyCode(Applicable fnValue, Code argCode) {
      this.fnValue = fnValue;
      this.argCode = argCode;
    }

    @Override
    public int maxSlots() {
      return argCode.maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      return new TailCall(fnValue, argCode.eval(stack));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "tailApply", d -> d.arg("fnValue", fnValue).arg("argCode", argCode));
    }
  }

  /** Tail-call variant of {@link ApplyCodeCode}: returns {@link TailCall}. */
  static class TailApplyCodeCode implements Code {
    public final Code fnCode;
    public final Code argCode;

    TailApplyCodeCode(Code fnCode, Code argCode) {
      this.fnCode = fnCode;
      this.argCode = argCode;
    }

    @Override
    public int maxSlots() {
      return maxOf(fnCode, argCode);
    }

    @Override
    public Object eval(Stack stack) {
      return new TailCall((Applicable) fnCode.eval(stack), argCode.eval(stack));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "tailApply", d -> d.arg("fnCode", fnCode).arg("argCode", argCode));
    }
  }

  /**
   * A {@code Code} that evaluates a {@code Code} and if the result is a {@link
   * net.hydromatic.morel.foreign.RelList}, wraps it in a different kind of
   * list.
   */
  static class WrapRelList implements Code {
    public final Code code;

    WrapRelList(Code code) {
      this.code = code;
    }

    @Override
    public int maxSlots() {
      return code.maxSlots();
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("wrapRelList", d -> d.arg("code", code));
    }

    @Override
    public Object eval(Stack stack) {
      return wrap(code.eval(stack));
    }

    private static Object wrap(Object arg) {
      if (arg instanceof RelList) {
        final RelList list = (RelList) arg;
        return new AbstractList<Object>() {
          @Override
          public Object get(int index) {
            return list.get(index);
          }

          @Override
          public int size() {
            return list.size();
          }
        };
      }
      return arg;
    }
  }

  /**
   * An {@link Applicable} whose position can be changed.
   *
   * <p>Operations that may throw exceptions should implement this interface.
   * Then the exceptions can be tied to the correct position in the source code.
   *
   * <p>If you don't implement this interface, the applicable will use the
   * default position, which is {@link Pos#ZERO}. If the exception has position
   * "0.0-0.0", that is an indication you need to use this interface, and make
   * sure that the position is propagated through the translation process.
   */
  public interface Positioned extends Applicable {
    Applicable withPos(Pos pos);
  }

  /**
   * An {@link Applicable} whose type may be specified.
   *
   * <p>This is useful for instances where the behavior depends on the type.
   */
  public interface Typed extends Applicable {
    /**
     * Returns a copy of this applicable specialized to {@code type}. {@code
     * pos} is the position of the expression it was reached from, for an error
     * message if {@code type} is one it has no implementation for.
     */
    Applicable withType(TypeSystem typeSystem, Type type, Pos pos);
  }

  /**
   * Implementation of {@code Code} that evaluates the current row ordinal.
   *
   * @see OrdinalIncCode
   */
  private static class OrdinalGetCode implements Code {
    private final int[] ordinalSlots;

    OrdinalGetCode(int[] ordinalSlots) {
      this.ordinalSlots = requireNonNull(ordinalSlots);
      checkArgument(ordinalSlots.length == 1);
    }

    @Override
    public Object eval(Stack stack) {
      return ordinalSlots[0];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("ordinal", d -> {});
    }
  }

  /**
   * Implementation of {@code Code} that increments the current row ordinal then
   * calls another {@code Code}.
   */
  private static class OrdinalIncCode implements Code {
    private final int[] ordinalSlots;
    private final Code nextCode;

    OrdinalIncCode(int[] ordinalSlots, Code nextCode) {
      this.ordinalSlots = requireNonNull(ordinalSlots);
      this.nextCode = requireNonNull(nextCode);
      checkArgument(ordinalSlots.length == 1);
    }

    @Override
    public Object eval(Stack stack) {
      ++ordinalSlots[0];
      return nextCode.eval(stack);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("ordinal", d -> {});
    }
  }
}

// End Codes.java
