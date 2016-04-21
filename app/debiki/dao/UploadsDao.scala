/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import com.debiki.core.Prelude._
import com.google.{common => guava}
import debiki.DebikiHttp._
import debiki.Globals
import java.{io => jio, util => ju}
import java.awt.image.BufferedImage
import java.nio.{file => jf}
import java.nio.file.{attribute => jfa}
import debiki.{ImageUtils, ReactRenderer}
import org.jsoup.Jsoup
import play.{api => p}
import play.api.Play
import UploadsDao._

import scala.collection.mutable.ArrayBuffer


/** Moves temp files into the uploads directory and adds metadata about the uploaded files.
  */
trait UploadsDao {
  self: SiteDao =>


  /** Returns the hash-path-suffix to the file after it has been copied into the uploads
    * directory, or CDN. E.g. returns "x/y/zwq...abc.jpg" where "xyzwq...abc" is the hash.
    * The reason for the slashes is that then all uploads won't end up in the same
    * directory, if stored on localhost (some file systems don't want 99999 files in a
    * single directory).
    */
  def addUploadedFile(uploadedFileName: String, tempFile: jio.File, uploadedById: UserId,
        browserIdData: BrowserIdData): UploadRef = {

    import Globals.{LocalhostUploadsDirConfigValueName, maxUploadSizeBytes, localhostUploadsBaseUrl}

    val publicUploadsDir = Globals.anyPublicUploadsDir getOrElse throwForbidden(
      "DwE5KFY9", "File uploads disabled, config value missing: " +
        LocalhostUploadsDirConfigValueName)

    val uploadedDotSuffix = '.' + checkAndGetFileSuffix(uploadedFileName)

    val origMimeType = java.nio.file.Files.probeContentType(tempFile.toPath.toAbsolutePath)
    val origSize = tempFile.length
    if (origSize >= maxUploadSizeBytes)
      throwForbidden("DwE5YFK2", s"File too large, more than $origSize bytes")


    var tempCompressedFile: Option[jio.File] = None
    var dimensions: Option[(Int, Int)] = None

    // Try to convert the file to an image and then compress it. If this works and if
    // the compressed file is smaller, then use it instead.
    val (optimizedFile, optimizedDotSuffix) = {
      val anyImage: Option[BufferedImage] =
        try Option(javax.imageio.ImageIO.read(tempFile))
        catch {
          case _: jio.IOException =>
            None // not an image, fine
        }
      anyImage match {
        case None =>
          (tempFile, uploadedDotSuffix)
        case Some(image) =>
          dimensions = Some((image.getWidth, image.getHeight))
          if (origMimeType == ImageUtils.MimeTypeJpeg && origSize < MaxSkipImageCompressionBytes) {
            // Don't compress, so small already.
            (tempFile, ".jpg")
          }
          else {
            tempCompressedFile = Some(new jio.File(tempFile.toPath + ".compressed.jpg"))
            ImageUtils.convertToCompressedJpeg(image, tempCompressedFile.get)
            val compressedSize = tempCompressedFile.get.length
            val tempFileSize = tempFile.length
            if (compressedSize < tempFileSize) {
              (tempCompressedFile.get, ".jpg")
            }
            else {
              tempCompressedFile.get.delete()
              tempCompressedFile = None
              (tempFile, uploadedDotSuffix)
            }
          }
      }
    }

    val mimeType = java.nio.file.Files.probeContentType(optimizedFile.toPath.toAbsolutePath)
    val sizeBytes = {
      val sizeAsLong = optimizedFile.length
      if (sizeAsLong >= maxUploadSizeBytes) {
        throwForbidden("DwE5YFK2", s"Optimized file too large, more than $maxUploadSizeBytes bytes")
      }
      sizeAsLong.toInt
    }

    throwIfUploadedTooMuchRecently(uploadedById, sizeBytes = sizeBytes)

    val hashPathSuffix = makeHashPath(mimeType, optimizedFile, optimizedDotSuffix)
    val destinationFile = new java.io.File(s"$publicUploadsDir$hashPathSuffix")
    destinationFile.getParentFile.mkdirs()

    // Remember this new file and who uploaded it.
    // (Do this before moving the it into the uploads directory, in case the server
    // crashes. We don't want any files with missing metadata in the uploads directory
    // — but it's okay with metadata for which the actual files are missing: we can just
    // delete the metadata entries later. [9YMU2Y])
    readWriteTransaction { transaction =>
      // The file will be accessible on localhost, it hasn't yet been moved to e.g. any CDN.
      val uploadRef = UploadRef(localhostUploadsBaseUrl, hashPathSuffix)
      transaction.insertUploadedFileMeta(uploadRef, sizeBytes, mimeType, dimensions)
      insertAuditLogEntry(AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.UploadFile,
        doerId = uploadedById,
        doneAt = transaction.currentTime,
        browserIdData = browserIdData,
        uploadHashPathSuffix = Some(hashPathSuffix),
        uploadFileName = Some(uploadedFileName),
        sizeBytes = Some(sizeBytes)), transaction)
    }

