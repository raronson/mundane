package com.ambiata.mundane.io

import com.ambiata.mundane.path._
import java.io._
import java.net.URI
import java.util.UUID
import scalaz._, Scalaz._

object FilePath {
  def fromFile(f: File): P =
    unsafe(f.getPath)

  def fromString(s: String): Option[P] =
    s.split("/").toList match {
      case "" :: Nil =>
        None
      case "" :: parts =>
        parts.traverse(FileName.create).map(fromList(Root, _))
      case parts =>
        parts.traverse(FileName.create).map(fromList(Relative, _))
    }

  def fromList(dir: P, parts: List[FileName]): P =
    parts.foldLeft(dir)((acc, el) => acc </ el)

  def fromURI(s: URI): Option[FilePath] =
    fromString(s.getPath)

  def unsafe(s: String): FilePath =
    fromString(s).getOrElse(sys.error("FilePath.unsafe on an invalid string."))
}
