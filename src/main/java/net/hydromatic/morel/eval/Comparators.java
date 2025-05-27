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
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.DummyType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.PairList;

@SuppressWarnings("rawtypes")
public class Comparators {
  private Comparators() {}

  /** Returns a comparator for a given type. */
  public static Comparator comparatorFor(TypeSystem typeSystem, Type type) {
    return new ComparatorBuilder(typeSystem).comparatorFor(type);
  }

  /** Compares two objects using their natural order. */
  @SuppressWarnings("unchecked")
  public static int compare(Object o1, Object o2) {
    return ((Comparable) o1).compareTo(o2);
  }

  /** Sentinel value. */
  private enum Dummy implements Comparator {
    INSTANCE;

    @Override
    public int compare(Object o1, Object o2) {
      return 0;
    }
  }

  /** Contains shared state while building comparators for various types. */
  static class ComparatorBuilder {
    private final TypeSystem typeSystem;
    private final Map<Type.Key, Comparator> cache = new HashMap<>();

    ComparatorBuilder(TypeSystem typeSystem) {
      this.typeSystem = requireNonNull(typeSystem);
    }

    Comparator comparatorFor(Type t2) {
      if (t2 == DummyType.INSTANCE) {
        // This is a no-argument type constructor.
        return (list1, list2) -> 0;
      }

      // Check if we have already computed the comparator for this type.
      Type.Key key = t2.key();
      final Comparator comparator = cache.get(key);
      if (comparator == Dummy.INSTANCE) {
        // The comparator is a sentinel, indicating that we are
        // in the process of computing it. We need to defer the
        // lookup of the comparator until it is first used.
        return new DeferredComparator(key);
      }
      if (comparator != null) {
        return comparator;
      }
      return comparatorFor1(t2);
    }

    private Comparator comparatorFor1(Type type) {
      final Type.Key key = type.key();

      // Put a sentinel in the cache so that we can detect cycles.
      cache.put(key, Dummy.INSTANCE);
      final Comparator comparator2 = comparatorFor2(type);
      cache.put(key, comparator2);
      return comparator2;
    }

    @SuppressWarnings("unchecked")
    private Comparator comparatorFor2(Type type) {
      switch (type.op()) {
        case ID:
        case TY_VAR:
          // Primitive types are compared using their natural order.
          return Comparators::compare;

        case TUPLE_TYPE:
        case RECORD_TYPE:
          final List<Comparator> fieldComparators =
              transformEager(
                  ((RecordLikeType) type).argTypes(), this::comparatorFor);
          return (Comparator<List>)
              (List list1, List list2) -> {
                for (int i = 0; i < fieldComparators.size(); i++) {
                  Comparator comparator = fieldComparators.get(i);
                  int c = comparator.compare(list1.get(i), list2.get(i));
                  if (c != 0) {
                    return c;
                  }
                }
                return 0;
              };

        case LIST:
          return listComparator(type.arg(0));

        case DATA_TYPE:
          DataType dataType = (DataType) type;
          switch (dataType.name) {
            case "bag":
              return listComparator(dataType.arg(0));

            case "descending":
              Comparator<Object> objectComparator =
                  comparatorFor(dataType.arg(0));
              // Pass arguments in reverse order, to reverse comparison order.
              return (Comparator<List>)
                  (list1, list2) ->
                      objectComparator.compare(list2.get(1), list1.get(1));
          }
          final PairList<String, Ord<Comparator>> b = PairList.of();
          dataType
              .typeConstructors(typeSystem)
              .forEach(
                  (name, t) -> b.add(name, Ord.of(b.size(), comparatorFor(t))));
          final ImmutableMap<String, Ord<Comparator>> constructorComparators =
              b.toImmutableMap();
          return (Comparator<List>)
              (list1, list2) -> {
                final String s1 = (String) list1.get(0);
                final String s2 = (String) list2.get(0);
                if (s1.equals(s2)) {
                  // Same constructor.
                  if (list1.size() == 1) {
                    // Constructor has no arguments. We're done.
                    return 0;
                  } else {
                    // Same constructor. Compare the values.
                    final Ord<Comparator> comparator =
                        requireNonNull(constructorComparators.get(s1));
                    return comparator.e.compare(list1.get(1), list2.get(1));
                  }
                } else {
                  // Different constructors. Compare based on their ordinals.
                  final Ord<Comparator> comparator1 =
                      requireNonNull(constructorComparators.get(s1));
                  final Ord<Comparator> comparator2 =
                      requireNonNull(constructorComparators.get(s2));
                  return Integer.compare(comparator1.i, comparator2.i);
                }
              };

        default:
          throw new AssertionError("unknown type: " + type);
      }
    }

    @SuppressWarnings("unchecked")
    private Comparator<List> listComparator(Type elementType) {
      final Comparator<Object> elementComparator =
          (Comparator<Object>) comparatorFor(elementType);
      return (list1, list2) -> {
        final int n1 = list1.size();
        final int n2 = list2.size();
        final int n = Math.min(n1, n2);
        for (int i = 0; i < n; i++) {
          final Object element0 = list1.get(i);
          final Object element1 = list2.get(i);
          final int c = elementComparator.compare(element0, element1);
          if (c != 0) {
            return c;
          }
        }
        return Integer.compare(n1, n2);
      };
    }

    /**
     * Comparator that defers the lookup of the comparator until it is first
     * used. Used for cyclic types, e.g.
     *
     * <pre>{@code
     * datatype 'a list = cons of 'a * 'a list | nil;
     * }</pre>
     */
    private class DeferredComparator implements Comparator {
      final Supplier<Comparator> supplier;

      DeferredComparator(Type.Key key) {
        supplier = Suppliers.memoize(() -> requireNonNull(cache.get(key)));
      }

      @SuppressWarnings("unchecked")
      @Override
      public int compare(Object o1, Object o2) {
        return requireNonNull(supplier.get()).compare(o1, o2);
      }
    }
  }
}

// End Comparators.java
