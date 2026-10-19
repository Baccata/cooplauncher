package cooplauncher

import java.io.File
import scala.sys.process.{Process, ProcessLogger}

object SelfPath:
  private val Quiet = ProcessLogger(_ => ())

  /** The exact command that is currently running — the executable plus its
    * arguments — so another process can reproduce it. A development run
    * attaches "-jar <jar>" to the java binary; a packaged native binary is a
    * single token.
    */
  def tokens(): Option[Vector[String]] =
    try
      val info = java.lang.ProcessHandle.current().info()
      (opt(info.command), opt(info.arguments)) match
        case (Some(cmd), args) =>
          Some(args match
            case Some(as) if as.nonEmpty =>
              (cmd +: as.toVector).map(absolutizeToken)
            case _ => Vector(cmd))
        case _ => None
    catch case _: Throwable => get().map(p => Vector(p))

  /** POSIX-safe single-quote quoting. */
  def quote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

  private def opt[A](o: java.util.Optional[A]): Option[A] =
    if o.isPresent then Some(o.get) else None

  /** Launch options run with the game folder as working directory, so any
    * argument naming an existing relative path must be resolved now.
    */
  private def absolutizeToken(t: String): String =
    if t.nonEmpty && !t.startsWith("/") && !t.startsWith("-") &&
        new File(t).isFile then new File(t).getAbsolutePath
    else t

  def get(): Option[String] =
    if Platform.isMac then viaPs()
    else
      viaProc().orElse(viaPs())

  private def viaProc(): Option[String] =
    try
      val p = java.nio.file.Files
        .readSymbolicLink(java.nio.file.Paths.get("/proc/self/exe"))
        .toString
      if new File(p).isFile then Some(p) else None
    catch case _: Exception => None

  private def viaPs(): Option[String] =
    try
      val pid = Process(Seq("sh", "-c", "echo $PPID")).!!(Quiet).trim
      if pid.isEmpty then None
      else
        val out = Process(Seq("ps", "-p", pid, "-o", "comm=")).!!(Quiet).trim
        if out.nonEmpty && new File(out).isFile then Some(out)
        else viaLsof(pid)
    catch case _: Exception => None

  private def viaLsof(pid: String): Option[String] =
    try
      val lines = Process(Seq("lsof", "-p", pid)).!!(Quiet).linesIterator.toList
      lines
        .filter(l => l.contains(" txt ") && !l.contains(".dylib") && !l.contains(".so"))
        .map(_.trim.split("\\s+").last)
        .find(f => new File(f).isFile)
    catch case _: Exception => None
