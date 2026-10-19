package cooplauncher

import java.nio.file.Files

class SelfPathSuite extends munit.FunSuite:

  test("quote is POSIX-safe") {
    assertEquals(SelfPath.quote("/a b/java"), "'/a b/java'")
    assertEquals(SelfPath.quote("it's"), "'it'\\''s'")
    assertEquals(SelfPath.quote("/plain"), "'/plain'")
  }

  test("dev JVM runs get a self-reproducing wrapper script as the launch option") {
    val dir = Files.createTempDirectory("coopwrap")
    val line = LauncherConfig.wrapLaunchOptions(dir).get
    assert(line.endsWith(" --wrap %command%"), line)
    assert(line.startsWith("'"), s"first token should be quoted: $line")

    val script = dir.resolve("dev-wrapper.sh")
    assert(Files.exists(script))
    assert(script.toFile.canExecute, "dev wrapper must be executable")
    val content = new String(Files.readAllBytes(script), "UTF-8")
    assert(content.startsWith("#!/bin/sh\nexec "), content)
    assert(content.endsWith("\"$@\"\n"), s"wrapper must forward Steam's args: $content")

    // The line must point at the wrapper script, not at the bare executable.
    // This is the regression test for Steam being told to run bare `java`
    // with no jar, which silently did nothing.
    val firstToken = line.stripPrefix("'").takeWhile(_ != '\'')
    assertEquals(firstToken, script.toString)
    assert(firstToken != SelfPath.get().get, "should not reference the executable alone")
  }
