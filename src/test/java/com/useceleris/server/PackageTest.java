package com.useceleris.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The public surface is written down once, and the package has no way to print or log. */
class PackageTest {
  private static final List<String> EXPORTED_SURFACE =
      List.of(
          "class ChannelScope",
          "class Claims",
          "class Claims.Builder",
          "class Replay",
          "class SegmentPermissions",
          "class Signer",
          "class SignerOptions",
          "class SignerOptions.Builder",
          "constructor SegmentClaim(String, boolean, boolean)",
          "interface ClaimsFunction",
          "method Claims.Builder.allowEcho(boolean): Claims.Builder",
          "method Claims.Builder.build(): Claims",
          "method Claims.Builder.channels(ChannelScope): Claims.Builder",
          "method Claims.Builder.permissions(SegmentPermissions): Claims.Builder",
          "method Claims.Builder.reference(String): Claims.Builder",
          "method Claims.Builder.replay(Replay): Claims.Builder",
          "method ClaimsFunction.claimsFor(CredentialRequest): CompletionStage",
          "method SegmentClaim.equals(Object): boolean",
          "method SegmentClaim.hashCode(): int",
          "method SegmentClaim.read(): boolean",
          "method SegmentClaim.segmentId(): String",
          "method SegmentClaim.toString(): String",
          "method SegmentClaim.write(): boolean",
          "method Signer.credentialProvider(ClaimsFunction): CredentialProvider",
          "method Signer.sign(Claims): Credentials",
          "method SignerOptions.Builder.build(): SignerOptions",
          "method SignerOptions.Builder.clientId(String): SignerOptions.Builder",
          "method SignerOptions.Builder.clock(Clock): SignerOptions.Builder",
          "method SignerOptions.Builder.signingSecret(String): SignerOptions.Builder",
          "method SignerOptions.Builder.toString(): String",
          "method SignerOptions.toString(): String",
          "record SegmentClaim",
          "static method ChannelScope.all(): ChannelScope",
          "static method ChannelScope.restricted(String...): ChannelScope",
          "static method Claims.builder(): Claims.Builder",
          "static method Replay.backlog(): Replay",
          "static method Replay.lookback(Duration): Replay",
          "static method Replay.none(): Replay",
          "static method SegmentPermissions.all(boolean, boolean): SegmentPermissions",
          "static method SegmentPermissions.restricted(SegmentClaim...): SegmentPermissions",
          "static method Signer.create(SignerOptions): Signer",
          "static method SignerOptions.builder(): SignerOptions.Builder");

  private static final List<String> PRINTING_OR_LOGGING =
      List.of(
          "System.out",
          "System.err",
          "printStackTrace",
          "dumpStack",
          "java.util.logging",
          "System.Logger",
          "System.getLogger");

  private static Path compiledClasses() throws URISyntaxException {
    return Path.of(Signer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  } // end method compiledClasses

  @Test
  void exportedSurfaceIsPinned() throws Exception {
    List<String> surface = new ArrayList<>();
    List<Path> classFiles;

    try (Stream<Path> files = Files.list(compiledClasses().resolve("com/useceleris/server"))) {
      classFiles = files.filter(file -> file.toString().endsWith(".class")).toList();
    }

    for (Path classFile : classFiles) {
      String fileName = classFile.getFileName().toString();
      Class<?> type =
          Class.forName(
              Signer.class.getPackageName() + "." + fileName.substring(0, fileName.length() - 6));

      if (exported(type)) {
        addMembers(surface, type);
      }
    }

    surface.sort(null);
    assertEquals(EXPORTED_SURFACE, surface, String.join("\n", surface));
  } // end method exportedSurfaceIsPinned

  @Test
  void theModuleExportsOnePackageAndRequiresOnlyTheClient() throws Exception {
    ModuleDescriptor descriptor;

    try (InputStream input = Files.newInputStream(compiledClasses().resolve("module-info.class"))) {
      descriptor = ModuleDescriptor.read(input);
    }

    assertEquals("com.useceleris.server", descriptor.name());
    assertEquals(
        Set.of("com.useceleris.server"),
        descriptor.exports().stream()
            .filter(exports -> !exports.isQualified())
            .map(ModuleDescriptor.Exports::source)
            .collect(Collectors.toSet()));
    assertEquals(1, descriptor.exports().size());
    assertEquals(
        Set.of("java.base", "com.useceleris.client transitive"),
        descriptor.requires().stream()
            .map(
                requires ->
                    requires.name()
                        + (requires
                                .modifiers()
                                .contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)
                            ? " transitive"
                            : ""))
            .collect(Collectors.toSet()));
    assertTrue(descriptor.opens().isEmpty() && descriptor.uses().isEmpty());
    assertTrue(descriptor.provides().isEmpty());
  } // end method theModuleExportsOnePackageAndRequiresOnlyTheClient

  // The signing secret never leaves a trusted server: the package has no way to print or log.
  @Test
  void packageNeitherPrintsNorLogs() throws IOException {
    List<Path> sources;

    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      sources = files.filter(file -> file.toString().endsWith(".java")).toList();
    }

    assertTrue(sources.size() > 5, "main sources not found");

    for (Path source : sources) {
      String text = Files.readString(source, StandardCharsets.UTF_8);

      for (String forbidden : PRINTING_OR_LOGGING) {
        assertTrue(!text.contains(forbidden), source + " uses " + forbidden);
      }
    }
  } // end method packageNeitherPrintsNorLogs

  private static boolean exported(Class<?> type) {
    Class<?> enclosing = type.getEnclosingClass();

    return Modifier.isPublic(type.getModifiers()) && (enclosing == null || exported(enclosing));
  } // end method exported

  private static void addMembers(List<String> surface, Class<?> type) {
    String kind = type.isRecord() ? "record" : type.isInterface() ? "interface" : "class";
    surface.add(kind + " " + name(type));

    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (Modifier.isPublic(constructor.getModifiers()) && !constructor.isSynthetic()) {
        surface.add("constructor " + name(type) + parameters(constructor));
      }
    }

    for (Method method : type.getDeclaredMethods()) {
      int modifiers = method.getModifiers();

      if (Modifier.isPublic(modifiers) && !method.isSynthetic() && !method.isBridge()) {
        surface.add(
            (Modifier.isStatic(modifiers) ? "static method " : "method ")
                + name(type)
                + "."
                + method.getName()
                + parameters(method)
                + ": "
                + name(method.getReturnType()));
      }
    }

    for (Field field : type.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers()) && !field.isSynthetic()) {
        surface.add("field " + name(type) + "." + field.getName());
      }
    }
  } // end method addMembers

  private static String parameters(Executable executable) {
    List<String> names =
        Arrays.stream(executable.getParameterTypes()).map(PackageTest::name).toList();

    if (executable.isVarArgs()) {
      String last = names.get(names.size() - 1);
      names = new ArrayList<>(names);
      names.set(names.size() - 1, last.substring(0, last.length() - 2) + "...");
    }

    return "(" + String.join(", ", names) + ")";
  } // end method parameters

  private static String name(Class<?> type) {
    if (type.isArray()) {
      return name(type.getComponentType()) + "[]";
    }

    Class<?> enclosing = type.getEnclosingClass();

    return enclosing == null ? type.getSimpleName() : name(enclosing) + "." + type.getSimpleName();
  } // end method name
} // end class PackageTest
