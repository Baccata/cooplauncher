package cooplauncher

import java.io.File

sealed trait VdfNode
object VdfNode:
  final case class Obj(fields: List[(String, VdfNode)]) extends VdfNode:
    def get(key: String): Option[VdfNode] =
      fields.collectFirst { case (k, v) if k == key => v }
    def str(key: String): Option[String] =
      get(key).collect { case VdfNode.Str(s) => s }
  final case class Str(value: String) extends VdfNode

object Vdf:
  def parse(input: String): Option[VdfNode.Obj] =
    try Some(Parser(input).parseTop())
    catch case _: Exception => None

  private final class Parser(s: String):
    private var pos = 0

    def parseTop(): VdfNode.Obj =
      skipWs()
      if pos < s.length && s(pos) == '"' then
        readString()
        skipWs()
      if pos < s.length && s(pos) == '{' then parseObjBody()
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
