/**
 * Copyright (C) 2017 Kaj Magnus Lindberg
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

package ed.server.auth

import com.debiki.core._
import com.debiki.core.Prelude._
import scala.collection.immutable
import MayMaybe._


sealed abstract class MayMaybe(private val may: Boolean) { def mayNot: Boolean = !may }
object MayMaybe {
  case object Yes extends MayMaybe(true)
  case class NoMayNot(code: String, reason: String) extends MayMaybe(false)
  case class NoNotFound(debugCode: String) extends MayMaybe(false)
}


/** Checks if a member may e.g. create pages, post replies, wikify, ... and so on.
  * And, if not, tells you why not: all functions returns a why-may-not reason.
  */
object Authz {

  private val MayNothing: MayWhat = MayWhat.mayNotSee("EdMMAY0")

  private val MayEverything: MayWhat = MayWhat(mayEditPage = true, mayEditComment = true,
    mayEditWiki = true, mayDeletePage = true, mayDeleteComment = true, mayCreatePage = true,
    mayPostComment = true, maySee = true, "EdMEVRYTNG")


  def mayCreatePage(
    userAndLevels: UserAndLevels,
    groupIds: immutable.Seq[GroupId],
    pageRole: PageRole,
    bodyPostType: PostType,
    pinWhere: Option[PinPageWhere],
    anySlug: Option[String],
    anyFolder: Option[String],
    inCategoriesRootLast: immutable.Seq[Category],
    relevantPermissions: immutable.Seq[PermsOnPages]): MayMaybe = {

    val user = userAndLevels.user

    val mayWhat = checkPermsOnPages(Some(user), groupIds, pageMeta = None, pageMembers = None,
      inCategoriesRootLast, relevantPermissions)

    if (!mayWhat.maySee)
      return NoNotFound(s"EdE4FDW2-${mayWhat.debugCode}")

    if (!mayWhat.mayCreatePage)
      return NoMayNot(s"EdEMNOTCRPG-${mayWhat.debugCode}", "May not create a page in this category")

    if (!user.isStaff) {
      if (inCategoriesRootLast.isEmpty && pageRole != PageRole.FormalMessage)
        return NoMayNot("EsE8GY32", "Only staff may create pages outside any category")

      if (anySlug.exists(_.nonEmpty))
        return NoMayNot("DwE4KFW87", "Only staff may specify page slug")

      if (pageRole.staffOnly)
        return NoMayNot("DwE5KEPY2", s"Forbidden page type: $pageRole")
    }

    Yes
  }


  def maySeePage(
    pageMeta: PageMeta,
    user: Option[User],
    groupIds: immutable.Seq[GroupId],
    pageMembers: Set[UserId],
    categoriesRootLast: immutable.Seq[Category],
    relevantPermissions: immutable.Seq[PermsOnPages],
    maySeeUnlisted: Boolean = true): MayMaybe = {

    val mayWhat = checkPermsOnPages(user, groupIds, Some(pageMeta), Some(pageMembers),
      categoriesRootLast, relevantPermissions, maySeeUnlisted = maySeeUnlisted)

    if (!mayWhat.maySee)
      return NoNotFound(s"EdE4KW0HD5-${mayWhat.debugCode}")

    Yes
  }


  def mayPostReply(
    userAndLevels: UserAndLevels,
    groupIds: immutable.Seq[GroupId],
    postType: PostType,
    pageMeta: PageMeta,
    privateGroupTalkMemberIds: Set[UserId],
    inCategoriesRootLast: immutable.Seq[Category],
    relevantPermissions: immutable.Seq[PermsOnPages]): MayMaybe = {

    val user = userAndLevels.user

    SHOULD // be check-perms-on pageid + postnr, not just page
    val mayWhat = checkPermsOnPages(Some(user), groupIds, Some(pageMeta),
      Some(privateGroupTalkMemberIds), inCategoriesRootLast, relevantPermissions)

    if (!mayWhat.maySee)
      return NoNotFound(s"EdE5LKW0R-${mayWhat.debugCode}")

    if (!mayWhat.mayPostComment)
      return NoMayNot("EdE2WRKD5", "You don't have permissions to post a reply on this page")

    // Mind maps can easily get messed up by people posting comments. So, for now, only
    // allow the page author + staff to add stuff to a mind map. [7KUE20]
    if (pageMeta.pageRole == PageRole.MindMap) {
      if (user.id != pageMeta.authorId && !user.isStaff)
        return NoMayNot("EsE6JK4I0", "Only the page author and staff may edit this mind map")
    }

    if (!pageMeta.pageRole.canHaveReplies)
      return NoMayNot("EsE8YGK42", s"Cannot post to page type ${pageMeta.pageRole}")

    Yes
  }


  def mayFlagPost(
    member: Member,
    groupIds: immutable.Seq[GroupId],
    post: Post,
    pageMeta: PageMeta,
    privateGroupTalkMemberIds: Set[UserId],
    inCategoriesRootLast: immutable.Seq[Category],
    relevantPermissions: immutable.Seq[PermsOnPages]): MayMaybe = {

    if (member.effectiveTrustLevel == TrustLevel.New) {
      COULD // Later: Check site settings to find out if members may flag stuff.
      // Small forums: everyone may flag. Medium/large: new users may not flag?
    }

    if (member.threatLevel.isSevereOrWorse)
      return NoMayNot(s"EdE2FK49T", "You may not flag stuff, sorry")

    SHOULD // be maySeePost pageid, postnr, not just page
    val mayWhat = checkPermsOnPages(Some(member), groupIds, Some(pageMeta),
      Some(privateGroupTalkMemberIds), inCategoriesRootLast, relevantPermissions)

    if (!mayWhat.maySee)
      return NoNotFound("EdE2GKF8Y4")

    Yes
  }


