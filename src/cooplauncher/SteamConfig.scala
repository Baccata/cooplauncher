package cooplauncher

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.sys.process.{Process, ProcessLogger}

/** Sets a game's Steam launch options by editing Steam's per-account
  * localconfig.vdf (userdata/<account>/config/localconfig.vdf) — the only
  * place Steam keeps them, and what makes the donor trick work without
  * touching game files. Steam reads this file at startup, so an external edit
  * only applies on the next Steam start (and a running Steam may overwrite it
  * when it exits — hence the backup and the warning).
  */
object SteamConfig:

  private val Quiet = ProcessLogger(_ => ())

  def localConfigPaths(steamRoot: File): List[Path] =
    val userdata = new File(steamRoot, "userdata")
    Option(userdata.listFiles()).getOrElse(Array.empty[File]).toList
      .filter(_.isDirectory)
      .map(acct => new File(acct, "config/localconfig.vdf"))
      .filter(_.isFile)
      .map(_.toPath)

  def steamRunning: Boolean =
    try Process(Seq("pgrep", "-x", "steam")).! == 0
    catch case _: Exception => false

  /** Asks the running Steam client to exit gracefully (`steam -shutdown`)
    * and returns whether the process is truly gone before the wait runs out.
    * (Timeout is overridable for tests.)
    */
  def shutdownSteam(): Boolean =
    try Process(Seq("steam", "-shutdown")).!(Quiet)
    catch case _: Exception => ()
    val timeout = sys.env.get("COOP_STEAM_SHUTDOWN_TIMEOUT_MS")
      .flatMap(_.toLongOption)
      .getOrElse(60L * 1000)
    val end = System.currentTimeMillis() + timeout
    while steamRunning && System.currentTimeMillis() < end do Thread.sleep(400)
    !steamRunning

  /** Starts the Steam client detached from this process, so it survives the
    * launcher (and its terminal) exiting.
    */
  def startSteam(): Either[String, Unit] =
    try
      Process(Seq("sh", "-c", "nohup steam >/dev/null 2>&1 &")).run(Quiet)
      Right(())
    catch
      case e: Exception => Left(if e.getMessage != null then e.getMessage else e.toString)

  /** The game's LaunchOptions from the first account config that has an entry. */
  def getLaunchOptions(steamRoot: File, appId: String): Option[String] =
    localConfigPaths(steamRoot).iterator
      .flatMap { p =>
        Vdf.parseDocument(read(p)).flatMap(
          _.at(List("Software", "Valve", "Steam", "apps", appId, "LaunchOptions"))).iterator
      }
      .collect { case VdfNode.Str(s) => s }
      .nextOption()

  /** True when the game's launch options route through our --wrap wrapper. */
  def isWrapped(steamRoot: File, appId: String): Boolean =
    getLaunchOptions(steamRoot, appId).exists(_.contains("--wrap"))

  /** The compat tool Steam would use for the game, from its per-game
    * Proton settings (empty/absent when the game follows the default).
    */
  def compatToolName(steamRoot: File, appId: String): Option[String] =
    localConfigPaths(steamRoot).iterator
      .flatMap { p =>
        Vdf.parseDocument(read(p)).flatMap(
          _.at(List("Software", "Valve", "Steam", "CompatToolMapping", appId, "name"))).iterator
      }
      .collect { case VdfNode.Str(s) => s }
      .nextOption()

  /** Writes the launch options into every account's localconfig.vdf, backing
    * each file up first. Returns the files that were changed.
    */
  def setLaunchOptions(steamRoot: File, appId: String, options: String): Either[String, List[Path]] =
    val paths = localConfigPaths(steamRoot)
    if paths.isEmpty then
      Left("no Steam user config found (userdata/*/config/localconfig.vdf)")
    else
      val results = paths.map { p =>
        try
          Files.copy(p, p.resolveSibling(p.getFileName.toString + ".cooplauncher.bak"),
            StandardCopyOption.REPLACE_EXISTING)
        catch case _: Exception => ()
        transform(read(p), appId, options) match
          case Right(text) => write(p, text); Right(p)
          case Left(err) => Left(s"${p.getFileName}: $err")
      }
      val oks = results.collect { case Right(p) => p }
      val errs = results.collect { case Left(e) => e }
      if oks.isEmpty then Left(errs.mkString("; "))
      else Right(oks)

  private def read(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def write(p: Path, text: String): Unit =
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))

  /** Public for tests. Parses the VDF into the tree, sets (creating the block
    * if needed) Software/Valve/Steam/apps/<appId>/LaunchOptions, and encodes
    * back. Anything unexpected fails without writing.
    */
  def transform(text: String, appId: String, options: String): Either[String, String] =
    Vdf.parseDocument(text) match
      case None => Left("unrecognized localconfig.vdf: not valid VDF")
      case Some(doc) =>
        val path = List("Software", "Valve", "Steam", "apps")
        doc.at(path) match
          case None =>
            Left("unrecognized localconfig.vdf: no Software/Valve/Steam/apps section")
          case Some(apps: VdfNode.Obj) =>
            val app: Either[String, VdfNode.Obj] = apps.get(appId) match
              case Some(o: VdfNode.Obj) =>
                Right(o.updated("LaunchOptions", VdfNode.Str(options)))
              case Some(_: VdfNode.Str) =>
                Left(s"apps/$appId is not an object")
              case None =>
                Right(VdfNode.Obj(List("LaunchOptions" -> VdfNode.Str(options))))
            app.flatMap(appObj => doc.updated(path :+ appId, appObj)).map(Vdf.encode)
          case Some(_) =>
            Left("unrecognized localconfig.vdf: apps is not an object")

