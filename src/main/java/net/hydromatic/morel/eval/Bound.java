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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * One endpoint of a range: either unbounded (representing −&infin; or +&infin;)
 * or a specific value with inclusivity.
 */
class Bound {
  /** Sentinel representing an unbounded endpoint (−&infin; or +&infin;). */
  public static final Bound UNBOUNDED = new Bound(null, false);

  /** The endpoint value, or {@code null} if unbounded. */
  public final @Nullable Object value;

  /**
   * Whether the endpoint is inclusive ({@code >=} or {@code <=}). Only
   * meaningful when {@link #value} is non-null.
   */
  public final boolean inclusive;

  private Bound(@Nullable Object value, boolean inclusive) {
    this.value = value;
    this.inclusive = inclusive;
  }

  /** Returns an inclusive bound at {@code v}. */
  public static Bound inclusive(Object v) {
    return new Bound(requireNonNull(v), true);
  }

  /** Returns an exclusive bound at {@code v}. */
  public static Bound exclusive(Object v) {
    return new Bound(requireNonNull(v), false);
  }

  /**
   * Converts a lower-upper {@link Bound} pair to a runtime range list value.
   */
  public static List<Object> toRange(Bound lo, Bound hi) {
    if (lo.value == null) {
      if (hi.value == null) {
        return ImmutableList.of(BuiltIn.Constructor.RANGE_ALL.constructor);
      }
      return ImmutableList.of(
          hi.inclusive
              ? BuiltIn.Constructor.RANGE_AT_MOST.constructor
              : BuiltIn.Constructor.RANGE_LESS_THAN.constructor,
          hi.value);
    }
    if (hi.value == null) {
      return ImmutableList.of(
          lo.inclusive
              ? BuiltIn.Constructor.RANGE_AT_LEAST.constructor
              : BuiltIn.Constructor.RANGE_GREATER_THAN.constructor,
          lo.value);
    }
    // Both bounds are finite.
    if (lo.inclusive && hi.inclusive && lo.value.equals(hi.value)) {
      return ImmutableList.of(
          BuiltIn.Constructor.RANGE_POINT.constructor, lo.value);
    }
    return ImmutableList.of(
        lo.inclusive
            ? (hi.inclusive
                ? BuiltIn.Constructor.RANGE_CLOSED.constructor
                : BuiltIn.Constructor.RANGE_CLOSED_OPEN.constructor)
            : (hi.inclusive
                ? BuiltIn.Constructor.RANGE_OPEN_CLOSED.constructor
                : BuiltIn.Constructor.RANGE_OPEN.constructor),
        ImmutableList.of(lo.value, hi.value));
  }

  static void enumerate(
      Discrete<Object> discrete,
      Bound lo,
      Bound hi,
      BigInteger maxLength,
      Pos pos,
      Consumer<Object> out) {
    // An endpoint left unbounded is the end of the domain. The domain is
    // finite, so "AT_LEAST #\"\253\"" yields the last three characters, even
    // though it names no upper bound.
    final Object end;
    final boolean endInclusive;
    if (hi.value == null) {
      end = discrete.maxValue();
      endInclusive = true;
    } else {
      end = hi.value;
      endInclusive = hi.inclusive;
    }
    Object start;
    if (lo.value == null) {
      start = discrete.minValue();
    } else {
      if (lo.inclusive) {
        start = lo.value;
      } else {
        start = discrete.next(lo.value);
        if (start == null) {
          throw new Codes.MorelRuntimeException(
              Codes.BuiltInExn.SIZE, pos); // empty range
        }
      }
    }
    // Count the values before producing any. The count is the distance
    // between the endpoints' positions, so a range of 2^65 values is refused
    // as quickly as a range of three is built.
    final BigInteger count =
        discrete.ordinal(end).subtract(discrete.ordinal(start));
    if (count.compareTo(maxLength) >= 0) {
      throw new Codes.MorelRuntimeException(Codes.BuiltInExn.SIZE, pos);
    }

    Comparator<Object> cmp = discrete.comparator();
    Object v = start;
    while (true) {
      int c = cmp.compare(v, end);
      if (c > 0 || c == 0 && !endInclusive) {
        break;
      }
      out.accept(v);
      Object next = discrete.next(v);
      if (next == null) {
        break;
      }
      v = next;
    }
  }

