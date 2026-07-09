package io.helix.parser.hprof;

import io.helix.core.model.heap.ByteArrayLeakSuspect;
import io.helix.core.model.heap.ClassLoaderLeakSuspect;
import io.helix.core.model.heap.DuplicateStringEntry;
import io.helix.core.model.heap.DuplicateStringSummary;
import io.helix.core.model.heap.FinalizerQueueSummary;
import io.helix.core.model.heap.HeapClassHistogramEntry;
import io.helix.core.model.heap.HeapDominatorEntry;
import io.helix.core.model.heap.HeapLeakSuspect;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.progress.ProgressMonitor;
import io.helix.core.spi.ParseException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class MatHeapSnapshotReader {
  private static final int MAX_HISTOGRAM_ROWS = 100;
  private static final int MAX_DOMINATORS = 20;
  private static final int MAX_DUPLICATE_STRING_ROWS = 20;
  private static final int MAX_RETENTION_PATH_DEPTH = 8;
  private static final double LEAK_THRESHOLD_PERCENT = 10.0;
  private static final Pattern TABLE_ROW =
      Pattern.compile("<tr(?:\\s+class=\"([^\"]+)\")?>(.*?)</tr>", Pattern.DOTALL);
  private static final Pattern TABLE_CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
  private static final Pattern TOTAL_ENTRIES = Pattern.compile("of\\s+([0-9,]+)\\s+entries");

  private final ProgressMonitor progressMonitor;

  MatHeapSnapshotReader(ProgressMonitor progressMonitor) {
    this.progressMonitor = progressMonitor == null ? ProgressMonitor.noop() : progressMonitor;
  }

  HeapSnapshotData read(Path hprofPath) throws ParseException {
    MatRuntime mat = MatRuntime.load(progressMonitor);
    Object snapshot = null;
    try {
      if (mat.supportsExternalQueries()) {
        return mat.readViaQueryReports(hprofPath.toFile());
      }
      snapshot = mat.openSnapshot(hprofPath.toFile());
      return extract(mat, snapshot);
    } finally {
      if (snapshot != null) {
        mat.dispose(snapshot);
      }
      mat.close();
    }
  }

  private HeapSnapshotData extract(MatRuntime mat, Object snapshot) throws ParseException {
    Object info = mat.invoke(snapshot, "getSnapshotInfo");
    long totalHeapBytes = mat.longValue(info, "getUsedHeapSize").orElse(0L);
    long totalObjects = mat.longValue(info, "getNumberOfObjects").orElse(0L);
    long totalClasses = mat.longValue(info, "getNumberOfClasses").orElse(0L);
    long gcRootCount = mat.intArray(snapshot, "getGCRoots").map(ids -> (long) ids.length).orElse(0L);

    progressMonitor.message("Reading heap classes");
    Collection<?> classes = mat.collection(snapshot, "getClasses");
    List<ClassRow> classRows = new ArrayList<>();
    for (Object heapClass : classes) {
      classRows.add(readClassRow(mat, snapshot, heapClass, totalHeapBytes));
    }

    List<HeapClassHistogramEntry> histogram = classRows.stream()
        .sorted(Comparator.comparing(ClassRow::shallowBytes).reversed())
        .limit(MAX_HISTOGRAM_ROWS)
        .map(row -> new HeapClassHistogramEntry(
            row.className(),
            row.classLoaderName(),
            row.instanceCount(),
            row.shallowBytes(),
            row.retainedBytes()))
        .toList();

    List<HeapDominatorEntry> dominators = classRows.stream()
        .filter(row -> row.retainedBytes() > 0)
        .sorted(Comparator.comparing(ClassRow::retainedBytes).reversed())
        .limit(MAX_DOMINATORS)
        .map(row -> new HeapDominatorEntry(
            row.className(),
            row.className(),
            row.classLoaderName(),
            row.instanceCount(),
            row.retainedBytes(),
            percent(row.retainedBytes(), totalHeapBytes),
            List.of("[GCRoot]", row.className())))
        .toList();

    List<HeapLeakSuspect> leakSuspects = dominators.stream()
        .filter(row -> row.heapPercent() >= LEAK_THRESHOLD_PERCENT)
        .map(row -> new HeapLeakSuspect(
            row.className(),
            row.classLoaderName(),
            row.retainedBytes(),
            row.heapPercent(),
            retentionPath(mat, snapshot, row.className())))
        .toList();

    DuplicateStringSummary duplicateStrings = duplicateStrings(mat, snapshot, classes);
    FinalizerQueueSummary finalizerQueue = finalizerQueue(classRows);
    List<ByteArrayLeakSuspect> byteArrayLeaks = byteArrayLeaks(classRows, totalHeapBytes);
    List<ClassLoaderLeakSuspect> classLoaderLeaks = classLoaderLeaks(classRows, totalHeapBytes);

    return new HeapSnapshotData(
        totalHeapBytes,
        totalObjects,
        totalClasses,
        gcRootCount,
        histogram,
        dominators,
        leakSuspects,
        duplicateStrings,
        finalizerQueue,
        byteArrayLeaks,
        classLoaderLeaks,
        Map.of(
            "mat.version", mat.version(),
            "jvmInfo", mat.stringValue(info, "getJvmInfo").orElse(""),
            "identifierSize", mat.longValue(info, "getIdentifierSize").orElse(0L)));
  }

  private ClassRow readClassRow(MatRuntime mat, Object snapshot, Object heapClass, long totalHeapBytes) {
    String className = mat.stringValue(heapClass, "getName").orElse("<unknown>");
    long instanceCount = mat.longValue(heapClass, "getNumberOfObjects").orElse(0L);
    long shallowBytes = mat.longValue(heapClass, "getUsedHeapSize").orElse(0L);
    long retainedBytes = mat.retainedHeapSizeOfObjects(heapClass).orElse(0L);
    String loaderName = classLoaderName(mat, snapshot, heapClass);
    double heapPercent = percent(retainedBytes > 0 ? retainedBytes : shallowBytes, totalHeapBytes);
    return new ClassRow(className, loaderName, instanceCount, shallowBytes, retainedBytes, heapPercent);
  }

  private String classLoaderName(MatRuntime mat, Object snapshot, Object heapClass) {
    Optional<Long> loaderId = mat.longValue(heapClass, "getClassLoaderId");
    if (loaderId.isEmpty() || loaderId.get() < 0) {
      return "<bootstrap>";
    }
    try {
      Object loader = mat.invoke(snapshot, "getObject", loaderId.get().intValue());
      return mat.stringValue(loader, "getTechnicalName")
          .or(() -> mat.stringValue(loader, "getClassSpecificName"))
          .orElse("<classloader-" + loaderId.get() + ">");
    } catch (ParseException e) {
      return "<classloader-" + loaderId.get() + ">";
    }
  }

  private DuplicateStringSummary duplicateStrings(MatRuntime mat, Object snapshot, Collection<?> classes) {
    Object stringClass = classes.stream()
        .filter(candidate -> mat.stringValue(candidate, "getName").orElse("").equals("java.lang.String"))
        .findFirst()
        .orElse(null);
    if (stringClass == null) {
      return DuplicateStringSummary.empty();
    }

    Map<String, Long> counts = new HashMap<>();
    int[] objectIds = mat.intArray(stringClass, "getObjectIds").orElse(new int[0]);
    for (int objectId : objectIds) {
      try {
        Object object = mat.invoke(snapshot, "getObject", objectId);
        String value = mat.stringValue(object, "getClassSpecificName").orElse(null);
        if (value != null) {
          counts.merge(value, 1L, Long::sum);
        }
      } catch (ParseException ignored) {
        // String extraction is best-effort; malformed objects should not fail the whole heap parse.
      }
    }

    List<DuplicateStringEntry> entries = counts.entrySet().stream()
        .filter(entry -> entry.getValue() > 1)
        .map(entry -> new DuplicateStringEntry(
            preview(entry.getKey()),
            entry.getValue(),
            estimatedWastedStringBytes(entry.getKey(), entry.getValue())))
        .sorted(Comparator.comparing(DuplicateStringEntry::wastedBytes).reversed())
        .limit(MAX_DUPLICATE_STRING_ROWS)
        .toList();
    long duplicateValueCount = counts.values().stream().filter(count -> count > 1).count();
    long duplicateInstanceCount = counts.values().stream()
        .filter(count -> count > 1)
        .mapToLong(Long::longValue)
        .sum();
    long wastedBytes = entries.stream().mapToLong(DuplicateStringEntry::wastedBytes).sum();
    return new DuplicateStringSummary(duplicateValueCount, duplicateInstanceCount, wastedBytes, entries);
  }

  private FinalizerQueueSummary finalizerQueue(List<ClassRow> classRows) {
    long pending = classRows.stream()
        .filter(row -> row.className().equals("java.lang.ref.Finalizer"))
        .mapToLong(ClassRow::instanceCount)
        .sum();
    return new FinalizerQueueSummary(pending);
  }

  private List<ByteArrayLeakSuspect> byteArrayLeaks(List<ClassRow> classRows, long totalHeapBytes) {
    return classRows.stream()
        .filter(row -> row.className().equals("byte[]") || row.className().equals("[B"))
        .filter(row -> percent(row.shallowBytes(), totalHeapBytes) >= LEAK_THRESHOLD_PERCENT)
        .map(row -> new ByteArrayLeakSuspect(
            row.className(),
            row.instanceCount(),
            row.shallowBytes(),
            percent(row.shallowBytes(), totalHeapBytes),
            List.of("[GCRoot]", row.className())))
        .toList();
  }

  private List<ClassLoaderLeakSuspect> classLoaderLeaks(List<ClassRow> classRows, long totalHeapBytes) {
    Map<String, ClassLoaderAggregate> aggregates = new LinkedHashMap<>();
    for (ClassRow row : classRows) {
      if (row.classLoaderName().equals("<bootstrap>")) {
        continue;
      }
      ClassLoaderAggregate aggregate = aggregates.computeIfAbsent(
          row.classLoaderName(),
          ClassLoaderAggregate::new);
      aggregate.add(row);
    }

    return aggregates.values().stream()
        .filter(aggregate -> percent(aggregate.retainedBytes(), totalHeapBytes) >= LEAK_THRESHOLD_PERCENT)
        .sorted(Comparator.comparing(ClassLoaderAggregate::retainedBytes).reversed())
        .map(aggregate -> new ClassLoaderLeakSuspect(
            aggregate.classLoaderName(),
            aggregate.retainedBytes(),
            percent(aggregate.retainedBytes(), totalHeapBytes),
            aggregate.definedClassCount(),
            aggregate.retainedClasses()))
        .toList();
  }

  private List<String> retentionPath(MatRuntime mat, Object snapshot, String className) {
    Object heapClass = mat.collection(snapshot, "getClasses").stream()
        .filter(candidate -> mat.stringValue(candidate, "getName").orElse("").equals(className))
        .findFirst()
        .orElse(null);
    if (heapClass == null) {
      return List.of("[GCRoot]", className);
    }
    int[] objectIds = mat.intArray(heapClass, "getObjectIds").orElse(new int[0]);
    if (objectIds.length == 0) {
      return List.of("[GCRoot]", className);
    }

    try {
      Object computer = mat.invoke(snapshot, "getPathsFromGCRoots", objectIds[0], null);
      int[] path = (int[]) mat.invoke(computer, "getNextShortestPath");
      if (path == null || path.length == 0) {
        return List.of("[GCRoot]", className);
      }
      List<String> labels = new ArrayList<>();
      labels.add("[GCRoot]");
      for (int i = Math.max(0, path.length - MAX_RETENTION_PATH_DEPTH); i < path.length; i++) {
        Object object = mat.invoke(snapshot, "getObject", path[i]);
        labels.add(mat.stringValue(object, "getTechnicalName")
            .or(() -> mat.stringValue(object, "getClassSpecificName"))
            .orElse("object-" + path[i]));
      }
      return labels;
    } catch (ParseException | ClassCastException e) {
      return List.of("[GCRoot]", className);
    }
  }

  private static long estimatedWastedStringBytes(String value, long instances) {
    long bytesPerString = 40L + (long) value.length() * 2L;
    return Math.max(0L, instances - 1L) * bytesPerString;
  }

  private static String preview(String value) {
    String escaped = value.replace("\n", "\\n").replace("\r", "\\r");
    return escaped.length() <= 80 ? escaped : escaped.substring(0, 77) + "...";
  }

  private static double percent(long value, long total) {
    if (total <= 0) {
      return 0.0;
    }
    return value * 100.0 / total;
  }

  private record ClassRow(
      String className,
      String classLoaderName,
      long instanceCount,
      long shallowBytes,
      long retainedBytes,
      double heapPercent
  ) {}

  private static final class ClassLoaderAggregate {
    private final String classLoaderName;
    private final List<String> retainedClasses = new ArrayList<>();
    private long retainedBytes;
    private long definedClassCount;

    private ClassLoaderAggregate(String classLoaderName) {
      this.classLoaderName = classLoaderName;
    }

    private void add(ClassRow row) {
      definedClassCount++;
      retainedBytes += row.retainedBytes() > 0 ? row.retainedBytes() : row.shallowBytes();
      if (retainedClasses.size() < 20) {
        retainedClasses.add(row.className());
      }
    }

    private String classLoaderName() {
      return classLoaderName;
    }

    private long retainedBytes() {
      return retainedBytes;
    }

    private long definedClassCount() {
      return definedClassCount;
    }

    private List<String> retainedClasses() {
      return List.copyOf(retainedClasses);
    }
  }

  private static final class MatRuntime {
    private final Object snapshotFactory;
    private final Class<?> snapshotFactoryClass;
    private final Object progressListener;
    private final ProgressMonitor progressMonitor;
    private final ClassLoader classLoader;
    private final URLClassLoader ownedClassLoader;
    private final Path matInstallHome;
    private final String source;

    private MatRuntime(
        Object snapshotFactory,
        Class<?> snapshotFactoryClass,
        Object progressListener,
        ProgressMonitor progressMonitor,
        ClassLoader classLoader,
        URLClassLoader ownedClassLoader,
        Path matInstallHome,
        String source) {
      this.snapshotFactory = snapshotFactory;
      this.snapshotFactoryClass = snapshotFactoryClass;
      this.progressListener = progressListener;
      this.progressMonitor = progressMonitor;
      this.classLoader = classLoader;
      this.ownedClassLoader = ownedClassLoader;
      this.matInstallHome = matInstallHome;
      this.source = source;
    }

    static MatRuntime load(ProgressMonitor progressMonitor) throws ParseException {
      ClassLoader currentLoader = MatHeapSnapshotReader.class.getClassLoader();
      try {
        return loadFrom(currentLoader, null, null, "application classpath", progressMonitor);
      } catch (ClassNotFoundException e) {
        Optional<MatHomeResolver.MatHome> matHome = MatHomeResolver.fromEnvironment();
        if (matHome.isPresent()) {
          URLClassLoader matClassLoader = new URLClassLoader(
              matHome.get().jarUrls().toArray(URL[]::new),
              currentLoader);
          try {
            return loadFrom(
                matClassLoader,
                matClassLoader,
                installHome(matHome.get()),
                MatHomeResolver.ENV_NAME + "=" + matHome.get().home(),
                progressMonitor);
          } catch (ClassNotFoundException nested) {
            try {
              matClassLoader.close();
            } catch (IOException ignored) {
              // Nothing useful can be done if cleanup fails before parsing starts.
            }
            throw new ParseException("Could not load Eclipse MAT from " + MatHomeResolver.ENV_NAME
                + "=" + matHome.get().home() + ". Verify this directory contains a complete MAT installation.",
                nested);
          }
        }
        throw new ParseException(
            "Eclipse MAT runtime jars are required to parse .hprof files. Set "
                + MatHomeResolver.ENV_NAME
                + " to a MAT installation or update-site plugins directory, or add MAT jars to the runtime classpath.",
            e);
      }
    }

    private static MatRuntime loadFrom(
        ClassLoader classLoader,
        URLClassLoader ownedClassLoader,
        Path matInstallHome,
        String source,
        ProgressMonitor progressMonitor) throws ClassNotFoundException, ParseException {
      Class<?> snapshotFactoryClass =
          Class.forName("org.eclipse.mat.parser.internal.SnapshotFactoryImpl", false, classLoader);
      Class<?> progressListenerClass =
          Class.forName("org.eclipse.mat.util.IProgressListener", false, classLoader);

      configureStandaloneRuntime(classLoader);

      Object snapshotFactory = newInstance(snapshotFactoryClass);
      Object listener = Proxy.newProxyInstance(
          progressListenerClass.getClassLoader(),
          new Class<?>[] {progressListenerClass},
          new ProgressInvocationHandler(progressMonitor));
      progressMonitor.message("Loaded Eclipse MAT from " + source);
      return new MatRuntime(
          snapshotFactory,
          snapshotFactoryClass,
          listener,
          progressMonitor,
          classLoader,
          ownedClassLoader,
          matInstallHome,
          source);
    }

    Object openSnapshot(File file) throws ParseException {
      progressMonitor.message("Opening HPROF with Eclipse MAT");
      try {
        return readIndexedSnapshot(file);
      } catch (ParseException firstFailure) {
        if (matInstallHome == null) {
          throw firstFailure;
        }
        runMatIndexer(file, firstFailure);
        return readIndexedSnapshot(file);
      }
    }

    private Object readIndexedSnapshot(File file) throws ParseException {
      try {
        Class<?> snapshotImplClass =
            Class.forName("org.eclipse.mat.parser.internal.SnapshotImpl", true, classLoader);
        Method readFromFile = snapshotImplClass.getMethod(
            "readFromFile",
            File.class,
            String.class,
            progressListener.getClass().getInterfaces()[0]);
        return invokeMethod(readFromFile, null, file, matIndexPrefix(file), progressListener);
      } catch (ClassNotFoundException | NoSuchMethodException e) {
        throw new ParseException("Could not find Eclipse MAT SnapshotImpl.readFromFile(File, String, IProgressListener)", e);
      }
    }

    private Object parseSnapshotDirectly(File file) throws ParseException {
      for (Method method : snapshotFactoryClass.getDeclaredMethods()) {
        if (method.getName().equals("parse") && method.getParameterCount() == 5) {
          method.setAccessible(true);
          return invokeMethod(
              method,
              snapshotFactory,
              file,
              matIndexPrefix(file),
              Map.of("keep_unreachable_objects", "true"),
              List.of(),
              progressListener);
        }
      }
      throw new ParseException("Could not find Eclipse MAT SnapshotFactoryImpl.parse(File, String, Map, List, IProgressListener)");
    }

    private void runMatIndexer(File file, ParseException firstFailure) throws ParseException {
      Path installHome = matInstallHome.toAbsolutePath().normalize();
      Path launcher = findLauncher(installHome);
      try {
        Path config = Files.createTempDirectory("helix-mat-config-");
        Path workspace = Files.createTempDirectory("helix-mat-workspace-");
        Path log = Files.createTempFile("helix-mat-indexer-", ".log");
        List<String> command = List.of(
            javaBinary().toString(),
            "-Xmx1024m",
            "-jar",
            launcher.toString(),
            "-install",
            installHome.toString(),
            "-configuration",
            config.toString(),
            "-data",
            workspace.toString(),
            "-consolelog",
            "-nosplash",
            "-application",
            "org.eclipse.mat.api.parse",
            file.getAbsolutePath());
        progressMonitor.message("Indexing HPROF with Eclipse MAT runtime");
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
        int exitCode = process.waitFor();
        String output = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);
        if (exitCode != 0) {
          throw new ParseException(
              "Eclipse MAT indexer failed with exit code " + exitCode + ": "
                  + tail(output, 40),
              firstFailure);
        }
      } catch (IOException e) {
        throw new ParseException("Failed to run Eclipse MAT indexer from " + installHome, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ParseException("Interrupted while running Eclipse MAT indexer.", e);
      }
    }

    private Path findLauncher(Path installHome) throws ParseException {
      Path plugins = installHome.resolve("plugins");
      if (!Files.isDirectory(plugins)) {
        throw new ParseException(MatHomeResolver.ENV_NAME
            + " must point to a full MAT installation for HPROF indexing; missing plugins directory under "
            + installHome);
      }
      try (var stream = Files.list(plugins)) {
        return stream
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
            .filter(path -> path.getFileName().toString().endsWith(".jar"))
            .sorted()
            .findFirst()
            .orElseThrow(() -> new ParseException(
                "Could not find org.eclipse.equinox.launcher_*.jar under " + plugins));
      } catch (IOException e) {
        throw new ParseException("Failed to inspect Eclipse MAT plugins directory: " + plugins, e);
      }
    }

    private static Path javaBinary() {
      String executable = System.getProperty("os.name", "")
          .toLowerCase()
          .contains("win")
          ? "java.exe"
          : "java";
      return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static Path installHome(MatHomeResolver.MatHome matHome) {
      Path home = matHome.home();
      if (Files.isDirectory(home.resolve("plugins"))) {
        return home;
      }
      Path parent = home.getParent();
      return parent == null ? home : parent;
    }

    private static String tail(String text, int maxLines) {
      List<String> lines = text.lines().toList();
      int from = Math.max(0, lines.size() - maxLines);
      return String.join(System.lineSeparator(), lines.subList(from, lines.size()));
    }

    boolean supportsExternalQueries() {
      return matInstallHome != null;
    }

    HeapSnapshotData readViaQueryReports(File file) throws ParseException {
      progressMonitor.message("Reading HPROF through Eclipse MAT query reports");
      HistogramReport histogram = parseHistogramReport(runMatQuery(file, "histogram"));
      List<HeapDominatorEntry> dominators = parseDominatorReport(
              runMatQuery(file, "dominator_tree"),
              histogram.totalHeapBytes()).stream()
          .limit(MAX_DOMINATORS)
          .toList();
    List<HeapLeakSuspect> leakSuspects = dominators.stream()
        .filter(dominator -> dominator.heapPercent() >= LEAK_THRESHOLD_PERCENT)
        .map(dominator -> new HeapLeakSuspect(
            dominator.className(),
            dominator.classLoaderName(),
              dominator.retainedBytes(),
              dominator.heapPercent(),
              dominator.retentionPath()))
          .toList();
      DuplicateStringSummary duplicateStrings = parseDuplicateStringReport(
          runMatQuery(file, "group_by_value java.lang.String"));
      FinalizerQueueSummary finalizerQueue = parseFinalizerQueueReport(
          runMatQuery(file, "finalizer_queue -mode HISTOGRAM"));
      List<ByteArrayLeakSuspect> byteArrayLeaks = histogram.entries().stream()
          .filter(entry -> entry.className().equals("byte[]") || entry.className().equals("[B"))
          .filter(entry -> percent(entry.shallowBytes(), histogram.totalHeapBytes()) >= LEAK_THRESHOLD_PERCENT)
          .map(entry -> new ByteArrayLeakSuspect(
              entry.className(),
              entry.instanceCount(),
              entry.shallowBytes(),
              percent(entry.shallowBytes(), histogram.totalHeapBytes()),
              List.of("[GCRoot]", entry.className())))
          .toList();
      List<ClassLoaderLeakSuspect> classLoaderLeaks = parseClassLoaderReport(
          runMatQuery(file, "dominator_tree -groupBy BY_CLASSLOADER"),
          histogram.totalHeapBytes());

      return new HeapSnapshotData(
          histogram.totalHeapBytes(),
          histogram.totalObjects(),
          histogram.totalClasses(),
          0,
          histogram.entries().stream().limit(MAX_HISTOGRAM_ROWS).toList(),
          dominators,
          leakSuspects,
          duplicateStrings,
          finalizerQueue,
          byteArrayLeaks,
          classLoaderLeaks,
          Map.of(
              "mat.version", version(),
              "mat.integration", "query-report"));
    }

    private String runMatQuery(File file, String query) throws ParseException {
      Path zip = queryZip(file);
      try {
        Files.deleteIfExists(zip);
      } catch (IOException e) {
        throw new ParseException("Failed to remove stale MAT query report: " + zip, e);
      }

      Path installHome = matInstallHome.toAbsolutePath().normalize();
      Path launcher = findLauncher(installHome);
      try {
        Path config = Files.createTempDirectory("helix-mat-config-");
        Path workspace = Files.createTempDirectory("helix-mat-workspace-");
        Path log = Files.createTempFile("helix-mat-query-", ".log");
        List<String> command = List.of(
            javaBinary().toString(),
            "-Xmx1024m",
            "-jar",
            launcher.toString(),
            "-install",
            installHome.toString(),
            "-configuration",
            config.toString(),
            "-data",
            workspace.toString(),
            "-consolelog",
            "-nosplash",
            "-application",
            "org.eclipse.mat.api.parse",
            file.getAbsolutePath(),
            "-command=" + query,
            "org.eclipse.mat.api:query");
        progressMonitor.message("Running Eclipse MAT query: " + query);
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
        int exitCode = process.waitFor();
        String output = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);
        if (exitCode != 0) {
          throw new ParseException(
              "Eclipse MAT query '" + query + "' failed with exit code " + exitCode + ": "
                  + tail(output, 40));
        }
        if (!Files.isRegularFile(zip)) {
          throw new ParseException("Eclipse MAT query '" + query + "' did not produce expected report: " + zip);
        }
        return readZipEntry(zip, "index.html");
      } catch (IOException e) {
        throw new ParseException("Failed to run Eclipse MAT query '" + query + "' from " + installHome, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ParseException("Interrupted while running Eclipse MAT query '" + query + "'.", e);
      }
    }

    private static String readZipEntry(Path zip, String entryName) throws IOException, ParseException {
      try (ZipFile zipFile = new ZipFile(zip.toFile())) {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
          throw new ParseException("MAT query report is missing " + entryName + ": " + zip);
        }
        return new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
      }
    }

    private static Path queryZip(File file) {
      String fileName = file.getName();
      int extension = fileName.lastIndexOf('.');
      String stem = extension >= 0 ? fileName.substring(0, extension) : fileName;
      File parent = file.getParentFile();
      return new File(parent == null ? new File(".") : parent, stem + "_Query.zip")
          .toPath()
          .toAbsolutePath()
          .normalize();
    }

    private static HistogramReport parseHistogramReport(String html) {
      List<HeapClassHistogramEntry> entries = new ArrayList<>();
      long totalObjects = 0;
      long totalHeapBytes = 0;
      long totalClasses = 0;
      Matcher rowMatcher = TABLE_ROW.matcher(html);
      while (rowMatcher.find()) {
        String rowClass = rowMatcher.group(1);
        List<String> cells = cells(rowMatcher.group(2));
        if (cells.size() < 3) {
          continue;
        }
        if ("totals".equals(rowClass)) {
          totalClasses = totalEntries(cells.get(0), entries.size());
          totalObjects = parseLong(cells.get(1));
          totalHeapBytes = parseLong(cells.get(2));
          continue;
        }
        String className = firstLine(cells.get(0));
        if (!className.isBlank()) {
          entries.add(new HeapClassHistogramEntry(
              className,
              "<unknown classloader>",
              parseLong(cells.get(1)),
              parseLong(cells.get(2)),
              0));
        }
      }
      if (totalClasses == 0) {
        totalClasses = entries.size();
      }
      if (totalObjects == 0) {
        totalObjects = entries.stream().mapToLong(HeapClassHistogramEntry::instanceCount).sum();
      }
      if (totalHeapBytes == 0) {
        totalHeapBytes = entries.stream().mapToLong(HeapClassHistogramEntry::shallowBytes).sum();
      }
      return new HistogramReport(entries, totalObjects, totalHeapBytes, totalClasses);
    }

    private static List<HeapDominatorEntry> parseDominatorReport(String html, long totalHeapBytes) {
      List<HeapDominatorEntry> dominators = new ArrayList<>();
      Matcher rowMatcher = TABLE_ROW.matcher(html);
      while (rowMatcher.find()) {
        if ("totals".equals(rowMatcher.group(1))) {
          continue;
        }
        List<String> cells = cells(rowMatcher.group(2));
        if (cells.size() < 4) {
          continue;
        }
        String label = firstLine(cells.get(0)).replace("\u00bb", "").trim();
        if (label.isBlank()) {
          continue;
        }
        long retainedBytes = parseLong(cells.get(2));
        double heapPercent = parsePercent(cells.get(3));
        if (heapPercent == 0.0) {
          heapPercent = percent(retainedBytes, totalHeapBytes);
        }
        dominators.add(new HeapDominatorEntry(
            label,
            classNameFromDominatorLabel(label),
            "<unknown classloader>",
            1,
            retainedBytes,
            heapPercent,
            List.of("[GCRoot]", label)));
      }
      return dominators;
    }

    private static DuplicateStringSummary parseDuplicateStringReport(String html) {
      List<DuplicateStringEntry> entries = new ArrayList<>();
      long duplicateValueCount = 0;
      long duplicateInstanceCount = 0;
      long wastedBytes = 0;

      Matcher rowMatcher = TABLE_ROW.matcher(html);
      while (rowMatcher.find()) {
        if ("totals".equals(rowMatcher.group(1))) {
          continue;
        }
        List<String> cells = cells(rowMatcher.group(2));
        if (cells.size() < 3) {
          continue;
        }

        long instances = parseLong(cells.get(1));
        if (instances <= 1) {
          continue;
        }

        long shallowBytes = parseLong(cells.get(2));
        long wastedForValue = wastedDuplicateBytes(instances, shallowBytes);
        duplicateValueCount++;
        duplicateInstanceCount += instances;
        wastedBytes += wastedForValue;
        if (entries.size() < MAX_DUPLICATE_STRING_ROWS) {
          entries.add(new DuplicateStringEntry(
              preview(firstLineKeepingBlank(cells.get(0))),
              instances,
              wastedForValue));
        }
      }

      return new DuplicateStringSummary(
          duplicateValueCount,
          duplicateInstanceCount,
          wastedBytes,
          entries);
    }

    private static FinalizerQueueSummary parseFinalizerQueueReport(String html) {
      long pendingObjects = 0;
      Matcher rowMatcher = TABLE_ROW.matcher(html);
      while (rowMatcher.find()) {
        if ("totals".equals(rowMatcher.group(1))) {
          continue;
        }
        List<String> cells = cells(rowMatcher.group(2));
        if (cells.size() >= 2) {
          pendingObjects += parseLong(cells.get(1));
        }
      }
      return new FinalizerQueueSummary(pendingObjects);
    }

    private static List<ClassLoaderLeakSuspect> parseClassLoaderReport(
        String html,
        long totalHeapBytes) {
      List<ClassLoaderLeakSuspect> suspects = new ArrayList<>();
      Matcher rowMatcher = TABLE_ROW.matcher(html);
      while (rowMatcher.find()) {
        if ("totals".equals(rowMatcher.group(1))) {
          continue;
        }
        List<String> cells = cells(rowMatcher.group(2));
        if (cells.size() < 5) {
          continue;
        }

        String classLoaderName = firstLine(cells.get(0)).replace("\u00bb", "").trim();
        if (classLoaderName.isBlank() || isJdkClassLoader(classLoaderName)) {
          continue;
        }

        long objectCount = parseLong(cells.get(1));
        long retainedBytes = parseLong(cells.get(3));
        double heapPercent = parsePercent(cells.get(4));
        if (heapPercent == 0.0) {
          heapPercent = percent(retainedBytes, totalHeapBytes);
        }
        if (heapPercent >= LEAK_THRESHOLD_PERCENT) {
          suspects.add(new ClassLoaderLeakSuspect(
              classLoaderName,
              retainedBytes,
              heapPercent,
              objectCount,
              List.of(classLoaderName)));
        }
      }
      return suspects;
    }

    private static long wastedDuplicateBytes(long instances, long shallowBytes) {
      if (instances <= 1 || shallowBytes <= 0) {
        return 0;
      }
      long averageBytes = Math.max(1L, shallowBytes / instances);
      return (instances - 1L) * averageBytes;
    }

    private static List<String> cells(String rowHtml) {
      List<String> result = new ArrayList<>();
      Matcher cellMatcher = TABLE_CELL.matcher(rowHtml);
      while (cellMatcher.find()) {
        result.add(text(cellMatcher.group(1)));
      }
      return result;
    }

    private static String firstLine(String text) {
      return text.lines()
          .map(String::trim)
          .filter(line -> !line.isBlank())
          .findFirst()
          .orElse("");
    }

    private static String firstLineKeepingBlank(String text) {
      int lineEnd = text.indexOf('\n');
      String firstLine = lineEnd >= 0 ? text.substring(0, lineEnd) : text;
      return firstLine.trim();
    }

    private static boolean isJdkClassLoader(String classLoaderName) {
      String normalized = classLoaderName.toLowerCase();
      return normalized.equals("<bootstrap>")
          || normalized.equals("<system class loader>")
          || normalized.contains("bootclassloader")
          || normalized.contains("platformclassloader")
          || normalized.contains("appclassloader")
          || normalized.startsWith("jdk.internal.loader.classloaders$");
    }

    private static String text(String html) {
      return decodeHtml(html
              .replaceAll("(?i)<br\\s*/?>", "\n")
              .replaceAll("(?s)<[^>]+>", ""))
          .replace('\u00a0', ' ')
          .trim();
    }

    private static String decodeHtml(String text) {
      return text
          .replace("&raquo;", "\u00bb")
          .replace("&amp;", "&")
          .replace("&lt;", "<")
          .replace("&gt;", ">")
          .replace("&quot;", "\"")
          .replace("&#39;", "'");
    }

    private static long parseLong(String value) {
      String normalized = value.replaceAll("[^0-9-]", "");
      return normalized.isBlank() ? 0L : Long.parseLong(normalized);
    }

    private static double parsePercent(String value) {
      String normalized = value.replace("%", "").replace(",", "").trim();
      return normalized.isBlank() ? 0.0 : Double.parseDouble(normalized);
    }

    private static long totalEntries(String text, long fallback) {
      Matcher matcher = TOTAL_ENTRIES.matcher(text);
      return matcher.find() ? parseLong(matcher.group(1)) : fallback;
    }

    private static String classNameFromDominatorLabel(String label) {
      String cleaned = label;
      if (cleaned.startsWith("class ")) {
        cleaned = cleaned.substring("class ".length());
      }
      int at = cleaned.indexOf(" @ ");
      if (at > 0) {
        cleaned = cleaned.substring(0, at);
      }
      int bracket = cleaned.indexOf('[');
      if (bracket > 0 && cleaned.endsWith("]")) {
        cleaned = cleaned.substring(0, bracket);
      }
      return cleaned.isBlank() ? label : cleaned.trim();
    }

    private record HistogramReport(
        List<HeapClassHistogramEntry> entries,
        long totalObjects,
        long totalHeapBytes,
        long totalClasses) {}

    void dispose(Object snapshot) {
      try {
        for (Method method : snapshotFactoryClass.getMethods()) {
          if (method.getName().equals("dispose") && method.getParameterCount() == 1) {
            method.invoke(snapshotFactory, snapshot);
            return;
          }
        }
        Method dispose = snapshot.getClass().getMethod("dispose");
        dispose.invoke(snapshot);
      } catch (ReflectiveOperationException ignored) {
        // Nothing useful can be done during cleanup.
      }
    }

    String version() {
      Package pkg = snapshotFactoryClass.getPackage();
      String version = pkg == null || pkg.getImplementationVersion() == null
          ? "unknown"
          : pkg.getImplementationVersion();
      return version + " (" + source + ")";
    }

    void close() {
      if (ownedClassLoader == null) {
        return;
      }
      try {
        ownedClassLoader.close();
      } catch (IOException ignored) {
        // Nothing useful can be done during cleanup.
      }
    }

    Collection<?> collection(Object target, String methodName) {
      try {
        Object value = invoke(target, methodName);
        if (value instanceof Collection<?> collection) {
          return collection;
        }
      } catch (ParseException ignored) {
        return List.of();
      }
      return List.of();
    }

    Optional<int[]> intArray(Object target, String methodName) {
      try {
        Object value = invoke(target, methodName);
        if (value instanceof int[] array) {
          return Optional.of(array);
        }
      } catch (ParseException ignored) {
        return Optional.empty();
      }
      return Optional.empty();
    }

    Optional<Long> retainedHeapSizeOfObjects(Object target) {
      for (Method method : target.getClass().getMethods()) {
        if (method.getName().equals("getRetainedHeapSizeOfObjects")
            && method.getParameterCount() == 3) {
          try {
            Object value = invokeMethod(method, target, true, true, progressListener);
            return number(value);
          } catch (ParseException ignored) {
            return Optional.empty();
          }
        }
      }
      return Optional.empty();
    }

    Optional<Long> longValue(Object target, String methodName) {
      try {
        return number(invoke(target, methodName));
      } catch (ParseException ignored) {
        return Optional.empty();
      }
    }

    Optional<String> stringValue(Object target, String methodName) {
      try {
        Object value = invoke(target, methodName);
        return value == null ? Optional.empty() : Optional.of(value.toString());
      } catch (ParseException ignored) {
        return Optional.empty();
      }
    }

    Object invoke(Object target, String methodName, Object... args) throws ParseException {
      for (Method method : target.getClass().getMethods()) {
        if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
          return invokeMethod(method, target, args);
        }
      }
      throw new ParseException("Could not find method " + methodName + " on " + target.getClass().getName());
    }

    private Object invokeMethod(Method method, Object target, Object... args) throws ParseException {
      Thread thread = Thread.currentThread();
      ClassLoader previousClassLoader = thread.getContextClassLoader();
      try {
        thread.setContextClassLoader(classLoader);
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        throw new ParseException(
            "Eclipse MAT method failed: "
                + method.getDeclaringClass().getName()
                + "."
                + method.getName()
                + ": "
                + cause.getMessage(),
            cause);
      } catch (IllegalAccessException e) {
        throw new ParseException(
            "Eclipse MAT method failed: "
                + method.getDeclaringClass().getName()
                + "."
                + method.getName(),
            e);
      } finally {
        thread.setContextClassLoader(previousClassLoader);
      }
    }

    private static String matIndexPrefix(File file) {
      String fileName = file.getName();
      int extension = fileName.lastIndexOf('.');
      String prefix = extension >= 0
          ? fileName.substring(0, extension + 1)
          : fileName + ".";
      File parent = file.getParentFile();
      return new File(parent == null ? new File(".") : parent, prefix).getAbsolutePath();
    }

    private static void configureStandaloneRuntime(ClassLoader classLoader) throws ParseException {
      try {
        MatExtensionRegistry registry = new MatExtensionRegistry(classLoader);
        registry.install();
        registry.installParserPlugin();
      } catch (ReflectiveOperationException e) {
        throw new ParseException("Could not initialize standalone Eclipse MAT runtime.", e);
      }
    }

    private static Object newInstance(Class<?> type) throws ParseException {
      try {
        return type.getConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        throw new ParseException("Could not instantiate Eclipse MAT type: " + type.getName(), e);
      }
    }

    private static Optional<Long> number(Object value) {
      if (value instanceof Number number) {
        return Optional.of(number.longValue());
      }
      return Optional.empty();
    }
  }

  private static final class MatExtensionRegistry {
    private static final String PARSER_POINT = "org.eclipse.mat.parser.parser";
    private static final String FACTORY_POINT = "org.eclipse.mat.api.factory";
    private static final String NAME_RESOLVER_POINT = "org.eclipse.mat.api.nameResolver";
    private static final String THREAD_RESOLVER_POINT = "org.eclipse.mat.api.threadResolver";
    private static final String COLLECTION_EXTRACTOR_POINT = "org.eclipse.mat.api.collectionExtractorProvider";
    private static final String HPROF_ENHANCER_POINT = "org.eclipse.mat.hprof.enhancer";

    private final ClassLoader classLoader;
    private final Class<?> registryProviderInterface;
    private final Class<?> extensionRegistryInterface;
    private final Class<?> extensionPointInterface;
    private final Class<?> extensionInterface;
    private final Class<?> configurationElementInterface;

    private MatExtensionRegistry(ClassLoader classLoader) throws ClassNotFoundException {
      this.classLoader = classLoader;
      registryProviderInterface =
          Class.forName("org.eclipse.core.runtime.spi.IRegistryProvider", false, classLoader);
      extensionRegistryInterface =
          Class.forName("org.eclipse.core.runtime.IExtensionRegistry", false, classLoader);
      extensionPointInterface =
          Class.forName("org.eclipse.core.runtime.IExtensionPoint", false, classLoader);
      extensionInterface =
          Class.forName("org.eclipse.core.runtime.IExtension", false, classLoader);
      configurationElementInterface =
          Class.forName("org.eclipse.core.runtime.IConfigurationElement", false, classLoader);
    }

    private void install() throws ReflectiveOperationException {
      Class<?> registryFactory =
          Class.forName("org.eclipse.core.runtime.RegistryFactory", true, classLoader);
      Object currentRegistry = registryFactory.getMethod("getRegistry").invoke(null);
      if (currentRegistry != null) {
        return;
      }

      Object extensionRegistry = proxy(
          extensionRegistryInterface,
          new ExtensionRegistryHandler(extensionPoints()));
      Object provider = proxy(registryProviderInterface, (proxy, method, args) -> {
        if (method.getName().equals("getRegistry")) {
          return extensionRegistry;
        }
        return proxyDefaultValue(method.getReturnType());
      });
      registryFactory.getMethod("setDefaultRegistryProvider", registryProviderInterface)
          .invoke(null, provider);
    }

    private void installParserPlugin() throws ReflectiveOperationException {
      Class<?> parserPluginClass =
          Class.forName("org.eclipse.mat.parser.internal.ParserPlugin", true, classLoader);
      Object existing = parserPluginClass.getMethod("getDefault").invoke(null);
      if (existing != null) {
        return;
      }

      Object parserPlugin = parserPluginClass.getConstructor().newInstance();
      Class<?> parserRegistryClass =
          Class.forName("org.eclipse.mat.parser.internal.util.ParserRegistry", true, classLoader);
      Object parserRegistry = parserRegistryClass.getConstructor(
              Class.forName("org.eclipse.core.runtime.dynamichelpers.IExtensionTracker", false, classLoader))
          .newInstance((Object) null);

      java.lang.reflect.Field pluginField = parserPluginClass.getDeclaredField("plugin");
      pluginField.setAccessible(true);
      pluginField.set(null, parserPlugin);

      java.lang.reflect.Field registryField = parserPluginClass.getDeclaredField("registry");
      registryField.setAccessible(true);
      registryField.set(parserPlugin, parserRegistry);
    }

    private Map<String, ExtensionPointSpec> extensionPoints() {
      return Map.of(
          FACTORY_POINT,
          new ExtensionPointSpec(FACTORY_POINT, List.of(extension(
              "org.eclipse.mat.parser",
              "factory",
              List.of(element("factory", Map.of(
                  "impl", "org.eclipse.mat.parser.internal.SnapshotFactoryImpl")))))),
          PARSER_POINT,
          new ExtensionPointSpec(PARSER_POINT, List.of(extension(
              "org.eclipse.mat.hprof",
              "hprof",
              List.of(element(
                  "parser",
                  Map.of(
                      "name", "HPROF binary heap dump",
                      "fileExtension", "hprof,bin,hprof.gz",
                      "indexBuilder", "org.eclipse.mat.hprof.HprofIndexBuilder",
                      "objectReader", "org.eclipse.mat.hprof.HprofHeapObjectReader"),
                  List.of(element("contentTypeBinding", Map.of(
                      "contentTypeId", "org.eclipse.mat.HprofHeapDump")))))))),
          NAME_RESOLVER_POINT,
          new ExtensionPointSpec(NAME_RESOLVER_POINT, List.of(extension(
              "org.eclipse.mat.api",
              "nameResolver",
              resolverElements(List.of(
                  "org.eclipse.mat.inspections.CommonNameResolver$StringResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$StringBufferResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$ThreadResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$ThreadGroupResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$ValueResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$CharArrayResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$ByteArrayResolver",
                  "org.eclipse.mat.inspections.CommonNameResolver$ClassTypeResolver"))))),
          THREAD_RESOLVER_POINT,
          new ExtensionPointSpec(THREAD_RESOLVER_POINT, List.of(extension(
              "org.eclipse.mat.api",
              "threadResolver",
              resolverElements(List.of("org.eclipse.mat.inspections.threads.ThreadDetailsResolver"))))),
          COLLECTION_EXTRACTOR_POINT,
          new ExtensionPointSpec(COLLECTION_EXTRACTOR_POINT, List.of(extension(
              "org.eclipse.mat.api",
              "collectionExtractorProvider",
              providerElements(List.of("org.eclipse.mat.internal.collectionextract.KnownCollectionInfo"))))),
          HPROF_ENHANCER_POINT,
          new ExtensionPointSpec(HPROF_ENHANCER_POINT, List.of()));
    }

    private List<ConfigurationElementSpec> resolverElements(List<String> classes) {
      return classes.stream()
          .map(className -> element("resolver", Map.of("impl", className)))
          .toList();
    }

    private List<ConfigurationElementSpec> providerElements(List<String> classes) {
      return classes.stream()
          .map(className -> element("provider", Map.of("impl", className)))
          .toList();
    }

    private ExtensionSpec extension(
        String namespace,
        String simpleIdentifier,
        List<ConfigurationElementSpec> elements) {
      return new ExtensionSpec(namespace, simpleIdentifier, elements);
    }

    private ConfigurationElementSpec element(String name, Map<String, String> attributes) {
      return element(name, attributes, List.of());
    }

    private ConfigurationElementSpec element(
        String name,
        Map<String, String> attributes,
        List<ConfigurationElementSpec> children) {
      return new ConfigurationElementSpec(name, attributes, children);
    }

    private Object extensionPoint(ExtensionPointSpec point) {
      return proxy(extensionPointInterface, (proxy, method, args) -> switch (method.getName()) {
        case "getExtensions" -> extensionArray(point.extensions());
        case "getNamespaceIdentifier" -> namespace(point.id());
        case "getUniqueIdentifier", "getSimpleIdentifier", "getLabel" -> point.id();
        case "isValid" -> true;
        default -> proxyDefaultValue(method.getReturnType());
      });
    }

    private Object extension(ExtensionSpec extension) {
      return proxy(extensionInterface, (proxy, method, args) -> switch (method.getName()) {
        case "getConfigurationElements" -> configurationElementArray(
            extension.elements(),
            proxy,
            extension.namespace());
        case "getNamespaceIdentifier", "getContributorId" -> extension.namespace();
        case "getSimpleIdentifier", "getUniqueIdentifier", "getLabel" -> extension.simpleIdentifier();
        case "isValid" -> true;
        default -> proxyDefaultValue(method.getReturnType());
      });
    }

    private Object configurationElement(
        ConfigurationElementSpec element,
        Object declaringExtension,
        String namespace) {
      return proxy(configurationElementInterface, (proxy, method, args) -> switch (method.getName()) {
        case "getName" -> element.name();
        case "getAttribute" -> element.attributes().get((String) args[0]);
        case "getChildren" -> children(element, declaringExtension, namespace, args);
        case "getDeclaringExtension" -> declaringExtension;
        case "getNamespaceIdentifier", "getContributorId" -> namespace;
        case "createExecutableExtension" -> createExecutableExtension(element, (String) args[0]);
        case "isValid" -> true;
        default -> proxyDefaultValue(method.getReturnType());
      });
    }

    private Object children(
        ConfigurationElementSpec element,
        Object declaringExtension,
        String namespace,
        Object[] args) {
      String name = args == null || args.length == 0 ? null : (String) args[0];
      List<ConfigurationElementSpec> children = element.children().stream()
          .filter(child -> name == null || child.name().equals(name))
          .toList();
      return configurationElementArray(children, declaringExtension, namespace);
    }

    private Object createExecutableExtension(ConfigurationElementSpec element, String attribute)
        throws ReflectiveOperationException {
      String className = element.attributes().get(attribute);
      if (className == null || className.isBlank()) {
        return null;
      }
      Class<?> type = Class.forName(className, true, classLoader);
      return type.getConstructor().newInstance();
    }

    private String namespace(String extensionPoint) {
      int lastDot = extensionPoint.lastIndexOf('.');
      return lastDot < 0 ? extensionPoint : extensionPoint.substring(0, lastDot);
    }

    private Object proxy(Class<?> type, InvocationHandler handler) {
      return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private Object extensionArray(List<ExtensionSpec> extensions) {
      Object array = java.lang.reflect.Array.newInstance(extensionInterface, extensions.size());
      for (int i = 0; i < extensions.size(); i++) {
        java.lang.reflect.Array.set(array, i, extension(extensions.get(i)));
      }
      return array;
    }

    private Object configurationElementArray(
        List<ConfigurationElementSpec> elements,
        Object declaringExtension,
        String namespace) {
      Object array = java.lang.reflect.Array.newInstance(configurationElementInterface, elements.size());
      for (int i = 0; i < elements.size(); i++) {
        java.lang.reflect.Array.set(
            array,
            i,
            configurationElement(elements.get(i), declaringExtension, namespace));
      }
      return array;
    }

    private Object proxyDefaultValue(Class<?> returnType) {
      if (returnType == boolean.class) {
        return false;
      }
      if (returnType == int.class || returnType == short.class || returnType == byte.class) {
        return 0;
      }
      if (returnType == long.class) {
        return 0L;
      }
      if (returnType == double.class) {
        return 0.0;
      }
      if (returnType == float.class) {
        return 0.0f;
      }
      if (returnType == char.class) {
        return '\0';
      }
      if (returnType.isArray()) {
        return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
      }
      return null;
    }

    private final class ExtensionRegistryHandler implements InvocationHandler {
      private final Map<String, ExtensionPointSpec> extensionPoints;

      private ExtensionRegistryHandler(Map<String, ExtensionPointSpec> extensionPoints) {
        this.extensionPoints = extensionPoints;
      }

      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
          case "getExtensionPoint" -> extensionPoint(extensionPoints.getOrDefault(
              (String) args[0],
              new ExtensionPointSpec((String) args[0], List.of())));
          case "getConfigurationElementsFor" -> configurationElementsFor(args);
          case "isMultiLanguage" -> false;
          case "isValid" -> true;
          default -> proxyDefaultValue(method.getReturnType());
        };
      }

      private Object configurationElementsFor(Object[] args) {
        String pointId = args == null || args.length == 0 ? "" : (String) args[0];
        ExtensionPointSpec point = extensionPoints.getOrDefault(
            pointId,
            new ExtensionPointSpec(pointId, List.of()));
        List<ConfigurationElementSpec> elements = point.extensions().stream()
            .flatMap(extension -> extension.elements().stream())
            .toList();
        Object declaringExtension = point.extensions().isEmpty()
            ? null
            : extension(point.extensions().getFirst());
        String namespace = point.extensions().isEmpty()
            ? namespace(pointId)
            : point.extensions().getFirst().namespace();
        return configurationElementArray(elements, declaringExtension, namespace);
      }
    }

    private record ExtensionPointSpec(String id, List<ExtensionSpec> extensions) {}

    private record ExtensionSpec(
        String namespace,
        String simpleIdentifier,
        List<ConfigurationElementSpec> elements) {}

    private record ConfigurationElementSpec(
        String name,
        Map<String, String> attributes,
        List<ConfigurationElementSpec> children) {}
  }

  private static final class ProgressInvocationHandler implements InvocationHandler {
    private final ProgressMonitor progressMonitor;

    private ProgressInvocationHandler(ProgressMonitor progressMonitor) {
      this.progressMonitor = progressMonitor == null ? ProgressMonitor.noop() : progressMonitor;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      return switch (method.getName()) {
        case "beginTask" -> {
          progressMonitor.beginTask(String.valueOf(args[0]), ((Number) args[1]).longValue());
          yield null;
        }
        case "subTask" -> {
          progressMonitor.message(String.valueOf(args[0]));
          yield null;
        }
        case "worked" -> {
          progressMonitor.worked(((Number) args[0]).longValue());
          yield null;
        }
        case "done" -> {
          progressMonitor.complete();
          yield null;
        }
        case "isCanceled" -> progressMonitor.isCancelled();
        case "sendUserMessage" -> {
          if (args != null && args.length >= 2) {
            progressMonitor.message(String.valueOf(args[1]));
          }
          yield null;
        }
        case "toString" -> "Helix MAT progress bridge";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> defaultValue(method.getReturnType());
      };
    }

    private static Object defaultValue(Class<?> returnType) {
      if (returnType == boolean.class) {
        return false;
      }
      if (returnType == int.class || returnType == short.class || returnType == byte.class) {
        return 0;
      }
      if (returnType == long.class) {
        return 0L;
      }
      if (returnType == double.class) {
        return 0.0;
      }
      if (returnType == float.class) {
        return 0.0f;
      }
      if (returnType == char.class) {
        return '\0';
      }
      return null;
    }
  }
}
