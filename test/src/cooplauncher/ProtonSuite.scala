package cooplauncher

import java.nio.file.{Files, Paths}

class ProtonSuite extends munit.FunSuite:

  private def pe(dir: java.nio.file.Path, name: String, body: String = ""): Unit =
    Files.write(dir.resolve(name), ("MZ" + body).getBytes)

  test("findExecutable picks the name-matched game exe, skipping helpers") {
    val dir = Files.createTempDirectory("coopproton")
    pe(dir, "UnityCrashHandler64.exe", "helper")
    pe(dir, "vc_redist.x64.exe", "helper")
    pe(dir, "Risk of Rain 2.exe", "game")
    val game = SteamGame("632360", "Risk of Rain 2", dir.toString, None)
    val found = Proton.findExecutable(game)
    assert(found.isDefined)
    assertEquals(found.get.getName, "Risk of Rain 2.exe")
  }

  test("findExecutable ignores non-PE files and empty folders") {
    val dir = Files.createTempDirectory("coopproton2")
    val game = SteamGame("632360", "Risk of Rain 2", dir.toString, None)
    assertEquals(Proton.findExecutable(game), None)
    Files.write(dir.resolve("readme.txt"), "hello".getBytes)
    Files.write(dir.resolve("fake.exe"), "not a PE".getBytes)
    assertEquals(Proton.findExecutable(game), None)
  }

  private def fakeRoot(name: String): (java.io.File, java.nio.file.Path) =
    val root = Files.createTempDirectory(name)
    val cfg = root.resolve("userdata/999/config/localconfig.vdf")
    Files.createDirectories(cfg.getParent)
    (root.toFile, cfg)

  test("resolve follows the game's CompatToolMapping entry") {
    val (root, cfg) = fakeRoot("coopproton3")
    Files.write(cfg,
      ("\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n" +
      "\t\t\t\t\"CompatToolMapping\"\n\t\t\t\t{\n\t\t\t\t\t\"632360\"\n\t\t\t\t\t{\n" +
      "\t\t\t\t\t\t\"name\"\t\t\"proton_9\"\n\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}").getBytes)
    val p9 = root.toPath.resolve("steamapps/common/Proton 9.0")
    Files.createDirectories(p9)
    Files.write(p9.resolve("proton"), "#!/bin/sh\n".getBytes)
    Files.createDirectories(root.toPath.resolve("steamapps/common/Proton - Experimental"))
    assert(SteamConfig.compatToolName(root, "632360") == Some("proton_9"))
    assertEquals(Proton.resolve(root, "632360").map(_.getName), Some("Proton 9.0"))
  }

  test("resolve without a mapping prefers Experimental, else the newest build") {
    val (root, cfg) = fakeRoot("coopproton4")
    Files.write(cfg, "\"Root\"\n{\n}".getBytes)
    val exp = root.toPath.resolve("steamapps/common/Proton - Experimental")
    Files.createDirectories(exp)
    Files.write(exp.resolve("proton"), "script".getBytes)
    assertEquals(Proton.resolve(root, "632360").map(_.getName),
      Some("Proton - Experimental"))

    val lonely = Files.createTempDirectory("coopproton5")
    for v <- Seq("Proton 8.0", "Proton 10.0") do
      Files.createDirectories(lonely.resolve(s"steamapps/common/$v"))
      Files.write(lonely.resolve(s"steamapps/common/$v/proton"), "script".getBytes)
    assertEquals(Proton.resolve(lonely.toFile, "632360").map(_.getName), Some("Proton 10.0"))
  }

  test("launchAndWait runs the game exe through Proton as our child") {
    val root = Files.createTempDirectory("coopproton6")
    val exe = Files.createTempDirectory("coopproton-dump").resolve("Risk of Rain 2.exe")
    Files.write(exe, "MZfake".getBytes)
    val argsDump = root.resolve("argsdump.txt")
    val proton = root.resolve("steamapps/common/Proton 9.0/proton")
    Files.createDirectories(proton.getParent)
    Files.write(proton,
      ("#!/bin/sh\necho \"$STEAM_COMPAT_DATA_PATH: $STEAM_COMPAT_CLIENT_INSTALL_PATH: \" $@ >> " +
        argsDump + "\n").getBytes)
    proton.toFile.setExecutable(true)
    Files.createDirectories(root.resolve("steamapps/compatdata/632360"))

    val game = SteamGame("632360", "Risk of Rain 2", exe.getParent.toString, None)
    Proton.launchAndWait(root.toFile, game) match
      case Left(err) => fail(s"proton launch failed: $err")
      case Right(()) => ()

    val line = new String(Files.readAllBytes(argsDump), "UTF-8").trim
    assert(line.contains("waitforexitandrun"), line)
    assert(line.contains(exe.toAbsolutePath.toString), line)
    assert(line.contains(s"${root}/steamapps/compatdata/632360"), line)
    assert(line.contains(root.toAbsolutePath.toString), line)
  }

  test("launchAndWait explains a missing prefix instead of failing cryptically") {
    val root = Files.createTempDirectory("coopproton7")
    val exe = root.resolve("Risk of Rain 2.exe")
    Files.write(exe, "MZ".getBytes)
    val proton = root.resolve("steamapps/common/Proton 9.0/proton")
    Files.createDirectories(proton.getParent)
    Files.write(proton, "#!/bin/sh\n".getBytes)
    proton.toFile.setExecutable(true)
    val game = SteamGame("632360", "Risk of Rain 2", root.toString, None)
    Proton.launchAndWait(root.toFile, game) match
      case Left(err) => assert(err.contains("prefix"), err)
      case Right(()) => fail("expected failure: no compatdata prefix")
  }

  test("launchAndWait explains a game folder without any exe") {
    val root = Files.createTempDirectory("coopproton8")
    val folder = root.resolve("steamapps/common/FakeGame")
    Files.createDirectories(folder)
    Files.createDirectories(root.resolve("steamapps/compatdata/632360"))
    val game = SteamGame("632360", "Risk of Rain 2", folder.toString, None)
    Proton.launchAndWait(root.toFile, game) match
      case Left(err) => assert(err.contains("no Windows executable"), err)
      case Right(()) => fail("expected failure: no exe")
  }
