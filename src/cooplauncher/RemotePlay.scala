package cooplauncher

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

/** Resolves Steam "Remote Play Together" support per appId from the store
  * appdetails API (category "Remote Play Together"). Results are cached on disk
  * because the API is rate-limited. Tri-state per appId: Some(true) /
  * Some(false) resolved, None = unknown or still being checked. Only resolved
  * values are cached — a failed lookup must never masquerade as "unsupported".
  */
object RemotePlay:
  private val Quiet = ProcessLogger(_ => ())
  private val CategoryName = "remote play together"
  private val CategoryId = 44
  private val TtlMs = 30L * 24 * 60 * 60 * 1000
  private val Workers = 4

  def cachePath: Path =
    val home = Paths.get(System.getProperty("user.home"))
    val base =
      if Platform.isMac then home.resolve("Library/Caches/CoopLauncher")
      else
        sys.env
          .get("XDG_CACHE_HOME")
          .filter(_.nonEmpty)
          .map(Paths.get(_))
          .getOrElse(home.resolve(".cache"))
          .resolve("cooplauncher")
    base.resolve("rpt_cache.json")

  private val lock = new Object
  private val status = mutable.HashMap.empty[String, Boolean]
  private val requested = mutable.HashSet.empty[String]
  private val queue = mutable.Queue.empty[String]
  private var onUpdate: () => Unit = () => ()
  private var inFlight = 0
  private var started = false

  def start(callback: () => Unit): Unit = lock.synchronized {
    if !started then
      started = true
      onUpdate = callback
      loadCache()
      (0 until Workers).foreach { _ =>
        val t = new Thread(() => work())
        t.setDaemon(true)
        t.start()
      }
  }

  def request(appIds: Iterable[String]): Unit = lock.synchronized {
    if started then
      appIds.foreach { id =>
        if !requested.contains(id) && !status.contains(id) then
          requested += id
          queue.enqueue(id)
      }
      lock.notifyAll()
  }

  def statusOf(appId: String): Option[Boolean] = lock.synchronized(status.get(appId))

  def pendingCount: Int = lock.synchronized(queue.size + inFlight)

  private def work(): Unit =
    while true do
      val appId = lock.synchronized {
        while queue.isEmpty do lock.wait()
        inFlight += 1
        queue.dequeue()
      }
      val resolved = fetch(appId)
      lock.synchronized {
        inFlight -= 1
        resolved.foreach(s =>
          status(appId) = s
          saveCache())
      }
      onUpdate()

  private def fetch(appId: String): Option[Boolean] =
    val url =
      s"https://store.steampowered.com/api/appdetails?appids=$appId&filters=categories&l=english"
    httpGet(url).flatMap(body => parseResponse(appId, body))

  /** Visible for tests. None means unknown (offline, rate-limited, unexpected
    * payload) and is never cached as a verdict.
    */
  def parseResponse(appId: String, body: String): Option[Boolean] =
    try
      val entry = ujson.read(body).obj(appId)
      if !entry.obj.get("success").exists(_.bool) then None
      else
        val categories = entry.obj.get("data").flatMap(_.objOpt).flatMap(_.get("categories"))
        Some(categories.exists(isRptCategory))
    catch case _: Exception => None

  private def isRptCategory(categories: ujson.Value): Boolean =
    categories.arr.exists { c =>
      c.obj.get("description").flatMap(_.strOpt).exists(_.toLowerCase.contains(CategoryName)) ||
        c.obj.get("id").collect { case ujson.Num(n) => n.toInt }.contains(CategoryId)
    }

  private def loadCache(): Unit =
    val now = System.currentTimeMillis
    try
      ujson.read(new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8)).obj.foreach {
        (k, v) =>
          v match
            case ujson.Arr(items) if items.size == 2 =>
              (items(0), items(1)) match
                case (ujson.Num(ts), ujson.Bool(b)) =>
                  if now - ts.toLong < TtlMs then status(k) = b
                case _ => ()
            case ujson.Bool(b) => status(k) = b
            case _             => ()
      }
    catch case _: Exception => ()

  private def saveCache(): Unit =
    try
      Files.createDirectories(cachePath.getParent)
      val json = ujson.Obj.from(
        status.map((k, v) => k -> ujson.Arr(System.currentTimeMillis, v)).toSeq)
      Files.write(cachePath, ujson.write(json).getBytes(StandardCharsets.UTF_8))
    catch case _: Exception => ()

  private def httpGet(url: String): Option[String] =
    val tmp = File.createTempFile("cooprpt", ".json")
    try
      val cmd = Seq("curl", "-sS", "-f", "--max-time", "8", "-L", "-o", tmp.getAbsolutePath, url)
      if Process(cmd).! == 0 && tmp.length() > 0 then
        Some(new String(Files.readAllBytes(tmp.toPath), StandardCharsets.UTF_8))
      else None
    catch case _: Exception => None
    finally tmp.delete()