    // (Don't do this inside the transaction above, because then the file might be moved
    // in place, but the transaction might fail —> metadata never created.)
    try {
      // Let Nginx read this file so it can be served directly from the file system.
      val anyoneMayRead = new ju.HashSet[jfa.PosixFilePermission]()
      anyoneMayRead.add(jfa.PosixFilePermission.OWNER_READ)
      anyoneMayRead.add(jfa.PosixFilePermission.GROUP_READ)
      anyoneMayRead.add(jfa.PosixFilePermission.OTHERS_READ)
      java.nio.file.Files.setPosixFilePermissions(optimizedFile.toPath, anyoneMayRead)
      // Prevent people from accidentally modifying the file contents.
      optimizedFile.setReadOnly()
      // The last thing we do:
      jf.Files.move(optimizedFile.toPath, destinationFile.toPath)
    }
    catch {
      case _: jf.FileAlreadyExistsException =>
        // Fine. Same name -> same hash -> same content.
      case ex: Exception =>
        p.Logger.error(o"""Error moving file into place, name: $uploadedFileName, file path:
          ${optimizedFile.getPath}, destination: ${destinationFile.getPath} [DwE8MF2]""", ex)
        throw ex
    }

    // COULD wrap in try...finally, so will be deleted for sure.
    tempCompressedFile.foreach(_.delete)

    UploadRef(localhostUploadsBaseUrl, hashPathSuffix)
  }


  private def throwIfUploadedTooMuchRecently(uploaderId: UserId, sizeBytes: Int) {
    readOnlyTransaction { transaction =>
      val user = transaction.loadUser(uploaderId) getOrElse throwForbidden(
        "EsE7KMW2", "Strangely enough, your user account just disappeared")

      // God mode.
      if (user.isAdmin)
        return

      val nowMs = transaction.currentTime.getTime
      val entries = transaction.loadAuditLogEntriesRecentFirst(userId = uploaderId,
        tyype = AuditLogEntryType.UploadFile, limit = MaxUploadsLastWeek)

      // Check if the user has uploaded more than MaxUploadsLastWeek uploads the last 7 days
      // — that'd feel fishy.
      if (entries.length >= MaxUploadsLastWeek) {
        entries.lastOption foreach { oldest =>
          val timeAgoMs = nowMs - oldest.doneAt.getTime
          if (timeAgoMs < OneWeekInMillis)
            throwTooManyRequests(
              "Sorry but you've uploaded too many files the last 7 days [EsE5GM2]")
        }
      }

      var bytesUploadedLastWeek = sizeBytes
      var bytesUploadedLastDay = sizeBytes

      entries foreach { entry =>
        val doneAtMs = entry.doneAt.getTime
        if (nowMs - doneAtMs < OneDayInMillis) {
          bytesUploadedLastDay += entry.sizeBytes getOrElse 0
        }
        if (nowMs - doneAtMs < OneWeekInMillis) {
          bytesUploadedLastWeek += entry.sizeBytes getOrElse 0
        }
      }

      // For now: (COULD make configurable in the admin section)
      val maxBytesWeek = user.isStaff ? MaxBytesPerWeekStaff | MaxBytesPerWeekMember
      val maxBytesDay = user.isStaff ? MaxBytesPerDayStaff | MaxBytesPerDayMember

      def throwIfTooMuch(actual: Int, max: Int, lastWhat: String) {
        if (actual > max)
          throwEntityTooLarge("EsE4MPK02", o"""Sorry but you've uploaded too much stuff the
              last $lastWhat. You can upload at most ${(max - actual) / 1000} more kilobytes""")
      }
      throwIfTooMuch(bytesUploadedLastWeek, maxBytesWeek, "7 days")
      throwIfTooMuch(bytesUploadedLastDay, maxBytesDay, "24 hours")
    }
  }
}


