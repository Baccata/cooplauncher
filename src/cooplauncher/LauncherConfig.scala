package cooplauncher

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import upickle.default.*

/** A Remote Play Together-capable game picked as the launch host. Purely
  * bookkeeping: Coop Launcher never modifies the game's files.
  */
final case class DonorGame(
    appId: String,
    name: String,
    exePath: Option[String] = None
)

/** One-shot launch intent: the plain TUI records the game the user picked,
  * then asks Steam to start the donor; the --wrap instance spawned by the
  * donor's launch options consumes it and starts the game inside the donor's
  * process tree.
  */
final case class PendingLaunch(appId: String, at: Long)
object PendingLaunch:
  implicit val rw: ReadWriter[PendingLaunch] = macroRW

final case class LauncherConfig(
    donor: Option[DonorGame] = None,
    executableOverrides: Map[String, String] = Map.empty
)

object LauncherConfig:
  implicit val donorRw: ReadWriter[DonorGame] = macroRW
  implicit val rw: ReadWriter[LauncherConfig] = macroRW

  def configDir: Path =
    val home = Paths.get(System.getProperty("user.home"))
    if Platform.isMac then home.resolve("Library/Application Support/CoopLauncher")
    else
      sys.env
        .get("XDG_CONFIG_HOME")
        .filter(_.nonEmpty)
        .map(Paths.get(_))
        .getOrElse(home.resolve(".config"))
        .resolve("cooplauncher")

  def configPath: Path = configDir.resolve("launcher_config.json")

  def pendingPath: Path = configDir.resolve("pending_launch.json")

  def writePendingLaunch(appId: String, path: Path = pendingPath): Either[String, Unit] =
    try
      Files.createDirectories(path.getParent)
      Files.write(path,
        write(PendingLaunch(appId, System.currentTimeMillis())).getBytes(StandardCharsets.UTF_8))
      Right(())
    catch
      case e: Exception => Left(if e.getMessage != null then e.getMessage else e.toString)

  /** Consumes a pending launch: returns its appId when fresh (within
    * maxAgeMs), deletes the file either way.
    */
  def consumePendingLaunch(path: Path = pendingPath,
      maxAgeMs: Long = 5L * 60 * 1000): Option[String] =
    try
      if !Files.exists(path) then None
      else
        val pending =
          read[PendingLaunch](new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
        Files.delete(path)
        val fresh = System.currentTimeMillis() - pending.at <= maxAgeMs
        if fresh then Some(pending.appId) else None
    catch case _: Exception => None

  def clearPendingLaunch(path: Path = pendingPath): Unit =
    try Files.deleteIfExists(path) catch case _: Exception => ()

  def devWrapperPath: Path = configDir.resolve("dev-wrapper.sh")

  /** Development helper: a JVM process (scala-cli run, java -jar from any
    * path) is awkward to reproduce inline in a Steam launch option, so write
    * a small exec script that mirrors the exact current command. The Steam
    * launch option then points at this stable script path, refreshed on
    * every donor launch, so no jar location matters. Returns None (no
    * work needed) for packaged native binaries, which are referenced
    * directly.
    */
  def refreshDevWrapper(dir: Path = configDir): Option[Path] =
    SelfPath.tokens() match
      case Some(ts) if ts.length > 1 =>
        try
          val path = dir.resolve("dev-wrapper.sh")
          Files.createDirectories(dir)
          Files.write(path,
            ("#!/bin/sh\nexec " + ts.map(SelfPath.quote).mkString(" ") + " \"$@\"\n")
              .getBytes(StandardCharsets.UTF_8))
          path.toFile.setExecutable(true)
          Some(path)
        catch case _: Exception => None
      case _ => None

  /** The Steam launch-option text that reproduces the current process:
    * the freshly rewritten dev wrapper script for JVM invocations, or the
    * binary path directly, followed by the wrapper flag and Steam's
    * placeholder for the donor's own command (deliberately not executed).
    */
  def wrapLaunchOptions(dir: Path = configDir): Option[String] =
    SelfPath.tokens() match
      case Some(ts) if ts.length > 1 =>
        refreshDevWrapper(dir).map(p =>
          SelfPath.quote(p.toAbsolutePath.toString) + " --wrap %command%")
      case Some(ts) if ts.nonEmpty =>
        Some(SelfPath.quote(ts.head) + " --wrap %command%")
      case _ => None

  def load(path: Path = configPath): LauncherConfig =
    try
      if Files.exists(path) then
        read[LauncherConfig](new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      else LauncherConfig()
    catch case _: Exception => LauncherConfig()

  def save(cfg: LauncherConfig, path: Path = configPath): Unit =
    try
      Files.createDirectories(path.getParent)
      Files.write(path, write(cfg, indent = 2).getBytes(StandardCharsets.UTF_8))
    catch case _: Exception => ()
