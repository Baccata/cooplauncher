package cooplauncher

class TerminalImagesSuite extends munit.FunSuite:
  private def img(w: Int, h: Int, fill: (Int, Int) => (Int, Int, Int)): RgbImage =
    val rgb = new Array[Byte](w * h * 3)
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        val (r, g, b) = fill(x, y)
        val p = (y * w + x) * 3
        rgb(p) = r.toByte
        rgb(p + 1) = g.toByte
        rgb(p + 2) = b.toByte
        x += 1
      y += 1
    RgbImage(w, h, rgb)

  test("rowsFor keeps aspect ratio and clamps") {
    val banner = img(460, 215, (_, _) => (0, 0, 0))
    assertEquals(TerminalImages.rowsFor(40, banner), 9)
    val hero = img(1920, 620, (_, _) => (0, 0, 0))
    assertEquals(TerminalImages.rowsFor(100, hero), 16)
    val portrait = img(600, 900, (_, _) => (0, 0, 0))
    assert(TerminalImages.rowsFor(100, portrait) <= 22)
    assert(TerminalImages.rowsFor(200, portrait) >= 4)
  }

  test("resizeArea averages source pixels") {
    val halves = img(8, 8, (x, _) => if x < 4 then (0, 0, 0) else (255, 255, 255))
    val out = TerminalImages.resizeArea(halves, 4, 4)
    assertEquals(out.width, 4)
    assertEquals(out.height, 4)
    assertEquals(out.rgb((0 * 4 + 0) * 3) & 0xff, 0)
    assertEquals(out.rgb((3 * 4 + 3) * 3) & 0xff, 255)
  }

  test("resizeSmart uses area filter on large downscale") {
    val big = img(100, 100, (x, _) => if x < 50 then (100, 0, 0) else (200, 0, 0))
    val out = TerminalImages.resizeSmart(big, 10, 10)
    assertEquals(out.width, 10)
    assertEquals(out.rgb((5 * 10 + 4) * 3) & 0xff, 100)
    assertEquals(out.rgb((5 * 10 + 5) * 3) & 0xff, 200)
  }

  test("halfBlocks emits one line per two pixel rows") {
    val im = img(4, 6, (_, _) => (10, 20, 30))
    assertEquals(TerminalImages.halfBlocks(im).linesIterator.count(_ => true), 3)
  }
