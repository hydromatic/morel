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
package net.hydromatic.morel;

import static java.lang.String.join;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.hydromatic.morel.util.MorelHighlighter;
import org.jspecify.annotations.Nullable;

/**
 * Morel notebook kernel: processes documents containing {@code <!-- morel ...
 * -->} cells, executes the embedded Morel code, and weaves {@code <div
 * class="morel">...</div>} blocks with syntax-highlighted output back into the
 * document.
 *
 * <p>Inspired by Donald Knuth's WEAVE (part of the WEB literate programming
 * system, 1984), which produced a typeset document from source containing both
 * code and prose. Like Jupyter notebooks, Darn treats each {@code <!-- morel
 * -->} comment as a cell, executes it via the Morel kernel, and stores the
 * results alongside the code — but operating on plain text files (Markdown,
 * HTML, or any file that accepts HTML comments) rather than a JSON notebook
 * format.
 *
 * <p>Each cell has exactly one <em>command</em> and zero or more
 * <em>options</em> on the {@code <!-- morel} opening line.
 *
 * <p>Commands (mutually exclusive; default is {@code run}):
 *
 * <ul>
 *   <li>{@code run} &mdash; execute the code and emit a {@code <div>} block
 *       (this is the default; the token may be omitted);
 *   <li>{@code silent} &mdash; execute but do not emit a {@code <div>} block;
 *   <li>{@code skip} &mdash; emit a {@code <div>} but do not execute.
 * </ul>
 *
 * <p>Options (boolean, use {@code no-} prefix to negate):
 *
 * <ul>
 *   <li>{@code output} / {@code no-output} (default: {@code output}) &mdash;
 *       include or suppress the output {@code <pre>} block in the div; the
 *       output is still stored in the comment for validation;
 *   <li>{@code fail} / {@code no-fail} (default: {@code no-fail}) &mdash;
 *       expect execution to produce an error;
 *   <li>{@code env=NAME} &mdash; share kernel state with other cells that use
 *       the same environment name (default: {@code "default"}).
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 * ./morel darn-update file.md   # execute cells and update in-place
 * ./morel darn-verify file.md   # verify only, report mismatches
 * ./morel darn-probe file.md    # probe skip cells, report OK/ERROR
 * </pre>
 */
public class Darn {

  static final String DIV_OPEN = "<div class=\"code-block\">";
  static final String DIV_CLOSE = "</div>";
  static final String COMMENT_PREFIX = "<!-- morel";
  static final String COMMENT_CLOSE = "-->";

  private Darn() {}

  /** Returns a supplier that refuses to make a {@link Kernel}. */
  static Supplier<Kernel> noKernel() {
    return () -> {
      throw new IllegalArgumentException("no kernel");
    };
  }

  /**
   * Processes a document file. In update mode, writes changes in-place. In
   * verify mode, reports mismatches. Optionally prints per-file statistics to
   * {@link System#out} when {@code verbose} is true.
   *
   * @param file The document file to process
   * @param verifyOnly If true, report mismatches but do not modify the file
   * @param kernelSupplier Supplier that creates a fresh {@link Kernel} instance
   *     for each new execution environment
   * @param verbose If true, print per-file statistics
   * @return true if there were any changes (or mismatches in verify mode)
   */
  public static boolean process(
      File file,
      boolean verifyOnly,
      Supplier<Kernel> kernelSupplier,
      boolean verbose)
      throws IOException {
    List<String> inputLines =
        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    MorelHighlighter highlighter = MorelHighlighter.DEFAULT;
    if (file.getName().contains("dml-in-morel")) {
      highlighter =
          highlighter.amendKeywords(
              keywords ->
                  Iterables.concat(keywords, MorelHighlighter.DML_KEYWORDS));
    }
    ProcessResult result =
        processLines(inputLines, kernelSupplier, highlighter);
    boolean changed = !result.lines.equals(inputLines);
    if (changed) {
      if (verifyOnly) {
        System.err.printf(
            "Mismatch in %s: %d cell(s) differ%n", file, result.mismatchCount);
      } else {
        Files.write(file.toPath(), result.lines, StandardCharsets.UTF_8);
      }
    }
    if (verbose) {
      System.out.println(result.toVerboseString(file.getName()));
    }
    return changed;
  }