  /** Extracts the lower {@link Bound} from a runtime range value. */
  @SuppressWarnings("rawtypes")
  static Bound lowerBound(List range) {
    BuiltIn.Constructor ctor =
        requireNonNull(BuiltIn.Constructor.forName((String) range.get(0)));
    switch (ctor) {
      case RANGE_ALL:
      case RANGE_AT_MOST:
      case RANGE_LESS_THAN:
        return UNBOUNDED;
      case RANGE_POINT:
      case RANGE_AT_LEAST:
        return inclusive(unaryArg(range));
      case RANGE_CLOSED:
      case RANGE_CLOSED_OPEN:
        return inclusive(binaryArg0(range));
      case RANGE_GREATER_THAN:
        return exclusive(unaryArg(range));
      case RANGE_OPEN:
      case RANGE_OPEN_CLOSED:
        return exclusive(binaryArg0(range));
      default:
        throw new AssertionError("unknown range constructor: " + ctor);
    }
  }

  /** Extracts the upper {@link Bound} from a runtime range value. */
  @SuppressWarnings("rawtypes")
  static Bound upperBound(List range) {
    BuiltIn.Constructor ctor =
        requireNonNull(BuiltIn.Constructor.forName((String) range.get(0)));
    switch (ctor) {
      case RANGE_ALL:
      case RANGE_AT_LEAST:
      case RANGE_GREATER_THAN:
        return UNBOUNDED;
      case RANGE_POINT:
      case RANGE_AT_MOST:
        return inclusive(unaryArg(range));
      case RANGE_CLOSED:
      case RANGE_OPEN_CLOSED:
        return inclusive(binaryArg1(range));
      case RANGE_LESS_THAN:
        return exclusive(unaryArg(range));
      case RANGE_OPEN:
      case RANGE_CLOSED_OPEN:
        return exclusive(binaryArg1(range));
      default:
        throw new AssertionError("unknown range constructor: " + ctor);
    }
  }

  /**
   * Returns the sole value argument of a unary range constructor (e.g. {@code
   * POINT v}, {@code AT_LEAST v}).
   *
   * <p>Unlike binary constructors, the argument may itself be a tuple (stored
   * as a {@code List}), so it must not be unwrapped.
   */
  @SuppressWarnings("rawtypes")
  private static Object unaryArg(List range) {
    return range.get(1);
  }

  /**
   * Returns the lower-bound element of a binary range constructor's {@code (lo,
   * hi)} argument (e.g. {@code CLOSED (lo, hi)}, {@code OPEN (lo, hi)}).
   */
  @SuppressWarnings("rawtypes")
  private static Object binaryArg0(List range) {
    return ((List) range.get(1)).get(0);
  }

  /**
   * Returns the upper-bound element of a binary range constructor's {@code (lo,
   * hi)} argument.
   */
  @SuppressWarnings("rawtypes")
  private static Object binaryArg1(List range) {
    return ((List) range.get(1)).get(1);
  }

