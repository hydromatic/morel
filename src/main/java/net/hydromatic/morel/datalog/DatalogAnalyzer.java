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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.datalog.DatalogAst.ArithmeticExpr;
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.BodyAtom;
import net.hydromatic.morel.datalog.DatalogAst.Comparison;
import net.hydromatic.morel.datalog.DatalogAst.Constant;
import net.hydromatic.morel.datalog.DatalogAst.Declaration;
import net.hydromatic.morel.datalog.DatalogAst.Fact;
import net.hydromatic.morel.datalog.DatalogAst.Input;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.datalog.DatalogAst.Rule;
import net.hydromatic.morel.datalog.DatalogAst.Statement;
import net.hydromatic.morel.datalog.DatalogAst.Term;
import net.hydromatic.morel.datalog.DatalogAst.Variable;

/**
 * Analyzer for Datalog programs.
 *
 * <p>Performs safety checking and stratification analysis.
 */
public class DatalogAnalyzer {
  private DatalogAnalyzer() {
    // Utility class
  }

  /**
   * Analyzes a Datalog program for safety and stratification.
   *
   * @param program the program to analyze
   * @throws DatalogException if the program is unsafe or non-stratified
   */
  public static void analyze(Program program) {
    checkDeclarations(program);
    checkSafety(program);
    checkStratification(program);
  }

  /**
   * Checks that all relations used in facts, rules, and directives are
   * declared.
   */
  private static void checkDeclarations(Program program) {
    for (Input input : program.getInputs()) {
      if (!program.hasDeclaration(input.relationName)) {
        throw new DatalogException(
            String.format(
                "Relation '%s' used in .input but not declared",
                input.relationName));
      }
    }
    for (Statement stmt : program.statements) {
      if (stmt instanceof Fact) {
        Fact fact = (Fact) stmt;
        checkAtomDeclaration(program, fact.atom, "fact");
        // Facts must only contain constants, not variables
        checkFactConstants(fact);
      } else if (stmt instanceof Rule) {
        Rule rule = (Rule) stmt;
        checkAtomDeclaration(program, rule.head, "rule head");
        for (BodyAtom bodyAtom : rule.body) {
          // Skip comparisons - they don't refer to declared relations
          if (!(bodyAtom instanceof Comparison)) {
            checkAtomDeclaration(program, bodyAtom.atom, "rule body");
          }
        }
      }
    }
  }

  /**
   * Checks that a fact contains only constants, not variables or arithmetic.
   */
  private static void checkFactConstants(Fact fact) {
    for (Term term : fact.atom.terms) {
      if (term instanceof Variable) {
        throw new DatalogException(
            String.format(
                "Argument in fact is not constant: %s",
                ((Variable) term).name));
      }
      if (term instanceof ArithmeticExpr) {
        throw new DatalogException(
            String.format("Argument in fact is not constant: %s", term));
      }
    }
  }

  private static void checkAtomDeclaration(
      Program program, Atom atom, String context) {
    if (!program.hasDeclaration(atom.name)) {
      throw new DatalogException(
          String.format(
              "Relation '%s' used in %s but not declared", atom.name, context));
    }

    Declaration decl = program.getDeclaration(atom.name);
    if (atom.arity() != decl.arity()) {
      throw new DatalogException(
          String.format(
              "Atom %s/%d does not match declaration %s/%d",
              atom.name, atom.arity(), decl.name, decl.arity()));
    }

    // Check types of constants in the atom
    for (int i = 0; i < atom.terms.size(); i++) {
      Term term = atom.terms.get(i);
      if (term instanceof Constant) {
        Constant constant = (Constant) term;
        String expectedType = decl.params.get(i).type;
        String actualType = constant.type;

        // Map types for validation
        String normalizedExpected = normalizeType(expectedType);
        String normalizedActual = normalizeType(actualType);

        if (!normalizedExpected.equals(normalizedActual)) {
          throw new DatalogException(
              String.format(
                  "Type mismatch in %s %s(...): expected %s, got %s for parameter %s",
                  context,
                  atom.name,
                  expectedType,
                  actualType,
                  decl.params.get(i).name));
        }
      }
    }
  }

  private static String normalizeType(String type) {
    // Normalize type names for comparison
    return type;
  }

  /**
   * Checks that all rules are safe.
   *
   * <p>A rule is safe if:
   *
   * <ul>
   *   <li>Each variable in the head appears in a positive body atom
   *   <li>Each variable in a negated body atom appears in a positive body atom
   * </ul>
   */
  private static void checkSafety(Program program) {
    for (Statement stmt : program.statements) {
      if (stmt instanceof Rule) {
        Rule rule = (Rule) stmt;
        checkRuleSafety(rule);
      }
    }
  }

