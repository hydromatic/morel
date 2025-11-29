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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.datalog.DatalogAst.ArithOp;
import net.hydromatic.morel.datalog.DatalogAst.ArithmeticExpr;
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.BodyAtom;
import net.hydromatic.morel.datalog.DatalogAst.CompOp;
import net.hydromatic.morel.datalog.DatalogAst.Comparison;
import net.hydromatic.morel.datalog.DatalogAst.Constant;
import net.hydromatic.morel.datalog.DatalogAst.Declaration;
import net.hydromatic.morel.datalog.DatalogAst.Fact;
import net.hydromatic.morel.datalog.DatalogAst.Output;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.datalog.DatalogAst.Rule;
import net.hydromatic.morel.datalog.DatalogAst.Statement;
import net.hydromatic.morel.datalog.DatalogAst.Term;
import net.hydromatic.morel.datalog.DatalogAst.Variable;

/**
 * Translates Datalog programs to Morel source code.
 *
 * <p>Translation strategy:
 *
 * <ul>
 *   <li>Facts-only relations &rarr; list literals ({@code val rel = [...]})
 *   <li>Recursive rules &rarr; {@code Relational.iterate} with semi-naive
 *       evaluation
 *   <li>Non-recursive rules &rarr; {@code Relational.iterate} with empty seed
 *       for deduplication
 *   <li>Output &rarr; record with output relations, using {@code from (x, y) in
 *       rel} to convert tuples to records
 * </ul>
 */
public class DatalogTranslator {

  private DatalogTranslator() {}

  /**
   * Translates a Datalog program to Morel source code.
   *
   * @param ast the parsed Datalog program
   * @return Morel source code string
   */
  public static String translate(Program ast) {
    StringBuilder morel = new StringBuilder();

    // Build declaration map
    Map<String, Declaration> declarationMap = new LinkedHashMap<>();
    for (Declaration decl : ast.getDeclarations()) {
      declarationMap.put(decl.name, decl);
    }

    // Group facts and rules by relation
    Map<String, List<Fact>> factsByRelation = new LinkedHashMap<>();
    Map<String, List<Rule>> rulesByRelation = new LinkedHashMap<>();

    for (Statement stmt : ast.statements) {
      if (stmt instanceof Fact) {
        Fact fact = (Fact) stmt;
        factsByRelation
            .computeIfAbsent(fact.atom.name, k -> new ArrayList<>())
            .add(fact);
      } else if (stmt instanceof Rule) {
        Rule rule = (Rule) stmt;
        rulesByRelation
            .computeIfAbsent(rule.head.name, k -> new ArrayList<>())
            .add(rule);
      }
    }

    // Get declarations in source order
    List<Declaration> orderedDecls = new ArrayList<>();
    for (Statement stmt : ast.statements) {
      if (stmt instanceof Declaration) {
        orderedDecls.add((Declaration) stmt);
      }
    }

    // Check if we need a let expression (when there are facts or rules)
    boolean hasDeclarations = false;
    for (Declaration decl : orderedDecls) {
      List<Fact> facts =
          factsByRelation.getOrDefault(decl.name, new ArrayList<>());
      List<Rule> rules =
          rulesByRelation.getOrDefault(decl.name, new ArrayList<>());

      if (!facts.isEmpty() || !rules.isEmpty()) {
        hasDeclarations = true;
        break;
      }
    }

    if (hasDeclarations) {
      // Start let expression
      morel.append("let\n");

      for (Declaration decl : orderedDecls) {
        List<Fact> facts =
            factsByRelation.getOrDefault(decl.name, new ArrayList<>());
        List<Rule> rules =
            rulesByRelation.getOrDefault(decl.name, new ArrayList<>());

        if (facts.isEmpty() && rules.isEmpty()) {
          continue;
        }

        if (rules.isEmpty()) {
          // Fact-only: val rel = [facts]
          morel.append("  val ").append(decl.name).append(" = ");
          morel.append(translateFactsToList(decl, facts));
          morel.append("\n");
        } else {
          // Has rules: use Relational.iterate
          appendRuleRelation(morel, decl, facts, rules, declarationMap);
        }
      }

      // in clause with output record
      morel.append("in\n").append("  ");
    }

    // Generate output expression
    List<Output> outputs = ast.getOutputs();
    if (outputs.isEmpty()) {
      morel.append("()");
    } else {
      morel.append("{");
      for (int i = 0; i < outputs.size(); i++) {
        if (i > 0) {
          morel.append(", ");
        }
        String relName = outputs.get(i).relationName;
        morel.append(relName).append(" = ");

        Declaration decl = declarationMap.get(relName);
        List<Fact> facts =
            factsByRelation.getOrDefault(relName, new ArrayList<>());
        List<Rule> rules =
            rulesByRelation.getOrDefault(relName, new ArrayList<>());

        if (!facts.isEmpty() || !rules.isEmpty()) {
          if (decl.arity() <= 1) {
            // Arity 0 or 1: reference the list directly
            morel.append(relName);
          } else {
            // Arity 2+: destructure tuples into records.
            // Parenthesize if multiple outputs to avoid ambiguity
            // (from's comma would be parsed as a new from source).
            if (outputs.size() > 1) {
              morel.append("(");
            }
            morel.append("from (");
            for (int j = 0; j < decl.params.size(); j++) {
              if (j > 0) {
                morel.append(", ");
              }
              morel.append(decl.params.get(j).name);
            }
            morel.append(") in ").append(relName);
            if (outputs.size() > 1) {
              morel.append(")");
            }
          }
        } else {
          // Empty relation
          morel.append("[]");
        }
      }
      morel.append("}");
    }

    // end clause (only if we started with let)
    if (hasDeclarations) {
      morel.append("\n").append("end");
    }

    return morel.toString();
  }