  /** Processes a list of lines and returns the updated list. */
  static ProcessResult processLines(
      List<String> lines, Supplier<Kernel> kernelSupplier) {
    return processLines(lines, kernelSupplier, MorelHighlighter.DEFAULT);
  }

  /** As {@link #processLines(List, Supplier)}, with a highlighter. */
  static ProcessResult processLines(
      List<String> lines,
      Supplier<Kernel> kernelSupplier,
      MorelHighlighter highlighter) {
    List<String> result = new ArrayList<>();
    int cellCount = 0;
    int executedCount = 0;
    int mismatchCount = 0;
    int divCount = 0;
    int divChangedCount = 0;
    int i = 0;
    int n = lines.size();

    // Map from environment name to the persistent kernel for that environment.
    Map<String, Kernel> kernels = new LinkedHashMap<>();

    while (i < n) {
      String line = lines.get(i);

      if (!line.startsWith(COMMENT_PREFIX)) {
        result.add(line);
        i++;
        continue;
      }

      // Found a "<!-- morel ..." opening. Collect lines through "-->".
      List<String> commentLines = new ArrayList<>();
      commentLines.add(line);
      i++;
      while (i < n && !lines.get(i).equals(COMMENT_CLOSE)) {
        commentLines.add(lines.get(i));
        i++;
      }
      if (i < n) {
        commentLines.add(COMMENT_CLOSE);
        i++;
      }

      // Parse attributes and content.
      Attrs attrs = parseAttrs(line);
      cellCount++;
      List<String> contentLines =
          commentLines.subList(1, commentLines.size() - 1);
      List<Segment> segments = parseSegments(contentLines);

      // Emit the comment block unchanged.
      result.addAll(commentLines);

      // Skip any existing <div class="morel">...</div> block that follows,
      // along with surrounding blank lines. Blank lines before the div are
      // always consumed; the generated div's surrounding blank lines come
      // solely from the result.add("") calls below.
      while (i < n && lines.get(i).isEmpty()) {
        i++;
      }
      // Capture the old div so we can detect whether the regenerated div
      // has changed (for statistics).
      List<String> oldDivLines = null;
      if (i < n
          && lines.get(i).startsWith("<div ")
          && (lines.get(i).contains("code-block")
              || lines.get(i).contains("class=")
                  && lines.get(i).contains("morel"))) {
        oldDivLines = new ArrayList<>();
        oldDivLines.add(lines.get(i)); // opening <div ...>
        i++;
        while (i < n && !lines.get(i).equals(DIV_CLOSE)) {
          oldDivLines.add(lines.get(i));
          i++;
        }
        if (i < n) {
          oldDivLines.add(lines.get(i)); // closing </div>
          i++;
        }
        // Also skip a trailing blank line after </div> if present.
        if (i < n && lines.get(i).isEmpty()) {
          i++;
        }
      }

      // Execute the cell (unless skip). Silent cells execute to build env
      // state but do not emit a div; only skip cells are not executed.
      if (attrs.command != Command.SKIP) {
        executedCount++;
        @SuppressWarnings("resource")
        final Kernel kernel =
            kernels.computeIfAbsent(attrs.env, k -> kernelSupplier.get());
        try {
          // Each segment may contain multiple statements. Split on ';'-
          // terminated lines so output stays paired with its code, and
          // flatten multi-line kernel output into physical lines.
          final List<Segment> updated = new ArrayList<>();
          for (Segment segment : segments) {
            for (List<String> group : splitStatements(segment.input)) {
              final List<String> outLines =
                  flattenLines(kernel.execute(join("\n", group) + "\n"));
              // Remove trailing empty lines.
              while (!outLines.isEmpty()
                  && outLines.get(outLines.size() - 1).isEmpty()) {
                outLines.remove(outLines.size() - 1);
              }
              updated.add(new Segment(group, outLines));
            }
          }
          if (!updated.equals(segments)) {
            mismatchCount++;
            segments = updated;
            // Rewrite comment with updated > lines.
            // (Replace the last emitted comment block in result.)
            int resultCommentStart = result.size() - commentLines.size();
            List<String> newCommentLines = rebuildComment(line, segments);
            result.subList(resultCommentStart, result.size()).clear();
            result.addAll(newCommentLines);
          }
        } catch (Exception e) {
          // Execution error: leave segments as-is.
        }
      }

      // Generate and insert a <div class="morel"> block (unless silent).
      if (attrs.command != Command.SILENT) {
        divCount++;
        final List<String> newDivLines =
            generateHtmlLines(
                segments, attrs.noOutput, attrs.fail, highlighter);
        if (!newDivLines.equals(
            oldDivLines != null ? oldDivLines : ImmutableList.of())) {
          divChangedCount++;
        }
        result.add("");
        result.addAll(newDivLines);
        result.add("");
      }
    }

    kernels.values().forEach(Kernel::close);
    return new ProcessResult(
        result,
        cellCount,
        executedCount,
        mismatchCount,
        divCount,
        divChangedCount);
  }

