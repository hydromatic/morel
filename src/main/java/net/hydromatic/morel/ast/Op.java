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

import com.google.common.collect.ImmutableMap;

/** Sub-types of {@link AstNode}. */
public enum Op {
  // identifiers
  ID(true),
  RECORD_SELECTOR(true),

  // literals
  BOOL_LITERAL(true),
  CHAR_LITERAL(true),
  INT_LITERAL(true),
  REAL_LITERAL(true),
  STRING_LITERAL(true),
  UNIT_LITERAL(true),
  /** Literal whose value is a {@link net.hydromatic.morel.compile.BuiltIn}. */
  FN_LITERAL(true), // occurs in Core, not in Ast
  /** Literal whose value is a non-atomic value, such as a record or list. */
  VALUE_LITERAL(true), // occurs in Core, not in Ast
  INTERNAL_LITERAL(true), // occurs in Core, not in Ast

  // patterns
  ID_PAT(true),
  WILDCARD_PAT,
  AS_PAT(" as "),
  CON_PAT(" "),
  CON0_PAT(" "),
  TUPLE_PAT(true),
  RECORD_PAT,
  LIST_PAT,
  CONS_PAT(" :: "),
  BOOL_LITERAL_PAT(true),
  CHAR_LITERAL_PAT(true),
  INT_LITERAL_PAT(true),
  REAL_LITERAL_PAT(true),
  STRING_LITERAL_PAT(true),
  // annotated pattern "p: t"
  ANNOTATED_PAT(" : "),

  // miscellaneous
  BAR(" | "),
  COMMA(","),
  FUN_BIND(" and "),
  FUN_MATCH,
  TY_CON,
  DATATYPE_DECL,
  DATATYPE_BIND,
  FUN_DECL,
  VAL_DECL(" = "),
  REC_VAL_DECL(" = "),

  // internal
  FROM_EQ("$FROM_EQ "),

  // value constructors
  TUPLE(true),
  LIST(" list", 8),
  RECORD(true),
  FN(" -> ", 6, false),

  // types
  TY_VAR(true),
  RECORD_TYPE(true),
  PROGRESSIVE_RECORD_TYPE(true),
  DATA_TYPE(" ", 8),
  /** Used internally, as the 'type' of a type constructor that does not contain
   * data. */
  DUMMY_TYPE(true),
  APPLY_TYPE(" ", 8),
  TUPLE_TYPE(" * ", 7),
  COMPOSITE_TYPE,
  FUNCTION_TYPE(" -> ", 6, false),
  NAMED_TYPE(" ", 8),
  FORALL_TYPE,

  // annotated expression "e: t"
  ANNOTATED_EXP(" : ", 0),

  TIMES(" * ", 7),
  DIVIDE(" / ", 7),
  DIV(" div ", 7),
  MOD(" mod ", 7),
  INTERSECT(" intersect ", 7),
  PLUS(" + ", 6),
  MINUS(" - ", 6),
  CARET(" ^ ", 6),
  EXCEPT(" except ", 6),
  UNION(" union ", 6),
  NEGATE("~ "),
  CONS(" :: ", 5, false),
  AT(" @ ", 5, false),
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
  BEFORE(" before ", 0),
  LET,
  LOCAL,
  MATCH,
  VAL_BIND,
  APPLY(" ", 8),
  CASE,
  FROM,
  SCAN(" "),
  INNER_JOIN(" join "),
  WHERE,
  GROUP,
  COMPUTE,
  ORDER,
  ORDER_ITEM,
  SKIP,
  TAKE,
  YIELD,
  AGGREGATE,
  IF;

  /** Padded name, e.g. " : ". */
  public final String padded;
  /** Left precedence */
  public final int left;
  /** Right precedence */
  public final int right;
  /** Operator name. Sometimes null, sometimes something like "op +". */
  public final String opName;

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
    this(null, 0, 0);
  }

  Op(boolean atom) {
    this("", 99);
    assert atom;
  }

  Op(String padded) {
    this(padded, 0, 0);
  }

  Op(String padded, int leftPrecedence) {
    this(padded, leftPrecedence, true);
  }

  Op(String padded, int precedence, boolean leftAssociative) {
    this(padded,
        precedence * 2 + (leftAssociative ? 0 : 1),
        precedence * 2 + (leftAssociative ? 1 : 0));
  }

  Op(String padded, int left, int right) {
    this.padded = padded;
    this.left = left;
    this.right = right;
    this.opName = padded == null || padded.equals("")
        ? null
        : "op " + padded.trim();
  }

  /** Converts the op of a literal or tuple expression to the corresponding op
   * of a pattern. */
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
