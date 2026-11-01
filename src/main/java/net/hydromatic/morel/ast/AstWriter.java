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

import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedLong;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.util.Lindig;
import net.hydromatic.morel.util.Lindig.Doc;
import net.hydromatic.morel.util.Pair;

/**
 * Context for writing an AST out as a string.
 *
 * <p>Builds a {@link Doc} rather than a string, so that the layout can be
 * chosen to fit a width. A run of characters with no break in it accumulates in
 * {@code pending} and becomes one {@code text} when a break arrives, which
 * keeps the document small and lets the hundred-odd {@code unparse} methods go
 * on appending characters as they always did.
 *
 * <p>Until a break point is offered -- a {@link Lindig#group} around something
 * that may be laid out either way -- the document is a flat concatenation, and
 * renders the same at every width.
 */
public class AstWriter {
  /**
   * The width a plan is laid out within where the session does not say -- a
   * test, an assertion message. Matches the default of the {@code lineWidth}
   * property, which is what says it everywhere else.
   */
  public static final int DEFAULT_WIDTH = 79;

  /** Characters written since the last break. */
  private final StringBuilder pending = new StringBuilder();

  private final List<Doc> docs = new ArrayList<>();
  private final List<Frame> stack = new ArrayList<>();
  private final boolean parenthesize;

  /**
   * Whether nothing but spaces has been written since the last line break.
   * Tracked as it is written, because a document does not have a column until
   * it is rendered.
   */
  private boolean lineStart = true;

  /** The suffix each pattern prints under; see {@link #suffix}. */
  private final Map<Pair<String, Integer>, Integer> suffixes = new HashMap<>();

  /** How many patterns of each name this writer has met. */
  private final Map<String, Integer> nextSuffix = new HashMap<>();

  /**
   * The name each node's pattern prints under, assigned when the node that
   * binds it prints; see {@link #bindIds}.
   */
  private final Map<Core.IdPat, String> idNames = new HashMap<>();

  /** The names of the ids in scope where the writer is now. */
  private final List<String> visibleIds = new ArrayList<>();

  public AstWriter() {
    this(false);
  }

  /**
   * Creates a writer that wraps every operator application in parentheses,
   * which makes an expression's structure explicit.
   */
  public AstWriter(boolean parenthesize) {
    this.parenthesize = parenthesize;
  }

  /** Moves the characters written so far into the document. */
  private void flush() {
    if (pending.length() > 0) {
      docs.add(Lindig.text(pending.toString()));
      pending.setLength(0);
    }
  }

  /**
   * The width to lay the document out within.
   *
   * <p>Unbounded unless a subclass says otherwise, so that a plain writer --
   * the one behind {@code toString}, an error message, a test matcher --
   * produces the one layout a document with no break point has. Plan text is
   * the thing with a width.
   */
  protected int width() {
    return Integer.MAX_VALUE;
  }

  /**
   * Starts a region that may be laid out on one line or broken.
   *
   * <p>Breaks offered inside it (by {@link #softBreak}) are taken only if what
   * the region holds does not fit, and a broken line is indented {@code indent}
   * from where the region began.
   */
  public AstWriter startGroup(int indent) {
    return start(indent, true);
  }

  /**
   * Starts a region that is indented but not grouped.
   *
   * <p>A group decides for itself whether to break; a nest only says where a
   * break lands. Use this where several breaks must be taken together but at
   * different indents -- the `let`, `in` and `end` of a `let` are one decision,
   * and the two parts they enclose are indented.
   */
  public AstWriter startNest(int indent) {
    return start(indent, false);
  }

  private AstWriter start(int indent, boolean group) {
    flush();
    stack.add(new Frame(docs.size(), indent, group));
    return this;
  }

  /** Ends the region that {@link #startGroup} began. */
  public AstWriter endGroup() {
    return end();
  }

  /** Ends the region that {@link #startNest} began. */
  public AstWriter endNest() {
    return end();
  }

  private AstWriter end() {
    flush();
    final Frame frame = stack.remove(stack.size() - 1);
    final List<Doc> inner =
        new ArrayList<>(docs.subList(frame.start, docs.size()));
    docs.subList(frame.start, docs.size()).clear();
    final Doc doc = Lindig.nest(frame.indent, Lindig.hcat(inner));
    docs.add(frame.group ? Lindig.group(doc) : doc);
    return this;
  }