  /**
   * Appends a relation defined by rules (and possibly facts) using {@code
   * Relational.iterate}.
   */
  private static void appendRuleRelation(
      StringBuilder morel,
      Declaration decl,
      List<Fact> facts,
      List<Rule> rules,
      Map<String, Declaration> declarationMap) {
    String relName = decl.name;
    boolean isRecursive = rules.stream().anyMatch(r -> isRecursive(r, relName));

    // Compute seed and classify rules for the step function
    List<String> seedParts = new ArrayList<>();
    List<Rule> stepRules = new ArrayList<>();

    if (!facts.isEmpty()) {
      seedParts.add(translateFactsToList(decl, facts));
    }

    if (isRecursive) {
      // Separate base (non-recursive) rules: passthrough goes to seed,
      // others go to step alongside recursive rules.
      for (Rule rule : rules) {
        if (!isRecursive(rule, relName) && isPassthrough(rule)) {
          seedParts.add(rule.body.get(0).atom.name);
        } else {
          stepRules.add(rule);
        }
      }
    } else {
      // All non-recursive rules go into step (iterate deduplicates)
      stepRules.addAll(rules);
    }

    String seed;
    if (seedParts.isEmpty()) {
      seed = "[]";
    } else if (seedParts.size() == 1) {
      seed = seedParts.get(0);
    } else {
      seed = String.join(" @ ", seedParts);
    }

    morel.append("  val ").append(relName).append(" =\n");
    morel.append("    Relational.iterate ").append(seed).append("\n");

    // Lambda parameters: use names if recursive, _ if not
    String allVar = "all" + capitalize(relName);
    String newVar = "new" + capitalize(relName);

    if (isRecursive) {
      morel
          .append("      (fn (")
          .append(allVar)
          .append(", ")
          .append(newVar)
          .append(") =>\n");
    } else {
      morel.append("      (fn (_, _) =>\n");
    }

    // Build step expressions from rules
    List<String> stepExprs = new ArrayList<>();
    for (Rule rule : stepRules) {
      String fromExpr =
          ruleToFrom(
              rule,
              decl,
              isRecursive ? relName : null,
              isRecursive ? allVar : null,
              isRecursive ? newVar : null,
              declarationMap);
      stepExprs.add(fromExpr);
    }

    if (stepExprs.isEmpty()) {
      morel.append("        []");
    } else if (stepExprs.size() == 1) {
      morel.append("        ").append(stepExprs.get(0));
    } else {
      for (int i = 0; i < stepExprs.size(); i++) {
        if (i > 0) {
          morel.append("\n").append("        @ ");
        } else {
          morel.append("        ");
        }
        morel.append("(").append(stepExprs.get(i)).append(")");
      }
    }
    morel.append(")\n");
  }

