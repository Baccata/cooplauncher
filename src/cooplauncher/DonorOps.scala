package cooplauncher

import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.sys.process.{Process, ProcessLogger}

object DonorOps:
  private val Quiet = ProcessLogger(_ => ())

  def isDonorGame(g: SteamGame, config: LauncherConfig): Boolean =
    config.donorExePath match
      case None => false
      case Some(donor) =>
        g.executablePath.exists(_.equalsIgnoreCase(donor)) || {
          val folder =
            if g.gameFolder.endsWith(File.separator) then g.gameFolder
            else g.gameFolder + File.separator
          donor.toLowerCase.startsWith(folder.toLowerCase)
        }

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

  def install(donor: SteamGame, config: LauncherConfig): Either[String, String] =
    SelfPath.get() match
      case None => Left("Could not locate this launcher's binary. Run the installed build.")
      case Some(self) =>
        try
          val target = donor.executablePath.getOrElse(
            new File(donor.gameFolder, "cooplauncher").getAbsolutePath)
          val targetF = new File(target)
          val bak = new File(target + ".bak")
          if targetF.isFile && !bak.isFile then
            Files.copy(targetF.toPath, bak.toPath, StandardCopyOption.REPLACE_EXISTING)
          val tmp = new File(target + ".coopnew")
          Files.copy(Paths.get(self), tmp.toPath, StandardCopyOption.REPLACE_EXISTING)
          chmodPlusX(tmp)
          try Files.move(
            tmp.toPath,
            targetF.toPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
          catch case _: Exception =>
            Files.move(tmp.toPath, targetF.toPath, StandardCopyOption.REPLACE_EXISTING)
          chmodPlusX(targetF)
          if Platform.isMac then codesign(targetF)
          val cfg = config.copy(donorExePath = Some(target))
          LauncherConfig.save(cfg)
          Right(target)
        catch
          case e: Exception => Left(if e.getMessage != null then e.getMessage else e.toString)

  private def chmodPlusX(f: File): Unit =
    Process(Seq("chmod", "+x", f.getAbsolutePath)).!(Quiet)

  private def codesign(f: File): Unit =
    Process(Seq("codesign", "-f", "-s", "-", f.getAbsolutePath)).!(Quiet)
