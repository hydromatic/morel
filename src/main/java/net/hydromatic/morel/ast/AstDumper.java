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

import java.util.List;
import java.util.Locale;

/**
 * Produces a parenthesized S-expression dump of an {@link AstNode}.
 *
 * <p>The output is intended to make tree structure (operator precedence,
 * attribute attachment, declaration shape) easily assertable from {@code .smli}
 * scripts via {@code Sys.parseTree}. It is not intended to be re-parsable; use
 * {@link AstNode#unparse} for that.
 *
 * <p>Each non-leaf node is rendered as {@code (kind child1 child2 ...)} where
 * {@code kind} is the lowercase name of the node's {@link
 * net.hydromatic.morel.ast.Op}. Leaves (literals, identifiers, type variables)
 * are rendered atomically.
 */
public class AstDumper {
  private AstDumper() {}

  /** Returns an S-expression dump of {@code node}. */
  public static String dump(AstNode node) {
    final StringBuilder b = new StringBuilder();
    dump(b, node);
    return b.toString();
  }

  private static void dump(StringBuilder b, Object node) {
    if (node == null) {
      b.append("nil");
      return;
    }
    if (dumpLeaf(b, node)) {
      return;
    }
    if (dumpExp(b, node)) {
      return;
    }
    if (dumpType(b, node)) {
      return;
    }
    if (dumpPat(b, node)) {
      return;
    }
    if (dumpDecl(b, node)) {
      return;
    }
    // Fallback: opName plus the node's unparsed source text. This keeps the
    // dumper functional for AST shapes we haven't enumerated above; the
    // dump is still parenthesized and identifiable.
    if (node instanceof AstNode) {
      final AstNode n = (AstNode) node;
      b.append('(').append(opName(n.op)).append(' ').append(n).append(')');
      return;
    }
    b.append(node);
  }

  /**
   * Dumps simple leaf nodes (literals, ids, wildcards). Returns true if
   * matched.
   */
  private static boolean dumpLeaf(StringBuilder b, Object node) {
    if (node instanceof Ast.Literal) {
      dumpLiteral(b, (Ast.Literal) node);
      return true;
    }
    if (node instanceof Ast.LiteralPat) {
      final Ast.LiteralPat lp = (Ast.LiteralPat) node;
      b.append('(')
          .append(opName(lp.op))
          .append(' ')
          .append(lp.value)
          .append(')');
      return true;
    }
    if (node instanceof Ast.Id) {
      b.append("(id ").append(((Ast.Id) node).name).append(')');
      return true;
    }
    if (node instanceof Ast.IdPat) {
      b.append("(idPat ").append(((Ast.IdPat) node).name).append(')');
      return true;
    }
    if (node instanceof Ast.WildcardPat) {
      b.append("wildcard");
      return true;
    }
    if (node instanceof Ast.TyVar) {
      b.append("(tyVar ").append(((Ast.TyVar) node).name).append(')');
      return true;
    }
    return false;
  }

