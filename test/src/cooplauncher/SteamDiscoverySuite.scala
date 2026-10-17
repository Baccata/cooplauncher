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
