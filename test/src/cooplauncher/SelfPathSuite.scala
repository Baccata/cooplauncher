package cooplauncher

import java.nio.file.Files

class SelfPathSuite extends munit.FunSuite:

  test("quote is POSIX-safe") {
    assertEquals(SelfPath.quote("/a b/java"), "'/a b/java'")
    assertEquals(SelfPath.quote("it's"), "'it'\\''s'")
    assertEquals(SelfPath.quote("/plain"), "'/plain'")
  }

  test("launch options reproduce the running command by mode") {
    val dir = Files.createTempDirectory("coopwrap")
    val line = LauncherConfig.wrapLaunchOptions(dir).get
    assert(line.endsWith(" --wrap %command%"), line)
    assert(line.startsWith("'"), s"first token should be quoted: $line")

    val tokens = SelfPath.tokens().get
    val firstToken = line.stripPrefix("'").takeWhile(_ != '\'')
    if LauncherConfig.isJvmInvocation(tokens) then
      // JVM invocation: the reproducible command is the freshly written dev
      // wrapper, never a bare `java` — the original bug: Steam ran bare
      // `java` with no jar, which silently did nothing.
      val script = dir.resolve("dev-wrapper.sh")
      assert(Files.exists(script))
      assert(script.toFile.canExecute, "dev wrapper must be executable")
      val content = new String(Files.readAllBytes(script), "UTF-8")
      assert(content.startsWith("#!/bin/"), content)
      assert(content.endsWith("\"$@\"\n"), s"wrapper must forward Steam's args: $content")
      assertEquals(firstToken, script.toString)
      assert(firstToken != tokens.head, "must not reference java alone")
    else
      // A standalone binary (the packaged launcher, or CI's native test
      // driver): its own path plus Steam's placeholder is the faithful
      // reproduction.
      assertEquals(firstToken, tokens.head)
  }

  test("scala-cli .app argv is replayed by executing the .app, not java") {
    // This is the regression test for the 2026-10-19 real-launch failure:
    // Steam ran the wrapper, java read the trailing .app path as a main
    // class, ClassNotFoundException, instant exit, pending never consumed.
    val jdk = Files.createTempDirectory("jdk")
    val bin = jdk.resolve("bin")
    Files.createDirectories(bin)
    val javaExe = bin.resolve("java")
    val app = Files.createTempFile("coop", ".app")
    val content = LauncherConfig.wrapperScript(
      Vector(javaExe.toString, "-Dscala.sources=/x/src", "-cp", "/x/classes", app.toString))
    assert(content.contains("export JAVA_HOME="), content)
    assert(content.contains("export PATH="), content)
    assert(content.contains("\"$@\"\n"), content)
    assert(content.contains(s"exec ${SelfPath.quote(app.toString)}"), content)
    // java itself must not receive the .app path as its main class ever again
    assert(!content.contains(javaExe.toString), content)
  }

  test("a .app that does not exist falls back to verbatim java replay") {
    val content = LauncherConfig.wrapperScript(
      Vector("/opt/jdk/bin/java", "-jar", "/x/missing.app"))
    assert(content.startsWith("#!/bin/sh\nexec "), content)
    assert(content.contains(SelfPath.quote("/opt/jdk/bin/java")), content)
  }

  test("non-.app JVM shape keeps verbatim argv replay") {
    val content =
      LauncherConfig.wrapperScript(Vector("/usr/bin/java", "-jar", "/opt/coop.jar"))
    assertEquals(content,
      "#!/bin/sh\nexec '/usr/bin/java' '-jar' '/opt/coop.jar' \"$@\"\n")
  }