  /** Dumps expression nodes. Returns true if matched. */
  private static boolean dumpExp(StringBuilder b, Object node) {
    if (node instanceof Ast.InfixCall) {
      final Ast.InfixCall c = (Ast.InfixCall) node;
      b.append('(').append(opName(c.op)).append(' ');
      dump(b, c.a0);
      b.append(' ');
      dump(b, c.a1);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.PrefixCall) {
      final Ast.PrefixCall c = (Ast.PrefixCall) node;
      b.append('(').append(opName(c.op)).append(' ');
      dump(b, c.a);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.Apply) {
      final Ast.Apply a = (Ast.Apply) node;
      b.append("(apply ");
      dump(b, a.fn);
      b.append(' ');
      dump(b, a.arg);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.AnnotatedExp) {
      final Ast.AnnotatedExp a = (Ast.AnnotatedExp) node;
      b.append("(annotatedExp ");
      dump(b, a.exp);
      b.append(' ');
      dump(b, a.type);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.Tuple) {
      sexp(b, "tuple", ((Ast.Tuple) node).args);
      return true;
    }
    if (node instanceof Ast.ListExp) {
      sexp(b, "list", ((Ast.ListExp) node).args);
      return true;
    }
    if (node instanceof Ast.Record) {
      dumpRecord(b, (Ast.Record) node);
      return true;
    }
    if (node instanceof Ast.If) {
      final Ast.If i = (Ast.If) node;
      b.append("(if ");
      dump(b, i.condition);
      b.append(' ');
      dump(b, i.ifTrue);
      b.append(' ');
      dump(b, i.ifFalse);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.AttributedExp) {
      final Ast.AttributedExp a = (Ast.AttributedExp) node;
      b.append("(attributedExp ");
      dump(b, a.exp);
      for (Ast.Attribute attr : a.attributes) {
        b.append(' ');
        dump(b, attr);
      }
      b.append(')');
      return true;
    }
    if (node instanceof Ast.Attribute) {
      final Ast.Attribute a = (Ast.Attribute) node;
      final String marker;
      switch (a.kind) {
        case EXP:
          marker = "@";
          break;
        case DECL:
          marker = "@@";
          break;
        case FLOATING:
        default:
          marker = "@@@";
          break;
      }
      b.append("(attribute ").append(marker).append(a.name);
      if (a.payload != null) {
        b.append(' ');
        dump(b, a.payload);
      } else if (a.typePayload != null) {
        b.append(" : ");
        dump(b, a.typePayload);
      }
      b.append(')');
      return true;
    }
    return dumpExp2(b, node);
  }

  private static boolean dumpExp2(StringBuilder b, Object node) {
    if (node instanceof Ast.Let) {
      final Ast.Let l = (Ast.Let) node;
      b.append("(let");
      for (Ast.Decl d : l.decls) {
        b.append(' ');
        dump(b, d);
      }
      b.append(' ');
      dump(b, l.exp);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.Fn) {
      sexp(b, "fn", ((Ast.Fn) node).matchList);
      return true;
    }
    if (node instanceof Ast.Match) {
      final Ast.Match m = (Ast.Match) node;
      b.append("(match ");
      dump(b, m.pat);
      b.append(' ');
      dump(b, m.exp);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.Case) {
      final Ast.Case c = (Ast.Case) node;
      b.append("(case ");
      dump(b, c.exp);
      for (Ast.Match m : c.matchList) {
        b.append(' ');
        dump(b, m);
      }
      b.append(')');
      return true;
    }
    return false;
  }

  /** Dumps type nodes. Returns true if matched. */
  private static boolean dumpType(StringBuilder b, Object node) {
    if (node instanceof Ast.NamedType) {
      final Ast.NamedType t = (Ast.NamedType) node;
      b.append("(named ").append(t.name);
      for (Ast.Type arg : t.types) {
        b.append(' ');
        dump(b, arg);
      }
      b.append(')');
      return true;
    }
    if (node instanceof Ast.TupleType) {
      sexp(b, "tupleType", ((Ast.TupleType) node).types);
      return true;
    }
    if (node instanceof Ast.FunctionType) {
      final Ast.FunctionType t = (Ast.FunctionType) node;
      b.append("(fnType ");
      dump(b, t.paramType);
      b.append(' ');
      dump(b, t.resultType);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.AttributedType) {
      final Ast.AttributedType t = (Ast.AttributedType) node;
      b.append("(attributedType ");
      dump(b, t.type);
      for (Ast.Attribute a : t.attributes) {
        b.append(' ');
        dump(b, a);
      }
      b.append(')');
      return true;
    }
    return false;
  }

  /**
   * Dumps pattern nodes (compound ones; leaves go through {@link #dumpLeaf}).
   * Returns true if matched.
   */
  private static boolean dumpPat(StringBuilder b, Object node) {
    if (node instanceof Ast.TuplePat) {
      sexp(b, "tuplePat", ((Ast.TuplePat) node).args);
      return true;
    }
    if (node instanceof Ast.AnnotatedPat) {
      final Ast.AnnotatedPat ap = (Ast.AnnotatedPat) node;
      b.append("(annotatedPat ");
      dump(b, ap.pat);
      b.append(' ');
      dump(b, ap.type);
      b.append(')');
      return true;
    }
    return false;
  }

