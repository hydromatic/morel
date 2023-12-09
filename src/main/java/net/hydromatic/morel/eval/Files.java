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
package net.hydromatic.morel.eval;

import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static java.nio.file.Files.newInputStream;
import static java.util.Objects.requireNonNull;

/** Implementations of {@link File}. */
public class Files {
  private Files() {
  }

  /** Creates a file (or directory).
   * Never returns null. */
  public static File create(java.io.File ioFile) {
    return createUnknown(null, ioFile).expand();
  }

  static UnknownFile createUnknown(@Nullable Directory directory,
      java.io.File ioFile) {
    FileType fileType;
    if (ioFile.isDirectory()) {
      fileType = FileType.DIRECTORY;
    } else {
      fileType = FileType.FILE;
      for (FileType fileType2 : FileType.INSTANCES) {
        if (ioFile.getName().endsWith(fileType2.suffix)) {
          fileType = fileType2;
          break;
        }
      }
    }
    if (directory != null) {
      return new UnknownChildFile(directory, ioFile, fileType);
    } else {
      return new UnknownFile(ioFile, fileType);
    }
  }

  /** Returns a string without its suffix; for example,
   * {@code removeSuffix("x.txt", ".txt")} returns {@code "x"}. */
  private static String removeSuffix(String s, String suffix) {
    if (!s.endsWith(suffix)) {
      return s;
    }
    return s.substring(0, s.length() - suffix.length());
  }

  private static PairList<String, Type.Key> deduceFieldsCsv(BufferedReader r)
      throws IOException {
    String firstLine = r.readLine();
    if (firstLine == null) {
      // File is empty. There will be no fields, and row type will be unit.
      return ImmutablePairList.of();
    }

    final PairList<String, Type.Key> nameTypes = PairList.of();
    for (String field : firstLine.split(",")) {
      final String[] split = field.split(":");
      final String subFieldName = split[0];
      final String subFieldType =
          split.length > 1 ? split[1] : "string";
      Type.Key subType;
      switch (subFieldType) {
      case "bool":
        subType = PrimitiveType.BOOL.key();
        break;
      case "decimal":
      case "double":
        subType = PrimitiveType.REAL.key();
        break;
      case "int":
        subType = PrimitiveType.INT.key();
        break;
      default:
        subType = PrimitiveType.STRING.key();
        break;
      }
      nameTypes.add(subFieldName, subType);
    }
    return nameTypes;
  }

  /** Creates a function that converts a string field value to the desired
   * type. */
  static Function<String, Object> parser(Type.Key type) {
    switch (type.op) {
    case DATA_TYPE:
      switch (type.toString()) {
      case "int":
        return s -> s.equals("NULL") ? 0 : Integer.parseInt(s);
      case "real":
        return s -> s.equals("NULL") ? 0f : Float.parseFloat(s);
      case "string":
        return Files::unquoteString;
      default:
        throw new IllegalArgumentException("unknown type " + type);
      }
    default:
      throw new IllegalArgumentException("unknown type " + type);
    }
  }

