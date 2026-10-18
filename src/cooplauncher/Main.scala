package cooplauncher

import java.io.File
import scala.sys.process.Process

@main def app(args: String*): Unit =
  args.toList match
    case Nil => Tui.run()
    case "--help" :: _ | "-h" :: _ =>
      printHelp()
    case "--list" :: _ =>
      listGames()
    case "--banner" :: appId :: _ =>
      showBanner(appId)
    case "--launch" :: target :: _ =>
      launchBy(target)
    case "--wrap" :: _ =>
      Tui.runWrap()
    case other =>
      System.err.println(s"Unknown arguments: ${other.mkString(" ")}")
      printHelp()

private def printHelp(): Unit =
  println(
    """Coop Launcher — Steam Remote Play Together launcher for non-Steam games
      |
      |Usage:
      |  cooplauncher                interactive TUI (default)
      |  cooplauncher --wrap [cmd]   run as a donor game's Steam launch option:
      |                              the TUI launches games inside the donor's
      |                              process tree so Remote Play Together applies
      |  cooplauncher --list         list discovered Steam games
      |  cooplauncher --launch <id>  launch a game by appId or (partial) name
      |  cooplauncher --help         this help
      |
      |Environment:
      |  STEAM_ROOT   override Steam installation path
      |  COOP_IMG     off | blocks | chafa | kitty | sixel | iterm
      |""".stripMargin)

private def showBanner(appId: String): Unit =
  BannerFetcher.fetch(appId) match
    case Some(bytes) =>
      System.err.println(s"fetched ${bytes.length} bytes, jpeg=${JpegDecoder.decode(bytes).map(i => s"${i.width}x${i.height}").getOrElse("DECODE FAILED")}")
      TerminalImages.renderBanner(bytes, 90) match
        case Some(art) => println(art)
        case None => System.err.println("no renderer produced output")
    case None =>
      System.err.println(s"no banner found for $appId")

private def listGames(): Unit =
  val config = LauncherConfig.load()
  val games = SteamDiscovery.discover()
  if games.isEmpty then
    println("No Steam games found. Is Steam installed? (STEAM_ROOT can override)")
  else
    val donor = config.donor.map(_.name).getOrElse("-")
    println(s"Steam root : ${SteamDiscovery.steamPath().map(_.getAbsolutePath).getOrElse("not found")}")
    println(s"Donor      : $donor")
    println(s"${games.length} games:")
    games.foreach { g =>
      val exe = DonorOps.launchablePath(g, config)
        .orElse(if GameRunner.steamAvailable then Some("via Steam") else None)
        .getOrElse("(none)")
      println(f"  ${g.appId}%-10s ${g.name}%-40s $exe")
    }

private def launchBy(target: String): Unit =
  val config = LauncherConfig.load()
  val games = SteamDiscovery.discover()
  val q = target.toLowerCase
  val matches =
    games.filter(g => g.appId == target || g.name.toLowerCase.contains(q))
  matches match
    case Nil =>
      System.err.println(s"No game matching '$target'.")
    case game :: Nil =>
      DonorOps.launchablePath(game, config) match
        case Some(exe) =>
          GameRunner.launch(exe, game.appId) match
            case Right(()) =>
              println(
                s"Launching ${game.name}: $exe\n  output → ${new File(GameRunner.logDir, game.appId + ".log").getAbsolutePath}")
            case Left(err) =>
              System.err.println(s"Failed to launch ${game.name}: $err")
        case None =>
          GameRunner.launchViaSteam(game.appId) match
            case Right(()) => println(s"Launching ${game.name} through Steam")
            case Left(err) => System.err.println(s"Cannot launch ${game.name}: $err")
    case several =>
      System.err.println(s"Ambiguous match for '$target':")
      several.foreach(g => System.err.println(s"  ${g.appId}  ${g.name}"))
