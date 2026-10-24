package cooplauncher

import java.nio.file.{Files, Path}

class VdfSuite extends munit.FunSuite:
  test("parses appmanifest with quoted keys, comments and whitespace") {
    val vdf = """"AppState"
                |{
                |  // this is a comment
                |  "appid"      "228980"
                |  "name"       "Steamworks Common Redistributables"
                |  "installdir" "Steamworks Common Redistributables"
                |}
                |""".stripMargin
    val root = Vdf.parse(vdf)
    assert(root.isDefined)
    assertEquals(root.get.str("appid"), Some("228980"))
    assertEquals(root.get.str("name"), Some("Steamworks Common Redistributables"))
    assertEquals(root.get.str("installdir"), Some("Steamworks Common Redistributables"))
  }

  test("parses nested libraryfolders structure") {
    val vdf = """"libraryfolders"
                |{
                |  "0"
                |  {
                |    "path"    "/home/gamer/.local/share/Steam"
                |    "label"   ""
                |  }
                |  "1"
                |  {
                |    "path"    "/mnt/games/SteamLibrary"
                |  }
                |}
                |""".stripMargin
    val root = Vdf.parse(vdf)
    assert(root.isDefined)
    val libs = root.get.fields.collect {
      case (_, o: VdfNode.Obj) => o.str("path")
    }.flatten
    assertEquals(libs, List("/home/gamer/.local/share/Steam", "/mnt/games/SteamLibrary"))
  }

  test("handles escaped quotes and backslashes in values") {
    val vdf = """"root"
                |{
                |  "k" "a \"quoted\\path"
                |}
                |""".stripMargin
    val root = Vdf.parse(vdf)
    assert(root.isDefined)
    assertEquals(root.get.str("k"), Some("a \"quoted\\path"))
  }

  test("malformed input returns None") {
    assertEquals(Vdf.parse("{ broken"), None)
    assertEquals(Vdf.parse("\"unterminated"), None)
    assertEquals(Vdf.parse(""), None)
  }

  test("parseDocument retains the root key") {
    val doc = Vdf.parseDocument("\"AppState\"\n{\n \"appid\" \"228980\"\n}").get
    assertEquals(doc.rootKey, "AppState")
    assertEquals(doc.root.str("appid"), Some("228980"))
  }

  test("encode emits Steam's tab style and round-trips") {
    val doc = Vdf.parseDocument(
      "\"Store\"\n{\n \"k\" \"a \\\"q\\\"\"\n \"sub\"\n {\n  \"x\" \"1\"\n }\n}").get
    val encoded = Vdf.encode(doc)
    assertEquals(Vdf.parseDocument(encoded).get, doc)
    assert(encoded.contains("\"k\"\t\t\"a \\\"q\\\"\""))
    assert(encoded.contains("\t\"sub\"\n\t{\n\t\t\"x\"\t\t\"1\"\n\t}"))
  }

  test("preserves duplicate keys and order through a round-trip") {
    val doc = Vdf.parseDocument("\"r\"\n{\n \"a\" \"1\"\n \"a\" \"2\"\n \"b\" \"3\"\n}").get
    val out = Vdf.encode(doc)
    assertEquals(Vdf.parseDocument(out).get, doc)
    assert(out.indexOf("\"a\"\t\t\"1\"") < out.indexOf("\"a\"\t\t\"2\""))
  }

  test("updated descends the path and fails on non-object intermediates") {
    val doc = Vdf.parseDocument("\"r\"\n{\n \"a\"\n {\n  \"b\" \"1\"\n }\n}").get
    val out = doc.updated(List("a", "b"), VdfNode.Str("2")).toOption.get
    assertEquals(out.root.at(List("a", "b")), Some(VdfNode.Str("2")))
    assertEquals(doc.updated(List("a", "b", "c"), VdfNode.Str("x")),
      Left("b is not an object"))
    assertEquals(doc.updated(List("zzz", "b"), VdfNode.Str("x")),
      Left("missing section zzz"))
  }

  test("key lookup is case-insensitive, like Steam's KeyValues") {
    val doc = Vdf.parseDocument(
      "\"UserLocalConfigStore\"\n{\n \"Software\"\n {\n  \"Valve\"\n  {\n   \"Steam\"\n   {\n    \"Apps\"\n    {\n     \"570\"\n     {\n      \"LastPlayed\" \"1234\"\n     }\n    }\n   }\n  }\n }\n}").get
    assertEquals(doc.at(List("Software", "Valve", "Steam", "apps", "570", "LastPlayed")),
      Some(VdfNode.Str("1234")))
    // merge into the existing app object, exactly as SteamConfig.transform does
    val apps = doc.at(List("Software", "Valve", "Steam", "apps")).collect {
      case o: VdfNode.Obj => o
    }.getOrElse(fail("expected apps object"))
    val app = apps.get("570").collect { case o: VdfNode.Obj => o }
      .getOrElse(fail("expected 570 object"))
    val out = doc
      .updated(List("Software", "Valve", "Steam", "apps", "570"),
        app.updated("LaunchOptions", VdfNode.Str("/opt/coop --wrap %command%")))
      .toOption.get
    val encoded = Vdf.encode(out)
    val back = Vdf.parseDocument(encoded).get
    assertEquals(
      back.at(List("Software", "Valve", "Steam", "Apps", "570", "LaunchOptions")),
      Some(VdfNode.Str("/opt/coop --wrap %command%")))
    // the pre-existing sibling key survives with its original casing
    assertEquals(
      back.at(List("Software", "Valve", "Steam", "Apps", "570", "LastPlayed")),
      Some(VdfNode.Str("1234")))
    assert(encoded.contains("\"Apps\""), "existing key casing must be preserved")
  }