  /** Parses attributes from the {@code <!-- morel [attrs] } opening line. */
  static Attrs parseAttrs(String openingLine) {
    // Strip "<!-- morel" prefix and optional trailing whitespace.
    String rest = openingLine.substring(COMMENT_PREFIX.length()).trim();
    Command command = Command.RUN;
    boolean noOutput = false;
    boolean fail = false;
    String env = "default";
    for (String token : rest.split("\\s+")) {
      if (token.isEmpty()) {
        continue;
      }
      switch (token) {
        case "run":
          command = Command.RUN;
          break;
        case "silent":
          command = Command.SILENT;
          break;
        case "skip":
          command = Command.SKIP;
          break;
        case "output":
          noOutput = false;
          break;
        case "no-output":
          noOutput = true;
          break;
        case "fail":
          fail = true;
          break;
        case "no-fail":
          fail = false;
          break;
        default:
          if (token.startsWith("env=")) {
            env = token.substring("env=".length());
          }
          break;
      }
    }
    return new Attrs(command, noOutput, fail, env);
  }

  /**
   * Parses comment content lines into a list of {@link Segment}s.
   *
   * <p>Input lines are plain text; output lines are prefixed with {@code "> "}.
   * Adjacent input lines form a single cell's input; adjacent output lines form
   * the following cell's output.
   */
  static List<Segment> parseSegments(List<String> contentLines) {
    List<Segment> segments = new ArrayList<>();
    List<String> currentInput = new ArrayList<>();
    List<String> currentOutput = new ArrayList<>();
    boolean inOutput = false;

    for (String line : contentLines) {
      if (line.equals(">") || line.startsWith("> ")) {
        String stripped = line.equals(">") ? "" : line.substring(2);
        if (!inOutput && !currentInput.isEmpty()) {
          inOutput = true;
        }
        currentOutput.add(stripped);
      } else {
        if (inOutput) {
          // Transition from output back to input: save the current segment.
          segments.add(
              new Segment(
                  ImmutableList.copyOf(currentInput),
                  ImmutableList.copyOf(currentOutput)));
          currentInput = new ArrayList<>();
          currentOutput = new ArrayList<>();
          inOutput = false;
        }
        currentInput.add(line);
      }
    }
    // Save the last segment.
    if (!currentInput.isEmpty() || !currentOutput.isEmpty()) {
      segments.add(
          new Segment(
              ImmutableList.copyOf(currentInput),
              ImmutableList.copyOf(currentOutput)));
    }
    return segments;
  }

  /**
   * Splits input lines into statement groups.
   *
   * <p>Each group ends with a line that ends with {@code ';'}, matching the
   * convention for top-level Morel declarations ({@code val}, {@code fun},
   * {@code datatype}, etc.). If no {@code ';'} is found, the entire input is
   * returned as one group. Trailing lines after the last {@code ';'} form an
   * additional group.
   *
   * <p>Note: this is a line-based heuristic and does not parse Morel syntax. It
   * works correctly for simple top-level declarations but may produce incorrect
   * splits for expressions containing {@code ;} in sub-expressions (e.g. {@code
   * let...in...end} with inner {@code val} bindings).
   */
  static List<List<String>> splitStatements(List<String> inputLines) {
    final List<List<String>> groups = new ArrayList<>();
    List<String> current = new ArrayList<>();
    for (String line : inputLines) {
      current.add(line);
      if (line.endsWith(";")) {
        groups.add(ImmutableList.copyOf(current));
        current = new ArrayList<>();
      }
    }
    if (!current.isEmpty()) {
      groups.add(ImmutableList.copyOf(current));
    }
    return groups.isEmpty()
        ? ImmutableList.of(ImmutableList.copyOf(inputLines))
        : ImmutableList.copyOf(groups);
  }

