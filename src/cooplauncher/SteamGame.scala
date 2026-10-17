package cooplauncher

final case class SteamGame(
    appId: String,
    name: String,
    gameFolder: String,
    executablePath: Option[String]
)

import java.io.{File, FileInputStream, FileOutputStream, InputStream}
import scala.sys.process.{Process, ProcessIO}

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

  def launch(exe: String, appId: String): Either[String, Unit] =
    val bin = new File(exe)
    if !bin.isFile then Left(s"executable not found: $exe")
    else if !bin.canExecute then Left(s"not executable: $exe")
    else
      val logFile = new File(logDir, s"$appId.log")
      try
        Process(Seq(exe), bin.getParentFile).run(
          new ProcessIO(
            in => try in.close() catch case _: Exception => (),
            is => drainTo(is, logFile),
            is => drainTo(is, logFile)
          )
        )
        Right(())
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