  /**
   * Translates a single Datalog rule to a {@code from ... yield ...}
   * expression.
   *
   * <p>Each positive body atom becomes a scan source in the {@code from}
   * clause. Variables that appear in multiple atoms use fresh names with
   * equality constraints for joins. Negated atoms become {@code not (... elem
   * ...)} constraints. The head terms determine the {@code yield} expression.
   */
  private static String ruleToFrom(
      Rule rule,
      Declaration headDecl,
      String recursiveRelName,
      String allRelVar,
      String newRelVar,
      Map<String, Declaration> declarationMap) {
    StringBuilder sb = new StringBuilder();
    List<String> fromSources = new ArrayList<>();
    List<String> whereConstraints = new ArrayList<>();

    // Track seen variables: Datalog variable name -> Morel variable name
    Map<String, String> varMap = new LinkedHashMap<>();
    Set<String> usedNames = new HashSet<>();
    int freshCounter = 0;
    boolean usedNewRel = false;

    for (BodyAtom bodyAtom : rule.body) {
      if (bodyAtom instanceof Comparison) {
        Comparison comp = (Comparison) bodyAtom;
        String left = termToExpr(comp.left, varMap);
        String right = termToExpr(comp.right, varMap);
        whereConstraints.add(left + " " + compOpToMorel(comp.op) + " " + right);
        continue;
      }

      Atom atom = bodyAtom.atom;

      if (bodyAtom.negated) {
        // Negated atom: add as where constraint using elem
        whereConstraints.add("not (" + buildElemExpr(atom, varMap) + ")");
        continue;
      }

      // Positive atom: determine scan source
      String sourceName;
      if (recursiveRelName != null && atom.name.equals(recursiveRelName)) {
        if (!usedNewRel) {
          sourceName = newRelVar;
          usedNewRel = true;
        } else {
          sourceName = allRelVar;
        }
      } else {
        sourceName = atom.name;
      }

      // Build pattern variables for this atom's terms
      List<String> patternVars = new ArrayList<>();
      for (int i = 0; i < atom.terms.size(); i++) {
        Term term = atom.terms.get(i);
        if (term instanceof Variable) {
          Variable var = (Variable) term;
          if (!varMap.containsKey(var.name)) {
            // New variable: use lowercase name
            String morelVar = var.name.toLowerCase();
            while (usedNames.contains(morelVar)) {
              morelVar = "v" + freshCounter++;
            }
            varMap.put(var.name, morelVar);
            usedNames.add(morelVar);
            patternVars.add(morelVar);
          } else {
            // Already seen: use fresh name, add equality constraint
            String existingName = varMap.get(var.name);
            String fresh = "v" + freshCounter++;
            while (usedNames.contains(fresh)) {
              fresh = "v" + freshCounter++;
            }
            usedNames.add(fresh);
            patternVars.add(fresh);
            whereConstraints.add(existingName + " = " + fresh);
          }
        } else if (term instanceof Constant) {
          // Constant in body atom: fresh variable + equality constraint
          String fresh = "v" + freshCounter++;
          while (usedNames.contains(fresh)) {
            fresh = "v" + freshCounter++;
          }
          usedNames.add(fresh);
          patternVars.add(fresh);
          whereConstraints.add(fresh + " = " + termToMorel(term));
        } else if (term instanceof ArithmeticExpr) {
          // Arithmetic in body atom: fresh variable + equality constraint
          String fresh = "v" + freshCounter++;
          while (usedNames.contains(fresh)) {
            fresh = "v" + freshCounter++;
          }
          usedNames.add(fresh);
          patternVars.add(fresh);
          whereConstraints.add(fresh + " = " + termToExpr(term, varMap));
        }
      }

      // Build the from source pattern
      String pattern;
      if (patternVars.isEmpty()) {
        pattern = "()";
      } else if (patternVars.size() == 1) {
        pattern = patternVars.get(0);
      } else {
        pattern = "(" + String.join(", ", patternVars) + ")";
      }
      fromSources.add(pattern + " in " + sourceName);
    }

    // Build yield expression from head terms
    String yieldExpr;
    if (headDecl.arity() == 0) {
      yieldExpr = "()";
    } else {
      List<String> yieldParts = new ArrayList<>();
      for (Term term : rule.head.terms) {
        yieldParts.add(termToExpr(term, varMap));
      }
      if (yieldParts.size() == 1) {
        yieldExpr = yieldParts.get(0);
      } else {
        yieldExpr = "(" + String.join(", ", yieldParts) + ")";
      }
    }

    // Assemble from expression
    sb.append("from ");
    sb.append(String.join(", ", fromSources));
    if (!whereConstraints.isEmpty()) {
      sb.append(" where ");
      sb.append(String.join(" andalso ", whereConstraints));
    }
    sb.append(" yield ");
    sb.append(yieldExpr);

    return sb.toString();
  }

  /** Builds an {@code elem} expression for a negated atom constraint. */
  private static String buildElemExpr(Atom atom, Map<String, String> varMap) {
    List<String> parts = new ArrayList<>();
    for (Term term : atom.terms) {
      parts.add(termToExpr(term, varMap));
    }
    if (parts.size() == 1) {
      return parts.get(0) + " elem " + atom.name;
    } else {
      return "(" + String.join(", ", parts) + ") elem " + atom.name;
    }
  }

