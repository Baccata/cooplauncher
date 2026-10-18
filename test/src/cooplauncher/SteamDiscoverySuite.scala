package cooplauncher

import java.io.File
import java.nio.file.Files
import scala.sys.process.{Process, ProcessLogger}

class SteamDiscoverySuite extends munit.FunSuite:
  private val Quiet = ProcessLogger(_ => ())

  test("shouldExclude matches known ids and name fragments") {
    assert(SteamDiscovery.shouldExclude("228980", "Anything"))
    assert(SteamDiscovery.shouldExclude("123", "Proton 9.0"))
    assert(!SteamDiscovery.shouldExclude("1234", "Hollow Knight"))
  }

  test("strip lowercases and keeps alphanumerics only") {
    assertEquals(SteamDiscovery.strip("Hollow Knight: Ed.!!"), "hollowknighted")
  }

  test("score matches the C# parity semantics") {
    assertEquals(SteamDiscovery.score("retroarch", "retroarch"), 1.0)
    assertEquals(SteamDiscovery.score("retroarch", "retro"), 0.8)
    assertEquals(SteamDiscovery.score("retroarch", "retro"), 0.8)
    assert(SteamDiscovery.score("abc", "xyz") == 0.0)
  }

  test("findExecutable prefers name-matching ELF and skips non-executables") {
    val dir = Files.createTempDirectory("coopdisc").toFile
    def mkExe(name: String, magic: Array[Byte]): File =
      val f = new File(dir, name)
      Files.write(f.toPath, magic)
      f
    val elf = Array(0x7f.toByte, 'E'.toByte, 'L'.toByte, 'F'.toByte)
    val macho = Array(0xcf.toByte, 0xfa.toByte, 0xed.toByte, 0xfe.toByte)
    mkExe("readme.txt", "hello".getBytes)
    mkExe("hollowknight", elf)
    mkExe("hollowknight_helper", elf)
    mkExe("unitycrashhandler", elf)
    mkExe("somescpt.sh", "#!/bin/sh".getBytes)
    Process(Seq("chmod", "+x", dir.getAbsolutePath + "/hollowknight",
      dir.getAbsolutePath + "/hollowknight_helper", dir.getAbsolutePath + "/unitycrashhandler",
      dir.getAbsolutePath + "/somescpt.sh")).!(Quiet)

    val found = SteamDiscovery.findExecutable(dir, "Hollow Knight")
    assert(found.isDefined)
    assertEquals(found.get.endsWith("hollowknight"), true)

    val single = SteamDiscovery.findExecutable(dir, "somescpt")
    assert(single.isDefined)
  }

  test("isNativeBinary detects macho magic") {
    val dir = Files.createTempDirectory("coopdisc2").toFile
    val f = new File(dir, "macbin")
    Files.write(f.toPath, Array(0xcf.toByte, 0xfa.toByte, 0xed.toByte, 0xfe.toByte))
    assert(SteamDiscovery.isNativeBinary(f))
    val g = new File(dir, "plain")
    Files.write(g.toPath, "text".getBytes)
    assert(!SteamDiscovery.isNativeBinary(g))
  }

  test("libraries dedupes a library reached through a symlink") {
    val real = Files.createTempDirectory("cooplib-real").toFile
    val link = new File(real.getParentFile, s"cooplib-link-${System.nanoTime}")
    Files.createSymbolicLink(link.toPath, real.toPath)
    try
      val steamapps = new File(real, "steamapps")
      steamapps.mkdirs()
      val vdf = s""""libraryfolders"
                  |{
                  |  "0"
                  |  {
                  |    "path"    "${real.getAbsolutePath}"
                  |  }
                  |}""".stripMargin
      Files.write(new File(steamapps, "libraryfolders.vdf").toPath, vdf.getBytes)

      val libs = SteamDiscovery.libraries(link)
      assertEquals(libs.length, 1, s"expected one library, got: ${libs.mkString(", ")}")
      assertEquals(libs.head.getCanonicalPath, real.getCanonicalPath)
    finally
      link.delete()
  }

  test("dedupeByAppId keeps one entry per appId, preferring launchable ones") {
    val noExe  = SteamGame("1", "Game A", "/a", None)
    val withExe = SteamGame("1", "Game A", "/b", Some("/b/x"))
    val other  = SteamGame("2", "Game B", "/c", None)
    val out = SteamDiscovery.dedupeByAppId(List(noExe, withExe, other))
    assertEquals(out.map(_.appId), List("1", "2"))
    assertEquals(out.head.executablePath, Some("/b/x"))
  }
