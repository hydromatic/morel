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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Ast.ModifierVerb;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.TypeResolver.TypeException;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Where the fields of a modified record come from.
 *
 * <p>Applying a modifier to a record whose field names are known yields a list
 * of fields, each taking its value from the record the modifier was applied to,
 * from an expression the modifier assigns, or from a field of the modifier's
 * {@code all} argument.
 *
 * <p>{@link TypeResolver} reads that list to deduce the type of the modified
 * record, and {@link Resolver} reads it again to build the record. Deriving it
 * twice from the same rules is what keeps the type and the value in step.
 */
class RecordModifiers {
  private RecordModifiers() {}

  /**
   * Applies {@code modifier} to a record whose fields are {@code fields},
   * returning where each field of the result gets its value.
   *
   * <p>Also checks the labels the modifier mentions against the fields it is
   * applied to, and throws if the verb says that a label present (or absent) is
   * an error.
   *
   * @param allFields field names of the modifier's argument, if it is an {@code
   *     all} modifier; otherwise null
   */
  static PairList<String, Source> apply(
      Ast.Modifier modifier,
      List<String> fields,
      @Nullable List<String> allFields) {
    final PairList<String, Source> sources = PairList.of();
    switch (modifier.op) {
      case ASSIGN_MODIFIER:
        assign((Ast.AssignModifier) modifier, fields, sources);
        break;

      case ALL_MODIFIER:
        assignAll(
            (Ast.AllModifier) modifier,
            fields,
            requireNonNull(allFields),
            sources);
        break;

      case REMOVE_MODIFIER:
        remove((Ast.RemoveModifier) modifier, fields, sources);
        break;

      case RENAME_MODIFIER:
        rename((Ast.RenameModifier) modifier, fields, sources);
        break;

      default:
        throw new AssertionError(modifier.op);
    }
    return sources;
  }

  /**
   * Applies an {@code extend} or {@code replace} modifier, in either case
   * taking each label to whichever of the verb's two cases it falls in: the
   * record has the label already, or it does not.
   */
  private static void assign(
      Ast.AssignModifier modifier,
      List<String> fields,
      PairList<String, Source> sources) {
    final Map<String, Ast.Exp> assigned = new LinkedHashMap<>();
    modifier.args.forEach(
        (id, exp) -> {
          checkLabel(modifier.verb, fields.contains(id.name), id.name, id.pos);
          assigned.put(id.name, exp);
        });

    // Fields the record has: assigned, or kept as they were.
    fields.forEach(
        field -> {
          final Ast.Exp exp = assigned.get(field);
          if (exp == null || modifier.verb.exists == ModifierVerb.Exists.SKIP) {
            sources.add(field, new Kept(field));
          } else {
            sources.add(field, new Assigned(exp, !modifier.lenient));
          }
        });

    // Labels the record does not have: added, or ignored. An added field has
    // no type to keep, so 'lenient' does not arise.
    if (modifier.verb.absent == ModifierVerb.Absent.ADD) {
      modifier.args.forEach(
          (id, exp) -> {
            if (!fields.contains(id.name)) {
              sources.add(id.name, new Assigned(exp, false));
            }
          });
    }
  }

  /**
   * Applies an {@code extend all} or {@code replace all} modifier: the same
   * rules as {@link #assign}, for every field of the modifier's record-valued
   * argument.
   */
  private static void assignAll(
      Ast.AllModifier modifier,
      List<String> fields,
      List<String> allFields,
      PairList<String, Source> sources) {
    allFields.forEach(
        field ->
            checkLabel(
                modifier.verb,
                fields.contains(field),
                field,
                modifier.exp.pos));

    fields.forEach(
        field -> {
          if (!allFields.contains(field)
              || modifier.verb.exists == ModifierVerb.Exists.SKIP) {
            sources.add(field, new Kept(field));
          } else {
            sources.add(field, new Taken(field, !modifier.lenient));
          }
        });

    if (modifier.verb.absent == ModifierVerb.Absent.ADD) {
      allFields.forEach(
          field -> {
            if (!fields.contains(field)) {
              sources.add(field, new Taken(field, false));
            }
          });
    }
  }

