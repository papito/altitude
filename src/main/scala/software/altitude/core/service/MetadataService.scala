package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.Validators.ModelDataValidator
import software.altitude.core.dao.AssetDao
import software.altitude.core.dao.MetadataFieldDao
import software.altitude.core.models._
import software.altitude.core.transactions.AbstractTransactionManager
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.Query
import software.altitude.core.{Const => C, _}

import scala.util.control.Breaks._

object MetadataService {
  class MetadataFieldValidator
    extends ModelDataValidator(
      required = Some(
        List(C.MetadataField.NAME, C.MetadataField.FIELD_TYPE)))

  final val METADATA_FIELD_VALIDATOR = new MetadataService.MetadataFieldValidator

  final val METADATA_FIELD_CLEANER: Cleaners.Cleaner = Cleaners.Cleaner(
    trim = Some(List(C.MetadataField.NAME, C.MetadataField.FIELD_TYPE)))

  final val VALID_BOOLEAN_VALUES: Set[String] = Set("0", "1", "true", "false")
}

class MetadataService(val app: Altitude) extends ModelValidation {
  private final val log = LoggerFactory.getLogger(getClass)

  protected val txManager: AbstractTransactionManager = app.injector.instance[AbstractTransactionManager]
  protected val metadataFieldDao: MetadataFieldDao = app.injector.instance[MetadataFieldDao]
  protected val assetDao: AssetDao = app.injector.instance[AssetDao]

  def addField(metadataField: MetadataField)
              (implicit ctx: Context, txId: TransactionId = new TransactionId): MetadataField = {

    txManager.withTransaction[MetadataField] {
      val cleaned: MetadataField = cleanAndValidate(
        metadataField, Some(MetadataService.METADATA_FIELD_CLEANER), Some(MetadataService.METADATA_FIELD_VALIDATOR))

      val existing = metadataFieldDao.query(new Query(params = Map(
        C.MetadataField.NAME_LC -> cleaned.nameLowercase
      )))

      if (existing.nonEmpty) {
        val existingField: MetadataField = existing.records.head
        log.debug(s"Duplicate found for field [${cleaned.name}]")
        throw DuplicateException(existingField.id.get)
      }

      metadataFieldDao.add(cleaned)
    }
  }

  /**
   * Returns a lookup map (by ID) of all configured fields in this repository
   */
  def getAllFields(implicit ctx: Context, txId: TransactionId = new TransactionId): Map[String, MetadataField] =
    txManager.asReadOnly[Map[String, MetadataField]] {
      metadataFieldDao.getAll.map{ res =>
        val metadataField: MetadataField = res
        metadataField.id.get -> metadataField
      }.toMap
    }

  def getFieldById(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): JsObject =
    txManager.asReadOnly[JsObject] {
      metadataFieldDao.getById(id) match {
        case Some(obj) => obj
        case None => throw NotFoundException(s"Cannot find field by ID [$id]")
      }
    }

  def deleteFieldById(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): Int =
    txManager.withTransaction[Int] {
      metadataFieldDao.deleteById(id)
    }

  def getMetadata(assetId: String)
                 (implicit ctx: Context, txId: TransactionId = new TransactionId): Metadata =
    // return the metadata or a new empty one if blank
    assetDao.getMetadata(assetId) match {
      case Some(metadata) => metadata
      case None => Metadata()
    }

  def setMetadata(assetId: String, metadata: Metadata)
               (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    log.info(s"Setting metadata for asset [$assetId]: $metadata")

    txManager.withTransaction {
      val cleanMetadata = cleanAndValidate(metadata)

      assetDao.setMetadata(assetId = assetId, metadata = cleanMetadata)
    }
  }

  // OPTIMIZE: this cleans and validates existing values (the ones that have IDs)
  def updateMetadata(assetId: String, metadata: Metadata)
                 (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    log.info(s"Updating metadata for asset [$assetId]: $metadata")

    txManager.withTransaction {
      val cleanMetadata = cleanAndValidate(metadata)
      /**
       * If the cleaned metadata does not have fields found in the original -
       * those are empty and should be deleted
       */
      val deletedFields = metadata.data.keys.filterNot(cleanMetadata.data.keys.toSet.contains).toSet

      assetDao.updateMetadata(assetId, cleanMetadata, deletedFields)
    }
  }

