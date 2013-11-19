package com.ambiata.mundane
package io

import java.io._
import org.specs2._
import java.security.MessageDigest

class FilesSpec extends Specification with ScalaCheck { def is = s2"""

Files should be able to:
  read bytes from file                        $e1
  read string from file                       $e2
  calculate checksum of a file                $e3
  validate tarball                            $e4
  validate gzip                               $e5
"""

  def e1 = prop((bs: Array[Byte]) => {
    val tmpFile = File.createTempFile("files-spec", ".bytes")
    writeBytes(tmpFile, bs)
    val fileBytes = Files.readFileBytes(tmpFile)
    tmpFile.delete()
    fileBytes.toOption.get === bs
  })

  def e2 = prop((str: String) => {
    val tmpFile = File.createTempFile("files-spec", ".string")
    writeString(tmpFile, str)
    val fileStr = Files.readFile(tmpFile)
    tmpFile.delete()
    fileStr.toOption.get === str
  })

  def e3 = prop((bs: Array[Byte]) => {
    val tmpFile = File.createTempFile("files-calc-checksum-spec", ".bytes")
    writeBytes(tmpFile, bs)
    val checksum = Files.calcChecksum(tmpFile)
    tmpFile.delete()
    val expectedChecksum = MessageDigest.getInstance("MD5").digest(bs).map("%02X".format(_)).mkString.toLowerCase
    checksum.toOption === Some(expectedChecksum)
  })

  def e4 = prop((bs: Array[Byte], str: String) => {
    val tmpDir = mkTempDir("files-spec")
    val tmpBinFile = File.createTempFile("files-spec", ".bytes", tmpDir)
    val tmpStrFile = File.createTempFile("files-spec", ".string", tmpDir)
    val tmpTgzFile = File.createTempFile("files-spec", ".tar.gz")

    writeBytes(tmpBinFile, bs)
    writeString(tmpStrFile, str)
    tarball(tmpDir, tmpTgzFile)

    Files.validTarball(tmpTgzFile) === true
    Files.tarballError(tmpStrFile) must beSome

    rmdir(tmpDir)
    tmpTgzFile.delete()
  })

  def e5 = prop((str: String) => {
    val tmpStrFile = File.createTempFile("files-spec", ".string")

    writeString(tmpStrFile, str)
    val tmpGzipFile = gzip(tmpStrFile)

    Files.validGzip(tmpGzipFile) === true
    Files.validGzip(tmpStrFile) === false
  })


  def mkTempDir(prefix: String, suffix: String = System.nanoTime.toString): File = {
    val tmpFile = File.createTempFile(prefix, suffix)
    if(!tmpFile.delete()) sys.error(s"Could not delete temp file '${tmpFile.getAbsolutePath}'")
    if(!tmpFile.mkdir()) sys.error(s"Could not create temp dir '${tmpFile.getAbsolutePath}'")
    tmpFile
  }

  def tarball(inDir: File, outFile: File): File = {
    import scala.sys.process._
    Process(List("sh", "-c", s"tar czf ${outFile.getPath} --directory ${inDir.getPath} .")).run(ProcessLogger(stdout => (), println))
    outFile
  }

  def gzip(file: File): File = {
    import scala.sys.process._
    val gzfile = new File(file.getPath + ".gz")
    Process(List("sh", "-c", s"gzip -c ${file.getPath} > ${gzfile.getPath}")).run(ProcessLogger(o => (), println))
    gzfile
  }

  def writeBytes(f: File, bytes: Array[Byte]) {
    val fos =  new FileOutputStream(f)
    fos.write(bytes)
    fos.close()
  }

  def writeString(f: File, str: String) {
    val pw = new PrintWriter(f)
    pw.write(str)
    pw.close()
  }

  def rmdir(d: File) {
    if(d.isDirectory) d.listFiles.foreach(rmdir) else d.delete
    d.delete
  }
}