  /**
   * Applies a {@code rename} modifier. It takes the value of each label on the
   * right, which must exist, and gives it to the label on the left, which must
   * not survive the renaming.
   */
  private static void rename(
      Ast.RenameModifier modifier,
      List<String> fields,
      PairList<String, Source> sources) {
    final Set<String> renamed = new LinkedHashSet<>();
    modifier.args.forEach(
        (target, source) -> {
          if (!fields.contains(source.name)) {
            throw fieldNotFound(source.name, source.pos);
          }
          if (!renamed.add(source.name)) {
            throw duplicateField(source.name, source.pos);
          }
        });
    fields.forEach(
        field -> {
          if (!renamed.contains(field)) {
            sources.add(field, new Kept(field));
          }
        });
    modifier.args.forEach(
        (target, source) -> {
          if (sources.leftList().contains(target.name)) {
            throw fieldExists(target.name, target.pos);
          }
          sources.add(target.name, new Kept(source.name));
        });
  }

  /** Applies a {@code remove} modifier. */
  private static void remove(
      Ast.RemoveModifier modifier,
      List<String> fields,
      PairList<String, Source> sources) {
    final Set<String> removed = new LinkedHashSet<>();
    modifier.labels.forEach(
        id -> {
          if (!fields.contains(id.name)
              && modifier.verb.absent == ModifierVerb.Absent.ERROR) {
            throw fieldNotFound(id.name, id.pos);
          }
          if (!removed.add(id.name)) {
            throw duplicateField(id.name, id.pos);
          }
        });
    fields.forEach(
        field -> {
          if (!removed.contains(field)) {
            sources.add(field, new Kept(field));
          }
        });
  }

  /**
   * Throws if a verb makes it an error that a label is present, or that it is
   * absent.
   */
  private static void checkLabel(
      ModifierVerb verb, boolean exists, String label, Pos pos) {
    if (exists) {
      if (verb.exists == ModifierVerb.Exists.ERROR) {
        throw fieldExists(label, pos);
      }
    } else {
      if (verb.absent == ModifierVerb.Absent.ERROR) {
        throw fieldNotFound(label, pos);
      }
    }
  }

  static TypeException fieldNotFound(String field, Pos pos) {
    return new TypeException(format("field '%s' does not exist", field), pos);
  }

  static TypeException fieldExists(String field, Pos pos) {
    return new TypeException(format("field '%s' already exists", field), pos);
  }

  private static TypeException duplicateField(String field, Pos pos) {
    return new TypeException(
        format("duplicate field '%s' in record", field), pos);
  }

  /** Where one field of a modified record gets its value. */
  abstract static class Source {}

  /**
   * Field that keeps the value of a field of the record the modifier was
   * applied to. The name may differ, if the modifier is a {@code rename}.
   */
  static class Kept extends Source {
    final String field;

    Kept(String field) {
      this.field = requireNonNull(field);
    }
  }

  /**
   * Field that is assigned the value of an expression.
   *
   * <p>{@code sameType} says whether the field keeps the type it had. It is
   * false if the field is being added, because then there is no type to keep,
   * and if the modifier is {@code lenient}, which is what {@code lenient}
   * means.
   */
  static class Assigned extends Source {
    final Ast.Exp exp;
    final boolean sameType;

    Assigned(Ast.Exp exp, boolean sameType) {
      this.exp = requireNonNull(exp);
      this.sameType = sameType;
    }
  }

  /**
   * Field that is assigned a field of the argument of an {@code all} modifier.
   * {@code sameType} means what it does in {@link Assigned}.
   */
  static class Taken extends Source {
    final String field;
    final boolean sameType;

    Taken(String field, boolean sameType) {
      this.field = requireNonNull(field);
      this.sameType = sameType;
    }
  }
}

// End RecordModifiers.java