  /** Dumps declaration nodes. Returns true if matched. */
  private static boolean dumpDecl(StringBuilder b, Object node) {
    if (node instanceof Ast.ValDecl) {
      final Ast.ValDecl d = (Ast.ValDecl) node;
      b.append("(val");
      if (d.rec) {
        b.append(" rec");
      }
      for (Ast.ValBind vb : d.valBinds) {
        b.append(' ');
        dump(b, vb);
      }
      b.append(')');
      return true;
    }
    if (node instanceof Ast.ValBind) {
      final Ast.ValBind vb = (Ast.ValBind) node;
      b.append("(valBind ");
      dump(b, vb.pat);
      b.append(' ');
      dump(b, vb.exp);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.FunDecl) {
      sexp(b, "fun", ((Ast.FunDecl) node).funBinds);
      return true;
    }
    if (node instanceof Ast.FunBind) {
      sexp(b, "funBind", ((Ast.FunBind) node).matchList);
      return true;
    }
    if (node instanceof Ast.FunMatch) {
      dumpFunMatch(b, (Ast.FunMatch) node);
      return true;
    }
    if (node instanceof Ast.AttributedDecl) {
      final Ast.AttributedDecl d = (Ast.AttributedDecl) node;
      b.append("(attributedDecl ");
      dump(b, d.decl);
      for (Ast.Attribute a : d.attributes) {
        b.append(' ');
        dump(b, a);
      }
      b.append(')');
      return true;
    }
    if (node instanceof Ast.FloatingAttrDecl) {
      final Ast.FloatingAttrDecl d = (Ast.FloatingAttrDecl) node;
      b.append("(floatingAttrDecl ");
      dump(b, d.attribute);
      b.append(')');
      return true;
    }
    if (node instanceof Ast.AttributedSpec) {
      final Ast.AttributedSpec s = (Ast.AttributedSpec) node;
      b.append("(attributedSpec ");
      dump(b, s.spec);
      for (Ast.Attribute a : s.attributes) {
        b.append(' ');
        dump(b, a);
      }
      b.append(')');
      return true;
    }
    if (node instanceof Ast.FloatingAttrSpec) {
      final Ast.FloatingAttrSpec s = (Ast.FloatingAttrSpec) node;
      b.append("(floatingAttrSpec ");
      dump(b, s.attribute);
      b.append(')');
      return true;
    }
    return false;
  }

  private static void dumpFunMatch(StringBuilder b, Ast.FunMatch fm) {
    b.append("(funMatch ").append(fm.name);
    for (Ast.Pat p : fm.patList) {
      b.append(' ');
      dump(b, p);
    }
    if (fm.returnType != null) {
      b.append(' ');
      dump(b, fm.returnType);
    }
    b.append(' ');
    dump(b, fm.exp);
    b.append(')');
  }

  private static void dumpRecord(StringBuilder b, Ast.Record r) {
    b.append("(record");
    r.args.forEach(
        (id, exp) -> {
          b.append(' ').append('(').append(id.name).append(' ');
          dump(b, exp);
          b.append(')');
        });
    b.append(')');
  }

  /** Renders {@code (kind c1 c2 ...)}. */
  private static void sexp(StringBuilder b, String kind, List<?> children) {
    b.append('(').append(kind);
    for (Object c : children) {
      b.append(' ');
      dump(b, c);
    }
    b.append(')');
  }

  private static void dumpLiteral(StringBuilder b, Ast.Literal lit) {
    b.append('(').append(opName(lit.op)).append(' ');
    final Object v = lit.value;
    if (v instanceof String) {
      b.append('"').append(v).append('"');
    } else if (v instanceof Character) {
      b.append('#').append('"').append(v).append('"');
    } else {
      b.append(v);
    }
    b.append(')');
  }

  private static String opName(Op op) {
    return op.name().toLowerCase(Locale.ROOT);
  }
}

// End AstDumper.java
