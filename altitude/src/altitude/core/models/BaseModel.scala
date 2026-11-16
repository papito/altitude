package altitude.core.models

import java.time.LocalDateTime

abstract class BaseModel:
  val id: Option[String]
  val createdAt: Option[LocalDateTime]
  val updatedAt: Option[LocalDateTime]

  // The ID of a stored model; throws for one not saved yet
  def persistedId: String =
    id match
      case None => throw RuntimeException("Cannot get persisted ID for a model that has not been saved yet")
      case _ => id.get

  override def toString: String = s"<${getClass.getSimpleName}> ${id.getOrElse("NO ID")}"
