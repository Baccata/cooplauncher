package cooplauncher

import java.io.File

/** Starts Windows-only games through Proton as the wrapper's own child, so
  * they get the donor's Remote Play Together attribution exactly like native
  * ones. Steam normally assembles this environment itself; we reconstruct the
  * essential parts: the per-game compat tool, the wine prefix, and the
  * client-install path.
  */
object Proton:

  /** Name fragments of helper executables that must never be mistaken for the
    * game itself.
    */
  private val ExeBlacklist = Vector(
    "crash", "setup", "unins", "redist", "vcredist", "dxsetup", "dotnet",
    "installer", "patcher", "update", " unity", "benchmark"
  )

  /** The Windows executable Steam would launch: best name match among the
    * game folder's top-level PE files, skipping installers and helpers.
    */
  def findExecutable(game: SteamGame): Option[File] =
    val dir = new File(game.gameFolder)
    val exes = Option(dir.listFiles()).getOrElse(Array.empty[File]).toList
      .filter(f => f.isFile && f.getName.toLowerCase.endsWith(".exe") && isPe(f))
      .filterNot(f => ExeBlacklist.exists(w => f.getName.toLowerCase.contains(w)))
    val clean = SteamDiscovery.strip(game.name)
    exes.maxByOption(f => SteamDiscovery.score(clean, SteamDiscovery.strip(f.getName)))

  private def isPe(f: File): Boolean =
    try
      val in = new java.io.FileInputStream(f)
      try
        val b = new Array[Byte](2)
        val n = in.read(b)
        n == 2 && b(0) == 'M'.toByte && b(1) == 'Z'.toByte
      finally in.close()
    catch case _: Exception => false

  /** The Proton build Steam would use for this game: the per-game compat tool
    * from localconfig.vdf, else the newest installed build (preferring
    * Experimental, which is the Steam Deck default).
    */
  def resolve(steamRoot: File, appId: String): Option[File] =
    val tool = SteamConfig.compatToolName(steamRoot, appId).filter(_.nonEmpty)
    tool.flatMap(dirFor(steamRoot, _)).orElse(fallback(steamRoot))

  private def common(steamRoot: File): File = new File(steamRoot, "steamapps/common")

  private def dirFor(steamRoot: File, tool: String): Option[File] =
    val dirName = tool match
      case "proton_experimental" => "Proton - Experimental"
      case "proton_hotfix"       => "Proton Hotfix"
      case _ =>
        val v = tool.stripPrefix("proton_")
        if v.isEmpty then tool
        else if v.contains(".") then "Proton " + v
        else "Proton " + v + ".0"
    val candidates = Seq(
      new File(common(steamRoot), dirName),
      new File(steamRoot, "steamapps/compattools.d/" + dirName),
      new File(steamRoot, "steamapps/compattools.d/" + tool))
    candidates.find(d => new File(d, "proton").isFile)

  private def fallback(steamRoot: File): Option[File] =
    val builds = Option(common(steamRoot).listFiles()).getOrElse(Array.empty[File]).toList
      .filter(d => d.isDirectory && d.getName.startsWith("Proton") && new File(d, "proton").isFile)
    builds.find(_.getName == "Proton - Experimental").orElse {
      builds.filterNot(_.getName == "Proton - Experimental")
        .foldLeft(Option.empty[File]) { (best, d) =>
          best match
            case Some(b) if newer(numbers(b.getName), numbers(d.getName)) => best
            case _ => Some(d)
        }
    }

  private def numbers(name: String): List[Int] =
    "[0-9]+".r.findAllIn(name).map(_.toInt).toList

  /** True when version list a is newer than list b, comparing each number
    * group with missing groups treated as 0.
    */
  private def newer(a: List[Int], b: List[Int]): Boolean =
    val n = math.max(a.length, b.length)
    val (pa, pb) = (a.padTo(n, 0), b.padTo(n, 0))
    pa.zip(pb).collectFirst(Function.unlift { case (x, y) =>
      if x != y then Some(x > y) else None
    }).getOrElse(false)

  /** Blocks until the game exits. Requires the game to have been run through
    * Steam at least once, so its wine prefix exists.
    */
  def launchAndWait(steamRoot: File, game: SteamGame): Either[String, Unit] =
    for
      exe <- findExecutable(game)
        .toRight(s"no Windows executable found for ${game.name}")
      proton <- resolve(steamRoot, game.appId)
        .toRight("no Proton build found in the Steam library")
      _ <- Either.cond(
        new File(steamRoot, s"steamapps/compatdata/${game.appId}").isDirectory,
        (),
        s"${game.name} has no Proton prefix yet — run it once through Steam, then retry")
      _ <- GameRunner.runChild(
        Seq(new File(proton, "proton").getAbsolutePath, "waitforexitandrun", exe.getAbsolutePath),
        exe.getParentFile,
        game.appId,
        "STEAM_COMPAT_DATA_PATH" -> new File(steamRoot, s"steamapps/compatdata/${game.appId}").getAbsolutePath,
        "STEAM_COMPAT_CLIENT_INSTALL_PATH" -> steamRoot.getAbsolutePath)
    yield ()