object UploadsDao {

  val MaxSuffixLength = 12

  val base32lowercaseEncoder = guava.io.BaseEncoding.base32().lowerCase()

  /** We don't need all 51.2 base32 sha256 chars (51 results in rather long file names).
    * SHA-1 is 32 chars in base32 — let's keep 33 chars so people won't mistake this
    * for SHA-1.
    * Don't change this — that would invalidate all hashes in the database.
    */
  val HashLength = 33

  // Later: make configurable in the admin section. But there should be some fix upper limits?
  val MaxBytesPerDayMember = 8*Megabytes
  val MaxBytesPerDayStaff = 999*Megabytes
  val MaxBytesPerWeekMember = 20*Megabytes
  val MaxBytesPerWeekStaff = 999*Megabytes

  val MaxUploadsLastWeek = 700

  val MaxAvatarTinySizeBytes = 2*1000
  val MaxAvatarSmallSizeBytes = 5*1000
  val MaxAvatarMediumSizeBytes = 100*1000

  val MaxSkipImageCompressionBytes = 5 * 1000


  // Later: Delete this in July 2016? And:
  // - delete all old images that use this regex in the database
  //   (that's ok, not many people use this right now).
  // - remove this regex from the psql function `is_valid_hash_path(varchar)`
  // - optionally, recalculate disk quotas (since files deleted)
  val OldHashPathSuffixRegex = """^[a-z0-9]/[a-z0-9]/[a-z0-9]+\.[a-z0-9]+$""".r

  val HashPathSuffixRegex =
    """^(video/)?[0-9][0-9]?/[a-z0-9]/[a-z0-9]{2}/[a-z0-9]+\.[a-z0-9]+$""".r

  val HlsVideoMediaSegmentsSuffix = ".m3u8"

  private val Log4 = math.log(4)


  def makeHashPath(mimeType: String, file: jio.File, dotSuffix: String): String = {
    // (It's okay to truncate the hash, see e.g.:
    // http://crypto.stackexchange.com/questions/9435/is-truncating-a-sha512-hash-to-the-first-160-bits-as-secure-as-using-sha1 )
    val hashCode = guava.io.Files.hash(file, guava.hash.Hashing.sha256)
    val hashString = base32lowercaseEncoder.encode(hashCode.asBytes) take HashLength
    makeHashPath(mimeType, file.length().toInt, hashString, dotSuffix)
  }


  /** [disabled: The hash path starts with video/ for video files, so e.g. nginx can be configured
    * to serve video files from video/.]
    * Then comes floor(log4(size-in-bytes / 1000)), i.e. 0 for < 4k files,
    * 1 for < 16k files, 2 for < 64k, 3 for < 256k, 4 for <= 1M, 5 < 4M, 6 < 16M, .. 9 <= 1G.
    * This lets us easily backup small files often, and large files infrequently. E.g.
    * backup directories with < 1M files hourly, but larger files only daily?
    * Or keep large files on other types of disks?
    */
  def makeHashPath(mimeType: String, sizeBytes: Int, hash: String, dotSuffix: String): String = {
    val sizeDigit = sizeKiloBase4(sizeBytes)
    val (hash0, hash1, hash2, theRest) = (hash.head, hash.charAt(1), hash.charAt(2), hash.drop(3))
    val anyTypePrefix =
      "" /* skip this separate-video-folder for now. Won't totally work anyway, because
        would need to do the same for audio? and won't work for new / other video formats anyway.
      if (mimeType.startsWith("video") || dotSuffix == HlsVideoMediaSegmentsSuffix) "video/"
      else ""  */
    s"$anyTypePrefix$sizeDigit/$hash0/$hash1$hash2/$theRest$dotSuffix"
  }


  /** Returns floor(log4(size-in-bytes / 1000)), and 0 if you give it 0.
    */
  def sizeKiloBase4(sizeBytes: Int): Int = {
    require(sizeBytes >= 0, "EsE7YUG2")
    if (sizeBytes < 4000) return 0
    val fourKiloBlocks = sizeBytes / 1000
    (math.log(fourKiloBlocks) / Log4).toInt
  }


