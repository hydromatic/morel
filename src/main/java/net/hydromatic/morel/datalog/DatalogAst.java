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
package net.hydromatic.morel.datalog;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Abstract syntax tree nodes for Datalog programs.
 *
 * <p>A Datalog program consists of declarations, facts, rules, and directives.
 */
public class DatalogAst {
  private DatalogAst() {
    // Utility class
  }

  /** A complete Datalog program. */
  public static class Program {
    public final List<Statement> statements;
    private final Map<String, Declaration> declarations;
    private final List<Input> inputs;
    private final List<Output> outputs;

    public Program(List<Statement> statements) {
      this.statements = ImmutableList.copyOf(statements);
      this.declarations = new HashMap<>();
      ImmutableList.Builder<Input> inputsBuilder = ImmutableList.builder();
      ImmutableList.Builder<Output> outputsBuilder = ImmutableList.builder();

      // Index declarations, inputs, and outputs for easy lookup
      for (Statement stmt : statements) {
        if (stmt instanceof Declaration) {
          Declaration decl = (Declaration) stmt;
          declarations.put(decl.name, decl);
        } else if (stmt instanceof Input) {
          inputsBuilder.add((Input) stmt);
        } else if (stmt instanceof Output) {
          outputsBuilder.add((Output) stmt);
        }
      }
      this.inputs = inputsBuilder.build();
      this.outputs = outputsBuilder.build();
    }

    public Declaration getDeclaration(String name) {
      return declarations.get(name);
    }

    public List<Input> getInputs() {
      return inputs;
    }

    public List<Output> getOutputs() {
      return outputs;
    }

    public boolean hasDeclaration(String name) {
      return declarations.containsKey(name);
    }

    public Iterable<Declaration> getDeclarations() {
      return declarations.values();
    }
  }

  /** Base class for all statements in a Datalog program. */
  public abstract static class Statement {
    // Marker class
  }

  /** A relation declaration: .decl relation(var:type, ...) */
  public static class Declaration extends Statement {
    public final String name;
    public final List<Param> params;

    public Declaration(String name, List<Param> params) {
      this.name = requireNonNull(name);
      this.params = ImmutableList.copyOf(params);
    }

    public int arity() {
      return params.size();
    }

    @Override
    public String toString() {
      return ".decl " + name + "(" + params + ")";
    }
  }

  /** A parameter in a relation declaration. */
  public static class Param {
    public final String name;
    public final String type;

    public Param(String name, String type) {
      this.name = requireNonNull(name);
      this.type = requireNonNull(type);
    }

    @Override
    public String toString() {
      return name + ":" + type;
    }
  }

  /** An input directive: .input relation [file_name] */
  public static class Input extends Statement {
    public final String relationName;
    public final @Nullable String fileName;

    public Input(String relationName, @Nullable String fileName) {
      this.relationName = requireNonNull(relationName);
      this.fileName = fileName;
    }

    /** Returns the effective file name (explicit or default). */
    public String effectiveFileName() {
      return fileName != null ? fileName : relationName + ".csv";
    }

    @Override
    public String toString() {
      return fileName != null
          ? ".input " + relationName + " \"" + fileName + "\""
          : ".input " + relationName;
    }
  }

  /** An output directive: .output relation */
  public static class Output extends Statement {
    public final String relationName;

    public Output(String relationName) {
      this.relationName = requireNonNull(relationName);
    }

    @Override
    public String toString() {
      return ".output " + relationName;
    }
  }

  /** A fact: relation(value, ...). */
  public static class Fact extends Statement {
    public final Atom atom;

    public Fact(Atom atom) {
      this.atom = requireNonNull(atom);
    }

    @Override
    public String toString() {
      return atom + ".";
    }
  }

  /** A rule: head :- body. */
  public static class Rule extends Statement {
    public final Atom head;
    public final List<BodyAtom> body;

    public Rule(Atom head, List<BodyAtom> body) {
      this.head = requireNonNull(head);
      this.body = ImmutableList.copyOf(body);
    }

    @Override
    public String toString() {
      return head + " :- " + body + ".";
    }
  }

  /** An atom in a rule body (can be positive or negated). */
  public static class BodyAtom {
    public final Atom atom;
    public final boolean negated;

    public BodyAtom(Atom atom, boolean negated) {
      this.atom = requireNonNull(atom);
      this.negated = negated;
    }

    @Override
    public String toString() {
      return negated ? "!" + atom : atom.toString();
    }
  }

  /** Comparison operators for use in rule bodies. */
  public enum CompOp {
    EQ("="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    public final String symbol;

    CompOp(String symbol) {
      this.symbol = symbol;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  /** A comparison predicate in a rule body. */
  public static class Comparison extends BodyAtom {
    public final Term left;
    public final CompOp op;
    public final Term right;

    public Comparison(Term left, CompOp op, Term right) {
      super(new Atom("$compare", ImmutableList.of(left, right)), false);
      this.left = requireNonNull(left);
      this.op = requireNonNull(op);
      this.right = requireNonNull(right);
    }

    @Override
    public String toString() {
      return left + " " + op + " " + right;
    }
  }

  /** An atom: relation(term, ...) */
  public static class Atom {
    public final String name;
    public final List<Term> terms;

    public Atom(String name, List<Term> terms) {
      this.name = requireNonNull(name);
      this.terms = ImmutableList.copyOf(terms);
    }

    public int arity() {
      return terms.size();
    }

    @Override
    public String toString() {
      return name + "(" + terms + ")";
    }
  }

  /** Base class for terms in atoms. */
  public abstract static class Term {
    // Marker class
  }

  /** A variable term. */
  public static class Variable extends Term {
    public final String name;

    public Variable(String name) {
      this.name = requireNonNull(name);
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Variable)) {
        return false;
      }
      Variable variable = (Variable) o;
      return name.equals(variable.name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }

  /** Arithmetic operators for use in terms. */
  public enum ArithOp {
    PLUS("+"),
    MINUS("-"),
    TIMES("*"),
    DIVIDE("/");

    public final String symbol;

    ArithOp(String symbol) {
      this.symbol = symbol;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  /** An arithmetic expression term (e.g., {@code N + 1}). */
  public static class ArithmeticExpr extends Term {
    public final Term left;
    public final ArithOp op;
    public final Term right;

    public ArithmeticExpr(Term left, ArithOp op, Term right) {
      this.left = requireNonNull(left);
      this.op = requireNonNull(op);
      this.right = requireNonNull(right);
    }

    @Override
    public String toString() {
      return left + " " + op + " " + right;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ArithmeticExpr)) {
        return false;
      }
      ArithmeticExpr that = (ArithmeticExpr) o;
      return left.equals(that.left)
          && op == that.op
          && right.equals(that.right);
    }

    @Override
    public int hashCode() {
      return Objects.hash(left, op, right);
    }
  }

  /** A constant term. */
  public static class Constant extends Term {
    public final Object value;
    public final String type; // "int", "string"

    public Constant(Object value, String type) {
      this.value = requireNonNull(value);
      this.type = requireNonNull(type);
    }

    @Override
    public String toString() {
      if (value instanceof String) {
        return "\"" + value + "\"";
      }
      return value.toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Constant)) {
        return false;
      }
      Constant constant = (Constant) o;
      return value.equals(constant.value) && type.equals(constant.type);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, type);
    }
  }
}

// End DatalogAst.java
