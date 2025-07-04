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
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.SKIP;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Chars;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Macro;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.JavaVersion;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.MorelException;
import org.apache.calcite.runtime.FlatLists;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Helpers for {@link Code}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Codes {
  /** Converts a {@code float} to a String per the JDK. */
  private static final Function<Float, String> FLOAT_TO_STRING =
      JavaVersion.CURRENT.compareTo(JavaVersion.of(19)) >= 0
          ? f -> Float.toString(f)
          : Codes::floatToString0;

  /** A special value that represents Standard ML "~NaN". */
  public static final float NEGATIVE_NAN =
      Float.intBitsToFloat(Float.floatToRawIntBits(Float.NaN) ^ 0x8000_0000);

  private Codes() {}

  /** Describes a {@link Code}. */
  public static String describe(Code code) {
    final Code code2 = Codes.strip(code);
    return code2.describe(new DescriberImpl()).toString();
  }

  /**
   * Value of {@link BuiltIn.Constructor#OPTION_NONE}.
   *
   * @see #optionSome(Object)
   */
  private static final List OPTION_NONE = ImmutableList.of("NONE");

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Object value) {
    return new ConstantCode(value);
  }

  /** Returns an Applicable that returns its argument. */
  private static ApplicableImpl identity(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return arg;
      }
    };
  }

  /** @see BuiltIn#OP_EQ */
  private static final Applicable OP_EQ =
      new Applicable2<Boolean, Object, Object>(BuiltIn.OP_EQ) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#OP_NE */
  private static final Applicable OP_NE =
      new Applicable2<Boolean, Object, Object>(BuiltIn.OP_NE) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#OP_LT */
  private static final Applicable OP_LT =
      new Applicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_LT) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) < 0;
        }
      };

  /** @see BuiltIn#OP_GT */
  private static final Applicable OP_GT =
      new Applicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_GT) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) > 0;
        }
      };

  /** @see BuiltIn#OP_LE */
  private static final Applicable OP_LE =
      new Applicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_LE) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) <= 0;
        }
      };

  /** @see BuiltIn#OP_GE */
  private static final Applicable OP_GE =
      new Applicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_GE) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) >= 0;
        }
      };

  /** @see BuiltIn#OP_ELEM */
  private static final Applicable OP_ELEM =
      new Applicable2<Boolean, Object, List>(BuiltIn.OP_ELEM) {
        @Override
        public Boolean apply(Object a0, List a1) {
          return a1.contains(a0);
        }
      };

  /** @see BuiltIn#OP_NOT_ELEM */
  private static final Applicable OP_NOT_ELEM =
      new Applicable2<Boolean, Object, List>(BuiltIn.OP_NOT_ELEM) {
        @Override
        public Boolean apply(Object a0, List a1) {
          return !a1.contains(a0);
        }
      };

  /** Returns a Code that evaluates "andalso". */
  public static Code andAlso(Code code0, Code code1) {
    return new AndAlsoCode(code0, code1);
  }

  /** Returns a Code that evaluates "orelse". */
  public static Code orElse(Code code0, Code code1) {
    return new OrElseCode(code0, code1);
  }

  /** Implements {@link #OP_NEGATE} for type {@code int}. */
  private static final Applicable Z_NEGATE_INT =
      new ApplicableImpl(BuiltIn.OP_NEGATE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return -((Integer) arg);
        }
      };

  /** Implements {@link #OP_NEGATE} for type {@code real}. */
  private static final Applicable Z_NEGATE_REAL =
      new ApplicableImpl(BuiltIn.OP_NEGATE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final float f = (Float) arg;
          if (Float.isNaN(f)) {
            // ~nan -> nan
            // nan (or any other value f such that isNan(f)) -> ~nan
            return Float.floatToRawIntBits(f)
                    == Float.floatToRawIntBits(NEGATIVE_NAN)
                ? Float.NaN
                : NEGATIVE_NAN;
          }
          return -f;
        }
      };

  /** Implements {@link #OP_PLUS} for type {@code int}. */
  private static final Applicable Z_PLUS_INT =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.OP_PLUS) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 + a1;
        }
      };

  /** Implements {@link #OP_PLUS} for type {@code real}. */
  private static final Applicable Z_PLUS_REAL =
      new Applicable2<Float, Float, Float>(BuiltIn.OP_PLUS) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 + a1;
        }
      };

  /** Implements {@link #OP_MINUS} for type {@code int}. */
  private static final Applicable Z_MINUS_INT =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.OP_MINUS) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 - a1;
        }
      };

  /** Implements {@link #OP_MINUS} for type {@code real}. */
  private static final Applicable Z_MINUS_REAL =
      new Applicable2<Float, Float, Float>(BuiltIn.OP_MINUS) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 - a1;
        }
      };

  /** Implements {@link #OP_TIMES} for type {@code int}. */
  private static final Applicable Z_TIMES_INT =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.OP_TIMES) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 * a1;
        }
      };

  /** Implements {@link #OP_TIMES} for type {@code real}. */
  private static final Applicable Z_TIMES_REAL =
      new Applicable2<Float, Float, Float>(BuiltIn.OP_TIMES) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 * a1;
        }
      };

  /** Implements {@link #OP_DIVIDE} for type {@code int}. */
  private static final Applicable Z_DIVIDE_INT =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.OP_DIVIDE) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 / a1;
        }
      };

  /** Implements {@link #OP_DIVIDE} for type {@code real}. */
  private static final Applicable Z_DIVIDE_REAL =
      new Applicable2<Float, Float, Float>(BuiltIn.OP_DIVIDE) {
        @Override
        public Float apply(Float a0, Float a1) {
          final float v = a0 / a1;
          if (Float.isNaN(v)) {
            return Float.NaN; // normalize NaN
          }
          return v;
        }
      };

  /** @see BuiltIn#OP_NEGATE */
  private static final Macro OP_NEGATE =
      (typeSystem, env, argType) -> {
        switch ((PrimitiveType) argType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.Z_NEGATE_INT);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.Z_NEGATE_REAL);
          default:
            throw new AssertionError("bad type " + argType);
        }
      };

  /** @see BuiltIn#OP_DIVIDE */
  private static final Macro OP_DIVIDE =
      (typeSystem, env, argType) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.Z_DIVIDE_INT);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.Z_DIVIDE_REAL);
          default:
            throw new AssertionError("bad type " + argType);
        }
      };

  /** @see BuiltIn#OP_DIV */
  private static final Applicable OP_DIV = new IntDiv(BuiltIn.OP_DIV);

  /** @see BuiltIn#GENERAL_OP_O */
  private static final Applicable GENERAL_OP_O =
      new ApplicableImpl("o") {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("rawtypes")
          final List tuple = (List) arg;
          final Applicable f = (Applicable) tuple.get(0);
          final Applicable g = (Applicable) tuple.get(1);
          return new ApplicableImpl("o$f$g") {
            @Override
            public Object apply(EvalEnv env, Object arg) {
              return f.apply(env, g.apply(env, arg));
            }
          };
        }
      };
  /** @see BuiltIn#CHAR_CHR */
  private static final Applicable CHAR_CHR =
      new ApplicableImpl(BuiltIn.CHAR_CHR) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          int ord = (int) arg;
          if (ord < 0 || ord > 255) {
            throw new MorelRuntimeException(BuiltInExn.CHR, pos);
          }
          return (char) ord;
        }
      };

  /** @see BuiltIn#CHAR_COMPARE */
  private static final Applicable CHAR_COMPARE =
      new Applicable2<List, Character, Character>(BuiltIn.CHAR_COMPARE) {
        @Override
        public List apply(Character a0, Character a1) {
          if (a0 < a1) {
            return ORDER_LESS;
          }
          if (a0 > a1) {
            return ORDER_GREATER;
          }
          return ORDER_EQUAL;
        }
      };

  /** @see BuiltIn#CHAR_CONTAINS */
  private static final Applicable CHAR_CONTAINS =
      new ApplicableImpl(BuiltIn.CHAR_CONTAINS) {
        @Override
        public Object apply(EvalEnv env, Object argValue) {
          final String s = (String) argValue;
          return charContains(s, false);
        }
      };

  /** Implement {@link #CHAR_CONTAINS} and {@link #CHAR_NOT_CONTAINS}. */
  private static ApplicableImpl charContains(String s, boolean negate) {
    return new ApplicableImpl("contains") {
      @Override
      public Object apply(EvalEnv env, Object argValue) {
        final Character c = (Character) argValue;
        return s.indexOf(c) >= 0 ^ negate;
      }
    };
  }

  /** @see BuiltIn#CHAR_FROM_CSTRING */
  private static final Applicable CHAR_FROM_CSTRING =
      new ApplicableImpl(BuiltIn.CHAR_FROM_CSTRING) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          throw new UnsupportedOperationException("CHAR_FROM_CSTRING");
        }
      };

  /** @see BuiltIn#CHAR_FROM_STRING */
  private static final Applicable CHAR_FROM_STRING =
      new ApplicableImpl(BuiltIn.CHAR_FROM_STRING) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          String s = (String) arg;
          Character c = Parsers.fromString(s);
          return c == null ? OPTION_NONE : optionSome(c);
        }
      };

  /** @see BuiltIn#CHAR_IS_ALPHA */
  private static final Applicable CHAR_IS_ALPHA =
      new CharPredicate(BuiltIn.CHAR_IS_ALPHA, CharPredicate::isAlpha);

  /** @see BuiltIn#CHAR_IS_ALPHA_NUM */
  private static final Applicable CHAR_IS_ALPHA_NUM =
      new CharPredicate(BuiltIn.CHAR_IS_ALPHA_NUM, CharPredicate::isAlphaNum);

  /** @see BuiltIn#CHAR_IS_ASCII */
  private static final Applicable CHAR_IS_ASCII =
      new CharPredicate(BuiltIn.CHAR_IS_ASCII, CharPredicate::isAscii);

  /** @see BuiltIn#CHAR_IS_CNTRL */
  private static final Applicable CHAR_IS_CNTRL =
      new CharPredicate(BuiltIn.CHAR_IS_CNTRL, CharPredicate::isCntrl);

  /** @see BuiltIn#CHAR_IS_DIGIT */
  private static final Applicable CHAR_IS_DIGIT =
      new CharPredicate(BuiltIn.CHAR_IS_DIGIT, CharPredicate::isDigit);

  /** @see BuiltIn#CHAR_IS_GRAPH */
  private static final Applicable CHAR_IS_GRAPH =
      new CharPredicate(BuiltIn.CHAR_IS_GRAPH, CharPredicate::isGraph);

  /** @see BuiltIn#CHAR_IS_HEX_DIGIT */
  private static final Applicable CHAR_IS_HEX_DIGIT =
      new CharPredicate(BuiltIn.CHAR_IS_HEX_DIGIT, CharPredicate::isHexDigit);

  /** @see BuiltIn#CHAR_IS_LOWER */
  private static final Applicable CHAR_IS_LOWER =
      new CharPredicate(BuiltIn.CHAR_IS_LOWER, CharPredicate::isLower);

  /** @see BuiltIn#CHAR_IS_PRINT */
  private static final Applicable CHAR_IS_PRINT =
      new CharPredicate(BuiltIn.CHAR_IS_PRINT, CharPredicate::isPrint);

  /** @see BuiltIn#CHAR_IS_PUNCT */
  private static final Applicable CHAR_IS_PUNCT =
      new CharPredicate(BuiltIn.CHAR_IS_PUNCT, CharPredicate::isPunct);

  /** @see BuiltIn#CHAR_IS_SPACE */
  private static final Applicable CHAR_IS_SPACE =
      new CharPredicate(BuiltIn.CHAR_IS_SPACE, CharPredicate::isSpace);

  /** @see BuiltIn#CHAR_IS_UPPER */
  private static final Applicable CHAR_IS_UPPER =
      new CharPredicate(BuiltIn.CHAR_IS_UPPER, CharPredicate::isUpper);

  /** @see BuiltIn#CHAR_MAX_CHAR */
  private static final Character CHAR_MAX_CHAR = 255;

  /** @see BuiltIn#CHAR_MAX_ORD */
  private static final Integer CHAR_MAX_ORD = 255;

  /** @see BuiltIn#CHAR_MIN_CHAR */
  private static final Character CHAR_MIN_CHAR = 0;

  /** @see BuiltIn#CHAR_NOT_CONTAINS */
  private static final Applicable CHAR_NOT_CONTAINS =
      new ApplicableImpl(BuiltIn.CHAR_CONTAINS) {
        @Override
        public Object apply(EvalEnv env, Object argValue) {
          final String s = (String) argValue;
          return charContains(s, true);
        }
      };

  /** @see BuiltIn#CHAR_OP_GE */
  private static final Applicable CHAR_OP_GE =
      new Applicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_GE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 >= a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_GT */
  private static final Applicable CHAR_OP_GT =
      new Applicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_GT) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 > a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_LE */
  private static final Applicable CHAR_OP_LE =
      new Applicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_LE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 <= a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_LT */
  private static final Applicable CHAR_OP_LT =
      new Applicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_LT) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 < a1;
        }
      };

  /** @see BuiltIn#CHAR_ORD */
  private static final Applicable CHAR_ORD =
      new ApplicableImpl(BuiltIn.CHAR_ORD) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (int) ((char) arg);
        }
      };

  /** @see BuiltIn#CHAR_PRED */
  private static final Applicable CHAR_PRED =
      new ApplicableImpl(BuiltIn.CHAR_PRED) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          char c = (char) arg;
          if (c == CHAR_MIN_CHAR) {
            throw new MorelRuntimeException(BuiltInExn.CHR, pos);
          }
          return (char) (c - 1);
        }
      };

  /** @see BuiltIn#CHAR_SUCC */
  private static final Applicable CHAR_SUCC =
      new ApplicableImpl(BuiltIn.CHAR_SUCC) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          char c = (char) arg;
          if (c == CHAR_MAX_CHAR) {
            throw new MorelRuntimeException(BuiltInExn.CHR, pos);
          }
          return (char) (c + 1);
        }
      };

  /** @see BuiltIn#CHAR_TO_CSTRING */
  private static final Applicable CHAR_TO_CSTRING =
      new ApplicableImpl(BuiltIn.CHAR_TO_CSTRING) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          throw new UnsupportedOperationException("CHAR_TO_CSTRING");
        }
      };

  /** @see BuiltIn#CHAR_TO_LOWER */
  private static final Applicable CHAR_TO_LOWER =
      new ApplicableImpl(BuiltIn.CHAR_TO_LOWER) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Character.toLowerCase((char) arg);
        }
      };

  /** @see BuiltIn#CHAR_TO_STRING */
  private static final Applicable CHAR_TO_STRING =
      new ApplicableImpl(BuiltIn.CHAR_TO_STRING) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Parsers.charToString((Character) arg);
        }
      };

  /** @see BuiltIn#CHAR_TO_UPPER */
  private static final Applicable CHAR_TO_UPPER =
      new ApplicableImpl(BuiltIn.CHAR_TO_UPPER) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Character.toUpperCase((char) arg);
        }
      };

  /** @see BuiltIn#INT_ABS */
  private static final Applicable INT_ABS =
      new ApplicableImpl(BuiltIn.INT_ABS) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Math.abs((int) arg);
        }
      };

  /** @see BuiltIn#INT_COMPARE */
  private static final Applicable INT_COMPARE =
      new Applicable2<List, Integer, Integer>(BuiltIn.INT_COMPARE) {
        @Override
        public List apply(Integer a0, Integer a1) {
          return order(Integer.compare(a0, a1));
        }
      };

  /** @see BuiltIn#INT_FROM_INT */
  private static final Applicable INT_FROM_INT = identity(BuiltIn.INT_FROM_INT);

  /** @see BuiltIn#INT_FROM_LARGE */
  private static final Applicable INT_FROM_LARGE =
      identity(BuiltIn.INT_FROM_LARGE);

  /**
   * Pattern for integers (after '~' has been converted to '-'). ".", ".e",
   * ".e-", ".e5", "e7", "2.", ".5", "2.e5" are invalid; "-2", "5" are valid.
   */
  static final Pattern INT_PATTERN = Pattern.compile("^ *-?[0-9]+");

  /** @see BuiltIn#INT_FROM_STRING */
  private static final Applicable INT_FROM_STRING =
      new ApplicableImpl(BuiltIn.INT_FROM_STRING) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          final String s2 = s.replace('~', '-');
          final Matcher matcher = INT_PATTERN.matcher(s2);
          if (!matcher.find(0)) {
            return OPTION_NONE;
          }
          final String s3 = s2.substring(0, matcher.end());
          try {
            final int f = Integer.parseInt(s3);
            return optionSome(f);
          } catch (NumberFormatException e) {
            // We should not have reached this point. The pattern
            // should not have matched the input.
            throw new AssertionError(e);
          }
        }
      };

  /** @see BuiltIn#INT_MAX */
  private static final Applicable INT_MAX =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.INT_MAX) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return Math.max(a0, a1);
        }
      };

  /** @see BuiltIn#INT_MAX_INT */
  private static final List INT_MAX_INT = optionSome(Integer.MAX_VALUE);

  /** @see BuiltIn#INT_MIN */
  private static final Applicable INT_MIN =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.INT_MIN) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return Math.min(a0, a1);
        }
      };

  /** @see BuiltIn#INT_MIN_INT */
  private static final List INT_MIN_INT = optionSome(Integer.MAX_VALUE);

  /** @see BuiltIn#INT_DIV */
  private static final Applicable INT_DIV = new IntDiv(BuiltIn.INT_DIV);

  /** @see BuiltIn#INT_MOD */
  private static final Applicable INT_MOD = new IntMod(BuiltIn.INT_MOD);

  /** @see BuiltIn#Z_ORDINAL */
  public static Code ordinalGet(int[] ordinalSlots) {
    return new OrdinalGetCode(ordinalSlots);
  }

  /** Helper for {@link #ordinalGet(int[])}. */
  public static Code ordinalInc(int[] ordinalSlots, Code nextCode) {
    return new OrdinalIncCode(ordinalSlots, nextCode);
  }

  /** Implements {@link #INT_MOD} and {@link #OP_MOD}. */
  private static class IntMod extends Applicable2<Integer, Integer, Integer> {
    IntMod(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public Integer apply(Integer a0, Integer a1) {
      return Math.floorMod(a0, a1);
    }
  }

  /** Implements {@link #INT_DIV} and {@link #OP_DIV}. */
  private static class IntDiv extends Applicable2<Integer, Integer, Integer> {
    IntDiv(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public Integer apply(Integer a0, Integer a1) {
      return Math.floorDiv(a0, a1);
    }
  }

  /** @see BuiltIn#INT_PRECISION */
  private static final List INT_PRECISION = optionSome(32); // Java int 32 bits

  /** @see BuiltIn#INT_QUOT */
  private static final Applicable INT_QUOT =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.INT_QUOT) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 / a1;
        }
      };

  /** @see BuiltIn#INT_REM */
  private static final Applicable INT_REM =
      new Applicable2<Integer, Integer, Integer>(BuiltIn.INT_REM) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 % a1;
        }
      };

  /** @see BuiltIn#INT_SAME_SIGN */
  private static final Applicable INT_SAME_SIGN =
      new Applicable2<Boolean, Integer, Integer>(BuiltIn.INT_SAME_SIGN) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 < 0 && a1 < 0 || a0 == 0 && a1 == 0 || a0 > 0 && a1 > 0;
        }
      };

  /** @see BuiltIn#INT_SIGN */
  private static final Applicable INT_SIGN =
      new ApplicableImpl(BuiltIn.INT_SIGN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Integer.compare((Integer) arg, 0);
        }
      };

  /** @see BuiltIn#INT_TO_INT */
  private static final Applicable INT_TO_INT = identity(BuiltIn.INT_TO_INT);

  /** @see BuiltIn#INT_TO_LARGE */
  private static final Applicable INT_TO_LARGE = identity(BuiltIn.INT_TO_LARGE);

  /** @see BuiltIn#INT_TO_STRING */
  private static final Applicable INT_TO_STRING =
      new ApplicableImpl(BuiltIn.INT_TO_STRING) {
        @Override
        public String apply(EvalEnv env, Object arg) {
          // Java's formatting is reasonably close to ML's formatting,
          // if we replace minus signs.
          Integer f = (Integer) arg;
          final String s = Integer.toString(f);
          return s.replace('-', '~');
        }
      };

  /** @see BuiltIn#INTERACT_USE */
  private static final Applicable INTERACT_USE =
      new InteractUse(Pos.ZERO, false);

  /** @see BuiltIn#INTERACT_USE_SILENTLY */
  private static final Applicable INTERACT_USE_SILENTLY =
      new InteractUse(Pos.ZERO, true);

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

  /** Implements {@link BuiltIn#INTERACT_USE}. */
  private static class InteractUse extends PositionedApplicableImpl {
    private final boolean silent;

    InteractUse(Pos pos, boolean silent) {
      super(BuiltIn.INTERACT_USE, pos);
      this.silent = silent;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new InteractUse(pos, silent);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final String f = (String) arg;
      final Session session = (Session) env.getOpt(EvalEnv.SESSION);
      session.use(f, silent, pos);
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#OP_CARET */
  private static final Applicable OP_CARET =
      new Applicable2<String, String, String>(BuiltIn.OP_CARET) {
        @Override
        public String apply(String a0, String a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#OP_CONS */
  private static final Applicable OP_CONS =
      new Applicable2<List, Object, Iterable>(BuiltIn.OP_CONS) {
        @Override
        public List apply(Object e, Iterable iterable) {
          return ImmutableList.builder().add(e).addAll(iterable).build();
        }
      };

  /**
   * Returns a Code that returns the value of variable "name" in the current
   * environment.
   */
  public static Code get(String name) {
    return new GetCode(name);
  }

  /**
   * Returns a Code that returns a tuple consisting of the values of variables
   * "name0", ... "nameN" in the current environment.
   */
  public static Code getTuple(Iterable<String> names) {
    if (Iterables.isEmpty(names)) {
      return new ConstantCode(Unit.INSTANCE);
    }
    return new GetTupleCode(ImmutableList.copyOf(names));
  }

  public static Code let(List<Code> matchCodes, Code resultCode) {
    switch (matchCodes.size()) {
      case 0:
        return resultCode;

      case 1:
        // Use a more efficient runtime path if the list has only one element.
        // The effect is the same.
        return new Let1Code(matchCodes.get(0), resultCode);

      default:
        return new LetCode(ImmutableList.copyOf(matchCodes), resultCode);
    }
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

  /** Generates the code for applying a function value to two arguments. */
  public static Code apply2(Applicable2 fnValue, Code argCode0, Code argCode1) {
    return new ApplyCode2(fnValue, argCode0, argCode1);
  }

  /** Generates the code for applying a function value to three arguments. */
  public static Code apply3(
      Applicable3 fnValue, Code argCode0, Code argCode1, Code argCode2) {
    return new ApplyCode3(fnValue, argCode0, argCode1, argCode2);
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

  /**
   * Returns an applicable that constructs an instance of a datatype. The
   * instance is a list with two elements [constructorName, value].
   */
  public static Applicable tyCon(Type dataType, String name) {
    requireNonNull(dataType);
    requireNonNull(name);
    return new ApplicableImpl("tyCon") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return ImmutableList.of(name, arg);
      }
    };
  }

  public static Code from(Supplier<RowSink> rowSinkFactory) {
    return new FromCode(rowSinkFactory);
  }

  /** Creates a {@link RowSink} for a {@code join} step. */
  public static RowSink scanRowSink(
      Op op, Core.Pat pat, Code code, Code conditionCode, RowSink rowSink) {
    return new ScanRowSink(op, pat, code, conditionCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code where} step. */
  public static RowSink whereRowSink(Code filterCode, RowSink rowSink) {
    return new WhereRowSink(filterCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code skip} step. */
  public static RowSink skipRowSink(Code skipCode, RowSink rowSink) {
    return new SkipRowSink(skipCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code take} step. */
  public static RowSink takeRowSink(Code takeCode, RowSink rowSink) {
    return new TakeRowSink(takeCode, rowSink);
  }

  /** Creates a {@link RowSink} for an {@code except} step. */
  public static RowSink exceptRowSink(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      RowSink rowSink) {
    return distinct
        ? new ExceptDistinctRowSink(codes, names, rowSink)
        : new ExceptAllRowSink(codes, names, rowSink);
  }

  /** Creates a {@link RowSink} for an {@code intersect} step. */
  public static RowSink intersectRowSink(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      RowSink rowSink) {
    return distinct
        ? new IntersectDistinctRowSink(codes, names, rowSink)
        : new IntersectAllRowSink(codes, names, rowSink);
  }

  /**
   * Creates a {@link RowSink} for an {@code except}, {@code intersect} or
   * {@code union} step.
   */
  public static RowSink unionRowSink(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      RowSink rowSink) {
    return new UnionRowSink(distinct, codes, names, rowSink);
  }

  /** Creates a {@link RowSink} for an {@code order} step. */
  public static RowSink orderRowSink(
      Code code, Comparator comparator, Core.StepEnv env, RowSink rowSink) {
    ImmutableList<String> names = transformEager(env.bindings, b -> b.id.name);
    return new OrderRowSink(code, comparator, names, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code group} step. */
  public static RowSink groupRowSink(
      Code keyCode,
      ImmutableList<Applicable> aggregateCodes,
      ImmutableList<String> inNames,
      ImmutableList<String> keyNames,
      ImmutableList<String> outNames,
      RowSink rowSink) {
    return new GroupRowSink(
        keyCode, aggregateCodes, inNames, keyNames, outNames, rowSink);
  }

  /** Creates a {@link RowSink} for a non-terminal {@code yield} step. */
  public static RowSink yieldRowSink(
      Map<String, Code> yieldCodes, RowSink rowSink) {
    return new YieldRowSink(
        ImmutableList.copyOf(yieldCodes.keySet()),
        ImmutableList.copyOf(yieldCodes.values()),
        rowSink);
  }

  /**
   * Creates a {@link RowSink} to collect the results of a {@code from}
   * expression.
   */
  public static RowSink collectRowSink(Code code) {
    return new CollectRowSink(code);
  }

  /** Creates a {@link RowSink} that starts all downstream row sinks. */
  public static RowSink firstRowSink(RowSink rowSink) {
    // Use a Describer to walk over the downstream sinks and their codes, and
    // collect the start actions. The only start action, currently, resets
    // ordinals to -1.
    final List<Runnable> startActions = new ArrayList<>();
    rowSink.describe(
        new CodeVisitor() {
          @Override
          public void addStartAction(Runnable runnable) {
            startActions.add(runnable);
          }
        });
    if (startActions.isEmpty()) {
      // There are no start actions, so there's no need to wrap the in a
      // FirstRowSink.
      return rowSink;
    }
    return new FirstRowSink(rowSink, startActions);
  }

  /**
   * Returns an applicable that returns the {@code slot}th field of a tuple or
   * record.
   */
  public static Applicable nth(int slot) {
    assert slot >= 0 : slot;
    return new ApplicableImpl("nth:" + slot) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return ((List) arg).get(slot);
      }
    };
  }

  /** An applicable that negates a boolean value. */
  private static final Applicable NOT =
      new ApplicableImpl(BuiltIn.NOT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return !(Boolean) arg;
        }
      };

  /** An applicable that returns the absolute value of an int. */
  private static final Applicable ABS =
      new ApplicableImpl(BuiltIn.ABS) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Integer integer = (Integer) arg;
          return integer >= 0 ? integer : -integer;
        }
      };

  /** @see BuiltIn#IGNORE */
  private static final Applicable IGNORE =
      new ApplicableImpl(BuiltIn.IGNORE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#OP_MINUS */
  private static final Macro OP_MINUS =
      (typeSystem, env, argType) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.Z_MINUS_INT);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.Z_MINUS_REAL);
          default:
            throw new AssertionError("bad type " + argType);
        }
      };

  /** @see BuiltIn#OP_MOD */
  private static final Applicable OP_MOD = new IntMod(BuiltIn.OP_MOD);

  /** @see BuiltIn#OP_PLUS */
  private static final Macro OP_PLUS =
      (typeSystem, env, argType) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.Z_PLUS_INT);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.Z_PLUS_REAL);
          default:
            throw new AssertionError("bad type " + argType);
        }
      };

  /** @see BuiltIn#OP_TIMES */
  private static final Macro OP_TIMES =
      (typeSystem, env, argType) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.Z_TIMES_INT);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.Z_TIMES_REAL);
          default:
            throw new AssertionError("bad type " + argType);
        }
      };

  /** @see BuiltIn#STRING_MAX_SIZE */
  private static final Integer STRING_MAX_SIZE = Integer.MAX_VALUE;

  /** @see BuiltIn#STRING_OP_CARET */
  private static final Applicable STRING_OP_CARET =
      new Applicable2<String, String, String>(BuiltIn.STRING_OP_CARET) {
        @Override
        public String apply(String a0, String a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#STRING_OP_GE */
  private static final Applicable STRING_OP_GE =
      new Applicable2<Boolean, String, String>(BuiltIn.STRING_OP_GE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) >= 0;
        }
      };

  /** @see BuiltIn#STRING_OP_GT */
  private static final Applicable STRING_OP_GT =
      new Applicable2<Boolean, String, String>(BuiltIn.STRING_OP_GT) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) > 0;
        }
      };

  /** @see BuiltIn#STRING_OP_LE */
  private static final Applicable STRING_OP_LE =
      new Applicable2<Boolean, String, String>(BuiltIn.STRING_OP_LE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) <= 0;
        }
      };

  /** @see BuiltIn#STRING_OP_LT */
  private static final Applicable STRING_OP_LT =
      new Applicable2<Boolean, String, String>(BuiltIn.STRING_OP_LT) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) < 0;
        }
      };

  /** @see BuiltIn#STRING_SIZE */
  private static final Applicable STRING_SIZE =
      new ApplicableImpl(BuiltIn.STRING_SIZE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return ((String) arg).length();
        }
      };

  /** @see BuiltIn#STRING_SUB */
  private static final Applicable STRING_SUB = new StringSub(Pos.ZERO);

  /** Implements {@link BuiltIn#STRING_SUB}. */
  private static class StringSub extends Applicable2<Character, String, Integer>
      implements Positioned {
    StringSub(Pos pos) {
      super(BuiltIn.STRING_SUB, pos);
    }

    @Override
    public Character apply(String s, Integer i) {
      if (i < 0 || i >= s.length()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return s.charAt(i);
    }

    public StringSub withPos(Pos pos) {
      return new StringSub(pos);
    }
  }

  /** @see BuiltIn#STRING_COLLATE */
  private static final Applicable STRING_COLLATE =
      new ApplicableImpl(BuiltIn.STRING_COLLATE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return stringCollate((Applicable) arg);
        }
      };

  private static Applicable stringCollate(Applicable comparator) {
    return new ApplicableImpl("String.collate$comparator") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List tuple = (List) arg;
        final String string0 = (String) tuple.get(0);
        final String string1 = (String) tuple.get(1);
        final int n0 = string0.length();
        final int n1 = string1.length();
        final int n = Math.min(n0, n1);
        for (int i = 0; i < n; i++) {
          final char char0 = string0.charAt(i);
          final char char1 = string1.charAt(i);
          final List compare =
              (List) comparator.apply(env, ImmutableList.of(char0, char1));
          if (!compare.get(0).equals("EQUAL")) {
            return compare;
          }
        }
        return order(Integer.compare(n0, n1));
      }
    };
  }

  /** @see BuiltIn#STRING_COMPARE */
  private static final Applicable STRING_COMPARE =
      new Applicable2<List, String, String>(BuiltIn.STRING_COMPARE) {
        @Override
        public List apply(String a0, String a1) {
          return order(a0.compareTo(a1));
        }
      };

  /** @see BuiltIn#STRING_EXTRACT */
  private static final Applicable STRING_EXTRACT = new StringExtract(Pos.ZERO);

  /** @see BuiltIn#STRING_FIELDS */
  private static final Applicable STRING_FIELDS =
      fieldsTokens(BuiltIn.STRING_FIELDS);

  /** @see BuiltIn#STRING_TOKENS */
  private static final Applicable STRING_TOKENS =
      fieldsTokens(BuiltIn.STRING_TOKENS);

  private static Applicable fieldsTokens(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object argValue) {
        return fieldsTokens2(builtIn, (Applicable) argValue);
      }
    };
  }

  private static Applicable fieldsTokens2(
      BuiltIn builtIn, Applicable applicable) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object argValue) {
        String s = (String) argValue;
        List<String> result = new ArrayList<>();
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
          char c = s.charAt(i);
          Boolean b = (Boolean) applicable.apply(env, c);
          if (b) {
            if (builtIn == BuiltIn.STRING_FIELDS || i > h) {
              // String.tokens only adds fields if they are non-empty.
              result.add(s.substring(h, i));
            }
            h = i + 1;
          }
        }
        if (builtIn == BuiltIn.STRING_FIELDS || s.length() > h) {
          // String.tokens only adds fields if they are non-empty.
          result.add(s.substring(h));
        }
        return result;
      }
    };
  }

  /** Implements {@link BuiltIn#STRING_SUB}. */
  private static class StringExtract
      extends Applicable3<String, String, Integer, List> implements Positioned {
    StringExtract(Pos pos) {
      super(BuiltIn.STRING_EXTRACT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new StringExtract(pos);
    }

    @Override
    public String apply(String s, Integer i, List jOpt) {
      if (i < 0) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      if (jOpt.size() == 2) {
        final int j = (Integer) jOpt.get(1);
        if (j < 0 || i + j > s.length()) {
          throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
        }
        return s.substring(i, i + j);
      } else {
        if (i > s.length()) {
          throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
        }
        return s.substring(i);
      }
    }
  }

  /** @see BuiltIn#STRING_SUBSTRING */
  private static final Applicable STRING_SUBSTRING =
      new StringSubstring(Pos.ZERO);

  /** Implements {@link BuiltIn#STRING_SUBSTRING}. */
  private static class StringSubstring
      extends Applicable3<String, String, Integer, Integer>
      implements Positioned {
    StringSubstring(Pos pos) {
      super(BuiltIn.STRING_SUBSTRING, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new StringSubstring(pos);
    }

    @Override
    public String apply(String s, Integer i, Integer j) {
      if (i < 0 || j < 0 || i + j > s.length()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return s.substring(i, i + j);
    }
  }

  /** @see BuiltIn#STRING_CONCAT */
  private static final Applicable STRING_CONCAT = new StringConcat(Pos.ZERO);

  /** Implements {@link BuiltIn#STRING_CONCAT}. */
  private static class StringConcat extends PositionedApplicableImpl {
    StringConcat(Pos pos) {
      super(BuiltIn.STRING_CONCAT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new StringConcat(pos);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object apply(EvalEnv env, Object arg) {
      return stringConcat(pos, "", (List<String>) arg);
    }
  }

  /** @see BuiltIn#STRING_CONCAT_WITH */
  private static final Applicable STRING_CONCAT_WITH =
      new StringConcatWith(Pos.ZERO);

  /** Implements {@link BuiltIn#STRING_CONCAT_WITH}. */
  private static class StringConcatWith extends PositionedApplicableImpl {
    StringConcatWith(Pos pos) {
      super(BuiltIn.STRING_CONCAT_WITH, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new StringConcatWith(pos);
    }

    @Override
    public Object apply(EvalEnv env, Object argValue) {
      final String separator = (String) argValue;
      return new ApplicableImpl("String.concatWith$separator") {
        @SuppressWarnings("unchecked")
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return stringConcat(pos, separator, (List<String>) arg);
        }
      };
    }
  }

  private static String stringConcat(
      Pos pos, String separator, List<String> list) {
    long n = 0;
    for (String s : list) {
      n += s.length();
      n += separator.length();
    }
    if (n > STRING_MAX_SIZE) {
      throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
    }
    return String.join(separator, list);
  }

  /** @see BuiltIn#STRING_STR */
  private static final Applicable STRING_STR =
      new ApplicableImpl(BuiltIn.STRING_STR) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Character character = (Character) arg;
          return character + "";
        }
      };

  /** @see BuiltIn#STRING_IMPLODE */
  private static final Applicable STRING_IMPLODE =
      new ApplicableImpl(BuiltIn.STRING_IMPLODE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          // Note: In theory this function should raise Size, but it is not
          // possible in practice because List.size() is never larger than
          // Integer.MAX_VALUE.
          return String.valueOf(Chars.toArray((List) arg));
        }
      };

  /** @see BuiltIn#STRING_EXPLODE */
  private static final Applicable STRING_EXPLODE =
      new ApplicableImpl(BuiltIn.STRING_EXPLODE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return MapList.of(s.length(), s::charAt);
        }
      };

  /** @see BuiltIn#STRING_MAP */
  private static final Applicable STRING_MAP =
      new ApplicableImpl(BuiltIn.STRING_MAP) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return stringMap((Applicable) arg);
        }
      };

  private static Applicable stringMap(Applicable f) {
    return new ApplicableImpl("String.map$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final String s = (String) arg;
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
          final char c = s.charAt(i);
          final char c2 = (Character) f.apply(env, c);
          buf.append(c2);
        }
        return buf.toString();
      }
    };
  }

  /** @see BuiltIn#STRING_TRANSLATE */
  private static final Applicable STRING_TRANSLATE =
      new ApplicableImpl(BuiltIn.STRING_TRANSLATE) {
        @Override
        public Applicable apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return translate(f);
        }
      };

  private static Applicable translate(Applicable f) {
    return new ApplicableImpl("String.translate$f") {
      @Override
      public String apply(EvalEnv env, Object arg) {
        final String s = (String) arg;
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
          final char c = s.charAt(i);
          final String c2 = (String) f.apply(env, c);
          buf.append(c2);
        }
        return buf.toString();
      }
    };
  }

  /** @see BuiltIn#STRING_IS_PREFIX */
  private static final Applicable STRING_IS_PREFIX =
      new ApplicableImpl(BuiltIn.STRING_IS_PREFIX) {
        @Override
        public Applicable apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return isPrefix(s);
        }
      };

  private static Applicable isPrefix(String s) {
    return new ApplicableImpl("String.isPrefix$s") {
      @Override
      public Boolean apply(EvalEnv env, Object arg) {
        final String s2 = (String) arg;
        return s2.startsWith(s);
      }
    };
  }

  /** @see BuiltIn#STRING_IS_SUBSTRING */
  private static final Applicable STRING_IS_SUBSTRING =
      new ApplicableImpl(BuiltIn.STRING_IS_SUBSTRING) {
        @Override
        public Applicable apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return isSubstring(s);
        }
      };

  private static Applicable isSubstring(String s) {
    return new ApplicableImpl("String.isSubstring$s") {
      @Override
      public Boolean apply(EvalEnv env, Object arg) {
        final String s2 = (String) arg;
        return s2.contains(s);
      }
    };
  }

  /** @see BuiltIn#STRING_IS_SUFFIX */
  private static final Applicable STRING_IS_SUFFIX =
      new ApplicableImpl(BuiltIn.STRING_IS_SUFFIX) {
        @Override
        public Applicable apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return isSuffix(s);
        }
      };

  private static Applicable isSuffix(String s) {
    return new ApplicableImpl("String.isSuffix$s") {
      @Override
      public Boolean apply(EvalEnv env, Object arg) {
        final String s2 = (String) arg;
        return s2.endsWith(s);
      }
    };
  }

  /** @see BuiltIn#LIST_NULL */
  private static final Applicable LIST_NULL = empty(BuiltIn.LIST_NULL);

  /** @see BuiltIn#LIST_LENGTH */
  private static final Applicable LIST_LENGTH = length(BuiltIn.LIST_LENGTH);

  private static ApplicableImpl length(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return ((List) arg).size();
      }
    };
  }

  /** @see BuiltIn#LIST_AT */
  private static final Applicable LIST_AT = union(BuiltIn.LIST_AT);

  private static ApplicableImpl union(final BuiltIn builtIn) {
    return new Applicable2<List, List, List>(builtIn) {
      @Override
      public List apply(List list0, List list1) {
        return ImmutableList.builder().addAll(list0).addAll(list1).build();
      }
    };
  }

  /** @see BuiltIn#LIST_HD */
  private static final Applicable LIST_HD =
      new ListHd(BuiltIn.LIST_HD, Pos.ZERO);

  /** Implements {@link BuiltIn#LIST_HD}. */
  private static class ListHd extends PositionedApplicableImpl {
    ListHd(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListHd(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List list = (List) arg;
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.get(0);
    }
  }

  /** @see BuiltIn#LIST_TL */
  private static final Applicable LIST_TL =
      new ListTl(BuiltIn.LIST_TL, Pos.ZERO);

  /** Implements {@link BuiltIn#LIST_TL}. */
  private static class ListTl extends PositionedApplicableImpl {
    ListTl(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTl(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List list = (List) arg;
      final int size = list.size();
      if (size == 0) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.subList(1, size);
    }
  }

  /** @see BuiltIn#LIST_LAST */
  private static final Applicable LIST_LAST =
      new ListLast(BuiltIn.LIST_LAST, Pos.ZERO);

  /** Implements {@link BuiltIn#LIST_LAST}. */
  private static class ListLast extends PositionedApplicableImpl {
    ListLast(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListLast(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List list = (List) arg;
      final int size = list.size();
      if (size == 0) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.get(size - 1);
    }
  }

  /** @see BuiltIn#LIST_GET_ITEM */
  private static final Applicable LIST_GET_ITEM =
      listGetItem(BuiltIn.LIST_GET_ITEM);

  private static ApplicableImpl listGetItem(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        if (list.isEmpty()) {
          return OPTION_NONE;
        } else {
          return optionSome(
              ImmutableList.of(list.get(0), list.subList(1, list.size())));
        }
      }
    };
  }

  /** @see BuiltIn#LIST_NTH */
  private static final Applicable LIST_NTH =
      new ListNth(BuiltIn.LIST_NTH, Pos.ZERO);

  /** Implements {@link BuiltIn#LIST_NTH} and {@link BuiltIn#VECTOR_SUB}. */
  private static class ListNth extends Applicable2<Object, List, Integer>
      implements Positioned {
    private final BuiltIn builtIn;

    ListNth(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
      this.builtIn = builtIn;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListNth(builtIn, pos);
    }

    @Override
    public Object apply(List list, Integer i) {
      if (i < 0 || i >= list.size()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return list.get(i);
    }
  }

  /** @see BuiltIn#LIST_TAKE */
  private static final Applicable LIST_TAKE =
      new ListTake(BuiltIn.LIST_TAKE, Pos.ZERO);

  /** Implements {@link BuiltIn#LIST_TAKE}. */
  private static class ListTake extends Applicable2<List, List, Integer>
      implements Positioned {
    private final BuiltIn builtIn;

    ListTake(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
      this.builtIn = builtIn;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTake(builtIn, pos);
    }

    @Override
    public List apply(List list, Integer i) {
      if (i < 0 || i > list.size()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return list.subList(0, i);
    }
  }

  /** @see BuiltIn#LIST_DROP */
  private static final Applicable LIST_DROP = listDrop(BuiltIn.LIST_DROP);

  private static Applicable2<List, List, Integer> listDrop(BuiltIn builtIn) {
    return new Applicable2<List, List, Integer>(builtIn) {
      @Override
      public List apply(List list, Integer i) {
        return list.subList(i, list.size());
      }
    };
  }

  /** @see BuiltIn#LIST_REV */
  private static final Applicable LIST_REV =
      new ApplicableImpl(BuiltIn.LIST_REV) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return Lists.reverse(list);
        }
      };

  /** @see BuiltIn#LIST_CONCAT */
  private static final Applicable LIST_CONCAT = listConcat(BuiltIn.LIST_CONCAT);

  private static ApplicableImpl listConcat(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        final ImmutableList.Builder<Object> builder = ImmutableList.builder();
        for (Object o : list) {
          builder.addAll((List) o);
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_EXCEPT */
  private static final Applicable LIST_EXCEPT =
      new ApplicableImpl(BuiltIn.LIST_EXCEPT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List<List> list = (List) arg;
          if (list.isEmpty()) {
            throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
          }
          List collection = new ArrayList(list.get(0));
          for (int i = 1; i < list.size(); i++) {
            collection.removeAll(list.get(i));
          }
          if (collection.size() == list.get(0).size()) {
            collection = list.get(0); // save the effort of making a copy
          }
          return ImmutableList.copyOf(collection);
        }
      };

  /** @see BuiltIn#LIST_INTERSECT */
  private static final Applicable LIST_INTERSECT =
      new ApplicableImpl(BuiltIn.LIST_EXCEPT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List<List> list = (List) arg;
          if (list.isEmpty()) {
            throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
          }
          List collection = new ArrayList(list.get(0));
          for (int i = 1; i < list.size(); i++) {
            final Set set = new HashSet(list.get(i));
            collection.retainAll(set);
          }
          if (collection.size() == list.get(0).size()) {
            collection = list.get(0); // save the effort of making a copy
          }
          return ImmutableList.copyOf(collection);
        }
      };

  /** @see BuiltIn#LIST_REV_APPEND */
  private static final Applicable LIST_REV_APPEND =
      new Applicable2<List, List, List>(BuiltIn.LIST_REV_APPEND) {
        @Override
        public List apply(List list0, List list1) {
          return ImmutableList.builder()
              .addAll(Lists.reverse(list0))
              .addAll(list1)
              .build();
        }
      };

  /** @see BuiltIn#LIST_APP */
  private static final Applicable LIST_APP = listApp0(BuiltIn.LIST_APP);

  private static ApplicableImpl listApp0(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Applicable apply(EvalEnv env, Object arg) {
        return listApp((Applicable) arg);
      }
    };
  }

  private static Applicable listApp(Applicable consumer) {
    return new ApplicableImpl("List.app$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        list.forEach(o -> consumer.apply(env, o));
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#LIST_MAPI */
  private static final Applicable LIST_MAPI =
      new ApplicableImpl(BuiltIn.LIST_MAPI) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return listMapi((Applicable) arg);
        }
      };

  /** @see BuiltIn#LIST_MAP */
  private static final Applicable LIST_MAP = listMap0(BuiltIn.LIST_MAP);

  private static Applicable listMap0(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Applicable apply(EvalEnv env, Object arg) {
        return listMap((Applicable) arg);
      }
    };
  }

  private static Applicable listMap(Applicable fn) {
    return new ApplicableImpl("List.map$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        final ImmutableList.Builder<Object> builder = ImmutableList.builder();
        for (Object o : list) {
          builder.add(fn.apply(env, o));
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_MAP_PARTIAL */
  private static final Applicable LIST_MAP_PARTIAL =
      listMapPartial0(BuiltIn.LIST_MAP_PARTIAL);

  private static Applicable listMapPartial0(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Applicable apply(EvalEnv env, Object arg) {
        return listMapPartial((Applicable) arg);
      }
    };
  }

  private static Applicable listMapPartial(Applicable f) {
    return new ApplicableImpl("List.mapPartial$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        final ImmutableList.Builder<Object> builder = ImmutableList.builder();
        for (Object o : list) {
          final List opt = (List) f.apply(env, o);
          if (opt.size() == 2) {
            builder.add(opt.get(1));
          }
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_FIND */
  private static final Applicable LIST_FIND = find(BuiltIn.LIST_FIND);

  private static ApplicableImpl find(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Applicable apply(EvalEnv env, Object arg) {
        final Applicable fn = (Applicable) arg;
        return find(fn);
      }
    };
  }

  private static Applicable find(Applicable f) {
    return new ApplicableImpl("List.find$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        for (Object o : list) {
          if ((Boolean) f.apply(env, o)) {
            return optionSome(o);
          }
        }
        return OPTION_NONE;
      }
    };
  }

  /** @see BuiltIn#LIST_FILTER */
  private static final Applicable LIST_FILTER =
      listFilter0(BuiltIn.LIST_FILTER);

  private static Applicable listFilter0(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final Applicable fn = (Applicable) arg;
        return listFilter(fn);
      }
    };
  }

  private static Applicable listFilter(Applicable f) {
    return new ApplicableImpl("List.filter$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          if ((Boolean) f.apply(env, o)) {
            builder.add(o);
          }
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_PARTITION */
  private static final Applicable LIST_PARTITION =
      listPartition0(BuiltIn.LIST_PARTITION);

  private static ApplicableImpl listPartition0(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final Applicable fn = (Applicable) arg;
        return listPartition(fn);
      }
    };
  }

  private static Applicable listPartition(Applicable f) {
    return new ApplicableImpl("List.partition$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        final ImmutableList.Builder trueBuilder = ImmutableList.builder();
        final ImmutableList.Builder falseBuilder = ImmutableList.builder();
        for (Object o : list) {
          ((Boolean) f.apply(env, o) ? trueBuilder : falseBuilder).add(o);
        }
        return ImmutableList.of(trueBuilder.build(), falseBuilder.build());
      }
    };
  }

  /** @see BuiltIn#LIST_FOLDL */
  private static final Applicable LIST_FOLDL =
      listFold0(BuiltIn.LIST_FOLDL, true);

  /** @see BuiltIn#LIST_FOLDR */
  private static final Applicable LIST_FOLDR =
      listFold0(BuiltIn.LIST_FOLDR, false);

  private static ApplicableImpl listFold0(BuiltIn builtIn, boolean left) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return listFold(left, (Applicable) arg);
      }
    };
  }

  private static Applicable listFold(boolean left, Applicable f) {
    return new ApplicableImpl("List.fold$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return listFold2(left, f, arg);
      }
    };
  }

  private static Applicable listFold2(boolean left, Applicable f, Object init) {
    return new ApplicableImpl("List.fold$f$init") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        Object b = init;
        for (Object a : left ? list : Lists.reverse(list)) {
          b = f.apply(env, ImmutableList.of(a, b));
        }
        return b;
      }
    };
  }

  /** @see BuiltIn#LIST_EXISTS */
  private static final Applicable LIST_EXISTS = exists(BuiltIn.LIST_EXISTS);

  private static ApplicableImpl exists(final BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return listExists((Applicable) arg);
      }
    };
  }

  private static Applicable listExists(Applicable f) {
    return new ApplicableImpl("List.exists$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        for (Object o : list) {
          if ((Boolean) f.apply(env, o)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  /** @see BuiltIn#LIST_ALL */
  private static final Applicable LIST_ALL = all(BuiltIn.LIST_ALL);

  private static ApplicableImpl all(final BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return listAll((Applicable) arg);
      }
    };
  }

  private static Applicable listAll(Applicable f) {
    return new ApplicableImpl("List.all$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        for (Object o : list) {
          if (!(Boolean) f.apply(env, o)) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** @see BuiltIn#LIST_TABULATE */
  private static final Applicable LIST_TABULATE =
      new ListTabulate(BuiltIn.LIST_TABULATE, Pos.ZERO);

  /** Implements {@link BuiltIn#LIST_TABULATE}. */
  private static class ListTabulate extends PositionedApplicableImpl {
    ListTabulate(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTabulate(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List tuple = (List) arg;
      final int count = (Integer) tuple.get(0);
      if (count < 0) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      final Applicable fn = (Applicable) tuple.get(1);
      final ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (int i = 0; i < count; i++) {
        builder.add(fn.apply(env, i));
      }
      return builder.build();
    }
  }

  /** @see BuiltIn#LIST_COLLATE */
  private static final Applicable LIST_COLLATE = collate(BuiltIn.LIST_COLLATE);

  private static ApplicableImpl collate(final BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return collate((Applicable) arg);
      }
    };
  }

  private static Applicable collate(Applicable comparator) {
    return new ApplicableImpl("List.collate$comparator") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List tuple = (List) arg;
        final List list0 = (List) tuple.get(0);
        final List list1 = (List) tuple.get(1);
        final int n0 = list0.size();
        final int n1 = list1.size();
        final int n = Math.min(n0, n1);
        for (int i = 0; i < n; i++) {
          final Object element0 = list0.get(i);
          final Object element1 = list1.get(i);
          final List compare =
              (List)
                  comparator.apply(env, ImmutableList.of(element0, element1));
          if (!compare.get(0).equals("EQUAL")) {
            return compare;
          }
        }
        return order(Integer.compare(n0, n1));
      }
    };
  }

  /** @see BuiltIn#LIST_PAIR_ALL */
  private static final Applicable LIST_PAIR_ALL =
      new ApplicableImpl(BuiltIn.LIST_PAIR_ALL) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return listPairAll((Applicable) arg, false);
        }
      };

  /** @see BuiltIn#LIST_PAIR_ALL_EQ */
  private static final Applicable LIST_PAIR_ALL_EQ =
      new ApplicableImpl(BuiltIn.LIST_PAIR_ALL_EQ) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return listPairAll((Applicable) arg, true);
        }
      };

  private static Applicable listPairAll(Applicable f, boolean eq) {
    return new ApplicableImpl("ListPair.all$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List<List<Object>> listPair = (List<List<Object>>) arg;
        final List<Object> list0 = listPair.get(0);
        final List<Object> list1 = listPair.get(1);
        if (eq && list0.size() != list1.size()) {
          return false;
        }
        final Iterator<Object> iter0 = list0.iterator();
        final Iterator<Object> iter1 = list1.iterator();
        while (iter0.hasNext() && iter1.hasNext()) {
          final List<Object> args = FlatLists.of(iter0.next(), iter1.next());
          if (!(Boolean) f.apply(env, args)) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** Helper for {@link #LIST_PAIR_APP} and {@link #LIST_PAIR_APP_EQ}. */
  private static class ListPairApp extends PositionedApplicableImpl {
    ListPairApp(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairApp(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final Applicable f = (Applicable) arg;
      return new ListPairApp2(builtIn, pos, f);
    }
  }

  /** Second stage of {@link ListPairApp}. */
  private static class ListPairApp2 extends Applicable1 {
    private final Applicable f;

    ListPairApp2(BuiltIn builtIn, Pos pos, Applicable f) {
      super(builtIn, pos);
      this.f = f;
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List<List<Object>> listPair = (List<List<Object>>) arg;
      final List<Object> list0 = listPair.get(0);
      final List<Object> list1 = listPair.get(1);
      if (builtIn == BuiltIn.LIST_PAIR_APP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final Iterator<Object> iter0 = list0.iterator();
      final Iterator<Object> iter1 = list1.iterator();
      while (iter0.hasNext() && iter1.hasNext()) {
        final List<Object> args = FlatLists.of(iter0.next(), iter1.next());
        f.apply(env, args);
      }
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#LIST_PAIR_APP */
  private static final Applicable LIST_PAIR_APP =
      new ListPairApp(BuiltIn.LIST_PAIR_APP, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_APP_EQ */
  private static final Applicable LIST_PAIR_APP_EQ =
      new ListPairApp(BuiltIn.LIST_PAIR_APP_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_EXISTS */
  private static final Applicable LIST_PAIR_EXISTS =
      new ApplicableImpl(BuiltIn.LIST_PAIR_EXISTS) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return listPairExists((Applicable) arg);
        }
      };

  private static Applicable listPairExists(Applicable f) {
    return new ApplicableImpl("ListPair.exists$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List<List<Object>> listPair = (List<List<Object>>) arg;
        final List<Object> list0 = listPair.get(0);
        final List<Object> list1 = listPair.get(1);
        final Iterator<Object> iter0 = list0.iterator();
        final Iterator<Object> iter1 = list1.iterator();
        while (iter0.hasNext() && iter1.hasNext()) {
          final List<Object> args = FlatLists.of(iter0.next(), iter1.next());
          if ((Boolean) f.apply(env, args)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  /**
   * Helper for {@link #LIST_PAIR_FOLDL}, {@link #LIST_PAIR_FOLDL_EQ}, {@link
   * #LIST_PAIR_FOLDR}, {@link #LIST_PAIR_FOLDR_EQ}.
   */
  private static class ListPairFold extends PositionedApplicableImpl {
    ListPairFold(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairFold(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      return new ListPairFold2(builtIn, pos, (Applicable) arg);
    }
  }

  /** Second stage of {@link ListPairFold}. */
  private static class ListPairFold2 extends Applicable1 {
    private final Applicable f;

    ListPairFold2(BuiltIn builtIn, Pos pos, Applicable f) {
      super(builtIn, pos);
      this.f = f;
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      return new ListPairFold3(builtIn, pos, f, arg);
    }
  }

  /** Third stage of {@link ListPairFold}. */
  private static class ListPairFold3 extends Applicable1 {
    private final Applicable f;
    private final Object init;

    ListPairFold3(BuiltIn builtIn, Pos pos, Applicable f, Object init) {
      super(builtIn, pos);
      this.f = f;
      this.init = init;
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List<List<Object>> pair = (List) arg;
      final List<Object> list0 = pair.get(0);
      final List<Object> list1 = pair.get(1);
      if (eq() && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final int n = Math.min(list0.size(), list1.size());
      Object b = init;
      if (left()) {
        for (int i = 0; i < n; i++) {
          b = f.apply(env, FlatLists.of(list0.get(i), list1.get(i), b));
        }
      } else {
        for (int i = n - 1; i >= 0; i--) {
          b = f.apply(env, FlatLists.of(list0.get(i), list1.get(i), b));
        }
      }
      return b;
    }

    private boolean eq() {
      return builtIn == BuiltIn.LIST_PAIR_FOLDL_EQ
          || builtIn == BuiltIn.LIST_PAIR_FOLDR_EQ;
    }

    private boolean left() {
      return builtIn == BuiltIn.LIST_PAIR_FOLDL
          || builtIn == BuiltIn.LIST_PAIR_FOLDL_EQ;
    }
  }

  /** @see BuiltIn#LIST_PAIR_FOLDL */
  private static final Applicable LIST_PAIR_FOLDL =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDL, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_FOLDL_EQ */
  private static final Applicable LIST_PAIR_FOLDL_EQ =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDL_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_FOLDR */
  private static final Applicable LIST_PAIR_FOLDR =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDR, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_FOLDR_EQ */
  private static final Applicable LIST_PAIR_FOLDR_EQ =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDR_EQ, Pos.ZERO);

  /** Helper for {@link #LIST_PAIR_MAP}, {@link #LIST_PAIR_MAP_EQ}. */
  private static class ListPairMap extends PositionedApplicableImpl {
    ListPairMap(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairMap(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final Applicable f = (Applicable) arg;
      return new ListPairMap2(builtIn, pos, f);
    }
  }

  /** Second stage of {@link ListPairMap}. */
  private static class ListPairMap2 extends Applicable1 {
    private final Applicable f;

    ListPairMap2(BuiltIn builtIn, Pos pos, Applicable f) {
      super(builtIn, pos);
      this.f = f;
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List<List<Object>> listPair = (List) arg;
      List<Object> list0 = listPair.get(0);
      List<Object> list1 = listPair.get(1);
      if (builtIn == BuiltIn.LIST_PAIR_MAP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final ImmutableList.Builder<@NonNull Object> result =
          ImmutableList.builder();
      forEach(
          list0, list1, (a, b) -> result.add(f.apply(env, FlatLists.of(a, b))));
      return result.build();
    }
  }

  /** @see BuiltIn#LIST_PAIR_MAP */
  private static final Applicable LIST_PAIR_MAP =
      new ListPairMap(BuiltIn.LIST_PAIR_MAP, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_MAP_EQ */
  private static final Applicable LIST_PAIR_MAP_EQ =
      new ListPairMap(BuiltIn.LIST_PAIR_MAP_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_UNZIP */
  private static final Applicable LIST_PAIR_UNZIP =
      new ApplicableImpl(BuiltIn.LIST_PAIR_UNZIP) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List<List<Object>> list = (List) arg;
          if (list.isEmpty()) {
            throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
          }
          final ImmutableList.Builder<@NonNull Object> builder0 =
              ImmutableList.builder();
          final ImmutableList.Builder<@NonNull Object> builder1 =
              ImmutableList.builder();
          for (List<Object> pair : list) {
            builder0.add(pair.get(0));
            builder1.add(pair.get(1));
          }
          return ImmutableList.of(builder0.build(), builder1.build());
        }
      };

  /** Helper for {@link #LIST_PAIR_ZIP} and {@link #LIST_PAIR_ZIP_EQ}. */
  private static class ListPairZip extends PositionedApplicableImpl {
    ListPairZip(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairZip(builtIn, pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      List<List<Object>> listPair = (List) arg;
      final List<Object> list0 = listPair.get(0);
      final List<Object> list1 = listPair.get(1);
      if (builtIn == BuiltIn.LIST_PAIR_ZIP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final List<Object> result = new ArrayList<>();
      forEach(list0, list1, (a, b) -> result.add(FlatLists.of(a, b)));
      return result;
    }
  }

  /** @see BuiltIn#LIST_PAIR_ZIP */
  private static final Applicable LIST_PAIR_ZIP =
      new ListPairZip(BuiltIn.LIST_PAIR_ZIP, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_ZIP_EQ */
  private static final Applicable LIST_PAIR_ZIP_EQ =
      new ListPairZip(BuiltIn.LIST_PAIR_ZIP_EQ, Pos.ZERO);

  /** @see BuiltIn#MATH_ACOS */
  private static final Applicable MATH_ACOS =
      new ApplicableImpl(BuiltIn.MATH_ACOS) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.acos((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_ASIN */
  private static final Applicable MATH_ASIN =
      new ApplicableImpl(BuiltIn.MATH_ASIN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.asin((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_ATAN */
  private static final Applicable MATH_ATAN =
      new ApplicableImpl(BuiltIn.MATH_ATAN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.atan((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_ATAN2 */
  private static final Applicable MATH_ATAN2 =
      new Applicable2<Float, Float, Float>(BuiltIn.MATH_ATAN2) {
        @Override
        public Float apply(Float arg0, Float arg1) {
          return (float) Math.atan2(arg0, arg1);
        }
      };

  /** @see BuiltIn#MATH_COS */
  private static final Applicable MATH_COS =
      new ApplicableImpl(BuiltIn.MATH_COS) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.cos((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_COSH */
  private static final Applicable MATH_COSH =
      new ApplicableImpl(BuiltIn.MATH_COSH) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.cosh((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_E */
  private static final float MATH_E = (float) Math.E;

  /** @see BuiltIn#MATH_EXP */
  private static final Applicable MATH_EXP =
      new ApplicableImpl(BuiltIn.MATH_EXP) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.exp((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_LN */
  private static final Applicable MATH_LN =
      new ApplicableImpl(BuiltIn.MATH_LN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.log((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_LOG10 */
  private static final Applicable MATH_LOG10 =
      new ApplicableImpl(BuiltIn.MATH_LOG10) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.log10((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_PI */
  private static final float MATH_PI = (float) Math.PI;

  /** @see BuiltIn#MATH_POW */
  private static final Applicable MATH_POW =
      new Applicable2<Float, Float, Float>(BuiltIn.MATH_POW) {
        @Override
        public Float apply(Float arg0, Float arg1) {
          return (float) Math.pow(arg0, arg1);
        }
      };

  /** @see BuiltIn#MATH_SIN */
  private static final Applicable MATH_SIN =
      new ApplicableImpl(BuiltIn.MATH_SIN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.sin((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_SINH */
  private static final Applicable MATH_SINH =
      new ApplicableImpl(BuiltIn.MATH_SINH) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.sinh((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_SQRT */
  private static final Applicable MATH_SQRT =
      new ApplicableImpl(BuiltIn.MATH_SQRT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.sqrt((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_TAN */
  private static final Applicable MATH_TAN =
      new ApplicableImpl(BuiltIn.MATH_TAN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.tan((Float) arg);
        }
      };

  /** @see BuiltIn#MATH_TANH */
  private static final Applicable MATH_TANH =
      new ApplicableImpl(BuiltIn.MATH_TANH) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return (float) Math.tanh((Float) arg);
        }
      };

  /** @see BuiltIn#OPTION_APP */
  private static final Applicable OPTION_APP =
      new ApplicableImpl(BuiltIn.OPTION_APP) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return optionApp(f);
        }
      };

  /** Implements {@link #OPTION_APP}. */
  private static Applicable optionApp(Applicable f) {
    return new ApplicableImpl("Option.app$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List a = (List) arg;
        if (a.size() == 2) {
          f.apply(env, a.get(1));
        }
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#OPTION_GET_OPT */
  private static final Applicable OPTION_GET_OPT =
      new ApplicableImpl(BuiltIn.OPTION_GET_OPT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List opt = (List) tuple.get(0);
          if (opt.size() == 2) {
            assert opt.get(0).equals("SOME");
            return opt.get(1); // SOME has 2 elements, NONE has 1
          }
          return tuple.get(1);
        }
      };

  /** @see BuiltIn#OPTION_IS_SOME */
  private static final Applicable OPTION_IS_SOME =
      new ApplicableImpl(BuiltIn.OPTION_IS_SOME) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List opt = (List) arg;
          return opt.size() == 2; // SOME has 2 elements, NONE has 1
        }
      };

  /** @see BuiltIn#OPTION_VAL_OF */
  private static final Applicable OPTION_VAL_OF = new OptionValOf(Pos.ZERO);

  /** Implements {@link BuiltIn#OPTION_VAL_OF}. */
  private static class OptionValOf extends PositionedApplicableImpl {
    OptionValOf(Pos pos) {
      super(BuiltIn.OPTION_VAL_OF, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new OptionValOf(pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List opt = (List) arg;
      if (opt.size() == 2) { // SOME has 2 elements, NONE has 1
        return opt.get(1);
      } else {
        throw new MorelRuntimeException(BuiltInExn.OPTION, pos);
      }
    }
  }

  /** @see BuiltIn#OPTION_FILTER */
  private static final Applicable OPTION_FILTER =
      new ApplicableImpl(BuiltIn.OPTION_FILTER) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return optionFilter(f);
        }
      };

  /** Implementation of {@link #OPTION_FILTER}. */
  private static Applicable optionFilter(Applicable f) {
    return new ApplicableImpl("Option.filter$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        if ((Boolean) f.apply(env, arg)) {
          return optionSome(arg);
        } else {
          return OPTION_NONE;
        }
      }
    };
  }

  /** @see BuiltIn#OPTION_JOIN */
  private static final Applicable OPTION_JOIN =
      new ApplicableImpl(BuiltIn.OPTION_JOIN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List opt = (List) arg;
          return opt.size() == 2
              ? opt.get(1) // SOME(SOME(v)) -> SOME(v), SOME(NONE) -> NONE
              : opt; // NONE -> NONE
        }
      };

  /** @see BuiltIn#OPTION_MAP */
  private static final Applicable OPTION_MAP =
      new ApplicableImpl(BuiltIn.OPTION_MAP) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return optionMap((Applicable) arg);
        }
      };

  /** Implements {@link #OPTION_MAP}. */
  private static Applicable optionMap(Applicable f) {
    return new ApplicableImpl(BuiltIn.OPTION_MAP) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List a = (List) arg;
        if (a.size() == 2) { // SOME v
          return optionSome(f.apply(env, a.get(1))); // SOME (f v)
        }
        return a; // NONE
      }
    };
  }

  /**
   * Creates a value of {@code SOME v}.
   *
   * @see net.hydromatic.morel.compile.BuiltIn.Constructor#OPTION_SOME
   * @see #OPTION_NONE
   */
  private static List optionSome(Object o) {
    return ImmutableList.of(BuiltIn.Constructor.OPTION_SOME.constructor, o);
  }

  /** @see BuiltIn#OPTION_MAP_PARTIAL */
  private static final Applicable OPTION_MAP_PARTIAL =
      new ApplicableImpl(BuiltIn.OPTION_MAP_PARTIAL) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return optionMapPartial((Applicable) arg);
        }
      };

  /** Implements {@link #OPTION_MAP_PARTIAL}. */
  private static Applicable optionMapPartial(Applicable f) {
    return new ApplicableImpl("Option.mapPartial$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List a = (List) arg;
        if (a.size() == 2) { // SOME v
          return f.apply(env, a.get(1)); // f v
        }
        return a; // NONE
      }
    };
  }

  /** @see BuiltIn#OPTION_COMPOSE */
  private static final Applicable OPTION_COMPOSE =
      new ApplicableImpl(BuiltIn.OPTION_COMPOSE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final Applicable f = (Applicable) tuple.get(0);
          final Applicable g = (Applicable) tuple.get(1);
          return optionCompose(f, g);
        }
      };

  /** Implements {@link #OPTION_COMPOSE}. */
  private static Applicable optionCompose(Applicable f, Applicable g) {
    return new ApplicableImpl("Option.compose$f$g") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List ga = (List) g.apply(env, arg); // g (a)
        if (ga.size() == 2) { // SOME v
          return optionSome(f.apply(env, ga.get(1))); // SOME (f (v))
        }
        return ga; // NONE
      }
    };
  }

  /** @see BuiltIn#OPTION_COMPOSE_PARTIAL */
  private static final Applicable OPTION_COMPOSE_PARTIAL =
      new ApplicableImpl(BuiltIn.OPTION_COMPOSE_PARTIAL) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final Applicable f = (Applicable) tuple.get(0);
          final Applicable g = (Applicable) tuple.get(1);
          return optionComposePartial(f, g);
        }
      };

  /** Implements {@link #OPTION_COMPOSE_PARTIAL}. */
  private static Applicable optionComposePartial(Applicable f, Applicable g) {
    return new ApplicableImpl("Option.composePartial$f$g") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        final List ga = (List) g.apply(env, arg); // g (a)
        if (ga.size() == 2) { // SOME v
          return f.apply(env, ga.get(1)); // f (v)
        }
        return ga; // NONE
      }
    };
  }

  /** @see BuiltIn#REAL_ABS */
  private static final Applicable REAL_ABS =
      new ApplicableImpl(BuiltIn.REAL_ABS) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Math.abs((float) arg);
        }
      };

  /** @see BuiltIn#REAL_CEIL */
  private static final Applicable REAL_CEIL =
      new ApplicableImpl(BuiltIn.REAL_CEIL) {
        @Override
        public Integer apply(EvalEnv env, Object arg) {
          float f = (float) arg;
          if (f >= 0) {
            return Math.round(f);
          } else {
            return -Math.round(-f);
          }
        }
      };

  /** @see BuiltIn#REAL_CHECK_FLOAT */
  private static final Applicable REAL_CHECK_FLOAT =
      new RealCheckFloat(Pos.ZERO);

  /** Implements {@link BuiltIn#REAL_CHECK_FLOAT}. */
  private static class RealCheckFloat extends PositionedApplicableImpl {
    RealCheckFloat(Pos pos) {
      super(BuiltIn.REAL_CHECK_FLOAT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealCheckFloat(pos);
    }

    @Override
    public Float apply(EvalEnv env, Object arg) {
      final Float f = (Float) arg;
      if (Float.isFinite(f)) {
        return f;
      }
      if (Float.isNaN(f)) {
        throw new MorelRuntimeException(BuiltInExn.DIV, pos);
      } else {
        throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
      }
    }
  }

  /** @see BuiltIn#REAL_COMPARE */
  private static final Applicable REAL_COMPARE = new RealCompare(Pos.ZERO);

  /** Implements {@link BuiltIn#REAL_COMPARE}. */
  private static class RealCompare extends Applicable2<List, Float, Float>
      implements Positioned {
    RealCompare(Pos pos) {
      super(BuiltIn.REAL_COMPARE, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealCompare(pos);
    }

    @Override
    public List apply(Float f0, Float f1) {
      if (Float.isNaN(f0) || Float.isNaN(f1)) {
        throw new MorelRuntimeException(BuiltInExn.UNORDERED, pos);
      }
      if (f0 < f1) {
        return ORDER_LESS;
      }
      if (f0 > f1) {
        return ORDER_GREATER;
      }
      // In particular, compare (~0.0, 0) returns ORDER_EQUAL
      return ORDER_EQUAL;
    }
  }

  /** @see BuiltIn#REAL_COPY_SIGN */
  private static final Applicable REAL_COPY_SIGN =
      new Applicable2<Float, Float, Float>(BuiltIn.REAL_COPY_SIGN) {
        @Override
        public Float apply(Float f0, Float f1) {
          if (Float.isNaN(f1)) {
            // Emulate SMLNJ/Mlton behavior that nan is negative,
            // ~nan is positive.
            f1 = isNegative(f1) ? -1.0f : 1.0f;
          }
          return Math.copySign(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_FLOOR */
  private static final Applicable REAL_FLOOR =
      new ApplicableImpl(BuiltIn.REAL_FLOOR) {
        @Override
        public Integer apply(EvalEnv env, Object arg) {
          float f = (float) arg;
          if (f >= 0) {
            return -Math.round(-f);
          } else {
            return Math.round(f);
          }
        }
      };

  /** @see BuiltIn#REAL_FROM_INT */
  private static final Applicable REAL_FROM_INT =
      new ApplicableImpl(BuiltIn.REAL_FROM_INT) {
        @Override
        public Float apply(EvalEnv env, Object arg) {
          return (float) ((Integer) arg);
        }
      };

  /** @see BuiltIn#REAL_FROM_MAN_EXP */
  private static final Applicable REAL_FROM_MAN_EXP =
      new Applicable2<Float, Integer, Float>(BuiltIn.REAL_FROM_MAN_EXP) {
        @Override
        public Float apply(Integer exp, Float mantissa) {
          if (!Float.isFinite(mantissa)) {
            return mantissa;
          }
          if (exp >= Float.MAX_EXPONENT) {
            final int exp2 = (exp - Float.MIN_EXPONENT) & 0xFF;
            final int bits = Float.floatToRawIntBits(mantissa);
            final int bits2 = (bits & ~(0xFF << 23)) | (exp2 << 23);
            return Float.intBitsToFloat(bits2);
          }
          final int exp2 = (exp - Float.MIN_EXPONENT + 1) & 0xFF;
          final float exp3 = Float.intBitsToFloat(exp2 << 23); // 2 ^ exp
          return mantissa * exp3;
        }
      };

  /**
   * Pattern for floating point numbers (after '~' has been converted to '-').
   * ".", ".e", ".e-", ".e5", "e7" are invalid; "2.", ".5", "2.e5", "2.e" are
   * valid.
   */
  static final Pattern FLOAT_PATTERN =
      Pattern.compile("^ *-?([0-9]*\\.)?[0-9]+([Ee]-?[0-9]+)?");

  /** @see BuiltIn#REAL_FROM_STRING */
  private static final Applicable REAL_FROM_STRING =
      new ApplicableImpl(BuiltIn.REAL_FROM_STRING) {
        @Override
        public List apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          final String s2 = s.replace('~', '-');
          final Matcher matcher = FLOAT_PATTERN.matcher(s2);
          if (!matcher.find(0)) {
            return OPTION_NONE;
          }
          final String s3 = s2.substring(0, matcher.end());
          try {
            final float f = Float.parseFloat(s3);
            return optionSome(f);
          } catch (NumberFormatException e) {
            // We should not have reached this point. The pattern
            // should not have matched the input.
            throw new AssertionError(e);
          }
        }
      };

  /** @see BuiltIn#REAL_IS_FINITE */
  private static final Applicable REAL_IS_FINITE =
      new ApplicableImpl(BuiltIn.REAL_IS_FINITE) {
        @Override
        public Boolean apply(EvalEnv env, Object arg) {
          return Float.isFinite((Float) arg);
        }
      };

  /** @see BuiltIn#REAL_IS_NAN */
  private static final Applicable REAL_IS_NAN =
      new ApplicableImpl(BuiltIn.REAL_IS_NAN) {
        @Override
        public Boolean apply(EvalEnv env, Object arg) {
          return Float.isNaN((Float) arg);
        }
      };

  /** @see BuiltIn#REAL_IS_NORMAL */
  private static final Applicable REAL_IS_NORMAL =
      new ApplicableImpl(BuiltIn.REAL_IS_NORMAL) {
        @Override
        public Boolean apply(EvalEnv env, Object arg) {
          final Float f = (Float) arg;
          return Float.isFinite(f)
              && (f >= Float.MIN_NORMAL || f <= -Float.MIN_NORMAL);
        }
      };

  /** @see BuiltIn#REAL_NEG_INF */
  private static final float REAL_NEG_INF = Float.NEGATIVE_INFINITY;

  /** @see BuiltIn#REAL_POS_INF */
  private static final float REAL_POS_INF = Float.POSITIVE_INFINITY;

  /** @see BuiltIn#REAL_RADIX */
  private static final int REAL_RADIX = 2;

  /** @see BuiltIn#REAL_PRECISION */
  // value is from jdk.internal.math.FloatConsts#SIGNIFICAND_WIDTH
  // (32 bit IEEE floating point is 1 sign bit, 8 bit exponent,
  // 23 bit mantissa)
  private static final int REAL_PRECISION = 24;

  /** @see BuiltIn#REAL_MIN */
  private static final Applicable REAL_MIN =
      new Applicable2<Float, Float, Float>(BuiltIn.REAL_MIN) {
        @Override
        public Float apply(Float f0, Float f1) {
          return Float.isNaN(f0) ? f1 : Float.isNaN(f1) ? f0 : Math.min(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_MAX */
  private static final Applicable REAL_MAX =
      new Applicable2<Float, Float, Float>(BuiltIn.REAL_MAX) {
        @Override
        public Float apply(Float f0, Float f1) {
          return Float.isNaN(f0) ? f1 : Float.isNaN(f1) ? f0 : Math.max(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_MAX_FINITE */
  private static final float REAL_MAX_FINITE = Float.MAX_VALUE;

  /** @see BuiltIn#REAL_MIN_POS */
  private static final float REAL_MIN_POS = Float.MIN_VALUE;

  /** @see BuiltIn#REAL_MIN_NORMAL_POS */
  private static final float REAL_MIN_NORMAL_POS = Float.MIN_NORMAL;

  /** @see BuiltIn#REAL_REAL_MOD */
  private static final Applicable REAL_REAL_MOD =
      new ApplicableImpl(BuiltIn.REAL_REAL_MOD) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final float f = (Float) arg;
          if (Float.isInfinite(f)) {
            // realMod posInf  => 0.0
            // realMod negInf  => ~0.0
            return f > 0f ? 0f : -0f;
          }
          return f % 1;
        }
      };

  /** @see BuiltIn#REAL_REAL_CEIL */
  private static final Applicable REAL_REAL_CEIL =
      new ApplicableImpl(BuiltIn.REAL_REAL_CEIL) {
        @Override
        public Float apply(EvalEnv env, Object arg) {
          return (float) Math.ceil((float) arg);
        }
      };

  /** @see BuiltIn#REAL_REAL_FLOOR */
  private static final Applicable REAL_REAL_FLOOR =
      new ApplicableImpl(BuiltIn.REAL_REAL_FLOOR) {
        @Override
        public Float apply(EvalEnv env, Object arg) {
          return (float) Math.floor((float) arg);
        }
      };

  /** @see BuiltIn#REAL_REAL_ROUND */
  private static final Applicable REAL_REAL_ROUND =
      new ApplicableImpl(BuiltIn.REAL_REAL_ROUND) {
        @Override
        public Float apply(EvalEnv env, Object arg) {
          return (float) Math.rint((float) arg);
        }
      };

  /** @see BuiltIn#REAL_REAL_TRUNC */
  private static final Applicable REAL_REAL_TRUNC =
      new ApplicableImpl(BuiltIn.REAL_REAL_TRUNC) {
        @Override
        public Float apply(EvalEnv env, Object arg) {
          final float f = (float) arg;
          final float frac = f % 1;
          return f - frac;
        }
      };

  /** @see BuiltIn#REAL_REM */
  private static final Applicable REAL_REM =
      new Applicable2<Float, Float, Float>(BuiltIn.REAL_REM) {
        @Override
        public Float apply(Float x, Float y) {
          return x % y;
        }
      };

  /** @see BuiltIn#REAL_ROUND */
  private static final Applicable REAL_ROUND =
      new ApplicableImpl(BuiltIn.REAL_ROUND) {
        @Override
        public Integer apply(EvalEnv env, Object arg) {
          return Math.round((float) arg);
        }
      };

  /** @see BuiltIn#REAL_SAME_SIGN */
  private static final Applicable REAL_SAME_SIGN =
      new Applicable2<Boolean, Float, Float>(BuiltIn.REAL_SAME_SIGN) {
        @Override
        public Boolean apply(Float x, Float y) {
          return isNegative(x) == isNegative(y);
        }
      };

  /**
   * Returns whether a {@code float} is negative. This is the same as the
   * specification of {@code Real.signBit}.
   */
  @VisibleForTesting
  public static boolean isNegative(float f) {
    final boolean negative =
        (Float.floatToRawIntBits(f) & 0x8000_0000) == 0x8000_0000;
    if (Float.isNaN(f)) {
      // Standard ML/NJ and Mlton treat nan as negative,
      // and ~nan as positive. Let's do the same.
      return !negative;
    }
    return negative;
  }

  /** @see BuiltIn#REAL_SIGN */
  private static final Applicable REAL_SIGN = new RealSign(Pos.ZERO);

  /** Implements {@link BuiltIn#REAL_COMPARE}. */
  private static class RealSign extends PositionedApplicableImpl {
    RealSign(Pos pos) {
      super(BuiltIn.REAL_SIGN, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealSign(pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final float f = (Float) arg;
      if (Float.isNaN(f)) {
        throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
      }
      return f == 0f
          ? 0 // positive or negative zero
          : (f > 0f)
              ? 1 // positive number or positive infinity
              : -1; // negative number or negative infinity
    }
  }

  /** @see BuiltIn#REAL_SIGN_BIT */
  private static final Applicable REAL_SIGN_BIT =
      new ApplicableImpl(BuiltIn.REAL_SIGN_BIT) {
        @Override
        public Boolean apply(EvalEnv env, Object arg) {
          return isNegative((Float) arg);
        }
      };

  /** @see BuiltIn#REAL_SPLIT */
  private static final Applicable REAL_SPLIT =
      new ApplicableImpl(BuiltIn.REAL_SPLIT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final float f = (Float) arg;
          final float frac;
          final float whole;
          if (Float.isInfinite(f)) {
            // realMod posInf  => 0.0
            // realMod negInf  => ~0.0
            frac = f > 0f ? 0f : -0f;
            whole = f;
          } else {
            frac = f % 1;
            whole = f - frac;
          }
          return ImmutableList.of(frac, whole);
        }
      };

  /** @see BuiltIn#REAL_TO_MAN_EXP */
  private static final Applicable REAL_TO_MAN_EXP =
      new ApplicableImpl(BuiltIn.REAL_TO_MAN_EXP) {
        @Override
        public List apply(EvalEnv env, Object arg) {
          // In IEEE 32 bit floating point,
          // bit 31 is the sign (1 bit);
          // bits 30 - 23 are the exponent (8 bits);
          // bits 22 - 0 are the mantissa (23 bits).
          float f = (Float) arg;
          final int bits = Float.floatToRawIntBits(f);
          final int exp = (bits >> 23) & 0xFF;
          final float mantissa;
          if (exp == 0) {
            // Exponent = 0 indicates that f is a very small number (0 < abs(f)
            // <= MIN_NORMAL). The mantissa has leading zeros, so we have to use
            // a different algorithm to get shift it into range.
            mantissa = f / Float.MIN_NORMAL;
          } else if (Float.isFinite(f)) {
            // Set the exponent to 126 (which is the exponent for 1.0). First
            // remove all set bits, then OR in the value 126.
            final int bits2 = (bits & ~(0xFF << 23)) | (0x7E << 23);
            mantissa = Float.intBitsToFloat(bits2);
          } else {
            mantissa = f;
          }
          return ImmutableList.of(exp + Float.MIN_EXPONENT, mantissa);
        }
      };

  /** @see BuiltIn#REAL_TO_STRING */
  private static final Applicable REAL_TO_STRING =
      new ApplicableImpl(BuiltIn.REAL_TO_STRING) {
        @Override
        public String apply(EvalEnv env, Object arg) {
          // Java's formatting is reasonably close to ML's formatting,
          // if we replace minus signs.
          Float f = (Float) arg;
          return floatToString(f);
        }
      };

  /** @see BuiltIn#REAL_TRUNC */
  private static final Applicable REAL_TRUNC =
      new ApplicableImpl(BuiltIn.REAL_TRUNC) {
        @Override
        public Integer apply(EvalEnv env, Object arg) {
          float f = (float) arg;
          return (int) f;
        }
      };

  /** @see BuiltIn#REAL_UNORDERED */
  private static final Applicable REAL_UNORDERED =
      new Applicable2<Boolean, Float, Float>(BuiltIn.REAL_UNORDERED) {
        @Override
        public Boolean apply(Float f0, Float f1) {
          return Float.isNaN(f0) || Float.isNaN(f1);
        }
      };

  /** @see BuiltIn#RELATIONAL_COMPARE */
  private static final Applicable RELATIONAL_COMPARE = Comparer.INITIAL;

  /** @see BuiltIn#RELATIONAL_COUNT */
  private static final Applicable RELATIONAL_COUNT =
      length(BuiltIn.RELATIONAL_COUNT);

  /** @see BuiltIn#RELATIONAL_NON_EMPTY */
  private static final Applicable RELATIONAL_NON_EMPTY =
      nonEmpty(BuiltIn.RELATIONAL_NON_EMPTY);

  private static ApplicableImpl nonEmpty(final BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        return !((List) arg).isEmpty();
      }
    };
  }

  /** @see BuiltIn#RELATIONAL_EMPTY */
  private static final Applicable RELATIONAL_EMPTY =
      empty(BuiltIn.RELATIONAL_EMPTY);

  private static ApplicableImpl empty(BuiltIn builtIn) {
    return new ApplicableImpl(builtIn) {
      @Override
      public Boolean apply(EvalEnv env, Object arg) {
        return ((List) arg).isEmpty();
      }
    };
  }

  /** @see BuiltIn#RELATIONAL_ITERATE */
  private static final Applicable RELATIONAL_ITERATE =
      new ApplicableImpl(BuiltIn.RELATIONAL_ITERATE) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final List initialList = (List) arg;
          return new ApplicableImpl("Relational.iterate$list") {
            @Override
            public Object apply(EvalEnv env, Object argValue) {
              final Applicable update = (Applicable) argValue;
              List list = initialList;
              List newList = list;
              for (; ; ) {
                List nextList =
                    (List) update.apply(env, FlatLists.of(list, newList));
                if (nextList.isEmpty()) {
                  return list;
                }
                // REVIEW:
                // 1. should we eliminate duplicates when computing "oldList
                //   union newList"?
                // 2. should we subtract oldList before checking whether newList
                //    is empty?
                // 3. add an "iterateDistinct" variant?
                list =
                    ImmutableList.builder()
                        .addAll(list)
                        .addAll(nextList)
                        .build();
                newList = nextList;
              }
            }
          };
        }
      };

  /** @see BuiltIn#RELATIONAL_ONLY */
  private static final Applicable RELATIONAL_ONLY =
      new RelationalOnly(Pos.ZERO);

  /** Implements {@link BuiltIn#RELATIONAL_ONLY}. */
  private static class RelationalOnly extends PositionedApplicableImpl {
    RelationalOnly(Pos pos) {
      super(BuiltIn.RELATIONAL_ONLY, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalOnly(pos);
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      final List list = (List) arg;
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      if (list.size() > 1) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      return list.get(0);
    }
  }

  /** Implements {@link #RELATIONAL_SUM} for type {@code int list}. */
  private static final Applicable Z_SUM_INT =
      new ApplicableImpl("Relational.sum$int") {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("unchecked")
          final List<? extends Number> list = (List) arg;
          int sum = 0;
          for (Number o : list) {
            sum += o.intValue();
          }
          return sum;
        }
      };

  /** Implements {@link #RELATIONAL_SUM} for type {@code real list}. */
  private static final Applicable Z_SUM_REAL =
      new ApplicableImpl("Relational.sum$real") {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("unchecked")
          final List<? extends Number> list = (List) arg;
          float sum = 0;
          for (Number o : list) {
            sum += o.floatValue();
          }
          return sum;
        }
      };

  /** @see BuiltIn#RELATIONAL_SUM */
  private static final Macro RELATIONAL_SUM =
      (typeSystem, env, argType) -> {
        if (argType.isCollection()) {
          final Type resultType = argType.arg(0);
          switch ((PrimitiveType) resultType) {
            case INT:
              return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_INT);
            case REAL:
              return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_REAL);
          }
        }
        throw new AssertionError("bad type " + argType);
      };

  /** @see BuiltIn#RELATIONAL_MIN */
  private static final Applicable RELATIONAL_MIN =
      new ApplicableImpl(BuiltIn.RELATIONAL_MIN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Ordering.natural().min((List) arg);
        }
      };

  /** @see BuiltIn#RELATIONAL_MAX */
  private static final Applicable RELATIONAL_MAX =
      new ApplicableImpl(BuiltIn.RELATIONAL_MAX) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return Ordering.natural().max((List) arg);
        }
      };

  /** @see BuiltIn#SYS_CLEAR_ENV */
  private static final Applicable SYS_CLEAR_ENV =
      new ApplicableImpl(BuiltIn.MATH_LOG10) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          session.clearEnv();
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#SYS_ENV */
  private static Core.Exp sysEnv(
      TypeSystem typeSystem, Environment env, Type argType) {
    final TupleType stringPairType =
        typeSystem.tupleType(PrimitiveType.STRING, PrimitiveType.STRING);
    final List<Core.Tuple> args =
        env.getValueMap(true).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry ->
                    core.tuple(
                        stringPairType,
                        core.stringLiteral(entry.getKey()),
                        core.stringLiteral(entry.getValue().id.type.moniker())))
            .collect(Collectors.toList());
    return core.apply(
        Pos.ZERO,
        typeSystem.listType(argType),
        core.functionLiteral(typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeSystem, null, args));
  }

  /** @see BuiltIn#SYS_PLAN */
  private static final Applicable SYS_PLAN =
      new ApplicableImpl(BuiltIn.SYS_PLAN) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          return Codes.describe(session.code);
        }
      };

  /** @see BuiltIn#SYS_SET */
  private static final Applicable SYS_SET =
      new ApplicableImpl(BuiltIn.SYS_SET) {
        @Override
        public Unit apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final List list = (List) arg;
          final String propName = (String) list.get(0);
          final Object value = list.get(1);
          Prop.lookup(propName).setLenient(session.map, value);
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#SYS_SHOW */
  private static final Applicable SYS_SHOW =
      new ApplicableImpl(BuiltIn.SYS_SHOW) {
        @Override
        public List apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final String propName = (String) arg;
          final Object value = Prop.lookup(propName).get(session.map);
          return value == null ? OPTION_NONE : optionSome(value.toString());
        }
      };

  /** @see BuiltIn#SYS_SHOW_ALL */
  private static final Applicable SYS_SHOW_ALL =
      new ApplicableImpl(BuiltIn.SYS_SHOW_ALL) {
        @Override
        public List apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final ImmutableList.Builder<List<List>> list =
              ImmutableList.builder();
          for (Prop prop : Prop.BY_CAMEL_NAME) {
            final Object value = prop.get(session.map);
            List option =
                value == null ? OPTION_NONE : optionSome(value.toString());
            list.add((List) ImmutableList.of(prop.camelName, option));
          }
          return list.build();
        }
      };

  /** @see BuiltIn#SYS_UNSET */
  private static final Applicable SYS_UNSET =
      new ApplicableImpl(BuiltIn.SYS_UNSET) {
        @Override
        public Unit apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final String propName = (String) arg;
          final Prop prop = Prop.lookup(propName);
          @SuppressWarnings("unused")
          final Object value = prop.remove(session.map);
          return Unit.INSTANCE;
        }
      };

  /**
   * Converts the result of {@link Comparable#compareTo(Object)} to an {@code
   * Order} value.
   */
  static List order(int c) {
    if (c < 0) {
      return ORDER_LESS;
    }
    if (c > 0) {
      return ORDER_GREATER;
    }
    return ORDER_EQUAL;
  }

  /** @see BuiltIn.Constructor#ORDER_LESS */
  private static final List ORDER_LESS =
      ImmutableList.of(BuiltIn.Constructor.ORDER_LESS.constructor);

  /** @see BuiltIn.Constructor#ORDER_EQUAL */
  private static final List ORDER_EQUAL =
      ImmutableList.of(BuiltIn.Constructor.ORDER_EQUAL.constructor);

  /** @see BuiltIn.Constructor#ORDER_GREATER */
  private static final List ORDER_GREATER =
      ImmutableList.of(BuiltIn.Constructor.ORDER_GREATER.constructor);

  /** @see BuiltIn#VECTOR_MAX_LEN */
  private static final int VECTOR_MAX_LEN = (1 << 24) - 1;

  /** @see BuiltIn#VECTOR_FROM_LIST */
  private static final Applicable VECTOR_FROM_LIST =
      identity(BuiltIn.VECTOR_FROM_LIST);

  /** @see BuiltIn#VECTOR_TABULATE */
  private static final Applicable VECTOR_TABULATE =
      new ListTabulate(BuiltIn.VECTOR_TABULATE, Pos.ZERO);

  /** @see BuiltIn#VECTOR_LENGTH */
  private static final Applicable VECTOR_LENGTH = length(BuiltIn.VECTOR_LENGTH);

  /** @see BuiltIn#VECTOR_SUB */
  private static final Applicable VECTOR_SUB =
      new ListNth(BuiltIn.VECTOR_SUB, Pos.ZERO);

  /** @see BuiltIn#VECTOR_UPDATE */
  private static final Applicable VECTOR_UPDATE = new VectorUpdate(Pos.ZERO);

  /** Implements {@link BuiltIn#VECTOR_UPDATE}. */
  private static class VectorUpdate
      extends Applicable3<List, List, Integer, Object> implements Positioned {
    VectorUpdate(Pos pos) {
      super(BuiltIn.VECTOR_UPDATE, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new VectorUpdate(pos);
    }

    @Override
    public List apply(List vec, Integer i, Object x) {
      if (i < 0 || i >= vec.size()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      final Object[] elements = vec.toArray();
      elements[i] = x;
      return ImmutableList.copyOf(elements);
    }
  }

  /** @see BuiltIn#VECTOR_CONCAT */
  private static final Applicable VECTOR_CONCAT =
      new ApplicableImpl(BuiltIn.VECTOR_CONCAT) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("unchecked")
          final List<List<Object>> lists = (List<List<Object>>) arg;
          final ImmutableList.Builder<Object> b = ImmutableList.builder();
          for (List<Object> list : lists) {
            b.addAll(list);
          }
          return b.build();
        }
      };

  /** @see BuiltIn#VECTOR_APPI */
  private static final Applicable VECTOR_APPI =
      new ApplicableImpl(BuiltIn.VECTOR_APPI) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return vectorAppi((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_APPI}. */
  private static Applicable vectorAppi(Applicable f) {
    return new ApplicableImpl("Vector.appi$f") {
      @Override
      public Unit apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> vec = (List<Object>) arg;
        forEachIndexed(vec, (e, i) -> f.apply(env, FlatLists.of(i, e)));
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#VECTOR_APP */
  private static final Applicable VECTOR_APP =
      new ApplicableImpl(BuiltIn.VECTOR_APP) {
        @Override
        public Applicable apply(EvalEnv env, Object arg) {
          return vectorApp((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_APP}. */
  private static Applicable vectorApp(Applicable f) {
    return new ApplicableImpl("Vector.app$f") {
      @Override
      public Unit apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> vec = (List<Object>) arg;
        vec.forEach(e -> f.apply(env, e));
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#VECTOR_MAPI */
  private static final Applicable VECTOR_MAPI =
      new ApplicableImpl(BuiltIn.VECTOR_MAPI) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return listMapi((Applicable) arg);
        }
      };

  /** Implements {@link #LIST_MAPI}, {@link #VECTOR_MAPI}. */
  private static Applicable listMapi(Applicable f) {
    return new ApplicableImpl("Vector.map$f") {
      @Override
      public List apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> vec = (List<Object>) arg;
        ImmutableList.Builder<Object> b = ImmutableList.builder();
        forEachIndexed(vec, (e, i) -> b.add(f.apply(env, FlatLists.of(i, e))));
        return b.build();
      }
    };
  }

  /** @see BuiltIn#VECTOR_MAP */
  private static final Applicable VECTOR_MAP =
      new ApplicableImpl(BuiltIn.VECTOR_MAP) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return vectorMap((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_MAP}. */
  private static Applicable vectorMap(Applicable f) {
    return new ApplicableImpl("Vector.map$f") {
      @Override
      public List apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> vec = (List<Object>) arg;
        ImmutableList.Builder<Object> b = ImmutableList.builder();
        vec.forEach(e -> b.add(f.apply(env, e)));
        return b.build();
      }
    };
  }

  /** @see BuiltIn#VECTOR_FOLDLI */
  private static final Applicable VECTOR_FOLDLI =
      new ApplicableImpl(BuiltIn.VECTOR_FOLDLI) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldli$f") {
            @Override
            public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldli$f$init") {
                @Override
                public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked")
                  final List<Object> vec = (List<Object>) arg3;
                  Object acc = init;
                  for (int i = 0, n = vec.size(); i < n; i++) {
                    acc = f.apply(env3, FlatLists.of(i, vec.get(i), acc));
                  }
                  return acc;
                }
              };
            }
          };
        }
      };

  /** @see BuiltIn#VECTOR_FOLDRI */
  private static final Applicable VECTOR_FOLDRI =
      new ApplicableImpl(BuiltIn.VECTOR_FOLDRI) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldri$f") {
            @Override
            public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldri$f$init") {
                @Override
                public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked")
                  final List<Object> vec = (List<Object>) arg3;
                  Object acc = init;
                  for (int i = vec.size() - 1; i >= 0; i--) {
                    acc = f.apply(env3, FlatLists.of(i, vec.get(i), acc));
                  }
                  return acc;
                }
              };
            }
          };
        }
      };

  /** @see BuiltIn#VECTOR_FOLDL */
  private static final Applicable VECTOR_FOLDL =
      new ApplicableImpl(BuiltIn.VECTOR_FOLDL) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldl$f") {
            @Override
            public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldl$f$init") {
                @Override
                public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked")
                  final List<Object> vec = (List<Object>) arg3;
                  Object acc = init;
                  for (Object o : vec) {
                    acc = f.apply(env3, FlatLists.of(o, acc));
                  }
                  return acc;
                }
              };
            }
          };
        }
      };

  /** @see BuiltIn#VECTOR_FOLDR */
  private static final Applicable VECTOR_FOLDR =
      new ApplicableImpl(BuiltIn.VECTOR_FOLDR) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldlr$f") {
            @Override
            public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldr$f$init") {
                @Override
                public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked")
                  final List<Object> vec = (List<Object>) arg3;
                  Object acc = init;
                  for (int i = vec.size() - 1; i >= 0; i--) {
                    acc = f.apply(env3, FlatLists.of(vec.get(i), acc));
                  }
                  return acc;
                }
              };
            }
          };
        }
      };

  /** @see BuiltIn#VECTOR_FINDI */
  private static final Applicable VECTOR_FINDI =
      new ApplicableImpl(BuiltIn.VECTOR_FINDI) {
        @Override
        public Object apply(EvalEnv env, Object arg) {
          return vectorFindi((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_FINDI}. */
  private static Applicable vectorFindi(Applicable f) {
    return new ApplicableImpl("Vector.findi$f") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> vec = (List<Object>) arg;
        for (int i = 0, n = vec.size(); i < n; i++) {
          final List<Object> tuple = FlatLists.of(i, vec.get(i));
          if ((Boolean) f.apply(env, tuple)) {
            return optionSome(tuple);
          }
        }
        return OPTION_NONE;
      }
    };
  }

  /** @see BuiltIn#VECTOR_FIND */
  private static final Applicable VECTOR_FIND = find(BuiltIn.VECTOR_FIND);

  /** @see BuiltIn#VECTOR_EXISTS */
  private static final Applicable VECTOR_EXISTS = exists(BuiltIn.VECTOR_EXISTS);

  /** @see BuiltIn#VECTOR_ALL */
  private static final Applicable VECTOR_ALL = all(BuiltIn.VECTOR_ALL);

  /** @see BuiltIn#VECTOR_COLLATE */
  private static final Applicable VECTOR_COLLATE =
      collate(BuiltIn.VECTOR_COLLATE);

  /** @see BuiltIn#BAG_NULL */
  private static final Applicable BAG_NULL = empty(BuiltIn.BAG_NULL);

  /** @see BuiltIn#BAG_LENGTH */
  private static final Applicable BAG_LENGTH = length(BuiltIn.BAG_LENGTH);

  /** @see BuiltIn#BAG_AT */
  private static final Applicable BAG_AT = union(BuiltIn.BAG_AT);

  /** @see BuiltIn#BAG_HD */
  private static final Applicable BAG_HD =
      new ListHd(BuiltIn.LIST_HD, Pos.ZERO);

  /** @see BuiltIn#BAG_TL */
  private static final Applicable BAG_TL =
      new ListTl(BuiltIn.LIST_TL, Pos.ZERO);

  /** @see BuiltIn#BAG_GET_ITEM */
  private static final Applicable BAG_GET_ITEM =
      listGetItem(BuiltIn.BAG_GET_ITEM);

  /** @see BuiltIn#BAG_NTH */
  private static final Applicable BAG_NTH =
      new ListNth(BuiltIn.BAG_NTH, Pos.ZERO);

  /** @see BuiltIn#BAG_TAKE */
  private static final Applicable BAG_TAKE =
      new ListTake(BuiltIn.BAG_TAKE, Pos.ZERO);

  /** @see BuiltIn#BAG_DROP */
  private static final Applicable BAG_DROP = listDrop(BuiltIn.BAG_DROP);

  /** @see BuiltIn#BAG_CONCAT */
  private static final Applicable BAG_CONCAT = listConcat(BuiltIn.BAG_CONCAT);

  /** @see BuiltIn#BAG_APP */
  private static final Applicable BAG_APP = listApp0(BuiltIn.BAG_APP);

  /** @see BuiltIn#BAG_MAP */
  private static final Applicable BAG_MAP = listMap0(BuiltIn.BAG_MAP);

  /** @see BuiltIn#BAG_MAP_PARTIAL */
  private static final Applicable BAG_MAP_PARTIAL =
      listMapPartial0(BuiltIn.BAG_MAP_PARTIAL);

  /** @see BuiltIn#BAG_FIND */
  private static final Applicable BAG_FIND = find(BuiltIn.BAG_FIND);

  /** @see BuiltIn#BAG_FILTER */
  private static final Applicable BAG_FILTER = listFilter0(BuiltIn.BAG_FILTER);

  /** @see BuiltIn#BAG_PARTITION */
  private static final Applicable BAG_PARTITION =
      listPartition0(BuiltIn.BAG_PARTITION);

  /** @see BuiltIn#BAG_FOLD */
  private static final Applicable BAG_FOLD =
      // Order does not matter, but we call with left = true because foldl is
      // more efficient than foldr.
      listFold0(BuiltIn.BAG_FOLD, true);

  /** @see BuiltIn#BAG_EXISTS */
  private static final Applicable BAG_EXISTS = exists(BuiltIn.BAG_EXISTS);

  /** @see BuiltIn#BAG_ALL */
  private static final Applicable BAG_ALL = all(BuiltIn.BAG_ALL);

  /** @see BuiltIn#BAG_FROM_LIST */
  private static final Applicable BAG_FROM_LIST =
      identity(BuiltIn.BAG_FROM_LIST);

  /** @see BuiltIn#BAG_TO_LIST */
  private static final Applicable BAG_TO_LIST = identity(BuiltIn.BAG_TO_LIST);

  /** @see BuiltIn#BAG_TABULATE */
  private static final Applicable BAG_TABULATE =
      new ListTabulate(BuiltIn.BAG_TABULATE, Pos.ZERO);

  /** @see BuiltIn#Z_EXTENT */
  private static final Applicable Z_EXTENT =
      new ApplicableImpl(BuiltIn.Z_EXTENT) {
        @Override
        public List apply(EvalEnv env, Object arg) {
          final RangeExtent rangeExtent = (RangeExtent) arg;
          if (rangeExtent.iterable == null) {
            throw new AssertionError("infinite: " + rangeExtent);
          }
          return ImmutableList.copyOf(rangeExtent.iterable);
        }
      };

  /** @see BuiltIn#Z_LIST */
  private static final Applicable Z_LIST = identity(BuiltIn.Z_LIST);

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
          if (key.structure == null) {
            valueMap.put(key.mlName, value);
          }
          if (key.alias != null) {
            valueMap.put(key.alias, value);
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

  /** Creates a compilation environment. */
  public static Environment env(
      TypeSystem typeSystem, Environment environment) {
    final Environment[] hEnv = {environment};
    BUILT_IN_VALUES.forEach(
        (key, value) -> {
          final Type type = key.typeFunction.apply(typeSystem);
          if (key.structure == null) {
            final Core.IdPat idPat =
                core.idPat(type, key.mlName, typeSystem.nameGenerator::inc);
            hEnv[0] = hEnv[0].bind(idPat, value);
          }
          if (key.alias != null) {
            final Core.IdPat idPat =
                core.idPat(type, key.alias, typeSystem.nameGenerator::inc);
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
      @Nullable Code argumentCode) {
    return new ApplicableImpl("aggregate") {
      @Override
      public Object apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked")
        final List<Object> rows = (List<Object>) arg;
        final List<Object> argRows;
        if (argumentCode != null) {
          final MutableEvalEnv env2 = env.bindMutableArray(names);
          argRows = new ArrayList<>(rows.size());
          for (Object row : rows) {
            env2.set(row);
            argRows.add(argumentCode.eval(env2));
          }
        } else if (names.size() != 1) {
          // Reconcile the fact that we internally represent rows as arrays when
          // we're buffering for "group", lists at other times.
          argRows = transform(rows, row -> Arrays.asList((Object[]) row));
        } else {
          argRows = rows;
        }
        final Applicable aggregate = (Applicable) aggregateCode.eval(env);
        return aggregate.apply(env, argRows);
      }
    };
  }

  public static final ImmutableMap<BuiltIn, Object> BUILT_IN_VALUES =
      ImmutableMap.<BuiltIn, Object>builder()
          .put(BuiltIn.TRUE, true)
          .put(BuiltIn.FALSE, false)
          .put(BuiltIn.NOT, NOT)
          .put(BuiltIn.ABS, ABS)
          .put(BuiltIn.IGNORE, IGNORE)
          .put(BuiltIn.GENERAL_OP_O, GENERAL_OP_O) // lint:startSorted
          .put(BuiltIn.BAG_ALL, BAG_ALL)
          .put(BuiltIn.BAG_APP, BAG_APP)
          .put(BuiltIn.BAG_AT, BAG_AT)
          .put(BuiltIn.BAG_CONCAT, BAG_CONCAT)
          .put(BuiltIn.BAG_DROP, BAG_DROP)
          .put(BuiltIn.BAG_EXISTS, BAG_EXISTS)
          .put(BuiltIn.BAG_FILTER, BAG_FILTER)
          .put(BuiltIn.BAG_FIND, BAG_FIND)
          .put(BuiltIn.BAG_FOLD, BAG_FOLD)
          .put(BuiltIn.BAG_FROM_LIST, BAG_FROM_LIST)
          .put(BuiltIn.BAG_GET_ITEM, BAG_GET_ITEM)
          .put(BuiltIn.BAG_HD, BAG_HD)
          .put(BuiltIn.BAG_LENGTH, BAG_LENGTH)
          .put(BuiltIn.BAG_MAP_PARTIAL, BAG_MAP_PARTIAL)
          .put(BuiltIn.BAG_MAP, BAG_MAP)
          .put(BuiltIn.BAG_NIL, ImmutableList.of())
          .put(BuiltIn.BAG_NTH, BAG_NTH)
          .put(BuiltIn.BAG_NULL, BAG_NULL)
          .put(BuiltIn.BAG_OP_AT, BAG_AT) // op @ == Bag.at
          .put(BuiltIn.BAG_PARTITION, BAG_PARTITION)
          .put(BuiltIn.BAG_TABULATE, BAG_TABULATE)
          .put(BuiltIn.BAG_TAKE, BAG_TAKE)
          .put(BuiltIn.BAG_TL, BAG_TL)
          .put(BuiltIn.BAG_TO_LIST, BAG_TO_LIST)
          .put(BuiltIn.CHAR_CHR, CHAR_CHR)
          .put(BuiltIn.CHAR_COMPARE, CHAR_COMPARE)
          .put(BuiltIn.CHAR_CONTAINS, CHAR_CONTAINS)
          .put(BuiltIn.CHAR_FROM_CSTRING, CHAR_FROM_CSTRING)
          .put(BuiltIn.CHAR_FROM_STRING, CHAR_FROM_STRING)
          .put(BuiltIn.CHAR_IS_ALPHA_NUM, CHAR_IS_ALPHA_NUM)
          .put(BuiltIn.CHAR_IS_ALPHA, CHAR_IS_ALPHA)
          .put(BuiltIn.CHAR_IS_ASCII, CHAR_IS_ASCII)
          .put(BuiltIn.CHAR_IS_CNTRL, CHAR_IS_CNTRL)
          .put(BuiltIn.CHAR_IS_DIGIT, CHAR_IS_DIGIT)
          .put(BuiltIn.CHAR_IS_GRAPH, CHAR_IS_GRAPH)
          .put(BuiltIn.CHAR_IS_HEX_DIGIT, CHAR_IS_HEX_DIGIT)
          .put(BuiltIn.CHAR_IS_LOWER, CHAR_IS_LOWER)
          .put(BuiltIn.CHAR_IS_PRINT, CHAR_IS_PRINT)
          .put(BuiltIn.CHAR_IS_PUNCT, CHAR_IS_PUNCT)
          .put(BuiltIn.CHAR_IS_SPACE, CHAR_IS_SPACE)
          .put(BuiltIn.CHAR_IS_UPPER, CHAR_IS_UPPER)
          .put(BuiltIn.CHAR_MAX_CHAR, CHAR_MAX_CHAR)
          .put(BuiltIn.CHAR_MAX_ORD, CHAR_MAX_ORD)
          .put(BuiltIn.CHAR_MIN_CHAR, CHAR_MIN_CHAR)
          .put(BuiltIn.CHAR_NOT_CONTAINS, CHAR_NOT_CONTAINS)
          .put(BuiltIn.CHAR_OP_GE, CHAR_OP_GE)
          .put(BuiltIn.CHAR_OP_GT, CHAR_OP_GT)
          .put(BuiltIn.CHAR_OP_LE, CHAR_OP_LE)
          .put(BuiltIn.CHAR_OP_LT, CHAR_OP_LT)
          .put(BuiltIn.CHAR_ORD, CHAR_ORD)
          .put(BuiltIn.CHAR_PRED, CHAR_PRED)
          .put(BuiltIn.CHAR_SUCC, CHAR_SUCC)
          .put(BuiltIn.CHAR_TO_CSTRING, CHAR_TO_CSTRING)
          .put(BuiltIn.CHAR_TO_LOWER, CHAR_TO_LOWER)
          .put(BuiltIn.CHAR_TO_STRING, CHAR_TO_STRING)
          .put(BuiltIn.CHAR_TO_UPPER, CHAR_TO_UPPER)
          .put(BuiltIn.INT_ABS, INT_ABS)
          .put(BuiltIn.INT_COMPARE, INT_COMPARE)
          .put(BuiltIn.INT_DIV, INT_DIV)
          .put(BuiltIn.INT_FROM_INT, INT_FROM_INT)
          .put(BuiltIn.INT_FROM_LARGE, INT_FROM_LARGE)
          .put(BuiltIn.INT_FROM_STRING, INT_FROM_STRING)
          .put(BuiltIn.INT_MAX_INT, INT_MAX_INT)
          .put(BuiltIn.INT_MAX, INT_MAX)
          .put(BuiltIn.INT_MIN_INT, INT_MIN_INT)
          .put(BuiltIn.INT_MIN, INT_MIN)
          .put(BuiltIn.INT_MOD, INT_MOD)
          .put(BuiltIn.INT_PRECISION, INT_PRECISION)
          .put(BuiltIn.INT_QUOT, INT_QUOT)
          .put(BuiltIn.INT_REM, INT_REM)
          .put(BuiltIn.INT_SAME_SIGN, INT_SAME_SIGN)
          .put(BuiltIn.INT_SIGN, INT_SIGN)
          .put(BuiltIn.INT_TO_INT, INT_TO_INT)
          .put(BuiltIn.INT_TO_LARGE, INT_TO_LARGE)
          .put(BuiltIn.INT_TO_STRING, INT_TO_STRING)
          .put(BuiltIn.INTERACT_USE_SILENTLY, INTERACT_USE_SILENTLY)
          .put(BuiltIn.INTERACT_USE, INTERACT_USE)
          .put(BuiltIn.LIST_ALL, LIST_ALL)
          .put(BuiltIn.LIST_APP, LIST_APP)
          .put(BuiltIn.LIST_AT, LIST_AT)
          .put(BuiltIn.LIST_COLLATE, LIST_COLLATE)
          .put(BuiltIn.LIST_CONCAT, LIST_CONCAT)
          .put(BuiltIn.LIST_DROP, LIST_DROP)
          .put(BuiltIn.LIST_EXCEPT, LIST_EXCEPT)
          .put(BuiltIn.LIST_EXISTS, LIST_EXISTS)
          .put(BuiltIn.LIST_FILTER, LIST_FILTER)
          .put(BuiltIn.LIST_FIND, LIST_FIND)
          .put(BuiltIn.LIST_FOLDL, LIST_FOLDL)
          .put(BuiltIn.LIST_FOLDR, LIST_FOLDR)
          .put(BuiltIn.LIST_GET_ITEM, LIST_GET_ITEM)
          .put(BuiltIn.LIST_HD, LIST_HD)
          .put(BuiltIn.LIST_INTERSECT, LIST_INTERSECT)
          .put(BuiltIn.LIST_LAST, LIST_LAST)
          .put(BuiltIn.LIST_LENGTH, LIST_LENGTH)
          .put(BuiltIn.LIST_MAP_PARTIAL, LIST_MAP_PARTIAL)
          .put(BuiltIn.LIST_MAP, LIST_MAP)
          .put(BuiltIn.LIST_MAPI, LIST_MAPI)
          .put(BuiltIn.LIST_NIL, ImmutableList.of())
          .put(BuiltIn.LIST_NTH, LIST_NTH)
          .put(BuiltIn.LIST_NULL, LIST_NULL)
          .put(BuiltIn.LIST_OP_AT, LIST_AT) // op @ == List.at
          .put(BuiltIn.LIST_PAIR_ALL_EQ, LIST_PAIR_ALL_EQ)
          .put(BuiltIn.LIST_PAIR_ALL, LIST_PAIR_ALL)
          .put(BuiltIn.LIST_PAIR_APP_EQ, LIST_PAIR_APP_EQ)
          .put(BuiltIn.LIST_PAIR_APP, LIST_PAIR_APP)
          .put(BuiltIn.LIST_PAIR_EXISTS, LIST_PAIR_EXISTS)
          .put(BuiltIn.LIST_PAIR_FOLDL_EQ, LIST_PAIR_FOLDL_EQ)
          .put(BuiltIn.LIST_PAIR_FOLDL, LIST_PAIR_FOLDL)
          .put(BuiltIn.LIST_PAIR_FOLDR_EQ, LIST_PAIR_FOLDR_EQ)
          .put(BuiltIn.LIST_PAIR_FOLDR, LIST_PAIR_FOLDR)
          .put(BuiltIn.LIST_PAIR_MAP_EQ, LIST_PAIR_MAP_EQ)
          .put(BuiltIn.LIST_PAIR_MAP, LIST_PAIR_MAP)
          .put(BuiltIn.LIST_PAIR_UNZIP, LIST_PAIR_UNZIP)
          .put(BuiltIn.LIST_PAIR_ZIP_EQ, LIST_PAIR_ZIP_EQ)
          .put(BuiltIn.LIST_PAIR_ZIP, LIST_PAIR_ZIP)
          .put(BuiltIn.LIST_PARTITION, LIST_PARTITION)
          .put(BuiltIn.LIST_REV_APPEND, LIST_REV_APPEND)
          .put(BuiltIn.LIST_REV, LIST_REV)
          .put(BuiltIn.LIST_TABULATE, LIST_TABULATE)
          .put(BuiltIn.LIST_TAKE, LIST_TAKE)
          .put(BuiltIn.LIST_TL, LIST_TL)
          .put(BuiltIn.MATH_ACOS, MATH_ACOS)
          .put(BuiltIn.MATH_ASIN, MATH_ASIN)
          .put(BuiltIn.MATH_ATAN, MATH_ATAN)
          .put(BuiltIn.MATH_ATAN2, MATH_ATAN2)
          .put(BuiltIn.MATH_COS, MATH_COS)
          .put(BuiltIn.MATH_COSH, MATH_COSH)
          .put(BuiltIn.MATH_E, MATH_E)
          .put(BuiltIn.MATH_EXP, MATH_EXP)
          .put(BuiltIn.MATH_LN, MATH_LN)
          .put(BuiltIn.MATH_LOG10, MATH_LOG10)
          .put(BuiltIn.MATH_PI, MATH_PI)
          .put(BuiltIn.MATH_POW, MATH_POW)
          .put(BuiltIn.MATH_SIN, MATH_SIN)
          .put(BuiltIn.MATH_SINH, MATH_SINH)
          .put(BuiltIn.MATH_SQRT, MATH_SQRT)
          .put(BuiltIn.MATH_TAN, MATH_TAN)
          .put(BuiltIn.MATH_TANH, MATH_TANH)
          .put(BuiltIn.OP_CARET, OP_CARET)
          .put(BuiltIn.OP_CONS, OP_CONS)
          .put(BuiltIn.OP_DIV, OP_DIV)
          .put(BuiltIn.OP_DIVIDE, OP_DIVIDE)
          .put(BuiltIn.OP_ELEM, OP_ELEM)
          .put(BuiltIn.OP_EQ, OP_EQ)
          .put(BuiltIn.OP_GE, OP_GE)
          .put(BuiltIn.OP_GT, OP_GT)
          .put(BuiltIn.OP_LE, OP_LE)
          .put(BuiltIn.OP_LT, OP_LT)
          .put(BuiltIn.OP_MINUS, OP_MINUS)
          .put(BuiltIn.OP_MOD, OP_MOD)
          .put(BuiltIn.OP_NE, OP_NE)
          .put(BuiltIn.OP_NEGATE, OP_NEGATE)
          .put(BuiltIn.OP_NOT_ELEM, OP_NOT_ELEM)
          .put(BuiltIn.OP_PLUS, OP_PLUS)
          .put(BuiltIn.OP_TIMES, OP_TIMES)
          .put(BuiltIn.OPTION_APP, OPTION_APP)
          .put(BuiltIn.OPTION_COMPOSE_PARTIAL, OPTION_COMPOSE_PARTIAL)
          .put(BuiltIn.OPTION_COMPOSE, OPTION_COMPOSE)
          .put(BuiltIn.OPTION_FILTER, OPTION_FILTER)
          .put(BuiltIn.OPTION_GET_OPT, OPTION_GET_OPT)
          .put(BuiltIn.OPTION_IS_SOME, OPTION_IS_SOME)
          .put(BuiltIn.OPTION_JOIN, OPTION_JOIN)
          .put(BuiltIn.OPTION_MAP_PARTIAL, OPTION_MAP_PARTIAL)
          .put(BuiltIn.OPTION_MAP, OPTION_MAP)
          .put(BuiltIn.OPTION_VAL_OF, OPTION_VAL_OF)
          .put(BuiltIn.REAL_ABS, REAL_ABS)
          .put(BuiltIn.REAL_CEIL, REAL_CEIL)
          .put(BuiltIn.REAL_CHECK_FLOAT, REAL_CHECK_FLOAT)
          .put(BuiltIn.REAL_COMPARE, REAL_COMPARE)
          .put(BuiltIn.REAL_COPY_SIGN, REAL_COPY_SIGN)
          .put(BuiltIn.REAL_FLOOR, REAL_FLOOR)
          .put(BuiltIn.REAL_FROM_INT, REAL_FROM_INT)
          .put(BuiltIn.REAL_FROM_MAN_EXP, REAL_FROM_MAN_EXP)
          .put(BuiltIn.REAL_FROM_STRING, REAL_FROM_STRING)
          .put(BuiltIn.REAL_IS_FINITE, REAL_IS_FINITE)
          .put(BuiltIn.REAL_IS_NAN, REAL_IS_NAN)
          .put(BuiltIn.REAL_IS_NORMAL, REAL_IS_NORMAL)
          .put(BuiltIn.REAL_MAX_FINITE, REAL_MAX_FINITE)
          .put(BuiltIn.REAL_MAX, REAL_MAX)
          .put(BuiltIn.REAL_MIN_NORMAL_POS, REAL_MIN_NORMAL_POS)
          .put(BuiltIn.REAL_MIN_POS, REAL_MIN_POS)
          .put(BuiltIn.REAL_MIN, REAL_MIN)
          .put(BuiltIn.REAL_NEG_INF, REAL_NEG_INF)
          .put(BuiltIn.REAL_POS_INF, REAL_POS_INF)
          .put(BuiltIn.REAL_PRECISION, REAL_PRECISION)
          .put(BuiltIn.REAL_RADIX, REAL_RADIX)
          .put(BuiltIn.REAL_REAL_CEIL, REAL_REAL_CEIL)
          .put(BuiltIn.REAL_REAL_FLOOR, REAL_REAL_FLOOR)
          .put(BuiltIn.REAL_REAL_MOD, REAL_REAL_MOD)
          .put(BuiltIn.REAL_REAL_ROUND, REAL_REAL_ROUND)
          .put(BuiltIn.REAL_REAL_TRUNC, REAL_REAL_TRUNC)
          .put(BuiltIn.REAL_REM, REAL_REM)
          .put(BuiltIn.REAL_ROUND, REAL_ROUND)
          .put(BuiltIn.REAL_SAME_SIGN, REAL_SAME_SIGN)
          .put(BuiltIn.REAL_SIGN_BIT, REAL_SIGN_BIT)
          .put(BuiltIn.REAL_SIGN, REAL_SIGN)
          .put(BuiltIn.REAL_SPLIT, REAL_SPLIT)
          .put(BuiltIn.REAL_TO_MAN_EXP, REAL_TO_MAN_EXP)
          .put(BuiltIn.REAL_TO_STRING, REAL_TO_STRING)
          .put(BuiltIn.REAL_TRUNC, REAL_TRUNC)
          .put(BuiltIn.REAL_UNORDERED, REAL_UNORDERED)
          .put(BuiltIn.RELATIONAL_COMPARE, RELATIONAL_COMPARE)
          .put(BuiltIn.RELATIONAL_COUNT, RELATIONAL_COUNT)
          .put(BuiltIn.RELATIONAL_EMPTY, RELATIONAL_EMPTY)
          .put(BuiltIn.RELATIONAL_ITERATE, RELATIONAL_ITERATE)
          .put(BuiltIn.RELATIONAL_MAX, RELATIONAL_MAX)
          .put(BuiltIn.RELATIONAL_MIN, RELATIONAL_MIN)
          .put(BuiltIn.RELATIONAL_NON_EMPTY, RELATIONAL_NON_EMPTY)
          .put(BuiltIn.RELATIONAL_ONLY, RELATIONAL_ONLY)
          .put(BuiltIn.RELATIONAL_SUM, RELATIONAL_SUM)
          .put(BuiltIn.STRING_COLLATE, STRING_COLLATE)
          .put(BuiltIn.STRING_COMPARE, STRING_COMPARE)
          .put(BuiltIn.STRING_CONCAT_WITH, STRING_CONCAT_WITH)
          .put(BuiltIn.STRING_CONCAT, STRING_CONCAT)
          .put(BuiltIn.STRING_EXPLODE, STRING_EXPLODE)
          .put(BuiltIn.STRING_EXTRACT, STRING_EXTRACT)
          .put(BuiltIn.STRING_FIELDS, STRING_FIELDS)
          .put(BuiltIn.STRING_IMPLODE, STRING_IMPLODE)
          .put(BuiltIn.STRING_IS_PREFIX, STRING_IS_PREFIX)
          .put(BuiltIn.STRING_IS_SUBSTRING, STRING_IS_SUBSTRING)
          .put(BuiltIn.STRING_IS_SUFFIX, STRING_IS_SUFFIX)
          .put(BuiltIn.STRING_MAP, STRING_MAP)
          .put(BuiltIn.STRING_MAX_SIZE, STRING_MAX_SIZE)
          .put(BuiltIn.STRING_OP_CARET, STRING_OP_CARET)
          .put(BuiltIn.STRING_OP_GE, STRING_OP_GE)
          .put(BuiltIn.STRING_OP_GT, STRING_OP_GT)
          .put(BuiltIn.STRING_OP_LE, STRING_OP_LE)
          .put(BuiltIn.STRING_OP_LT, STRING_OP_LT)
          .put(BuiltIn.STRING_SIZE, STRING_SIZE)
          .put(BuiltIn.STRING_STR, STRING_STR)
          .put(BuiltIn.STRING_SUB, STRING_SUB)
          .put(BuiltIn.STRING_SUBSTRING, STRING_SUBSTRING)
          .put(BuiltIn.STRING_TOKENS, STRING_TOKENS)
          .put(BuiltIn.STRING_TRANSLATE, STRING_TRANSLATE)
          .put(BuiltIn.SYS_CLEAR_ENV, SYS_CLEAR_ENV)
          .put(BuiltIn.SYS_ENV, (Macro) Codes::sysEnv)
          // Value of Sys.file comes from Session.file, but initial value must
          // be a List because it has (progressive) record type.
          .put(BuiltIn.SYS_FILE, ImmutableList.of())
          .put(BuiltIn.SYS_PLAN, SYS_PLAN)
          .put(BuiltIn.SYS_SET, SYS_SET)
          .put(BuiltIn.SYS_SHOW_ALL, SYS_SHOW_ALL)
          .put(BuiltIn.SYS_SHOW, SYS_SHOW)
          .put(BuiltIn.SYS_UNSET, SYS_UNSET)
          .put(BuiltIn.VECTOR_ALL, VECTOR_ALL)
          .put(BuiltIn.VECTOR_APP, VECTOR_APP)
          .put(BuiltIn.VECTOR_APPI, VECTOR_APPI)
          .put(BuiltIn.VECTOR_COLLATE, VECTOR_COLLATE)
          .put(BuiltIn.VECTOR_CONCAT, VECTOR_CONCAT)
          .put(BuiltIn.VECTOR_EXISTS, VECTOR_EXISTS)
          .put(BuiltIn.VECTOR_FIND, VECTOR_FIND)
          .put(BuiltIn.VECTOR_FINDI, VECTOR_FINDI)
          .put(BuiltIn.VECTOR_FOLDL, VECTOR_FOLDL)
          .put(BuiltIn.VECTOR_FOLDLI, VECTOR_FOLDLI)
          .put(BuiltIn.VECTOR_FOLDR, VECTOR_FOLDR)
          .put(BuiltIn.VECTOR_FOLDRI, VECTOR_FOLDRI)
          .put(BuiltIn.VECTOR_FROM_LIST, VECTOR_FROM_LIST)
          .put(BuiltIn.VECTOR_LENGTH, VECTOR_LENGTH)
          .put(BuiltIn.VECTOR_MAP, VECTOR_MAP)
          .put(BuiltIn.VECTOR_MAPI, VECTOR_MAPI)
          .put(BuiltIn.VECTOR_MAX_LEN, VECTOR_MAX_LEN)
          .put(BuiltIn.VECTOR_SUB, VECTOR_SUB)
          .put(BuiltIn.VECTOR_TABULATE, VECTOR_TABULATE)
          .put(BuiltIn.VECTOR_UPDATE, VECTOR_UPDATE)
          .put(BuiltIn.Z_ANDALSO, Unit.INSTANCE)
          .put(BuiltIn.Z_CURRENT, Unit.INSTANCE)
          .put(BuiltIn.Z_DIVIDE_INT, Z_DIVIDE_INT)
          .put(BuiltIn.Z_DIVIDE_REAL, Z_DIVIDE_REAL)
          .put(BuiltIn.Z_EXTENT, Z_EXTENT)
          .put(BuiltIn.Z_LIST, Z_LIST)
          .put(BuiltIn.Z_MINUS_INT, Z_MINUS_INT)
          .put(BuiltIn.Z_MINUS_REAL, Z_MINUS_REAL)
          .put(BuiltIn.Z_NEGATE_INT, Z_NEGATE_INT)
          .put(BuiltIn.Z_NEGATE_REAL, Z_NEGATE_REAL)
          .put(BuiltIn.Z_ORDINAL, 0)
          .put(BuiltIn.Z_ORELSE, Unit.INSTANCE)
          .put(BuiltIn.Z_PLUS_INT, Z_PLUS_INT)
          .put(BuiltIn.Z_PLUS_REAL, Z_PLUS_REAL)
          .put(BuiltIn.Z_SUM_INT, Z_SUM_INT)
          .put(BuiltIn.Z_SUM_REAL, Z_SUM_REAL)
          .put(BuiltIn.Z_TIMES_INT, Z_TIMES_INT)
          .put(BuiltIn.Z_TIMES_REAL, Z_TIMES_REAL)
          .build(); // lint:endSorted

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
    return buf.append(floatToString(f));
  }

  /**
   * Converts a Java {@code float} to the format expected of Standard ML {@code
   * real} values.
   */
  @VisibleForTesting
  public static String floatToString(float f) {
    if (Float.isFinite(f)) {
      final String s = FLOAT_TO_STRING.apply(f);
      return s.replace('-', '~');
    } else if (f == Float.POSITIVE_INFINITY) {
      return "inf";
    } else if (f == Float.NEGATIVE_INFINITY) {
      return "~inf";
    } else if (Float.isNaN(f)) {
      return "nan";
    } else {
      throw new AssertionError("unknown float " + f);
    }
  }

  private static String floatToString0(float f) {
    String s = Float.toString(f);
    int lastDigit = s.indexOf("E");
    if (lastDigit < 0) {
      lastDigit = s.length();
    }
    if (s.equals("1.17549435E-38")) {
      return "1.1754944E-38";
    }
    if (s.equals("1.23456795E12")) {
      return "1.234568E12";
    }
    if (s.equals("1.23456791E11")) {
      return "1.2345679E11";
    }
    if (s.equals("1.23456788E10")) {
      return "1.2345679E10";
    }
    if (s.equals("1.23456792E8")) {
      return "1.2345679E8";
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

    public Object eval(EvalEnv env) {
      final Object[] values = new Object[codes.size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = codes.get(i).eval(env);
      }
      return Arrays.asList(values);
    }
  }

  /** Code that evaluates a query. */
  static class FromCode implements Code {
    private final Supplier<Codes.RowSink> rowSinkFactory;

    FromCode(Supplier<Codes.RowSink> rowSinkFactory) {
      this.rowSinkFactory = requireNonNull(rowSinkFactory);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("from", d -> d.arg("sink", rowSinkFactory.get()));
    }

    @Override
    public Object eval(EvalEnv env) {
      final RowSink rowSink = rowSinkFactory.get();
      rowSink.start(env);
      rowSink.accept(env);
      return rowSink.result(env);
    }
  }

  /** Accepts rows produced by a supplier as part of a {@code from} step. */
  public interface RowSink extends Describable {
    void start(EvalEnv env);

    void accept(EvalEnv env);

    List<Object> result(EvalEnv env);
  }

  /** Abstract implementation for row sinks that have one successor. */
  abstract static class BaseRowSink implements RowSink {
    final RowSink rowSink;

    BaseRowSink(RowSink rowSink) {
      this.rowSink = requireNonNull(rowSink);
    }

    @Override
    public void start(EvalEnv env) {
      rowSink.start(env);
    }

    @Override
    public void accept(EvalEnv env) {
      rowSink.accept(env);
    }

    @Override
    public List<Object> result(EvalEnv env) {
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code join} step. */
  static class ScanRowSink extends BaseRowSink {
    final Op op; // inner, left, right, full
    private final Core.Pat pat;
    private final Code code;
    final Code conditionCode;

    ScanRowSink(
        Op op, Core.Pat pat, Code code, Code conditionCode, RowSink rowSink) {
      super(rowSink);
      checkArgument(op == Op.SCAN);
      this.op = op;
      this.pat = pat;
      this.code = code;
      this.conditionCode = conditionCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "join",
          d ->
              d.arg("pat", pat)
                  .arg("exp", code)
                  .argIf(
                      "condition",
                      conditionCode,
                      !isConstantTrue(conditionCode))
                  .arg("sink", rowSink));
    }

    private static boolean isConstantTrue(Code code) {
      return code.isConstant() && Objects.equals(code.eval(null), true);
    }

    @Override
    public void accept(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutablePat(pat);
      final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
      for (Object element : elements) {
        if (mutableEvalEnv.setOpt(element)) {
          Boolean b = (Boolean) conditionCode.eval(mutableEvalEnv);
          if (b != null && b) {
            rowSink.accept(mutableEvalEnv);
          }
        }
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code where} step. */
  static class WhereRowSink extends BaseRowSink {
    final Code filterCode;

    WhereRowSink(Code filterCode, RowSink rowSink) {
      super(rowSink);
      this.filterCode = filterCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "where", d -> d.arg("condition", filterCode).arg("sink", rowSink));
    }

    @Override
    public void accept(EvalEnv env) {
      if ((Boolean) filterCode.eval(env)) {
        rowSink.accept(env);
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code skip} step. */
  static class SkipRowSink extends BaseRowSink {
    final Code skipCode;
    int skip;

    SkipRowSink(Code skipCode, RowSink rowSink) {
      super(rowSink);
      this.skipCode = skipCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "skip", d -> d.arg("count", skipCode).arg("sink", rowSink));
    }

    @Override
    public void start(EvalEnv env) {
      skip = (Integer) skipCode.eval(env);
      super.start(env);
    }

    @Override
    public void accept(EvalEnv env) {
      if (skip > 0) {
        --skip;
      } else {
        rowSink.accept(env);
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code take} step. */
  static class TakeRowSink extends BaseRowSink {
    final Code takeCode;
    int take;

    TakeRowSink(Code takeCode, RowSink rowSink) {
      super(rowSink);
      this.takeCode = takeCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "take", d -> d.arg("count", takeCode).arg("sink", rowSink));
    }

    @Override
    public void start(EvalEnv env) {
      take = (Integer) takeCode.eval(env);
      super.start(env);
    }

    @Override
    public void accept(EvalEnv env) {
      if (take > 0) {
        --take;
        rowSink.accept(env);
      }
    }
  }

  /**
   * Implementation of {@link RowSink} for an {@code except}, {@code intersect},
   * or {@code union} step.
   */
  abstract static class SetRowSink extends BaseRowSink {
    private static final int[] ZERO = {};

    final Op op;
    final boolean distinct;
    final ImmutableList<Code> codes;
    final ImmutableList<String> names;
    final Map<Object, int[]> map = new HashMap<>();
    final Object[] values;

    SetRowSink(
        Op op,
        boolean distinct,
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(rowSink);
      checkArgument(
          op == Op.EXCEPT || op == Op.INTERSECT || op == Op.UNION,
          "invalid op %s",
          op);
      this.op = op;
      this.distinct = distinct;
      this.codes = requireNonNull(codes);
      this.names = requireNonNull(names);
      this.values = new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          op.opName,
          d -> {
            d.arg("distinct", distinct);
            forEachIndexed(codes, (code, i) -> d.arg("arg" + i, code));
            d.arg("sink", rowSink);
          });
    }

    /**
     * Adds the current element to the collection, and returns whether it was
     * added.
     */
    boolean add(EvalEnv env) {
      if (names.size() == 1) {
        Object value = env.getOpt(names.get(0));
        return map.put(value, ZERO) == null;
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        return map.put(ImmutableList.copyOf(values), ZERO) == null;
      }
    }

    /** Removes the current element from the collection. */
    void remove(EvalEnv env) {
      if (names.size() == 1) {
        Object value = env.getOpt(names.get(0));
        map.remove(value);
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        map.remove(ImmutableList.copyOf(values));
      }
    }

    /** Increments the count of the current element in the collection. */
    void inc(EvalEnv env) {
      compute(
          env,
          (k, v) -> {
            if (v == null) {
              return new int[] {1};
            }
            ++v[0];
            return v;
          });
    }

    /**
     * Decrements the count of the current element in the collection, if it is
     * present.
     */
    void dec(EvalEnv env) {
      computeIfPresent(
          env,
          (k, v) -> {
            --v[0];
            return v;
          });
    }

    /** Does something to the count of the current element in the collection. */
    void compute(EvalEnv env, BiFunction<Object, int[], int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.compute(value, fn);
    }

    /** Does something to the count of the current element in the collection. */
    void computeIfPresent(EvalEnv env, BiFunction<Object, int[], int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.computeIfPresent(value, fn);
    }

    /** Does something to the count of the current element in the collection. */
    void computeIfAbsent(EvalEnv env, Function<Object, int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.computeIfAbsent(value, fn);
    }
  }

  /** Implementation of {@link RowSink} for non-distinct {@code except} step. */
  static class ExceptAllRowSink extends SetRowSink {
    ExceptAllRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.EXCEPT, false, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      inc(env);
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          dec(mutableEvalEnv);
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        map.forEach(
            (k, v) -> {
              int v2 = v[0];
              if (v2 > 0) {
                mutableEvalEnv2.set(k);
                for (int i = 0; i < v2; i++) {
                  // Output the element several times.
                  rowSink.accept(mutableEvalEnv2);
                }
              }
            });
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a distinct {@code except} step. */
  static class ExceptDistinctRowSink extends SetRowSink {
    ExceptDistinctRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.EXCEPT, true, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      add(env);
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          remove(mutableEvalEnv);
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        map.keySet()
            .forEach(
                element -> {
                  mutableEvalEnv2.set(element);
                  rowSink.accept(mutableEvalEnv2);
                });
      }
      return rowSink.result(env);
    }
  }

  /**
   * Implementation of {@link RowSink} for a non-distinct {@code intersect}
   * step.
   *
   * <p>The algorithm is as follows:
   *
   * <ol>
   *   <li>Populate the map with (k, 1, 0) for each key k from input 0, and
   *       increment the count each time a key repeats.
   *   <li>Read input 1, and populate the second count field.
   *   <li>If there is another input, pass over the map, replacing each entry
   *       (k, x, y) with (k, min(x, y), 0), and removing all entries (k, x, 0).
   *       Then repeat from step 1.
   *   <li>Output all keys min(x, y) times.
   * </ol>
   */
  static class IntersectAllRowSink extends SetRowSink {
    IntersectAllRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.INTERSECT, false, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      // Initialize each key to 1, and increment the count each time the key
      // repeats.
      compute(
          env,
          (k, v) -> {
            if (v == null) {
              return new int[] {1, 0};
            }
            ++v[0];
            return v;
          });
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      int pass = 0;
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        if (pass++ > 0) {
          // If there was a previous input, remove all keys whose most recent
          // count#1 is 0, set count#0 to the minimum of the two counts, and
          // zero count#1.
          map.entrySet().removeIf(e -> e.getValue()[1] == 0);
          map.values()
              .forEach(
                  v -> {
                    v[0] = Math.min(v[0], v[1]);
                    v[1] = 0;
                  });
        }
        // Increment count#1 of each key from the new input, ignoring keys not
        // present.
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          computeIfPresent(
              mutableEvalEnv,
              (k, v) -> {
                ++v[1];
                return v;
              });
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        map.forEach(
            (k, v) -> {
              int v2 = Math.min(v[0], v[1]);
              if (v2 > 0) {
                mutableEvalEnv2.set(k);
                for (int i = 0; i < v2; i++) {
                  // Output the element several times.
                  rowSink.accept(mutableEvalEnv2);
                }
              }
            });
      }
      return rowSink.result(env);
    }
  }

  /**
   * Implementation of {@link RowSink} for a distinct {@code intersect} step.
   *
   * <p>The algorithm is as follows:
   *
   * <ol>
   *   <li>Populate the map with (k, 0) for each key k from input 0;
   *   <li>Read input 1, and for each key increments the count.
   *   <li>If there is another input, first remove each key with count zero, and
   *       sets other keys' count to zero. Then repeat from step 1.
   *   <li>Output all keys that have count greater than 0 from the last pass.
   * </ol>
   */
  static class IntersectDistinctRowSink extends SetRowSink {
    IntersectDistinctRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.INTERSECT, true, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      // Initialize each distinct key to 0.
      computeIfAbsent(env, k -> new int[] {0});
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      int pass = 0;
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        // If there was a previous step, remove all keys with count 0, and
        // zero the counts of the other keys.
        if (pass++ > 0) {
          map.entrySet().removeIf(e -> e.getValue()[0] == 0);
          map.forEach((k, v) -> v[0] = 0);
        }

        // Increment the count of each key; ignore keys not present.
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          computeIfPresent(
              mutableEvalEnv,
              (k, v) -> {
                ++v[0];
                return v;
              });
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        // Output keys that have a positive count than 0 from the last pass.
        map.forEach(
            (k, v) -> {
              if (v[0] > 0) {
                mutableEvalEnv2.set(k);
                rowSink.accept(mutableEvalEnv2);
              }
            });
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code union} step. */
  static class UnionRowSink extends SetRowSink {
    UnionRowSink(
        boolean distinct,
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.UNION, distinct, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      if (!distinct || add(env)) {
        rowSink.accept(env);
      }
    }

    @Override
    public List<Object> result(EvalEnv env) {
      MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          if (!distinct || add(mutableEvalEnv)) {
            rowSink.accept(mutableEvalEnv);
          }
        }
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code group} step. */
  private static class GroupRowSink extends BaseRowSink {
    final Code keyCode;
    final ImmutableList<String> inNames;
    final ImmutableList<String> keyNames;
    /** group names followed by aggregate names */
    final ImmutableList<String> outNames;

    final ImmutableList<Applicable> aggregateCodes;
    final ListMultimap<Object, Object> map = ArrayListMultimap.create();
    final Object[] values;

    GroupRowSink(
        Code keyCode,
        ImmutableList<Applicable> aggregateCodes,
        ImmutableList<String> inNames,
        ImmutableList<String> keyNames,
        ImmutableList<String> outNames,
        RowSink rowSink) {
      super(rowSink);
      this.keyCode = requireNonNull(keyCode);
      this.aggregateCodes = requireNonNull(aggregateCodes);
      this.inNames = requireNonNull(inNames);
      this.keyNames = requireNonNull(keyNames);
      this.outNames = requireNonNull(outNames);
      this.values = inNames.size() == 1 ? null : new Object[inNames.size()];
      checkArgument(isPrefix(keyNames, outNames));
    }

    private static <E> boolean isPrefix(List<E> list0, List<E> list1) {
      return list0.size() <= list1.size()
          && list0.equals(list1.subList(0, list0.size()));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "group",
          d -> {
            d.arg("key", keyCode);
            aggregateCodes.forEach(a -> d.arg("agg", a));
            d.arg("sink", rowSink);
          });
    }

    @Override
    public void accept(EvalEnv env) {
      if (inNames.size() == 1) {
        map.put(keyCode.eval(env), env.getOpt(inNames.get(0)));
      } else {
        for (int i = 0; i < inNames.size(); i++) {
          values[i] = env.getOpt(inNames.get(i));
        }
        map.put(keyCode.eval(env), values.clone());
      }
    }

    @Override
    public List<Object> result(final EvalEnv env) {
      // Derive env2, the environment for our consumer. It consists of our input
      // environment plus output names.
      EvalEnv env2 = env;
      final MutableEvalEnv[] groupEnvs = new MutableEvalEnv[outNames.size()];
      int i = 0;
      for (String name : outNames) {
        env2 = groupEnvs[i++] = env2.bindMutable(name);
      }

      // Also derive env3, the environment wherein the aggregate functions are
      // evaluated.
      final EvalEnv env3 =
          keyNames.isEmpty() ? env : groupEnvs[keyNames.size() - 1];

      final Map<Object, List<Object>> map2;
      if (map.isEmpty()
          && keyCode instanceof TupleCode
          && ((TupleCode) keyCode).codes.isEmpty()) {
        // There are no keys, and there were no input rows.
        map2 = ImmutableMap.of(ImmutableList.of(), ImmutableList.of());
      } else {
        //noinspection UnstableApiUsage
        map2 = Multimaps.asMap(map);
      }
      for (Map.Entry<Object, List<Object>> entry : map2.entrySet()) {
        final List list = (List) entry.getKey();
        for (i = 0; i < list.size(); i++) {
          groupEnvs[i].set(list.get(i));
        }
        final List<Object> rows = entry.getValue(); // rows in this bucket
        for (Applicable aggregateCode : aggregateCodes) {
          groupEnvs[i++].set(aggregateCode.apply(env3, rows));
        }
        rowSink.accept(env2);
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for an {@code order} step. */
  static class OrderRowSink extends BaseRowSink {
    final Code code;
    final Comparator comparator;
    final ImmutableList<String> names;
    final List<Object> rows = new ArrayList<>();
    final Object[] values;

    OrderRowSink(
        Code code,
        Comparator comparator,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(rowSink);
      this.code = code;
      this.comparator = comparator;
      this.names = names;
      this.values = names.size() == 1 ? null : new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "order", d -> d.arg("code", code).arg("sink", rowSink));
    }

    @Override
    public void accept(EvalEnv env) {
      if (values == null) {
        rows.add(env.getOpt(names.get(0)));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        rows.add(values.clone());
      }
    }

    @Override
    public List<Object> result(final EvalEnv env) {
      final MutableEvalEnv leftEnv = env.bindMutableArray(names);
      final MutableEvalEnv rightEnv = env.bindMutableArray(names);
      rows.sort(
          (left, right) -> {
            leftEnv.set(left);
            rightEnv.set(right);
            final Object leftVal = code.eval(leftEnv);
            final Object rightVal = code.eval(rightEnv);
            return comparator.compare(leftVal, rightVal);
          });
      for (Object row : rows) {
        leftEnv.set(row);
        rowSink.accept(leftEnv);
      }
      return rowSink.result(env);
    }
  }

  /**
   * Implementation of {@link RowSink} for a {@code yield} step.
   *
   * <p>If this is the last step, use instead a {@link CollectRowSink}. It is
   * more efficient; there is no downstream row sink; and a terminal yield step
   * is allowed to generate expressions that are not records. Non-record
   * expressions (e.g. {@code int} expressions) do not have a name, and
   * therefore the value cannot be passed via the {@link EvalEnv}.
   */
  private static class YieldRowSink extends BaseRowSink {
    private final ImmutableList<String> names;
    private final ImmutableList<Code> codes;
    private final Object[] values;

    YieldRowSink(
        ImmutableList<String> names,
        ImmutableList<Code> codes,
        RowSink rowSink) {
      super(rowSink);
      this.names = names;
      this.codes = codes;
      this.values = names.size() == 1 ? null : new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "yield", d -> d.args("codes", codes).arg("sink", rowSink));
    }

    @Override
    public void accept(EvalEnv env) {
      final MutableEvalEnv env2 = env.bindMutableArray(names);
      if (values == null) {
        final Object value = codes.get(0).eval(env);
        env2.set(value);
      } else {
        for (int i = 0; i < codes.size(); i++) {
          values[i] = codes.get(i).eval(env);
        }
        env2.set(values);
      }
      rowSink.accept(env2);
    }
  }

  /**
   * Implementation of {@link RowSink} that the last step of a {@code from}
   * writes into.
   */
  private static class CollectRowSink implements RowSink {
    final List<Object> list = new ArrayList<>();
    final Code code;

    CollectRowSink(Code code) {
      this.code = requireNonNull(code);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("collect", d -> d.arg("", code));
    }

    @Override
    public void start(EvalEnv env) {
      list.clear();
    }

    @Override
    public void accept(EvalEnv env) {
      list.add(code.eval(env));
    }

    @Override
    public List<Object> result(EvalEnv env) {
      return list;
    }
  }

  /** First row sink in the chain. */
  private static class FirstRowSink extends BaseRowSink {
    final ImmutableList<Runnable> startActions;

    FirstRowSink(RowSink rowSink, List<Runnable> startActions) {
      super(rowSink);
      this.startActions = ImmutableList.copyOf(startActions);
    }

    @Override
    public Describer describe(Describer describer) {
      return rowSink.describe(describer);
    }

    @Override
    public void start(EvalEnv env) {
      startActions.forEach(Runnable::run);
      rowSink.start(env);
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
      return describer.start("get", d -> d.arg("name", name));
    }

    @Override
    public String toString() {
      return "get(" + name + ")";
    }

    public Object eval(EvalEnv env) {
      return env.getOpt(name);
    }
  }

  /**
   * Code that retrieves, as a tuple, the value of several variables from the
   * environment.
   */
  private static class GetTupleCode implements Code {
    private final ImmutableList<String> names;
    private final Object[] values; // work space

    GetTupleCode(ImmutableList<String> names) {
      this.names = requireNonNull(names);
      this.values = new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("getTuple", d -> d.args("names", names));
    }

    @Override
    public String toString() {
      return "getTuple(" + names + ")";
    }

    @Override
    public Object eval(EvalEnv env) {
      for (int i = 0; i < names.size(); i++) {
        values[i] = env.getOpt(names.get(i));
      }
      return Arrays.asList(values.clone());
    }
  }

  /** Java exception that wraps an exception thrown by the Morel runtime. */
  public static class MorelRuntimeException extends RuntimeException
      implements MorelException {
    private final BuiltInExn e;
    private final Pos pos;

    /** Creates a MorelRuntimeException. */
    public MorelRuntimeException(BuiltInExn e, Pos pos) {
      this.e = requireNonNull(e);
      this.pos = requireNonNull(pos);
    }

    @Override
    public String toString() {
      return e.mlName + " at " + pos;
    }

    @Override
    public StringBuilder describeTo(StringBuilder buf) {
      return buf.append("uncaught exception ").append(e.mlName);
    }

    @Override
    public Pos pos() {
      return pos;
    }
  }

  /** Definitions of Morel built-in exceptions. */
  public enum BuiltInExn {
    EMPTY("List", "Empty"),
    BIND("General", "Bind"),
    CHR("General", "Chr"),
    DIV("General", "Div"),
    DOMAIN("General", "Domain"),
    OPTION("Option", "Option"),
    OVERFLOW("General", "Overflow"),
    ERROR("Interact", "Error"), // not in standard basis
    SIZE("General", "Size"),
    SUBSCRIPT("General", "Subscript [subscript out of bounds]"),
    UNEQUAL_LENGTHS("ListPair", "UnequalLengths"),
    UNORDERED("IEEEReal", "Unordered");

    public final String structure;
    public final String mlName;

    BuiltInExn(String structure, String mlName) {
      this.structure = structure;
      this.mlName = mlName;
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

    public Object eval(EvalEnv env) {
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
    public Describer describe(Describer describer) {
      return describer.start("andalso", d -> d.arg("", code0).arg("", code1));
    }

    @Override
    public Object eval(EvalEnv evalEnv) {
      // Lazy evaluation. If code0 returns false, code1 is never evaluated.
      return (boolean) code0.eval(evalEnv) && (boolean) code1.eval(evalEnv);
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
    public Describer describe(Describer describer) {
      return describer.start("orelse", d -> d.arg("", code0).arg("", code1));
    }

    @Override
    public Object eval(EvalEnv evalEnv) {
      // Lazy evaluation. If code0 returns true, code1 is never evaluated.
      return (boolean) code0.eval(evalEnv) || (boolean) code1.eval(evalEnv);
    }
  }

  /** Code that implements {@link #let(List, Code)} with one argument. */
  private static class Let1Code implements Code {
    private final Code matchCode;
    private final Code resultCode;

    Let1Code(Code matchCode, Code resultCode) {
      this.matchCode = matchCode;
      this.resultCode = resultCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "let1",
          d -> d.arg("matchCode", matchCode).arg("resultCode", resultCode));
    }

    @Override
    public Object eval(EvalEnv evalEnv) {
      final Closure fnValue = (Closure) matchCode.eval(evalEnv);
      EvalEnv env2 = fnValue.evalBind(evalEnv);
      return resultCode.eval(env2);
    }
  }

  /** Code that implements {@link #let(List, Code)} with multiple arguments. */
  private static class LetCode implements Code {
    private final ImmutableList<Code> matchCodes;
    private final Code resultCode;

    LetCode(ImmutableList<Code> matchCodes, Code resultCode) {
      this.matchCodes = matchCodes;
      this.resultCode = resultCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "let",
          d -> {
            forEachIndexed(
                matchCodes,
                (matchCode, i) -> d.arg("matchCode" + i, matchCode));
            d.arg("resultCode", resultCode);
          });
    }

    @Override
    public Object eval(EvalEnv evalEnv) {
      EvalEnv evalEnv2 = evalEnv;
      for (Code matchCode : matchCodes) {
        final Closure fnValue = (Closure) matchCode.eval(evalEnv);
        evalEnv2 = fnValue.evalBind(evalEnv2);
      }
      return resultCode.eval(evalEnv2);
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
    public Object eval(EvalEnv env) {
      final Object arg = argCode.eval(env);
      return fnValue.apply(env, arg);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply", d -> d.arg("fnValue", fnValue).arg("argCode", argCode));
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
    public Object eval(EvalEnv env) {
      final Object arg0 = argCode0.eval(env);
      final Object arg1 = argCode1.eval(env);
      return fnValue.apply(arg0, arg1);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply2",
          d -> d.arg("fnValue", fnValue).arg("", argCode0).arg("", argCode1));
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
    public Object eval(EvalEnv env) {
      final Object arg0 = argCode0.eval(env);
      final Object arg1 = argCode1.eval(env);
      final Object arg2 = argCode2.eval(env);
      return fnValue.apply(arg0, arg1, arg2);
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
    public Describer describe(Describer describer) {
      return describer.start(
          "apply", d -> d.arg("fnCode", fnCode).arg("argCode", argCode));
    }

    @Override
    public Object eval(EvalEnv env) {
      final Applicable fnValue = (Applicable) fnCode.eval(env);
      final Object arg = argCode.eval(env);
      return fnValue.apply(env, arg);
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
    public Describer describe(Describer describer) {
      return describer.start("wrapRelList", d -> d.arg("code", code));
    }

    @Override
    public Object eval(EvalEnv env) {
      final Object arg = code.eval(env);
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
    Applicable withType(TypeSystem typeSystem, Type type);
  }

  /** Implementation of {@link Applicable} that stores a {@link BuiltIn}. */
  private abstract static class Applicable1 implements Applicable {
    final BuiltIn builtIn;
    final Pos pos;

    protected Applicable1(BuiltIn builtIn, Pos pos) {
      this.builtIn = builtIn;
      this.pos = pos;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(name(), d -> {});
    }

    protected String name() {
      return builtIn.mlName.startsWith("op ")
          ? builtIn.mlName.substring("op ".length())
          : builtIn.structure + "." + builtIn.mlName;
    }
  }

  /**
   * Implementation of both {@link Applicable} and {@link Positioned}. Remembers
   * its {@link BuiltIn} so that it can re-position.
   */
  private abstract static class PositionedApplicableImpl extends Applicable1
      implements Positioned {
    protected PositionedApplicableImpl(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }
  }

  /** Implementation of {@link Applicable} that has a single char argument. */
  private static class CharPredicate extends ApplicableImpl {
    private final Predicate<Character> predicate;

    CharPredicate(BuiltIn builtIn, Predicate<Character> predicate) {
      super(builtIn);
      this.predicate = predicate;
    }

    @Override
    public Object apply(EvalEnv env, Object arg) {
      return predicate.test((Character) arg);
    }

    static boolean isGraph(char c) {
      return c >= '!' && c <= '~';
    }

    static boolean isPrint(char c) {
      return isGraph(c) || c == ' ';
    }

    static boolean isCntrl(char c) {
      return isAscii(c) && !isPrint(c);
    }

    static boolean isSpace(char c) {
      return c >= '\t' && c <= '\r' || c == ' ';
    }

    static boolean isAscii(char c) {
      return c <= 127;
    }

    static boolean isUpper(char c) {
      return 'A' <= c && c <= 'Z';
    }

    static boolean isLower(char c) {
      return 'a' <= c && c <= 'z';
    }

    static boolean isDigit(char c) {
      return '0' <= c && c <= '9';
    }

    static boolean isAlpha(char c) {
      return isUpper(c) || isLower(c);
    }

    static boolean isAlphaNum(char c) {
      return isAlpha(c) || isDigit(c);
    }

    static boolean isHexDigit(char c) {
      return isDigit(c) || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F';
    }

    static boolean isPunct(char c) {
      return isGraph(c) && !isAlphaNum(c);
    }
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
    public Object eval(EvalEnv evalEnv) {
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
    public Object eval(EvalEnv evalEnv) {
      ++ordinalSlots[0];
      return nextCode.eval(evalEnv);
    }

    @Override
    public Describer describe(Describer describer) {
      describer.addStartAction(this::resetOrdinal);
      return describer.start("ordinal", d -> {});
    }

    private void resetOrdinal() {
      ordinalSlots[0] = -1;
    }
  }

  /** Implementation of {@link #RELATIONAL_COMPARE}. */
  @SuppressWarnings("rawtypes")
  static class Comparer extends Applicable2<List, Object, Object>
      implements Codes.Typed {
    static final Applicable INITIAL = new Comparer(Comparators::compare);

    private final Comparator comparator;

    Comparer(Comparator comparator) {
      super(BuiltIn.RELATIONAL_COMPARE);
      this.comparator = requireNonNull(comparator);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type) {
      checkArgument(type instanceof FnType);
      Type argType = ((FnType) type).paramType;
      checkArgument(argType instanceof TupleType);
      List<Type> argTypes = ((TupleType) argType).argTypes;
      checkArgument(argTypes.size() == 2);
      Type argType0 = argTypes.get(0);
      Type argType1 = argTypes.get(1);
      checkArgument(argType0.equals(argType1));
      return new Comparer(Comparators.comparatorFor(typeSystem, argType0));
    }

    @SuppressWarnings("unchecked")
    @Override
    public List apply(Object o1, Object o2) {
      return Codes.order(comparator.compare(o1, o2));
    }
  }
}

// End Codes.java
