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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Chars;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Pos;
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
import net.hydromatic.morel.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.AstBuilder.ast;

/** Helpers for {@link Code}. */
public abstract class Codes {
  private Codes() {}

  /** Value of {@code NONE}.
   *
   * @see #optionSome(Object) */
  private static final List OPTION_NONE = ImmutableList.of("NONE");

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Comparable value) {
    return new Code() {
      public Object eval(EvalEnv env) {
        return value;
      }

      @Override public boolean isConstant() {
        return true;
      }
    };
  }

  /** @see BuiltIn#OP_EQ */
  private static final Applicable OP_EQ = Codes::eq;

  /** Implements {@link #OP_EQ}. */
  private static boolean eq(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return list.get(0).equals(list.get(1));
  }

  /** @see BuiltIn#OP_NE */
  private static final Applicable OP_NE = Codes::ne;

  /** Implements {@link #OP_NE}. */
  private static boolean ne(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return !list.get(0).equals(list.get(1));
  }

  /** @see BuiltIn#OP_LT */
  private static final Applicable OP_LT = Codes::lt;

  /** Implements {@link #OP_LT}. */
  private static boolean lt(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final Comparable v0 = (Comparable) list.get(0);
    final Comparable v1 = (Comparable) list.get(1);
    return v0.compareTo(v1) < 0;
  }

  /** @see BuiltIn#OP_GT */
  private static final Applicable OP_GT = Codes::gt;

  /** Implements {@link #OP_GT}. */
  private static boolean gt(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final Comparable v0 = (Comparable) list.get(0);
    final Comparable v1 = (Comparable) list.get(1);
    return v0.compareTo(v1) > 0;
  }

  /** @see BuiltIn#OP_LE */
  private static final Applicable OP_LE = Codes::le;

  /** Implements {@link #OP_LE}. */
  private static boolean le(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final Comparable v0 = (Comparable) list.get(0);
    final Comparable v1 = (Comparable) list.get(1);
    return v0.compareTo(v1) <= 0;
  }

  /** @see BuiltIn#OP_GE */
  private static final Applicable OP_GE = Codes::ge;

  /** Implements {@link #OP_GE}. */
  private static boolean ge(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final Comparable v0 = (Comparable) list.get(0);
    final Comparable v1 = (Comparable) list.get(1);
    return v0.compareTo(v1) >= 0;
  }

  /** @see BuiltIn#OP_ELEM */
  private static final Applicable OP_ELEM = Codes::elem;

  /** Implements {@link #OP_ELEM}. */
  private static boolean elem(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final Object v0 = list.get(0);
    final List v1 = (List) list.get(1);
    return v1.contains(v0);
  }

  /** @see BuiltIn#OP_NOT_ELEM */
  private static final Applicable OP_NOT_ELEM = Codes::notElem;

  /** Implements {@link #OP_NOT_ELEM}. */
  private static boolean notElem(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final Object v0 = list.get(0);
    final List v1 = (List) list.get(1);
    return !v1.contains(v0);
  }

  /** Returns a Code that evaluates "andalso". */
  public static Code andAlso(Code code0, Code code1) {
    // Lazy evaluation. If code0 returns false, code1 is never evaluated.
    return env -> (boolean) code0.eval(env) && (boolean) code1.eval(env);
  }

  /** Returns a Code that evaluates "orelse". */
  public static Code orElse(Code code0, Code code1) {
    // Lazy evaluation. If code0 returns true, code1 is never evaluated.
    return env -> (boolean) code0.eval(env) || (boolean) code1.eval(env);
  }

  /** Implements {@link #OP_PLUS} for type {@code int}. */
  private static int plusInt(EvalEnv env, Object arg) {
    return (int) ((List) arg).get(0) + (int) ((List) arg).get(1);
  }

  /** Implements {@link #OP_PLUS} for type {@code real}. */
  private static float plusReal(EvalEnv env, Object arg) {
    return (float) ((List) arg).get(0) + (float) ((List) arg).get(1);
  }

  /** Implements {@link #OP_MINUS} for type {@code int}. */
  private static int minusInt(EvalEnv env, Object arg) {
    return (int) ((List) arg).get(0) - (int) ((List) arg).get(1);
  }

  /** Implements {@link #OP_MINUS} for type {@code real}. */
  private static float minusReal(EvalEnv env, Object arg) {
    return (float) ((List) arg).get(0) - (float) ((List) arg).get(1);
  }

  /** Implements {@link #OP_MOD}. */
  private static int mod(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return Math.floorMod((int) list.get(0), (int) list.get(1));
  }

  /** Implements {@link #OP_TIMES} for type {@code int}. */
  private static int timesInt(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return (int) list.get(0) * (int) list.get(1);
  }

  /** Implements {@link #OP_TIMES} for type {@code real}. */
  private static float timesReal(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return (float) list.get(0) * (float) list.get(1);
  }

  /** @see BuiltIn#OP_DIVIDE */
  private static final Macro OP_DIVIDE = (env, argType) -> {
    switch ((PrimitiveType) ((TupleType) argType).argTypes.get(0)) {
    case INT:
      return ast.wrapApplicable(Codes::divideInt);
    case REAL:
      return ast.wrapApplicable(Codes::divideReal);
    default:
      throw new AssertionError("bad type " + argType);
    }
  };

  /** Implements {@link #OP_DIVIDE} for type {@code int}. */
  private static int divideInt(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return (int) list.get(0) / (int) list.get(1);
  }

  /** Implements {@link #OP_DIVIDE} for type {@code real}. */
  private static float divideReal(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return (float) list.get(0) / (float) list.get(1);
  }

  /** @see BuiltIn#OP_DIV */
  private static final Applicable OP_DIV = Codes::div;

  /** Implements {@link #OP_DIV}. */
  private static int div(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return Math.floorDiv((int) list.get(0), (int) list.get(1));
  }

  /** @see BuiltIn#GENERAL_OP_O */
  private static final Applicable GENERAL_OP_O = Codes::compose;

  /** Implements {@link #GENERAL_OP_O}. */
  private static Applicable compose(EvalEnv evalEnv, Object arg) {
    @SuppressWarnings("rawtypes") final List list = (List) arg;
    final Applicable f = (Applicable) list.get(0);
    final Applicable g = (Applicable) list.get(1);
    return (evalEnv2, arg2) -> f.apply(evalEnv2, g.apply(evalEnv2, arg2));
  }

  /** @see BuiltIn#OP_CARET */
  private static final Applicable OP_CARET = Codes::caret;

  /** Implements {@link #OP_CARET}. */
  private static String caret(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return (String) list.get(0) + (String) list.get(1);
  }

  /** @see BuiltIn#OP_CONS */
  private static final Applicable OP_CONS = Codes::cons;

  /** Implements {@link #OP_CONS}. */
  private static List cons(EvalEnv env, Object arg) {
    final List list = (List) arg;
    return ImmutableList.builder().add(list.get(0))
        .addAll((Iterable) list.get(1))
        .build();
  }

  /** @see BuiltIn#OP_EXCEPT */
  private static final Applicable OP_EXCEPT = Codes::except;

  /** Implements {@link #OP_EXCEPT}. */
  private static List except(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final List list0 = (List) list.get(0);
    List collection = new ArrayList(list0);
    final Set set = new HashSet((List) list.get(1));
    if (!collection.removeAll(set)) {
      collection = list0;
    }
    return ImmutableList.copyOf(collection);
  }

  /** @see BuiltIn#OP_INTERSECT */
  private static final Applicable OP_INTERSECT = Codes::intersect;

  /** Implements {@link #OP_INTERSECT}. */
  private static List intersect(EvalEnv env, Object arg) {
    final List list = (List) arg;
    final List list0 = (List) list.get(0);
    List collection = new ArrayList(list0);
    final Set set = new HashSet((List) list.get(1));
    if (!collection.retainAll(set)) {
      collection = list0;
    }
    return ImmutableList.copyOf(collection);
  }

  /** Returns a Code that returns the value of variable "name" in the current
   * environment. */
  public static Code get(String name) {
    return new GetCode(name);
  }

  public static Code let(List<Code> fnCodes, Code argCode) {
    switch (fnCodes.size()) {
    case 0:
      return argCode;

    case 1:
      // Use a more efficient runtime path if the list has only one element.
      // The effect is the same.
      final Code fnCode0 = Iterables.getOnlyElement(fnCodes);
      return env -> {
        final Closure fnValue = (Closure) fnCode0.eval(env);
        EvalEnv env2 = fnValue.evalBind(env);
        return argCode.eval(env2);
      };

    default:
      return env -> {
        EvalEnv env2 = env;
        for (Code fnCode : fnCodes) {
          final Closure fnValue = (Closure) fnCode.eval(env);
          env2 = fnValue.evalBind(env2);
        }
        return argCode.eval(env2);
      };
    }
  }

  /** Generates the code for applying a function (or function value) to an
   * argument. */
  public static Code apply(Code fnCode, Code argCode) {
    assert !fnCode.isConstant(); // if constant, use "apply(Closure, Code)"
    return env -> {
      final Applicable fnValue = (Applicable) fnCode.eval(env);
      final Object arg = argCode.eval(env);
      return fnValue.apply(env, arg);
    };
  }

  /** Generates the code for applying a function value to an argument. */
  public static Code apply(Applicable fnValue, Code argCode) {
    return env -> {
      final Object arg = argCode.eval(env);
      return fnValue.apply(env, arg);
    };
  }

  public static Code ifThenElse(Code condition, Code ifTrue,
      Code ifFalse) {
    return env -> {
      final boolean b = (Boolean) condition.eval(env);
      return (b ? ifTrue : ifFalse).eval(env);
    };
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
    Objects.requireNonNull(dataType);
    Objects.requireNonNull(name);
    return (env, arg) -> ImmutableList.of(name, arg);
  }

  public static Code from(Map<Ast.Pat, Code> sources,
      Supplier<RowSink> rowSinkFactory) {
    if (sources.size() == 0) {
      return env -> {
        final RowSink rowSink = rowSinkFactory.get();
        rowSink.accept(env);
        return rowSink.result(env);
      };
    }
    final ImmutableList<Ast.Pat> pats = ImmutableList.copyOf(sources.keySet());
    final ImmutableList<Code> codes = ImmutableList.copyOf(sources.values());
    return env -> {
      final RowSink rowSink = rowSinkFactory.get();
      final Looper looper = new Looper(pats, codes, env, rowSink);
      looper.loop(0);
      return rowSink.result(env);
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
    final ImmutableList<String> labels = bindings.stream().map(b -> b.name)
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

  /** Creates a {@link RowSink} for a {@code yield} clause. */
  public static RowSink yieldRowSink(Code yieldCode) {
    return new YieldRowSink(yieldCode);
  }

  /** Returns an applicable that returns the {@code slot}th field of a tuple or
   * record. */
  public static Applicable nth(int slot) {
    assert slot >= 0 : slot;
    return (env, arg) -> ((List) arg).get(slot);
  }

  /** An applicable that negates a boolean value. */
  private static final Applicable NOT = (env, arg) -> !(Boolean) arg;

  /** An applicable that returns the absolute value of an int. */
  private static final Applicable ABS = (env, arg) -> {
    final Integer integer = (Integer) arg;
    return integer >= 0 ? integer : -integer;
  };

  /** @see BuiltIn#IGNORE */
  private static final Applicable IGNORE = (env, arg) -> Unit.INSTANCE;

  /** @see BuiltIn#OP_MINUS */
  private static final Macro OP_MINUS = (env, argType) -> {
    switch ((PrimitiveType) ((TupleType) argType).argTypes.get(0)) {
    case INT:
      return ast.wrapApplicable(Codes::minusInt);
    case REAL:
      return ast.wrapApplicable(Codes::minusReal);
    default:
      throw new AssertionError("bad type " + argType);
    }
  };

  /** @see BuiltIn#OP_MOD */
  private static final Applicable OP_MOD = Codes::mod;

  /** @see BuiltIn#OP_PLUS */
  private static final Macro OP_PLUS = (env, argType) -> {
    switch ((PrimitiveType) ((TupleType) argType).argTypes.get(0)) {
    case INT:
      return ast.wrapApplicable(Codes::plusInt);
    case REAL:
      return ast.wrapApplicable(Codes::plusReal);
    default:
      throw new AssertionError("bad type " + argType);
    }
  };

  /** @see BuiltIn#OP_TIMES */
  private static final Macro OP_TIMES = (env, argType) -> {
    switch ((PrimitiveType) ((TupleType) argType).argTypes.get(0)) {
    case INT:
      return ast.wrapApplicable(Codes::timesInt);
    case REAL:
      return ast.wrapApplicable(Codes::timesReal);
    default:
      throw new AssertionError("bad type " + argType);
    }
  };

  /** @see BuiltIn#STRING_MAX_SIZE */
  private static final Integer STRING_MAX_SIZE = Integer.MAX_VALUE;

  /** @see BuiltIn#STRING_SIZE */
  private static final Applicable STRING_SIZE = (env, arg) ->
      ((String) arg).length();

  /** @see BuiltIn#STRING_SUB */
  private static final Applicable STRING_SUB = (env, arg) -> {
    final List tuple = (List) arg;
    final String s = (String) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    if (i < 0 || i >= s.length()) {
      throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
    }
    return s.charAt(i);
  };

  /** @see BuiltIn#STRING_EXTRACT */
  private static final Applicable STRING_EXTRACT = (env, arg) -> {
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
  };

  /** @see BuiltIn#STRING_SUBSTRING */
  private static final Applicable STRING_SUBSTRING = (env, arg) -> {
    final List tuple = (List) arg;
    final String s = (String) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    final int j = (Integer) tuple.get(2);
    if (i < 0 || j < 0 || i + j > s.length()) {
      throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
    }
    return s.substring(i, i + j);
  };

  /** @see BuiltIn#STRING_CONCAT */
  private static final Applicable STRING_CONCAT = stringConcat("");

  private static Applicable stringConcat(String separator) {
    return (env, arg) -> {
      @SuppressWarnings("unchecked") final List<String> list = (List) arg;
      long n = 0;
      for (String s : list) {
        n += s.length();
        n += separator.length();
      }
      if (n > STRING_MAX_SIZE) {
        throw new MorelRuntimeException(BuiltInExn.SIZE);
      }
      return String.join(separator, list);
    };
  }

  /** @see BuiltIn#STRING_CONCAT_WITH */
  private static final Applicable STRING_CONCAT_WITH = (env, arg) ->
      stringConcat((String) arg);

  /** @see BuiltIn#STRING_STR */
  private static final Applicable STRING_STR = (env, arg) ->
      ((Character) arg) + "";

  /** @see BuiltIn#STRING_IMPLODE */
  private static final Applicable STRING_IMPLODE = (env, arg) ->
      // Note: In theory this function should raise Size, but it is not
      // possible in practice because List.size() is never larger than
      // Integer.MAX_VALUE.
      String.valueOf(Chars.toArray((List) arg));

  /** @see BuiltIn#STRING_EXPLODE */
  private static final Applicable STRING_EXPLODE = (env, arg) -> {
    final String s = (String) arg;
    return MapList.of(s.length(), s::charAt);
  };

  /** @see BuiltIn#STRING_MAP */
  private static final Applicable STRING_MAP = (env, arg) ->
      stringMap((Applicable) arg);

  private static Applicable stringMap(Applicable f) {
    return (env, arg) -> {
      final String s = (String) arg;
      final StringBuilder buf = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        final char c = s.charAt(i);
        final char c2 = (Character) f.apply(env, c);
        buf.append(c2);
      }
      return buf.toString();
    };
  }

  /** @see BuiltIn#STRING_TRANSLATE */
  private static final Applicable STRING_TRANSLATE = (env, arg) -> {
    final Applicable f = (Applicable) arg;
    return translate(f);
  };

  private static Applicable translate(Applicable f) {
    return (env, arg) -> {
      final String s = (String) arg;
      final StringBuilder buf = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        final char c = s.charAt(i);
        final String c2 = (String) f.apply(env, c);
        buf.append(c2);
      }
      return buf.toString();
    };
  }

  /** @see BuiltIn#STRING_IS_PREFIX */
  private static final Applicable STRING_IS_PREFIX = (env, arg) -> {
    final String s = (String) arg;
    return isPrefix(s);
  };

  private static Applicable isPrefix(String s) {
    return (env, arg) -> {
      final String s2 = (String) arg;
      return s2.startsWith(s);
    };
  }

  /** @see BuiltIn#STRING_IS_SUBSTRING */
  private static final Applicable STRING_IS_SUBSTRING = (env, arg) -> {
    final String s = (String) arg;
    return isSubstring(s);
  };

  private static Applicable isSubstring(String s) {
    return (env, arg) -> {
      final String s2 = (String) arg;
      return s2.contains(s);
    };
  }

  /** @see BuiltIn#STRING_IS_SUFFIX */
  private static final Applicable STRING_IS_SUFFIX = (env, arg) -> {
    final String s = (String) arg;
    return isSuffix(s);
  };

  private static Applicable isSuffix(String s) {
    return (env, arg) -> {
      final String s2 = (String) arg;
      return s2.endsWith(s);
    };
  }

  /** @see BuiltIn#LIST_NULL */
  private static final Applicable LIST_NULL = (env, arg) ->
      ((List) arg).isEmpty();

  /** @see BuiltIn#LIST_LENGTH */
  private static final Applicable LIST_LENGTH = (env, arg) ->
      ((List) arg).size();

  /** @see BuiltIn#LIST_AT
   * @see BuiltIn#OP_UNION */
  private static final Applicable LIST_AT = (env, arg) -> {
    final List tuple = (List) arg;
    final List list0 = (List) tuple.get(0);
    final List list1 = (List) tuple.get(1);
    return ImmutableList.builder().addAll(list0).addAll(list1).build();
  };

  /** @see BuiltIn#LIST_HD */
  private static final Applicable LIST_HD = (env, arg) -> {
    final List list = (List) arg;
    if (list.isEmpty()) {
      throw new MorelRuntimeException(BuiltInExn.EMPTY);
    }
    return list.get(0);
  };

  /** @see BuiltIn#LIST_TL */
  private static final Applicable LIST_TL = (env, arg) -> {
    final List list = (List) arg;
    final int size = list.size();
    if (size == 0) {
      throw new MorelRuntimeException(BuiltInExn.EMPTY);
    }
    return list.subList(1, size);
  };

  /** @see BuiltIn#LIST_LAST */
  private static final Applicable LIST_LAST = (env, arg) -> {
    final List list = (List) arg;
    final int size = list.size();
    if (size == 0) {
      throw new MorelRuntimeException(BuiltInExn.EMPTY);
    }
    return list.get(size - 1);
  };

  /** @see BuiltIn#LIST_GET_ITEM */
  private static final Applicable LIST_GET_ITEM = (env, arg) -> {
    final List list = (List) arg;
    if (list.isEmpty()) {
      return OPTION_NONE;
    } else {
      return optionSome(
          ImmutableList.of(list.get(0), list.subList(1, list.size())));
    }
  };

  /** @see BuiltIn#LIST_NTH */
  private static final Applicable LIST_NTH = (env, arg) -> {
    final List tuple = (List) arg;
    final List list = (List) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    if (i < 0 || i >= list.size()) {
      throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
    }
    return list.get(i);
  };

  /** @see BuiltIn#LIST_TAKE */
  private static final Applicable LIST_TAKE = (env, arg) -> {
    final List tuple = (List) arg;
    final List list = (List) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    if (i < 0 || i > list.size()) {
      throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT);
    }
    return list.subList(0, i);
  };

  /** @see BuiltIn#LIST_DROP */
  private static final Applicable LIST_DROP = (env, arg) -> {
    final List tuple = (List) arg;
    final List list = (List) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    return list.subList(i, list.size());
  };

  /** @see BuiltIn#LIST_REV */
  private static final Applicable LIST_REV = (env, arg) -> {
    final List list = (List) arg;
    return Lists.reverse(list);
  };

  /** @see BuiltIn#LIST_CONCAT */
  private static final Applicable LIST_CONCAT = (env, arg) -> {
    final List list = (List) arg;
    final ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (Object o : list) {
      builder.addAll((List) o);
    }
    return builder.build();
  };

  /** @see BuiltIn#LIST_REV_APPEND */
  private static final Applicable LIST_REV_APPEND = (env, arg) -> {
    final List tuple = (List) arg;
    final List list0 = (List) tuple.get(0);
    final List list1 = (List) tuple.get(1);
    return ImmutableList.builder().addAll(Lists.reverse(list0))
        .addAll(list1).build();
  };

  /** @see BuiltIn#LIST_APP */
  private static final Applicable LIST_APP = (env, arg) ->
      listApp((Applicable) arg);

  private static Applicable listApp(Applicable consumer) {
    return (env, arg) -> {
      final List list = (List) arg;
      list.forEach(o -> consumer.apply(env, o));
      return Unit.INSTANCE;
    };
  }

  /** @see BuiltIn#LIST_MAP */
  private static final Applicable LIST_MAP = (env, arg) ->
      listMap((Applicable) arg);

  private static Applicable listMap(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      final ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object o : list) {
        builder.add(fn.apply(env, o));
      }
      return builder.build();
    };
  }

  /** @see BuiltIn#LIST_MAP_PARTIAL */
  private static final Applicable LIST_MAP_PARTIAL = (env, arg) ->
      listMapPartial((Applicable) arg);

  private static Applicable listMapPartial(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      final ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object o : list) {
        final List opt = (List) fn.apply(env, o);
        if (opt.size() == 2) {
          builder.add(opt.get(1));
        }
      }
      return builder.build();
    };
  }

  /** @see BuiltIn#LIST_FIND */
  private static final Applicable LIST_FIND = (env, arg) -> {
    final Applicable fn = (Applicable) arg;
    return find(fn);
  };

  private static Applicable find(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      for (Object o : list) {
        if ((Boolean) fn.apply(env, o)) {
          return optionSome(o);
        }
      }
      return OPTION_NONE;
    };
  }

  /** @see BuiltIn#LIST_FILTER */
  private static final Applicable LIST_FILTER = (env, arg) -> {
    final Applicable fn = (Applicable) arg;
    return listFilter(fn);
  };

  private static Applicable listFilter(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      final ImmutableList.Builder builder = ImmutableList.builder();
      for (Object o : list) {
        if ((Boolean) fn.apply(env, o)) {
          builder.add(o);
        }
      }
      return builder.build();
    };
  }

  /** @see BuiltIn#LIST_PARTITION */
  private static final Applicable LIST_PARTITION = (env, arg) -> {
    final Applicable fn = (Applicable) arg;
    return listPartition(fn);
  };

  private static Applicable listPartition(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      final ImmutableList.Builder trueBuilder = ImmutableList.builder();
      final ImmutableList.Builder falseBuilder = ImmutableList.builder();
      for (Object o : list) {
        ((Boolean) fn.apply(env, o) ? trueBuilder : falseBuilder).add(o);
      }
      return ImmutableList.of(trueBuilder.build(), falseBuilder.build());
    };
  }
  /** @see BuiltIn#LIST_FOLDL */
  private static final Applicable LIST_FOLDL = (env, arg) ->
      listFold(true, (Applicable) arg);

  /** @see BuiltIn#LIST_FOLDR */
  private static final Applicable LIST_FOLDR = (env, arg) ->
      listFold(false, (Applicable) arg);

  private static Applicable listFold(boolean left, Applicable fn) {
    return (env, arg) -> listFold2(left, fn, arg);
  }

  private static Applicable listFold2(boolean left, Applicable fn,
      Object init) {
    return (env, arg) -> {
      final List list = (List) arg;
      Object b = init;
      for (Object a : left ? list : Lists.reverse(list)) {
        b = fn.apply(env, ImmutableList.of(a, b));
      }
      return b;
    };
  }

  /** @see BuiltIn#LIST_EXISTS */
  private static final Applicable LIST_EXISTS = (env, arg) ->
      listExists((Applicable) arg);

  private static Applicable listExists(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      for (Object o : list) {
        if ((Boolean) fn.apply(env, o)) {
          return true;
        }
      }
      return false;
    };
  }

  /** @see BuiltIn#LIST_ALL */
  private static final Applicable LIST_ALL = (env, arg) ->
      listAll((Applicable) arg);

  private static Applicable listAll(Applicable fn) {
    return (env, arg) -> {
      final List list = (List) arg;
      for (Object o : list) {
        if (!(Boolean) fn.apply(env, o)) {
          return false;
        }
      }
      return true;
    };
  }

  /** @see BuiltIn#LIST_TABULATE */
  private static final Applicable LIST_TABULATE = (env, arg) -> {
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
  };

  /** @see BuiltIn#LIST_COLLATE */
  private static final Applicable LIST_COLLATE = (env, arg) ->
      collate((Applicable) arg);

  private static Applicable collate(Applicable comparator) {
    return (env, arg) -> {
      final List tuple = (List) arg;
      final List list0 = (List) tuple.get(0);
      final List list1 = (List) tuple.get(1);
      final int n = Math.min(list0.size(), list1.size());
      for (int i = 0; i < n; i++) {
        final Object element0 = list0.get(i);
        final Object element1 = list1.get(i);
        final int compare = (Integer) comparator.apply(env,
            ImmutableList.of(element0, element1));
        if (compare != 0) {
          return compare;
        }
      }
      return Integer.compare(list0.size(), list1.size());
    };
  }

  /** @see BuiltIn#OPTION_APP */
  private static final Applicable OPTION_APP = Codes::optionApp;

  /** Implements {@link #OPTION_APP}. */
  private static Applicable optionApp(EvalEnv env, Object arg) {
    final Applicable f = (Applicable) arg;
    return (env2, arg2) -> {
      final List a = (List) arg2;
      if (a.size() == 2) {
        f.apply(env2, a.get(1));
      }
      return Unit.INSTANCE;
    };
  }

  /** @see BuiltIn#OPTION_GET_OPT */
  private static final Applicable OPTION_GET_OPT = (env, arg) -> {
    final List tuple = (List) arg;
    final List opt = (List) tuple.get(0);
    if (opt.size() == 2) {
      assert opt.get(0).equals("SOME");
      return opt.get(1); // SOME has 2 elements, NONE has 1
    }
    return tuple.get(1);
  };

  /** @see BuiltIn#OPTION_IS_SOME */
  private static final Applicable OPTION_IS_SOME = (env, arg) -> {
    final List opt = (List) arg;
    return opt.size() == 2; // SOME has 2 elements, NONE has 1
  };

  /** @see BuiltIn#OPTION_VAL_OF */
  private static final Applicable OPTION_VAL_OF = (env, arg) -> {
    final List opt = (List) arg;
    if (opt.size() == 2) { // SOME has 2 elements, NONE has 1
      return opt.get(1);
    } else {
      throw new MorelRuntimeException(BuiltInExn.OPTION);
    }
  };

  /** @see BuiltIn#OPTION_FILTER */
  private static final Applicable OPTION_FILTER = Codes::optionFilter;

  /** Implementation of {@link #OPTION_FILTER}. */
  private static Applicable optionFilter(EvalEnv env, Object arg) {
    final Applicable f = (Applicable) arg;
    return (env2, arg2) -> {
      final Object a = arg2;
      if ((Boolean) f.apply(env, a)) {
        return optionSome(a);
      } else {
        return OPTION_NONE;
      }
    };
  }

  /** @see BuiltIn#OPTION_JOIN */
  private static final Applicable OPTION_JOIN = (env, arg) -> {
    final List opt = (List) arg;
    return opt.size() == 2
        ? opt.get(1) // SOME(SOME(v)) -> SOME(v), SOME(NONE) -> NONE
        : opt; // NONE -> NONE
  };

  /** @see BuiltIn#OPTION_MAP */
  private static final Applicable OPTION_MAP = Codes::optionMap;

  /** Implements {@link #OPTION_MAP}. */
  private static Applicable optionMap(EvalEnv env, Object arg) {
    final Applicable f = (Applicable) arg;
    return (env2, arg2) -> {
      final List a = (List) arg2;
      if (a.size() == 2) { // SOME v
        return optionSome(f.apply(env2, a.get(1))); // SOME (f v)
      }
      return a; // NONE
    };
  }

  /** Creates a value of {@code SOME v}.
   *
   * @see #OPTION_NONE */
  private static List optionSome(Object o) {
    return ImmutableList.of("SOME", o);
  }

  /** @see BuiltIn#OPTION_MAP_PARTIAL */
  private static final Applicable OPTION_MAP_PARTIAL = Codes::optionMapPartial;

  /** Implements {@link #OPTION_MAP_PARTIAL}. */
  private static Applicable optionMapPartial(EvalEnv env, Object arg) {
    final Applicable f = (Applicable) arg;
    return (env2, arg2) -> {
      final List a = (List) arg2;
      if (a.size() == 2) { // SOME v
        return f.apply(env2, a.get(1)); // f v
      }
      return a; // NONE
    };
  }

  /** @see BuiltIn#OPTION_COMPOSE */
  private static final Applicable OPTION_COMPOSE = Codes::optionCompose;

  /** Implements {@link #OPTION_COMPOSE}. */
  private static Applicable optionCompose(EvalEnv env, Object arg) {
    final List tuple = (List) arg;
    final Applicable f = (Applicable) tuple.get(0);
    final Applicable g = (Applicable) tuple.get(1);
    return (env2, a) -> {
      final List ga = (List) g.apply(env2, a); // g (a)
      if (ga.size() == 2) { // SOME v
        return optionSome(f.apply(env2, ga.get(1))); // SOME (f (v))
      }
      return ga; // NONE
    };
  }

  /** @see BuiltIn#OPTION_COMPOSE_PARTIAL */
  private static final Applicable OPTION_COMPOSE_PARTIAL =
      Codes::optionComposePartial;

  /** Implements {@link #OPTION_COMPOSE_PARTIAL}. */
  private static Applicable optionComposePartial(EvalEnv env, Object arg) {
    final List tuple = (List) arg;
    final Applicable f = (Applicable) tuple.get(0);
    final Applicable g = (Applicable) tuple.get(1);
    return (env2, a) -> {
      final List ga = (List) g.apply(env2, a); // g (a)
      if (ga.size() == 2) { // SOME v
        return f.apply(env2, ga.get(1)); // f (v)
      }
      return ga; // NONE
    };
  }

  /** @see BuiltIn#RELATIONAL_COUNT */
  private static final Applicable RELATIONAL_COUNT = (env, arg) ->
      ((List) arg).size();

  /** Implements {@link #RELATIONAL_SUM} for type {@code int list}. */
  private static final Applicable RELATIONAL_SUM_INT = (env, arg) -> {
    @SuppressWarnings("unchecked") final List<? extends Number> list =
        (List) arg;
    int sum = 0;
    for (Number o : list) {
      sum += o.intValue();
    }
    return sum;
  };

  /** Implements {@link #RELATIONAL_SUM} for type {@code real list}. */
  private static final Applicable RELATIONAL_SUM_REAL = (env, arg) -> {
    @SuppressWarnings("unchecked") final List<? extends Number> list =
        (List) arg;
    float sum = 0;
    for (Number o : list) {
      sum += o.floatValue();
    }
    return sum;
  };

  /** @see BuiltIn#RELATIONAL_SUM */
  private static final Macro RELATIONAL_SUM = (env, argType) -> {
    if (argType instanceof ListType) {
      switch ((PrimitiveType) ((ListType) argType).elementType) {
      case INT:
        return ast.wrapApplicable(RELATIONAL_SUM_INT);
      case REAL:
        return ast.wrapApplicable(RELATIONAL_SUM_REAL);
      }
    }
    throw new AssertionError("bad type " + argType);
  };

  /** @see BuiltIn#RELATIONAL_MIN */
  private static final Applicable RELATIONAL_MIN = (env, arg) ->
      Ordering.natural().min((List) arg);

  /** @see BuiltIn#RELATIONAL_MAX */
  private static final Applicable RELATIONAL_MAX = (env, arg) ->
      Ordering.natural().max((List) arg);

  /** @see BuiltIn#SYS_ENV */
  private static final Macro SYS_ENV = (env, argType) ->
      ast.list(Pos.ZERO,
          env.getValueMap().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(entry ->
                  ast.tuple(Pos.ZERO,
                      ImmutableList.of(
                          ast.stringLiteral(Pos.ZERO, entry.getKey()),
                          ast.stringLiteral(Pos.ZERO,
                              entry.getValue().type.moniker()))))
              .collect(Collectors.toList()));

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
  public static EvalEnv emptyEnvWith(Environment env) {
    final Map<String, Object> map = new HashMap<>();
    populateBuiltIns(map);
    env.forEachValue(map::put);
    return EvalEnvs.copyOf(map);
  }

  /** @see BuiltIn#OP_NEGATE */
  private static final Macro OP_NEGATE = (env, argType) -> {
    switch ((PrimitiveType) argType) {
    case INT:
      return ast.wrapApplicable(Codes::negateInt);
    case REAL:
      return ast.wrapApplicable(Codes::negateReal);
    default:
      throw new AssertionError("bad type " + argType);
    }
  };

  /** Implements {@link #OP_NEGATE} for type {@code int}. */
  private static int negateInt(EvalEnv env, Object arg) {
    return -((Integer) arg);
  }

  /** Creates a compilation environment. */
  public static Environment env(TypeSystem typeSystem,
      Environment environment) {
    final Environment[] hEnv = {environment};
    BUILT_IN_VALUES.forEach((key, value) -> {
      final Type type = key.typeFunction.apply(typeSystem);
      if (key.structure == null) {
        hEnv[0] = hEnv[0].bind(key.mlName, type, value);
      }
      if (key.alias != null) {
        hEnv[0] = hEnv[0].bind(key.alias, type, value);
      }
    });

    final List<Object> valueList = new ArrayList<>();
    BuiltIn.forEachStructure(typeSystem, (structure, type) -> {
      valueList.clear();
      structure.memberMap.values()
          .forEach(builtIn -> valueList.add(BUILT_IN_VALUES.get(builtIn)));
      hEnv[0] = hEnv[0]
          .bind(structure.name, type, ImmutableList.copyOf(valueList));
    });
    return hEnv[0];
  }

  /** Implements {@link #OP_NEGATE} for type {@code real}. */
  private static float negateReal(EvalEnv env, Object arg) {
    return -((Float) arg);
  }

  private static <E> Set<E> minus(Set<E> set1, Set<E> set0) {
    final Set<E> set = new LinkedHashSet<>(set1);
    set.removeAll(set0);
    return set;
  }

  public static Applicable aggregate(Environment env, Code aggregateCode,
      List<String> names, @Nullable Code argumentCode) {
    return (env1, arg) -> {
      final List rows = (List) arg;
      final List<Object> argRows;
      if (argumentCode != null) {
        final MutableEvalEnv env2 = env1.bindMutableArray(names);
        argRows = new ArrayList<>(rows.size());
        for (Object row : rows) {
          env2.set(row);
          argRows.add(argumentCode.eval(env2));
        }
      } else if (names.size() != 1) {
        // Reconcile the fact that we internally represent rows as arrays when
        // we're buffering for "group", lists at other times.
        //noinspection unchecked
        argRows = Lists.transform(rows,
            row -> (Object) Arrays.asList((Object []) row));
      } else {
        //noinspection unchecked
        argRows = rows;
      }
      final Applicable aggregate = (Applicable) aggregateCode.eval(env1);
      return aggregate.apply(env1, argRows);
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
          .put(BuiltIn.SYS_ENV, SYS_ENV)
          .build();

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

    Looper(ImmutableList<Ast.Pat> pats, ImmutableList<Code> codes, EvalEnv env,
        RowSink rowSink) {
      this.codes = codes;
      this.rowSink = rowSink;
      for (Ast.Pat pat : pats) {
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
  public interface RowSink {
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
      this.keyCode = Objects.requireNonNull(keyCode);
      this.aggregateCodes = Objects.requireNonNull(aggregateCodes);
      this.inNames = Objects.requireNonNull(inNames);
      this.outNames = Objects.requireNonNull(outNames);
      this.rowSink = Objects.requireNonNull(rowSink);
      this.values = inNames.size() == 1 ? null : new Object[inNames.size()];
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

    public List<Object> result(EvalEnv env) {
      final EvalEnv env0 = env;
      EvalEnv env2 = env0;
      final MutableEvalEnv[] groupEnvs = new MutableEvalEnv[outNames.size()];
      int i = 0;
      for (String name : outNames) {
        env2 = groupEnvs[i++] = env2.bindMutable(name);
      }
      for (Map.Entry<Object, List<Object>> entry
          : Multimaps.asMap(map).entrySet()) {
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
      return rowSink.result(env0);
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

  /** Implementation of {@link RowSink} for a {@code yield} clause. */
  private static class YieldRowSink implements RowSink {
    final List<Object> list;
    private final Code yieldCode;

    YieldRowSink(Code yieldCode) {
      this.yieldCode = yieldCode;
      list = new ArrayList<>();
    }

    public void accept(EvalEnv env) {
      list.add(yieldCode.eval(env));
    }

    public List<Object> result(EvalEnv env) {
      return list;
    }
  }

  /** Code that retrieves the value of a variable from the environment. */
  private static class GetCode implements Code {
    private final String name;

    GetCode(String name) {
      this.name = Objects.requireNonNull(name);
    }

    @Override public String toString() {
      return "get(" + name + ")";
    }

    public Object eval(EvalEnv env) {
      return env.getOpt(name);
    }
  }

  /** Java exception that wraps an exception thrown by the Morel runtime. */
  public static class MorelRuntimeException extends RuntimeException {
    private final BuiltInExn e;

    /** Creates a MorelRuntimeException. */
    public MorelRuntimeException(BuiltInExn e) {
      this.e = Objects.requireNonNull(e);
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
}

// End Codes.java
