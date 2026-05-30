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
package net.hydromatic.morel.util;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Syntax-highlights Morel source code as HTML.
 *
 * <p>Token classes produced for input code:
 *
 * <ul>
 *   <li>{@code kw} &mdash; SML and Morel reserved words;
 *   <li>{@code str} &mdash; double-quoted string literals;
 *   <li>{@code cmt} &mdash; SML comments ({@code (*} &hellip; {@code *)});
 *   <li>{@code num} &mdash; numeric literals;
 *   <li>{@code ctor} &mdash; type variables ({@code 'a}, {@code 'alpha},
 *       &hellip;) and structure names (identifier before {@code .});
 *   <li>{@code op} &mdash; operators ({@code ::}, {@code ->}, {@code :=},
 *       {@code +}, {@code -}, {@code *}, {@code /}, {@code :}).
 * </ul>
 *
 * <p>Plain identifiers and punctuation are emitted as undecorated text.
 *
 * <p>For output text (REPL responses), {@link #highlightOutput} returns the
 * text HTML-escaped but otherwise undecorated.
 *
 * <p>Morel-specific keywords (such as {@code from}, {@code yield}, {@code
 * elem}, {@code exists}) are given the {@code kw} class. Adding a new Morel
 * keyword requires only adding it to {@link #MOREL_KEYWORDS}.
 *
 * <p>The {@link #DEFAULT} instance highlights SML and Morel keywords. To add
 * further keywords (e.g. DML keywords from {@link #DML_KEYWORDS}), call {@link
 * #amendKeywords(Function)}.
 */
public class MorelHighlighter {

  /** SML reserved words. */
  private static final Set<String> SML_KEYWORDS =
      ImmutableSet.of(
          "abstype",
          "and",
          "andalso",
          "as",
          "case",
          "datatype",
          "div",
          "do",
          "else",
          "end",
          "exception",
          "fn",
          "fun",
          "handle",
          "if",
          "in",
          "infix",
          "infixr",
          "let",
          "local",
          "mod",
          "nonfix",
          "of",
          "op",
          "open",
          "orelse",
          "raise",
          "rec",
          "sharing",
          "sig",
          "signature",
          "struct",
          "structure",
          "then",
          "type",
          "val",
          "where",
          "while",
          "with",
          "withtype");

  /**
   * Morel-specific keywords not in SML. These are also the keywords that {@code
   * after.sh} promotes from {@code <span class="n">} to {@code <span
   * class="kr">} on the live blog.
   */
  private static final Set<String> MOREL_KEYWORDS =
      ImmutableSet.of(
          "compute",
          "current",
          "desc",
          "distinct",
          "elem",
          "except",
          "exists",
          "forall",
          "from",
          "group",
          "implies",
          "inst",
          "intersect",
          "join",
          "not",
          "on",
          "order",
          "ordinal",
          "over",
          "require",
          "skip",
          "take",
          "unorder",
          "union",
          "yield",
          "yieldAll");

  /**
   * DML keywords, not active by default. Pass to {@link
   * #amendKeywords(Function)} to enable them for a highlighter instance.
   */
  public static final Set<String> DML_KEYWORDS =
      ImmutableSet.of("assign", "commit", "delete", "insert", "update");

  /** Union of {@link #SML_KEYWORDS} and {@link #MOREL_KEYWORDS}. */
  private static final Set<String> ALL_KEYWORDS =
      ImmutableSet.<String>builder()
          .addAll(SML_KEYWORDS)
          .addAll(MOREL_KEYWORDS)
          .build();

  /**
   * Punctuation characters. Consecutive punctuation characters are grouped and
   * emitted as plain text (no span).
   */
  private static final String PUNCT_CHARS = "()[]{}=,;|.";

  /** Default highlighter instance, with SML and Morel keywords active. */
  public static final MorelHighlighter DEFAULT =
      new MorelHighlighter(ALL_KEYWORDS);

  /** Active keyword set for this highlighter instance. */
  private final Set<String> keywords;

  private MorelHighlighter(Set<String> keywords) {
    this.keywords = ImmutableSet.copyOf(keywords);
  }

  /**
   * Returns a new highlighter whose keyword set is the result of applying
   * {@code fn} to this highlighter's keyword set.
   *
   * <p>For example, to add DML keywords:
   *
   * <pre>{@code
   * MorelHighlighter.DEFAULT.amendKeywords(
   *     keywords -> Iterables.concat(keywords, MorelHighlighter.DML_KEYWORDS))
   * }</pre>
   */
  public MorelHighlighter amendKeywords(
      Function<Set<String>, Iterable<String>> fn) {
    return new MorelHighlighter(ImmutableSet.copyOf(fn.apply(keywords)));
  }

  /**
   * Receives highlighted token spans emitted by {@link #highlightCode}.
   *
   * <p>Each method is named after the CSS class of the token it represents,
   * abbreviated to one or two letters. {@code start} and {@code end} are
   * character positions in the source string passed to {@code highlightCode}.
   */
  public interface Sink {
    /** Keyword. */
    void kr(int start, int end);

    /** String literal. */
    void s(int start, int end);

    /** Comment opening {@code (*}. */
    void c(int start, int end);

    /**
     * Comment continuation (everything after {@code (*} through {@code *)}).
     */
    void cm(int start, int end);

    /** Constructor or type variable. */
    void ct(int start, int end);

    /** Numeric literal. */
    void n(int start, int end);

    /** Operator. */
    void o(int start, int end);

    /** Identifier that is the name bound by a {@code val} declaration. */
    void nv(int start, int end);

    /** Identifier that is the name bound by a {@code fun} declaration. */
    void nf(int start, int end);

    /** Plain identifier (not a keyword or binding name). */
    void id(int start, int end);

    /** Punctuation (grouped consecutive chars from {@code ()[]{}=,;|.}). */
    void p(int start, int end);

    /** Whitespace and other undecorated text. */
    void plain(int start, int end);
  }

  /**
   * Helper for {@link Sink} implementations that write HTML {@code <span>}s.
   */
  private abstract static class AbstractSpanSink implements Sink {
    protected final String source;
    protected final StringBuilder out;

    AbstractSpanSink(String source, StringBuilder out) {
      this.source = source;
      this.out = out;
    }

    protected void span(String cls, int start, int end) {
      out.append("<span class=\"").append(cls).append("\">");
      appendEscaped(source, start, end, out);
      out.append("</span>");
    }

    @Override
    public void plain(int start, int end) {
      appendEscaped(source, start, end, out);
    }
  }

  /**
   * {@link Sink} that wraps tokens in {@code <span>} elements using Rouge CSS
   * classes, compatible with Jekyll's {@code highlighter-rouge} output.
   */
  private static class RougeSink extends AbstractSpanSink {
    RougeSink(String source, StringBuilder out) {
      super(source, out);
    }

    @Override
    public void kr(int start, int end) {
      span("kr", start, end);
    }

    @Override
    public void s(int start, int end) {
      span("s2", start, end);
    }

    @Override
    public void c(int start, int end) {
      span("c", start, end);
    }

    @Override
    public void cm(int start, int end) {
      span("cm", start, end);
    }

    @Override
    public void ct(int start, int end) {
      span("nn", start, end);
    }

    @Override
    public void n(int start, int end) {
      span("mi", start, end);
    }

    @Override
    public void o(int start, int end) {
      span("o", start, end);
    }

    @Override
    public void nv(int start, int end) {
      span("nv", start, end);
    }

    @Override
    public void nf(int start, int end) {
      span("nf", start, end);
    }

    @Override
    public void id(int start, int end) {
      span("n", start, end);
    }

    @Override
    public void p(int start, int end) {
      span("p", start, end);
    }
  }

  /**
   * {@link Sink} that converts {@code span(cls, start, end)} to "cls{source}",
   * a concise format suitable for writing unit tests.
   */
  private static final class ConciseRougeSink extends RougeSink {
    ConciseRougeSink(String source, StringBuilder out) {
      super(source, out);
    }

    @Override
    protected void span(String cls, int start, int end) {
      out.append(cls).append('{').append(source, start, end).append('}');
    }
  }

  /**
   * Highlights Morel input code using span classes.
   *
   * <p>Keywords become {@code kw}, type variables and structure names {@code
   * ctor}, integers {@code num}, operators {@code op}, comments {@code cmt},
   * and string literals {@code str}. Plain identifiers and punctuation are
   * emitted without spans.
   */
  public String highlightInput(String code) {
    StringBuilder sb = new StringBuilder();
    highlightCode(code, new RougeSink(code, sb));
    return sb.toString();
  }

  /**
   * Highlights Morel input code as a Jekyll/Rouge-compatible HTML block.
   *
   * <p>Produces a {@code <div class="language-sml highlighter-rouge">} wrapper
   * containing a {@code <pre class="highlight"><code>} block with tokens
   * annotated using Rouge CSS classes ({@code kr}, {@code nv}, {@code nf},
   * {@code mi}, {@code n}, {@code p}, etc.).
   */
  public String highlightRouge(String code) {
    StringBuilder sb = new StringBuilder();
    sb.append("<div class=\"language-sml highlighter-rouge\">")
        .append("<div class=\"highlight\">")
        .append("<pre class=\"highlight\">")
        .append("<code>");
    highlightCode(code, new RougeSink(code, sb));
    sb.append("</code></pre></div></div>");
    return sb.toString();
  }

  /**
   * As {@link #highlightRouge(String)} but more concise output. For testing.
   */
  public String highlightRouge2(String code) {
    StringBuilder sb = new StringBuilder();
    highlightCode(code, new ConciseRougeSink(code, sb));
    return sb.toString();
  }

  /**
   * Returns HTML-escaped output text (REPL responses), without any span
   * decoration.
   */
  public String highlightOutput(String text) {
    StringBuilder sb = new StringBuilder();
    appendEscaped(text, 0, text.length(), sb);
    return sb.toString();
  }

  /**
   * Tokenizes {@code s} and emits each token to {@code sink}.
   *
   * <p>Tracks whether the immediately preceding keyword was {@code val} or
   * {@code fun}: the first plain identifier after {@code val} is emitted via
   * {@link Sink#nv} and the first after {@code fun} via {@link Sink#nf}; all
   * other plain identifiers are emitted via {@link Sink#id}.
   *
   * <p>Identifiers that appear as the bound variable in a {@code from}
   * generator (between {@code from}/{@code ,}/{@code join} and the following
   * {@code in} keyword) are also emitted via {@link Sink#nv}. For example, in
   * {@code from x in emps, (y, z) in depts join w in customers}, the variables
   * {@code x}, {@code y}, {@code z}, and {@code w} are all emitted as {@link
   * Sink#nv}.
   *
   * <p>Lines that start with {@code > } (REPL output) are emitted as a single
   * {@link Sink#c} token (treated as a comment in Rouge output).
   */
  public void highlightCode(String s, Sink sink) {
    int i = 0;
    int n = s.length();
    // State for context-sensitive identifier classification.
    boolean awaitingFunName = false;
    // valPatDepth: -1=inactive, 0+=in val pattern (depth of brackets). All
    // identifiers in val pattern mode are emitted as nv. Exits on '=' at
    // depth 0. fromState: 0=NONE, 1=PAT (expecting bound variables), 2=EXPR
    // (in source expression after 'in'). fromDepth tracks parenthesis nesting
    // in EXPR state so that a top-level comma triggers a new generator.
    int valPatDepth = -1;
    int fromState = 0;
    int fromDepth = 0;

    while (i < n) {
      char c = s.charAt(i);

      if (c == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
        // SML comment (* ... *): emit "(*" as c() and the rest as cm().
        int end = scanComment(s, i, n);
        sink.c(i, i + 2);
        if (i + 2 < end) {
          sink.cm(i + 2, end);
        }
        i = end;

      } else if (c == '"') {
        // String literal
        int end = scanString(s, i, n);
        sink.s(i, end);
        i = end;

      } else if (c == '\''
          && i + 1 < n
          && Character.isLetter(s.charAt(i + 1))) {
        // Type variable: 'a, 'b, 'alpha, etc. → ctor class
        int end = i + 1;
        while (end < n
            && (Character.isLetterOrDigit(s.charAt(end))
                || s.charAt(end) == '_'
                || s.charAt(end) == '\'')) {
          end++;
        }
        sink.ct(i, end);
        i = end;

      } else if (Character.isLetter(c) || c == '_') {
        // Identifier or keyword
        int end = i + 1;
        while (end < n
            && (Character.isLetterOrDigit(s.charAt(end))
                || s.charAt(end) == '_'
                || s.charAt(end) == '\'')) {
          end++;
        }
        String word = s.substring(i, end);
        if (keywords.contains(word)) {
          sink.kr(i, end);
          if (word.equals("val")) {
            valPatDepth = 0;
            awaitingFunName = false;
            fromState = 0;
          } else if (word.equals("fun")) {
            awaitingFunName = true;
            valPatDepth = -1;
            fromState = 0;
          } else if (word.equals("from")) {
            fromState = 1; // PAT
            fromDepth = 0;
            valPatDepth = -1;
            awaitingFunName = false;
          } else if (word.equals("in") && fromState == 1) {
            // 'in' after a from-pattern: switch to expression mode.
            fromState = 2; // EXPR
            fromDepth = 0;
            awaitingFunName = false;
          } else if (word.equals("join") && fromState == 2) {
            // 'join' introduces a new generator pattern.
            fromState = 1; // PAT
            awaitingFunName = false;
          } else if ((word.equals("where")
                  || word.equals("yield")
                  || word.equals("group")
                  || word.equals("order"))
              && (fromState == 1 || fromState == 2 && fromDepth == 0)) {
            // End of the generator list; no more patterns expected.
            fromState = 0;
            awaitingFunName = false;
          } else {
            awaitingFunName = false;
          }
        } else if (end < n && s.charAt(end) == '.') {
          // Identifier immediately followed by '.' is a structure name → ctor
          sink.ct(i, end);
          valPatDepth = -1;
          awaitingFunName = false;
        } else if (valPatDepth >= 0) {
          // Bound variable in a val pattern.
          sink.nv(i, end);
        } else if (awaitingFunName) {
          sink.nf(i, end);
          awaitingFunName = false;
        } else if (fromState == 1) {
          // Bound variable in a from-generator pattern.
          sink.nv(i, end);
        } else {
          sink.id(i, end);
        }
        i = end;

      } else if (Character.isDigit(c)) {
        // Integer literal
        int end = i + 1;
        while (end < n && Character.isDigit(s.charAt(end))) {
          end++;
        }
        sink.n(i, end);
        i = end;

      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == ':') {
        // :: list-cons operator (check before lone ':')
        sink.o(i, i + 2);
        i += 2;

      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == '=') {
        // := reference assignment operator
        sink.o(i, i + 2);
        i += 2;

      } else if (c == '=' && i + 1 < n && s.charAt(i + 1) == '>') {
        // => pattern-match arrow (check before '=' alone in PUNCT_CHARS)
        sink.o(i, i + 2);
        i += 2;

      } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '>') {
        // -> function-type arrow
        sink.o(i, i + 2);
        i += 2;

      } else if (PUNCT_CHARS.indexOf(c) >= 0) {
        // Punctuation: group consecutive punctuation characters, and update
        // from-state based on parenthesis depth and commas encountered.
        int end = i + 1;
        while (end < n && PUNCT_CHARS.indexOf(s.charAt(end)) >= 0) {
          end++;
        }
        if (valPatDepth >= 0 || fromState == 2) {
          // Track bracket depth; ',' in EXPR starts a new generator; '=' at
          // depth 0 ends a val pattern. valPatDepth and fromState==2 are
          // mutually exclusive (from clears valPatDepth).
          for (int j = i; j < end; j++) {
            char p = s.charAt(j);
            if (p == '(' || p == '[' || p == '{') {
              if (valPatDepth >= 0) {
                valPatDepth++;
              } else {
                fromDepth++;
              }
            } else if (p == ')' || p == ']' || p == '}') {
              if (valPatDepth > 0) {
                valPatDepth--;
              } else if (fromDepth > 0) {
                fromDepth--;
              }
            } else if (p == ',' && fromState == 2 && fromDepth == 0) {
              fromState = 1; // PAT
            } else if (p == '=' && valPatDepth == 0) {
              valPatDepth = -1;
            }
          }
        }
        sink.p(i, end);
        i = end;

      } else if (c == ':') {
        // Lone colon (type annotation) → punctuation
        sink.p(i, i + 1);
        i++;

      } else if (c == '<' || c == '>') {
        // Comparison operators: SML treats these as identifiers → o() class
        sink.o(i, i + 1);
        i++;

      } else if (c == '+' || c == '*' || c == '/' || c == '-') {
        // Arithmetic operators (single character; - and -> handled above)
        sink.o(i, i + 1);
        i++;

      } else if (c == '\n') {
        sink.plain(i, i + 1);
        i++;

      } else {
        // Whitespace, &, and other characters.
        sink.plain(i, i + 1);
        i++;
      }
    }
  }

  /** Scans a nested SML comment {@code (* ... *)} and returns end index. */
  private static int scanComment(String s, int start, int n) {
    int depth = 0;
    int i = start;
    while (i < n) {
      if (s.charAt(i) == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
        depth++;
        i += 2;
      } else if (s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == ')') {
        depth--;
        i += 2;
        if (depth == 0) {
          break;
        }
      } else {
        i++;
      }
    }
    return i;
  }

  /** Scans a string literal {@code "..."} and returns end index. */
  private static int scanString(String s, int start, int n) {
    int i = start + 1; // skip opening "
    while (i < n) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2; // skip escape sequence
      } else if (c == '"') {
        i++; // skip closing "
        break;
      } else {
        i++;
      }
    }
    return i;
  }

  /**
   * Appends HTML-escaped characters from {@code s[start..end)} to {@code out}.
   */
  private static void appendEscaped(
      String s, int start, int end, StringBuilder out) {
    for (int i = start; i < end; i++) {
      char c = s.charAt(i);
      if (c == '<') {
        out.append("&lt;");
      } else if (c == '>') {
        out.append("&gt;");
      } else if (c == '&') {
        out.append("&amp;");
      } else {
        out.append(c);
      }
    }
  }
}

// End MorelHighlighter.java
