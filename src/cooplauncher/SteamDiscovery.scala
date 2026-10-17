package cooplauncher

import java.io.{File, FileInputStream}
import java.nio.file.Files
import java.nio.charset.StandardCharsets

object SteamDiscovery:
  private val ExcludedAppIds = Set(
    "228980",
    "431960",
    "1391110",
    "1628350",
    "1070560",
    "1245040",
    "961940",
    "2180100",
    "1580130"
  )

  private val ExcludeNameFragments = Vector(
    "redistributable",
    "proton",
    "steam linux runtime",
    "steamworks",
    "directx",
    "vcredist",
    "microsoft visual c++",
    "dotnet runtime",
    "physx",
    "openal"
  )

  private val ExeNameBlocklist = Vector(
    "crash",
    "dump",
    "breakpad",
    "reporter",
    "redist",
    "vcredist",
    "unins000",
    "unity_resource"
  )

  private val MachMagics =
    Set(0xfeedfaceL, 0xfeedfacfL, 0xcefaedfeL, 0xcffaedfeL, 0xcafebabeL, 0xbebafecaL)

  def shouldExclude(appId: String, name: String): Boolean =
    ExcludedAppIds.contains(appId) ||
      ExcludeNameFragments.exists(f => name.toLowerCase.contains(f))

  def steamPath(): Option[File] =
    sys.env
      .get("STEAM_ROOT")
      .map(f => new File(f))
      .find(isSteamRoot)
      .orElse(candidatePaths.find(isSteamRoot))

  private def isSteamRoot(f: File): Boolean =
    f.isDirectory && new File(f, "steamapps").isDirectory

  private def candidatePaths: Seq[File] =
    val home = System.getProperty("user.home")
    if Platform.isMac then
      Seq(new File(home, "Library/Application Support/Steam"))
    else
      Seq(
        new File(home, ".steam/steam"),
        new File(home, ".local/share/Steam"),
        new File(home, ".steam/root"),
        new File(home, ".var/app/com.valvesoftware.Steam/data/Steam"),
        new File("/usr/share/steam"),
        new File("/usr/local/share/steam")
      )

  def localIconPath(appId: String): Option[File] =
    steamPath().flatMap { base =>
      val cache = new File(base, "appcache/librarycache")
      val dir = new File(cache, appId)
      Seq(
        new File(dir, "library_hero.jpg"),
        new File(cache, appId + "_library_hero.jpg"),
        new File(dir, "header.jpg"),
        new File(cache, appId + "_header.jpg"),
        new File(dir, "icon.jpg"),
        new File(cache, appId + "_icon.jpg"),
        new File(dir, "library_600x900.jpg"),
        new File(cache, appId + "_library_600x900.jpg")
      ).find(_.isFile)
    }

  def libraries(base: File): List[File] =
    val seen = scala.collection.mutable.LinkedHashSet[File](base)
    val vdf = new File(base, "steamapps/libraryfolders.vdf")
    if vdf.isFile then
      for
        root <- Vdf.parse(readText(vdf)).toSeq
        (_, node) <- root.fields
        obj <- node match
          case o: VdfNode.Obj => Some(o)
          case _              => None
        path <- obj.str("path").toSeq
        f = new File(path)
        if f.isDirectory
      do seen += f
    seen.toList

  def discover(): List[SteamGame] =
    steamPath().toList
      .flatMap(libraries)
      .flatMap { lib =>
        val steamapps = new File(lib, "steamapps")
        val manifests = Option(steamapps.listFiles()).getOrElse(Array.empty[File])
          .filter(f =>
            f.isFile && f.getName.startsWith("appmanifest_") && f.getName.endsWith(".acf"))
        manifests
          .flatMap(m => parseManifest(m, lib))
          .filterNot(g => shouldExclude(g.appId, g.name))
      }
      .sortBy(_.name)

  private def parseManifest(acf: File, lib: File): Option[SteamGame] =
    Vdf.parse(readText(acf)).flatMap { root =>
      for
        appId <- root.str("appid")
        name <- root.str("name")
        dir <- root.str("installdir")
        folder = new File(new File(lib, "steamapps/common"), dir)
        if folder.isDirectory
      yield SteamGame(appId, name, folder.getAbsolutePath, findExecutable(folder, name))
    }

  def findExecutable(folder: File, gameName: String): Option[String] =
    val cands = executableCandidates(folder)
    cands match
      case Nil           => None
      case one :: Nil    => Some(one.getAbsolutePath)
      case _ =>
        val clean = strip(gameName)
        Some(
          cands.maxBy(c => score(clean, strip(baseName(c)))).getAbsolutePath
        )

  private def executableCandidates(folder: File): List[File] =
    val direct = Option(folder.listFiles()).getOrElse(Array.empty[File]).toList
    direct.sortBy(_.getName.toLowerCase).flatMap { f =>
      val n = f.getName.toLowerCase
      if f.isDirectory && n.endsWith(".app") then
        val macDir = new File(f, "Contents/MacOS")
        Option(macDir.listFiles()).getOrElse(Array.empty[File]).toList
          .filter(c => c.isFile && c.canExecute && isNativeBinary(c))
      else if f.isFile && f.canExecute && !ExeNameBlocklist.exists(n.contains) && isRunnable(f) then
        List(f)
      else Nil
    }

  private def isRunnable(f: File): Boolean = isShebang(f) || isNativeBinary(f)

  private def isShebang(f: File): Boolean =
    magic2(f) == 0x2321

  def isNativeBinary(f: File): Boolean =
    val m = magic4(f)
    m == 0x7f454c46L || MachMagics.contains(m)

  private def magic2(f: File): Long =
    try
      val in = new FileInputStream(f)
      try
        val b = new Array[Byte](2)
        val n = in.read(b)
        if n < 2 then 0L
        else ((b(0) & 0xff).toLong << 8) | (b(1) & 0xff).toLong
      finally in.close()
    catch case _: Exception => 0L

  private def magic4(f: File): Long =
    try
      val in = new FileInputStream(f)
      try
        val b = new Array[Byte](4)
        val n = in.read(b)
        if n < 4 then 0L
        else
          ((b(0) & 0xff).toLong << 24) | ((b(1) & 0xff).toLong << 16) |
            ((b(2) & 0xff).toLong << 8) | (b(3) & 0xff).toLong
      finally in.close()
    catch case _: Exception => 0L

  def strip(s: String): String = s.toLowerCase.filter(_.isLetterOrDigit)

  def score(a: String, b: String): Double =
    if a == b then 1.0
    else if a.contains(b) || b.contains(a) then 0.8
    else
      val denom = math.max(a.length, math.max(b.length, 1))
      a.toSet.intersect(b.toSet).size.toDouble / denom

  private def baseName(f: File): String =
    val n = f.getName
    val i = n.lastIndexOf('.')
    if i > 0 then n.substring(0, i) else n

  private def readText(f: File): String =
    new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
