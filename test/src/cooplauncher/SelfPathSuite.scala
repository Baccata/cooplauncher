package cooplauncher

class SelfPathSuite extends munit.FunSuite:

  test("quote is POSIX-safe") {
    assertEquals(SelfPath.quote("/a b/java"), "'/a b/java'")
    assertEquals(SelfPath.quote("it's"), "'it'\\''s'")
    assertEquals(SelfPath.quote("/plain"), "'/plain'")
  }

  test("wrapLaunchOptions reproduces the full running command") {
    val opts = SelfPath.wrapLaunchOptions()
    assert(opts.isDefined)
    assert(opts.get.endsWith(" --wrap %command%"), opts.get)
    assert(opts.get.startsWith("'"), s"first token should be quoted: ${opts.get}")
  }

  test("regression: the launch option carries the running arguments, not just the binary") {
    // A java -jar / scala-cli process must re-attach its arguments; this
    // caught the bug where Steam was told to run bare `java` with no jar.
    val prefix = SelfPath.wrapPrefix().get
    val bin = SelfPath.get().get
    assert(prefix.length > bin.length,
      s"prefix '$prefix' should carry more than the binary alone ('$bin')")
  }
