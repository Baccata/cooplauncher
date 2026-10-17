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