  /**
   * Returns the index of the range in {@code ranges} that contains {@code x},
   * or {@code -1} if no range contains it.
   *
   * <p>Binary-searches for the last range whose lower bound is satisfied by
   * {@code x}, then checks whether {@code x} also satisfies that range's upper
   * bound.
   */
  static int rangeContaining(
      PairList<Bound, Bound> ranges, Object x, Comparator<Object> cmp) {
    int lo = 0;
    int hi = ranges.size() - 1;
    int candidate = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (ranges.left(mid).compareValue(x, cmp) >= 0) {
        candidate = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (candidate >= 0) {
      Bound hiB = ranges.right(candidate);
      if (hiB.value == null) {
        return candidate; // unbounded above: x is in this range
      }
      int c = cmp.compare(x, hiB.value);
      if (c < 0 || c == 0 && hiB.inclusive) {
        return candidate;
      }
    }
    return -1;
  }

  /**
   * Returns the complement of a normalized {@link PairList}{@code <Bound,
   * Bound>}: the set of all values <em>not</em> covered by any range in {@code
   * ranges}.
   *
   * <p>For continuous sets, each bound is flipped (inclusive &harr; exclusive).
   * For discrete sets ({@code discrete} non-null), adjacent discrete values are
   * used so that the result contains only {@code CLOSED} and unbounded
   * constructors.
   */
  static PairList<Bound, Bound> complement(
      PairList<Bound, Bound> ranges, @Nullable Discrete<Object> discrete) {
    final PairList<Bound, Bound> result = PairList.of();
    Bound lo = UNBOUNDED;
    for (int i = 0; i < ranges.size(); i++) {
      final Bound rangeLo = ranges.left(i);
      final Bound rangeHi = ranges.right(i);
      if (rangeLo.value != null) {
        final Bound hi = complementHi(rangeLo, discrete);
        if (hi != null) {
          result.add(lo, hi);
        }
      }
      if (rangeHi.value == null) {
        // Range extends to +∞; no complement after this.
        return result.immutable();
      }
      final Bound nextLo = complementLo(rangeHi, discrete);
      if (nextLo == null) {
        // Range ends at the maximum discrete value; nothing after it.
        return result.immutable();
      }
      lo = nextLo;
    }
    result.add(lo, UNBOUNDED);
    return result.immutable();
  }

  /**
   * Returns the upper bound of the complement range that ends just before lower
   * bound {@code lo}.
   *
   * <p>Returns {@code null} if the complement range is empty (i.e., {@code lo}
   * is the minimum discrete value).
   *
   * <p>{@code lo} must be bounded; that is, its value must not be null.
   */
  private static @Nullable Bound complementHi(
      Bound lo, @Nullable Discrete<Object> discrete) {
    final Object loValue = requireNonNull(lo.value, "lo.value");
    if (discrete != null) {
      if (lo.inclusive) {
        Object prev = discrete.prev(loValue);
        return prev != null ? inclusive(prev) : null;
      } else {
        return inclusive(loValue);
      }
    }
    return lo.inclusive ? exclusive(loValue) : inclusive(loValue);
  }

  /**
   * Returns the lower bound of the complement range that starts just after
   * upper bound {@code hi}.
   *
   * <p>Returns {@code null} if there is no discrete value after {@code hi}
   * (i.e., {@code hi} is the maximum discrete value).
   *
   * <p>{@code hi} must be bounded; that is, its value must not be null.
   */
  private static @Nullable Bound complementLo(
      Bound hi, @Nullable Discrete<Object> discrete) {
    final Object hiValue = requireNonNull(hi.value, "hi.value");
    if (discrete != null) {
      if (hi.inclusive) {
        Object next = discrete.next(hiValue);
        return next != null ? inclusive(next) : null;
      } else {
        return inclusive(hiValue);
      }
    }
    return hi.inclusive ? exclusive(hiValue) : inclusive(hiValue);
  }

  /**
   * Converts a list of runtime range values into a normalized {@link
   * PairList}{@code <Bound, Bound>}: sorts by lower bound, then merges
   * overlapping or touching ranges. If {@code discrete} is non-null, also
   * merges adjacent ranges whose effective endpoints are one step apart.
   */
  static PairList<Bound, Bound> fromRanges(
      List<List<?>> ranges,
      Comparator<Object> cmp,
      @Nullable Discrete<Object> discrete) {
    // Convert each runtime range to a pair of (lo, hi) bounds, then sort.
    final PairList<Bound, Bound> pairs = PairList.withCapacity(ranges.size());
    for (List<?> r : ranges) {
      pairs.add(lowerBound(r), upperBound(r));
    }

    // If there are 0 or 1 pairs, sorting and merging is unnecessary.
    if (pairs.size() < 2) {
      return pairs.immutable();
    }

    // Sort, then merge overlapping/touching ranges.
    pairs.sort((e1, e2) -> e1.getKey().compareLower(e2.getKey(), cmp));

    final PairList<Bound, Bound> result = PairList.of();
    Bound lo = pairs.left(0);
    Bound hi = pairs.right(0);
    for (int i = 1; i < pairs.size(); i++) {
      final Bound nextLo = pairs.left(i);
      final Bound nextHi = pairs.right(i);
      final boolean merge =
          discrete != null
              ? hi.canMergeDiscrete(nextLo, discrete)
              : hi.canMerge(nextLo, cmp);
      if (merge) {
        hi = hi.max(nextHi, cmp);
      } else {
        result.add(lo, hi);
        lo = nextLo;
        hi = nextHi;
      }
    }
    result.add(lo, hi);
    return result.immutable();
  }

  /**
   * Compares a value {@code x} against this lower bound.
   *
   * <p>Returns a positive number if {@code x} satisfies the bound (is at or
   * past the lower endpoint), zero if {@code x} exactly equals an inclusive
   * bound, or a negative number if {@code x} is strictly before the bound.
   * Returns -1 when {@code x} equals an exclusive lower bound (because {@code x
   * > lo} is required but not met).
   */
  int compareValue(Object x, Comparator<Object> cmp) {
    if (value == null) {
      return 1; // x is always past −infinity
    }
    int c = cmp.compare(x, value);
    return (c == 0 && !inclusive) ? -1 : c;
  }

  /**
   * Returns whether this upper bound reaches or exceeds {@code bound} (the
   * lower bound of the next range), meaning the two ranges overlap or touch.
   */
  boolean canMerge(Bound bound, Comparator<Object> cmp) {
    if (value == null || bound.value == null) {
      return true;
    }
    int c = cmp.compare(value, bound.value);
    if (c > 0) {
      return true;
    }
    if (c < 0) {
      return false;
    }
    return inclusive || bound.inclusive;
  }

  /**
   * Returns whether this upper bound and {@code bound} are adjacent in discrete
   * order (e.g., {@code CLOSED(1,3)} and {@code CLOSED(4,6)} for {@code int}).
   */
  boolean canMergeDiscrete(Bound bound, Discrete<Object> discrete) {
    if (value == null || bound.value == null) {
      return true;
    }
    // Compute effective last-included value of this range.
    Object hiEffective = inclusive ? value : discrete.prev(value);
    // Compute effective first-included value of the next range.
    Object loEffective =
        bound.inclusive ? bound.value : discrete.next(bound.value);
    if (hiEffective == null || loEffective == null) {
      return false;
    }
    Object nextAfterHi = discrete.next(hiEffective);
    return nextAfterHi != null
        && discrete.comparator().compare(nextAfterHi, loEffective) >= 0;
  }

  /** Returns the greater of this upper bound and {@code bound}. */
  public Bound max(Bound bound, Comparator<Object> cmp) {
    if (value == null || bound.value == null) {
      return UNBOUNDED;
    }
    int c = cmp.compare(value, bound.value);
    if (c > 0) {
      return this;
    }
    if (c < 0) {
      return bound;
    }
    return inclusive ? this : bound;
  }

  /**
   * Compares this lower bound with {@code bound} (earlier/smaller first).
   *
   * <p>An unbounded lower bound sorts before all finite bounds. Among bounds
   * with the same value, inclusive sorts before exclusive (starts earlier).
   */
  public int compareLower(Bound bound, Comparator<Object> cmp) {
    if (value == null && bound.value == null) {
      return 0;
    }
    if (value == null) {
      return -1;
    }
    if (bound.value == null) {
      return 1;
    }
    int c = cmp.compare(value, bound.value);
    if (c != 0) {
      return c;
    }
    // Same value: inclusive sorts before exclusive (starts "earlier")
    if (inclusive && !bound.inclusive) {
      return -1;
    }
    if (!inclusive && bound.inclusive) {
      return 1;
    }
    return 0;
  }
}

// End Bound.java
