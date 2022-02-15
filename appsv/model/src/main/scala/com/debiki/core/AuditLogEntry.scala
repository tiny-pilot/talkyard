/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
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

package com.debiki.core

import com.debiki.core.Prelude._
import java.{util => ju}


/** Derived from the browser ip address, via something like http://ipinfo.io/.
  */
case class BrowserLocation(
  country: String,
  region: Option[String],
  city: Option[String])


sealed abstract class AuditLogEntryType(protected val IntVal: Int) { def toInt: Int = IntVal }
object AuditLogEntryType {
  // Let 1-999 be about content?
  case object CreateSite extends AuditLogEntryType(1)
  case object ThisSiteCreated extends AuditLogEntryType(2)
  case object CreateForum extends AuditLogEntryType(12)
  case object NewPage extends AuditLogEntryType(3)
  case object NewReply extends AuditLogEntryType(4)
  case object NewChatMessage extends AuditLogEntryType(5)
  case object EditPost extends AuditLogEntryType(6)
  case object ChangePostSettings extends AuditLogEntryType(7)
  case object MovePost extends AuditLogEntryType(11)
  case object UploadFile extends AuditLogEntryType(8)
  case object DeletePage extends AuditLogEntryType(9)
  case object UndeletePage extends AuditLogEntryType(10)

  // Maybe there'll be an array with events, e.g. [PageClosed, PageAnswered]?
  // (Since a page gets closed, once an answer has been selected.)
  // But that could be a client side thing?
  // Here, should be enough to store actiosn by the users.
  case object PageClosed  extends AuditLogEntryType(101)
  case object PageOpened  extends AuditLogEntryType(102)

  case object PageAnswered  extends AuditLogEntryType(101)
  case object PagePlanned  extends AuditLogEntryType(101)
  case object PageStarted  extends AuditLogEntryType(101)
  case object PageDone  extends AuditLogEntryType(101)

  // Let 1001-1999 be about people?
  case object CreateUser extends AuditLogEntryType(1001)
  // later ----
  case object ApproveUser extends AuditLogEntryType(1002)
  case object SuspendUser extends AuditLogEntryType(1003)
  case object UnsuspendUser extends AuditLogEntryType(1004)
  // Block, unblock.
  // Edit profile. etc.
  //-----------
  case object DeactivateUser extends AuditLogEntryType(1997)
  case object ReactivateUser extends AuditLogEntryType(1998)
  case object DeleteUser extends AuditLogEntryType(1999)
  // (Cannot undelete.)

  // Let 2001-2999 be admin & staff actions?
  case object SaveSiteSettings extends AuditLogEntryType(2001)
  case object MakeReviewDecision extends AuditLogEntryType(2002)
  case object UndoReviewDecision extends AuditLogEntryType(2003)


  def fromInt(value: Int): Option[AuditLogEntryType] = Some(value match {
    case CreateSite.IntVal => CreateSite
    case ThisSiteCreated.IntVal => ThisSiteCreated
    case CreateForum.IntVal => CreateForum
    case NewPage.IntVal => NewPage
    case NewReply.IntVal => NewReply
    case NewChatMessage.IntVal => NewChatMessage
    case EditPost.IntVal => EditPost
    case ChangePostSettings.IntVal => ChangePostSettings
    case MovePost.IntVal => MovePost
    case UploadFile.IntVal => UploadFile
    case DeletePage.IntVal => DeletePage
    case UndeletePage.IntVal => UndeletePage
    case CreateUser.IntVal => CreateUser
    case ApproveUser.IntVal => ApproveUser
    case SuspendUser.IntVal => SuspendUser
    case UnsuspendUser.IntVal => UnsuspendUser
    case DeactivateUser.IntVal => DeactivateUser
    case ReactivateUser.IntVal => ReactivateUser
    case DeleteUser.IntVal => DeleteUser
    case SaveSiteSettings.IntVal => SaveSiteSettings
    case MakeReviewDecision.IntVal => MakeReviewDecision
    case UndoReviewDecision.IntVal => UndoReviewDecision
    case _ => return None
  })
}


case class AuditLogEntry(
  siteId: SiteId,
  id: AuditLogEntryId,
  didWhat: AuditLogEntryType,
  doerId: UserId,
  doneAt: ju.Date,
  browserIdData: BrowserIdData,
  browserLocation: Option[BrowserLocation] = None,
  emailAddress: Option[String] = None,
  pageId: Option[PageId] = None,
  pageType: Option[PageType] = None,
  uniquePostId: Option[PostId] = None,
  postNr: Option[PostNr] = None,
  uploadHashPathSuffix: Option[String] = None,
  uploadFileName: Option[String] = None,
  sizeBytes: Option[Int] = None,
  targetUniquePostId: Option[PostId] = None,
  targetSiteId: Option[SiteId] = None, // CLEAN_UP ought to RENAME to otherSiteId, rename db column too
  targetPageId: Option[PageId] = None,
  targetPostNr: Option[PostNr] = None,
  targetUserId: Option[UserId] = None,
  batchId: Option[AuditLogEntryId] = None,
  isLoading: Boolean = false) {

  if (!isLoading) {
    val T = AuditLogEntryType
    emailAddress.foreach(Validation.requireOkEmail(_, "EsE5YJK2"))
    require(pageType.isEmpty || pageId.isDefined, "DwE4PFKW7")
    require(postNr.isEmpty || pageId.isDefined, "DwE3574FK2")
    require(postNr.isDefined == uniquePostId.isDefined, "DwE2WKEFW8")
    requireIf(didWhat == T.NewPage, pageId.isDefined && uniquePostId.isDefined, "EdE5PFK2")
    requireIf(didWhat == T.DeletePage || didWhat == T.UndeletePage,
                pageId.isDefined && uniquePostId.isEmpty, "EdE7ZXCY4")
    // COULD check uploaded file hash-path-suffix regex, see UploadsDao in debiki-server.
    require(!uploadHashPathSuffix.exists(_.trim.isEmpty), "DwE0PMF2")
    require(!uploadFileName.exists(_.trim.isEmpty), "DwE7UPM1")
    require(!sizeBytes.exists(_ < 0), "DwE7UMF4")
    require(targetPostNr.isDefined == targetUniquePostId.isDefined, "DwE4QU38")
    require(targetPostNr.isEmpty || targetPageId.isDefined, "DwE5PFK2")
    require(!batchId.exists(_ > id), "EsE5PK2L8")
    require(!batchId.exists(_ <= 0), "EsE8YJK52")
  }
}


object AuditLogEntry {
  val UnassignedId = 0
  val FirstId = 1
}

