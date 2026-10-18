package cooplauncher

import java.io.{File, FileInputStream}
import scala.sys.process.{Process, ProcessLogger}

object Tui:
  private val Quiet = ProcessLogger(_ => ())
  private val Esc = "\u001b"
  private val Reset = s"${Esc}[0m"
  private val Bold = s"${Esc}[1m"
  private val Dim = s"${Esc}[2m"

  private def fg(r: Int, g: Int, b: Int) = s"${Esc}[38;2;$r;$g;${b}m"
  private def bg(r: Int, g: Int, b: Int) = s"${Esc}[48;2;$r;$g;${b}m"

  /** OSC 8 terminal hyperlink; terminals without support just show the text. */
  private def link(url: String): String =
    s"${Esc}]8;;$url${Esc}\\$url${Esc}]8;;${Esc}\\"

  private val NameFg = fg(235, 235, 235)
  private val PathFg = fg(144, 140, 160)
  private val DimPurple = fg(144, 140, 175)
  private val SelBg = bg(42, 38, 63)
  private val SelFg = fg(245, 245, 245)
  private val Accent = fg(122, 110, 200)

  private sealed trait Mode
  private case object MainMode extends Mode
  private case object DonorMode extends Mode
  private case object HelpMode extends Mode
  private case class InputMode(label: String, game: Option[SteamGame], buf: String) extends Mode

  private sealed trait Key
  private object Up extends Key
  private object Down extends Key
  private object Enter extends Key
  private object EscKey extends Key
  private object Backspace extends Key
  private object CtrlC extends Key
  private case class Char(c: scala.Char) extends Key
  private object Other extends Key

  private final class Term:
    private var savedStty: String = null
    private var (rowsCache, colsCache, at) = (24, 80, 0L)

    def enter(): Unit =
      savedStty = try Process(Seq("sh", "-c", "stty -g </dev/tty")).!!(Quiet).trim
      catch case _: Exception => null
      try Process(Seq("sh", "-c", "stty raw -echo min 0 time 1 </dev/tty")).!(Quiet)
      catch case _: Exception => ()
      System.out.print(s"${Esc}[?1049h${Esc}[?25l")
      System.out.flush()

    def leave(): Unit =
      System.out.print(s"${Esc}[?25h${Esc}[?1049l")
      System.out.flush()
      if savedStty != null then
        try Process(Seq("sh", "-c", s"stty $savedStty </dev/tty")).!(Quiet)
        catch case _: Exception => ()

    def size: (Int, Int) =
      val now = System.currentTimeMillis
      if now - at > 2000 then
        at = now
        try
          val out = Process(Seq("sh", "-c", "stty size </dev/tty")).!!(Quiet).trim
          out.split(' ') match
            case Array(r, c) if r.toInt > 0 && c.toInt > 0 =>
              rowsCache = r.toInt
              colsCache = c.toInt
            case _ =>
        catch case _: Exception => ()
      (rowsCache, colsCache)

  def hasTty: Boolean =
    try
      val f = new FileInputStream("/dev/tty")
      f.close()
      true
    catch case _: Exception => false

  private def shellQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

  def reExecInTerminal(): Boolean =
    SelfPath.get() match
      case None => false
      case Some(bin) =>
        if Platform.isMac then
          val cmd = s"tell application \"Terminal\" to do script ${shellQuote(bin)}"
          Process(Seq("osascript", "-e", cmd)).!(Quiet)
          true
        else
          val terms = Seq(
            sys.env.getOrElse("TERMINAL", ""),
            "gnome-terminal",
            "konsole",
            "xfce4-terminal",
            "xterm"
          ).filter(_.nonEmpty).distinct
          terms.find(TerminalImages.which) match
            case None => false
            case Some(t) =>
              val ok = t match
                case "gnome-terminal" =>
                  Process(Seq(t, "--", bin)).!(Quiet) == 0
                case "konsole" =>
                  Process(Seq(t, "-e", bin)).!(Quiet) == 0
                case "xfce4-terminal" =>
                  Process(Seq(t, "-x", bin)).!(Quiet) == 0
                case _ =>
                  Process(Seq(t, "-e", bin)).!(Quiet) == 0
              ok

  private final class Prefetcher(width: () => Int, redraw: () => Unit)
      extends Thread:
    setDaemon(true)
    private val queue = scala.collection.mutable.Queue.empty[String]
    private val requested = scala.collection.mutable.Set.empty[String]
    private val cache = scala.collection.mutable.HashMap.empty[String, Option[String]]

    def banner(appId: String): Option[String] = synchronized(cache.get(appId)).flatten

    def request(appId: String): Unit = synchronized {
      if requested.add(appId) then
        queue.enqueue(appId)
        notifyAll()
    }

    def invalidate(): Unit = synchronized {
      cache.clear()
      requested.clear()
    }

    override def run(): Unit =
      while true do
        val appId = synchronized {
          while queue.isEmpty do wait()
          queue.dequeue()
        }
        val rendered =
          BannerFetcher.fetch(appId).flatMap(bytes =>
            TerminalImages.renderBanner(bytes, width()))
        synchronized(cache(appId) = rendered)
        redraw()

  def run(): Unit =
    if !hasTty then
      if reExecInTerminal() then return
      System.err.println("No terminal available. Use --list / --launch <appid> for headless mode.")
      return
    runInteractive()

  private def runInteractive(): Unit =
    val term = new Term
    var config = LauncherConfig.load()
    var all: List[SteamGame] = Nil
    var visible: List[SteamGame] = Nil
    var donorCandidates: List[SteamGame] = Nil
    var donorVisible: List[SteamGame] = Nil
    var donorSel = 0
    var query = ""
    var searching = false
    var sel = 0
    var status = ""
    var mode: Mode = MainMode
    var running = true
    val lock = new Object

    val (_, cols) = term.size
    var bannerWidth = math.min(math.max((cols * 4) / 5, 30), math.max(cols - 2, 30))
    var bannerCache: Prefetcher = null
    val imageProtocol = TerminalImages.strategy match
      case TerminalImages.Kitty | TerminalImages.Iterm | TerminalImages.Sixel => true
      case _ => false

    def redraw(): Unit = lock.synchronized {
      val (rows, cols) = term.size
      val lines = List.newBuilder[String]

      def fit(s: String, w: Int): String =
        if s.length <= w then s else s.take(math.max(w - 1, 1)) + "…"

      mode match
        case HelpMode =>
          val help = List(
            "  Coop Launcher — Help",
            "",
            "  ↑/↓ or j/k     move selection",
            "  enter / o       launch selected game",
            "  /               search — type to filter, enter accepts",
            "  esc             clear filter / cancel",
            "  + / -           banner size",
            "  e               change executable for selected game",
            "  d               donor game setup",
            "  r               rescan Steam library",
            "  q / ctrl-c      quit",
            "",
            "  The donor game: a Remote Play Together game you pick",
            "  (e.g. RetroArch) as the launch host. Coop Launcher only",
            "  keeps track of it — no game files are modified.",
            "",
            "  The donor list only offers games that support Remote Play",
            "  Together (queried from the Steam store and cached locally).",
            "",
            Dim + "  Developed by Matheus Baliver — github.com/MBaliver" + Reset
          )
          help.foreach(l => lines += fit(l, cols))

        case m: InputMode =>
          lines += Bold + "  " + fit(m.label, cols) + Reset
          lines += ""
          lines += fit("  " + Accent + "> " + Reset + m.buf + "▏", cols)
          lines += ""
          lines += Dim + "  enter to confirm · esc to cancel" + Reset

        case DonorMode =>
          lines += Bold + "  Donor Game Setup" + Reset
          val cur = config.donor match
            case Some(d) => DimPurple + "  current: " + d.name + Reset
            case None =>
              PathFg + "  no donor configured — select a game and press enter" + Reset
          lines += cur
          lines += Dim + "  enter: set as donor · u: clear · esc: back" + Reset
          lines += Accent + "  ─" * math.max(cols / 2 - 2, 4) + Reset
          val pending = RemotePlay.pendingCount
          val checking =
            if pending > 0 then
              Some(Dim + s"  checking Remote Play Together support — $pending left" + Reset)
            else None
          checking.foreach(lines += _)
          if donorVisible.isEmpty then
            lines += PathFg + "  none of your installed games support Remote Play Together" + Reset
            lines += "  browse the Steam store — games that do, cheapest first:"
            lines += Accent +
              link("https://store.steampowered.com/search/?category2=44&sort_by=Price_ASC") + Reset
          val maxRows = math.max(rows - 7 - checking.size, 1)
          val from = math.max(math.min(donorSel - maxRows / 2, donorVisible.length - maxRows), 0)
          donorVisible.zipWithIndex
            .slice(from, from + maxRows)
            .foreach { (g, i) =>
              val idx = from + i
              val isDonor = DonorOps.isDonorGame(g, config)
              val confirmed = RemotePlay.statusOf(g.appId).contains(true)
              val tag =
                if isDonor then Accent + " ●" + Reset
                else if confirmed then Accent + " ✓" + Reset
                else "  "
              if idx == donorSel then
                lines += SelBg + SelFg + Bold + tag + " " + fit(g.name, cols - 8) + Reset
              else
                lines += tag + " " + NameFg + fit(g.name, cols - 8) + Reset
            }

        case MainMode =>
          lines += Bold + SelFg + "  Coop Launcher" + Reset +
            Dim + s"   ${visible.length} games" + Reset
          val donorLine = config.donor match
            case Some(d) => DimPurple + "  donor: " + fit(d.name, cols - 10) + Reset
            case None => PathFg + "  no donor configured — press d to set up" + Reset
          lines += donorLine
          if searching then
            lines += "  " + Accent + "/" + Reset + query + "▏"
          else if query.nonEmpty then
            lines += "  " + Dim + "/" + Reset + query
          else
            lines += Dim + "  / search" + Reset
          lines += Dim + "  " + ("─" * math.max(cols - 4, 4)) + Reset

          val selected = visible.lift(sel)
          val selectedArt = selected.flatMap(g => bannerCache.banner(g.appId))
          val showPreview = rows >= 28 && selected.isDefined
          val previewLines =
            if !showPreview then 0
            else
              // Measure the preview height instead of estimating it: the old
              // truncated guess (w*215/460/2) undershot the real banner by a
              // row for many widths, pushing the whole frame past the screen
              // edge and scrolling the terminal mid-redraw.
              val rendered = selectedArt match
                case Some(art) if !imageProtocol => art.linesIterator.length
                case Some(_) => // kitty/iterm/sixel art occupies cells, not lines
                  math.min(math.max(math.round(bannerWidth * 215f / 460 / 2), 4), 22)
                case None => 2 // placeholder letter + name
              rendered + 2
          val maxRows = math.max(rows - 6 - previewLines, 1)

          if showPreview && selected.isDefined then
            val g = selected.get
            selectedArt match
              case Some(art) =>
                art.linesIterator.foreach(l => lines += "  " + l)
              case None =>
                val letter = if g.name.nonEmpty then g.name.take(1).toUpperCase else "?"
                lines += DimPurple + s"  [ $letter ]" + Reset
                lines += Dim + "  " + fit(g.name, cols - 4) + Reset
            val exe = DonorOps.launchablePath(g, config)
              .getOrElse("launched through Steam (no native executable)")
            lines += PathFg + "  " + fit(exe, cols - 4) + Reset
            lines += Dim + "  " + ("─" * math.max(cols - 4, 4)) + Reset

          if visible.isEmpty then
            lines += PathFg + (if all.isEmpty then "  scanning Steam library…" else "  no games match") + Reset
          else
            val from = math.max(math.min(sel - maxRows / 2, visible.length - maxRows), 0)
            visible.zipWithIndex.slice(from, from + maxRows).foreach { (g, i) =>
              val idx = from + i
              if idx == sel then
                lines += SelBg + SelFg + Bold + " ▸ " + fit(g.name, cols - 6) + Reset
              else
                lines += "   " + NameFg + fit(g.name, cols - 6) + Reset
            }

          val hints = fit(
            "  / search · ↑↓ select · enter launch · e exe · d donor · r refresh · ? help · q quit",
            cols
          )
          lines += Dim + hints + Reset
          if status.nonEmpty then lines += "  " + fit(status, cols - 4)

      val sb = new StringBuilder
      if TerminalImages.strategy == TerminalImages.Kitty then
        sb ++= TerminalImages.kittyClearImages()
      sb ++= s"${Esc}[H"
      val ls = lines.result().take(rows)
      ls.zipWithIndex.foreach { (l, i) =>
        sb ++= l
        sb ++= s"${Esc}[K"
        if i < ls.length - 1 then sb ++= "\r\n"
      }
      sb ++= s"${Esc}[0J"
      System.out.print(sb.result())
      System.out.flush()
    }

    def refreshGames(): Unit =
      all = Nil
      applyFilter()
      redraw()
      all = SteamDiscovery.discover()
      applyFilter()
      status = if all.isEmpty then "No Steam games found. Is Steam installed? (STEAM_ROOT can override)" else ""
      requestBanners()
      redraw()

    def applyFilter(): Unit =
      val q = query.trim.toLowerCase
      val src =
        if q.isEmpty then all
        else all.filter(_.name.toLowerCase.contains(q))
      visible = src.filterNot(g => DonorOps.isDonorGame(g, config))
      donorCandidates = all.sortBy(_.name)
      recomputeDonorVisible()
      if sel >= visible.length then sel = math.max(visible.length - 1, 0)

    /** Donor candidates minus games known to lack Remote Play Together support.
      * Unknown-status games stay listed so the picker still works offline.
      */
    def recomputeDonorVisible(): Unit =
      donorVisible = donorCandidates.filterNot(g => RemotePlay.statusOf(g.appId).contains(false))
      if donorSel >= donorVisible.length then donorSel = math.max(donorVisible.length - 1, 0)

    def requestBanners(): Unit =
      if TerminalImages.strategy != TerminalImages.Off && mode == MainMode then
        (-1 to 1).flatMap(i => visible.lift(sel + i).map(_.appId)).foreach(bannerCache.request(_))

    def launch(g: SteamGame): Unit =
      DonorOps.launchablePath(g, config) match
        case Some(exe) =>
          GameRunner.launch(exe, g.appId) match
            case Right(()) => status = s"Launching ${g.name}… (output → ${new File(GameRunner.logDir, g.appId + ".log").getAbsolutePath})"
            case Left(err) => status = "⚠ " + err
        case None =>
          GameRunner.launchViaSteam(g.appId) match
            case Right(()) => status = s"Launching ${g.name} through Steam…"
            case Left(err) =>
              status = "⚠ " + err
              mode = InputMode(s"Executable path for ${g.name}:", Some(g), "")

    def changeExe(g: SteamGame): Unit =
      mode = InputMode(s"Executable path for ${g.name} (empty to clear):", Some(g),
        config.executableOverrides.getOrElse(g.appId, ""))

    def saveExe(g: SteamGame, path: String): Unit =
      if path.trim.isEmpty then
        config = config.copy(executableOverrides = config.executableOverrides - g.appId)
      else if new File(path.trim).isFile then
        config = config.copy(executableOverrides = config.executableOverrides + (g.appId -> path.trim))
        status = s"Saved executable for ${g.name}"
      else status = "⚠ path does not exist"
      LauncherConfig.save(config)

    /** Records the donor game — no files are touched, the choice is only kept
      * in the config for when games are launched from the TUI.
      */
    def setDonor(g: SteamGame): Unit =
      config = config.copy(donor =
        Some(DonorGame(g.appId, g.name, DonorOps.launchablePath(g, config))))
      LauncherConfig.save(config)
      applyFilter()

    def adjustBanner(d: Int): Unit =
      val (_, c) = term.size
      bannerWidth = math.min(math.max(bannerWidth + d, 24), math.max(c - 2, 24))
      bannerCache.invalidate()
      status = s"banner width: $bannerWidth"

    def readKey(): Option[Key] =
      var b = System.in.read()
      while b == -1 do b = System.in.read()
      b match
        case 3 | 4 => Some(CtrlC)
        case 10 | 13 => Some(Enter)
        case 127 | 8 => Some(Backspace)
        case 27 =>
          val n1 = System.in.read()
          if n1 == -1 then Some(EscKey)
          else if n1 == '[' then
            System.in.read() match
              case 'A' => Some(Up)
              case 'B' => Some(Down)
              case 'C' | 'D' | 'H' | 'F' => Some(Other)
              case '3' | '5' | '6' =>
                System.in.read()
                Some(Other)
              case _ => Some(Other)
          else if n1 == 'O' then
            System.in.read() match
              case 'A' => Some(Up)
              case 'B' => Some(Down)
              case _ => Some(Other)
          else Some(Other)
        case c if c >= 32 && c < 127 => Some(Char(c.toChar))
        case _ => Some(Other)

    def move(delta: Int): Unit =
      mode match
        case MainMode =>
          if visible.nonEmpty then
            sel = math.min(math.max(sel + delta, 0), visible.length - 1)
        case DonorMode =>
          if donorVisible.nonEmpty then
            donorSel = math.min(math.max(donorSel + delta, 0), donorVisible.length - 1)
        case _ => ()

    def handleKey(k: Key): Unit =
      mode match
        case HelpMode => mode = MainMode

        case m: InputMode =>
          k match
            case Char(c) => mode = m.copy(buf = m.buf + c)
            case Backspace =>
              mode = if m.buf.nonEmpty then m.copy(buf = m.buf.dropRight(1)) else m
            case Enter =>
              mode = MainMode
              m.game.foreach(saveExe(_, m.buf.trim))
            case EscKey => mode = MainMode
            case _ => ()

        case DonorMode =>
          k match
            case Up => move(-1)
            case Char('k') => move(-1)
            case Down => move(1)
            case Char('j') => move(1)
            case Enter => donorVisible.lift(donorSel).foreach(setDonor)
            case Char('u') =>
              if config.donor.isDefined then
                config = config.copy(donor = None)
                LauncherConfig.save(config)
                applyFilter()
            case EscKey => mode = MainMode
            case CtrlC => running = false
            case _ => ()

        case MainMode =>
          k match
            case Up => move(-1)
            case Down => move(1)
            case Enter =>
              searching = false
              visible.lift(sel).foreach(launch)
            case EscKey =>
              searching = false
              if query.nonEmpty then
                query = ""
                applyFilter()
                sel = 0
            case Backspace if searching =>
              query = query.dropRight(1)
              if query.isEmpty then searching = false
              applyFilter()
              sel = 0
            case CtrlC => running = false
            case Char('k') if !searching => move(-1)
            case Char('j') if !searching => move(1)
            case Char('/') if !searching => searching = true
            case Char('+') | Char('=') => adjustBanner(8)
            case Char('-') | Char('_') => adjustBanner(-8)
            case Char(c) if searching =>
              query += c
              applyFilter()
              sel = 0
            case Char('o') =>
              visible.lift(sel).foreach(launch)
            case Char('r') =>
              query = ""
              refreshGames()
            case Char('e') =>
              visible.lift(sel).foreach(changeExe)
            case Char('d') =>
              donorSel = 0
              RemotePlay.request(donorCandidates.map(_.appId))
              mode = DonorMode
            case Char('?') => mode = HelpMode
            case Char('q') => running = false
            case Char(c) =>
              searching = true
              query += c
              applyFilter()
              sel = 0
            case _ => ()

    term.enter()
    Runtime.getRuntime.addShutdownHook(new Thread(() => term.leave()))
    bannerCache = new Prefetcher(() => bannerWidth, () => redraw())
    bannerCache.start()
    RemotePlay.start { () =>
      lock.synchronized(recomputeDonorVisible())
      redraw()
    }

    try
      refreshGames()
      while running do
        readKey().foreach { k =>
          handleKey(k)
          if running then
            requestBanners()
            redraw()
        }
    finally
      term.leave()