  def findUploadRefsInText(html: String): Set[UploadRef] = {
    // COULD reuse TextAndHtml — it also finds links
    TESTS_MISSING
    val document = Jsoup.parse(html)
    val anchorElems: org.jsoup.select.Elements = document.select("a[href]")
    val mediaElems: org.jsoup.select.Elements = document.select("[src]")
    val references = ArrayBuffer[UploadRef]()

    import scala.collection.JavaConversions._

    for (linkElem: org.jsoup.nodes.Element <- anchorElems) {
      val url = linkElem.attr("href")
      if ((url ne null) && url.nonEmpty) {
        addUrlIfReferencesUploadedFile(url)
      }
    }

    for (mediaElem: org.jsoup.nodes.Element <- mediaElems) {
      val url = mediaElem.attr("src")
      if ((url ne null) && url.nonEmpty) {
        addUrlIfReferencesUploadedFile(url)
      }
    }

    def addUrlIfReferencesUploadedFile(urlString: String) {
      val urlPath =
        if (urlString startsWith "/") urlString
        else {
          try new java.net.URL(urlString).getPath
          catch {
            case _: java.net.MalformedURLException =>
              return
          }
        }
      import Globals.localhostUploadsBaseUrl
      if (urlPath startsWith localhostUploadsBaseUrl) {
        val hashPathSuffix = urlPath drop localhostUploadsBaseUrl.length
        if (OldHashPathSuffixRegex.matches(hashPathSuffix) ||
            HashPathSuffixRegex.matches(hashPathSuffix)) {
          // Don't add any hostname, because files stored locally are accessible from any hostname
          // that maps to this server — only the file content hash matters.
          references.append(UploadRef(localhostUploadsBaseUrl, hashPathSuffix))
        }
      }
      /* Later, if serving uploads via a CDN:
      else if (urlPath starts with cdn-hostname) {
        if (HashPathSuffixRegex matches hashPathSuffix) {
          ...
          val baseUrl = url.getHost + "/"
          ...
        }
      }*/
    }

    references.toSet
  }


  def findUploadRefsInPost(post: Post): Set[UploadRef] = {
    val approvedRefs = post.approvedHtmlSanitized.map(findUploadRefsInText) getOrElse Set.empty
    val currentRefs = findUploadRefsInText(post.currentHtmlSanitizedToFindLinks(ReactRenderer))
    approvedRefs ++ currentRefs
  }


  def checkAndGetFileSuffix(fileName: String): String = {
    // For now, require exactly 1 dot. Later: don't store the suffix at all?
    // Instead, derive it based on the mime type. Could use Apache Tika.
    // See: http://stackoverflow.com/questions/13650372/
    //        how-to-determine-appropriate-file-extension-from-mime-type-in-java
    // the answer: MimeTypes.getDefaultMimeTypes().forName("image/jpeg").getExtension()
    // and: need include xml file from Tika jar, it has all mime definitions.

    if (fileName.contains(".tar.")) throwForbidden(
      "DwE8PMU2", o"""Please change the suffix from e.g. ".tar.gz" to ".tgz", because only the "
      characters after the very last dot are used as the suffix""")

    // (Convert to lowercase, don't want e.g. both .JPG and .jpg.)
    val suffix = fileName.takeRightWhile(_ != '.').toLowerCase

    // Common image file formats:
    // (https://www.library.cornell.edu/preservation/tutorial/presentation/table7-1.html)
    if ("tif tiff gif jpeg jpg jif jfif jp2 jpx j2k j2c fpx pcd png pdf".contains(suffix))
      return suffix

    // Common movie file formats: (https://en.wikipedia.org/wiki/Video_file_format)
    if ("webm mkv ogv ogg gifv mp4 m4v".contains(suffix))
      return suffix

    if (fileName.count(_ == '.') > 1) throwForbidden(
      "DwE2FPYU0", o"""The file name should have exactly one dot, otherwise I don't know where
           the file suffix starts""")

    if (!fileName.exists(_ == '.'))
      throwForbidden("DwE6UPM5", "The file has no suffix")

    if (suffix.length > MaxSuffixLength)
      throwBadRequest("DwE7F3P5", o"""File has too long suffix: '$fileName'
        (max $MaxSuffixLength chars)""")

    if (suffix.isEmpty)
      throwForbidden("DwE2WUMF", "Empty file suffix, nothing after the dot in the file name")

    suffix
  }

}
