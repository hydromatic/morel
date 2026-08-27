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

import static net.hydromatic.morel.ast.AstBuilder.ast;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Carrying a {@code check} condition from one record type to another.
 *
 * <p>A record modifier that adds, removes or renames a field gives a record of
 * a different shape, and a condition is typed against the exact record type it
 * was written for, because records are not width-subtyped. So a condition can
 * be carried over only if it is rewritten to hold of the new record, and only
 * if every field it depends on is still there.
 */
class Conditions {
  /** The name a condition's record is rebound to when it is rewritten. */
  private static final String RECORD = "$r";

  private Conditions() {}

  /**
   * Returns {@code check} rewritten to hold of a record whose fields are named
   * by {@code fields}, or null if it cannot be.
   *
   * <p>{@code fields} maps each field of the record the condition was written
   * for to its name in the new record. A field that is missing from the map is
   * one the modifier removed or assigned to, and a condition that depends on it
   * cannot be carried over: it would no longer typecheck, or it was never shown
   * to hold of the new value.
   *
   * <p>Returns null also when the condition uses the record as a whole rather
   * than by selecting fields from it, and when its match is one this cannot
   * rewrite. Both are answered conservatively: a condition that is dropped
   * claims less, which is sound.
   */
  static Ast.@Nullable Fn inherit(
      TypeSystem typeSystem, Ast.Fn check, Map<String, String> fields) {
    if (check.matchList.stream().allMatch(m -> m.pat.op == Op.ID_PAT)) {
      return rename(typeSystem, check, fields);
    }
    if (check.matchList.size() == 1) {
      return select(check.matchList.get(0), fields);
    }
    return null;
  }

  /**
   * Rewrites a condition that names the record and selects fields from it --
   * {@code r => r.a < 10} -- by renaming what it selects.
   *
   * <p>Every use of the name must be a selection. One that is not uses the
   * record as a whole, which a record of another shape is not.
   */
  private static Ast.@Nullable Fn rename(
      TypeSystem typeSystem, Ast.Fn check, Map<String, String> fields) {
    final List<Ast.Match> matches = new ArrayList<>();
    for (Ast.Match match : check.matchList) {
      final String name = ((Ast.IdPat) match.pat).name;
      if (!selectsOnly(match.exp, name, fields.keySet())) {
        return null;
      }
      matches.add(
          match.copy(
              match.pat,
              rewriteSelectors(typeSystem, match.exp, name, fields)));
    }
    return ast.fn(check.pos, matches);
  }

  /**
   * Rewrites a condition that destructures the record -- {@code {a, b} => a <
   * 10} -- into one that selects from it, so that it holds of a record with
   * fields the pattern does not mention.
   *
   * <blockquote>
   *
   * <pre>{@code
   * {a, b} => a < 10
   * ==>
   * $r => let val a = #a $r and b = #b $r in a < 10 end
   * }</pre>
   *
   * </blockquote>
   *
   * <p>Only an irrefutable pattern is rewritten. A refutable one -- {@code {a =
   * 0, b}} -- decides by not matching, and a {@code val} that does not match
   * raises {@code Bind} rather than answering false.
   */
  private static Ast.@Nullable Fn select(
      Ast.Match match, Map<String, String> fields) {
    if (match.pat.op != Op.RECORD_PAT) {
      return null;
    }
    final Ast.RecordPat recordPat = (Ast.RecordPat) match.pat;
    if (recordPat.ellipsis) {
      return null;
    }
    final Pos pos = match.pat.pos;
    final PairList<Ast.Pat, Ast.Exp> binds = PairList.of();
    for (Map.Entry<String, Ast.Pat> arg : recordPat.args.entrySet()) {
      final String field = fields.get(arg.getKey());
      if (field == null || !irrefutable(arg.getValue())) {
        return null;
      }
      binds.add(
          arg.getValue(),
          ast.apply(ast.recordSelector(pos, field), ast.id(pos, RECORD)));
    }
    if (binds.isEmpty()) {
      // The pattern binds nothing, so the condition does not depend on any
      // field and holds of any record.
      return ast.fn(
          match.pos, ast.match(pos, ast.idPat(pos, RECORD), match.exp));
    }
    final List<Ast.ValBind> valBinds =
        binds.transform((pat, exp) -> ast.valBind(pos, pat, exp));
    final Ast.Exp let =
        ast.let(
            pos,
            ImmutableList.of(ast.valDecl(pos, false, false, valBinds)),
            match.exp);
    return ast.fn(match.pos, ast.match(pos, ast.idPat(pos, RECORD), let));
  }

  /** Returns whether a pattern matches every value, and so cannot decide. */
  private static boolean irrefutable(Ast.Pat pat) {
    return pat.op == Op.ID_PAT || pat.op == Op.WILDCARD_PAT;
  }

  /**
   * Returns whether every use of {@code name} in {@code exp} selects one of
   * {@code fields} from it, and {@code name} is not bound again within.
   */
  private static boolean selectsOnly(
      Ast.Exp exp, String name, Set<String> fields) {
    final Set<AstNode> selected = new HashSet<>();
    final boolean[] ok = {true};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.Apply apply) {
            if (apply.fn.op == Op.RECORD_SELECTOR
                && apply.arg.op == Op.ID
                && ((Ast.Id) apply.arg).name.equals(name)) {
              if (!fields.contains(((Ast.RecordSelector) apply.fn).name)) {
                ok[0] = false;
              }
              selected.add(apply.arg);
            }
            super.visit(apply);
          }

          @Override
          protected void visit(Ast.Id id) {
            if (id.name.equals(name) && !selected.contains(id)) {
              ok[0] = false;
            }
            super.visit(id);
          }

          @Override
          protected void visit(Ast.IdPat idPat) {
            if (idPat.name.equals(name)) {
              ok[0] = false; // rebound, so the uses below are not the record
            }
            super.visit(idPat);
          }
        });
    return ok[0];
  }

  /** Renames the fields that {@code name} is selected on. */
  private static Ast.Exp rewriteSelectors(
      TypeSystem typeSystem,
      Ast.Exp exp,
      String name,
      Map<String, String> fields) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Ast.Apply visit(Ast.Apply apply) {
            final Ast.Apply apply2 = super.visit(apply);
            if (apply2.fn.op == Op.RECORD_SELECTOR
                && apply2.arg.op == Op.ID
                && ((Ast.Id) apply2.arg).name.equals(name)) {
              final Ast.RecordSelector selector =
                  (Ast.RecordSelector) apply2.fn;
              final String field = fields.get(selector.name);
              if (field != null && !field.equals(selector.name)) {
                return ast.apply(
                    ast.recordSelector(selector.pos, field), apply2.arg);
              }
            }
            return apply2;
          }
        });
  }
}

// End Conditions.java
