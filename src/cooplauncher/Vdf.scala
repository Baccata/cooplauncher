package cooplauncher

import java.io.File

/** Valve VDF values: keys are strings, values are strings or nested objects.
  * Fields are a List to preserve order and duplicate keys — Steam rewrites
  * these files itself, so fidelity matters more than convenience.
  */
sealed trait VdfNode
object VdfNode:
  final case class Obj(fields: List[(String, VdfNode)]) extends VdfNode:
    /** Steam's KeyValues matches keys case-insensitively, and real files mix
      * casings ("apps" in some dumps, "Apps" in others) — so must we.
      */
    def get(key: String): Option[VdfNode] =
      fields.collectFirst { case (k, v) if k.equalsIgnoreCase(key) => v }
    def at(path: List[String]): Option[VdfNode] =
      path match
        case Nil => Some(this)
        case key :: rest =>
          if rest.isEmpty then get(key)
          else get(key).flatMap { case o: Obj => o.at(rest); case _ => None }
    def str(key: String): Option[String] =
      get(key).collect { case VdfNode.Str(s) => s }
    /** Sets or replaces key in place (matching case-insensitively), appending
      * it when absent. A matched key keeps its original spelling, so touched
      * files don't get gratuitously recased.
      */
    def updated(key: String, value: VdfNode): Obj =
      if fields.exists((k, _) => k.equalsIgnoreCase(key)) then
        Obj(fields.map(f => if f._1.equalsIgnoreCase(key) then f._1 -> value else f))
      else Obj(fields :+ (key -> value))
    /** Descends path and sets value there; missing or non-object intermediates fail. */
    def updated(path: List[String], value: VdfNode): Either[String, Obj] =
      path match
        case Nil => Right(this)
        case key :: rest =>
          if rest.isEmpty then Right(updated(key, value))
          else
            get(key) match
              case Some(o: Obj) => o.updated(rest, value).map(child => updated(key, child))
              case Some(_: Str) => Left(s"$key is not an object")
              case None         => Left(s"missing section $key")
  final case class Str(value: String) extends VdfNode

/** A VDF file: its root key ("UserLocalConfigStore", "AppState", …) and the
  * object it opens.
  */
final case class VdfDocument(rootKey: String, root: VdfNode.Obj):
  def at(path: List[String]): Option[VdfNode] = root.at(path)
  def updated(path: List[String], value: VdfNode): Either[String, VdfDocument] =
    root.updated(path, value).map(VdfDocument(rootKey, _))

object Vdf:
  def parseDocument(input: String): Option[VdfDocument] =
    try Some(Parser(input).parseDocument())
    catch case _: Exception => None

  /** Root object only (root key discarded) — manifest/library parsing. */
  def parse(input: String): Option[VdfNode.Obj] =
    parseDocument(input).map(_.root)

  /** Emits Steam's own style: tab indentation, two tabs before leaf values. */
  def encode(doc: VdfDocument): String =
    val sb = new StringBuilder
    sb ++= "\"" + escape(doc.rootKey) + "\"\n{\n"
    encodeFields(doc.root, 1, sb)
    sb ++= "}\n"
    sb.result

  private def encodeFields(obj: VdfNode.Obj, depth: Int, sb: StringBuilder): Unit =
    val indent = "\t" * depth
    obj.fields.foreach { (k, v) =>
      v match
        case VdfNode.Str(s) =>
          sb ++= indent + "\"" + escape(k) + "\"\t\t\"" + escape(s) + "\"\n"
        case child: VdfNode.Obj =>
          sb ++= indent + "\"" + escape(k) + "\"\n" + indent + "{\n"
          encodeFields(child, depth + 1, sb)
          sb ++= indent + "}\n"
    }

  private def escape(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

  private final class Parser(s: String):
    private var pos = 0

    def parseDocument(): VdfDocument =
      skipWs()
      val rootKey =
        if pos < s.length && s(pos) == '"' then readString()
        else ""
      skipWs()
      if pos < s.length && s(pos) == '{' then VdfDocument(rootKey, parseObjBody())
      else throw new IllegalArgumentException("expected top-level object")

    private def parseObjBody(): VdfNode.Obj =
      expect('{')
      val buf = List.newBuilder[(String, VdfNode)]
      var done = false
      while !done do
        skipWs()
        if pos >= s.length then done = true
        else if s(pos) == '}' then
          pos += 1
          done = true
        else if s(pos) == '"' then
          val key = readString()
          skipWs()
          if pos < s.length && s(pos) == '"' then
            buf += key -> VdfNode.Str(readString())
          else if pos < s.length && s(pos) == '{' then
            buf += key -> parseObjBody()
          else throw new IllegalArgumentException("expected value for " + key)
        else throw new IllegalArgumentException("unexpected char " + s(pos))
      VdfNode.Obj(buf.result())

    private def readString(): String =
      expect('"')
      val sb = new StringBuilder
      var done = false
      while !done do
        if pos >= s.length then throw new IllegalArgumentException("unterminated string")
        s(pos) match
          case '"' =>
            pos += 1
            done = true
          case '\\' =>
            pos += 1
            if pos >= s.length then throw new IllegalArgumentException("unterminated escape")
            s(pos) match
              case 'n' => sb += '\n'; pos += 1
              case 't' => sb += '\t'; pos += 1
              case 'r' => sb += '\r'; pos += 1
              case c   => sb += c; pos += 1
          case c =>
            sb += c
            pos += 1
      sb.result()

    private def expect(c: Char): Unit =
      if pos >= s.length || s(pos) != c then
        throw new IllegalArgumentException("expected " + c)
      pos += 1

    private def skipWs(): Unit =
      var go = true
      while go && pos < s.length do
        val c = s(pos)
        if c.isWhitespace then pos += 1
        else if c == '/' && pos + 1 < s.length && s(pos + 1) == '/' then
          while pos < s.length && s(pos) != '\n' do pos += 1
        else go = false

object Platform:
  lazy val isMac: Boolean =
    System.getProperty("os.name", "").toLowerCase.contains("mac")
