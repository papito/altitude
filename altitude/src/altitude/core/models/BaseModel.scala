package altitude.core.models

import java.time.LocalDateTime

abstract class BaseModel:
  val id: Option[String]
  val createdAt: Option[LocalDateTime]
  val updatedAt: Option[LocalDateTime]

  // Should be always used to get the ID of an object, unless we are positive that
  // the object has not been persisted yet
  def persistedId: String =
    id match
      case None => throw RuntimeException("Cannot get persisted ID for a model that has not been saved yet")
      case _ => id.get

  override def toString: String = s"<${getClass.getSimpleName}> ${id.getOrElse("NO ID")}"
