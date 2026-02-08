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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static org.apache.calcite.util.Util.isDistinct;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;

/**
 * An expression that returns all values that satisfy a predicate.
 *
 * <p>Created by {@link Expander}.
 */
abstract class Generator {
  final Core.Exp exp;
  final Core.Pat pat;
  final Cardinality cardinality;
  final List<Core.NamedPat> freePats;
  /** Whether the generator produces unique values (no duplicates). */
  final boolean unique;

  /**
   * Whether every value produced by this generator satisfies all constraints in
   * {@link #provenance}.
   *
   * <p>A sealed generator's provenance can be used to remove redundant WHERE
   * conjuncts in {@code expandFrom2}. An unsealed generator's provenance is
   * advisory only — the WHERE conjuncts must be kept for correctness.
   */
  final boolean sealed;

  /**
   * The constraints from the original WHERE clause that this generator
   * subsumes. Every value produced by the generator satisfies all of these
   * constraints.
   *
   * <p>This should be the minimal set needed to derive the generator. Smaller
   * provenance means broader reuse.
   *
   * <p>Append-only: entries are added during derivation (e.g., when {@code
   * maybeFunction} discovers that a generator subsumes the original
   * function-call constraint) but never removed. This preserves the
   * monotonicity invariant of the {@link Generators.Cache}.
   */
  final Set<Core.Exp> provenance;

  Generator(
      Core.Exp exp,
      Iterable<? extends Core.NamedPat> freePats,
      Core.Pat pat,
      Cardinality cardinality,
      boolean unique,
      boolean sealed,
      Set<Core.Exp> provenance) {
    this.exp = requireNonNull(exp);
    this.freePats = ImmutableList.copyOf(freePats);
    checkArgument(freePats instanceof Set || isDistinct(this.freePats));
    this.pat = requireNonNull(pat);
    this.cardinality = requireNonNull(cardinality);
    this.unique = unique;
    this.sealed = sealed;
    this.provenance = new LinkedHashSet<>(provenance);
  }

  Generator(
      Core.Exp exp,
      Iterable<? extends Core.NamedPat> freePats,
      Core.Pat pat,
      Cardinality cardinality,
      boolean unique,
      boolean sealed) {
    this(exp, freePats, pat, cardinality, unique, sealed, ImmutableSet.of());
  }

  /**
   * Returns every value returned from the generator satisfies a given boolean
   * expression.
   *
   * <p>Each generator should return literal "true" for the predicate that it
   * inverted. For example, the generator from {@link
   * Generators#generateRange}(0, 5) should return true for {@code p > 0 andalso
   * p < 5}.
   */
  abstract Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp);

  /** Cardinality of a generator per binding of its free variables. */
  public enum Cardinality {
    /** Produces exactly one value (e.g., x = 5) */
    SINGLE,
    /** Produces a finite number of values (e.g., x elem [1,2,3]) */
    FINITE,
    /** Produces an infinite number of values (e.g., unbounded extent) */
    INFINITE;

    /** Returns this or {@code o}, whichever has the greater cardiality. */
    Cardinality max(Cardinality o) {
      return this.ordinal() >= o.ordinal() ? this : o;
    }
  }
}

// End Generator.java
