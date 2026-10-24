package cooplauncher

import java.io.File
import java.nio.file.Files
import scala.sys.process.{Process, ProcessLogger}

object TerminalImages:
  sealed trait Strategy
  case object Chafa extends Strategy
  case object Kitty extends Strategy
  case object Iterm extends Strategy
  case object Sixel extends Strategy
  case object Blocks extends Strategy
  case object Off extends Strategy

  private val Quiet = ProcessLogger(_ => ())

  lazy val strategy: Strategy =
    sys.env.get("COOP_IMG").map(_.toLowerCase) match
      case Some("off")    => Off
      case Some("chafa")  => Chafa
      case Some("kitty")  => Kitty
      case Some("sixel")  => Sixel
      case Some("iterm")  => Iterm
      case Some("blocks") => Blocks
      case _ =>
        val term = sys.env.getOrElse("TERM", "")
        val termProgram = sys.env.getOrElse("TERM_PROGRAM", "")
        if term.contains("kitty") || term.contains("ghostty") then Kitty
        else if termProgram.contains("iTerm") || termProgram.contains("WezTerm") || term
            .contains("wezterm")
        then Iterm
        else if term.contains("sixel") || term.contains("mlterm") then Sixel
        else if which("chafa") then Chafa
        else Blocks

  def which(bin: String): Boolean =
    sys.env.getOrElse("PATH", "").split(':').exists(p =>
      p.nonEmpty && new File(p, bin).canExecute)

  /** Rendered banner art plus the number of terminal rows it occupies:
    * text lines for chafa/blocks output, image cells for the native
    * protocols. Callers reserve exactly this much space.
    */
  final case class Rendered(text: String, rows: Int)

  def renderBanner(
      bytes: Array[Byte],
      cols: Int,
      s: Strategy = strategy,
      maxRows: Int = Int.MaxValue
  ): Option[Rendered] =
    if bytes == null || bytes.isEmpty then None
    else
      s match
        case Off => None
        case Chafa =>
          JpegDecoder.decode(bytes).flatMap { img =>
            viaChafa(bytes, cols, rowsCapped(cols, img, maxRows))
              .map(text => Rendered(text, math.min(text.linesIterator.length, maxRows)))
          }
        case Iterm =>
          JpegDecoder.decode(bytes).map { img =>
            val rows = rowsCapped(cols, img, maxRows)
            Rendered(itermEscape(bytes, rows), rows)
          }
        case Kitty =>
          JpegDecoder.decode(bytes).map { img =>
            val rows = rowsCapped(cols, img, maxRows)
            Rendered(kittyEscape(img, cols, rows), rows)
          }
        case Sixel =>
          JpegDecoder.decode(bytes).map { img =>
            val rows = rowsCapped(cols, img, maxRows)
            Rendered(sixelEscape(resizeSmart(img, cols * 8, rows * 16)), rows)
          }
        case Blocks =>
          JpegDecoder.decode(bytes).map { img =>
            val rows = rowsCapped(cols, img, maxRows)
            Rendered(halfBlocks(resizeSmart(img, cols, rows * 2)), rows)
          }

  def rowsFor(cols: Int, img: RgbImage): Int =
    val r = math.round(cols.toFloat * img.height / img.width / 2f)
    math.min(math.max(r, 4), 22)

  private def rowsCapped(cols: Int, img: RgbImage, maxRows: Int): Int =
    math.min(rowsFor(cols, img), math.max(maxRows, 1))

  def kittyClearImages(): String = "\u001b_Ga=d,d=i\u001b\\"

  private def viaChafa(bytes: Array[Byte], cols: Int, rows: Int): Option[String] =
    if !which("chafa") then None
    else
      val tmp = File.createTempFile("coopimg", ".jpg")
      try
        Files.write(tmp.toPath, bytes)
        val out =
          try
            Process(Seq("chafa", s"--size=${cols}x${rows}", "--colors", "256", tmp.getAbsolutePath))
              .!!(Quiet)
          catch case _: Exception => ""
        val s = out.trim
        if s.isEmpty then None else Some(s)
      finally tmp.delete()

  private def itermEscape(jpeg: Array[Byte], rows: Int): String =
    s"\u001b]1337;File=inline=1;size=${jpeg.length};height=$rows:;base64,${b64(jpeg)}\u0007"

  private def kittyEscape(img: RgbImage, cols: Int, rows: Int): String =
    val payload = b64(img.rgb)
    val sb = new StringBuilder
    val header = s"f=24,s=${img.width},v=${img.height},c=$cols,r=$rows"
    val chunk = 4096
    var i = 0
    var first = true
    while i < payload.length do
      val end = math.min(i + chunk, payload.length)
      val m = if end == payload.length then 0 else 1
      val keys = if first then s"$header,m=$m" else s"m=$m"
      sb ++= "\u001b_G"
      sb ++= keys
      sb += ';'
      sb ++= payload.substring(i, end)
      sb ++= "\u001b\\"
      first = false
      i = end
    sb.result()

  private def sixelEscape(img: RgbImage): String =
    val (palette, idx) = quantize(img, 256)
    val w = img.width
    val h = img.height
    val sb = new StringBuilder
    sb ++= "\u001bPq"
    sb ++= s"\"1;1;$w;$h"
    palette.indices.foreach { ci =>
      val c = palette(ci)
      val r = ((c >> 16) & 255) * 1000 / 255
      val g = ((c >> 8) & 255) * 1000 / 255
      val b = (c & 255) * 1000 / 255
      sb ++= s"#$ci;2;$r;$g;$b"
    }
    var y = 0
    while y < h do
      val bandRows = math.min(6, h - y)
      var colorEmitted = false
      palette.indices.foreach { ci =>
        val buf = new StringBuilder
        var x = 0
        var any = false
        var runChar = -1
        var runLen = 0
        while x < w do
          var bits = 0
          var dy = 0
          while dy < bandRows do
            if idx((y + dy) * w + x) == ci then bits |= 1 << dy
            dy += 1
          val ch = 0x3f + bits
          if bits != 0 then any = true
          if ch == runChar then runLen += 1
          else
            flushRun(buf, runChar, runLen)
            runChar = ch
            runLen = 1
          x += 1
        flushRun(buf, runChar, runLen)
        if any then
          sb ++= "#"
          sb ++= ci.toString
          sb ++= buf.result()
          colorEmitted = true
      }
      sb += '-'
      y += 6
    sb ++= "\u001b\\"
    sb.result()

  private def flushRun(sb: StringBuilder, ch: Int, len: Int): Unit =
    if len > 0 && ch >= 0 then
      if len >= 4 then
        sb ++= "!"
        sb ++= len.toString
        sb += ch.toChar
      else
        var i = 0
        while i < len do
          sb += ch.toChar
          i += 1

  def halfBlocks(img: RgbImage): String =
    val sb = new StringBuilder
    var y = 0
    while y < img.height do
      var x = 0
      while x < img.width do
        val t = (y * img.width + x) * 3
        val b = (math.min(y + 1, img.height - 1) * img.width + x) * 3
        val tr = img.rgb(t) & 0xff; val tg = img.rgb(t + 1) & 0xff; val tb = img.rgb(t + 2) & 0xff
        val br = img.rgb(b) & 0xff; val bg = img.rgb(b + 1) & 0xff; val bb = img.rgb(b + 2) & 0xff
        sb ++= s"\u001b[48;2;$tr;$tg;${tb}m"
        sb ++= s"\u001b[38;2;$br;$bg;${bb}m"
        sb += '\u2584'
        x += 1
      sb ++= "\u001b[0m"
      if y + 2 < img.height then sb += '\n'
      y += 2
    sb.result()

  def resize(img: RgbImage, w: Int, h: Int): RgbImage =
    if img.width == w && img.height == h then img
    else
      val out = new Array[Byte](w * h * 3)
      var y = 0
      while y < h do
        val sy = y * img.height / h
        var x = 0
        while x < w do
          val sx = x * img.width / w
          val s = (sy * img.width + sx) * 3
          val d = (y * w + x) * 3
          out(d) = img.rgb(s)
          out(d + 1) = img.rgb(s + 1)
          out(d + 2) = img.rgb(s + 2)
          x += 1
        y += 1
      RgbImage(w, h, out)

  def resizeSmart(img: RgbImage, w: Int, h: Int): RgbImage =
    if img.width == w && img.height == h then img
    else if img.width >= 2 * w && img.height >= 2 * h then resizeArea(img, w, h)
    else resize(img, w, h)

  def resizeArea(img: RgbImage, w: Int, h: Int): RgbImage =
    val out = new Array[Byte](w * h * 3)
    val sx = img.width.toDouble / w
    val sy = img.height.toDouble / h
    var y = 0
    while y < h do
      val y0 = math.floor(y * sy).toInt
      val y1 = math.min(math.max(math.floor((y + 1) * sy).toInt, y0 + 1), img.height)
      var x = 0
      while x < w do
        val x0 = math.floor(x * sx).toInt
        val x1 = math.min(math.max(math.floor((x + 1) * sx).toInt, x0 + 1), img.width)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var yy = y0
        while yy < y1 do
          var xx = x0
          while xx < x1 do
            val p = (yy * img.width + xx) * 3
            r += img.rgb(p) & 0xff
            g += img.rgb(p + 1) & 0xff
            b += img.rgb(p + 2) & 0xff
            n += 1
            xx += 1
          yy += 1
        val d = (y * w + x) * 3
        out(d) = (r / n).toByte
        out(d + 1) = (g / n).toByte
        out(d + 2) = (b / n).toByte
        x += 1
      y += 1
    RgbImage(w, h, out)

  def quantize(img: RgbImage, maxColors: Int): (Array[Int], Array[Byte]) =
    val n = img.width * img.height
    val px = new Array[Int](n)
    var i = 0
    while i < n do
      px(i) =
        ((img.rgb(i * 3) & 0xff) << 16) | ((img.rgb(i * 3 + 1) & 0xff) << 8) | (img.rgb(
          i * 3 + 2) & 0xff)
      i += 1
    val buckets = scala.collection.mutable.ArrayBuffer[Array[Int]]((0 until n).toArray)
    def channel(v: Int, c: Int): Int = (v >> (16 - 8 * c)) & 255
    var stop = false
    while buckets.size < maxColors && !stop do
      val cand = buckets.zipWithIndex.flatMap { case (b, bi) =>
        if b.length < 2 then None
        else
          var best = 0
          var bestRange = -1
          var c = 0
          while c < 3 do
            var mn = 255; var mx = 0
            var k = 0
            while k < b.length do
              val v = channel(px(b(k)), c)
              if v < mn then mn = v
              if v > mx then mx = v
              k += 1
            val range = mx - mn
            if range > bestRange then
              bestRange = range
              best = c
            c += 1
          if bestRange > 0 then Some((bi, best, bestRange.toLong * b.length)) else None
      }
      if cand.isEmpty then stop = true
      else
        val (bi, ch, _) = cand.maxBy(_._3)
        val b = buckets(bi)
        val sorted = b.sortBy(k => channel(px(k), ch))
        val mid = sorted.length / 2
        buckets(bi) = sorted.take(mid)
        buckets += sorted.drop(mid)
    val palette = buckets.map { b =>
      if b.isEmpty then 0
      else
        var r = 0L; var g = 0L; var bl = 0L
        var k = 0
        while k < b.length do
          val v = px(b(k))
          r += (v >> 16) & 255
          g += (v >> 8) & 255
          bl += v & 255
          k += 1
        (((r / b.length).toInt << 16) | ((g / b.length).toInt << 8) | (bl / b.length).toInt)
    }.toArray
    val idx = new Array[Byte](n)
    i = 0
    while i < buckets.length do
      var k = 0
      val b = buckets(i)
      while k < b.length do
        idx(b(k)) = i.toByte
        k += 1
      i += 1
    (palette, idx)

  def b64(bytes: Array[Byte]): String =
    val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = new StringBuilder((bytes.length + 2) / 3 * 4)
    var i = 0
    while i < bytes.length do
      val b0 = bytes(i) & 0xff
      val b1 = if i + 1 < bytes.length then bytes(i + 1) & 0xff else 0
      val b2 = if i + 2 < bytes.length then bytes(i + 2) & 0xff else 0
      val trip = (b0 << 16) | (b1 << 8) | b2
      sb += alpha((trip >> 18) & 63)
      sb += alpha((trip >> 12) & 63)
      sb += (if i + 1 < bytes.length then alpha((trip >> 6) & 63) else '=')
      sb += (if i + 2 < bytes.length then alpha(trip & 63) else '=')
      i += 3
    sb.result()