  def addFieldValue(assetId: String, fieldId: String, newValue: String)
                    (implicit ctx: Context, txId: TransactionId): Unit = {
    log.info(s"Adding value [$newValue] for field [$fieldId] on asset [$assetId] ")

    txManager.withTransaction {
      val metadata = Metadata(Map(fieldId -> Set(MetadataValue(newValue))))
      val cleanMetadata = cleanAndValidate(metadata)

      // if after cleaning the value is not there - it's empty
      if (!cleanMetadata.contains(fieldId)) {
        val ex = ValidationException()
        ex.errors += (fieldId -> C.Msg.Err.CANNOT_BE_EMPTY)
        ex.trigger()
      }

      val cleanValue = cleanMetadata.get(fieldId).get.head

      // cannot add a second value to a single-value field
      val field: MetadataField = getFieldById(fieldId)
      val currentMetadata = app.service.metadata.getMetadata(assetId)
      val existingValues = currentMetadata.get(fieldId).getOrElse(Set[MetadataValue]())

      if (field.fieldType != FieldType.BOOL) {
        // check duplicate
        if (existingValues.contains(cleanValue)) {
          val ex = ValidationException()
          ex.errors += (fieldId -> C.Msg.Err.DUPLICATE)
          ex.trigger()
        }
      }

      val currentValues: Set[MetadataValue] = if (currentMetadata.get(fieldId).isEmpty) {
        Set[MetadataValue]()
      }
      else {
        currentMetadata.get(fieldId).get
      }

      val newValues = field.fieldType match {
        // boolean values replaces each other
        case FieldType.BOOL => Set(cleanValue)
        // non-boolean values are appended
        case _ => currentValues + cleanValue
      }

      val data = Map[String, Set[MetadataValue]](fieldId -> newValues)
      updateMetadata(assetId, Metadata(data))
    }
  }

  def deleteFieldValue(assetId: String, valueId: String)
                      (implicit ctx: Context, txId: TransactionId ): Unit = {
    log.info(s"Deleting value [$valueId] for on asset [$assetId] ")

    txManager.withTransaction {
      val currentMetadata = getMetadata(assetId)

      // find the field that has the value and filter it out
      val newData = currentMetadata.data.map { item =>
        val oldValues = item._2
        item._1 -> oldValues.filterNot(_.id.contains(valueId))
      }

      // FIXME: NotFound
      require(newData.nonEmpty)

      updateMetadata(assetId, Metadata(newData))
    }
  }

  def updateFieldValue(assetId: String, valueId: String, newValue: String)
                      (implicit ctx: Context, txId: TransactionId): Unit = {

    log.info(s"Updating value [$valueId] for on asset [$assetId] with [$newValue] ")

    txManager.withTransaction {
      val currentMetadata = getMetadata(assetId)

      val newMdVal = MetadataValue(id = Some(valueId), value = newValue)
      // find the field that has the value by ID
      val search = currentMetadata.data.filter(_._2/* values */.exists(_.id.contains(valueId)))

      // FIXME: NotFound
      require(search.size == 1)

      val (fieldId, currentMdVals) = search.head

      val metadata = Metadata(Map(fieldId -> Set(MetadataValue(newValue))))
      val cleanMetadata = cleanAndValidate(metadata)

      // if after cleaning the value is not there - it's empty
      if (!cleanMetadata.contains(fieldId)) {
        val ex = ValidationException()
        ex.errors += (fieldId -> C.Msg.Err.CANNOT_BE_EMPTY)
        ex.trigger()
      }

      val cleamMdVal = cleanMetadata.get(fieldId).get.head

      val existingMdVal = currentMdVals.find(_.id.contains(valueId))

      // FIXME: NotFound
      require(existingMdVal.nonEmpty)

      // bail if the new values is identical to the old one
      if (existingMdVal.get.value == newMdVal.value) {
        return
      }

      // when checking for existing values, ignore the current ID
      if (currentMdVals.filterNot(_.id.contains(valueId)).contains(cleamMdVal)) {
        val ex = ValidationException()
        ex.errors += (fieldId -> C.Msg.Err.DUPLICATE)
        ex.trigger()
      }

      val newData = currentMetadata.data.map { item =>
        val fId = item._1
        val mdVals = item._2

        // return all values as is, only replacing the one value we are working on
        val newMdVals = if (fId == fieldId) {
          mdVals.map { v =>
            if (v.id.get == valueId) newMdVal else v
          }
        }
        else {
          mdVals
        }

        fId -> newMdVals
      }

      updateMetadata(assetId, Metadata(newData))
    }
  }