  /** Converts "abc" to "abc" and "'abc, def'" to "abc, def". */
  static Object unquoteString(String s) {
    if (s.startsWith("'")) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /** Abstract implementation of File. */
  abstract static class AbstractFile implements File {
    final java.io.File ioFile;
    final String baseName;
    final FileType fileType;

    /** Creates an AbstractFile. */
    AbstractFile(java.io.File ioFile, FileType fileType) {
      this.ioFile = requireNonNull(ioFile, "file");
      this.baseName = removeSuffix(ioFile.getName(), fileType.suffix);
      this.fileType = requireNonNull(fileType, "fileType");
    }

    @Override public String toString() {
      return baseName;
    }
  }

  /** File that is a directory. */
  private static class Directory extends AbstractList<File> implements File {
    final java.io.File ioFile;
    final SortedMap<String, File> entries; // mutable

    Directory(java.io.File file) {
      this.ioFile = file;

      entries = new TreeMap<>(RecordType.ORDERING);
      for (java.io.File subFile
          : Util.first(ioFile.listFiles(), new java.io.File[0])) {
        UnknownFile f = createUnknown(this, subFile);
        entries.put(f.baseName, f);
      }
    }

    @Override public Type.Key typeKey() {
      return Keys.progressiveRecord(
          Maps.transformValues(entries, TypedValue::typeKey));
    }

    @Override public File discoverField(TypeSystem typeSystem,
        String fieldName) {
      final File file = entries.get(fieldName);
      if (file != null) {
        File file2 = file.expand();
        if (file2 != file) {
          typeSystem.expandCount.incrementAndGet();
        }
      }
      return this;
    }

    @Override public File get(int index) {
      return Iterables.get(entries.values(), index);
    }

    @Override public int size() {
      return entries.size();
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      if (clazz.isInstance(this)) {
        return clazz.cast(this);
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override public <V> V fieldValueAs(String fieldName, Class<V> clazz) {
      return clazz.cast(entries.get(fieldName));
    }

    @Override public <V> V fieldValueAs(int fieldIndex, Class<V> clazz) {
      return clazz.cast(Iterables.get(entries.values(), fieldIndex));
    }
  }

  /** File that is not a directory, and can be parsed into a set of records. */
  private static class DataFile extends AbstractFile {
    final Type.Key typeKey;
    final PairList<Integer, Function<String, Object>> parsers;

    DataFile(java.io.File file, FileType fileType, Type.Key typeKey,
        PairList<Integer, Function<String, Object>> parsers) {
      super(file, fileType);
      this.typeKey = requireNonNull(typeKey, "typeKey");
      this.parsers = parsers.immutable();
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      try (BufferedReader r = fileType.open(ioFile)) {
        String firstLine = r.readLine();
        if (firstLine == null) {
          return null;
        }
        final Object[] values = new Object[parsers.size()];
        final List<List<Object>> list = new ArrayList<>();
        for (;;) {
          String line = r.readLine();
          if (line == null) {
            return clazz.cast(list);
          }
          String[] fields = line.split(",");
          parsers.forEachIndexed((i, j, parser) ->
              values[j] = parser.apply(fields[i]));
          list.add(ImmutableList.copyOf(values));
        }
      } catch (IOException e) {
        // ignore
        return null;
      }
    }

    @Override public Type.Key typeKey() {
      return typeKey;
    }
  }

  /** File that we have not yet categorized. We don't know whether it is a
   * directory.
   *
   * <p>Its type is an empty record type (because we don't know the files in the
   * directory, or the fields of the data file). */
  private static class UnknownFile extends AbstractFile {
    /** Key for the type "{...}", the progressive record with no
     * (as yet known) fields. */
    static final Type.Key PROGRESSIVE_UNIT =
        Keys.progressiveRecord(ImmutableSortedMap.of());

    /** Key for the type "{...} list", the list of progressive records with no
     * (as yet known) fields. */
    static final Type.Key PROGRESSIVE_UNIT_LIST =
        Keys.list(PROGRESSIVE_UNIT);

    protected UnknownFile(java.io.File file, FileType fileType) {
      super(file, fileType);
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      if (clazz.isAssignableFrom(ImmutableList.class)) {
        return clazz.cast(ImmutableList.of());
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override public Type.Key typeKey() {
      return fileType.list ? PROGRESSIVE_UNIT_LIST : PROGRESSIVE_UNIT;
    }

    @Override public File expand() {
      switch (fileType) {
      case DIRECTORY:
        return new Directory(ioFile);

      case FILE:
        return this;

      default:
        try (BufferedReader r = fileType.open(ioFile)) {
          final PairList<String, Type.Key> nameTypes = fileType.deduceFields(r);
          final ImmutableSortedMap<String, Type.Key> sortedNameTypes =
              ImmutableSortedMap.<String, Type.Key>orderedBy(RecordType.ORDERING)
                  .putAll(nameTypes)
                  .build();
          final PairList<Integer, Function<String, Object>> fieldParsers =
              PairList.of();
          nameTypes.forEach((name, typeKey) -> {
            final int j = sortedNameTypes.keySet().asList().indexOf(name);
            fieldParsers.add(j, parser(typeKey));
          });

          final Type.Key listType = Keys.list(Keys.record(sortedNameTypes));
          return new DataFile(ioFile, fileType, listType, fieldParsers);
        } catch (IOException e) {
          // ignore, and skip file
          return this;
        }
      }
    }

    @Override public File discoverField(TypeSystem typeSystem,
        String fieldName) {
      final File file = expand();
      if (file == this) {
        return this;
      }
      typeSystem.expandCount.incrementAndGet();
      return file.discoverField(typeSystem, fieldName);
    }
  }

  private static class UnknownChildFile extends UnknownFile {
    private final Directory directory;

    protected UnknownChildFile(Directory directory, java.io.File file,
        FileType fileType) {
      super(file, fileType);
      this.directory = requireNonNull(directory, "directory");
    }

    @Override public File expand() {
      final File file = super.expand();
      if (file != this) {
        directory.entries.put(baseName, file);
      }
      return file;
    }
  }

  /** Describes a type of file that can be read by this reader.
   * Each file has a way to deduce the schema (set of field names and types)
   * and to parse the file into a set of records. */
  enum FileType {
    DIRECTORY("", false),
    FILE("", false),
    CSV(".csv", true),
    CSV_GZ(".csv.gz", true);

    /** The non-trivial file types. */
    static final List<FileType> INSTANCES =
        Arrays.stream(values())
            .filter(f -> !f.suffix.isEmpty())
            .collect(ImmutableList.toImmutableList());

    final String suffix;

    /** Whether this file is a list of records. */
    final boolean list;

    FileType(String suffix, boolean list) {
      this.suffix = suffix;
      this.list = list;
    }

    BufferedReader open(java.io.File file) throws IOException {
      switch (this) {
      case CSV:
        return Util.reader(file);
      case CSV_GZ:
        return Util.reader(new GZIPInputStream(newInputStream(file.toPath())));
      default:
        throw new IllegalArgumentException("cannot open file " + file
            + " of type " + this);
      }
    }

    PairList<String, Type.Key> deduceFields(BufferedReader r)
        throws IOException {
      return deduceFieldsCsv(r);
    }
  }
}

// End Files.java
