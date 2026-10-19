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