  def clean(metadata: Metadata)
           (implicit ctx: Context, txId: TransactionId): Metadata = {
    // get all metadata fields configured for this repository
    val fields = getAllFields

    // make sure all metadata field IDs given to us are known
    val existingFieldIds = fields.keys.toSet
    val suppliedFieldIds = metadata.keys

    val missing = suppliedFieldIds.diff(existingFieldIds)

    if (missing.nonEmpty) {
      throw NotFoundException(
        s"Fields [${missing.mkString(", ")}] are not supported by this repository"
      )
    }

    /**
     * Clean the metadata to be ready for validation
     */
    val cleanData = metadata.data.foldLeft(Map[String, Set[MetadataValue]]()) { (res, m) =>
      val fieldId = m._1
      val field: MetadataField = fields(fieldId)
      val mdVals: Set[MetadataValue] = m._2

      val trimmed: Set[MetadataValue] = field.fieldType match {
        case FieldType.KEYWORD => {
          mdVals
          // trim leading/trailing
          .map{ mdVal => MetadataValue(mdVal.id, mdVal.value.trim) }
          // compact multiple space characters into one
          .map{ mdVal => MetadataValue(mdVal.id, mdVal.value.replaceAll("[\\s]{2,}", " "))}
          // force a space character to be vanilla whitespace
          .map{ mdVal => MetadataValue(mdVal.id, mdVal.value.replaceAll("\\s", " ")) }
          // and lose the blanks
          .filter(_.nonEmpty)
        }

        case FieldType.TEXT => {
          mdVals
          // trim leading/trailing
          .map{ mdVal => MetadataValue(mdVal.id, mdVal.value.trim) }
          // and lose the blanks
          .filter(_.nonEmpty)
        }

        case FieldType.NUMBER | FieldType.BOOL => {
          mdVals
          // trim leading/trailing
          .map{ mdVal => MetadataValue(mdVal.id, mdVal.value.trim) }
          // and lose the blanks
          .filter(_.nonEmpty)
        }
      }

      if (trimmed.nonEmpty) res + (fieldId -> trimmed) else res
    }

    val cleanMetadata = Metadata(data = cleanData)
    cleanMetadata.isClean = true
    cleanMetadata
  }

  def validate(metadata: Metadata)(implicit ctx: Context, txId: TransactionId): Unit = {
    if (metadata.data.isEmpty) {
      return
    }

    // get all metadata fields configured for this repository
    // OPTIMIZE: only get the fields in the metadata
    val fields = getAllFields

    val ex = ValidationException()

    // for each field
    metadata.data.foreach { m =>
      val fieldId = m._1
      val field: MetadataField = fields(fieldId)
      val mdVals: Set[MetadataValue] = m._2

      breakable {
        // booleans cannot have multiple values
        if (field.fieldType == FieldType.BOOL && mdVals.size > 1) {
          ex.errors += (field.id.get -> C.Msg.Err.INCORRECT_VALUE_TYPE.format(field.name))
          break()
        }

        val illegalValues = collectInvalidTypeValues(field.fieldType, mdVals)

        // add to the validation exception if any
        if (illegalValues.nonEmpty) {
          ex.errors += (field.id.get ->
            C.Msg.Err.INCORRECT_VALUE_TYPE.format(field.name, illegalValues.mkString(", ")))
        }
      }
    }

    ex.trigger()
  }