  /**
   * Splits each element of {@code rawLines} on embedded {@code '\n'} so that
   * multi-line pretty-printed values become individual physical lines.
   */
  private static List<String> flattenLines(List<String> rawLines) {
    final List<String> flat = new ArrayList<>();
    for (String s : rawLines) {
      Collections.addAll(flat, s.split("\n", -1));
    }
    return flat;
  }

  /** Builds the concatenated input string for all segments. */
  private static String buildInput(List<Segment> segments) {
    StringBuilder sb = new StringBuilder();
    for (Segment segment : segments) {
      for (String line : segment.input) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString();
  }

  /** Rebuilds the comment lines with updated expected output. */
  static List<String> rebuildComment(
      String openingLine, List<Segment> segments) {
    List<String> lines = new ArrayList<>();
    lines.add(openingLine);
    for (Segment segment : segments) {
      lines.addAll(segment.input);
      for (String outLine : segment.output) {
        lines.add(outLine.isEmpty() ? ">" : "> " + outLine);
      }
    }
    lines.add(COMMENT_CLOSE);
    return lines;
  }

  /**
   * Updates segment expected output with the actual execution output.
   *
   * <p>Distributes the actual output lines across segments proportionally to
   * the number of expected output lines, treating segments without expected
   * output as having zero output.
   */
  static List<Segment> updateSegments(
      List<Segment> segments, List<String> actualLines) {
    // Remove trailing empty lines from actualLines.
    if (actualLines.stream().anyMatch(String::isEmpty)) {
      // Ensure mutable.
      actualLines = new ArrayList<>(actualLines);
      while (!actualLines.isEmpty()
          && actualLines.get(actualLines.size() - 1).isEmpty()) {
        actualLines.remove(actualLines.size() - 1);
      }
    }

    // Collect existing expected output, flat.
    List<String> expected = new ArrayList<>();
    for (Segment segment : segments) {
      expected.addAll(segment.output);
    }

    if (actualLines.equals(expected)) {
      return segments; // no change
    }

    // Put all actual output in the last segment.
    List<Segment> updated = new ArrayList<>();
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = segments.get(i);
      if (i == segments.size() - 1) {
        updated.add(
            new Segment(segment.input, ImmutableList.copyOf(actualLines)));
      } else {
        updated.add(new Segment(segment.input, ImmutableList.of()));
      }
    }
    return updated;
  }

  /**
   * Probes each {@code skip} cell in a document by attempting to execute it,
   * and prints a report to {@code out}.
   *
   * <p>For each {@code skip} cell, reports whether it would execute
   * successfully (suggesting {@code no-output} or plain {@code morel}) or fail
   * (suggesting it stay as {@code skip}). Non-skip cells are executed silently
   * to build up the kernel environment, so that later {@code skip} cells that
   * depend on earlier definitions are tested in context.
   */
  public static void probe(
      File file, PrintStream out, Supplier<Kernel> kernelSupplier)
      throws IOException {
    List<String> lines =
        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    List<ProbeResult> results = probeLines(lines, kernelSupplier);
    String name = file.getName();
    for (ProbeResult r : results) {
      if (r.isOk()) {
        requireNonNull(r.output);
        final String summary;
        final String suggest;
        if (r.output.isEmpty()) {
          summary = "(no output)";
          suggest = "no-output";
        } else {
          suggest = "morel";
          if (r.output.length() > 60) {
            summary = r.output.substring(0, 60) + "...";
          } else {
            summary = r.output;
          }
        }
        out.printf(
            "%s:%d: skip — OK: %s [suggest: %s]%n",
            name, r.lineNumber, summary, suggest);
      } else {
        // Error: either a Java exception (r.error non-null) or interpreter
        // error output (r.output doesn't match the val/type pattern).
        String detail =
            r.error != null
                ? r.error
                : r.output != null ? r.output : "(unknown error)";
        if (detail.length() > 80) {
          detail = detail.substring(0, 80) + "...";
        }
        out.println(name + ":" + r.lineNumber + ": skip — ERROR: " + detail);
      }
    }
  }

  /**
   * Probes {@code skip} cells in a list of document lines, attempting to
   * execute each one.
   *
   * <p>Non-skip, non-silent cells are executed to accumulate kernel state, so
   * that skip cells can be tested in their natural context.
   */
  static List<ProbeResult> probeLines(List<String> lines) {
    return probeLines(lines, noKernel());
  }

