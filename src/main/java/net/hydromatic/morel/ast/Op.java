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
package net.hydromatic.morel.ast;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Subtypes of {@link AstNode}. */
public enum Op {
  // identifiers
  ID(Assoc.ATOM),
  OP_SECTION(Assoc.ATOM),
  CURRENT(" current ", Assoc.ATOM),
  ELEMENTS(" elements ", Assoc.ATOM),
  ORDINAL(" ordinal ", Assoc.ATOM),
  RECORD_SELECTOR(Assoc.ATOM),

  // literals
  BOOL_LITERAL(Assoc.ATOM),
  CHAR_LITERAL(Assoc.ATOM),
  INT_LITERAL(Assoc.ATOM),
  REAL_LITERAL(Assoc.ATOM),
  STRING_LITERAL(Assoc.ATOM),
  UNIT_LITERAL(Assoc.ATOM),
  /** Literal whose value is a {@link net.hydromatic.morel.compile.BuiltIn}. */
  FN_LITERAL(Assoc.ATOM), // occurs in Core, not in Ast
  /** Literal whose value is a non-atomic value, such as a record or list. */
  VALUE_LITERAL(Assoc.ATOM), // occurs in Core, not in Ast
  INTERNAL_LITERAL(Assoc.ATOM), // occurs in Core, not in Ast

  // patterns
  ID_PAT(Assoc.ATOM),
  WILDCARD_PAT,
  AS_PAT(" as "),
  CON_PAT(" "),
  CON0_PAT(" "),
  TUPLE_PAT(Assoc.ATOM),
  RECORD_PAT,
  LIST_PAT,
  CONS_PAT(" :: "),
  BOOL_LITERAL_PAT(Assoc.ATOM),
  CHAR_LITERAL_PAT(Assoc.ATOM),
  INT_LITERAL_PAT(Assoc.ATOM),
  REAL_LITERAL_PAT(Assoc.ATOM),
  STRING_LITERAL_PAT(Assoc.ATOM),
  // annotated pattern "p: t"
  ANNOTATED_PAT(" : "),

  // miscellaneous
  BAR(" | "),
  COMMA(","),
  FUN_BIND(" and "),
  FUN_MATCH,
  TY_CON,
  TYPE_DECL,
  DATATYPE_DECL,
  DATATYPE_BIND,
  FUN_DECL,
  VAL_DECL(" = "),
  REC_VAL_DECL(" = "),
  SIGNATURE_DECL,
  SIGNATURE_BIND,
  SPEC_VAL,
  SPEC_TYPE,
  SPEC_DATATYPE,
  SPEC_EXCEPTION,
  ATTRIBUTED_SPEC,
  FLOATING_ATTR_SPEC,

  // internal
  FROM_EQ("$FROM_EQ "),

  // value constructors
  TUPLE(Assoc.ATOM),
  LIST(Assoc.ATOM),
  RECORD(Assoc.ATOM),
  FN(" -> ", 6, Assoc.RIGHT),

  // types
  TY_VAR(Assoc.ATOM),
  RECORD_TYPE(Assoc.ATOM),
  PROGRESSIVE_RECORD_TYPE(Assoc.ATOM),
  DATA_TYPE(" ", 8),
  /**
   * Used internally, as the 'type' of a type constructor that does not contain
   * data.
   */
  DUMMY_TYPE(Assoc.ATOM),
  APPLY_TYPE(" ", 8),
  // '*' is non-associative: '(t1 * t2) * t3', 't1 * (t2 * t3)' and
  // 't1 * t2 * t3' are three distinct types in SML.
  TUPLE_TYPE(" * ", 7, Assoc.NONE),
  COMPOSITE_TYPE,
  FUNCTION_TYPE(" -> ", 6, Assoc.RIGHT),
  NAMED_TYPE(" ", 8),
  ALIAS_TYPE(" ", 8),
  EXPRESSION_TYPE("typeof ", 9),
  FORALL_TYPE,
  MULTI_TYPE,

  // annotated expression "e: t"
  ANNOTATED_EXP(" : ", 0),

  // attributes
  ATTRIBUTE,
  ATTRIBUTED_DECL,
  ATTRIBUTED_EXP,
  ATTRIBUTED_TYPE,
  FLOATING_ATTR_DECL,