  private static void checkRuleSafety(Rule rule) {
    // Collect variables from positive body atoms (but not comparisons).
    // Only direct Variable terms ground a variable; variables inside
    // ArithmeticExpr do not (following Souffle semantics).
    Set<String> groundedVars = new HashSet<>();
    for (BodyAtom bodyAtom : rule.body) {
      if (!bodyAtom.negated && !(bodyAtom instanceof Comparison)) {
        for (Term term : bodyAtom.atom.terms) {
          if (term instanceof Variable) {
            groundedVars.add(((Variable) term).name);
          }
        }
      }
    }

    // Check that all variables in head terms are grounded
    for (Term term : rule.head.terms) {
      Set<String> vars = new HashSet<>();
      extractVariables(term, vars);
      for (String varName : vars) {
        if (!groundedVars.contains(varName)) {
          throw new DatalogException(
              String.format(
                  "Rule is unsafe. Variable '%s' in head does not appear"
                      + " in positive body atom",
                  varName));
        }
      }
    }

    // Check that variables in negated atoms and comparisons are grounded
    for (BodyAtom bodyAtom : rule.body) {
      if (bodyAtom.negated || bodyAtom instanceof Comparison) {
        Set<String> vars = new HashSet<>();
        if (bodyAtom instanceof Comparison) {
          Comparison comp = (Comparison) bodyAtom;
          extractVariables(comp.left, vars);
          extractVariables(comp.right, vars);
        } else {
          for (Term term : bodyAtom.atom.terms) {
            extractVariables(term, vars);
          }
        }
        for (String varName : vars) {
          if (!groundedVars.contains(varName)) {
            String context =
                bodyAtom instanceof Comparison ? "comparison" : "negated atom";
            throw new DatalogException(
                String.format(
                    "Rule is unsafe. Variable '%s' in %s does not appear"
                        + " in positive body atom",
                    varName, context));
          }
        }
      }
    }
  }

  /** Recursively extracts all variable names from a term. */
  private static void extractVariables(Term term, Set<String> vars) {
    if (term instanceof Variable) {
      vars.add(((Variable) term).name);
    } else if (term instanceof ArithmeticExpr) {
      ArithmeticExpr expr = (ArithmeticExpr) term;
      extractVariables(expr.left, vars);
      extractVariables(expr.right, vars);
    }
    // Constants have no variables
  }

  /**
   * Checks that the program is stratified.
   *
   * <p>A program is stratified if there are no cycles in the dependency graph
   * that contain a negated edge.
   */
  private static void checkStratification(Program program) {
    // Build dependency graph
    Map<String, Set<Dependency>> graph = new HashMap<>();

    for (Statement stmt : program.statements) {
      if (stmt instanceof Rule) {
        Rule rule = (Rule) stmt;
        String headRelation = rule.head.name;

        graph.putIfAbsent(headRelation, new HashSet<>());

        for (BodyAtom bodyAtom : rule.body) {
          // Skip comparisons - they don't create dependencies
          if (!(bodyAtom instanceof Comparison)) {
            String bodyRelation = bodyAtom.atom.name;
            graph
                .get(headRelation)
                .add(new Dependency(bodyRelation, bodyAtom.negated));
          }
        }
      }
    }

    // Check for cycles with negation using DFS
    for (String relation : graph.keySet()) {
      Set<String> visited = new HashSet<>();
      Set<String> recStack = new HashSet<>();
      if (hasNegationCycle(graph, relation, visited, recStack, false)) {
        throw new DatalogException(
            String.format(
                "Program is not stratified. Negation cycle detected involving relation: %s",
                relation));
      }
    }
  }

  private static boolean hasNegationCycle(
      Map<String, Set<Dependency>> graph,
      String node,
      Set<String> visited,
      Set<String> recStack,
      boolean seenNegation) {
    if (recStack.contains(node)) {
      // Found a cycle; it's a problem if we've seen negation
      return seenNegation;
    }

    if (visited.contains(node)) {
      return false;
    }

    visited.add(node);
    recStack.add(node);

    Set<Dependency> dependencies = graph.get(node);
    if (dependencies != null) {
      for (Dependency dep : dependencies) {
        boolean negInPath = seenNegation || dep.negated;
        if (hasNegationCycle(graph, dep.target, visited, recStack, negInPath)) {
          return true;
        }
      }
    }

    recStack.remove(node);
    return false;
  }

  /** A dependency between relations in the dependency graph. */
  private static class Dependency {
    final String target;
    final boolean negated;

    Dependency(String target, boolean negated) {
      this.target = target;
      this.negated = negated;
    }
  }
}

// End DatalogAnalyzer.java
