package cooplauncher

import java.nio.file.Files
import scala.sys.process.{Process, ProcessLogger}

class GameRunnerSuite extends munit.FunSuite:
  private val Quiet = ProcessLogger(_ => ())

  test("game output is captured to the log file instead of the terminal") {
    val dir = Files.createTempDirectory("cooprun")
    val script = dir.resolve("fakegame.sh").toFile
    Files.write(script.toPath, "#!/bin/sh\necho one\necho two >&2\necho three\n".getBytes)
    Process(Seq("chmod", "+x", script.getAbsolutePath)).!(Quiet)

    val appId = "999999998"
    val log = new java.io.File(GameRunner.logDir, s"$appId.log")
    log.delete()

    GameRunner.launchAndWait(script.getAbsolutePath, appId) match
      case Left(err) => fail(s"launch failed: $err")
      case Right(()) => ()

    val content = new String(Files.readAllBytes(log.toPath), "UTF-8")
    assert(content.contains("one"), s"log missing stdout: '$content'")
    assert(content.contains("two"), s"log missing stderr: '$content'")
    assert(content.contains("three"), s"log incomplete: '$content'")
  }

  test("launch reports failure for missing binary") {
    assert(GameRunner.launchAndWait("/nonexistent/path/to/binary", "999999997").isLeft)
  }

  test("nonzero game exit is reported") {
    val dir = Files.createTempDirectory("cooprun2")
    val script = dir.resolve("failgame.sh").toFile
    Files.write(script.toPath, "#!/bin/sh\nexit 3\n".getBytes)
    Process(Seq("chmod", "+x", script.getAbsolutePath)).!(Quiet)
    GameRunner.launchAndWait(script.getAbsolutePath, "999999996") match
      case Left(err) => assert(err.contains("3"))
      case Right(()) => fail("expected failure")
  }
