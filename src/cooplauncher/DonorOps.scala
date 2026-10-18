package cooplauncher

import java.io.File

object DonorOps:
  def isDonorGame(g: SteamGame, config: LauncherConfig): Boolean =
    config.donor.exists(_.appId == g.appId)

  def launchablePath(g: SteamGame, config: LauncherConfig): Option[String] =
    val override_ = config.executableOverrides.get(g.appId)
    val discovered = g.executablePath
    val fromFolder = discovered.filter(p => new File(p).isFile)
    override_
      .filter(p => new File(p).isFile)
      .orElse(fromFolder)
      .orElse(scanFolderForExecutable(g.gameFolder))

  private def scanFolderForExecutable(folder: String): Option[String] =
    SteamDiscovery.findExecutable(new File(folder), "")
