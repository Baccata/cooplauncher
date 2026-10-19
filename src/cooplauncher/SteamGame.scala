package cooplauncher

final case class SteamGame(
    appId: String,
    name: String,
    gameFolder: String,
    executablePath: Option[String]
)

import java.io.{File, FileInputStream, FileOutputStream, InputStream}
import scala.sys.process.{Process, ProcessIO, ProcessLogger}

object GameRunner:
  def logDir: File =
    val home = System.getProperty("user.home")
    val dir =
      if Platform.isMac then new File(home, "Library/Logs/CoopLauncher")
      else
        sys.env
          .get("XDG_CACHE_HOME")
          .filter(_.nonEmpty)
          .map(d => new File(d))
          .getOrElse(new File(home, ".cache"))
    val logs = new File(dir, if Platform.isMac then "" else "cooplauncher/logs")
    logs.mkdirs()
    logs

  /** Steam games without a native executable (Proton titles) are launched
    * through Steam itself, which handles Proton, compat data and launch
    * options. Directly exec'ing a Windows binary cannot work on Linux.
    */
  def launchViaSteam(appId: String): Either[String, Unit] =
    steamLaunchArgs(appId) match
      case Some(args) =>
        try
          Process(args).run(ProcessLogger(_ => ()))
          Right(())
        catch case e: Exception => Left(if e.getMessage != null then e.getMessage else e.toString)
      case None => Left("Steam not found — set an executable manually")

  private def steamLaunchArgs(appId: String): Option[Seq[String]] =
    if TerminalImages.which("steam") then Some(Seq("steam", "-applaunch", appId))
    else if TerminalImages.which("xdg-open") then
      Some(Seq("xdg-open", s"steam://rungameid/$appId"))
    else None

  def steamAvailable: Boolean = steamLaunchArgs("0").isDefined

  /** Plain-mode launch: records the user's pick, then has Steam start the
    * donor, whose --wrap launch option hands control back to Coop Launcher
    * inside the donor's process tree.
    */
  def requestViaDonor(donor: DonorGame, game: SteamGame): Either[String, String] =
    LauncherConfig.writePendingLaunch(game.appId) match
      case Left(err) => Left(err)
      case Right(_) =>
        launchViaSteam(donor.appId) match
          case Right(()) =>
            Right(s"Starting ${donor.name} — ${game.name} will launch inside it")
          case Left(err) =>
            LauncherConfig.clearPendingLaunch()
            Left(err)

  /** Wrap mode: the caller IS the donor's launch-option process, so the game
    * must run as its child — Steam then attributes the whole tree to the
    * donor and grants it Remote Play Together. Blocks until the game exits.
    */
  def launchAndWait(exe: String, appId: String): Either[String, Unit] =
    val bin = new File(exe)
    if !bin.isFile then Left(s"executable not found: $exe")
    else if !bin.canExecute then Left(s"not executable: $exe")
    else
      val logFile = new File(logDir, s"$appId.log")
      try
        val code = Process(Seq(exe), bin.getParentFile).run(
          new ProcessIO(
            in => try in.close() catch case _: Exception => (),
            is => drainTo(is, logFile),
            is => drainTo(is, logFile)
          )
        ).exitValue()
        if code == 0 then Right(())
        else Left(s"$exe exited with code $code")
      catch case e: Exception => Left(if e.getMessage != null then e.getMessage else e.toString)

  private def drainTo(is: InputStream, log: File): Unit =
    var out: FileOutputStream = null
    try out = new FileOutputStream(log, true)
    catch case _: Exception => ()
    try
      val buf = new Array[Byte](8192)
      var n = is.read(buf)
      while n >= 0 do
        if out != null then
          try out.write(buf, 0, n)
          catch case _: Exception => ()
        n = is.read(buf)
    catch case _: Exception => ()
    finally
      if out != null then try out.close() catch case _: Exception => ()
      try is.close() catch case _: Exception => ()
