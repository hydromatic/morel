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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Macro;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Chars;
import org.apache.calcite.runtime.FlatLists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.CoreBuilder.core;

import static java.util.Objects.requireNonNull;

/** Helpers for {@link Code}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Codes {
  private Codes() {}

  /** Describes a {@link Code}. */
  public static String describe(Code code) {
    return code.describe(new DescriberImpl()).toString();
  }

  /** Value of {@code NONE}.
   *
   * @see #optionSome(Object) */
  private static final List OPTION_NONE = ImmutableList.of("NONE");

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Object value) {
    return new ConstantCode(value);
  }

  /** @see BuiltIn#OP_EQ */
  private static final Applicable OP_EQ =
      new ApplicableImpl("=") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return list.get(0).equals(list.get(1));
        }
      };

  /** @see BuiltIn#OP_NE */
  private static final Applicable OP_NE =
      new ApplicableImpl("<>") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return !list.get(0).equals(list.get(1));
        }
      };

  /** @see BuiltIn#OP_LT */
  private static final Applicable OP_LT =
      new ApplicableImpl("<") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final Comparable v0 = (Comparable) list.get(0);
          final Comparable v1 = (Comparable) list.get(1);
          return v0.compareTo(v1) < 0;
        }
      };

  /** @see BuiltIn#OP_GT */
  private static final Applicable OP_GT =
      new ApplicableImpl(">") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final Comparable v0 = (Comparable) list.get(0);
          final Comparable v1 = (Comparable) list.get(1);
          return v0.compareTo(v1) > 0;
        }
      };

  /** @see BuiltIn#OP_LE */
  private static final Applicable OP_LE =
      new ApplicableImpl("<=") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final Comparable v0 = (Comparable) list.get(0);
          final Comparable v1 = (Comparable) list.get(1);
          return v0.compareTo(v1) <= 0;
        }
      };

  /** @see BuiltIn#OP_GE */
  private static final Applicable OP_GE =
      new ApplicableImpl(">=") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final Comparable v0 = (Comparable) list.get(0);
          final Comparable v1 = (Comparable) list.get(1);
          return v0.compareTo(v1) >= 0;
        }
      };

  /** @see BuiltIn#OP_ELEM */
  private static final Applicable OP_ELEM =
      new ApplicableImpl("elem") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final Object v0 = list.get(0);
          final List v1 = (List) list.get(1);
          return v1.contains(v0);
        }
      };

  /** @see BuiltIn#OP_NOT_ELEM */
  private static final Applicable OP_NOT_ELEM =
      new ApplicableImpl("nonElem") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final Object v0 = list.get(0);
          final List v1 = (List) list.get(1);
          return !v1.contains(v0);
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
      new ApplicableImpl("~") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return -((Integer) arg);
        }
      };

  /** Implements {@link #OP_NEGATE} for type {@code real}. */
  private static final Applicable Z_NEGATE_REAL =
      new ApplicableImpl("~") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return -((Float) arg);
        }
      };

  /** Implements {@link #OP_PLUS} for type {@code int}. */
  private static final Applicable Z_PLUS_INT =
      new ApplicableImpl("+") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (int) list.get(0) + (int) list.get(1);
        }
      };

  /** Implements {@link #OP_PLUS} for type {@code real}. */
  private static final Applicable Z_PLUS_REAL =
      new ApplicableImpl("+") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (float) list.get(0) + (float) list.get(1);
        }
      };

  /** Implements {@link #OP_MINUS} for type {@code int}. */
  private static final Applicable Z_MINUS_INT =
      new ApplicableImpl("-") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (int) list.get(0) - (int) list.get(1);
        }
      };

  /** Implements {@link #OP_MINUS} for type {@code real}. */
  private static final Applicable Z_MINUS_REAL =
      new ApplicableImpl("-") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (float) list.get(0) - (float) list.get(1);
        }
      };

  /** Implements {@link #OP_TIMES} for type {@code int}. */
  private static final Applicable Z_TIMES_INT =
      new ApplicableImpl("*") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (int) list.get(0) * (int) list.get(1);
        }
      };

  /** Implements {@link #OP_TIMES} for type {@code real}. */
  private static final Applicable Z_TIMES_REAL =
      new ApplicableImpl("*") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (float) list.get(0) * (float) list.get(1);
        }
      };

  /** Implements {@link #OP_DIVIDE} for type {@code int}. */
  private static final Applicable Z_DIVIDE_INT =
      new ApplicableImpl("/") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (int) list.get(0) / (int) list.get(1);
        }
      };

  /** Implements {@link #OP_DIVIDE} for type {@code real}. */
  private static final Applicable Z_DIVIDE_REAL =
      new ApplicableImpl("/") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return (float) list.get(0) / (float) list.get(1);
        }
      };

  /** @see BuiltIn#OP_NEGATE */
  private static final Macro OP_NEGATE = (typeSystem, env, argType) -> {
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
  private static final Macro OP_DIVIDE = (typeSystem, env, argType) -> {
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
  private static final Applicable OP_DIV =
      new ApplicableImpl("div") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return Math.floorDiv((int) list.get(0), (int) list.get(1));
        }
      };

  /** @see BuiltIn#GENERAL_OP_O */
  private static final Applicable GENERAL_OP_O =
      new ApplicableImpl("o") {
        @Override public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("rawtypes") final List tuple = (List) arg;
          final Applicable f = (Applicable) tuple.get(0);
          final Applicable g = (Applicable) tuple.get(1);
          return new ApplicableImpl("o$f$g") {
            @Override public Object apply(EvalEnv env, Object arg) {
              return f.apply(env, g.apply(env, arg));
            }
          };
        }
      };

  /** @see BuiltIn#OP_CARET */
  private static final Applicable OP_CARET =
      new ApplicableImpl("^") {
        @Override public String apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final String arg0 = (String) tuple.get(0);
          final String arg1 = (String) tuple.get(1);
          return arg0 + arg1;
        }
      };

  /** @see BuiltIn#OP_CONS */
  private static final Applicable OP_CONS =
      new ApplicableImpl("::") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return ImmutableList.builder().add(list.get(0))
              .addAll((Iterable) list.get(1))
              .build();
        }
      };

  /** @see BuiltIn#OP_EXCEPT */
  private static final Applicable OP_EXCEPT =
      new ApplicableImpl("except") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final List list0 = (List) list.get(0);
          List collection = new ArrayList(list0);
          final Set set = new HashSet((List) list.get(1));
          if (!collection.removeAll(set)) {
            collection = list0;
          }
          return ImmutableList.copyOf(collection);
        }
      };

  /** @see BuiltIn#OP_INTERSECT */
  private static final Applicable OP_INTERSECT =
      new ApplicableImpl("intersect") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final List list0 = (List) list.get(0);
          List collection = new ArrayList(list0);
          final Set set = new HashSet((List) list.get(1));
          if (!collection.retainAll(set)) {
            collection = list0;
          }
          return ImmutableList.copyOf(collection);
        }
      };

  /** Returns a Code that returns the value of variable "name" in the current
   * environment. */
  public static Code get(String name) {
    return new GetCode(name);
  }

  /** Returns a Code that returns a tuple consisting of the values of variables
   * "name0", ... "nameN" in the current environment. */
  public static Code getTuple(Iterable<String> names) {
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

  /** Generates the code for applying a function (or function value) to an
   * argument. */
  public static Code apply(Code fnCode, Code argCode) {
    assert !fnCode.isConstant(); // if constant, use "apply(Closure, Code)"
    return new ApplyCodeCode(fnCode, argCode);
  }

  /** Generates the code for applying a function value to an argument. */
  public static Code apply(Applicable fnValue, Code argCode) {
    return new ApplyCode(fnValue, argCode);
  }

  public static Code list(Iterable<? extends Code> codes) {
    return tuple(codes);
  }

  public static Code tuple(Iterable<? extends Code> codes) {
    return new TupleCode(ImmutableList.copyOf(codes));
  }

  /** Returns an applicable that constructs an instance of a datatype.
   * The instance is a list with two elements [constructorName, value]. */
  public static Applicable tyCon(Type dataType, String name) {
    requireNonNull(dataType);
    requireNonNull(name);
    return new ApplicableImpl("tyCon") {
      @Override public Object apply(EvalEnv env, Object arg) {
        return ImmutableList.of(name, arg);
      }
    };
  }

  public static Code from(Map<Core.Pat, Code> sources,
      Supplier<RowSink> rowSinkFactory) {
    if (sources.size() == 0) {
      return new Code() {
        @Override public Describer describe(Describer describer) {
          return describer.start("from0", d -> {});
        }

        @Override public Object eval(EvalEnv env) {
          final RowSink rowSink = rowSinkFactory.get();
          rowSink.accept(env);
          return rowSink.result(env);
        }
      };
    }
    final ImmutableList<Core.Pat> pats = ImmutableList.copyOf(sources.keySet());
    final ImmutableList<Code> codes = ImmutableList.copyOf(sources.values());
    return new Code() {
      @Override public Describer describe(Describer describer) {
        return describer.start("from", d ->
            sources.forEach((pat, code) ->
                d.arg("", pat.toString())
                    .arg("", code)
                    .arg("sink", rowSinkFactory.get())));
      }

      @Override public Object eval(EvalEnv env) {
        final RowSink rowSink = rowSinkFactory.get();
        final Looper looper = new Looper(pats, codes, env, rowSink);
        looper.loop(0);
        return rowSink.result(env);
      }
    };
  }

  /** Creates a {@link RowSink} for a {@code where} clause. */
  public static RowSink whereRowSink(Code filterCode, RowSink rowSink) {
    return new WhereRowSink(filterCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code order} clause. */
  public static RowSink orderRowSink(ImmutableList<Pair<Code, Boolean>> codes,
      ImmutableList<Binding> bindings, RowSink rowSink) {
    @SuppressWarnings("UnstableApiUsage")
    final ImmutableList<String> labels = bindings.stream().map(b -> b.id.name)
        .collect(ImmutableList.toImmutableList());
    return new OrderRowSink(codes, labels, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code group} clause. */
  public static RowSink groupRowSink(Code keyCode,
      ImmutableList<Applicable> aggregateCodes, ImmutableList<String> inNames,
      ImmutableList<String> outNames, RowSink rowSink) {
    return new GroupRowSink(keyCode, aggregateCodes, inNames, outNames,
        rowSink);
  }

  /** Creates a {@link RowSink} for a non-terminal {@code yield} step. */
  public static RowSink yieldRowSink(Code yieldCode, RowSink rowSink) {
    return new YieldRowSink(yieldCode, rowSink);
  }

  /** Creates a {@link RowSink} to collect the results of a {@code from}
   * expression. */
  public static RowSink collectRowSink(Code code) {
    return new CollectRowSink(code);
  }

  /** Returns an applicable that returns the {@code slot}th field of a tuple or
   * record. */
  public static Applicable nth(int slot) {
    assert slot >= 0 : slot;
    return new ApplicableImpl("nth:" + slot) {
      @Override public Object apply(EvalEnv env, Object arg) {
        return ((List) arg).get(slot);
      }
    };
  }

  /** An applicable that negates a boolean value. */
  private static final Applicable NOT =
      new ApplicableImpl("not") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return !(Boolean) arg;
        }
      };

  /** An applicable that returns the absolute value of an int. */
  private static final Applicable ABS =
      new ApplicableImpl("abs") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Integer integer = (Integer) arg;
          return integer >= 0 ? integer : -integer;
        }
      };

  /** @see BuiltIn#IGNORE */
  private static final Applicable IGNORE =
      new ApplicableImpl("General.ignore") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#OP_MINUS */
  private static final Macro OP_MINUS = (typeSystem, env, argType) -> {
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
  private static final Applicable OP_MOD =
      new ApplicableImpl("mod") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return Math.floorMod((int) list.get(0), (int) list.get(1));
        }
      };

  /** @see BuiltIn#OP_PLUS */
  private static final Macro OP_PLUS = (typeSystem, env, argType) -> {
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
  private static final Macro OP_TIMES = (typeSystem, env, argType) -> {
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

  /** @see BuiltIn#STRING_SIZE */
  private static final Applicable STRING_SIZE =
      new ApplicableImpl("String.size") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return ((String) arg).length();
        }
      };

  /** @see BuiltIn#STRING_SUB */
  private static final Applicable STRING_SUB =
      new ApplicableImpl("String.sub") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final String s = (String) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          if (i < 0 || i >= s.length()) {
            throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
          }
          return s.charAt(i);
        }
      };

  /** @see BuiltIn#STRING_EXTRACT */
  private static final Applicable STRING_EXTRACT =
      new ApplicableImpl("String.extract") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final String s = (String) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          if (i < 0) {
            throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
          }
          final List jOpt = (List) tuple.get(2);
          if (jOpt.size() == 2) {
            final int j = (Integer) jOpt.get(1);
            if (j < 0 || i + j > s.length()) {
              throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
            }
            return s.substring(i, i + j);
          } else {
            if (i > s.length()) {
              throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
            }
            return s.substring(i);
          }
        }
      };

  /** @see BuiltIn#STRING_SUBSTRING */
  private static final Applicable STRING_SUBSTRING =
      new ApplicableImpl("String.substring") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final String s = (String) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          final int j = (Integer) tuple.get(2);
          if (i < 0 || j < 0 || i + j > s.length()) {
            throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
          }
          return s.substring(i, i + j);
        }
      };

  /** @see BuiltIn#STRING_CONCAT */
  private static final Applicable STRING_CONCAT =
      new ApplicableImpl("String.concat") {
        @SuppressWarnings("unchecked")
        @Override public Object apply(EvalEnv env, Object arg) {
          return stringConcat("", (List<String>) arg);
        }
      };

  /** @see BuiltIn#STRING_CONCAT_WITH */
  private static final Applicable STRING_CONCAT_WITH =
      new ApplicableImpl("String.concatWith") {
        @Override public Object apply(EvalEnv env, Object argValue) {
          final String separator = (String) argValue;
          return new ApplicableImpl("String.concatWith$separator") {
            @SuppressWarnings("unchecked")
            @Override public Object apply(EvalEnv env, Object arg) {
              return stringConcat(separator, (List<String>) arg);
            }
          };
        }
      };

  private static String stringConcat(String separator, List<String> list) {
    long n = 0;
    for (String s : list) {
      n += s.length();
      n += separator.length();
    }
    if (n > STRING_MAX_SIZE) {
      throw new MorelRuntimeException(BuiltInExn.SIZE);
    }
    return String.join(separator, list);
  }

  /** @see BuiltIn#STRING_STR */
  private static final Applicable STRING_STR =
      new ApplicableImpl("String.str") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Character character = (Character) arg;
          return character + "";
        }
      };

  /** @see BuiltIn#STRING_IMPLODE */
  private static final Applicable STRING_IMPLODE =
      new ApplicableImpl("String.implode") {
        @Override public Object apply(EvalEnv env, Object arg) {
          // Note: In theory this function should raise Size, but it is not
          // possible in practice because List.size() is never larger than
          // Integer.MAX_VALUE.
          return String.valueOf(Chars.toArray((List) arg));
        }
      };

  /** @see BuiltIn#STRING_EXPLODE */
  private static final Applicable STRING_EXPLODE =
      new ApplicableImpl("String.explode") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return MapList.of(s.length(), s::charAt);
        }
      };

  /** @see BuiltIn#STRING_MAP */
  private static final Applicable STRING_MAP =
      new ApplicableImpl("String.map") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return stringMap((Applicable) arg);
        }
      };

  private static Applicable stringMap(Applicable f) {
    return new ApplicableImpl("String.map$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("String.translate") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return translate(f);
        }
      };

  private static Applicable translate(Applicable f) {
    return new ApplicableImpl("String.translate$f") {
      @Override public String apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("String.isPrefix") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return isPrefix(s);
        }
      };

  private static Applicable isPrefix(String s) {
    return new ApplicableImpl("String.isPrefix$s") {
      @Override public Boolean apply(EvalEnv env, Object arg) {
        final String s2 = (String) arg;
        return s2.startsWith(s);
      }
    };
  }

  /** @see BuiltIn#STRING_IS_SUBSTRING */
  private static final Applicable STRING_IS_SUBSTRING =
      new ApplicableImpl("String.isSubstring") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return isSubstring(s);
        }
      };

  private static Applicable isSubstring(String s) {
    return new ApplicableImpl("String.isSubstring$s") {
      @Override public Boolean apply(EvalEnv env, Object arg) {
        final String s2 = (String) arg;
        return s2.contains(s);
      }
    };
  }

  /** @see BuiltIn#STRING_IS_SUFFIX */
  private static final Applicable STRING_IS_SUFFIX =
      new ApplicableImpl("String.isSuffix") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          final String s = (String) arg;
          return isSuffix(s);
        }
      };

  private static Applicable isSuffix(String s) {
    return new ApplicableImpl("String.isSuffix$s") {
      @Override public Boolean apply(EvalEnv env, Object arg) {
        final String s2 = (String) arg;
        return s2.endsWith(s);
      }
    };
  }

  /** @see BuiltIn#LIST_NULL */
  private static final Applicable LIST_NULL =
      new ApplicableImpl("List.null") {
        @Override public Boolean apply(EvalEnv env, Object arg) {
          return ((List) arg).isEmpty();
        }
      };

  /** @see BuiltIn#LIST_LENGTH */
  private static final Applicable LIST_LENGTH =
      new ApplicableImpl("List.length") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return ((List) arg).size();
        }
      };

  /** @see BuiltIn#LIST_AT
   * @see BuiltIn#OP_UNION */
  private static final Applicable LIST_AT =
      new ApplicableImpl("List.at") {
        @Override public List apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List list0 = (List) tuple.get(0);
          final List list1 = (List) tuple.get(1);
          return ImmutableList.builder().addAll(list0).addAll(list1).build();
        }
      };

  /** @see BuiltIn#LIST_HD */
  private static final Applicable LIST_HD =
      new ApplicableImpl("List.hd") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          if (list.isEmpty()) {
            throw new MorelRuntimeException(BuiltInExn.EMPTY);
          }
          return list.get(0);
        }
      };

  /** @see BuiltIn#LIST_TL */
  private static final Applicable LIST_TL =
      new ApplicableImpl("List.tl") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final int size = list.size();
          if (size == 0) {
            throw new MorelRuntimeException(BuiltInExn.EMPTY);
          }
          return list.subList(1, size);
        }
      };

  /** @see BuiltIn#LIST_LAST */
  private static final Applicable LIST_LAST =
      new ApplicableImpl("List.last") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final int size = list.size();
          if (size == 0) {
            throw new MorelRuntimeException(BuiltInExn.EMPTY);
          }
          return list.get(size - 1);
        }
      };

  /** @see BuiltIn#LIST_GET_ITEM */
  private static final Applicable LIST_GET_ITEM =
      new ApplicableImpl("List.getItem") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          if (list.isEmpty()) {
            return OPTION_NONE;
          } else {
            return optionSome(
                ImmutableList.of(list.get(0), list.subList(1, list.size())));
          }
        }
      };

  /** @see BuiltIn#LIST_NTH */
  private static final Applicable LIST_NTH =
      new ApplicableImpl("List.nth") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List list = (List) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          if (i < 0 || i >= list.size()) {
            throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
          }
          return list.get(i);
        }
      };

  /** @see BuiltIn#LIST_TAKE */
  private static final Applicable LIST_TAKE =
      new ApplicableImpl("List.take") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List list = (List) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          if (i < 0 || i > list.size()) {
            throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
          }
          return list.subList(0, i);
        }
      };

  /** @see BuiltIn#LIST_DROP */
  private static final Applicable LIST_DROP =
      new ApplicableImpl("List.drop") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List list = (List) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          return list.subList(i, list.size());
        }
      };

  /** @see BuiltIn#LIST_REV */
  private static final Applicable LIST_REV =
      new ApplicableImpl("List.rev") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          return Lists.reverse(list);
        }
      };

  /** @see BuiltIn#LIST_CONCAT */
  private static final Applicable LIST_CONCAT =
      new ApplicableImpl("List.concat") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List list = (List) arg;
          final ImmutableList.Builder<Object> builder = ImmutableList.builder();
          for (Object o : list) {
            builder.addAll((List) o);
          }
          return builder.build();
        }
      };

  /** @see BuiltIn#LIST_REV_APPEND */
  private static final Applicable LIST_REV_APPEND =
      new ApplicableImpl("List.revAppend") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List list0 = (List) tuple.get(0);
          final List list1 = (List) tuple.get(1);
          return ImmutableList.builder().addAll(Lists.reverse(list0))
              .addAll(list1).build();
        }
      };

  /** @see BuiltIn#LIST_APP */
  private static final Applicable LIST_APP =
      new ApplicableImpl("List.app") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          return listApp((Applicable) arg);
        }
      };

  private static Applicable listApp(Applicable consumer) {
    return new ApplicableImpl("List.app$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
        final List list = (List) arg;
        list.forEach(o -> consumer.apply(env, o));
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#LIST_MAP */
  private static final Applicable LIST_MAP =
      new ApplicableImpl("List.map") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          return listMap((Applicable) arg);
        }
      };

  private static Applicable listMap(Applicable fn) {
    return new ApplicableImpl("List.map$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("List.mapPartial") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          return listMapPartial((Applicable) arg);
        }
      };

  private static Applicable listMapPartial(Applicable f) {
    return new ApplicableImpl("List.mapPartial$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
  private static final Applicable LIST_FIND =
      new ApplicableImpl("List.find") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          final Applicable fn = (Applicable) arg;
          return find(fn);
        }
      };

  private static Applicable find(Applicable f) {
    return new ApplicableImpl("List.find$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("List.filter") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable fn = (Applicable) arg;
          return listFilter(fn);
        }
      };

  private static Applicable listFilter(Applicable f) {
    return new ApplicableImpl("List.filter$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("List.partition") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable fn = (Applicable) arg;
          return listPartition(fn);
        }
      };

  private static Applicable listPartition(Applicable f) {
    return new ApplicableImpl("List.partition$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("List.foldl") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return listFold(true, (Applicable) arg);
        }
      };

  /** @see BuiltIn#LIST_FOLDR */
  private static final Applicable LIST_FOLDR =
      new ApplicableImpl("List.foldr") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return listFold(false, (Applicable) arg);
        }
      };

  private static Applicable listFold(boolean left, Applicable f) {
    return new ApplicableImpl("List.fold$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
        return listFold2(left, f, arg);
      }
    };
  }

  private static Applicable listFold2(boolean left, Applicable f,
      Object init) {
    return new ApplicableImpl("List.fold$f$init") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
  private static final Applicable LIST_EXISTS =
      new ApplicableImpl("List.exists") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return listExists((Applicable) arg);
        }
      };

  private static Applicable listExists(Applicable f) {
    return new ApplicableImpl("List.exists$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
  private static final Applicable LIST_ALL =
      new ApplicableImpl("List.all") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return listAll((Applicable) arg);
        }
      };

  private static Applicable listAll(Applicable f) {
    return new ApplicableImpl("List.all$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("List.tabulate") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final int count = (Integer) tuple.get(0);
          if (count < 0) {
            throw new MorelRuntimeException(BuiltInExn.SIZE);
          }
          final Applicable fn = (Applicable) tuple.get(1);
          final ImmutableList.Builder<Object> builder = ImmutableList.builder();
          for (int i = 0; i < count; i++) {
            builder.add(fn.apply(env, i));
          }
          return builder.build();
        }
      };

  /** @see BuiltIn#LIST_COLLATE */
  private static final Applicable LIST_COLLATE =
      new ApplicableImpl("List.collate") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return collate((Applicable) arg);
        }
      };

  private static Applicable collate(Applicable comparator) {
    return new ApplicableImpl("List.collate$comparator") {
      @Override public Object apply(EvalEnv env, Object arg) {
        final List tuple = (List) arg;
        final List list0 = (List) tuple.get(0);
        final List list1 = (List) tuple.get(1);
        final int n0 = list0.size();
        final int n1 = list1.size();
        final int n = Math.min(n0, n1);
        for (int i = 0; i < n; i++) {
          final Object element0 = list0.get(i);
          final Object element1 = list1.get(i);
          final List compare = (List) comparator.apply(env,
              ImmutableList.of(element0, element1));
          if (!compare.get(0).equals("EQUAL")) {
            return compare;
          }
        }
        return n0 < n1 ? ORDER_LESS : n0 == n1 ? ORDER_EQUAL : ORDER_GREATER;
      }
    };
  }

  /** @see BuiltIn#OPTION_APP */
  private static final Applicable OPTION_APP =
      new ApplicableImpl("Option.app") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return optionApp(f);
        }
      };

  /** Implements {@link #OPTION_APP}. */
  private static Applicable optionApp(Applicable f) {
    return new ApplicableImpl("Option.app$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("Option.getOpt") {
        @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("Option.isSome") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List opt = (List) arg;
          return opt.size() == 2; // SOME has 2 elements, NONE has 1
        }
      };

  /** @see BuiltIn#OPTION_VAL_OF */
  private static final Applicable OPTION_VAL_OF =
      new ApplicableImpl("Option.valOf") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List opt = (List) arg;
          if (opt.size() == 2) { // SOME has 2 elements, NONE has 1
            return opt.get(1);
          } else {
            throw new MorelRuntimeException(BuiltInExn.OPTION);
          }
        }
      };

  /** @see BuiltIn#OPTION_FILTER */
  private static final Applicable OPTION_FILTER =
      new ApplicableImpl("Option.filter") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return optionFilter(f);
        }
      };

  /** Implementation of {@link #OPTION_FILTER}. */
  private static Applicable optionFilter(Applicable f) {
    return new ApplicableImpl("Option.filter$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("Option.join") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List opt = (List) arg;
          return opt.size() == 2
              ? opt.get(1) // SOME(SOME(v)) -> SOME(v), SOME(NONE) -> NONE
              : opt; // NONE -> NONE
        }
      };

  /** @see BuiltIn#OPTION_MAP */
  private static final Applicable OPTION_MAP =
      new ApplicableImpl("Option.map") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return optionMap((Applicable) arg);
        }
      };

  /** Implements {@link #OPTION_MAP}. */
  private static Applicable optionMap(Applicable f) {
    return new ApplicableImpl("Option.map") {
      @Override public Object apply(EvalEnv env, Object arg) {
        final List a = (List) arg;
        if (a.size() == 2) { // SOME v
          return optionSome(f.apply(env, a.get(1))); // SOME (f v)
        }
        return a; // NONE
      }
    };
  }

  /** Creates a value of {@code SOME v}.
   *
   * @see #OPTION_NONE */
  private static List optionSome(Object o) {
    return ImmutableList.of("SOME", o);
  }

  /** @see BuiltIn#OPTION_MAP_PARTIAL */
  private static final Applicable OPTION_MAP_PARTIAL =
      new ApplicableImpl("Option.mapPartial") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return optionMapPartial((Applicable) arg);
        }
      };

  /** Implements {@link #OPTION_MAP_PARTIAL}. */
  private static Applicable optionMapPartial(Applicable f) {
    return new ApplicableImpl("Option.mapPartial$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("Option.compose") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final Applicable f = (Applicable) tuple.get(0);
          final Applicable g = (Applicable) tuple.get(1);
          return optionCompose(f, g);
        }
      };

  /** Implements {@link #OPTION_COMPOSE}. */
  private static Applicable optionCompose(Applicable f, Applicable g) {
    return new ApplicableImpl("Option.compose$f$g") {
      @Override public Object apply(EvalEnv env, Object arg) {
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
      new ApplicableImpl("Option.composePartial") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final Applicable f = (Applicable) tuple.get(0);
          final Applicable g = (Applicable) tuple.get(1);
          return optionComposePartial(f, g);
        }
      };

  /** Implements {@link #OPTION_COMPOSE_PARTIAL}. */
  private static Applicable optionComposePartial(Applicable f, Applicable g) {
    return new ApplicableImpl("Option.composePartial$f$g") {
      @Override public Object apply(EvalEnv env, Object arg) {
        final List ga = (List) g.apply(env, arg); // g (a)
        if (ga.size() == 2) { // SOME v
          return f.apply(env, ga.get(1)); // f (v)
        }
        return ga; // NONE
      }
    };
  }

  /** @see BuiltIn#RELATIONAL_COUNT */
  private static final Applicable RELATIONAL_COUNT =
      new ApplicableImpl("Relational.count") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return ((List) arg).size();
        }
      };

  /** Implements {@link #RELATIONAL_SUM} for type {@code int list}. */
  private static final Applicable Z_SUM_INT =
      new ApplicableImpl("Relational.sum$int") {
        @Override public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("unchecked") final List<? extends Number> list =
              (List) arg;
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
        @Override public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("unchecked") final List<? extends Number> list =
              (List) arg;
          float sum = 0;
          for (Number o : list) {
            sum += o.floatValue();
          }
          return sum;
        }
      };

  /** @see BuiltIn#RELATIONAL_SUM */
  private static final Macro RELATIONAL_SUM = (typeSystem, env, argType) -> {
    if (argType instanceof ListType) {
      final Type resultType = ((ListType) argType).elementType;
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
      new ApplicableImpl("Relational.min") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return Ordering.natural().min((List) arg);
        }
      };

  /** @see BuiltIn#RELATIONAL_MAX */
  private static final Applicable RELATIONAL_MAX =
      new ApplicableImpl("Relational.max") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return Ordering.natural().max((List) arg);
        }
      };

  /** @see BuiltIn#SYS_ENV */
  private static Core.Exp sysEnv(TypeSystem typeSystem, Environment env,
      Type argType) {
    final List<Core.Tuple> args =
        env.getValueMap()
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry ->
                core.tuple(
                    typeSystem.tupleType(PrimitiveType.STRING,
                        PrimitiveType.STRING),
                    core.stringLiteral(entry.getKey()),
                    core.stringLiteral(entry.getValue().id.type.moniker())))
            .collect(Collectors.toList());
    return core.apply(typeSystem.listType(argType),
        core.functionLiteral(typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeSystem, null, args));
  }

  /** @see BuiltIn#SYS_PLAN */
  private static final Applicable SYS_PLAN =
      new ApplicableImpl("Sys.plan") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          return Codes.describe(session.code);
        }
      };

  /** @see BuiltIn#SYS_SET */
  private static final Applicable SYS_SET =
      new ApplicableImpl("Sys.plan") {
        @Override public Unit apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final List list = (List) arg;
          final String propName = (String) list.get(0);
          final Object value = list.get(1);
          Prop.lookup(propName).set(session.map, value);
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#SYS_SHOW */
  private static final Applicable SYS_SHOW =
      new ApplicableImpl("Sys.show") {
        @Override public List apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final String propName = (String) arg;
          final Object value = Prop.lookup(propName).get(session.map);
          return value == null ? OPTION_NONE : optionSome(value.toString());
        }
      };

  /** @see BuiltIn#SYS_UNSET */
  private static final Applicable SYS_UNSET =
      new ApplicableImpl("Sys.unset") {
        @Override public Unit apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final String propName = (String) arg;
          final Prop prop = Prop.lookup(propName);
          @SuppressWarnings("unused") final Object value =
              prop.remove(session.map);
          return Unit.INSTANCE;
        }
      };

  private static final List ORDER_LESS = ImmutableList.of("LESS");
  private static final List ORDER_EQUAL = ImmutableList.of("EQUAL");
  private static final List ORDER_GREATER = ImmutableList.of("GREATER");

  /** @see BuiltIn#VECTOR_MAX_LEN */
  private static final int VECTOR_MAX_LEN = (1 << 24) - 1;

  /** @see BuiltIn#VECTOR_FROM_LIST */
  private static final Applicable VECTOR_FROM_LIST =
      new ApplicableImpl("Vector.fromList") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return arg; // vector and list have the same implementation in Java
        }
      };

  /** @see BuiltIn#VECTOR_TABULATE */
  private static final Applicable VECTOR_TABULATE = LIST_TABULATE;

  /** @see BuiltIn#VECTOR_LENGTH */
  private static final Applicable VECTOR_LENGTH = LIST_LENGTH;

  /** @see BuiltIn#VECTOR_SUB */
  private static final Applicable VECTOR_SUB = LIST_NTH;

  /** @see BuiltIn#VECTOR_UPDATE */
  private static final Applicable VECTOR_UPDATE =
      new ApplicableImpl("Vector.update") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final List tuple = (List) arg;
          final List vec = (List) tuple.get(0);
          final int i = (Integer) tuple.get(1);
          if (i < 0 || i >= vec.size()) {
            throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
          }
          final Object x = tuple.get(2);
          final Object[] elements = vec.toArray();
          elements[i] = x;
          return ImmutableList.copyOf(elements);
        }
      };

  /** @see BuiltIn#VECTOR_CONCAT */
  private static final Applicable VECTOR_CONCAT =
      new ApplicableImpl("Vector.concat") {
        @Override public Object apply(EvalEnv env, Object arg) {
          @SuppressWarnings("unchecked") final List<List<Object>> lists =
              (List<List<Object>>) arg;
          final ImmutableList.Builder<Object> b = ImmutableList.builder();
          for (List<Object> list : lists) {
            b.addAll(list);
          }
          return b.build();
        }
      };

  /** @see BuiltIn#VECTOR_APPI */
  private static final Applicable VECTOR_APPI =
      new ApplicableImpl("Vector.appi") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return vectorAppi((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_APPI}. */
  private static Applicable vectorAppi(Applicable f) {
    return new ApplicableImpl("Vector.appi$f") {
      @Override public Unit apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked") final List<Object> vec =
            (List<Object>) arg;
        Ord.forEach(vec, (e, i) -> f.apply(env, FlatLists.of(i, e)));
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#VECTOR_APP */
  private static final Applicable VECTOR_APP =
      new ApplicableImpl("Vector.app") {
        @Override public Applicable apply(EvalEnv env, Object arg) {
          return vectorApp((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_APP}. */
  private static Applicable vectorApp(Applicable f) {
    return new ApplicableImpl("Vector.app$f") {
      @Override public Unit apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked") final List<Object> vec =
            (List<Object>) arg;
        vec.forEach(e -> f.apply(env, e));
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#VECTOR_MAPI */
  private static final Applicable VECTOR_MAPI =
      new ApplicableImpl("Vector.mapi") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return vectorMapi((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_MAPI}. */
  private static Applicable vectorMapi(Applicable f) {
    return new ApplicableImpl("Vector.map$f") {
      @Override public List apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked") final List<Object> vec =
            (List<Object>) arg;
        ImmutableList.Builder<Object> b = ImmutableList.builder();
        Ord.forEach(vec, (e, i) -> b.add(f.apply(env, FlatLists.of(i, e))));
        return b.build();
      }
    };
  }

  /** @see BuiltIn#VECTOR_MAP */
  private static final Applicable VECTOR_MAP =
      new ApplicableImpl("Vector.map") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return vectorMap((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_MAP}. */
  private static Applicable vectorMap(Applicable f) {
    return new ApplicableImpl("Vector.map$f") {
      @Override public List apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked") final List<Object> vec =
            (List<Object>) arg;
        ImmutableList.Builder<Object> b = ImmutableList.builder();
        vec.forEach(e -> b.add(f.apply(env, e)));
        return b.build();
      }
    };
  }

  /** @see BuiltIn#VECTOR_FOLDLI */
  private static final Applicable VECTOR_FOLDLI =
      new ApplicableImpl("Vector.foldli") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldli$f") {
            @Override public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldli$f$init") {
                @Override public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked") final List<Object> vec =
                      (List<Object>) arg3;
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
      new ApplicableImpl("Vector.foldri") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldri$f") {
            @Override public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldri$f$init") {
                @Override public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked") final List<Object> vec =
                      (List<Object>) arg3;
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
      new ApplicableImpl("Vector.foldl") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldl$f") {
            @Override public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldl$f$init") {
                @Override public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked") final List<Object> vec =
                      (List<Object>) arg3;
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
      new ApplicableImpl("Vector.foldr") {
        @Override public Object apply(EvalEnv env, Object arg) {
          final Applicable f = (Applicable) arg;
          return new ApplicableImpl("Vector.foldlr$f") {
            @Override public Object apply(EvalEnv env2, Object init) {
              return new ApplicableImpl("Vector.foldr$f$init") {
                @Override public Object apply(EvalEnv env3, Object arg3) {
                  @SuppressWarnings("unchecked") final List<Object> vec =
                      (List<Object>) arg3;
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
      new ApplicableImpl("Vector.findi") {
        @Override public Object apply(EvalEnv env, Object arg) {
          return vectorFindi((Applicable) arg);
        }
      };

  /** Implements {@link #VECTOR_FINDI}. */
  private static Applicable vectorFindi(Applicable f) {
    return new ApplicableImpl("Vector.findi$f") {
      @Override public Object apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked") final List<Object> vec =
            (List<Object>) arg;
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
  private static final Applicable VECTOR_FIND = LIST_FIND;

  /** @see BuiltIn#VECTOR_EXISTS */
  private static final Applicable VECTOR_EXISTS = LIST_EXISTS;

  /** @see BuiltIn#VECTOR_ALL */
  private static final Applicable VECTOR_ALL = LIST_ALL;

  /** @see BuiltIn#VECTOR_COLLATE */
  private static final Applicable VECTOR_COLLATE = LIST_COLLATE;

  /** @see BuiltIn#Z_LIST */
  private static final Applicable Z_LIST =
      new ApplicableImpl("$.list") {
        @Override public Object apply(EvalEnv env, Object arg) {
          assert arg instanceof List;
          return arg;
        }
      };

  private static void populateBuiltIns(Map<String, Object> valueMap) {
    // Dummy type system, thrown away after this method
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    BuiltIn.forEach(typeSystem, (key, type) -> {
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
    //noinspection UnstableApiUsage
    BuiltIn.forEachStructure(typeSystem, (structure, type) ->
        valueMap.put(structure.name,
            structure.memberMap.values().stream()
                .map(BUILT_IN_VALUES::get)
                .collect(ImmutableList.toImmutableList())));
  }

  /** Creates an empty evaluation environment. */
  public static EvalEnv emptyEnv() {
    final Map<String, Object> map = new HashMap<>();
    populateBuiltIns(map);
    return EvalEnvs.copyOf(map);
  }

  /** Creates an evaluation environment that contains the bound values from a
   * compilation environment. */
  public static EvalEnv emptyEnvWith(Session session, Environment env) {
    final Map<String, Object> map = new HashMap<>();
    populateBuiltIns(map);
    env.forEachValue(map::put);
    map.put(EvalEnv.SESSION, session);
    return EvalEnvs.copyOf(map);
  }

  /** Creates a compilation environment. */
  public static Environment env(TypeSystem typeSystem,
      Environment environment) {
    final Environment[] hEnv = {environment};
    BUILT_IN_VALUES.forEach((key, value) -> {
      final Type type = key.typeFunction.apply(typeSystem);
      if (key.structure == null) {
        final Core.IdPat idPat =
            core.idPat(type, key.mlName, typeSystem.nameGenerator);
        hEnv[0] = hEnv[0].bind(idPat, value);
      }
      if (key.alias != null) {
        final Core.IdPat idPat =
            core.idPat(type, key.alias, typeSystem.nameGenerator);
        hEnv[0] = hEnv[0].bind(idPat, value);
      }
    });

    final List<Object> valueList = new ArrayList<>();
    BuiltIn.forEachStructure(typeSystem, (structure, type) -> {
      valueList.clear();
      structure.memberMap.values()
          .forEach(builtIn -> valueList.add(BUILT_IN_VALUES.get(builtIn)));
      final Core.IdPat idPat =
          core.idPat(type, structure.name, typeSystem.nameGenerator);
      hEnv[0] = hEnv[0].bind(idPat, ImmutableList.copyOf(valueList));
    });
    return hEnv[0];
  }

  public static Applicable aggregate(Environment env0, Code aggregateCode,
      List<String> names, @Nullable Code argumentCode) {
    return new ApplicableImpl("aggregate") {
      @Override public Object apply(EvalEnv env, Object arg) {
        @SuppressWarnings("unchecked") final List<Object> rows =
            (List<Object>) arg;
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
          argRows = Lists.transform(rows,
              row -> Arrays.asList((Object []) row));
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
          .put(BuiltIn.GENERAL_OP_O, GENERAL_OP_O)
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
          .put(BuiltIn.OP_NE, OP_NE)
          .put(BuiltIn.OP_MINUS, OP_MINUS)
          .put(BuiltIn.OP_MOD, OP_MOD)
          .put(BuiltIn.OP_NEGATE, OP_NEGATE)
          .put(BuiltIn.OP_NOT_ELEM, OP_NOT_ELEM)
          .put(BuiltIn.OP_PLUS, OP_PLUS)
          .put(BuiltIn.OP_TIMES, OP_TIMES)
          .put(BuiltIn.OP_EXCEPT, OP_EXCEPT)
          .put(BuiltIn.OP_INTERSECT, OP_INTERSECT)
          .put(BuiltIn.OP_UNION, LIST_AT) // union == @
          .put(BuiltIn.STRING_MAX_SIZE, STRING_MAX_SIZE)
          .put(BuiltIn.STRING_SIZE, STRING_SIZE)
          .put(BuiltIn.STRING_SUB, STRING_SUB)
          .put(BuiltIn.STRING_EXTRACT, STRING_EXTRACT)
          .put(BuiltIn.STRING_SUBSTRING, STRING_SUBSTRING)
          .put(BuiltIn.STRING_CONCAT, STRING_CONCAT)
          .put(BuiltIn.STRING_CONCAT_WITH, STRING_CONCAT_WITH)
          .put(BuiltIn.STRING_STR, STRING_STR)
          .put(BuiltIn.STRING_IMPLODE, STRING_IMPLODE)
          .put(BuiltIn.STRING_EXPLODE, STRING_EXPLODE)
          .put(BuiltIn.STRING_MAP, STRING_MAP)
          .put(BuiltIn.STRING_TRANSLATE, STRING_TRANSLATE)
          .put(BuiltIn.STRING_IS_PREFIX, STRING_IS_PREFIX)
          .put(BuiltIn.STRING_IS_SUBSTRING, STRING_IS_SUBSTRING)
          .put(BuiltIn.STRING_IS_SUFFIX, STRING_IS_SUFFIX)
          .put(BuiltIn.LIST_NIL, ImmutableList.of())
          .put(BuiltIn.LIST_NULL, LIST_NULL)
          .put(BuiltIn.LIST_LENGTH, LIST_LENGTH)
          .put(BuiltIn.LIST_AT, LIST_AT)
          .put(BuiltIn.LIST_OP_AT, LIST_AT) // op @ == List.at
          .put(BuiltIn.LIST_HD, LIST_HD)
          .put(BuiltIn.LIST_TL, LIST_TL)
          .put(BuiltIn.LIST_LAST, LIST_LAST)
          .put(BuiltIn.LIST_GET_ITEM, LIST_GET_ITEM)
          .put(BuiltIn.LIST_NTH, LIST_NTH)
          .put(BuiltIn.LIST_TAKE, LIST_TAKE)
          .put(BuiltIn.LIST_DROP, LIST_DROP)
          .put(BuiltIn.LIST_REV, LIST_REV)
          .put(BuiltIn.LIST_CONCAT, LIST_CONCAT)
          .put(BuiltIn.LIST_REV_APPEND, LIST_REV_APPEND)
          .put(BuiltIn.LIST_APP, LIST_APP)
          .put(BuiltIn.LIST_MAP, LIST_MAP)
          .put(BuiltIn.LIST_MAP_PARTIAL, LIST_MAP_PARTIAL)
          .put(BuiltIn.LIST_FIND, LIST_FIND)
          .put(BuiltIn.LIST_FILTER, LIST_FILTER)
          .put(BuiltIn.LIST_PARTITION, LIST_PARTITION)
          .put(BuiltIn.LIST_FOLDL, LIST_FOLDL)
          .put(BuiltIn.LIST_FOLDR, LIST_FOLDR)
          .put(BuiltIn.LIST_EXISTS, LIST_EXISTS)
          .put(BuiltIn.LIST_ALL, LIST_ALL)
          .put(BuiltIn.LIST_TABULATE, LIST_TABULATE)
          .put(BuiltIn.LIST_COLLATE, LIST_COLLATE)
          .put(BuiltIn.OPTION_APP, OPTION_APP)
          .put(BuiltIn.OPTION_COMPOSE, OPTION_COMPOSE)
          .put(BuiltIn.OPTION_COMPOSE_PARTIAL, OPTION_COMPOSE_PARTIAL)
          .put(BuiltIn.OPTION_FILTER, OPTION_FILTER)
          .put(BuiltIn.OPTION_GET_OPT, OPTION_GET_OPT)
          .put(BuiltIn.OPTION_IS_SOME, OPTION_IS_SOME)
          .put(BuiltIn.OPTION_JOIN, OPTION_JOIN)
          .put(BuiltIn.OPTION_MAP, OPTION_MAP)
          .put(BuiltIn.OPTION_MAP_PARTIAL, OPTION_MAP_PARTIAL)
          .put(BuiltIn.OPTION_VAL_OF, OPTION_VAL_OF)
          .put(BuiltIn.RELATIONAL_COUNT, RELATIONAL_COUNT)
          .put(BuiltIn.RELATIONAL_MAX, RELATIONAL_MAX)
          .put(BuiltIn.RELATIONAL_MIN, RELATIONAL_MIN)
          .put(BuiltIn.RELATIONAL_SUM, RELATIONAL_SUM)
          .put(BuiltIn.SYS_ENV, (Macro) Codes::sysEnv)
          .put(BuiltIn.SYS_PLAN, SYS_PLAN)
          .put(BuiltIn.SYS_SET, SYS_SET)
          .put(BuiltIn.SYS_SHOW, SYS_SHOW)
          .put(BuiltIn.SYS_UNSET, SYS_UNSET)
          .put(BuiltIn.VECTOR_MAX_LEN, VECTOR_MAX_LEN)
          .put(BuiltIn.VECTOR_FROM_LIST, VECTOR_FROM_LIST)
          .put(BuiltIn.VECTOR_TABULATE, VECTOR_TABULATE)
          .put(BuiltIn.VECTOR_LENGTH, VECTOR_LENGTH)
          .put(BuiltIn.VECTOR_SUB, VECTOR_SUB)
          .put(BuiltIn.VECTOR_UPDATE, VECTOR_UPDATE)
          .put(BuiltIn.VECTOR_CONCAT, VECTOR_CONCAT)
          .put(BuiltIn.VECTOR_APPI, VECTOR_APPI)
          .put(BuiltIn.VECTOR_APP, VECTOR_APP)
          .put(BuiltIn.VECTOR_MAPI, VECTOR_MAPI)
          .put(BuiltIn.VECTOR_MAP, VECTOR_MAP)
          .put(BuiltIn.VECTOR_FOLDLI, VECTOR_FOLDLI)
          .put(BuiltIn.VECTOR_FOLDRI, VECTOR_FOLDRI)
          .put(BuiltIn.VECTOR_FOLDL, VECTOR_FOLDL)
          .put(BuiltIn.VECTOR_FOLDR, VECTOR_FOLDR)
          .put(BuiltIn.VECTOR_FINDI, VECTOR_FINDI)
          .put(BuiltIn.VECTOR_FIND, VECTOR_FIND)
          .put(BuiltIn.VECTOR_EXISTS, VECTOR_EXISTS)
          .put(BuiltIn.VECTOR_ALL, VECTOR_ALL)
          .put(BuiltIn.VECTOR_COLLATE, VECTOR_COLLATE)
          .put(BuiltIn.Z_ANDALSO, Unit.INSTANCE)
          .put(BuiltIn.Z_ORELSE, Unit.INSTANCE)
          .put(BuiltIn.Z_NEGATE_INT, Z_NEGATE_INT)
          .put(BuiltIn.Z_NEGATE_REAL, Z_NEGATE_REAL)
          .put(BuiltIn.Z_DIVIDE_INT, Z_DIVIDE_INT)
          .put(BuiltIn.Z_DIVIDE_REAL, Z_DIVIDE_REAL)
          .put(BuiltIn.Z_PLUS_INT, Z_PLUS_INT)
          .put(BuiltIn.Z_PLUS_REAL, Z_PLUS_REAL)
          .put(BuiltIn.Z_MINUS_INT, Z_MINUS_INT)
          .put(BuiltIn.Z_MINUS_REAL, Z_MINUS_REAL)
          .put(BuiltIn.Z_TIMES_INT, Z_TIMES_INT)
          .put(BuiltIn.Z_TIMES_REAL, Z_TIMES_REAL)
          .put(BuiltIn.Z_SUM_INT, Z_SUM_INT)
          .put(BuiltIn.Z_SUM_REAL, Z_SUM_REAL)
          .put(BuiltIn.Z_LIST, Z_LIST)
          .build();

  public static final Map<Applicable, BuiltIn> BUILT_IN_MAP =
      ((Supplier<Map<Applicable, BuiltIn>>) Codes::get).get();

  private static Map<Applicable, BuiltIn> get() {
    final IdentityHashMap<Applicable, BuiltIn> b = new IdentityHashMap<>();
    BUILT_IN_VALUES.forEach((builtIn, o) -> {
      if (o instanceof Applicable) {
        b.put((Applicable) o, builtIn);
      }
    });
    return ImmutableMap.copyOf(b);
  }

  /** A code that evaluates expressions and creates a tuple with the results.
   *
   * <p>An inner class so that we can pick apart the results of multiply
   * defined functions: {@code fun f = ... and g = ...}.
   */
  public static class TupleCode implements Code {
    public final List<Code> codes;

    private TupleCode(ImmutableList<Code> codes) {
      this.codes = codes;
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("tuple", d ->
          codes.forEach(code -> d.arg("", code)));
    }

    public Object eval(EvalEnv env) {
      final Object[] values = new Object[codes.size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = codes.get(i).eval(env);
      }
      return Arrays.asList(values);
    }
  }

  /** Implements the {@code from} clause, iterating over several variables
   * and then calling a {@link RowSink} to complete the next step or steps. */
  private static class Looper {
    final List<Iterable<Object>> iterables = new ArrayList<>();
    final List<MutableEvalEnv> mutableEvalEnvs = new ArrayList<>();
    private final ImmutableList<Code> codes;
    private final RowSink rowSink;

    Looper(ImmutableList<Core.Pat> pats, ImmutableList<Code> codes, EvalEnv env,
        RowSink rowSink) {
      this.codes = codes;
      this.rowSink = rowSink;
      for (Core.Pat pat : pats) {
        final MutableEvalEnv mutableEnv = env.bindMutablePat(pat);
        mutableEvalEnvs.add(mutableEnv);
        env = mutableEnv;
        iterables.add(null);
      }
      //noinspection unchecked
      iterables.set(0, (Iterable<Object>) codes.get(0).eval(env));
    }

    /** Generates the {@code i}th nested loop of a cartesian product of the
     * values in {@code iterables}. */
    void loop(int i) {
      final Iterable<Object> iterable = iterables.get(i);
      final MutableEvalEnv mutableEvalEnv = mutableEvalEnvs.get(i);
      final int next = i + 1;
      if (next == iterables.size()) {
        for (Object o : iterable) {
          if (mutableEvalEnv.setOpt(o)) {
            rowSink.accept(mutableEvalEnv);
          }
        }
      } else {
        for (Object o : iterable) {
          if (mutableEvalEnv.setOpt(o)) {
            //noinspection unchecked
            iterables.set(next, (Iterable<Object>)
                codes.get(next).eval(mutableEvalEnvs.get(next)));
            loop(next);
          }
        }
      }
    }
  }

  /** Accepts rows produced by a supplier as part of a {@code from} clause. */
  public interface RowSink extends Describable {
    void accept(EvalEnv env);
    List<Object> result(EvalEnv env);
  }

  /** Implementation of {@link RowSink} for a {@code where} clause. */
  static class WhereRowSink implements RowSink {
    final Code filterCode;
    final RowSink rowSink;

    WhereRowSink(Code filterCode, RowSink rowSink) {
      this.filterCode = filterCode;
      this.rowSink = rowSink;
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("where", d ->
          d.arg("condition", filterCode)
              .arg("sink", rowSink));
    }

    public void accept(EvalEnv env) {
      if ((Boolean) filterCode.eval(env)) {
        rowSink.accept(env);
      }
    }

    public List<Object> result(EvalEnv env) {
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code group} clause. */
  private static class GroupRowSink implements RowSink {
    final Code keyCode;
    final ImmutableList<String> inNames;
    /** group names followed by aggregate names */
    final ImmutableList<String> outNames;
    final ImmutableList<Applicable> aggregateCodes;
    final RowSink rowSink;
    final ListMultimap<Object, Object> map = ArrayListMultimap.create();
    final Object[] values;

    GroupRowSink(Code keyCode, ImmutableList<Applicable> aggregateCodes,
        ImmutableList<String> inNames, ImmutableList<String> outNames,
        RowSink rowSink) {
      this.keyCode = requireNonNull(keyCode);
      this.aggregateCodes = requireNonNull(aggregateCodes);
      this.inNames = requireNonNull(inNames);
      this.outNames = requireNonNull(outNames);
      this.rowSink = requireNonNull(rowSink);
      this.values = inNames.size() == 1 ? null : new Object[inNames.size()];
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("group", d -> {
        d.arg("key", keyCode);
        aggregateCodes.forEach(a -> d.arg("agg", a));
        d.arg("sink", rowSink);
      });
    }

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

    public List<Object> result(final EvalEnv env) {
      EvalEnv env2 = env;
      final MutableEvalEnv[] groupEnvs = new MutableEvalEnv[outNames.size()];
      int i = 0;
      for (String name : outNames) {
        env2 = groupEnvs[i++] = env2.bindMutable(name);
      }
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
          groupEnvs[i++].set(aggregateCode.apply(env, rows));
        }
        rowSink.accept(env2);
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for an {@code order} clause. */
  static class OrderRowSink implements RowSink {
    final List<Pair<Code, Boolean>> codes;
    final ImmutableList<String> names;
    final RowSink rowSink;
    final List<Object> rows = new ArrayList<>();
    final Object[] values;

    OrderRowSink(List<Pair<Code, Boolean>> codes,
        ImmutableList<String> names, RowSink rowSink) {
      this.codes = codes;
      this.names = names;
      this.rowSink = rowSink;
      this.values = names.size() == 1 ? null : new Object[names.size()];
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("order", d -> {
        codes.forEach(kv -> d.arg(kv.right ? "desc" : "asc", kv.left));
        Pair.forEach(codes, (k, v) -> d.arg(v ? "desc" : "asc", k));
        d.arg("sink", rowSink);
      });
    }

    public void accept(EvalEnv env) {
      if (names.size() == 1) {
        rows.add(env.getOpt(names.get(0)));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        rows.add(values.clone());
      }
    }

    public List<Object> result(final EvalEnv env) {
      final MutableEvalEnv leftEnv = env.bindMutableArray(names);
      final MutableEvalEnv rightEnv = env.bindMutableArray(names);
      rows.sort((left, right) -> {
        leftEnv.set(left);
        rightEnv.set(right);
        for (Pair<Code, Boolean> code : codes) {
          final Comparable leftVal = (Comparable) code.left.eval(leftEnv);
          final Comparable rightVal = (Comparable) code.left.eval(rightEnv);
          int c = leftVal.compareTo(rightVal);
          if (c != 0) {
            return code.right ? -c : c;
          }
        }
        return 0;
      });
      for (Object row : rows) {
        leftEnv.set(row);
        rowSink.accept(leftEnv);
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code yield} step.
   *
   * <p>If this is the last step, use instead a {@link CollectRowSink}. It
   * is more efficient; there is no downstream row sink; and a terminal yield
   * step is allowed to generate expressions that are not records. Non-record
   * expressions (e.g. {@code int} expressions) do not have a name, and
   * therefore the value cannot be passed via the {@link EvalEnv}. */
  private static class YieldRowSink implements RowSink {
    private final Code yieldCode;
    private final RowSink rowSink;

    YieldRowSink(Code yieldCode, RowSink rowSink) {
      this.yieldCode = yieldCode;
      this.rowSink = rowSink;
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("yield", d ->
          d.arg("code", yieldCode)
              .arg("sink", rowSink));
    }

    @Override public void accept(EvalEnv env) {
      yieldCode.eval(env);
      rowSink.accept(env);
    }

    @Override public List<Object> result(EvalEnv env) {
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} that the last step of a {@code from}
   * writes into. */
  private static class CollectRowSink implements RowSink {
    final List<Object> list = new ArrayList<>();
    final Code code;

    CollectRowSink(Code code) {
      this.code = requireNonNull(code);
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("collect", d -> d.arg("", code));
    }

    @Override public void accept(EvalEnv env) {
      list.add(code.eval(env));
    }

    @Override public List<Object> result(EvalEnv env) {
      return list;
    }
  }

  /** Code that retrieves the value of a variable from the environment. */
  private static class GetCode implements Code {
    private final String name;

    GetCode(String name) {
      this.name = requireNonNull(name);
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("get", d -> d.arg("name", name));
    }

    @Override public String toString() {
      return "get(" + name + ")";
    }

    public Object eval(EvalEnv env) {
      return env.getOpt(name);
    }
  }

  /** Code that retrieves, as a tuple, the value of several variables from the
   * environment. */
  private static class GetTupleCode implements Code {
    private final ImmutableList<String> names;
    private final Object[] values; // work space

    GetTupleCode(ImmutableList<String> names) {
      this.names = requireNonNull(names);
      this.values = new Object[names.size()];
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("getTuple", d -> d.arg("names", names));
    }

    @Override public String toString() {
      return "getTuple(" + names + ")";
    }

    @Override public Object eval(EvalEnv env) {
      for (int i = 0; i < names.size(); i++) {
        values[i] = env.getOpt(names.get(i));
      }
      return Arrays.asList(values.clone());
    }
  }

  /** Java exception that wraps an exception thrown by the Morel runtime. */
  public static class MorelRuntimeException extends RuntimeException {
    private final BuiltInExn e;

    /** Creates a MorelRuntimeException. */
    public MorelRuntimeException(BuiltInExn e) {
      this.e = requireNonNull(e);
    }

    public StringBuilder describeTo(StringBuilder buf) {
      return buf.append("uncaught exception ")
          .append(e.mlName);
    }
  }

  /** Definitions of Morel built-in exceptions. */
  public enum BuiltInExn {
    EMPTY("List", "Empty"),
    OPTION("Option", "Option"),
    SIZE("General", "Size"),
    SUBSCRIPT("General", "Subscript [subscript out of bounds]");

    public final String structure;
    public final String mlName;

    BuiltInExn(String structure, String mlName) {
      this.structure = structure;
      this.mlName = mlName;
    }
  }

  /** Code that implements a constant. */
  @SuppressWarnings("rawtypes")
  private static class ConstantCode implements Code {
    private final Object value;

    ConstantCode(Object value) {
      this.value = value;
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("constant", d -> d.arg("", value));
    }

    public Object eval(EvalEnv env) {
      return value;
    }

    @Override public boolean isConstant() {
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

    @Override public Describer describe(Describer describer) {
      return describer.start("andalso", d -> d.arg("", code0).arg("", code1));
    }

    @Override public Object eval(EvalEnv evalEnv) {
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

    @Override public Describer describe(Describer describer) {
      return describer.start("orelse", d -> d.arg("", code0).arg("", code1));
    }

    @Override public Object eval(EvalEnv evalEnv) {
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

    @Override public Describer describe(Describer describer) {
      return describer.start("let1", d ->
          d.arg("matchCode", matchCode).arg("resultCode", resultCode));
    }

    @Override public Object eval(EvalEnv evalEnv) {
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

    @Override public Describer describe(Describer describer) {
      return describer.start("let", d -> {
        Ord.forEach(matchCodes, (matchCode, i) ->
            d.arg("matchCode" + i, matchCode));
        d.arg("resultCode", resultCode);
      });
    }

    @Override public Object eval(EvalEnv evalEnv) {
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

    @Override public Object eval(EvalEnv env) {
      final Object arg = argCode.eval(env);
      return fnValue.apply(env, arg);
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("apply", d ->
          d.arg("fnValue", fnValue).arg("argCode", argCode));
    }
  }

  /** Applies a {@link Code} to a {@link Code}.
   *
   * <p>If {@link #fnCode} is constant, you should use {@link ApplyCode}
   * instead. */
  static class ApplyCodeCode implements Code {
    public final Code fnCode;
    public final Code argCode;

    ApplyCodeCode(Code fnCode, Code argCode) {
      this.fnCode = fnCode;
      this.argCode = argCode;
    }

    @Override public Describer describe(Describer describer) {
      return describer.start("apply",
          d -> d.arg("fnCode", fnCode).arg("argCode", argCode));
    }

    @Override public Object eval(EvalEnv env) {
      final Applicable fnValue = (Applicable) fnCode.eval(env);
      final Object arg = argCode.eval(env);
      return fnValue.apply(env, arg);
    }
  }
}

// End Codes.java
