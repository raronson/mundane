package com.ambiata.mundane.io

import com.ambiata.mundane.control._
import com.ambiata.mundane.io.Temporary._
import com.ambiata.mundane.testing.RIOMatcher._

import org.specs2.Specification

import scalaz.{Store => _, _}, Scalaz._, effect.IO

class TemporaryFilePathSpec extends Specification { def is = s2"""

 TemporaryFilePath should clean up its own resources
 ===================================================

   single file               $singleFile
   handles error             $handlesFail

"""

  def singleFile = {
    val p = uniqueFilePath
    (for {
      x <- TemporaryFilePath.runWithFilePath(p)(path => for {
        _ <- Files.write(path, "")
        e <- Files.exists(path)
      } yield e)
      y <- Files.exists(p)
    } yield x -> y) must beOkValue(true -> false)
  }

  def handlesFail = {
    val file: FilePath = uniqueFilePath
    TemporaryFilePath.runWithFilePath(file)( _ => RIO.fail[Int]("")).toOption must beNone
    !file.toFile.exists()
  }
}