  /**
   * Makes sure the metadata fields are configured in this system after common-sense data
   * hygiene. Validates correct type for anything
   *
   * @return clean, de-duplicated copy of the metadata
   */
  def cleanAndValidate(metadata: Metadata)
                      (implicit ctx: Context, txId: TransactionId): Metadata = {
    val cleanMetadata = clean(metadata)
    validate(cleanMetadata)
    cleanMetadata
  }

  /*
   */

  /**
    * Presentation-level JSON transformer for metadata. This augments the limiting metadata JSON object to supply
    * the names of fields, pulled from field configuration that the Metadata domain object is not aware of.
    *
    * On the way in we get:
    * {
    *   field id -> values
    *   field id -> values
    * }
    *
    * We get out:
    * [
    *   VALUES -> values[]
    *   FIELD_TYPE ->
    *     ID -> field id
    *     NAME -> field name
    *     FIELD_TYPE -> field type
    *
    *   VALUES -> values[]
    *   FIELD_TYPE ->
    *     ID -> field id
    *     NAME -> field name
    *     FIELD_TYPE -> field type
    * ]
    */
  def toJson(metadata: Metadata, allMetadataFields: Option[Map[String, MetadataField]] = None)
            (implicit ctx: Context, txId: TransactionId = new TransactionId): JsArray = {

    txManager.asReadOnly[JsArray] {
      val allFields = if (allMetadataFields.isDefined) allMetadataFields.get else getAllFields

      def toJson(field: MetadataField, mdVals: Set[MetadataValue]): JsObject = {
        Json.obj(
          C.MetadataField.FIELD -> (field.toJson -
            C.Base.UPDATED_AT -
            C.Base.CREATED_AT -
            C.MetadataField.NAME_LC),
          C.MetadataField.VALUES -> JsArray(mdVals.toSeq.map(_.toJson))
        )
      }

      val res = metadata.data.foldLeft(scala.collection.Seq[JsValue]()) { (res, m) =>
        val fieldId = m._1
        val field: MetadataField = allFields(fieldId)
        res :+ toJson(field, m._2)
      }

      // add the missing fields that have no values
      val emptyFields = allFields.filterNot { case (fieldId, _) =>
        metadata.contains(fieldId)
      }.map { case (fieldId, _) =>
        val field: MetadataField = allFields(fieldId)
        toJson(field, Set[MetadataValue]())
      }

      val sorted = (res ++ emptyFields).sortWith{ (left, right) =>
        val leftFieldName: String = (left \ C.MetadataField.FIELD \ C.MetadataField.NAME).as[String]
        val rightFieldName: String = (right \ C.MetadataField.FIELD \ C.MetadataField.NAME).as[String]
        leftFieldName.compareToIgnoreCase(rightFieldName) < 1
      }

      JsArray(sorted)
    }
  }

  /**
   * Given a field type and a set values, collect all the values that DO NOT pass
   * type checks.
   *
   * @param fieldType The type of field values for each we are validating
   * @param values Values that may or may note pass type validation
   * @return All values that FAIL type validation
   */
  def collectInvalidTypeValues(fieldType: FieldType.Value, values: Set[MetadataValue]): Set[String] = {
    // FIXME: foldLeft is better-suited here
    values.map { mdVal =>
      fieldType match {
        case FieldType.NUMBER => try {
          mdVal.value.toDouble
          None
        } catch {
          case _: Throwable => Some(mdVal.value)
        }
        case FieldType.KEYWORD => None // everything is allowed
        case FieldType.TEXT => None // everything is allowed
        case FieldType.BOOL => // only values that we recognize as booleans
          if (MetadataService.VALID_BOOLEAN_VALUES.contains(mdVal.value.toLowerCase)) {
            None
          }
          else {
            Some(mdVal.value)
          }
      }
      // get rid of None's - those are valid values
    }.filter(_.isDefined).map(_.get)
  }

}
