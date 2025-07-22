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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.hydromatic.morel.ast.Core;
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
import net.hydromatic.morel.util.PairList;
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

  /**
   * Returns an applicable that returns the {@code slot}th field of a tuple or
   * record.
   */
  public static Applicable nth(int slot) {
    checkArgument(slot >= 0);
    return new BaseApplicable1<Object, List>(BuiltIn.Z_NTH) {
      @Override
      protected String name() {
        return "nth:" + slot;
      }

      @Override
      public Object apply(List list) {
        return list.get(slot);
      }
    };
  }

  // ---------------------------------------------------------------------------
  // The following section contains fields that implement built-in functions and
  // values. They are in alphabetical order.

  // lint:startSorted:fields

  /** An applicable that returns the absolute value of an int. */
  private static final Applicable1 ABS =
      new BaseApplicable1<Integer, Integer>(BuiltIn.ABS) {
        @Override
        public Integer apply(Integer integer) {
          return integer >= 0 ? integer : -integer;
        }
      };

  /** @see BuiltIn#BAG_ALL */
  private static final Applicable2 BAG_ALL = all(BuiltIn.BAG_ALL);

  /** @see BuiltIn#BAG_APP */
  private static final Applicable2 BAG_APP = listApp(BuiltIn.BAG_APP);

  /** @see BuiltIn#BAG_AT */
  private static final Applicable2 BAG_AT = union(BuiltIn.BAG_AT);

  /** @see BuiltIn#BAG_CONCAT */
  private static final Applicable BAG_CONCAT = listConcat(BuiltIn.BAG_CONCAT);

  /** @see BuiltIn#BAG_DROP */
  private static final Applicable2 BAG_DROP = listDrop(BuiltIn.BAG_DROP);

  /** @see BuiltIn#BAG_FIND */
  private static final Applicable2 BAG_FIND = find(BuiltIn.BAG_FIND);

  /** @see BuiltIn#BAG_FILTER */
  private static final Applicable2 BAG_FILTER = listFilter(BuiltIn.BAG_FILTER);

  /** @see BuiltIn#BAG_FOLD */
  private static final Applicable3 BAG_FOLD =
      // Order does not matter, but we call with left = true because foldl is
      // more efficient than foldr.
      listFold0(BuiltIn.BAG_FOLD, true);

  /** @see BuiltIn#BAG_FROM_LIST */
  private static final Applicable1 BAG_FROM_LIST =
      identity(BuiltIn.BAG_FROM_LIST);

  /** @see BuiltIn#BAG_EXISTS */
  private static final Applicable2 BAG_EXISTS = exists(BuiltIn.BAG_EXISTS);

  /** @see BuiltIn#BAG_GET_ITEM */
  private static final Applicable BAG_GET_ITEM =
      listGetItem(BuiltIn.BAG_GET_ITEM);

  /** @see BuiltIn#BAG_HD */
  private static final Applicable BAG_HD =
      new ListHd(BuiltIn.LIST_HD, Pos.ZERO);

  /** @see BuiltIn#BAG_LENGTH */
  private static final Applicable1 BAG_LENGTH = length(BuiltIn.BAG_LENGTH);

  /** @see BuiltIn#BAG_MAP_PARTIAL */
  private static final Applicable2 BAG_MAP_PARTIAL =
      listMapPartial(BuiltIn.BAG_MAP_PARTIAL);

  /** @see BuiltIn#BAG_MAP */
  private static final Applicable2 BAG_MAP = listMap(BuiltIn.BAG_MAP);

  /** @see BuiltIn#BAG_NTH */
  private static final Applicable BAG_NTH =
      new ListNth(BuiltIn.BAG_NTH, Pos.ZERO);

  /** @see BuiltIn#BAG_NULL */
  private static final Applicable1 BAG_NULL = empty(BuiltIn.BAG_NULL);

  /** @see BuiltIn#BAG_PARTITION */
  private static final Applicable2 BAG_PARTITION =
      listPartition0(BuiltIn.BAG_PARTITION);

  /** @see BuiltIn#BAG_TABULATE */
  private static final Applicable BAG_TABULATE =
      new ListTabulate(BuiltIn.BAG_TABULATE, Pos.ZERO);

  /** @see BuiltIn#BAG_TAKE */
  private static final Applicable BAG_TAKE =
      new ListTake(BuiltIn.BAG_TAKE, Pos.ZERO);

  /** @see BuiltIn#BAG_TO_LIST */
  private static final Applicable1 BAG_TO_LIST = identity(BuiltIn.BAG_TO_LIST);

  /** @see BuiltIn#BAG_TL */
  private static final Applicable BAG_TL =
      new ListTl(BuiltIn.LIST_TL, Pos.ZERO);

  /** @see BuiltIn#CHAR_CHR */
  private static final Applicable1 CHAR_CHR = new CharChr(Pos.ZERO);

  /** Implements {@link #CHAR_CHR}. */
  private static class CharChr
      extends BasePositionedApplicable1<Character, Integer>
      implements Positioned {
    CharChr(Pos pos) {
      super(BuiltIn.CHAR_CHR, pos);
    }

    @Override
    public CharChr withPos(Pos pos) {
      return new CharChr(pos);
    }

    @Override
    public Character apply(Integer ord) {
      if (ord < 0 || ord > 255) {
        throw new MorelRuntimeException(BuiltInExn.CHR, pos);
      }
      return (char) ord.intValue();
    }
  }

  /** @see BuiltIn#CHAR_COMPARE */
  private static final Applicable2 CHAR_COMPARE =
      new BaseApplicable2<List, Character, Character>(BuiltIn.CHAR_COMPARE) {
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
  private static final Applicable2 CHAR_CONTAINS =
      charContains(BuiltIn.CHAR_CONTAINS);

  /** Implement {@link #CHAR_CONTAINS} and {@link #CHAR_NOT_CONTAINS}. */
  private static Applicable2 charContains(BuiltIn builtIn) {
    return new BaseApplicable2<Boolean, String, Character>(builtIn) {
      final boolean negate = builtIn == BuiltIn.CHAR_NOT_CONTAINS;

      @Override
      public Boolean apply(String s, Character c) {
        return s.indexOf(c) >= 0 ^ negate;
      }
    };
  }

  /** @see BuiltIn#CHAR_FROM_CSTRING */
  private static final Applicable1 CHAR_FROM_CSTRING =
      new BaseApplicable1<List, String>(BuiltIn.CHAR_FROM_CSTRING) {
        @Override
        public List apply(String s) {
          throw new UnsupportedOperationException("CHAR_FROM_CSTRING");
        }
      };

  /** @see BuiltIn#CHAR_FROM_STRING */
  private static final Applicable CHAR_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.CHAR_FROM_STRING) {
        @Override
        public List apply(String s) {
          Character c = Parsers.fromString(s);
          return c == null ? OPTION_NONE : optionSome(c);
        }
      };

  /** @see BuiltIn#CHAR_IS_ALPHA_NUM */
  private static final Applicable CHAR_IS_ALPHA_NUM =
      new CharPredicate(BuiltIn.CHAR_IS_ALPHA_NUM, CharPredicate::isAlphaNum);

  /** @see BuiltIn#CHAR_IS_ALPHA */
  private static final Applicable CHAR_IS_ALPHA =
      new CharPredicate(BuiltIn.CHAR_IS_ALPHA, CharPredicate::isAlpha);

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
  private static final Applicable2 CHAR_NOT_CONTAINS =
      charContains(BuiltIn.CHAR_NOT_CONTAINS);

  /** @see BuiltIn#CHAR_OP_GE */
  private static final Applicable2 CHAR_OP_GE =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_GE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 >= a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_GT */
  private static final Applicable2 CHAR_OP_GT =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_GT) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 > a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_LE */
  private static final Applicable2 CHAR_OP_LE =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_LE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 <= a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_LT */
  private static final Applicable2 CHAR_OP_LT =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_LT) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 < a1;
        }
      };

  /** @see BuiltIn#CHAR_ORD */
  private static final Applicable1 CHAR_ORD =
      new BaseApplicable1<Integer, Character>(BuiltIn.CHAR_ORD) {
        @Override
        public Integer apply(Character arg) {
          return (int) arg;
        }
      };

  /** @see BuiltIn#CHAR_PRED */
  private static final Applicable1 CHAR_PRED = new CharPred(Pos.ZERO);

  /** Implements {@link #CHAR_PRED}. */
  private static class CharPred
      extends BasePositionedApplicable1<Character, Character> {
    CharPred(Pos pos) {
      super(BuiltIn.CHAR_PRED, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new CharPred(pos);
    }

    @Override
    public Character apply(Character c) {
      if (c == CHAR_MIN_CHAR) {
        throw new MorelRuntimeException(BuiltInExn.CHR, pos);
      }
      return (char) (c - 1);
    }
  }

  /** @see BuiltIn#CHAR_SUCC */
  private static final Applicable CHAR_SUCC = new CharSucc(Pos.ZERO);

  /** Implements {@link #CHAR_SUCC}. */
  private static class CharSucc
      extends BasePositionedApplicable1<Character, Character> {
    CharSucc(Pos pos) {
      super(BuiltIn.CHAR_SUCC, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new CharSucc(pos);
    }

    @Override
    public Character apply(Character c) {
      if (c.equals(CHAR_MAX_CHAR)) {
        throw new MorelRuntimeException(BuiltInExn.CHR, pos);
      }
      return (char) (c + 1);
    }
  }

  /** @see BuiltIn#CHAR_TO_CSTRING */
  private static final Applicable CHAR_TO_CSTRING =
      new BaseApplicable1<String, Character>(BuiltIn.CHAR_TO_CSTRING) {
        @Override
        public String apply(Character character) {
          throw new UnsupportedOperationException("CHAR_TO_CSTRING");
        }
      };

  /** @see BuiltIn#CHAR_TO_LOWER */
  private static final Applicable CHAR_TO_LOWER =
      new BaseApplicable1<Character, Character>(BuiltIn.CHAR_TO_LOWER) {
        @Override
        public Character apply(Character c) {
          return Character.toLowerCase(c);
        }
      };

  /** @see BuiltIn#CHAR_TO_STRING */
  private static final Applicable CHAR_TO_STRING =
      new BaseApplicable1<String, Character>(BuiltIn.CHAR_TO_STRING) {
        @Override
        public String apply(Character c) {
          return Parsers.charToString(c);
        }
      };

  /** @see BuiltIn#CHAR_TO_UPPER */
  private static final Applicable CHAR_TO_UPPER =
      new BaseApplicable1<Character, Character>(BuiltIn.CHAR_TO_LOWER) {
        @Override
        public Character apply(Character c) {
          return Character.toUpperCase(c);
        }
      };

  /** @see BuiltIn#GENERAL_IGNORE */
  private static final Applicable GENERAL_IGNORE =
      new BaseApplicable1<Unit, Object>(BuiltIn.GENERAL_IGNORE) {
        @Override
        public Unit apply(Object arg) {
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#GENERAL_OP_O */
  private static final Applicable2 GENERAL_OP_O =
      new BaseApplicable2<Applicable1, Applicable1, Applicable1>(
          BuiltIn.GENERAL_OP_O) {
        @Override
        public Applicable1 apply(Applicable1 f, Applicable1 g) {
          return arg -> f.apply(g.apply(arg));
        }
      };

  /** @see BuiltIn#INT_ABS */
  private static final Applicable INT_ABS =
      new BaseApplicable1<Integer, Integer>(BuiltIn.INT_ABS) {
        @Override
        public Integer apply(Integer i) {
          return Math.abs(i);
        }
      };

  /** @see BuiltIn#INT_COMPARE */
  private static final Applicable2 INT_COMPARE =
      new BaseApplicable2<List, Integer, Integer>(BuiltIn.INT_COMPARE) {
        @Override
        public List apply(Integer a0, Integer a1) {
          return order(Integer.compare(a0, a1));
        }
      };

  /** @see BuiltIn#INT_FROM_INT */
  private static final Applicable1 INT_FROM_INT =
      identity(BuiltIn.INT_FROM_INT);

  /** @see BuiltIn#INT_FROM_LARGE */
  private static final Applicable1 INT_FROM_LARGE =
      identity(BuiltIn.INT_FROM_LARGE);

  /**
   * Pattern for integers (after '~' has been converted to '-'). ".", ".e",
   * ".e-", ".e5", "e7", "2.", ".5", "2.e5" are invalid; "-2", "5" are valid.
   */
  static final Pattern INT_PATTERN = Pattern.compile("^ *-?[0-9]+");

  /** @see BuiltIn#INT_FROM_STRING */
  private static final Applicable INT_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.INT_FROM_STRING) {
        @Override
        public List apply(String s) {
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
  private static final Applicable2 INT_MAX =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_MAX) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return Math.max(a0, a1);
        }
      };

  /** @see BuiltIn#INT_MAX_INT */
  private static final List INT_MAX_INT = optionSome(Integer.MAX_VALUE);

  /** @see BuiltIn#INT_MIN */
  private static final Applicable2 INT_MIN =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_MIN) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return Math.min(a0, a1);
        }
      };

  /** @see BuiltIn#INT_MIN_INT */
  private static final List INT_MIN_INT = optionSome(Integer.MAX_VALUE);

  /** @see BuiltIn#INT_DIV */
  private static final Applicable2 INT_DIV = new IntDiv(BuiltIn.INT_DIV);

  /** Implements {@link #INT_DIV} and {@link #OP_DIV}. */
  private static class IntDiv
      extends BaseApplicable2<Integer, Integer, Integer> {
    IntDiv(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public Integer apply(Integer a0, Integer a1) {
      return Math.floorDiv(a0, a1);
    }
  }

  /** @see BuiltIn#INT_MOD */
  private static final Applicable2 INT_MOD = new IntMod(BuiltIn.INT_MOD);

  /** Implements {@link #INT_MOD} and {@link #OP_MOD}. */
  private static class IntMod
      extends BaseApplicable2<Integer, Integer, Integer> {
    IntMod(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public Integer apply(Integer a0, Integer a1) {
      return Math.floorMod(a0, a1);
    }
  }

  /** @see BuiltIn#INT_PRECISION */
  private static final List INT_PRECISION = optionSome(32); // Java int 32 bits

  /** @see BuiltIn#INT_QUOT */
  private static final Applicable2 INT_QUOT =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_QUOT) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 / a1;
        }
      };

  /** @see BuiltIn#INT_REM */
  private static final Applicable2 INT_REM =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_REM) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 % a1;
        }
      };

  /** @see BuiltIn#INT_SAME_SIGN */
  private static final Applicable2 INT_SAME_SIGN =
      new BaseApplicable2<Boolean, Integer, Integer>(BuiltIn.INT_SAME_SIGN) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 < 0 && a1 < 0 || a0 == 0 && a1 == 0 || a0 > 0 && a1 > 0;
        }
      };

  /** @see BuiltIn#INT_SIGN */
  private static final Applicable1 INT_SIGN =
      new BaseApplicable1<Integer, Integer>(BuiltIn.INT_SIGN) {
        @Override
        public Integer apply(Integer i) {
          return Integer.compare(i, 0);
        }
      };

  /** @see BuiltIn#INT_TO_INT */
  private static final Applicable1 INT_TO_INT = identity(BuiltIn.INT_TO_INT);

  /** @see BuiltIn#INT_TO_LARGE */
  private static final Applicable1 INT_TO_LARGE =
      identity(BuiltIn.INT_TO_LARGE);

  /** @see BuiltIn#INT_TO_STRING */
  private static final Applicable1 INT_TO_STRING =
      new BaseApplicable1<String, Integer>(BuiltIn.INT_TO_STRING) {
        @Override
        public String apply(Integer f) {
          // Java's formatting is reasonably close to ML's formatting,
          // if we replace minus signs.
          final String s = Integer.toString(f);
          return s.replace('-', '~');
        }
      };

  /** @see BuiltIn#INTERACT_USE_SILENTLY */
  private static final Applicable INTERACT_USE_SILENTLY =
      new InteractUse(Pos.ZERO, true);

  /** @see BuiltIn#INTERACT_USE */
  private static final Applicable INTERACT_USE =
      new InteractUse(Pos.ZERO, false);

  /** Implements {@link #INTERACT_USE}. */
  private static class InteractUse extends BasePositionedApplicable {
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

  /** @see BuiltIn#LIST_ALL */
  private static final Applicable2 LIST_ALL = all(BuiltIn.LIST_ALL);

  private static Applicable2 all(final BuiltIn builtIn) {
    return new BaseApplicable2<Boolean, Applicable1, List>(builtIn) {
      @Override
      public Boolean apply(Applicable1 f, List list) {
        for (Object o : list) {
          if (!(Boolean) f.apply(o)) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** @see BuiltIn#LIST_APP */
  private static final Applicable2 LIST_APP = listApp(BuiltIn.LIST_APP);

  private static Applicable2 listApp(BuiltIn builtIn) {
    return new BaseApplicable2<Unit, Applicable1, List>(builtIn) {
      @Override
      public Unit apply(Applicable1 consumer, List list) {
        list.forEach(consumer::apply);
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#LIST_AT */
  private static final Applicable2 LIST_AT = union(BuiltIn.LIST_AT);

  private static Applicable2 union(final BuiltIn builtIn) {
    return new BaseApplicable2<List, List, List>(builtIn) {
      @Override
      public List apply(List list0, List list1) {
        return ImmutableList.builder().addAll(list0).addAll(list1).build();
      }
    };
  }

  /** @see BuiltIn#LIST_COLLATE */
  private static final Applicable2 LIST_COLLATE = collate(BuiltIn.LIST_COLLATE);

  private static Applicable2 collate(final BuiltIn builtIn) {
    return new BaseApplicable2<Object, Applicable1<List, List>, List>(builtIn) {
      @Override
      public Object apply(Applicable1<List, List> comparator, List tuple) {
        final List list0 = (List) tuple.get(0);
        final List list1 = (List) tuple.get(1);
        final int n0 = list0.size();
        final int n1 = list1.size();
        final int n = Math.min(n0, n1);
        for (int i = 0; i < n; i++) {
          final Object element0 = list0.get(i);
          final Object element1 = list1.get(i);
          final List compare =
              comparator.apply(FlatLists.of(element0, element1));
          if (!compare.get(0).equals("EQUAL")) {
            return compare;
          }
        }
        return order(Integer.compare(n0, n1));
      }
    };
  }

  /** @see BuiltIn#LIST_CONCAT */
  private static final Applicable LIST_CONCAT = listConcat(BuiltIn.LIST_CONCAT);

  private static Applicable listConcat(BuiltIn builtIn) {
    return new BaseApplicable1<List, List<List>>(builtIn) {
      @Override
      public List apply(List<List> lists) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (List list : lists) {
          builder.addAll(list);
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_DROP */
  private static final Applicable2 LIST_DROP = listDrop(BuiltIn.LIST_DROP);

  private static Applicable2<List, List, Integer> listDrop(BuiltIn builtIn) {
    return new BaseApplicable2<List, List, Integer>(builtIn) {
      @Override
      public List apply(List list, Integer i) {
        return list.subList(i, list.size());
      }
    };
  }

  /** @see BuiltIn#LIST_EXCEPT */
  private static final Applicable LIST_EXCEPT = new ListExcept(Pos.ZERO);

  /** Implements {@link #LIST_EXCEPT}. */
  private static class ListExcept
      extends BasePositionedApplicable1<List, List<List>> {
    ListExcept(Pos pos) {
      super(BuiltIn.LIST_EXCEPT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListExcept(pos);
    }

    @Override
    public List apply(List<List> lists) {
      if (lists.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      List collection = new ArrayList(lists.get(0));
      for (int i = 1; i < lists.size(); i++) {
        collection.removeAll(lists.get(i));
      }
      if (collection.size() == lists.get(0).size()) {
        collection = lists.get(0); // save the effort of making a copy
      }
      return ImmutableList.copyOf(collection);
    }
  }

  /** @see BuiltIn#LIST_EXISTS */
  private static final Applicable2 LIST_EXISTS = exists(BuiltIn.LIST_EXISTS);

  private static Applicable2 exists(final BuiltIn builtIn) {
    return new BaseApplicable2<Boolean, Applicable1, List>(builtIn) {
      @Override
      public Boolean apply(Applicable1 f, List list) {
        for (Object o : list) {
          if ((Boolean) f.apply(o)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  /** @see BuiltIn#LIST_FIND */
  private static final Applicable2 LIST_FIND = find(BuiltIn.LIST_FIND);

  private static Applicable2 find(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<Boolean, Object>, List>(
        builtIn) {
      @Override
      public List apply(Applicable1<Boolean, Object> f, List list) {
        for (Object o : list) {
          if (f.apply(o)) {
            return optionSome(o);
          }
        }
        return OPTION_NONE;
      }
    };
  }

  /** @see BuiltIn#LIST_FILTER */
  private static final Applicable2 LIST_FILTER =
      listFilter(BuiltIn.LIST_FILTER);

  private static Applicable2 listFilter(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<Boolean, Object>, List>(
        builtIn) {
      @Override
      public List apply(Applicable1<Boolean, Object> f, List list) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          if (f.apply(o)) {
            builder.add(o);
          }
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_FOLDL */
  private static final Applicable3 LIST_FOLDL =
      listFold0(BuiltIn.LIST_FOLDL, true);

  /** @see BuiltIn#LIST_FOLDR */
  private static final Applicable3 LIST_FOLDR =
      listFold0(BuiltIn.LIST_FOLDR, false);

  private static Applicable3 listFold0(BuiltIn builtIn, boolean left) {
    return new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
        builtIn) {
      @Override
      public Object apply(Applicable1<Object, List> f, Object init, List list) {
        Object b = init;
        for (Object a : left ? list : Lists.reverse(list)) {
          b = f.apply(FlatLists.of(a, b));
        }
        return b;
      }
    };
  }

  /** @see BuiltIn#LIST_GET_ITEM */
  private static final Applicable LIST_GET_ITEM =
      listGetItem(BuiltIn.LIST_GET_ITEM);

  private static Applicable listGetItem(BuiltIn builtIn) {
    return new BaseApplicable1<List, List>(builtIn) {
      @Override
      public List apply(List list) {
        if (list.isEmpty()) {
          return OPTION_NONE;
        } else {
          return optionSome(
              ImmutableList.of(list.get(0), list.subList(1, list.size())));
        }
      }
    };
  }

  /** @see BuiltIn#LIST_HD */
  private static final Applicable LIST_HD =
      new ListHd(BuiltIn.LIST_HD, Pos.ZERO);

  /** Implements {@link #LIST_HD}. */
  private static class ListHd extends BasePositionedApplicable1<Object, List> {
    ListHd(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListHd(builtIn, pos);
    }

    @Override
    public Object apply(List list) {
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.get(0);
    }
  }

  /** @see BuiltIn#LIST_INTERSECT */
  private static final Applicable LIST_INTERSECT = new ListIntersect(Pos.ZERO);

  /** Implements {@link #LIST_INTERSECT}. */
  private static class ListIntersect
      extends BasePositionedApplicable1<List, List<List>> {
    ListIntersect(Pos pos) {
      super(BuiltIn.LIST_INTERSECT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListIntersect(pos);
    }

    @Override
    public List apply(List<List> lists) {
      if (lists.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      List collection = new ArrayList(lists.get(0));
      for (int i = 1; i < lists.size(); i++) {
        final Set set = new HashSet(lists.get(i));
        collection.retainAll(set);
      }
      if (collection.size() == lists.get(0).size()) {
        collection = lists.get(0); // save the effort of making a copy
      }
      return ImmutableList.copyOf(collection);
    }
  }

  /** @see BuiltIn#LIST_LAST */
  private static final Applicable LIST_LAST =
      new ListLast(BuiltIn.LIST_LAST, Pos.ZERO);

  /** Implements {@link #LIST_LAST}. */
  private static class ListLast
      extends BasePositionedApplicable1<Object, List> {
    ListLast(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListLast(builtIn, pos);
    }

    @Override
    public Object apply(List list) {
      final int size = list.size();
      if (size == 0) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.get(size - 1);
    }
  }

  /** @see BuiltIn#LIST_LENGTH */
  private static final Applicable1 LIST_LENGTH = length(BuiltIn.LIST_LENGTH);

  private static Applicable1 length(BuiltIn builtIn) {
    return new BaseApplicable1<Integer, List>(builtIn) {
      @Override
      public Integer apply(List list) {
        return list.size();
      }
    };
  }

  /** @see BuiltIn#LIST_MAPI */
  private static final Applicable2 LIST_MAPI = listMapi(BuiltIn.LIST_MAPI);

  /** Implements {@link #LIST_MAPI}, {@link #VECTOR_MAPI}. */
  private static Applicable2<List, Applicable1, List> listMapi(
      BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1, List>(builtIn) {
      @Override
      public List apply(Applicable1 f, List vec) {
        ImmutableList.Builder b = ImmutableList.builder();
        forEachIndexed(vec, (e, i) -> b.add(f.apply(FlatLists.of(i, e))));
        return b.build();
      }
    };
  }

  /** @see BuiltIn#LIST_MAP */
  private static final Applicable2 LIST_MAP = listMap(BuiltIn.LIST_MAP);

  private static Applicable2 listMap(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1, List>(builtIn) {
      @Override
      public List apply(Applicable1 f, List list) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          builder.add(f.apply(o));
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_MAP_PARTIAL */
  private static final Applicable2 LIST_MAP_PARTIAL =
      listMapPartial(BuiltIn.LIST_MAP_PARTIAL);

  private static Applicable2 listMapPartial(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<List, Object>, List>(builtIn) {
      @Override
      public List apply(Applicable1<List, Object> f, List list) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          final List opt = f.apply(o);
          if (opt.size() == 2) {
            builder.add(opt.get(1));
          }
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_NTH */
  private static final Applicable LIST_NTH =
      new ListNth(BuiltIn.LIST_NTH, Pos.ZERO);

  /** Implements {@link #LIST_NTH} and {@link #VECTOR_SUB}. */
  private static class ListNth
      extends BasePositionedApplicable2<Object, List, Integer> {
    ListNth(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public ListNth withPos(Pos pos) {
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

  /** @see BuiltIn#LIST_NULL */
  private static final Applicable1 LIST_NULL = empty(BuiltIn.LIST_NULL);

  /** @see BuiltIn#LIST_PAIR_ALL_EQ */
  private static final Applicable2 LIST_PAIR_ALL_EQ =
      listPairAll(BuiltIn.LIST_PAIR_ALL_EQ, true);

  /** @see BuiltIn#LIST_PAIR_ALL */
  private static final Applicable2 LIST_PAIR_ALL =
      listPairAll(BuiltIn.LIST_PAIR_ALL, false);

  static Applicable2 listPairAll(BuiltIn builtIn, boolean eq) {
    return new BaseApplicable2<Boolean, Applicable1, List<List<Object>>>(
        builtIn) {
      @Override
      public Boolean apply(Applicable1 f, List<List<Object>> listPair) {
        final List<Object> list0 = listPair.get(0);
        final List<Object> list1 = listPair.get(1);
        if (eq && list0.size() != list1.size()) {
          return false;
        }
        final Iterator<Object> iter0 = list0.iterator();
        final Iterator<Object> iter1 = list1.iterator();
        while (iter0.hasNext() && iter1.hasNext()) {
          if (!(Boolean) f.apply(FlatLists.of(iter0.next(), iter1.next()))) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** Helper for {@link #LIST_PAIR_APP} and {@link #LIST_PAIR_APP_EQ}. */
  private static class ListPairApp
      extends BasePositionedApplicable2<Unit, Applicable1, List<List<Object>>> {
    ListPairApp(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairApp(builtIn, pos);
    }

    @Override
    public Unit apply(Applicable1 f, List<List<Object>> listPair) {
      final List<Object> list0 = listPair.get(0);
      final List<Object> list1 = listPair.get(1);
      if (builtIn == BuiltIn.LIST_PAIR_APP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final Iterator<Object> iter0 = list0.iterator();
      final Iterator<Object> iter1 = list1.iterator();
      while (iter0.hasNext() && iter1.hasNext()) {
        f.apply(FlatLists.of(iter0.next(), iter1.next()));
      }
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#LIST_PAIR_APP_EQ */
  private static final Applicable LIST_PAIR_APP_EQ =
      new ListPairApp(BuiltIn.LIST_PAIR_APP_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_APP */
  private static final Applicable LIST_PAIR_APP =
      new ListPairApp(BuiltIn.LIST_PAIR_APP, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_EXISTS */
  private static final Applicable2 LIST_PAIR_EXISTS =
      new BaseApplicable2<Boolean, Applicable1, List<List<Object>>>(
          BuiltIn.LIST_PAIR_EXISTS) {
        @Override
        public Boolean apply(Applicable1 f, List<List<Object>> listPair) {
          final List<Object> list0 = listPair.get(0);
          final List<Object> list1 = listPair.get(1);
          final Iterator<Object> iter0 = list0.iterator();
          final Iterator<Object> iter1 = list1.iterator();
          while (iter0.hasNext() && iter1.hasNext()) {
            if ((Boolean) f.apply(FlatLists.of(iter0.next(), iter1.next()))) {
              return true;
            }
          }
          return false;
        }
      };

  /**
   * Helper for {@link #LIST_PAIR_FOLDL}, {@link #LIST_PAIR_FOLDL_EQ}, {@link
   * #LIST_PAIR_FOLDR}, {@link #LIST_PAIR_FOLDR_EQ}.
   */
  private static class ListPairFold
      extends BasePositionedApplicable3<
          Object, Applicable1, Object, List<List<Object>>> {
    private final boolean eq;
    private final boolean left;

    ListPairFold(BuiltIn builtIn, Pos pos, boolean eq, boolean left) {
      super(builtIn, pos);
      this.eq = eq;
      this.left = left;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairFold(builtIn, pos, this.eq, this.left);
    }

    @Override
    public Object apply(Applicable1 f, Object init, List<List<Object>> pair) {
      final List<Object> list0 = pair.get(0);
      final List<Object> list1 = pair.get(1);
      if (eq && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final int n = Math.min(list0.size(), list1.size());
      Object b = init;
      if (left) {
        for (int i = 0; i < n; i++) {
          b = f.apply(FlatLists.of(list0.get(i), list1.get(i), b));
        }
      } else {
        for (int i = n - 1; i >= 0; i--) {
          b = f.apply(FlatLists.of(list0.get(i), list1.get(i), b));
        }
      }
      return b;
    }
  }

  /** @see BuiltIn#LIST_PAIR_FOLDL_EQ */
  private static final Applicable LIST_PAIR_FOLDL_EQ =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDL_EQ, Pos.ZERO, true, true);
  /** @see BuiltIn#LIST_PAIR_FOLDL */
  private static final Applicable LIST_PAIR_FOLDL =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDL, Pos.ZERO, false, true);

  /** @see BuiltIn#LIST_PAIR_FOLDR_EQ */
  private static final Applicable LIST_PAIR_FOLDR_EQ =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDR_EQ, Pos.ZERO, true, false);

  /** @see BuiltIn#LIST_PAIR_FOLDR */
  private static final Applicable LIST_PAIR_FOLDR =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDR, Pos.ZERO, false, false);

  /** Helper for {@link #LIST_PAIR_MAP}, {@link #LIST_PAIR_MAP_EQ}. */
  private static class ListPairMap
      extends BasePositionedApplicable2<
          Object, Applicable1, List<List<Object>>> {
    private final boolean equal;

    ListPairMap(BuiltIn builtIn, Pos pos, boolean equal) {
      super(builtIn, pos);
      this.equal = equal;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairMap(builtIn, pos, equal);
    }

    @Override
    public Object apply(Applicable1 f, List<List<Object>> listPair) {
      List<Object> list0 = listPair.get(0);
      List<Object> list1 = listPair.get(1);
      if (equal && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final ImmutableList.Builder<Object> result = ImmutableList.builder();
      forEach(list0, list1, (a, b) -> result.add(f.apply(FlatLists.of(a, b))));
      return result.build();
    }
  }

  /** @see BuiltIn#LIST_PAIR_MAP_EQ */
  private static final Applicable LIST_PAIR_MAP_EQ =
      new ListPairMap(BuiltIn.LIST_PAIR_MAP_EQ, Pos.ZERO, true);

  /** @see BuiltIn#LIST_PAIR_MAP */
  private static final Applicable LIST_PAIR_MAP =
      new ListPairMap(BuiltIn.LIST_PAIR_MAP, Pos.ZERO, false);

  /** @see BuiltIn#LIST_PAIR_UNZIP */
  private static final Applicable LIST_PAIR_UNZIP = new ListPairUnzip(Pos.ZERO);

  /** Implements {@link #LIST_PAIR_UNZIP}. */
  private static class ListPairUnzip
      extends BasePositionedApplicable1<List<List>, List<List>> {
    ListPairUnzip(Pos pos) {
      super(BuiltIn.LIST_PAIR_UNZIP, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairUnzip(pos);
    }

    @Override
    public List<List> apply(List<List> lists) {
      if (lists.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      final ImmutableList.Builder<Object> builder0 = ImmutableList.builder();
      final ImmutableList.Builder<Object> builder1 = ImmutableList.builder();
      for (List<Object> pair : lists) {
        builder0.add(pair.get(0));
        builder1.add(pair.get(1));
      }
      return ImmutableList.of(builder0.build(), builder1.build());
    }
  }

  /** Helper for {@link #LIST_PAIR_ZIP} and {@link #LIST_PAIR_ZIP_EQ}. */
  private static class ListPairZip
      extends BasePositionedApplicable2<List, List, List> {
    ListPairZip(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairZip(builtIn, pos);
    }

    @Override
    public List apply(List list0, List list1) {
      if (builtIn == BuiltIn.LIST_PAIR_ZIP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final List<Object> result = new ArrayList<>();
      forEach(list0, list1, (a, b) -> result.add(FlatLists.of(a, b)));
      return result;
    }
  }

  /** @see BuiltIn#LIST_PAIR_ZIP_EQ */
  private static final Applicable LIST_PAIR_ZIP_EQ =
      new ListPairZip(BuiltIn.LIST_PAIR_ZIP_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_ZIP */
  private static final Applicable LIST_PAIR_ZIP =
      new ListPairZip(BuiltIn.LIST_PAIR_ZIP, Pos.ZERO);

  /** @see BuiltIn#LIST_PARTITION */
  private static final Applicable2 LIST_PARTITION =
      listPartition0(BuiltIn.LIST_PARTITION);

  private static Applicable2 listPartition0(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<Boolean, Object>, List>(
        builtIn) {
      @Override
      public List apply(Applicable1<Boolean, Object> f, List list) {
        final ImmutableList.Builder trueBuilder = ImmutableList.builder();
        final ImmutableList.Builder falseBuilder = ImmutableList.builder();
        for (Object o : list) {
          (f.apply(o) ? trueBuilder : falseBuilder).add(o);
        }
        return ImmutableList.of(trueBuilder.build(), falseBuilder.build());
      }
    };
  }

  /** @see BuiltIn#LIST_REV_APPEND */
  private static final Applicable2 LIST_REV_APPEND =
      new BaseApplicable2<List, List, List>(BuiltIn.LIST_REV_APPEND) {
        @Override
        public List apply(List list0, List list1) {
          return ImmutableList.builder()
              .addAll(Lists.reverse(list0))
              .addAll(list1)
              .build();
        }
      };

  /** @see BuiltIn#LIST_REV */
  private static final Applicable LIST_REV =
      new BaseApplicable1<List, List>(BuiltIn.LIST_REV) {
        @Override
        public List apply(List list) {
          return Lists.reverse(list);
        }
      };

  /** @see BuiltIn#LIST_TABULATE */
  private static final Applicable LIST_TABULATE =
      new ListTabulate(BuiltIn.LIST_TABULATE, Pos.ZERO);

  /** Implements {@link #LIST_TABULATE}. */
  private static class ListTabulate
      extends BasePositionedApplicable2<Object, Integer, Applicable1> {
    ListTabulate(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTabulate(builtIn, pos);
    }

    @Override
    public Object apply(Integer count, Applicable1 f) {
      if (count < 0) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      final ImmutableList.Builder builder = ImmutableList.builder();
      for (int i = 0; i < count; i++) {
        builder.add(f.apply(i));
      }
      return builder.build();
    }
  }

  /** @see BuiltIn#LIST_TAKE */
  private static final Applicable LIST_TAKE =
      new ListTake(BuiltIn.LIST_TAKE, Pos.ZERO);

  /** Implements {@link #LIST_TAKE}. */
  private static class ListTake
      extends BasePositionedApplicable2<List, List, Integer> {
    ListTake(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public ListTake withPos(Pos pos) {
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

  /** @see BuiltIn#LIST_TL */
  private static final Applicable LIST_TL =
      new ListTl(BuiltIn.LIST_TL, Pos.ZERO);

  /** Implements {@link #LIST_TL}. */
  private static class ListTl extends BasePositionedApplicable1<List, List> {
    ListTl(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTl(builtIn, pos);
    }

    @Override
    public List apply(List list) {
      final int size = list.size();
      if (size == 0) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.subList(1, size);
    }
  }

  /** @see BuiltIn#MATH_ACOS */
  private static final Applicable MATH_ACOS =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_ACOS) {
        @Override
        public Float apply(Float f) {
          return (float) Math.acos(f);
        }
      };

  /** @see BuiltIn#MATH_ASIN */
  private static final Applicable MATH_ASIN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_ASIN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.asin(f);
        }
      };

  /** @see BuiltIn#MATH_ATAN */
  private static final Applicable MATH_ATAN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_ATAN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.atan(f);
        }
      };

  /** @see BuiltIn#MATH_ATAN2 */
  private static final Applicable2 MATH_ATAN2 =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.MATH_ATAN2) {
        @Override
        public Float apply(Float arg0, Float arg1) {
          return (float) Math.atan2(arg0, arg1);
        }
      };

  /** @see BuiltIn#MATH_COS */
  private static final Applicable MATH_COS =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_COS) {
        @Override
        public Float apply(Float f) {
          return (float) Math.cos(f);
        }
      };

  /** @see BuiltIn#MATH_COSH */
  private static final Applicable MATH_COSH =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_COSH) {
        @Override
        public Float apply(Float f) {
          return (float) Math.cosh(f);
        }
      };

  /** @see BuiltIn#MATH_E */
  private static final float MATH_E = (float) Math.E;

  /** @see BuiltIn#MATH_EXP */
  private static final Applicable MATH_EXP =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_EXP) {
        @Override
        public Float apply(Float f) {
          return (float) Math.exp(f);
        }
      };

  /** @see BuiltIn#MATH_LN */
  private static final Applicable MATH_LN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_LN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.log(f);
        }
      };

  /** @see BuiltIn#MATH_LOG10 */
  private static final Applicable MATH_LOG10 =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_LOG10) {
        @Override
        public Float apply(Float f) {
          return (float) Math.log10(f);
        }
      };

  /** @see BuiltIn#MATH_PI */
  private static final float MATH_PI = (float) Math.PI;

  /** @see BuiltIn#MATH_POW */
  private static final Applicable2 MATH_POW =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.MATH_POW) {
        @Override
        public Float apply(Float arg0, Float arg1) {
          return (float) Math.pow(arg0, arg1);
        }
      };

  /** @see BuiltIn#MATH_SIN */
  private static final Applicable MATH_SIN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_SIN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.sin(f);
        }
      };

  /** @see BuiltIn#MATH_SINH */
  private static final Applicable MATH_SINH =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_SINH) {
        @Override
        public Float apply(Float f) {
          return (float) Math.sinh(f);
        }
      };

  /** @see BuiltIn#MATH_SQRT */
  private static final Applicable MATH_SQRT =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_SQRT) {
        @Override
        public Float apply(Float f) {
          return (float) Math.sqrt(f);
        }
      };

  /** @see BuiltIn#MATH_TAN */
  private static final Applicable MATH_TAN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_TAN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.tan(f);
        }
      };

  /** @see BuiltIn#MATH_TANH */
  private static final Applicable MATH_TANH =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_TANH) {
        @Override
        public Float apply(Float f) {
          return (float) Math.tanh(f);
        }
      };

  /** An applicable that negates a boolean value. */
  private static final Applicable NOT =
      new BaseApplicable1<Boolean, Boolean>(BuiltIn.NOT) {
        @Override
        public Boolean apply(Boolean b) {
          return !(Boolean) b;
        }
      };

  /** @see BuiltIn#OP_CARET */
  private static final Applicable2 OP_CARET =
      new BaseApplicable2<String, String, String>(BuiltIn.OP_CARET) {
        @Override
        public String apply(String a0, String a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#OP_CONS */
  private static final Applicable2 OP_CONS =
      new BaseApplicable2<List, Object, Iterable>(BuiltIn.OP_CONS) {
        @Override
        public List apply(Object e, Iterable iterable) {
          return ImmutableList.builder().add(e).addAll(iterable).build();
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
  private static final Applicable2 OP_DIV = new IntDiv(BuiltIn.OP_DIV);

  /** @see BuiltIn#OP_ELEM */
  private static final Applicable2 OP_ELEM =
      new BaseApplicable2<Boolean, Object, List>(BuiltIn.OP_ELEM) {
        @Override
        public Boolean apply(Object a0, List a1) {
          return a1.contains(a0);
        }
      };

  /** @see BuiltIn#OP_EQ */
  private static final Applicable2 OP_EQ =
      new BaseApplicable2<Boolean, Object, Object>(BuiltIn.OP_EQ) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#OP_GE */
  private static final Applicable2 OP_GE =
      new BaseApplicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_GE) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) >= 0;
        }
      };

  /** @see BuiltIn#OP_GT */
  private static final Applicable2 OP_GT =
      new BaseApplicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_GT) {
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
  private static final Applicable2 OP_LE =
      new BaseApplicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_LE) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) <= 0;
        }
      };

  /** @see BuiltIn#OP_LT */
  private static final Applicable2 OP_LT =
      new BaseApplicable2<Boolean, Comparable, Comparable>(BuiltIn.OP_LT) {
        @Override
        public Boolean apply(Comparable a0, Comparable a1) {
          if (a0 instanceof Float && Float.isNaN((Float) a0)
              || a1 instanceof Float && Float.isNaN((Float) a1)) {
            return false;
          }
          return a0.compareTo(a1) < 0;
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
  private static final Applicable2 OP_MOD = new IntMod(BuiltIn.OP_MOD);

  /** @see BuiltIn#OP_NE */
  private static final Applicable2 OP_NE =
      new BaseApplicable2<Boolean, Object, Object>(BuiltIn.OP_NE) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return !a0.equals(a1);
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

  /** @see BuiltIn#OP_NOT_ELEM */
  private static final Applicable2 OP_NOT_ELEM =
      new BaseApplicable2<Boolean, Object, List>(BuiltIn.OP_NOT_ELEM) {
        @Override
        public Boolean apply(Object a0, List a1) {
          return !a1.contains(a0);
        }
      };

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

  /** @see BuiltIn#OPTION_APP */
  private static final Applicable2 OPTION_APP =
      new BaseApplicable2<Unit, Applicable1, List>(BuiltIn.OPTION_APP) {
        @Override
        public Unit apply(Applicable1 f, List a) {
          if (a.size() == 2) {
            f.apply(a.get(1));
          }
          return Unit.INSTANCE;
        }

        @Override
        public String toString() {
          return super.toString();
        }

        @Override
        public Describer describe(Describer describer) {
          return super.describe(describer);
        }
      };

  /** @see BuiltIn#OPTION_COMPOSE_PARTIAL */
  private static final Applicable2 OPTION_COMPOSE_PARTIAL =
      new BaseApplicable2<Applicable1, Applicable1<List, Object>, Applicable1>(
          BuiltIn.OPTION_COMPOSE_PARTIAL) {
        @Override
        public Applicable1 apply(Applicable1<List, Object> f, Applicable1 g) {
          return (Applicable1<@NonNull List, @NonNull Object>)
              arg -> {
                final List ga = (List) g.apply(arg); // g (a)
                if (ga.size() == 2) { // SOME v
                  return f.apply(ga.get(1)); // f (v)
                }
                return ga; // NONE
              };
        }
      };

  /** @see BuiltIn#OPTION_COMPOSE */
  private static final Applicable2 OPTION_COMPOSE =
      new BaseApplicable2<Applicable1, Applicable1, Applicable1<List, Object>>(
          BuiltIn.OPTION_COMPOSE) {
        @Override
        public Applicable1 apply(Applicable1 f, Applicable1<List, Object> g) {
          return (Applicable1<@NonNull List, @NonNull Object>)
              arg -> {
                final List ga = g.apply(arg); // g (a)
                if (ga.size() == 2) { // SOME v
                  return optionSome(f.apply(ga.get(1))); // SOME (f (v))
                }
                return ga; // NONE
              };
        }
      };

  /** @see BuiltIn#OPTION_FILTER */
  private static final Applicable2 OPTION_FILTER =
      new BaseApplicable2<List, Applicable1, Object>(BuiltIn.OPTION_FILTER) {
        @Override
        public List apply(Applicable1 f, Object arg) {
          if ((Boolean) f.apply(arg)) {
            return optionSome(arg);
          } else {
            return OPTION_NONE;
          }
        }
      };

  /** @see BuiltIn#OPTION_GET_OPT */
  private static final Applicable2 OPTION_GET_OPT =
      new BaseApplicable2<Object, List, Object>(BuiltIn.OPTION_GET_OPT) {
        @Override
        public Object apply(List opt, Object o) {
          if (opt.size() == 2) {
            assert opt.get(0).equals("SOME");
            return opt.get(1); // SOME has 2 elements, NONE has 1
          }
          return o;
        }
      };

  /** @see BuiltIn#OPTION_IS_SOME */
  private static final Applicable OPTION_IS_SOME =
      new BaseApplicable1<Boolean, List>(BuiltIn.OPTION_IS_SOME) {
        @Override
        public Boolean apply(List opt) {
          return opt.size() == 2; // SOME has 2 elements, NONE has 1
        }
      };

  /** @see BuiltIn#OPTION_JOIN */
  private static final Applicable OPTION_JOIN =
      new BaseApplicable1<List, List<List>>(BuiltIn.OPTION_JOIN) {
        @Override
        public List apply(List<List> opt) {
          return opt.size() == 2
              ? opt.get(1) // SOME(SOME(v)) -> SOME(v), SOME(NONE) -> NONE
              : opt; // NONE -> NONE
        }
      };

  /** @see BuiltIn#OPTION_MAP_PARTIAL */
  private static final Applicable2 OPTION_MAP_PARTIAL =
      new BaseApplicable2<List, Applicable1<List, Object>, List>(
          BuiltIn.OPTION_MAP_PARTIAL) {
        @Override
        public List apply(Applicable1<List, Object> f, List a) {
          if (a.size() == 2) { // SOME v
            return f.apply(a.get(1)); // f v
          }
          return a; // NONE
        }
      };

  /** @see BuiltIn#OPTION_MAP */
  private static final Applicable2 OPTION_MAP =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.OPTION_MAP) {
        @Override
        public List apply(Applicable1 f, List a) {
          if (a.size() == 2) { // SOME v
            return optionSome(f.apply(a.get(1))); // SOME (f v)
          }
          return a; // NONE
        }
      };

  /**
   * Value of {@link BuiltIn.Constructor#OPTION_NONE}.
   *
   * @see #optionSome(Object)
   */
  private static final List OPTION_NONE = ImmutableList.of("NONE");

  /**
   * Creates a value of {@code SOME v}.
   *
   * @see net.hydromatic.morel.compile.BuiltIn.Constructor#OPTION_SOME
   * @see #OPTION_NONE
   */
  private static List optionSome(Object o) {
    return ImmutableList.of(BuiltIn.Constructor.OPTION_SOME.constructor, o);
  }

  /** @see BuiltIn#OPTION_VAL_OF */
  private static final Applicable OPTION_VAL_OF = new OptionValOf(Pos.ZERO);

  /** Implements {@link #OPTION_VAL_OF}. */
  private static class OptionValOf
      extends BasePositionedApplicable1<Object, List> {
    OptionValOf(Pos pos) {
      super(BuiltIn.OPTION_VAL_OF, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new OptionValOf(pos);
    }

    @Override
    public Object apply(List opt) {
      if (opt.size() == 2) { // SOME has 2 elements, NONE has 1
        return opt.get(1);
      } else {
        throw new MorelRuntimeException(BuiltInExn.OPTION, pos);
      }
    }
  }

  /** @see BuiltIn#REAL_ABS */
  private static final Applicable REAL_ABS =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_ABS) {
        @Override
        public Float apply(Float f) {
          return Math.abs(f);
        }
      };

  /** @see BuiltIn#REAL_CEIL */
  private static final Applicable REAL_CEIL =
      new BaseApplicable1<Integer, Float>(BuiltIn.REAL_CEIL) {
        @Override
        public Integer apply(Float f) {
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

  /** Implements {@link #REAL_CHECK_FLOAT}. */
  private static class RealCheckFloat
      extends BasePositionedApplicable1<Float, Float> {
    RealCheckFloat(Pos pos) {
      super(BuiltIn.REAL_CHECK_FLOAT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealCheckFloat(pos);
    }

    @Override
    public Float apply(Float f) {
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

  /** Implements {@link #REAL_COMPARE}. */
  private static class RealCompare
      extends BasePositionedApplicable2<List, Float, Float> {
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
  private static final Applicable2 REAL_COPY_SIGN =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_COPY_SIGN) {
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
      new BaseApplicable1<Integer, Float>(BuiltIn.REAL_FLOOR) {
        @Override
        public Integer apply(Float f) {
          if (f >= 0) {
            return -Math.round(-f);
          } else {
            return Math.round(f);
          }
        }
      };

  /** @see BuiltIn#REAL_FROM_INT */
  private static final Applicable REAL_FROM_INT =
      new BaseApplicable1<Float, Integer>(BuiltIn.REAL_FROM_INT) {
        @Override
        public Float apply(Integer i) {
          return i.floatValue();
        }
      };

  /** @see BuiltIn#REAL_FROM_MAN_EXP */
  private static final Applicable2 REAL_FROM_MAN_EXP =
      new BaseApplicable2<Float, Integer, Float>(BuiltIn.REAL_FROM_MAN_EXP) {
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
      new BaseApplicable1<List, String>(BuiltIn.REAL_FROM_STRING) {
        @Override
        public List<Float> apply(String s) {
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
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_IS_FINITE) {
        @Override
        public Boolean apply(Float f) {
          return Float.isFinite(f);
        }
      };

  /** @see BuiltIn#REAL_IS_NAN */
  private static final Applicable REAL_IS_NAN =
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_IS_NAN) {
        @Override
        public Boolean apply(Float f) {
          return Float.isNaN(f);
        }
      };

  /** @see BuiltIn#REAL_IS_NORMAL */
  private static final Applicable REAL_IS_NORMAL =
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_IS_NORMAL) {
        @Override
        public Boolean apply(Float f) {
          return Float.isFinite(f)
              && (f >= Float.MIN_NORMAL || f <= -Float.MIN_NORMAL);
        }
      };

  /** @see BuiltIn#REAL_MAX */
  private static final Applicable2 REAL_MAX =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_MAX) {
        @Override
        public Float apply(Float f0, Float f1) {
          return Float.isNaN(f0) ? f1 : Float.isNaN(f1) ? f0 : Math.max(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_MAX_FINITE */
  private static final float REAL_MAX_FINITE = Float.MAX_VALUE;

  /** @see BuiltIn#REAL_MIN */
  private static final Applicable2 REAL_MIN =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_MIN) {
        @Override
        public Float apply(Float f0, Float f1) {
          return Float.isNaN(f0) ? f1 : Float.isNaN(f1) ? f0 : Math.min(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_MIN_POS */
  private static final float REAL_MIN_POS = Float.MIN_VALUE;

  /** @see BuiltIn#REAL_MIN_NORMAL_POS */
  private static final float REAL_MIN_NORMAL_POS = Float.MIN_NORMAL;

  /** @see BuiltIn#REAL_NEG_INF */
  private static final float REAL_NEG_INF = Float.NEGATIVE_INFINITY;

  /** @see BuiltIn#REAL_POS_INF */
  private static final float REAL_POS_INF = Float.POSITIVE_INFINITY;

  /** @see BuiltIn#REAL_PRECISION */
  // value is from jdk.internal.math.FloatConsts#SIGNIFICAND_WIDTH
  // (32 bit IEEE floating point is 1 sign bit, 8 bit exponent,
  // 23 bit mantissa)
  private static final int REAL_PRECISION = 24;

  /** @see BuiltIn#REAL_RADIX */
  private static final int REAL_RADIX = 2;

  /** @see BuiltIn#REAL_REAL_CEIL */
  private static final Applicable REAL_REAL_CEIL =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_CEIL) {
        @Override
        public Float apply(Float f) {
          return (float) Math.ceil(f);
        }
      };

  /** @see BuiltIn#REAL_REAL_FLOOR */
  private static final Applicable REAL_REAL_FLOOR =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_FLOOR) {
        @Override
        public Float apply(Float f) {
          return (float) Math.floor(f);
        }
      };

  /** @see BuiltIn#REAL_REAL_MOD */
  private static final Applicable REAL_REAL_MOD =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_MOD) {
        @Override
        public Float apply(Float f) {
          if (Float.isInfinite(f)) {
            // realMod posInf  => 0.0
            // realMod negInf  => ~0.0
            return f > 0f ? 0f : -0f;
          }
          return f % 1;
        }
      };

  /** @see BuiltIn#REAL_REAL_ROUND */
  private static final Applicable REAL_REAL_ROUND =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_ROUND) {
        @Override
        public Float apply(Float f) {
          return (float) Math.rint(f);
        }
      };

  /** @see BuiltIn#REAL_REAL_TRUNC */
  private static final Applicable REAL_REAL_TRUNC =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_TRUNC) {
        @Override
        public Float apply(Float f) {
          final float frac = f % 1;
          return f - frac;
        }
      };

  /** @see BuiltIn#REAL_REM */
  private static final Applicable2 REAL_REM =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_REM) {
        @Override
        public Float apply(Float x, Float y) {
          return x % y;
        }
      };

  /** @see BuiltIn#REAL_ROUND */
  private static final Applicable REAL_ROUND =
      new BaseApplicable1<Integer, Float>(BuiltIn.REAL_ROUND) {
        @Override
        public Integer apply(Float f) {
          return Math.round(f);
        }
      };

  /** @see BuiltIn#REAL_SAME_SIGN */
  private static final Applicable2 REAL_SAME_SIGN =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_SAME_SIGN) {
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

  /** Implements {@link #REAL_COMPARE}. */
  private static class RealSign
      extends BasePositionedApplicable1<Integer, Float> {
    RealSign(Pos pos) {
      super(BuiltIn.REAL_SIGN, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealSign(pos);
    }

    @Override
    public Integer apply(Float f) {
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
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_SIGN_BIT) {
        @Override
        public Boolean apply(Float f) {
          return isNegative(f);
        }
      };

  /** @see BuiltIn#REAL_SPLIT */
  private static final Applicable REAL_SPLIT =
      new BaseApplicable1<List, Float>(BuiltIn.REAL_SPLIT) {
        @Override
        public List apply(Float f) {
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
      new BaseApplicable1<List, Float>(BuiltIn.REAL_TO_MAN_EXP) {
        @Override
        public List apply(Float f) {
          // In IEEE 32 bit floating point,
          // bit 31 is the sign (1 bit);
          // bits 30 - 23 are the exponent (8 bits);
          // bits 22 - 0 are the mantissa (23 bits).
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
      new BaseApplicable1<String, Float>(BuiltIn.REAL_TO_STRING) {
        @Override
        public String apply(Float f) {
          // Java's formatting is reasonably close to ML's formatting,
          // if we replace minus signs.
          return floatToString(f);
        }
      };

  /** @see BuiltIn#REAL_TRUNC */
  private static final Applicable REAL_TRUNC =
      new BaseApplicable1<Integer, Float>(BuiltIn.REAL_TRUNC) {
        @Override
        public Integer apply(Float f) {
          return f.intValue();
        }
      };

  /** @see BuiltIn#REAL_UNORDERED */
  private static final Applicable2 REAL_UNORDERED =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_UNORDERED) {
        @Override
        public Boolean apply(Float f0, Float f1) {
          return Float.isNaN(f0) || Float.isNaN(f1);
        }
      };

  /** @see BuiltIn#RELATIONAL_COMPARE */
  private static final Applicable RELATIONAL_COMPARE = Comparer.INITIAL;

  /** @see BuiltIn#RELATIONAL_COUNT */
  private static final Applicable1 RELATIONAL_COUNT =
      length(BuiltIn.RELATIONAL_COUNT);

  /** @see BuiltIn#RELATIONAL_EMPTY */
  private static final Applicable1 RELATIONAL_EMPTY =
      empty(BuiltIn.RELATIONAL_EMPTY);

  private static Applicable1<Boolean, List> empty(BuiltIn builtIn) {
    return new BaseApplicable1<Boolean, List>(builtIn) {
      @Override
      public Boolean apply(List list) {
        return list.isEmpty();
      }
    };
  }

  /** @see BuiltIn#RELATIONAL_ITERATE */
  private static final Applicable2 RELATIONAL_ITERATE =
      new BaseApplicable2<List, List, Applicable1<List, List>>(
          BuiltIn.RELATIONAL_ITERATE) {
        @Override
        public List apply(
            final List initialList, Applicable1<List, List> update) {
          List list = initialList;
          List newList = list;
          for (; ; ) {
            List nextList = update.apply(FlatLists.of(list, newList));
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
                ImmutableList.builder().addAll(list).addAll(nextList).build();
            newList = nextList;
          }
        }
      };

  /** @see BuiltIn#RELATIONAL_MAX */
  private static final Applicable RELATIONAL_MAX =
      new BaseApplicable1<Object, List>(BuiltIn.RELATIONAL_MAX) {
        @Override
        public Object apply(List list) {
          return Ordering.natural().max(list);
        }
      };

  /** @see BuiltIn#RELATIONAL_MIN */
  private static final Applicable RELATIONAL_MIN =
      new BaseApplicable1<Object, List>(BuiltIn.RELATIONAL_MIN) {
        @Override
        public Object apply(List list) {
          return Ordering.natural().min(list);
        }
      };

  /** @see BuiltIn#RELATIONAL_NON_EMPTY */
  private static final Applicable1 RELATIONAL_NON_EMPTY =
      new BaseApplicable1<Boolean, List>(BuiltIn.RELATIONAL_NON_EMPTY) {
        @Override
        public Boolean apply(List list) {
          return !list.isEmpty();
        }
      };

  /** @see BuiltIn#RELATIONAL_ONLY */
  private static final Applicable RELATIONAL_ONLY =
      new RelationalOnly(Pos.ZERO);

  /** Implements {@link #RELATIONAL_ONLY}. */
  private static class RelationalOnly
      extends BasePositionedApplicable1<Object, List> {
    RelationalOnly(Pos pos) {
      super(BuiltIn.RELATIONAL_ONLY, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalOnly(pos);
    }

    @Override
    public Object apply(List list) {
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      if (list.size() > 1) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      return list.get(0);
    }
  }

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

  /** @see BuiltIn#STRING_COLLATE */
  private static final Applicable2 STRING_COLLATE =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.STRING_COLLATE) {
        @Override
        public List apply(Applicable1 comparator, List tuple) {
          final String string0 = (String) tuple.get(0);
          final String string1 = (String) tuple.get(1);
          final int n0 = string0.length();
          final int n1 = string1.length();
          final int n = Math.min(n0, n1);
          for (int i = 0; i < n; i++) {
            final char char0 = string0.charAt(i);
            final char char1 = string1.charAt(i);
            final List compare =
                (List) comparator.apply(FlatLists.of(char0, char1));
            if (!compare.get(0).equals("EQUAL")) {
              return compare;
            }
          }
          return order(Integer.compare(n0, n1));
        }
      };

  /** @see BuiltIn#STRING_COMPARE */
  private static final Applicable2 STRING_COMPARE =
      new BaseApplicable2<List, String, String>(BuiltIn.STRING_COMPARE) {
        @Override
        public List apply(String a0, String a1) {
          return order(a0.compareTo(a1));
        }
      };

  /** @see BuiltIn#STRING_CONCAT_WITH */
  private static final Applicable2 STRING_CONCAT_WITH =
      new StringConcatWith(BuiltIn.STRING_CONCAT_WITH, Pos.ZERO);

  /** Implements {@link #STRING_CONCAT_WITH}. */
  private static class StringConcatWith
      extends BasePositionedApplicable2<String, String, List<String>> {
    StringConcatWith(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public StringConcatWith withPos(Pos pos) {
      return new StringConcatWith(BuiltIn.STRING_CONCAT_WITH, pos);
    }

    @Override
    public String apply(String separator, List<String> list) {
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
  }

  /** @see BuiltIn#STRING_CONCAT */
  private static final Applicable1 STRING_CONCAT =
      new StringConcat(BuiltIn.STRING_CONCAT, Pos.ZERO);

  /** Implements {@link #STRING_CONCAT}. */
  private static class StringConcat
      extends BasePositionedApplicable1<String, List<String>> {
    StringConcat(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public StringConcat withPos(Pos pos) {
      return new StringConcat(BuiltIn.STRING_CONCAT, pos);
    }

    @Override
    public String apply(List<String> list) {
      long n = 0;
      for (String s : list) {
        n += s.length();
      }
      if (n > STRING_MAX_SIZE) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      return String.join("", list);
    }
  }

  /** @see BuiltIn#STRING_EXPLODE */
  private static final Applicable1 STRING_EXPLODE =
      new BaseApplicable1<List, String>(BuiltIn.STRING_EXPLODE) {
        @Override
        public List apply(String s) {
          return MapList.of(s.length(), s::charAt);
        }
      };

  /** @see BuiltIn#STRING_EXTRACT */
  private static final Applicable STRING_EXTRACT = new StringExtract(Pos.ZERO);

  /** Implements {@link #STRING_EXTRACT}. */
  private static class StringExtract
      extends BasePositionedApplicable3<String, String, Integer, List> {
    StringExtract(Pos pos) {
      super(BuiltIn.STRING_EXTRACT, pos);
    }

    @Override
    public StringExtract withPos(Pos pos) {
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

  /** @see BuiltIn#STRING_FIELDS */
  private static final Applicable2 STRING_FIELDS =
      new StringTokenize(BuiltIn.STRING_FIELDS);

  /** @see BuiltIn#STRING_IMPLODE */
  private static final Applicable STRING_IMPLODE =
      new BaseApplicable1<String, List<Character>>(BuiltIn.STRING_IMPLODE) {
        @Override
        public String apply(List<Character> characters) {
          // Note: In theory this function should raise Size, but it is not
          // possible in practice because List.size() is never larger than
          // Integer.MAX_VALUE.
          return String.valueOf(Chars.toArray(characters));
        }
      };

  /** @see BuiltIn#STRING_IS_PREFIX */
  private static final Applicable2 STRING_IS_PREFIX =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_IS_PREFIX) {
        @Override
        public Boolean apply(String s, String s2) {
          return s2.startsWith(s);
        }
      };

  /** @see BuiltIn#STRING_IS_SUBSTRING */
  private static final Applicable2 STRING_IS_SUBSTRING =
      new BaseApplicable2<Boolean, String, String>(
          BuiltIn.STRING_IS_SUBSTRING) {
        @Override
        public Boolean apply(String s, String s2) {
          return s2.contains(s);
        }
      };

  /** @see BuiltIn#STRING_IS_SUFFIX */
  private static final Applicable2 STRING_IS_SUFFIX =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_IS_SUFFIX) {
        @Override
        public Boolean apply(String s, String s2) {
          return s2.endsWith(s);
        }
      };

  /** @see BuiltIn#STRING_MAP */
  private static final Applicable2 STRING_MAP =
      new BaseApplicable2<String, Applicable1<Character, Character>, String>(
          BuiltIn.STRING_MAP) {
        @Override
        public String apply(Applicable1<Character, Character> f, String s) {
          final StringBuilder buf = new StringBuilder();
          for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final char c2 = f.apply(c);
            buf.append(c2);
          }
          return buf.toString();
        }
      };

  /** @see BuiltIn#STRING_MAX_SIZE */
  private static final Integer STRING_MAX_SIZE = Integer.MAX_VALUE;

  /** @see BuiltIn#STRING_OP_CARET */
  private static final Applicable2 STRING_OP_CARET =
      new BaseApplicable2<String, String, String>(BuiltIn.STRING_OP_CARET) {
        @Override
        public String apply(String a0, String a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#STRING_OP_GE */
  private static final Applicable2 STRING_OP_GE =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_GE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) >= 0;
        }
      };

  /** @see BuiltIn#STRING_OP_GT */
  private static final Applicable2 STRING_OP_GT =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_GT) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) > 0;
        }
      };

  /** @see BuiltIn#STRING_OP_LE */
  private static final Applicable2 STRING_OP_LE =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_LE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) <= 0;
        }
      };

  /** @see BuiltIn#STRING_OP_LT */
  private static final Applicable2 STRING_OP_LT =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_LT) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) < 0;
        }
      };

  /** @see BuiltIn#STRING_SIZE */
  private static final Applicable1 STRING_SIZE =
      new BaseApplicable1<Integer, String>(BuiltIn.STRING_SIZE) {
        @Override
        public Integer apply(String s) {
          return s.length();
        }
      };

  /** @see BuiltIn#STRING_SUB */
  private static final Applicable2 STRING_SUB = new StringSub(Pos.ZERO);

  /** Implements {@link #STRING_SUB}. */
  private static class StringSub
      extends BasePositionedApplicable2<Character, String, Integer> {
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

  /** @see BuiltIn#STRING_STR */
  private static final Applicable STRING_STR =
      new BaseApplicable1<String, Character>(BuiltIn.STRING_STR) {
        @Override
        public String apply(Character character) {
          return character + "";
        }
      };

  /** @see BuiltIn#STRING_SUBSTRING */
  private static final Applicable3 STRING_SUBSTRING =
      new StringSubstring(Pos.ZERO);

  /** Implements {@link #STRING_SUBSTRING}. */
  private static class StringSubstring
      extends BasePositionedApplicable3<String, String, Integer, Integer> {
    StringSubstring(Pos pos) {
      super(BuiltIn.STRING_SUBSTRING, pos);
    }

    @Override
    public StringSubstring withPos(Pos pos) {
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

  /** @see BuiltIn#STRING_TOKENS */
  private static final Applicable2 STRING_TOKENS =
      new StringTokenize(BuiltIn.STRING_TOKENS);

  /** Implements {@link #STRING_FIELDS} and {@link #STRING_TOKENS}. */
  private static class StringTokenize
      extends BaseApplicable2<
          List<String>, Applicable1<Boolean, Character>, String> {
    StringTokenize(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public List<String> apply(Applicable1<Boolean, Character> f, String s) {
      List<String> result = new ArrayList<>();
      int h = 0;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (f.apply(c)) {
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
  }

  /** @see BuiltIn#STRING_TRANSLATE */
  private static final Applicable2 STRING_TRANSLATE =
      new BaseApplicable2<String, Applicable1<String, Character>, String>(
          BuiltIn.STRING_TRANSLATE) {
        @Override
        public String apply(Applicable1<String, Character> f, String s) {
          final StringBuilder buf = new StringBuilder();
          for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final String c2 = f.apply(c);
            buf.append(c2);
          }
          return buf.toString();
        }
      };

  /** @see BuiltIn#SYS_CLEAR_ENV */
  private static final Applicable SYS_CLEAR_ENV =
      new ApplicableImpl(BuiltIn.SYS_CLEAR_ENV) {
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

  /** @see BuiltIn#SYS_SHOW_ALL */
  private static final Applicable SYS_SHOW_ALL =
      new ApplicableImpl(BuiltIn.SYS_SHOW_ALL) {
        @Override
        public List apply(EvalEnv env, Object arg) {
          final Session session = (Session) env.getOpt(EvalEnv.SESSION);
          final ImmutableList.Builder<List<List>> list =
              ImmutableList.builder();
          for (Prop prop : Prop.BY_CAMEL_NAME) {
            final @Nullable Object value = prop.get(session.map);
            List option =
                value == null ? OPTION_NONE : optionSome(value.toString());
            list.add((List) ImmutableList.of(prop.camelName, option));
          }
          return list.build();
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

  /** @see BuiltIn#VECTOR_ALL */
  private static final Applicable2 VECTOR_ALL = all(BuiltIn.VECTOR_ALL);

  /** @see BuiltIn#VECTOR_APP */
  private static final Applicable2 VECTOR_APP =
      new BaseApplicable2<Unit, Applicable1<Unit, Object>, List>(
          BuiltIn.VECTOR_APP) {
        @Override
        public Unit apply(Applicable1 f, List vec) {
          vec.forEach(f::apply);
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#VECTOR_APPI */
  private static final Applicable2 VECTOR_APPI =
      new BaseApplicable2<Unit, Applicable1<Unit, List>, List>(
          BuiltIn.VECTOR_APPI) {
        @Override
        public Unit apply(Applicable1<Unit, List> f, List vec) {
          forEachIndexed(vec, (e, i) -> f.apply(FlatLists.of(i, e)));
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#VECTOR_COLLATE */
  private static final Applicable2 VECTOR_COLLATE =
      collate(BuiltIn.VECTOR_COLLATE);

  /** @see BuiltIn#VECTOR_CONCAT */
  private static final Applicable VECTOR_CONCAT =
      new BaseApplicable1<List, List<List>>(BuiltIn.VECTOR_CONCAT) {
        @Override
        public List apply(List<List> lists) {
          final ImmutableList.Builder b = ImmutableList.builder();
          for (List<Object> list : lists) {
            b.addAll(list);
          }
          return b.build();
        }
      };

  /** @see BuiltIn#VECTOR_EXISTS */
  private static final Applicable2 VECTOR_EXISTS =
      exists(BuiltIn.VECTOR_EXISTS);

  /** @see BuiltIn#VECTOR_FIND */
  private static final Applicable2 VECTOR_FIND = find(BuiltIn.VECTOR_FIND);

  /** @see BuiltIn#VECTOR_FINDI */
  private static final Applicable2 VECTOR_FINDI =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.VECTOR_FINDI) {
        @Override
        public List apply(Applicable1 f, List vec) {
          for (int i = 0, n = vec.size(); i < n; i++) {
            final List<Object> tuple = FlatLists.of(i, vec.get(i));
            if ((Boolean) f.apply(tuple)) {
              return optionSome(tuple);
            }
          }
          return OPTION_NONE;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDL */
  private static final Applicable3 VECTOR_FOLDL =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDL) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (Object o : vec) {
            acc = f.apply(FlatLists.of(o, acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDLI */
  private static final Applicable3 VECTOR_FOLDLI =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDLI) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (int i = 0, n = vec.size(); i < n; i++) {
            acc = f.apply(FlatLists.of(i, vec.get(i), acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDR */
  private static final Applicable3 VECTOR_FOLDR =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDR) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (int i = vec.size() - 1; i >= 0; i--) {
            acc = f.apply(FlatLists.of(vec.get(i), acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDRI */
  private static final Applicable3 VECTOR_FOLDRI =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDRI) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (int i = vec.size() - 1; i >= 0; i--) {
            acc = f.apply(FlatLists.of(i, vec.get(i), acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FROM_LIST */
  private static final Applicable1 VECTOR_FROM_LIST =
      identity(BuiltIn.VECTOR_FROM_LIST);

  /** @see BuiltIn#VECTOR_MAX_LEN */
  private static final int VECTOR_MAX_LEN = (1 << 24) - 1;

  /** @see BuiltIn#VECTOR_LENGTH */
  private static final Applicable1 VECTOR_LENGTH =
      length(BuiltIn.VECTOR_LENGTH);

  /** @see BuiltIn#VECTOR_MAP */
  private static final Applicable2 VECTOR_MAP =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.VECTOR_MAP) {
        @Override
        public List apply(Applicable1 f, List vec) {
          ImmutableList.Builder b = ImmutableList.builder();
          vec.forEach(e -> b.add(f.apply(e)));
          return b.build();
        }
      };

  /** @see BuiltIn#VECTOR_MAPI */
  private static final Applicable2 VECTOR_MAPI = listMapi(BuiltIn.VECTOR_MAPI);

  /** @see BuiltIn#VECTOR_SUB */
  private static final Applicable VECTOR_SUB =
      new ListNth(BuiltIn.VECTOR_SUB, Pos.ZERO);

  /** @see BuiltIn#VECTOR_TABULATE */
  private static final Applicable VECTOR_TABULATE =
      new ListTabulate(BuiltIn.VECTOR_TABULATE, Pos.ZERO);

  /** @see BuiltIn#VECTOR_UPDATE */
  private static final Applicable VECTOR_UPDATE = new VectorUpdate(Pos.ZERO);

  /** Implements {@link #VECTOR_UPDATE}. */
  private static class VectorUpdate
      extends BasePositionedApplicable3<List, List, Integer, Object> {
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

  /** Implements {@link #OP_DIVIDE} for type {@code int}. */
  private static final Applicable2 Z_DIVIDE_INT =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.OP_DIVIDE) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 / a1;
        }
      };

  /** Implements {@link #OP_DIVIDE} for type {@code real}. */
  private static final Applicable2 Z_DIVIDE_REAL =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.OP_DIVIDE) {
        @Override
        public Float apply(Float a0, Float a1) {
          final float v = a0 / a1;
          if (Float.isNaN(v)) {
            return Float.NaN; // normalize NaN
          }
          return v;
        }
      };

  /** @see BuiltIn#Z_EXTENT */
  private static final Applicable Z_EXTENT =
      new BaseApplicable1<List, RangeExtent>(BuiltIn.Z_EXTENT) {
        @Override
        public List apply(RangeExtent rangeExtent) {
          if (rangeExtent.iterable == null) {
            throw new AssertionError("infinite: " + rangeExtent);
          }
          return ImmutableList.copyOf(rangeExtent.iterable);
        }
      };

  /** @see BuiltIn#Z_LIST */
  private static final Applicable1 Z_LIST = identity(BuiltIn.Z_LIST);

  /** Implements {@link #OP_MINUS} for type {@code int}. */
  private static final Applicable2 Z_MINUS_INT =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.OP_MINUS) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 - a1;
        }
      };

  /** Implements {@link #OP_MINUS} for type {@code real}. */
  private static final Applicable2 Z_MINUS_REAL =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.OP_MINUS) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 - a1;
        }
      };

  /** Implements {@link #OP_NEGATE} for type {@code int}. */
  private static final Applicable Z_NEGATE_INT =
      new BaseApplicable1<Integer, Integer>(BuiltIn.OP_NEGATE) {
        @Override
        public Integer apply(Integer i) {
          return -i;
        }
      };

  /** Implements {@link #OP_NEGATE} for type {@code real}. */
  private static final Applicable Z_NEGATE_REAL =
      new BaseApplicable1<Float, Float>(BuiltIn.OP_NEGATE) {
        @Override
        public Float apply(Float f) {
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
  private static final Applicable2 Z_PLUS_INT =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.OP_PLUS) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 + a1;
        }
      };

  /** Implements {@link #OP_PLUS} for type {@code real}. */
  private static final Applicable2 Z_PLUS_REAL =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.OP_PLUS) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 + a1;
        }
      };

  /** Implements {@link #RELATIONAL_SUM} for type {@code int list}. */
  private static final Applicable Z_SUM_INT =
      new BaseApplicable1<Integer, List<? extends Number>>(BuiltIn.Z_SUM_INT) {
        @Override
        protected String name() {
          return "Relational.sum$int";
        }

        @Override
        public Integer apply(List<? extends Number> numbers) {
          int sum = 0;
          for (Number o : numbers) {
            sum += o.intValue();
          }
          return sum;
        }
      };

  /** Implements {@link #RELATIONAL_SUM} for type {@code real list}. */
  private static final Applicable Z_SUM_REAL =
      new BaseApplicable1<Float, List<? extends Number>>(BuiltIn.Z_SUM_REAL) {
        @Override
        protected String name() {
          return "Relational.sum$real";
        }

        @Override
        public Float apply(List<? extends Number> numbers) {
          float sum = 0;
          for (Number o : numbers) {
            sum += o.floatValue();
          }
          return sum;
        }
      };

  /** Implements {@link #OP_TIMES} for type {@code int}. */
  private static final Applicable2 Z_TIMES_INT =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.OP_TIMES) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 * a1;
        }
      };

  /** Implements {@link #OP_TIMES} for type {@code real}. */
  private static final Applicable2 Z_TIMES_REAL =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.OP_TIMES) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 * a1;
        }
      };

  // lint:endSorted

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

  /** Returns an Applicable that returns its argument. */
  private static Applicable1 identity(BuiltIn builtIn) {
    return new BaseApplicable1<Object, Object>(builtIn) {
      @Override
      public Object apply(Object arg) {
        return arg;
      }
    };
  }

  /** Returns a Code that evaluates "andalso". */
  public static Code andAlso(Code code0, Code code1) {
    return new AndAlsoCode(code0, code1);
  }

  /** Returns a Code that evaluates "orelse". */
  public static Code orElse(Code code0, Code code1) {
    return new OrElseCode(code0, code1);
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

  /**
   * Returns an applicable that constructs an instance of a datatype. The
   * instance is a list with two elements [constructorName, value].
   */
  public static Applicable1 tyCon(Type dataType, String name) {
    requireNonNull(dataType);
    requireNonNull(name);
    return new BaseApplicable1(BuiltIn.Z_TY_CON) {
      @Override
      protected String name() {
        return "tyCon";
      }

      @Override
      public Object apply(Object arg) {
        return ImmutableList.of(name, arg);
      }
    };
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
    return new Applicable() {
      @Override
      public Describer describe(Describer describer) {
        return describer.start("aggregate", d -> {});
      }

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
      new Builder()
          .put(BuiltIn.TRUE, true)
          .put(BuiltIn.FALSE, false)
          .put(BuiltIn.NOT, NOT)
          .put(BuiltIn.ABS, ABS)
          .put(BuiltIn.GENERAL_IGNORE, GENERAL_IGNORE)
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
          .put2(BuiltIn.STRING_IS_PREFIX, STRING_IS_PREFIX)
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
          .put(BuiltIn.Z_NTH, Unit.INSTANCE)
          .put(BuiltIn.Z_ORDINAL, 0)
          .put(BuiltIn.Z_ORELSE, Unit.INSTANCE)
          .put(BuiltIn.Z_PLUS_INT, Z_PLUS_INT)
          .put(BuiltIn.Z_PLUS_REAL, Z_PLUS_REAL)
          .put(BuiltIn.Z_SUM_INT, Z_SUM_INT)
          .put(BuiltIn.Z_SUM_REAL, Z_SUM_REAL)
          .put(BuiltIn.Z_TIMES_INT, Z_TIMES_INT)
          .put(BuiltIn.Z_TIMES_REAL, Z_TIMES_REAL)
          .put(BuiltIn.Z_TY_CON, Unit.INSTANCE)
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

  /** Applies an {@link Applicable1} to one {@link Code} argument. */
  private static class ApplyCode1 implements Code {
    private final Applicable1 fnValue;
    private final Code argCode0;

    ApplyCode1(Applicable1 fnValue, Code argCode0) {
      this.fnValue = fnValue;
      this.argCode0 = argCode0;
    }

    @Override
    public Object eval(EvalEnv env) {
      final Object arg0 = argCode0.eval(env);
      return fnValue.apply(arg0);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "apply1", d -> d.arg("fnValue", fnValue).arg("", argCode0));
    }
  }

  /** Applies an {@link BaseApplicable2} to two {@link Code} arguments. */
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

  /** Applies an {@link Applicable2} to an argument that yields a 2-tuple. */
  private static class ApplyCode2Tuple implements Code {
    private final Applicable2 fnValue;
    private final Code argCode;

    ApplyCode2Tuple(Applicable2 fnValue, Code argCode) {
      this.fnValue = fnValue;
      this.argCode = argCode;
    }

    @Override
    public Object eval(EvalEnv env) {
      final List arg = (List) argCode.eval(env);
      return fnValue.apply(arg.get(0), arg.get(1));
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

  /** Applies an {@link Applicable3} to an argument that yields a 3-tuple. */
  private static class ApplyCode3Tuple implements Code {
    private final Applicable3 fnValue;
    private final Code argCode;

    ApplyCode3Tuple(Applicable3 fnValue, Code argCode) {
      this.fnValue = fnValue;
      this.argCode = argCode;
    }

    @Override
    public Object eval(EvalEnv env) {
      final List arg = (List) argCode.eval(env);
      return fnValue.apply(arg.get(0), arg.get(1), arg.get(2));
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
    public Object eval(EvalEnv env) {
      final Object arg0 = argCode0.eval(env);
      final Object arg1 = argCode1.eval(env);
      final Object arg2 = argCode2.eval(env);
      final Object arg3 = argCode3.eval(env);
      return fnValue.apply(arg0, arg1, arg2, arg3);
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
    public Describer describe(Describer describer) {
      return describer.start(
          "apply", d -> d.arg("fnCode", fnCode).arg("argCode", argCode));
    }

    @Override
    public Object eval(EvalEnv env) {
      final Applicable1 fnValue = (Applicable1) fnCode.eval(env);
      final Object arg = argCode.eval(env);
      return fnValue.apply(arg);
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
  abstract static class BaseApplicable implements Applicable {
    protected final BuiltIn builtIn;

    protected BaseApplicable(BuiltIn builtIn) {
      this.builtIn = builtIn;
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
  abstract static class BasePositionedApplicable extends BaseApplicable
      implements Positioned {
    protected final Pos pos;

    protected BasePositionedApplicable(BuiltIn builtIn, Pos pos) {
      super(builtIn);
      this.pos = pos;
    }
  }

  /**
   * Base class with which to implement {@link Applicable1}.
   *
   * @param <R> return type
   * @param <A0> type of argument
   */
  @SuppressWarnings({"unchecked"})
  abstract static class BaseApplicable1<R, A0> extends BaseApplicable
      implements Applicable1<R, A0> {
    protected BaseApplicable1(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override // Applicable
    public Object apply(EvalEnv env, Object argValue) {
      return apply((A0) argValue);
    }
  }

  /**
   * Base class with which to implement {@link Applicable1} and {@link
   * Positioned}.
   *
   * @param <R> return type
   * @param <A0> type of argument
   */
  abstract static class BasePositionedApplicable1<R, A0>
      extends BaseApplicable1<R, A0> implements Applicable1<R, A0>, Positioned {
    protected final Pos pos;

    protected BasePositionedApplicable1(BuiltIn builtIn, Pos pos) {
      super(builtIn);
      this.pos = pos;
    }
  }

  /**
   * Base class with which to implement {@link Applicable2}.
   *
   * @param <R> return type
   * @param <A0> type of argument 0
   * @param <A1> type of argument 1
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  abstract static class BaseApplicable2<R, A0, A1> extends BaseApplicable
      implements Applicable1<R, List>, Applicable2<R, A0, A1> {
    protected BaseApplicable2(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override // Applicable1
    public R apply(List list) {
      return apply((A0) list.get(0), (A1) list.get(1));
    }

    @Override // Applicable
    public Object apply(EvalEnv env, Object argValue) {
      final List list = (List) argValue;
      return apply((A0) list.get(0), (A1) list.get(1));
    }

    @Override
    public Applicable1<Applicable1<R, A1>, A0> curry() {
      return new CurriedApplicable1<Applicable1<R, A1>, A0>(builtIn, this) {
        @Override
        public Applicable1<R, A1> apply(A0 a0) {
          return new Applicable1<R, A1>() {
            @Override
            public R apply(A1 a1) {
              return BaseApplicable2.this.apply(a0, a1);
            }
          };
        }
      };
    }
  }

  /**
   * Base class with which to implement {@link Applicable2} and {@link
   * Positioned}.
   *
   * @param <R> return type
   * @param <A0> type of argument 0
   * @param <A1> type of argument 1
   */
  abstract static class BasePositionedApplicable2<R, A0, A1>
      extends BaseApplicable2<R, A0, A1> implements Positioned {
    protected final Pos pos;

    protected BasePositionedApplicable2(BuiltIn builtIn, Pos pos) {
      super(builtIn);
      this.pos = pos;
    }
  }

  /**
   * Base class with which to implement {@link Applicable3}.
   *
   * @param <R> return type
   * @param <A0> type of argument 0
   * @param <A1> type of argument 1
   * @param <A2> type of argument 2
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  abstract static class BaseApplicable3<R, A0, A1, A2> extends BaseApplicable
      implements Applicable1<R, List>, Applicable3<R, A0, A1, A2> {
    protected BaseApplicable3(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override // Applicable1
    public R apply(List list) {
      return apply((A0) list.get(0), (A1) list.get(1), (A2) list.get(2));
    }

    @Override // Applicable
    public Object apply(EvalEnv env, Object argValue) {
      final List list = (List) argValue;
      return apply((A0) list.get(0), (A1) list.get(1), (A2) list.get(2));
    }

    @Override
    public Applicable1<Applicable1<Applicable1<R, A2>, A1>, A0> curry() {
      return new CurriedApplicable1<Applicable1<Applicable1<R, A2>, A1>, A0>(
          builtIn, this) {
        @Override
        public Applicable1<Applicable1<R, A2>, A1> apply(A0 a0) {
          return new Applicable1<Applicable1<R, A2>, A1>() {
            @Override
            public Applicable1<R, A2> apply(A1 a1) {
              return new Applicable1<R, A2>() {
                @Override
                public R apply(A2 a2) {
                  return BaseApplicable3.this.apply(a0, a1, a2);
                }
              };
            }
          };
        }
      };
    }
  }

  /**
   * Base class with which to implement {@link Applicable3} and {@link
   * Positioned}.
   *
   * @param <R> return type
   * @param <A0> type of argument 0
   * @param <A1> type of argument 1
   * @param <A2> type of argument 2
   */
  abstract static class BasePositionedApplicable3<R, A0, A1, A2>
      extends BaseApplicable3<R, A0, A1, A2> implements Positioned {
    protected final Pos pos;

    protected BasePositionedApplicable3(BuiltIn builtIn, Pos pos) {
      super(builtIn);
      this.pos = pos;
    }
  }

  /** Implementation of {@link Applicable} that has a single char argument. */
  private static class CharPredicate
      extends BaseApplicable1<Boolean, Character> {
    private final Predicate<Character> predicate;

    CharPredicate(BuiltIn builtIn, Predicate<Character> predicate) {
      super(builtIn);
      this.predicate = predicate;
    }

    @Override
    public Boolean apply(Character c) {
      return predicate.test(c);
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
  static class Comparer extends BaseApplicable2<List, Object, Object>
      implements Applicable1<List, List>, Typed {
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
    @Override // Applicable2
    public List apply(Object o1, Object o2) {
      return order(comparator.compare(o1, o2));
    }
  }

  private static class Builder {
    final PairList<BuiltIn, Object> builtIns = PairList.of();

    ImmutableMap<BuiltIn, Object> build() {
      return builtIns.toImmutableMap();
    }

    Builder put(BuiltIn builtin, Object o) {
      builtIns.add(builtin, o);
      return this;
    }

    Builder put2(BuiltIn builtin, Object o) {
      builtIns.add(builtin, o);
      return this;
    }
  }

  /**
   * Curried function.
   *
   * <p>It takes one argument, because it is the first stage of several calls.
   *
   * <p>It implements {@link Describable}, because it may need to appear in a
   * plan. (If a call yields another {@link Applicable1}, that function is
   * lighter weight, because it does not need to be described.)
   *
   * @param <R> return type
   * @param <A0> type of first argument
   */
  abstract static class CurriedApplicable1<R, A0>
      extends BaseApplicable1<R, A0> {
    private final Applicable1 parent;

    CurriedApplicable1(BuiltIn builtIn, Applicable1 parent) {
      super(builtIn);
      this.parent = parent;
    }

    @Override
    public Describer describe(Describer describer) {
      return parent.describe(describer);
    }
  }
}

// End Codes.java