  /**
   * Offers a break: a space if the enclosing group fits on one line, a line
   * break and the group's indent if it does not.
   */
  public AstWriter softBreak() {
    flush();
    docs.add(Lindig.LINE);
    return this;
  }

  /** An open {@link #startGroup} region. */
  private static class Frame {
    final int start;
    final int indent;
    final boolean group;

    Frame(int start, int indent, boolean group) {
      this.start = start;
      this.indent = indent;
      this.group = group;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returns the ML source code generated by this writer.
   */
  @Override
  public String toString() {
    flush();
    return Lindig.render(width(), Lindig.hcat(docs));
  }

  /**
   * Returns whether a relational node should print the collection type of every
   * line, as {@code Sys.planEx} prints it.
   */
  public boolean withTypes() {
    return false;
  }

  /**
   * The longest type moniker that a plan prints in full. A longer one is
   * replaced by a reference, {@code t$0}, and printed once in the legend.
   *
   * <p>A character count rather than a rule about the type's shape, because
   * this implementation, morel-rust and morel-go print the same plan text, so
   * they must agree on it, and they already agree on the moniker's text. It
   * sits above {@code int option list} (15) and {@code {a:int, b:int} list}
   * (19), and below a three-field record (41).
   */
  public static final int MAX_TYPE_LENGTH = 24;

  /**
   * Returns whether a relation is broken out onto lines of its own, as {@code
   * Sys.planEx} prints it, rather than nested in an expression like any other
   * application.
   */
  public boolean treeMode() {
    return false;
  }

  /**
   * Appends a reference to a node's pattern: an id, such as {@code $e} or
   * {@code $0}, or {@code $ordinal}.
   *
   * <p>An id prints under the name its node gave it when the node printed; see
   * {@link #bindIds}. An id whose node has not printed, such as a path a
   * builder returns, prints under the name the node would give it.
   */
  public AstWriter idRef(Core.IdPat pat) {
    return appendRaw(idName(pat));
  }

  /** Returns the name under which a node's pattern prints. */
  protected String idName(Core.IdPat pat) {
    final String name = idNames.get(pat);
    if (name != null) {
      return name;
    }
    if (pat.name.equals("$ordinal")) {
      return pat.name;
    }
    return baseName(pat);
  }

  /** Returns the name an id prints under, before it is numbered. */
  private String baseName(Core.IdPat pat) {
    final String idName = Core.NamedPat.idName(pat.name);
    return idName == null ? "r" : renameGenerated(idName);
  }

  /**
   * Returns the name under which a generated binder's name prints. This writer
   * prints it as it is; a writer that renumbers binders renumbers it.
   */
  protected String renameGenerated(String name) {
    return name;
  }

  /**
   * Gives each pattern a node binds the name it prints under, and returns the
   * names.
   *
   * <p>An id named after a binder prints as the binder, {@code e} for {@code
   * $e}; any other id prints as {@code r}. An id is in scope in its node's
   * expressions, and a join's left id in the join's right input as well, so a
   * name is numbered -- {@code e_1} -- only if an id of that name is already in
   * scope, the node binds two, or an ordinary variable of that name occurs
   * where the id is in scope, as {@code r} does in {@code yield x + y + r}. The
   * names are a function of the tree, not of the ordinals the compiler happened
   * to give its patterns.
   */
  private List<String> bindIds(Core.Rel rel) {
    final List<String> names = new ArrayList<>();
    if (rel.patterns().isEmpty()) {
      return names;
    }
    final Set<String> varNames = varNamesInScope(rel);
    for (Core.IdPat pat : rel.patterns()) {
      final String name;
      if (pat.name.equals("$ordinal")) {
        name = pat.name;
      } else {
        final String base = baseName(pat);
        String candidate = base;
        for (int i = 1;
            visibleIds.contains(candidate)
                || names.contains(candidate)
                || varNames.contains(candidate);
            i++) {
          candidate = base + "_" + i;
        }
        name = candidate;
      }
      idNames.put(pat, name);
      names.add(name);
    }
    return names;
  }

  /**
   * Returns the names of the ordinary variables that occur where a node's ids
   * are in scope: in its own expressions, and in a join's right input.
   */
  private static Set<String> varNamesInScope(Core.Rel rel) {
    // A node's own expressions are what its subtree mentions and its inputs
    // do not, so count each name's occurrences and subtract the inputs'.
    final Map<String, Integer> counts = varCounts(rel);
    final List<Core.Exp> inputs = rel.inputs();
    for (int i = 0; i < inputs.size(); i++) {
      if (rel instanceof Core.Join && i == 1) {
        continue;
      }
      varCounts(inputs.get(i))
          .forEach((name, count) -> counts.merge(name, -count, Integer::sum));
    }
    counts.values().removeIf(count -> count <= 0);
    return counts.keySet();
  }

  /** Counts the occurrences of each ordinary variable in a tree. */
  private static Map<String, Integer> varCounts(Core.Exp exp) {
    final Map<String, Integer> counts = new HashMap<>();
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.name.charAt(0) != '$') {
              counts.merge(id.idPat.name, 1, Integer::sum);
            }
          }
        });
    return counts;
  }

  /**
   * Appends a relational node's plan text: its own line, then its inputs,
   * indented two deeper, each on lines of its own.
   */
  public AstWriter describe(Core.Rel rel, int indent) {
    final int scope = visibleIds.size();
    final List<String> names = bindIds(rel);
    describeLine(rel, names, indent);
    final List<Core.Exp> inputs = rel.inputs();
    for (int i = 0; i < inputs.size(); i++) {
      if (rel instanceof Core.Join && i == 1) {
        // The right input is evaluated once per left element, and may read
        // it.
        visibleIds.add(idName(((Core.Join) rel).leftId));
      }
      describeInput(inputs.get(i), indent + 2);
      truncate(visibleIds, scope);
    }
    return this;
  }

  private static void truncate(List<?> list, int size) {
    list.subList(size, list.size()).clear();
  }

  /**
   * Appends a node's own line: its operator, its arguments, and if {@link
   * #withTypes} its collection type.
   */
  private void describeLine(Core.Rel rel, List<String> names, int indent) {
    indent(indent);
    // The node's own line, which wraps four from the node where it does not
    // fit. Its inputs are two, so a continuation cannot be mistaken for a
    // child, and the run of continuations ends at the first line that is
    // not four deeper.
    //
    // Only in tree mode. Elsewhere a nested node still prints in place, and
    // its own line breaks would fall inside this one's indentation.
    final boolean group = treeMode();
    if (group) {
      startGroup(indent + 4);
    }
    append(rel.op.lowerName);
    // The patterns the node binds, which are in scope in its arguments.
    final int scope = visibleIds.size();
    for (String name : names) {
      append(" ").appendRaw(name);
      visibleIds.add(name);
    }
    rel.describeArgs(this);
    truncate(visibleIds, scope);
    if (withTypes()) {
      append(" : ").append(typeRef(rel.type.moniker()));
    }
    if (group) {
      endGroup();
    }
    append("\n");
  }

  /**
   * Appends an input of a node: a node's lines, or a leaf on a line of its own.
   * Also how an expression that is not a node prints as a plan.
   */
  public AstWriter describeInput(Core.Exp input, int indent) {
    if (input instanceof Core.Rel) {
      return describe((Core.Rel) input, indent);
    }
    indent(indent);
    // A leaf is a node line too, and wraps the same way: four from the leaf,
    // never back to a column where it would read as a node of its own.
    final boolean group = treeMode();
    if (group) {
      startGroup(indent + 4);
    }
    append(input, 0, 0);
    if (withTypes()) {
      append(" : ").append(typeRef(input.type.moniker()));
    }
    if (group) {
      endGroup();
    }
    return append("\n");
  }

  private void indent(int indent) {
    for (int i = 0; i < indent; i++) {
      append(" ");
    }
  }

  /** Appends a node argument in brackets, for {@link Core.Rel#describeArgs}. */
  public AstWriter arg(Object arg) {
    append(" [");
    if (arg instanceof AstNode) {
      append((AstNode) arg, 0, 0);
    } else {
      append(String.valueOf(arg));
    }
    return append("]");
  }

  /** Appends named node arguments in brackets, {@code [k = v, ...]}. */
  public AstWriter args(Map<String, ? extends AstNode> map) {
    append(" [");
    int i = 0;
    for (Map.Entry<String, ? extends AstNode> entry : map.entrySet()) {
      if (i++ > 0) {
        append(", ");
      }
      append(entry.getKey()).append(" = ").append(entry.getValue(), 0, 0);
    }
    return append("]");
  }

  /**
   * Registers a relation that cannot print where it stands, and returns the
   * reference that prints instead -- {@code r$0}, {@code r$1}, and so on.
   *
   * <p>A relational operator is the first non-whitespace on its line. A
   * relation reached from inside an expression is not, so it is broken out and
   * defined below the tree.
   */
  public String relRef(Core.Rel rel) {
    throw new UnsupportedOperationException("not in tree mode");
  }

  /**
   * Returns the reference for the {@code i}th broken-out relation, with the
   * variables it reads from outside itself -- {@code r$0[v$0, v$1]}. The same
   * text stands at the reference and at the definition, so that a fragment that
   * carries a variable into a fragment nested inside it says so.
   */
  public String relHeader(int i) {
    throw new UnsupportedOperationException("not in tree mode");
  }

  /** Returns how many relations {@link #relRef} has broken out. */
  public int relDefCount() {
    return 0;
  }

  /** Returns the {@code i}th relation that {@link #relRef} broke out. */
  public Core.Rel relDef(int i) {
    throw new UnsupportedOperationException("not in tree mode");
  }

  /**
   * Returns whether nothing but spaces has been written since the last line
   * break, so that what comes next is the first non-whitespace on its line.
   */
  public boolean atLineStart() {
    return lineStart;
  }

  /** Appends an identifier, quoting it if it needs quoting. */
  private void appendQuoted(String name) {
    final StringBuilder b = new StringBuilder();
    Parsers.appendId(b, name);
    raw(b.toString());
  }

  /**
   * Returns how a type is written where a plan line names it: the moniker
   * itself if it is short, otherwise a reference such as {@code t$0} that
   * {@link #typeLegend} expands.
   *
   * <p>Whether a type is converted into a reference is based on a simple
   * heuristic: whether the moniker is longer than {@link #MAX_TYPE_LENGTH}.
   *
   * <p>A better rule would allow monikers to share components with other
   * monikers. For example,
   *
   * <pre>
   *   t$0 {a:int, b:bool} list
   *   t$1 {a:int, b:bool} option bag
   * </pre>
   *
   * <p>could be shortened by introducing an intermediate type {@code t$2}:
   *
   * <pre>
   *   t$0 t$2 list
   *   t$1 t$2 option bag
   *   t$2 {a:int, b:bool}
   * </pre>
   *
   * <p>Choosing the set of common types that minimizes the total length of the
   * {@link #typeLegend()} is the <a
   * href="https://en.wikipedia.org/wiki/Smallest_grammar_problem">Smallest
   * grammar problem</a>, which is NP-complete.
   */
  public String typeRef(String moniker) {
    return moniker;
  }

  /**
   * Returns the legend for the references {@link #typeRef} has handed out: one
   * line per distinct type, in the order it was first encountered, or the empty
   * string if there are none.
   */
  public String typeLegend() {
    return "";
  }

  /** Appends a string to the output. */
  public AstWriter append(String s) {
    int i;
    while ((i = s.indexOf('\n')) >= 0) {
      pending.append(s, 0, i);
      flush();
      docs.add(Lindig.HARD_LINE);
      lineStart = true;
      s = s.substring(i + 1);
    }
    raw(s);
    return this;
  }

  /**
   * Appends text verbatim. Unlike {@link #append(String)}, a subclass that
   * renames what it prints does not see it; the text must not contain a line
   * break.
   */
  public AstWriter appendRaw(String s) {
    raw(s);
    return this;
  }

  /** Appends characters that contain no line break. */
  private void raw(String s) {
    pending.append(s);
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) != ' ') {
        lineStart = false;
        break;
      }
    }
  }

  /**
   * Appends a name to the output verbatim (no quoting). Use for type names,
   * type variables, and keywords that unparse themselves (e.g. {@code
   * ordinal}); use {@link #idQuoted} for variable identifiers and record
   * labels, which must be quoted if they are reserved words.
   */
  public AstWriter id(String name) {
    raw(name);
    return this;
  }

  /**
   * Appends an ordinal-qualified identifier to the output, verbatim.
   *
   * <p>Prints "v" for {@code id("v", 0)}, "v_1" for {@code id("v", 1)}, and so
   * forth.
   */
  public AstWriter id(String name, int i) {
    raw(name);
    final int j = suffix(name, i);
    if (j > 0) {
      raw("_" + j);
    }
    return this;
  }

  /**
   * Returns the suffix under which the {@code i}th pattern called {@code name}
   * prints: 0, meaning no suffix, for the first one this writer meets, 1 for
   * the next, and so on.
   *
   * <p>The ordinal a pattern carries counts every binder the compiler made, so
   * printing it would make the text depend on how many binders came before --
   * on history, not on the expression. Numbering by the order the reader meets
   * them keeps the text a function of what is printed, so that two expressions
   * that differ only in their binders' ordinals print alike.
   */
  private int suffix(String name, int i) {
    return suffixes.computeIfAbsent(
        Pair.of(name, i), k -> nextSuffix.merge(name, 1, Integer::sum) - 1);
  }

  /**
   * Appends a variable identifier or record label, quoting it with back-ticks
   * if it is a reserved word (or otherwise requires quoting), so that, for
   * example, a variable named {@code left} round-trips.
   */
  public AstWriter idQuoted(String name) {
    appendQuoted(name);
    return this;
  }

  /**
   * Appends a record selector, {@code #label}, quoting the label if it is not
   * letters, digits, underscores and primes: {@code #`*`}.
   */
  public AstWriter selector(String label) {
    raw(Parsers.appendSelector(new StringBuilder(), label).toString());
    return this;
  }

  /** Appends an ordinal-qualified variable identifier, quoting if necessary. */
  public AstWriter idQuoted(String name, int i) {
    final int j = suffix(name, i);
    if (j == 0) {
      appendQuoted(name);
    } else {
      // "name_i" is never a reserved word, so it does not need quoting.
      raw(name + "_" + j);
    }
    return this;
  }

  /**
   * Appends a function application. If the function is a named operator and the
   * argument has the shape the operator is written with -- a pair for an infix
   * operator, anything for a prefix operator -- the application is written in
   * operator syntax.
   */
  public AstWriter apply(int left, AstNode fn, AstNode arg, int right) {
    // TODO: obsolete Core.Id for these purposes. The operator should
    // be a function literal, and we would use a reverse mapping to
    // figure out which built-in operator it implements, and whether it
    // is infix (e.g. "+") or in a namespace (e.g. "#translate String")
    final Op op =
        fn instanceof Ast.Id
            ? Op.BY_OP_NAME.get(((Ast.Id) fn).name)
            : fn instanceof Core.Id
                ? Op.BY_OP_NAME.get(((Core.Id) fn).idPat.name)
                : null;
    if (op != null) {
      if (op.assoc == Op.Assoc.PREFIX) {
        return prefix(left, op, arg, right);
      }
      // An infix operator is written between its operands only if it has
      // two; used as a value, as in "op + p", it is written as a call.
      final List<? extends AstNode> args =
          arg instanceof Ast.Tuple
              ? ((Ast.Tuple) arg).args
              : arg instanceof Core.Tuple ? ((Core.Tuple) arg).args : null;
      if (args != null && args.size() == 2) {
        return infix(left, args.get(0), op, args.get(1), right);
      }
    }
    return infix(left, fn, Op.APPLY, arg, right);
  }

  /** Appends a call to an infix operator. */
  public AstWriter infix(int left, AstNode a0, Op op, AstNode a1, int right) {
    final boolean p = parenthesize || left > op.left || op.right < right;
    if (p) {
      raw("(");
      left = right = 0;
    }
    // `andalso` and `orelse` chain, and a plan's conditions are mostly made
    // of them, so they are where a long line is worth breaking. Each is a
    // group of its own, so an outer one breaks before an inner one does, and
    // a condition that fits stays on its line.
    final boolean breakable = op == Op.ANDALSO || op == Op.ORELSE;
    if (breakable) {
      startGroup(0);
    }
    append(a0, left, op.left);
    if (breakable) {
      softBreak();
      append(op.padded.substring(1));
    } else {
      append(op.padded);
    }
    append(a1, op.right, right);
    if (breakable) {
      endGroup();
    }
    if (p) {
      raw(")");
    }
    return this;
  }

  /** Appends a call to an prefix operator. */
  public AstWriter prefix(int left, Op op, AstNode a, int right) {
    final boolean p = parenthesize || left > op.left || op.right < right;
    if (p) {
      raw("(");
      right = 0;
    }
    append(op.padded);
    a.unparse(this, op.right, right);
    if (p) {
      raw(")");
    }
    return this;
  }

  /** Appends a call to a binary operator (e.g. "val ... = ..."). */
  public AstWriter binary(
      String left, AstNode a0, String mid, AstNode a1, int right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, right);
    return this;
  }

  /** Appends a call to a binary operator (e.g. "let ... in ... end"). */
  public AstWriter binary(
      String left, AstNode a0, String mid, AstNode a1, String right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, 0);
    append(right);
    return this;
  }

  /** Appends a parse tree node. */
  public AstWriter append(AstNode node, int left, int right) {
    final boolean p = parenthesize || node.op.wraps(left, right);
    if (p) {
      raw("(");
      left = right = 0;
    }
    node.unparse(this, left, right);
    if (p) {
      raw(")");
    }
    return this;
  }

  /** Appends a list of parse tree nodes. */
  public AstWriter appendAll(
      Iterable<? extends AstNode> nodes, int left, Op op, int right) {
    @SuppressWarnings("unchecked")
    final List<AstNode> nodeList =
        nodes instanceof List ? (List) nodes : Lists.newArrayList(nodes);
    for (int i = 0; i < nodeList.size(); i++) {
      final AstNode node = nodeList.get(i);
      final int thisLeft = i == 0 ? left : op.left;
      final int thisRight = i == nodeList.size() - 1 ? right : op.right;
      if (i > 0) {
        append(op.padded);
      }
      append(node, thisLeft, thisRight);
    }
    return this;
  }

  /** Appends a list of parse tree nodes separated by {@code sep}. */
  public AstWriter appendAll(Iterable<? extends AstNode> list, String sep) {
    return appendAll(list, "", sep, "");
  }

  /**
   * Appends a list of parse tree nodes separated by {@code sep}, and also with
   * prefix and suffix: {@code start node0 sep node1 ... sep nodeN end}.
   */
  public AstWriter appendAll(
      Iterable<? extends AstNode> list, String start, String sep, String end) {
    return appendAll(list, start, sep, end, "");
  }

  /**
   * Appends a list of parse tree nodes separated by {@code sep}, and also with
   * prefix and suffix: {@code start node0 sep node1 ... sep nodeN end}.
   */
  public AstWriter appendAll(
      Iterable<? extends AstNode> list,
      String start,
      String sep,
      String end,
      String empty) {
    String s = start;
    int i = 0;
    for (AstNode node : list) {
      ++i;
      append(s);
      s = sep;
      append(node, 0, 0);
    }
    if (i == 0 && empty != null) {
      append(empty);
    } else {
      append(end);
    }
    return this;
  }

  public AstWriter appendLiteral(Comparable value) {
    if (value instanceof String) {
      // As one piece of text, so that a newline inside the literal is part
      // of it rather than a line break the layout must take.
      raw(
          "\""
              + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"")
              + "\"");
    } else if (value instanceof UnsignedLong) {
      // A word, e.g. "0wxFF". Like Standard ML, print in hexadecimal.
      append("0wx")
          .append(
              Long.toUnsignedString(((UnsignedLong) value).longValue(), 16)
                  .toUpperCase(Locale.ROOT));
    } else if (value instanceof Character) {
      switch ((char) value) {
        case '"':
          append("#\"\\\"\"");
          break;
        case '\\':
          append("#\"\\\\\"");
          break;
        default:
          append("#\"").append(value.toString()).append("\"");
          break;
      }
    } else if (value instanceof BigDecimal) {
      BigDecimal c = (BigDecimal) value;
      if (c.compareTo(BigDecimal.ZERO) < 0) {
        append("~");
        c = c.negate();
      }
      append(c.toString().replace("+", ""));
    } else if (value instanceof BuiltIn) {
      final BuiltIn builtIn = (BuiltIn) value;
      if (builtIn == BuiltIn.BOOL_NOT) {
        // The "not" prefix operator prints unqualified, not as "#not Bool".
        append("not");
      } else if (!builtIn.structure.equals("Top")
          && !builtIn.structure.equals("$")) {
        // E.g. "#find List" for the List.find function, "#`*` Word" for the
        // Word.* operator.
        selector(builtIn.mlName).append(" ").append(builtIn.structure);
      } else {
        append(builtIn.mlName);
      }
    } else {
      append(value.toString());
    }
    return this;
  }
}

// End AstWriter.java
