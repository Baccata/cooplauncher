package cooplauncher

final class RgbImage(val width: Int, val height: Int, val rgb: Array[Byte])

object JpegDecoder:
  def decode(data: Array[Byte]): Option[RgbImage] =
    try
      if data.length < 4 || u16(data, 0) != 0xffd8 then None
      else Some(new Decoder(data).run())
    catch case _: Exception => None

  private def u16(d: Array[Byte], i: Int): Int =
    ((d(i) & 0xff) << 8) | (d(i + 1) & 0xff)

  private val ZigZag = Array(
    0, 1, 8, 16, 9, 2, 3, 10,
    17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
  )

  private final class Huff(counts: Array[Int], symbols: Array[Int]):
    private val firstCode = new Array[Int](16)
    private val firstIdx = new Array[Int](16)

    locally {
      var code = 0
      var idx = 0
      var len = 0
      while len < 16 do
        firstCode(len) = code
        firstIdx(len) = idx
        code = (code + counts(len)) << 1
        idx += counts(len)
        len += 1
    }

    def decode(br: BitReader): Int =
      var code = 0
      var len = 0
      var res = -1
      while res < 0 && len < 16 do
        code = (code << 1) | br.readBit()
        val c = counts(len)
        if code >= firstCode(len) && code < firstCode(len) + c then
          res = symbols(firstIdx(len) + code - firstCode(len))
        len += 1
      if res < 0 then throw new IllegalArgumentException("bad huffman code")
      res

  private final class BitReader(d: Array[Byte], var p: Int):
    private var buf = 0
    private var cnt = 0
    private var eof = false

    def readBit(): Int =
      if cnt == 0 then
        if eof then return 1
        refill()
        if cnt == 0 && eof then return 1
      cnt -= 1
      (buf >> cnt) & 1

    def receive(s: Int): Int =
      var v = 0
      var i = 0
      while i < s do
        v = (v << 1) | readBit()
        i += 1
      v

    private def refill(): Unit =
      if p >= d.length then
        eof = true
        return
      val b = d(p) & 0xff
      if b != 0xff then
        buf = b
        cnt = 8
        p += 1
      else
        val nb = if p + 1 < d.length then d(p + 1) & 0xff else 0xd9
        if nb == 0x00 then
          buf = 0xff
          cnt = 8
          p += 2
        else eof = true

    def atRestart: Option[Int] =
      if p + 1 < d.length && (d(p) & 0xff) == 0xff then
        val m = d(p + 1) & 0xff
        if m >= 0xd0 && m <= 0xd7 then Some(m) else None
      else None

    def consumeRestart(): Unit =
      p += 2
      cnt = 0
      buf = 0
      eof = false

    def scanToRestart(): Boolean =
      while p + 1 < d.length do
        if (d(p) & 0xff) == 0xff then
          val m = d(p + 1) & 0xff
          if m >= 0xd0 && m <= 0xd7 then return true
          if m != 0x00 then return false
        p += 1
      false

  private final class Comp(val id: Int, val h: Int, val v: Int, val tq: Int):
    var td = 0
    var ta = 0
    var pred = 0
    var plane: Array[Int] = new Array[Int](0)
    var pw = 0
    var ph = 0

  private final class Decoder(data: Array[Byte]):
    private var pos = 2
    private val quant = Array.fill(4)(new Array[Int](64))
    private val huffDC = new Array[Huff](4)
    private val huffAC = new Array[Huff](4)
    private var width = 0
    private var height = 0
    private var comps: Array[Comp] = Array.empty
    private var restartInterval = 0
    private var mcusX = 0
    private var mcusY = 0
    private var br: BitReader = null
    private val block = new Array[Int](64)
    private val tmpF = new Array[Float](64)
    private val cosT = {
      val t = Array.tabulate(8, 8)((u, x) =>
        math.cos((2 * x + 1) * u * math.Pi / 16).toFloat)
      t
    }
    private val cU = Array.tabulate(8)(u => if u == 0 then 0.7071067811865476f else 1.0f)

    def run(): RgbImage =
      var decoded = false
      while pos < data.length && !decoded do
        if (data(pos) & 0xff) != 0xff then pos += 1
        else
          val m = if pos + 1 < data.length then data(pos + 1) & 0xff else 0xd9
          if m == 0xff then pos += 1
          else
            pos += 2
            m match
              case 0xc0 | 0xc1 => parseSOF()
              case 0xc2 => throw new UnsupportedOperationException("progressive jpeg")
              case 0xc4 => parseDHT()
              case 0xdb => parseDQT()
              case 0xdd =>
                skipLen()
                restartInterval = u16(data, pos - 2)
              case 0xda =>
                parseSOS()
                decodeScan()
                decoded = true
              case 0xd9 => pos = data.length
              case _ => skipLen()
      if width == 0 || height == 0 || comps.isEmpty then
        throw new IllegalArgumentException("no frame")
      buildImage()

    private def skipLen(): Unit =
      val len = u16(data, pos)
      pos += len - 2

    private def parseSOF(): Unit =
      val len = u16(data, pos)
      val precision = data(pos + 2) & 0xff
      if precision != 8 then throw new UnsupportedOperationException("precision " + precision)
      height = u16(data, pos + 3)
      width = u16(data, pos + 5)
      val nc = data(pos + 7) & 0xff
      if nc != 1 && nc != 3 then throw new UnsupportedOperationException(s"$nc components")
      comps = new Array[Comp](nc)
      var i = 0
      while i < nc do
        val off = pos + 8 + i * 3
        val id = data(off) & 0xff
        val hv = data(off + 1) & 0xff
        val h = hv >> 4
        val v = hv & 0xf
        val tq = data(off + 2) & 0xff
        if h < 1 || h > 4 || v < 1 || v > 4 then throw new IllegalArgumentException("bad sampling")
        comps(i) = Comp(id, h, v, tq)
        i += 1
      val hmax = comps.map(_.h).max
      val vmax = comps.map(_.v).max
      mcusX = (width + 8 * hmax - 1) / (8 * hmax)
      mcusY = (height + 8 * vmax - 1) / (8 * vmax)
      for c <- comps do
        c.pw = mcusX * c.h * 8
        c.ph = mcusY * c.v * 8
        c.plane = new Array[Int](c.pw * c.ph)
      pos += len

    private def parseDQT(): Unit =
      val len = u16(data, pos)
      val end = pos + len
      var p = pos + 2
      while p < end do
        val pq = data(p) >> 4
        val tq = data(p) & 0xf
        p += 1
        var k = 0
        while k < 64 do
          val v =
            if pq == 0 then data(p + k) & 0xff
            else ((data(p + k) & 0xff) << 8) | (data(p + k + 1) & 0xff)
          quant(tq)(ZigZag(k)) = v
          k += 1
        p += (if pq == 0 then 64 else 128)
      pos = end

    private def parseDHT(): Unit =
      val len = u16(data, pos)
      val end = pos + len
      var p = pos + 2
      while p < end do
        val tc = data(p) >> 4
        val th = data(p) & 0xf
        p += 1
        val counts = new Array[Int](16)
        var total = 0
        var i = 0
        while i < 16 do
          counts(i) = data(p + i) & 0xff
          total += counts(i)
          i += 1
        p += 16
        val symbols = new Array[Int](total)
        var k = 0
        while k < total do
          symbols(k) = data(p + k) & 0xff
          k += 1
        p += total
        val t = Huff(counts, symbols)
        if tc == 0 then huffDC(th) = t else huffAC(th) = t
      pos = end

    private def parseSOS(): Unit =
      val ns = data(pos + 2) & 0xff
      var p = pos + 3
      var i = 0
      while i < ns do
        val cs = data(p) & 0xff
        val tdta = data(p + 1) & 0xff
        comps.find(_.id == cs) match
          case Some(c) =>
            c.td = tdta >> 4
            c.ta = tdta & 0xf
          case None => throw new IllegalArgumentException("unknown component in scan")
        p += 2
        i += 1
      br = BitReader(data, p + 3)

    private def decodeScan(): Unit =
      val total = mcusX * mcusY
      if total == 0 then return
      var mcu = 0
      var aborted = false
      while mcu < total && !aborted do
        try decodeMcu(mcu % mcusX, mcu / mcusX)
        catch case _: Exception => aborted = true
        mcu += 1
        if !aborted && restartInterval > 0 && mcu % restartInterval == 0 && mcu < total then
          if br.atRestart.isDefined then
            br.consumeRestart()
            for c <- comps do c.pred = 0
          else if br.scanToRestart() then
            br.consumeRestart()
            for c <- comps do c.pred = 0
          else aborted = true

    private def decodeMcu(mx: Int, my: Int): Unit =
      for c <- comps do
        var bv = 0
        while bv < c.v do
          var bh = 0
          while bh < c.h do
            decodeBlock(c)
            idctStore(c, mx * c.h + bh, my * c.v + bv)
            bh += 1
          bv += 1

    private def decodeBlock(c: Comp): Unit =
      java.util.Arrays.fill(block, 0)
      val dc = huffDC(c.td)
      val ac = huffAC(c.ta)
      val qt = quant(c.tq)
      val s0 = dc.decode(br)
      val d0 = if s0 == 0 then 0 else extend(br.receive(s0), s0)
      c.pred += d0
      block(0) = c.pred * qt(0)
      var k = 1
      while k < 64 do
        val rs = ac.decode(br)
        val r = rs >> 4
        val s = rs & 15
        if s == 0 then
          if r == 15 then k += 16 else k = 64
        else
          k += r
          if k > 63 then k = 64
          else
            val z = ZigZag(k)
            block(z) = extend(br.receive(s), s) * qt(z)
            k += 1

    private def extend(v: Int, s: Int): Int =
      if v < (1 << (s - 1)) then v - (1 << s) + 1 else v

    private def idctStore(c: Comp, bx: Int, by: Int): Unit =
      var y = 0
      while y < 8 do
        var x = 0
        while x < 8 do
          var sum = 0f
          var u = 0
          while u < 8 do
            sum += cU(u) * block(y * 8 + u) * cosT(u)(x)
            u += 1
          tmpF(y * 8 + x) = sum * 0.5f
          x += 1
        y += 1
      val stride = c.pw
      val off = by * 8 * stride + bx * 8
      y = 0
      while y < 8 do
        var x = 0
        while x < 8 do
          var sum = 0f
          var u = 0
          while u < 8 do
            sum += cU(u) * tmpF(u * 8 + x) * cosT(u)(y)
            u += 1
          var v = math.round(sum * 0.5f)
          c.plane(off + y * stride + x) = v
          x += 1
        y += 1

    private def buildImage(): RgbImage =
      val rgb = new Array[Byte](width * height * 3)
      val hmax = comps.map(_.h).max
      val vmax = comps.map(_.v).max
      var y = 0
      while y < height do
        var x = 0
        while x < width do
          val i = (y * width + x) * 3
          if comps.length == 1 then
            val g = (comps(0).sample(x, y, hmax, vmax) + 128).toByte
            rgb(i) = g
            rgb(i + 1) = g
            rgb(i + 2) = g
          else
            val yy = comps(0).sample(x, y, hmax, vmax).toFloat + 128f
            val cb = comps(1).sample(x, y, hmax, vmax)
            val cr = comps(2).sample(x, y, hmax, vmax)
            rgb(i) = clamp(yy + 1.402f * cr)
            rgb(i + 1) = clamp(yy - 0.344136f * cb - 0.714136f * cr)
            rgb(i + 2) = clamp(yy + 1.772f * cb)
          x += 1
        y += 1
      RgbImage(width, height, rgb)

    private def clamp(f: Float): Byte =
      var v = math.round(f)
      if v < 0 then v = 0
      if v > 255 then v = 255
      v.toByte

    extension (c: Comp) def sample(x: Int, y: Int, hmax: Int, vmax: Int): Int =
      c.plane((y * c.v / vmax) * c.pw + (x * c.h / hmax))
