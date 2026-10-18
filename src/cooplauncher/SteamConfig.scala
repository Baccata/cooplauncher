package cooplauncher

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.sys.process.Process

/** Sets a game's Steam launch options by editing Steam's per-account
  * localconfig.vdf (userdata/<account>/config/localconfig.vdf) — the only
  * place Steam keeps them, and what makes the donor trick work without
  * touching game files. Steam reads this file at startup, so an external edit
  * only applies on the next Steam start (and a running Steam may overwrite it
  * when it exits — hence the backup and the warning).
  */
object SteamConfig:

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