  /** As {@link #probeLines(List)}, with a kernel supplier. */
  static List<ProbeResult> probeLines(
      List<String> lines, Supplier<Kernel> kernelSupplier) {
    List<ProbeResult> results = new ArrayList<>();
    int i = 0;
    final int n = lines.size();
    final Map<String, Kernel> kernels = new LinkedHashMap<>();

    while (i < n) {
      String line = lines.get(i);
      if (!line.startsWith(COMMENT_PREFIX)) {
        i++;
        continue;
      }

      int lineNumber = i + 1; // 1-based
      List<String> commentLines = new ArrayList<>();
      commentLines.add(line);
      i++;
      while (i < n && !lines.get(i).equals(COMMENT_CLOSE)) {
        commentLines.add(lines.get(i));
        i++;
      }
      if (i < n) {
        commentLines.add(COMMENT_CLOSE);
        i++;
      }

      Attrs attrs = parseAttrs(line);
      List<String> contentLines =
          commentLines.subList(1, commentLines.size() - 1);
      List<Segment> segments = parseSegments(contentLines);
      String allInput = buildInput(segments);
      @SuppressWarnings("resource")
      final Kernel kernel =
          kernels.computeIfAbsent(attrs.env, k -> kernelSupplier.get());

      if (attrs.command == Command.SKIP) {
        // Probe: try executing this cell in the current kernel state.
        try {
          final List<String> outLines = kernel.execute(allInput);
          final String actual = join("\n", outLines).trim();
          results.add(new ProbeResult(lineNumber, actual, null));
        } catch (Exception e) {
          String msg =
              e.getMessage() != null
                  ? e.getMessage()
                  : e.getClass().getSimpleName();
          results.add(new ProbeResult(lineNumber, null, msg));
        }
      } else {
        // Run or silent cell: execute to accumulate env state for later skip
        // cells. Silent cells execute but don't show a div.
        try {
          kernel.execute(allInput);
        } catch (Exception ignored) {
          // Already-broken non-skip cells are not our concern here.
        }
      }
    }
    kernels.values().forEach(Kernel::close);
    return results;
  }

  /** Generates the HTML lines for a {@code <div class="morel">} block. */
  static List<String> generateHtmlLines(List<Segment> segments) {
    return generateHtmlLines(segments, false, false, MorelHighlighter.DEFAULT);
  }

  /** As {@link #generateHtmlLines(List)}, with a {@code noOutput} flag. */
  static List<String> generateHtmlLines(
      List<Segment> segments, boolean noOutput) {
    return generateHtmlLines(
        segments, noOutput, false, MorelHighlighter.DEFAULT);
  }

  /** As {@link #generateHtmlLines(List, boolean)}, with a {@code fail} flag. */
  static List<String> generateHtmlLines(
      List<Segment> segments, boolean noOutput, boolean fail) {
    return generateHtmlLines(
        segments, noOutput, fail, MorelHighlighter.DEFAULT);
  }

  /**
   * Generates the HTML lines for a {@code <div class="morel">} block.
   *
   * @param noOutput If true, omit the output div (the output is stored in the
   *     comment for validation but not rendered)
   * @param fail If true, use {@code code-error} class (red bar) instead of
   *     {@code code-output} (green bar) for output lines
   * @param highlighter Syntax highlighter instance
   */
  static List<String> generateHtmlLines(
      List<Segment> segments,
      boolean noOutput,
      boolean fail,
      MorelHighlighter highlighter) {
    List<String> lines = new ArrayList<>();
    lines.add(DIV_OPEN);
    for (Segment segment : segments) {
      if (!segment.input.isEmpty()) {
        String inputHtml =
            highlighter.highlightInput(join("\n", segment.input));
        addCodeBlock(lines, "code-input", inputHtml);
      }
      if (!noOutput && !segment.output.isEmpty()) {
        String outputHtml =
            highlighter.highlightOutput(join("\n", segment.output));
        addCodeBlock(lines, fail ? "code-error" : "code-output", outputHtml);
      }
    }
    lines.add(DIV_CLOSE);
    return lines;
  }