  TIMES(" * ", 7),
  DIVIDE(" / ", 7),
  DIV(" div ", 7),
  MOD(" mod ", 7),
  PLUS(" + ", 6),
  MINUS(" - ", 6),
  CARET(" ^ ", 6),
  NEGATE("~ "),
  CONS(" :: ", 5, Assoc.RIGHT),
  AT(" @ ", 5, Assoc.RIGHT),
  LE(" <= ", 4),
  LT(" < ", 4),
  GE(" >= ", 4),
  GT(" > ", 4),
  EQ(" = ", 4),
  NE(" <> ", 4),
  ELEM(" elem ", 4),
  NOT_ELEM(" notelem ", 4),
  ASSIGN(" := ", 3),
  COMPOSE(" o ", 3),
  ANDALSO(" andalso ", 2),
  ORELSE(" orelse ", 1),
  IMPLIES(" implies ", 0),
  BEFORE(" before ", 0),
  LET,
  LOCAL,
  MATCH,
  VAL_BIND,
  APPLY(" ", 8),
  POSTFIX_APP(" ", 8),
  CASE,
  FROM,
  EXISTS,
  FORALL,
  SCAN(" "),
  DISTINCT,
  WHERE,
  GROUP,
  COMPUTE,
  ORDER,
  ORDER_ITEM,
  REQUIRE,
  SKIP,
  TAKE,
  UNORDER,
  EXCEPT(" except "),
  INTERSECT(" intersect "),
  UNION(" union "),
  YIELD,
  INTO,
  THROUGH,
  AGGREGATE,
  IF,
  RAISE,
  OVER_DECL;

  /** Padded name, e.g. " : ". */
  public final String padded;
  /** Left precedence */
  public final int left;
  /** Right precedence */
  public final int right;
  /** Associativity (LEFT, RIGHT, or NONE). */
  private final Assoc assoc;
  /** Operator name. Sometimes null, sometimes something like "op +". */
  public final @Nullable String opName;

  public static final ImmutableMap<String, Op> BY_OP_NAME;

  static {
    final ImmutableMap.Builder<String, Op> b = ImmutableMap.builder();
    for (Op op : values()) {
      if (op.opName != null
          && op.opName.startsWith("op ")
          && !op.opName.equals("op ")
          && !op.name().endsWith("_TYPE")
          && !op.name().endsWith("_PAT")
          && !op.name().endsWith("_DECL")) {
        b.put(op.opName, op);
      }
    }
    BY_OP_NAME = b.build();
  }

  Op() {
    this("", 0, Assoc.AST);
  }

  Op(Assoc assoc) {
    this("", assoc);
  }

  Op(String padded, Assoc assoc) {
    this(padded, assoc == Assoc.ATOM ? Assoc.ATOM_PRECEDENCE : 0, assoc);
  }

  Op(String padded) {
    this(padded, Assoc.NONE);
  }

  Op(String padded, int precedence) {
    this(padded, precedence, Assoc.LEFT);
  }

  Op(String padded, int precedence, Assoc assoc) {
    this.padded = requireNonNull(padded);
    this.left = precedence * 2 + (assoc == Assoc.LEFT ? 0 : 1);
    this.right = precedence * 2 + (assoc == Assoc.RIGHT ? 0 : 1);
    this.assoc = assoc;
    this.opName = padded.isEmpty() ? null : "op " + padded.trim();
  }

  /** Associativity of an operator. */
  private enum Assoc {
    /** Left-associative binary infix, e.g. {@code +}. */
    LEFT,
    /** Right-associative binary infix, e.g. {@code ->}. */
    RIGHT,
    /** Non-associative binary infix, e.g. {@code *} as a type constructor. */
    NONE,
    /** Atomic; not an infix operator (e.g. literals, identifiers). */
    ATOM,
    /** Not an expression. */
    AST;

    /** Precedence for atoms; higher than any infix operator. */
    static final int ATOM_PRECEDENCE = 99;
  }

  /**
   * Returns whether an expression of this operator should be parenthesised when
   * nested inside a context with the given precedence bounds. For associative
   * operators the comparison is strict; for non-associative operators it is
   * non-strict, so that a child of equal precedence wraps — for example, {@code
   * (int * bool) * int} keeps its inner parens rather than flattening into
   * {@code int * bool * int}.
   */
  public boolean wraps(int leftBound, int rightBound) {
    if (assoc == Assoc.NONE) {
      return leftBound >= left || rightBound >= right;
    }
    return leftBound > left || rightBound > right;
  }

  /** Returns the name in lower case, e.g. "exists" for {@link #EXISTS}. */
  public String lowerName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Converts the op of a literal or tuple expression to the corresponding op of
   * a pattern.
   */
  public Op toPat() {
    switch (this) {
      case BOOL_LITERAL:
        return BOOL_LITERAL_PAT;
      case CHAR_LITERAL:
        return CHAR_LITERAL_PAT;
      case INT_LITERAL:
        return INT_LITERAL_PAT;
      case REAL_LITERAL:
        return REAL_LITERAL_PAT;
      case STRING_LITERAL:
        return STRING_LITERAL_PAT;
      case TUPLE:
        return TUPLE_PAT;
      default:
        throw new AssertionError("unknown op " + this);
    }
  }
}

// End Op.java
