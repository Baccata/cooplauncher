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
