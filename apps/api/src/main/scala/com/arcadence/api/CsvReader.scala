package com.arcadence.api

import fs2.Stream
import fs2.text

import scala.annotation.tailrec

/** Minimal RFC 4180 CSV parser over a byte stream (Phase 3.2).
  *
  *   - Delimiter sniffed from the header line (`,` `;` `\t` `|`, most frequent
  *     outside quotes).
  *   - Encoding: UTF-8 with BOM detection; falls back to ISO-8859-1 when the
  *     bytes are not valid UTF-8 (common with Excel exports).
  *   - Quoted fields may contain delimiters, escaped quotes (`""`) and
  *     newlines; all line endings (\r\n, \n, \r) are accepted.
  */
object CsvReader:

  /** Parses all records (without the header). Bounded by maxRows. */
  def parseAll(
      bytes: Array[Byte],
      maxRows: Int = 200_000
  ): Either[String, (List[String], List[List[String]])] =
    val text = decode(bytes)
    val delimiter = sniffDelimiter(text)
    val records = parseRecords(text, delimiter)
    records match
      case Nil            => Left("The file contains no header row.")
      case header :: rows =>
        if rows.size > maxRows then
          Left(s"The file exceeds the $maxRows-row limit.")
        else Right(normalizeHeader(header, delimiter), rows.take(maxRows))

  def decode(bytes: Array[Byte]): String =
    val hasBom = bytes.length >= 3 &&
      bytes(0) == 0xef.toByte && bytes(1) == 0xbb.toByte && bytes(
        2
      ) == 0xbf.toByte
    val body = if hasBom then bytes.drop(3) else bytes
    val utf8 = new String(body, java.nio.charset.StandardCharsets.UTF_8)
    if !hasBom && utf8.contains('\uFFFD') then
      new String(body, java.nio.charset.StandardCharsets.ISO_8859_1)
    else utf8

  private[api] def sniffDelimiter(text: String): Char =
    val firstLine = text.linesIterator.to(LazyList).headOption.getOrElse("")
    val candidates = Seq(',', ';', '\t', '|')
    candidates
      .map(c => c -> firstLine.count(_ == c))
      .maxByOption(_._2)
      .filter(_._2 > 0)
      .map(_._1)
      .getOrElse(',')

  /** Character-level RFC 4180 state machine. */
  private[api] def parseRecords(
      text: String,
      delimiter: Char
  ): List[List[String]] =
    val out = scala.collection.mutable.ListBuffer.empty[List[String]]
    val field = new StringBuilder
    val record = scala.collection.mutable.ListBuffer.empty[String]
    var inQuotes = false
    var fieldWasQuoted = false
    var index = 0
    val len = text.length

    def endField(): Unit =
      record += field.toString
      field.setLength(0)
      fieldWasQuoted = false

    def endRecord(): Unit =
      endField()
      if record.exists(_.trim.nonEmpty) || record.nonEmpty then
        out += record.toList
      record.clear()

    while index < len do
      val ch = text.charAt(index)
      if inQuotes then
        if ch == '"' then
          if index + 1 < len && text.charAt(index + 1) == '"' then
            field += '"'
            index += 1
          else inQuotes = false
        else field += ch
      else
        ch match
          case '"' if field.isEmpty =>
            inQuotes = true
            fieldWasQuoted = true
          case c if c == delimiter => endField()
          case '\r'                =>
            if index + 1 < len && text.charAt(index + 1) == '\n' then index += 1
            endRecord()
          case '\n' => endRecord()
          case c    => field += c
      index += 1
    end while

    // flush trailing field/record (files without trailing newline)
    if field.nonEmpty || record.nonEmpty || fieldWasQuoted then endRecord()
    out.toList

  private def normalizeHeader(
      header: List[String],
      delimiter: Char
  ): List[String] =
    header.map(h => h.trim.replaceAll("""\s""", " "))

  def renderCsv(rows: List[List[String]]): String =
    rows
      .map(_.map(escapeCsv).mkString(","))
      .mkString("\r\n")

  def escapeCsv(value: String): String =
    if value.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + value.replace("\"", "\"\"") + "\""
    else value
