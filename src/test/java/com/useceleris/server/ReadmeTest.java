package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles every Java block of README.md against the test classpath, so the documentation cannot
 * drift from the API.
 *
 * <p>A block that starts with {@code import}, {@code class}, {@code record} or {@code interface}
 * compiles as a file of its own. Any other block becomes the body of a method, in a class whose
 * fields stand for the values the README's prose introduces:
 *
 * <pre>
 * import com.useceleris.client.*;
 * import com.useceleris.server.*;
 * import java.time.*;
 * import java.util.concurrent.*;
 *
 * class Snippet {
 *   Signer signer;
 *   Claims claims;
 *   tools.jackson.databind.ObjectMapper mapper;
 *
 *   void run() throws Exception {
 *     // the block
 *   }
 * }
 * </pre>
 */
class ReadmeTest {
  private static final Pattern JAVA_BLOCK = Pattern.compile("(?s)```java\n(.*?)```");

  private static final Pattern WHOLE_FILE =
      Pattern.compile("(?s)^(import|class|record|interface)\\b.*");

  private static final Pattern TYPE_NAME =
      Pattern.compile("\\b(?:class|record|interface|enum)\\s+(\\w+)");

  private static final String PREAMBLE =
      "import com.useceleris.client.*;\n"
          + "import com.useceleris.server.*;\n"
          + "import java.time.*;\n"
          + "import java.util.concurrent.*;\n"
          + "\n"
          + "class Snippet {\n"
          + "  Signer signer;\n"
          + "  Claims claims;\n"
          + "  tools.jackson.databind.ObjectMapper mapper;\n"
          + "\n"
          + "  void run() throws Exception {\n";

  @Test
  void everyJavaSnippetCompiles(@TempDir Path output) throws IOException {
    Matcher blocks =
        JAVA_BLOCK.matcher(Files.readString(Path.of("README.md"), StandardCharsets.UTF_8));
    List<String> failures = new ArrayList<>();
    int count = 0;

    while (blocks.find()) {
      String block = blocks.group(1);
      count++;

      if (WHOLE_FILE.matcher(block).matches()) {
        Matcher typeName = TYPE_NAME.matcher(block);
        assertTrue(typeName.find(), "no type in README block " + count);
        compile(typeName.group(1), block, output, failures);
      } else {
        compile("Snippet", PREAMBLE + block + "  }\n}\n", output, failures);
      }
    }

    assertTrue(count >= 4, "README has " + count + " Java blocks");
    assertTrue(failures.isEmpty(), String.join("\n\n", failures));
  } // end method everyJavaSnippetCompiles

  private static void compile(String typeName, String source, Path output, List<String> failures)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaFileObject file =
        new SimpleJavaFileObject(
            URI.create("string:///" + typeName + ".java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          } // end method getCharContent
        };

    List<String> options =
        List.of(
            "--release",
            "17",
            "-proc:none",
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            output.toString());

    try (StandardJavaFileManager files =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      if (!compiler.getTask(null, files, diagnostics, options, null, List.of(file)).call()) {
        failures.add(source + "\n" + diagnostics.getDiagnostics());
      }
    }
  } // end method compile
} // end class ReadmeTest