  /** Returns whether a rule is recursive (references its own head relation). */
  private static boolean isRecursive(Rule rule, String relName) {
    for (BodyAtom bodyAtom : rule.body) {
      if (!(bodyAtom instanceof Comparison)
          && bodyAtom.atom.name.equals(relName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether a rule is a simple passthrough, i.e. the head terms are
   * exactly the same variables as the single body atom's terms.
   *
   * <p>For example, {@code path(X,Y) :- edge(X,Y)} is a passthrough.
   */
  private static boolean isPassthrough(Rule rule) {
    if (rule.body.size() != 1) {
      return false;
    }
    BodyAtom bodyAtom = rule.body.get(0);
    if (bodyAtom instanceof Comparison || bodyAtom.negated) {
      return false;
    }
    Atom atom = bodyAtom.atom;
    if (atom.terms.size() != rule.head.terms.size()) {
      return false;
    }
    for (int i = 0; i < rule.head.terms.size(); i++) {
      if (!(rule.head.terms.get(i) instanceof Variable)
          || !(atom.terms.get(i) instanceof Variable)) {
        return false;
      }
      if (!((Variable) rule.head.terms.get(i))
          .name.equals(((Variable) atom.terms.get(i)).name)) {
        return false;
      }
    }
    return true;
  }

  /** Capitalizes the first letter of a string. */
  private static String capitalize(String s) {
    if (s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /** Converts a Datalog term to a Morel expression using the variable map. */
  private static String termToExpr(Term term, Map<String, String> varMap) {
    if (term instanceof Variable) {
      Variable var = (Variable) term;
      String mapped = varMap.get(var.name);
      return mapped != null ? mapped : var.name.toLowerCase();
    } else if (term instanceof Constant) {
      return termToMorel(term);
    } else if (term instanceof ArithmeticExpr) {
      ArithmeticExpr expr = (ArithmeticExpr) term;
      String left = termToExprParens(expr.left, expr.op, true, varMap);
      String right = termToExprParens(expr.right, expr.op, false, varMap);
      return left + " " + expr.op.symbol + " " + right;
    }
    throw new IllegalArgumentException("Unknown term type: " + term);
  }

  /**
   * Converts a sub-term to an expression, adding parentheses if needed for
   * correct precedence.
   */
  private static String termToExprParens(
      Term term, ArithOp parentOp, boolean isLeft, Map<String, String> varMap) {
    String ref = termToExpr(term, varMap);
    if (term instanceof ArithmeticExpr) {
      ArithmeticExpr subExpr = (ArithmeticExpr) term;
      // Additive inside multiplicative needs parens
      if (isMultiplicative(parentOp) && !isMultiplicative(subExpr.op)) {
        return "(" + ref + ")";
      }
      // Right operand of minus or divide needs parens if additive
      if (!isLeft
          && (parentOp == ArithOp.MINUS || parentOp == ArithOp.DIVIDE)
          && !isMultiplicative(subExpr.op)) {
        return "(" + ref + ")";
      }
    }
    return ref;
  }

  /** Returns whether an operator is multiplicative (higher precedence). */
  private static boolean isMultiplicative(ArithOp op) {
    return op == ArithOp.TIMES || op == ArithOp.DIVIDE;
  }

  /** Converts comparison operator to Morel syntax. */
  private static String compOpToMorel(CompOp op) {
    switch (op) {
      case EQ:
        return "=";
      case NE:
        return "<>";
      case LT:
        return "<";
      case LE:
        return "<=";
      case GT:
        return ">";
      case GE:
        return ">=";
      default:
        throw new IllegalArgumentException("Unknown operator: " + op);
    }
  }

  /** Translates facts to a Morel list literal. */
  private static String translateFactsToList(
      Declaration decl, List<Fact> facts) {
    StringBuilder sb = new StringBuilder("[");

    for (int i = 0; i < facts.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      Fact fact = facts.get(i);

      if (decl.arity() == 1) {
        sb.append(termToMorel(fact.atom.terms.get(0)));
      } else {
        sb.append("(");
        for (int j = 0; j < fact.atom.terms.size(); j++) {
          if (j > 0) {
            sb.append(", ");
          }
          sb.append(termToMorel(fact.atom.terms.get(j)));
        }
        sb.append(")");
      }
    }

    sb.append("]");
    return sb.toString();
  }

  /** Converts a Datalog term to Morel syntax. */
  private static String termToMorel(Term term) {
    if (term instanceof Constant) {
      Constant constant = (Constant) term;
      Object value = constant.value;
      if (value instanceof Number) {
        return value.toString();
      } else if (value instanceof String) {
        return "\""
            + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"";
      }
    } else if (term instanceof Variable) {
      Variable var = (Variable) term;
      return "\"" + var.name + "\"";
    }
    throw new IllegalArgumentException("Cannot convert term to Morel: " + term);
  }
}

// End DatalogTranslator.java
