package cooplauncher

import java.nio.file.{Files, Path}

class JpegDecoderSuite extends munit.FunSuite:
  private val FixtureB64 =
    "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQA" +
      "AAABAAAACKADAAQAAAABAAAACAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmA" +
      "CZjs+EJ+/8AAEQgACAAIAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQE" +
      "AAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldY" +
      "WVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk" +
      "5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMR" +
      "BAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdo" +
      "aWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz" +
      "9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8P" +
      "D//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQA" +
      "Af/aAAwDAQACEQMRAD8A+L6KKK/lM/38P//Z"

  private lazy val fixture: Array[Byte] =
    val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val clean = FixtureB64.filter(_ != '=')
    val out = new Array[Byte](clean.length * 3 / 4)
    var bits = 0
    var acc = 0
    var o = 0
    clean.foreach { ch =>
      acc = (acc << 6) | alpha.indexOf(ch)
      bits += 6
      if bits >= 8 then
        bits -= 8
        out(o) = ((acc >> bits) & 0xff).toByte
        o += 1
    }
    out

  test("decodes baseline jpeg dimensions and dominant color") {
    assert(fixture.length > 100, s"fixture too small: ${fixture.length}")
    val img = JpegDecoder.decode(fixture)
    assert(img.isDefined, "decoder returned None")
    val i = img.get
    assertEquals(i.width, 8)
    assertEquals(i.height, 8)
    var r = 0L; var g = 0L; var b = 0L
    var idx = 0
    while idx < i.rgb.length do
      r += i.rgb(idx) & 0xff
      g += i.rgb(idx + 1) & 0xff
      b += i.rgb(idx + 2) & 0xff
      idx += 3
    val n = i.width * i.height
    assert(r / n > 200, s"red channel too low: ${r / n}")
    assert(g / n < 60, s"green channel too high: ${g / n}")
    assert(b / n < 60, s"blue channel too high: ${b / n}")
  }

  test("rejects non-jpeg data") {
    assert(JpegDecoder.decode("not a jpeg".getBytes).isEmpty)
    assert(JpegDecoder.decode(Array.emptyByteArray).isEmpty)
  }

class LauncherConfigSuite extends munit.FunSuite:
  test("config roundtrip through json") {
    val dir = Files.createTempDirectory("coopcfg")
    val path: Path = dir.resolve("launcher_config.json")
    val cfg = LauncherConfig(
      donor = Some(DonorGame("1118310", "RetroArch", Some("/games/r/retroarch"))),
      executableOverrides = Map("570" -> "/games/x/stellaris")
    )
    LauncherConfig.save(cfg, path)
    val back = LauncherConfig.load(path)
    assertEquals(back, cfg)
  }

  test("missing file yields defaults") {
    val dir = Files.createTempDirectory("coopcfg2")
    assertEquals(LauncherConfig.load(dir.resolve("none.json")), LauncherConfig())
  }

  test("legacy config with donorExePath loads without a donor") {
    val dir = Files.createTempDirectory("coopcfg3")
    val path = dir.resolve("launcher_config.json")
    Files.write(
      path,
      """{"donorExePath": "/games/retroarch/retroarch", "executableOverrides": {"570": "/games/x/s"}}""".getBytes)
    val back = LauncherConfig.load(path)
    assertEquals(back, LauncherConfig(executableOverrides = Map("570" -> "/games/x/s")))
  }

  test("pending launch round-trips and is consumed") {
    val path = Files.createTempDirectory("coopcfg4").resolve("pending_launch.json")
    assertEquals(LauncherConfig.consumePendingLaunch(path), None)
    assertEquals(LauncherConfig.writePendingLaunch("632360", path), Right(()))
    assertEquals(LauncherConfig.consumePendingLaunch(path), Some("632360"))
    assertEquals(LauncherConfig.consumePendingLaunch(path), None)
  }

  test("stale pending launches are discarded") {
    val path = Files.createTempDirectory("coopcfg5").resolve("pending_launch.json")
    Files.write(path, """{"appId": "632360", "at": 1000}""".getBytes)
    assertEquals(LauncherConfig.consumePendingLaunch(path), None)
    assert(!Files.exists(path))
  }
