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
package net.hydromatic.sml.eval;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.primitives.Chars;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.compile.BuiltIn;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.util.MapList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Helpers for {@link Code}. */
public abstract class Codes {
  private Codes() {}

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

  /** Returns a Code that evaluates "{@code =}". */
  public static Code eq(Code code0, Code code1) {
    return env -> code0.eval(env).equals(code1.eval(env));
  }

  /** Returns a Code that evaluates "{@code <>}". */
  public static Code ne(Code code0, Code code1) {
    return env -> !code0.eval(env).equals(code1.eval(env));
  }

  /** Returns a Code that evaluates "{@code <}". */
  public static Code lt(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) < 0;
    };
  }

  /** Returns a Code that evaluates "{@code >}". */
  public static Code gt(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) > 0;
    };
  }

  /** Returns a Code that evaluates "{@code <=}". */
  public static Code le(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) <= 0;
    };
  }

  /** Returns a Code that evaluates "{@code >=}". */
  public static Code ge(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) >= 0;
    };
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

  /** Returns a Code that evaluates "+". */
  public static Code plus(Code code0, Code code1) {
    return env -> (int) code0.eval(env) + (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "-". */
  public static Code minus(Code code0, Code code1) {
    return env -> (int) code0.eval(env) - (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "*". */
  public static Code times(Code code0, Code code1) {
    return env -> (int) code0.eval(env) * (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "/". */
  public static Code divide(Code code0, Code code1) {
    return env -> (int) code0.eval(env) / (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "div". */
  public static Code div(Code code0, Code code1) {
    return env -> Math.floorDiv((int) code0.eval(env), (int) code1.eval(env));
  }

  /** Returns a Code that evaluates "mod". */
  public static Code mod(Code code0, Code code1) {
    return env -> Math.floorMod((int) code0.eval(env), (int) code1.eval(env));
  }

  /** Returns a Code that evaluates "^" (string concatenation). */
  public static Code caret(Code code0, Code code1) {
    return env -> ((String) code0.eval(env)) + ((String) code1.eval(env));
  }

  /** Returns a Code that evaluates "::" (list cons). */
  public static Code cons(Code code0, Code code1) {
    return env -> ImmutableList.builder().add(code0.eval(env))
        .addAll((Iterable) code1.eval(env)).build();
  }

  /** Returns a Code that returns the value of variable "name" in the current
   * environment. */
  public static Code get(String name) {
    return env -> env.get(name);
  }

  public static Code let(List<Code> fnCodes, Code argCode) {
    if (fnCodes.size() == 1) {
      // Use a more efficient runtime path if the list has only one element.
      // The effect is the same.
      final Code fnCode = Iterables.getOnlyElement(fnCodes);
      return env -> {
        final Closure fnValue = (Closure) fnCode.eval(env);
        EvalEnv env2 = fnValue.evalBind(env.copy());
        return argCode.eval(env2);
      };
    } else {
      return env -> {
        EvalEnv env2 = env.copy();
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
      final Object argValue = argCode.eval(env);
      return fnValue.apply(env, argValue);
    };
  }

  /** Generates the code for applying a function value to an argument. */
  public static Code apply(Applicable fnValue, Code argCode) {
    return env -> {
      final Object argValue = argCode.eval(env);
      return fnValue.apply(env, argValue);
    };
  }

  public static Code ifThenElse(Code condition, Code ifTrue,
      Code ifFalse) {
    return env -> {
      final boolean b = (Boolean) condition.eval(env);
      return (b ? ifTrue : ifFalse).eval(env);
    };
  }

  public static Code list(List<Code> codes) {
    return tuple(codes);
  }

  public static Code tuple(List<Code> codes) {
    return new TupleCode(codes);
  }

  public static Code from(Map<Ast.Id, Code> sources, Code filterCode,
      Code yieldCode) {
    final ImmutableList<Ast.Id> ids = ImmutableList.copyOf(sources.keySet());
    return new Code() {
      @Override public Object eval(EvalEnv env) {
        final List<Iterable> values = new ArrayList<>();
        for (Code code : sources.values()) {
          values.add((Iterable) code.eval(env));
        }
        final List list = new ArrayList();
        loop(0, values, env, list);
        return list;
      }

      /** Generates the {@code i}th nested lopp of a cartesian product of the
       * values in {@code iterables}. */
      void loop(int i, List<Iterable> iterables, EvalEnv env,
          List<Object> list) {
        if (i == iterables.size()) {
          if ((Boolean) filterCode.eval(env)) {
            list.add(yieldCode.eval(env));
          }
        } else {
          final String name = ids.get(i).name;
          final Iterable iterable = iterables.get(i);
          for (Object o : iterable) {
            EvalEnv env2 = add(env, name, o);
            loop(i + 1, iterables, env2, list);
          }
        }
      }
    };
  }

  /** Returns an applicable that constructs an instance of a datatype.
   * The instance is a list with two elements [constructorName, value]. */
  public static Applicable tyCon(Type dataType, String name) {
    Objects.requireNonNull(dataType);
    Objects.requireNonNull(name);
    return (env, argValue) -> ImmutableList.of(name, argValue);
  }

  /** Returns an applicable that returns the {@code slot}th field of a tuple or
   * record. */
  public static Applicable nth(int slot) {
    assert slot >= 0;
    return (env, argValue) -> ((List) argValue).get(slot);
  }

  /** An applicable that negates a boolean value. */
  static final Applicable NOT = (env, argValue) -> !(Boolean) argValue;

  /** An applicable that returns the absolute value of an int. */
  public static final Applicable ABS = (env, argValue) -> {
    final Integer integer = (Integer) argValue;
    return integer >= 0 ? integer : -integer;
  };

  /** @see BuiltIn#IGNORE */
  static final Applicable IGNORE = (env, argValue) -> Unit.INSTANCE;

  /** @see BuiltIn#STRING_MAX_SIZE */
  static final Integer STRING_MAX_SIZE = Integer.MAX_VALUE;

  /** @see BuiltIn#STRING_SIZE */
  static final Applicable STRING_SIZE = (env, argValue) ->
      ((String) argValue).length();

  /** @see BuiltIn#STRING_SUB */
  static final Applicable STRING_SUB = (env, argValue) -> {
    final List tuple = (List) argValue;
    final String s = (String) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    return s.charAt(i);
  };

  /** @see BuiltIn#STRING_EXTRACT */
  static final Applicable STRING_EXTRACT = (env, argValue) -> {
    final List tuple = (List) argValue;
    final String s = (String) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    return s.substring(i);
  };

  /** @see BuiltIn#STRING_SUBSTRING */
  static final Applicable STRING_SUBSTRING = (env, argValue) -> {
    final List tuple = (List) argValue;
    final String s = (String) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    final int j = (Integer) tuple.get(2);
    return s.substring(i, i + j);
  };

  /** @see BuiltIn#STRING_CONCAT */
  static final Applicable STRING_CONCAT = stringConcat("");

  private static Applicable stringConcat(String separator) {
    return (env, argValue) -> {
      @SuppressWarnings("unchecked") final List<String> list = (List) argValue;
      return String.join(separator, list);
    };
  }

  /** @see BuiltIn#STRING_CONCAT_WITH */
  static final Applicable STRING_CONCAT_WITH = (env, argValue) ->
      stringConcat((String) argValue);

  /** @see BuiltIn#STRING_STR */
  static final Applicable STRING_STR = (env, argValue) ->
      ((Character) argValue) + "";

  /** @see BuiltIn#STRING_IMPLODE */
  static final Applicable STRING_IMPLODE = (env, argValue) ->
      String.valueOf(Chars.toArray((List) argValue));

  /** @see BuiltIn#STRING_EXPLODE */
  static final Applicable STRING_EXPLODE = (env, argValue) -> {
    final String s = (String) argValue;
    return MapList.of(s.length(), s::charAt);
  };

  /** @see BuiltIn#STRING_MAP */
  static final Applicable STRING_MAP = (env, argValue) ->
      stringMap((Applicable) argValue);

  static Applicable stringMap(Applicable f) {
    return (env, argValue) -> {
      final String s = (String) argValue;
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
  static final Applicable STRING_TRANSLATE = (env, argValue) -> {
    final Applicable f = (Applicable) argValue;
    return translate(f);
  };

  static Applicable translate(Applicable f) {
    return (env, argValue) -> {
      final String s = (String) argValue;
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
  static final Applicable STRING_IS_PREFIX = (env, argValue) -> {
    final String s = (String) argValue;
    return isPrefix(s);
  };

  static Applicable isPrefix(String s) {
    return (env, argValue) -> {
      final String s2 = (String) argValue;
      return s2.startsWith(s);
    };
  }

  /** @see BuiltIn#STRING_IS_SUBSTRING */
  static final Applicable STRING_IS_SUBSTRING = (env, argValue) -> {
    final String s = (String) argValue;
    return isSubstring(s);
  };

  static Applicable isSubstring(String s) {
    return (env, argValue) -> {
      final String s2 = (String) argValue;
      return s2.contains(s);
    };
  }

  /** @see BuiltIn#STRING_IS_SUFFIX */
  static final Applicable STRING_IS_SUFFIX = (env, argValue) -> {
    final String s = (String) argValue;
    return isSuffix(s);
  };

  static Applicable isSuffix(String s) {
    return (env, argValue) -> {
      final String s2 = (String) argValue;
      return s2.endsWith(s);
    };
  }

  /** @see BuiltIn#LIST_NULL */
  static final Applicable LIST_NULL = (env, argValue) ->
      ((List) argValue).isEmpty();

  /** @see BuiltIn#LIST_LENGTH */
  static final Applicable LIST_LENGTH = (env, argValue) ->
      ((List) argValue).size();

  /** @see BuiltIn#LIST_AT */
  static final Applicable LIST_AT = (env, argValue) -> {
    final List tuple = (List) argValue;
    final List list0 = (List) tuple.get(0);
    final List list1 = (List) tuple.get(1);
    return ImmutableList.builder().addAll(list0).addAll(list1).build();
  };

  /** @see BuiltIn#LIST_HD */
  static final Applicable LIST_HD = (env, argValue) ->
      ((List) argValue).get(0);

  /** @see BuiltIn#LIST_TL */
  static final Applicable LIST_TL = (env, argValue) ->
      ((List) argValue).subList(1, ((List) argValue).size());

  /** @see BuiltIn#LIST_LAST */
  static final Applicable LIST_LAST = (env, argValue) ->
      ((List) argValue).get(((List) argValue).size() - 1);

  /** @see BuiltIn#LIST_GET_ITEM */
  static final Applicable LIST_GET_ITEM = (env, argValue) -> {
    final List list = (List) argValue;
    return ImmutableList.of(list.get(0), list.subList(1, list.size()));
  };

  /** @see BuiltIn#LIST_NTH */
  static final Applicable LIST_NTH = (env, argValue) -> {
    final List tuple = (List) argValue;
    final List list = (List) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    return list.get(i);
  };

  /** @see BuiltIn#LIST_TAKE */
  static final Applicable LIST_TAKE = (env, argValue) -> {
    final List tuple = (List) argValue;
    final List list = (List) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    return list.subList(0, i);
  };

  /** @see BuiltIn#LIST_DROP */
  static final Applicable LIST_DROP = (env, argValue) -> {
    final List tuple = (List) argValue;
    final List list = (List) tuple.get(0);
    final int i = (Integer) tuple.get(1);
    return list.subList(i, list.size());
  };

  /** @see BuiltIn#LIST_REV */
  static final Applicable LIST_REV = (env, argValue) -> {
    final List list = (List) argValue;
    return Lists.reverse(list);
  };

  /** @see BuiltIn#LIST_CONCAT */
  static final Applicable LIST_CONCAT = (env, argValue) -> {
    final List list = (List) argValue;
    final ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (Object o : list) {
      builder.addAll((List) o);
    }
    return builder.build();
  };

  /** @see BuiltIn#LIST_REV_APPEND */
  static final Applicable LIST_REV_APPEND = (env, argValue) -> {
    final List tuple = (List) argValue;
    final List list0 = (List) tuple.get(0);
    final List list1 = (List) tuple.get(1);
    return ImmutableList.builder().addAll(Lists.reverse(list0))
        .addAll(list1).build();
  };

  /** @see BuiltIn#LIST_APP */
  static final Applicable LIST_APP = (env, argValue) ->
      listApp((Applicable) argValue);

  private static Applicable listApp(Applicable consumer) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      list.forEach(o -> consumer.apply(env, o));
      return Unit.INSTANCE;
    };
  }

  /** @see BuiltIn#LIST_MAP */
  static final Applicable LIST_MAP = (env, argValue) ->
      listMap((Applicable) argValue);

  private static Applicable listMap(Applicable fn) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      final ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object o : list) {
        builder.add(fn.apply(env, o));
      }
      return builder.build();
    };
  }

  /** @see BuiltIn#LIST_FIND */
  static final Applicable LIST_FIND = (env, argValue) -> {
    final Applicable fn = (Applicable) argValue;
    return find(fn);
  };

  static Applicable find(Applicable fn) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      for (Object o : list) {
        if ((Boolean) fn.apply(env, o)) {
          return o;
        }
      }
      throw new RuntimeException("not found");
    };
  }

  /** @see BuiltIn#LIST_FILTER */
  static final Applicable LIST_FILTER = (env, argValue) -> {
    final Applicable fn = (Applicable) argValue;
    return listFilter(fn);
  };

  private static Applicable listFilter(Applicable fn) {
    return (env, argValue) -> {
      final List list = (List) argValue;
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
  static final Applicable LIST_PARTITION = (env, argValue) -> {
    final Applicable fn = (Applicable) argValue;
    return listPartition(fn);
  };

  private static Applicable listPartition(Applicable fn) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      final ImmutableList.Builder trueBuilder = ImmutableList.builder();
      final ImmutableList.Builder falseBuilder = ImmutableList.builder();
      for (Object o : list) {
        ((Boolean) fn.apply(env, o) ? trueBuilder : falseBuilder).add(o);
      }
      return ImmutableList.of(trueBuilder.build(), falseBuilder.build());
    };
  }
  /** @see BuiltIn#LIST_FOLDL */
  static final Applicable LIST_FOLDL = (env, argValue) ->
      listFold(true, (Applicable) argValue);

  /** @see BuiltIn#LIST_FOLDR */
  static final Applicable LIST_FOLDR = (env, argValue) ->
      listFold(false, (Applicable) argValue);

  private static Applicable listFold(boolean left, Applicable fn) {
    return (env, argValue) -> listFold2(left, fn, argValue);
  }

  private static Applicable listFold2(boolean left, Applicable fn,
      Object init) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      Object b = init;
      for (Object a : left ? list : Lists.reverse(list)) {
        b = fn.apply(env, ImmutableList.of(a, b));
      }
      return b;
    };
  }

  /** @see BuiltIn#LIST_EXISTS */
  static final Applicable LIST_EXISTS = (env, argValue) ->
      listExists((Applicable) argValue);

  private static Applicable listExists(Applicable fn) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      for (Object o : list) {
        if ((Boolean) fn.apply(env, o)) {
          return true;
        }
      }
      return false;
    };
  }

  /** @see BuiltIn#LIST_ALL */
  static final Applicable LIST_ALL = (env, argValue) ->
      listAll((Applicable) argValue);

  private static Applicable listAll(Applicable fn) {
    return (env, argValue) -> {
      final List list = (List) argValue;
      for (Object o : list) {
        if (!(Boolean) fn.apply(env, o)) {
          return false;
        }
      }
      return true;
    };
  }

  /** @see BuiltIn#LIST_TABULATE */
  static final Applicable LIST_TABULATE = (env, argValue) -> {
    final List tuple = (List) argValue;
    final int count = (Integer) tuple.get(0);
    final Applicable fn = (Applicable) tuple.get(1);
    final ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (int i = 0; i < count; i++) {
      builder.add(fn.apply(env, i));
    }
    return builder.build();
  };

  /** @see BuiltIn#LIST_COLLATE */
  static final Applicable LIST_COLLATE = (env, argValue) ->
      collate((Applicable) argValue);

  static Applicable collate(Applicable comparator) {
    return (env, argValue) -> {
      final List tuple = (List) argValue;
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

  /** Creates an empty evaluation environment. */
  public static EvalEnv emptyEnv() {
    final EvalEnv env = new EvalEnv();
    add(env, BuiltIn.TRUE, true);
    add(env, BuiltIn.FALSE, false);
    add(env, BuiltIn.NOT, NOT);
    add(env, BuiltIn.ABS, ABS);
    add(env, BuiltIn.IGNORE, IGNORE);
    add(env, BuiltIn.STRING_MAX_SIZE, STRING_MAX_SIZE);
    add(env, BuiltIn.STRING_SIZE, STRING_SIZE);
    add(env, BuiltIn.STRING_SUB, STRING_SUB);
    add(env, BuiltIn.STRING_EXTRACT, STRING_EXTRACT);
    add(env, BuiltIn.STRING_SUBSTRING, STRING_SUBSTRING);
    add(env, BuiltIn.STRING_CONCAT, STRING_CONCAT);
    add(env, BuiltIn.STRING_CONCAT_WITH, STRING_CONCAT_WITH);
    add(env, BuiltIn.STRING_STR, STRING_STR);
    add(env, BuiltIn.STRING_IMPLODE, STRING_IMPLODE);
    add(env, BuiltIn.STRING_EXPLODE, STRING_EXPLODE);
    add(env, BuiltIn.STRING_MAP, STRING_MAP);
    add(env, BuiltIn.STRING_TRANSLATE, STRING_TRANSLATE);
    add(env, BuiltIn.STRING_IS_PREFIX, STRING_IS_PREFIX);
    add(env, BuiltIn.STRING_IS_SUBSTRING, STRING_IS_SUBSTRING);
    add(env, BuiltIn.STRING_IS_SUFFIX, STRING_IS_SUFFIX);
    add(env, BuiltIn.LIST_NIL, ImmutableList.of());
    add(env, BuiltIn.LIST_NULL, LIST_NULL);
    add(env, BuiltIn.LIST_LENGTH, LIST_LENGTH);
    add(env, BuiltIn.LIST_AT, LIST_AT);
    add(env, BuiltIn.LIST_HD, LIST_HD);
    add(env, BuiltIn.LIST_TL, LIST_TL);
    add(env, BuiltIn.LIST_LAST, LIST_LAST);
    add(env, BuiltIn.LIST_GET_ITEM, LIST_GET_ITEM);
    add(env, BuiltIn.LIST_NTH, LIST_NTH);
    add(env, BuiltIn.LIST_TAKE, LIST_TAKE);
    add(env, BuiltIn.LIST_DROP, LIST_DROP);
    add(env, BuiltIn.LIST_REV, LIST_REV);
    add(env, BuiltIn.LIST_CONCAT, LIST_CONCAT);
    add(env, BuiltIn.LIST_REV_APPEND, LIST_REV_APPEND);
    add(env, BuiltIn.LIST_APP, LIST_APP);
    add(env, BuiltIn.LIST_MAP, LIST_MAP);
    add(env, BuiltIn.LIST_MAP_PARTIAL, LIST_MAP);
    add(env, BuiltIn.LIST_FIND, LIST_FIND);
    add(env, BuiltIn.LIST_FILTER, LIST_FILTER);
    add(env, BuiltIn.LIST_PARTITION, LIST_PARTITION);
    add(env, BuiltIn.LIST_FOLDL, LIST_FOLDL);
    add(env, BuiltIn.LIST_FOLDR, LIST_FOLDR);
    add(env, BuiltIn.LIST_EXISTS, LIST_EXISTS);
    add(env, BuiltIn.LIST_ALL, LIST_ALL);
    add(env, BuiltIn.LIST_TABULATE, LIST_TABULATE);
    add(env, BuiltIn.LIST_COLLATE, LIST_COLLATE);
    assert env.valueMap.keySet().containsAll(BuiltIn.BY_ML_NAME.keySet())
        : "no implementation for "
        + minus(BuiltIn.BY_ML_NAME.keySet(), env.valueMap.keySet());
    return env;
  }

  private static void add(EvalEnv env, BuiltIn key, Object value) {
    env.valueMap.put(key.mlName, value);
    if (key.alias != null) {
      env.valueMap.put(key.alias, value);
    }
  }

  /** Creates an evaluation environment that is the same as a given evaluation
   * environment, plus one more variable. */
  public static EvalEnv add(EvalEnv env, String var, Object value) {
    // Copying the entire table is not very efficient.
    final EvalEnv env2 = env.copy();
    env2.valueMap.put(var, value);
    return env2;
  }

  public static Code negate(Code code) {
    return env -> -((Integer) code.eval(env));
  }

  private static <E> Set<E> minus(Set<E> set1, Set<E> set0) {
    final Set<E> set = new LinkedHashSet<>(set1);
    set.removeAll(set0);
    return set;
  }

  /** A code that evaluates expressions and creates a tuple with the results.
   *
   * <p>An inner class so that we can pick apart the results of multiply
   * defined functions: {@code fun f = ... and g = ...}.
   */
  public static class TupleCode implements Code {
    public final List<Code> codes;

    TupleCode(List<Code> codes) {
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
}

// End Codes.java
