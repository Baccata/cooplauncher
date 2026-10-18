package cooplauncher

import munit.FunSuite

class SteamConfigSuite extends FunSuite:
  private val T = "\t"
  private val I5 = T * 5
  private val I6 = T * 6

  private def sample(app: String): String =
    List(
      "\"UserLocalConfigStore\"",
      "{",
      T + "\"Software\"",
      T + "{",
      T * 2 + "\"Valve\"",
      T * 2 + "{",
      T * 3 + "\"Steam\"",
      T * 3 + "{",
      T * 4 + "\"apps\"",
      T * 4 + "{",
      app,
      T * 4 + "}",
      T * 3 + "}",
      T * 2 + "}",
      T + "}",
      "}"
    ).mkString("\n")

  private def appBlock(appId: String, body: String*): String =
    (List(I5 + "\"" + appId + "\"", I5 + "{") ++
      body.map(I6 + _) ++ List(I5 + "}")).mkString("\n")

  test("replaces an existing LaunchOptions value") {
    val text = sample(appBlock("1118310",
      "\"LastPlayed\"\t\t\"1784364298\"",
      "\"LaunchOptions\"\t\t\"old options\""))
    val out = SteamConfig.transform(text, "1118310", "/opt/coop --wrap %command%").toOption.get
    assert(out.contains("\"LaunchOptions\"\t\t\"/opt/coop --wrap %command%\""))
    assert(!out.contains("old options"))
    assert(out.contains("\"LastPlayed\"\t\t\"1784364298\""))
    assertEquals(out.linesIterator.size, text.linesIterator.size)
  }

  test("inserts LaunchOptions into an app block that lacks it") {
    val text = sample(appBlock("1118310", "\"LastPlayed\"\t\t\"1784364298\""))
    val out = SteamConfig.transform(text, "1118310", "abc").toOption.get
    assert(out.contains("\"LaunchOptions\"\t\t\"abc\""))
    val ls = out.linesIterator.toList
    val idx = ls.indexWhere(_.contains("\"LaunchOptions\""))
    assert(idx > 0 && ls(idx + 1).trim == "}", "inserted right before the app block's closing brace")
  }

  test("creates the app block when the appId is unknown") {
    val text = sample(appBlock("4183110", "\"LastPlayed\"\t\t\"1\""))
    val out = SteamConfig.transform(text, "1118310", "abc").toOption.get
    assert(out.contains("\"1118310\""))
    assert(out.contains("\"LaunchOptions\"\t\t\"abc\""))
    assert(out.contains("\"4183110\""))
  }

  test("escapes quotes and backslashes in the options value") {
    val text = sample(appBlock("1118310"))
    val out = SteamConfig.transform(text, "1118310", "a\"b\\c").toOption.get
    assert(out.contains("\"LaunchOptions\"\t\t\"a\\\"b\\\\c\""))
  }

  test("rejects a file without the apps section") {
    assertEquals(SteamConfig.transform("\"Root\"\n{\n}", "1", "x"),
      Left("unrecognized localconfig.vdf: no Software/Valve/Steam/apps section"))
  }