  /**
   * Wraps content in a {@code div} block with the given CSS class, splitting on
   * newlines so that multi-line blocks occupy multiple list entries (and thus
   * round-trip correctly through {@link java.nio.file.Files#readAllLines} and
   * {@link java.nio.file.Files#write}).
   */
  private static void addCodeBlock(
      List<String> lines, String cls, String content) {
    String block = String.format("<div class=\"%s\">%s</div>", cls, content);
    Collections.addAll(lines, block.split("\n", -1));
  }

  /** The command for a {@code <!-- morel -->} cell. */
  enum Command {
    /** Execute the code and emit a {@code <div>} block (the default). */
    RUN,
    /** Execute the code but do not emit a {@code <div>} block. */
    SILENT,
    /** Emit a {@code <div>} block but do not execute the code. */
    SKIP
  }

  /** Attributes parsed from the {@code <!-- morel [attrs] } opening line. */
  static class Attrs {
    /** The cell command; never null (defaults to {@link Command#RUN}). */
    final Command command;
    /** If true, suppress the output {@code <pre>} block in the rendered div. */
    final boolean noOutput;
    /** If true, expect execution to produce an error. */
    final boolean fail;
    /** Kernel environment name; never null (defaults to {@code "default"}). */
    final String env;

    Attrs(Command command, boolean noOutput, boolean fail, String env) {
      this.command = command;
      this.noOutput = noOutput;
      this.fail = fail;
      this.env = env;
    }
  }

  /**
   * A segment: one group of input lines (a cell's code) paired with its
   * expected kernel output.
   */
  static class Segment {
    final List<String> input;
    final List<String> output;

    Segment(List<String> input, List<String> output) {
      this.input = ImmutableList.copyOf(input);
      this.output = ImmutableList.copyOf(output);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Segment)) {
        return false;
      }
      Segment other = (Segment) o;
      return input.equals(other.input) && output.equals(other.output);
    }

    @Override
    public int hashCode() {
      return 31 * input.hashCode() + output.hashCode();
    }
  }

  /** Result of probing a single {@code skip} cell. */
  static class ProbeResult {
    /** 1-based line number of the {@code <!-- morel skip} opening line. */
    final int lineNumber;

    /**
     * Trimmed actual output if execution succeeded; empty string means the cell
     * produced no output. {@code null} if execution failed.
     */
    final @Nullable String output;

    /**
     * Error message if execution failed; {@code null} if execution succeeded.
     */
    final @Nullable String error;

    ProbeResult(
        int lineNumber, @Nullable String output, @Nullable String error) {
      this.lineNumber = lineNumber;
      this.output = output;
      this.error = error;
    }

    /**
     * Returns true if execution succeeded. Success is detected heuristically:
     * no Java exception was thrown, and the first non-empty output line starts
     * with {@code "val "} or {@code "type "} (matching the Morel REPL's output
     * format). Continuation lines (indented value or type text) are not
     * checked, since they do not follow the {@code val }/{@code type } pattern.
     */
    boolean isOk() {
      if (error != null || output == null) {
        return false;
      }
      for (String line : output.split("\n", -1)) {
        if (!line.isEmpty()) {
          return line.startsWith("val ") || line.startsWith("type ");
        }
      }
      return true; // empty output (e.g. unit-returning expression)
    }
  }

  /** Result of processing a document file. */
  static class ProcessResult {
    final List<String> lines;
    /** Total number of {@code <!-- morel} cells in the file. */
    final int cellCount;
    /** Cells whose code was executed (command ≠ {@code skip}). */
    final int executedCount;
    /** Executed cells whose comment output differed from the actual output. */
    final int mismatchCount;
    /** Cells that produce an HTML div block (command ≠ {@code silent}). */
    final int divCount;
    /** Div blocks whose HTML content changed (new ≠ old, or first time). */
    final int divChangedCount;

    ProcessResult(
        List<String> lines,
        int cellCount,
        int executedCount,
        int mismatchCount,
        int divCount,
        int divChangedCount) {
      this.lines = ImmutableList.copyOf(lines);
      this.cellCount = cellCount;
      this.executedCount = executedCount;
      this.mismatchCount = mismatchCount;
      this.divCount = divCount;
      this.divChangedCount = divChangedCount;
    }

    String toVerboseString(String fileName) {
      return String.format(
          "%s: %d cells, %d executed, %d different, %d divs, %d divs changed",
          fileName,
          cellCount,
          executedCount,
          mismatchCount,
          divCount,
          divChangedCount);
    }
  }
}

// End Darn.java
