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
package net.hydromatic.sml.ast;

/** Sub-types of {@link AstNode}. */
public enum Op {
  // identifiers
  ID("", 99),
  RECORD_SELECTOR,

  // literals
  INT_LITERAL,
  BOOL_LITERAL,
  REAL_LITERAL,
  STRING_LITERAL,
  UNIT_LITERAL,

  // patterns
  ID_PAT,
  WILDCARD_PAT,
  TUPLE_PAT,
  INT_LITERAL_PAT,
  BOOL_LITERAL_PAT,
  STRING_LITERAL_PAT,
  REAL_LITERAL_PAT,
  // annotated pattern "p: t"
  ANNOTATED_PAT(" : "),

  // value constructors
  TUPLE(" * ", 7),
  RECORD,
  FN(" -> ", 6, false),

  // annotated expression "e: t"
  ANNOTATED_EXP(" : "),
  NAMED_TYPE,
  VAL_DECL(" = "),
  TIMES(" * ", 7),
  DIVIDE(" / ", 7),
  DIV(" div ", 7),
  MOD(" mod ", 7),
  PLUS(" + ", 6),
  MINUS(" - ", 6),
  CARET(" ^ ", 6),
  CONS(" :: ", 5, false),
  AT(" @ ", 5, false),
  LE(" <= ", 4),
  LT(" < ", 4),
  GE(" >= ", 4),
  GT(" > ", 4),
  EQ(" = ", 4),
  NE(" <> ", 4),
  ASSIGN(" := ", 3),
  COMPOSE(" o ", 3),
  ANDALSO(" andalso ", 2),
  ORELSE(" orelse ", 1),
  BEFORE(" before ", 0),
  LET,
  MATCH,
  VAL_BIND,
  APPLY(" ", 8),
  IF;

  /** Padded name, e.g. " : ". */
  public final String padded;
  /** Left precedence */
  public final int left;
  /** Right precedence */
  public final int right;

  Op() {
    this(null, 0, 0);
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
  }
}

// End Op.java
