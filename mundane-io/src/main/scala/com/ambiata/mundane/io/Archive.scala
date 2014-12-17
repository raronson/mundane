package com.ambiata.mundane
package io

import com.ambiata.mundane.control._
import com.ambiata.mundane.path._
import java.io._

import scala.sys.process._

import scalaz._, Scalaz._, \&/._
import scalaz.effect._

case class Archive(archiveFile: FilePath, checksumFile: FilePath, contents: List[FilePath], checksum: Checksum) {
  def files: List[FilePath] =
    List(archiveFile, checksumFile)
}

object Archive {
  // FIX This is the original copper publish code, for creating archives, but it has some ill-defined sematics
  //     around printing errors to standard out as well as some very  _optimistic_ quoting. Needs work.
  def create(archiveName: FileName, checksumName: FileName, target: DirPath, contents: List[FilePath]): RIO[Archive] = {
    val archive = target </ archiveName
    val checksum = target </ checksumName
    val files = contents.map(_.basename).mkString(" ")
    val command = List("sh", "-c", s"tar czf ${archive.toFile.getAbsolutePath} -C ${target.toFile.getAbsolutePath} ${files} > /dev/null")
    RIO.safe[Int] { Process(command) ! ProcessLogger(o => (), println) }.flatMap({
      case 0 => for {
        md5 <- Checksum.file(archive, MD5)
        _   <- Files.write(checksum, md5.hash)
      } yield Archive(archive, checksum, contents, md5)
      case e =>
        RIO.fail[Archive](s"Error compressing files, tar exit code <$e>")
    })
  }

  def isVaildGzip(path: FilePath): RIO[Boolean] =  RIO.safe[Boolean] {
    List("sh", "-c", s"gzip -dc ${path} > /dev/null") ! ProcessLogger(o => (), e => ()) == 0
  }

  def extractGzipStream(gzip: InputStream, dest: FilePath): RIO[Unit] = RIO(_ => ResultT(IO {
    val buffer = new StringBuilder
    if (!dest.toFile.getParentFile.exists && !dest.toFile.getParentFile.mkdirs)
      Result.fail(s"Could not create gzip extraction directory for ${dest}.")
    else if (dest.toFile.isDirectory)
      Result.fail(s"Could not extract to ${dest} as it is a directory.")
    else if ((List("sh", "-c", "gzip -dc -") #< gzip #> dest.toFile ! ProcessLogger(o => (), e => buffer.append(s"${e}\n"))) != 0)
      Result.fail(s"Could not extract gzip, stderr:\n${buffer}")
    else
      Result.ok(())
  }))

  def isValidTarball(path: FilePath): RIO[Boolean] = RIO.safe[Boolean] {
    List("sh", "-c", s"tar xfz ${path} -O > /dev/null") ! ProcessLogger(o => (), e => ()) == 0
  }

  // FIX this is not cross platform, arg....
  // FIX clean-up strip-levels this is not a good default API it is overly specific to copper-extract
  def extractTarballStream(tarball: InputStream, dest: FilePath, stripLevels: Int): RIO[Unit] = RIO(_ => ResultT(IO {
    val levels = if (stripLevels > 0) s"-strip-components ${stripLevels}" else ""
    val cmd = s"tar xz -C ${dest} - ${levels}"
    val buffer = new StringBuffer
    if(!dest.toFile.exists && !dest.toFile.mkdirs)
      Result.fail(s"Could not extract tarball, extraction dir ${dest} does not exist and could not be created.")
    else if (!dest.toFile.isDirectory)
      Result.fail(s"Could not extract tarball, ${dest} is not a directory.")
    else if ((List("sh", "-c", cmd) #< tarball ! ProcessLogger(o => (), e => buffer.append(s"${e}\n"))) != 0)
      Result.fail(s"Could not extract tarball, stderr:\n${buffer}")
    else
      Result.ok(())
  }))

  // FIX testing and cross platform support, shelling out to tar is one thing, throwing random flags around is not ok though
  def extractTarballStreamTheOtherOneThisIsNotOk(tarball: InputStream, dest: FilePath): RIO[Unit] = RIO(_ => ResultT(IO {
    val cmd = s"tar xz -C ${dest}"
    val buffer = new StringBuffer
    if(!dest.toFile.exists && !dest.toFile.mkdirs)
      Result.fail(s"Could not extract tarball, extraction dir ${dest} does not exist and could not be created.")
    else if (!dest.toFile.isDirectory)
      Result.fail(s"Could not extract tarball, ${dest} is not a directory.")
    else if ((List("sh", "-c", cmd) #< tarball ! ProcessLogger(o => (), e => buffer.append(s"${e}\n"))) != 0)
      Result.fail(s"Could not extract tarball, stderr:\n${buffer}")
    else
      Result.ok(())
  }))

  def extractTarballStreamFlat(tarball: InputStream, dest: FilePath): RIO[Unit] = for {
    _ <- extractTarballStreamTheOtherOneThisIsNotOk(tarball, dest)
    _ <- RIO(_ => ResultT[IO, Unit](IO {
      val buffer = new StringBuffer
      if ((List("sh", "-c", s"find $dest -type f -exec mv {} ${dest.toFile.getAbsolutePath} \\;") ! ProcessLogger(o => (), e => buffer.append(s"${e}\n"))) != 0)
        Result.fail(s"Failed to flatten tarball structure into ${dest}, srderr:\n${buffer}")
      else
        Result.ok(())
    }))
  } yield ()
}
