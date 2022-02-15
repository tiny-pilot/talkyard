/**
  * Copyright (c) 2022 Kaj Magnus Lindberg
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


sealed abstract class EventType

object EventType {
  case object PageCreated extends EventType
  case object PageStatusChange extends EventType
}


case class Event(
  when: When,
  eventType: EventType) {

}

object Event {
  def fromAuditLogItem(aIt: AuditLogEntry): Opt[Event] = {
    val when = When.fromDate(aIt.doneAt)

    val event = aIt.didWhat match {
      case AuditLogEntryType.NewChatMessage => ???
      case AuditLogEntryType.NewReply => ???
      case AuditLogEntryType.NewPage =>
        Event(when = when, eventType = EventType.PageCreated)

      case AuditLogEntryType.PageAnswered => ???
      case AuditLogEntryType.PagePlanned => ???
      case AuditLogEntryType.PageStarted => ???
      case AuditLogEntryType.PageDone => ???

      case AuditLogEntryType.PageClosed => ???
      case AuditLogEntryType.PageOpened => ???

      case AuditLogEntryType.DeletePage => ???
      case AuditLogEntryType.UndeletePage => ???

      case AuditLogEntryType.EditPost => ???

      case _ =>
        return None
    }

    Some(event)
  }
}