package cooplauncher

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.sys.process.Process

object BannerFetcher:
  def cacheDir: Path =
    val home = Paths.get(System.getProperty("user.home"))
    if Platform.isMac then home.resolve("Library/Caches/CoopLauncher/banners")
    else
      sys.env
        .get("XDG_CACHE_HOME")
        .filter(_.nonEmpty)
        .map(Paths.get(_))
        .getOrElse(home.resolve(".cache"))
        .resolve("cooplauncher/banners")

  def fetch(appId: String): Option[Array[Byte]] =
    local(appId).orElse(cached(appId)).orElse {
      val bytes = httpGetBytes(cdnUrl(appId)).filter(isJpeg)
      bytes.foreach(b => storeCache(appId, b))
      bytes
    }

  private def isJpeg(b: Array[Byte]): Boolean =
    b.length > 4 && (b(0) & 0xff) == 0xff && (b(1) & 0xff) == 0xd8

  private def cdnUrl(appId: String): String =
    s"https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/header.jpg"

  private def local(appId: String): Option[Array[Byte]] =
    SteamDiscovery.localIconPath(appId).flatMap(readBytes)

  private def cached(appId: String): Option[Array[Byte]] =
    val f = cacheDir.resolve(s"$appId.jpg").toFile
    if f.isFile && f.length() > 4 then readBytes(f).filter(isJpeg) else None

  private def storeCache(appId: String, bytes: Array[Byte]): Unit =
    try
      Files.createDirectories(cacheDir)
      Files.write(cacheDir.resolve(s"$appId.jpg"), bytes)
    catch case _: Exception => ()

  private def httpGetBytes(url: String): Option[Array[Byte]] =
    val tmp = File.createTempFile("coopfetch", ".bin")
    try
      val cmd =
        Seq("curl", "-sS", "-f", "--max-time", "12", "-L", "-o", tmp.getAbsolutePath, url)
      if Process(cmd).! == 0 && tmp.length() > 0 then Some(Files.readAllBytes(tmp.toPath))
      else None
    catch case _: Exception => None
    finally tmp.delete()

  private def readBytes(f: File): Option[Array[Byte]] =
    try Some(Files.readAllBytes(f.toPath))
    catch case _: Exception => None
