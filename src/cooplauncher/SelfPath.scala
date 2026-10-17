package cooplauncher

import java.io.File
import scala.sys.process.{Process, ProcessLogger}

object SelfPath:
  private val Quiet = ProcessLogger(_ => ())

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