  def maySubmitCustomForm(
    userAndLevels: AnyUserAndThreatLevel,
    groupIds: immutable.Seq[GroupId],
    pageMeta: PageMeta,
    inCategoriesRootLast: immutable.Seq[Category],
    relevantPermissions: immutable.Seq[PermsOnPages]): MayMaybe = {

    val user = userAndLevels.user

    val mayWhat = checkPermsOnPages(user, groupIds, Some(pageMeta), None, inCategoriesRootLast,
      relevantPermissions)

    if (!mayWhat.maySee)
      return NoNotFound("EdE1KBTSW04")

    if (!mayWhat.mayPostComment)
      return NoMayNot("EdE5KFL02", "You don't have permissions to submit this form")

    if (pageMeta.pageRole != PageRole.WebPage && pageMeta.pageRole != PageRole.Form) {
      return NoMayNot("EsE4PBRN2F", s"Cannot submit custom forms to page type ${pageMeta.pageRole}")
    }

    dieUnless(pageMeta.pageRole.canHaveReplies, "EdE5PJWK20")

    Yes
  }


  private def checkPermsOnPages(
    user: Option[User],
    groupIds: immutable.Seq[GroupId],
    pageMeta: Option[PageMeta],
    pageMembers: Option[Set[UserId]],
    categoriesRootLast: immutable.Seq[Category],
    permissions: immutable.Seq[PermsOnPages],
    maySeeUnlisted: Boolean = true): MayWhat = {

    if (user.exists(_.isAdmin))
      return MayEverything

    val isStaff = user.exists(_.isStaff)
    val pageRole = pageMeta.map(_.pageRole)

    // For now, don't let people see pages outside any category. Hmm...?
    // (<= 1 not 0: don't count the root category, no pages should be placed directly in them.)
    /* Enable this later, need to migrate test cases first.
    if (categoriesRootLast.length <= 1 && !pageRole.exists(_.isPrivateGroupTalk))
      return MayWhat.mayNotSee("EdMNOCATS")
    */

    pageMeta foreach { meta =>
      categoriesRootLast.headOption foreach { parentCategory =>
        dieIf(!meta.categoryId.contains(parentCategory.id), "EdE5PBSW2")
      }

      // These page types are for admins only.
      if (meta.pageRole == PageRole.SpecialContent || meta.pageRole == PageRole.Code)
        return MayWhat.mayNotSee("EsE4YK02R-Code")

      // Only page participants may see things like private chats or private formal messages.
      if (meta.pageRole.isPrivateGroupTalk) {
        val thePageMembers = pageMembers getOrDie "EdE2SUH5G"
        val theUser = user getOrElse {
          return MayWhat.mayNotSee("EsE4YK032-No-User")
        }

        if (!theUser.isMember)
          return MayWhat.mayNotSee("EsE2GYF04-Is-Guest")

        if (!thePageMembers.contains(theUser.id))
          return MayWhat.mayNotSee("EsE5K8W27-Not-Page-Member")
      }
    }

    val relevantPermissions = permissions filter { permission =>
      groupIds.contains(permission.forPeopleId)
    }

    // We'll start with no permissions, at the top category, and loop through all categories
    // down to the category in which the page is placed, and add/remove permissions along the way.
    var mayWhat = MayNothing
    val isUsersPage = user.exists(u => pageMeta.exists(_.authorId == u.id))
    var isDeleted = pageMeta.exists(_.isDeleted)

    // Later: return may-not-see also if !published?
    if (isDeleted && !isUsersPage && !isStaff)
      return MayWhat.mayNotSee("EdEPAGEDELD")

    // [BACKW_COMPAT_PERMS] Remove this after I've inserted groups & permissions into the db.
    if (isStaff) {
      mayWhat = MayEverything
    }
    else {
      mayWhat = MayWhat(maySee = true, mayPostComment = true, mayCreatePage = true,
        debugCode = "EdM2KW0Y5")
    }

    if (categoriesRootLast.nonEmpty) for (category <- categoriesRootLast.init.reverse) {
      for (p <- relevantPermissions; if p.onCategoryId.contains(category.id)) {
        mayWhat = mayWhat.addRemovePermissions(p, "EdMCATLOOP")
      }

      if (category.isDeleted) {
        isDeleted = true
        if (!isStaff)
          return MayWhat.mayNotSee("EdECATDELD")
      }

      // [BACKW_COMPAT_PERMS] should remove !isStaff but first need to update some e2e tests.
      if (!isStaff && !maySeeUnlisted && category.unlisted)
        return MayWhat.mayNotSee("EdE6WKQ0-Unlisted")

      CLEAN_UP // deprecated, try to remove [5FKQWU02]
      if (!isStaff && category.staffOnly)
        return MayWhat.mayNotSee("EdE8YGK25-Staff-Only-Cat")

      CLEAN_UP // Deprecated
      if (!isStaff && category.onlyStaffMayCreateTopics)
        mayWhat = mayWhat.copy(mayCreatePage = false)

      // Abort if we may not see this category, because then we may not see any child cats either.
      if (!mayWhat.maySee)
        return mayWhat
    }

    // Do this first here, so the is-deleted changes won't get overwritten in later loop laps above.
    if (isDeleted) {
      mayWhat = mayWhat.copyAsDeleted
    }

    mayWhat
  }

}