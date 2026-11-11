package altitude.core.service
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core._
import altitude.core.{ Const => C }
import altitude.core.dao.AssetDao
import altitude.core.dao.UserMetadataFieldDao
import altitude.core.models._
import altitude.core.transactions.TransactionManager
import altitude.core.util.NameKey
import altitude.core.util.Query

class UserMetadataService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val metadataFieldDao: UserMetadataFieldDao = app.DAO.metadataField
  private val assetDao: AssetDao = app.DAO.asset

  /**
   * Adds the field under its name in NFC once the name passes the name rule (`Validators.validName`, a `ValidationException`); a
   * field whose name key the repository already holds is a `DuplicateException`
   */
  def addField(metadataField: UserMetadataField): UserMetadataField =
    val name =
      Validators.validName(metadataField.name, Api.Field.Metadata.Field.NAME, Api.Constraints.MAX_METADATA_FIELD_NAME_LENGTH)
    val field = metadataField.copy(name = name)

    txManager.withTransaction {
      val existing = metadataFieldDao.query(new Query().add(FieldConst.MetadataField.NAME_KEY -> field.nameKey))

      if existing.nonEmpty then
        logger.debug(s"Duplicate found for field [${field.name}]")
        throw DuplicateException()

      logger.debug(s"Adding metadata field [${field.name}] of type [${field.fieldType}]")
      metadataFieldDao.add(field)
    }

  /** Returns a lookup map (by ID) of all configured fields in this repository */
  def getAllFields: Map[String, UserMetadataField] =
    txManager.asReadOnly {
      val allFields = metadataFieldDao.query(new Query()).records

      allFields.map(metadataField => metadataField.persistedId -> metadataField).toMap
    }

  def getFieldById(id: String): UserMetadataField =
    txManager.asReadOnly {
      metadataFieldDao.getById(id)
    }

  /** A field of another repository, or none, is a `NotFoundException` */
  def deleteFieldById(id: String): Int =
    txManager.withTransaction {
      logger.debug(s"Deleting metadata field [$id]")
      BaseService.requireFound(metadataFieldDao.tableName, id, metadataFieldDao.deleteById(id))
    }

  /** The asset's metadata, empty when it has none; an asset of another repository, or none, is a `NotFoundException` */
  def getMetadata(assetId: String): UserMetadata =
    txManager.asReadOnly {
      assetDao.getUserMetadata(assetId)
    }

  /** Replaces the asset's metadata; an asset of another repository, or none, is a `NotFoundException` */
  def setMetadata(assetId: String, metadata: UserMetadata): Unit =
    logger.debug(s"Setting metadata for asset [$assetId]: $metadata")

    txManager.withTransaction {
      val cleanMetadata = cleanAndValidate(metadata)
      val updated = assetDao.setUserMetadata(assetId = assetId, metadata = cleanMetadata)
      BaseService.requireFound(assetDao.tableName, assetId, updated): Unit
    }

  // OPTIMIZE: this cleans and validates existing values (the ones that have IDs)
  def updateMetadata(assetId: String, metadata: UserMetadata): Unit =
    logger.debug(s"Updating metadata for asset [$assetId]: $metadata")

    txManager.withTransaction {
      val cleanMetadata = cleanAndValidate(metadata)

      /** If the cleaned metadata does not have fields found in the original - those are empty and should be deleted */
      val deletedFields = metadata.data.keys.filterNot(cleanMetadata.data.keys.toSet.contains).toSet

      val updated = assetDao.updateMetadata(assetId, cleanMetadata, deletedFields)
      BaseService.requireFound(assetDao.tableName, assetId, updated): Unit
    }

  def addMetadataValue(assetId: String, fieldId: String, newValue: Any): Unit =
    txManager.withTransaction {
      app.service.metadata.addFieldValue(assetId, fieldId, newValue.toString)
      val field: UserMetadataField = app.service.metadata.getFieldById(fieldId)
      val asset: Asset = app.service.asset.getById(assetId)
      app.service.search.addMetadataValue(asset, field, newValue.toString)
    }

  def deleteMetadataValue(assetId: String, valueId: String): Unit =
    txManager.withTransaction {
      app.service.metadata.deleteFieldValue(assetId, valueId)
      val asset: Asset = app.service.asset.getById(assetId)
      // OPTIMIZE: store value ID with search to delete in a targeted way
      app.service.search.reindexAsset(asset)
    }

  def updateMetadataValue(assetId: String, valueId: String, newValue: Any): Unit =
    txManager.withTransaction {
      app.service.metadata.updateFieldValue(assetId, valueId, newValue.toString)
      val asset: Asset = app.service.asset.getById(assetId)
      // OPTIMIZE: store value ID with search to update in a more efficient way
      app.service.search.reindexAsset(asset)
    }

  private def addFieldValue(assetId: String, fieldId: String, newValue: String): Unit =
    logger.debug(s"Adding value [$newValue] for field [$fieldId] on asset [$assetId] ")

    txManager.withTransaction {
      val metadata = UserMetadata(Map(fieldId -> Set(UserMetadataValue(value = newValue))))
      val cleanMetadata = cleanAndValidate(metadata)

      // if after cleaning the value is not there - it's empty
      if !cleanMetadata.contains(fieldId) then
        val ex = ValidationException()
        ex.errors += (fieldId -> C.Msg.Err.VALUE_CANNOT_BE_EMPTY)
        ex.trigger()

      val cleanValue = cleanMetadata.get(fieldId).get.head

      // cannot add a second value to a single-value field
      val field: UserMetadataField = getFieldById(fieldId)
      val currentMetadata = app.service.metadata.getMetadata(assetId)
      val existingValues = currentMetadata.get(fieldId).getOrElse(Set[UserMetadataValue]())

      if field.fieldType != FieldType.BOOL then
        // check duplicate
        if existingValues.contains(cleanValue) then
          val ex = ValidationException()
          ex.errors += (fieldId -> C.Msg.Err.DUPLICATE)
          ex.trigger()

      val currentValues: Set[UserMetadataValue] =
        if currentMetadata.get(fieldId).isEmpty then Set[UserMetadataValue]()
        else currentMetadata.get(fieldId).get

      val newValues = field.fieldType match {
        // Boolean values replace existing values.
        case FieldType.BOOL => Set(cleanValue)
        // non-boolean values are appended
        case _ => currentValues + cleanValue
      }

      val data = Map[String, Set[UserMetadataValue]](fieldId -> newValues)
      updateMetadata(assetId, UserMetadata(data))
    }

  def deleteFieldValue(assetId: String, valueId: String): Unit =
    logger.debug(s"Deleting value [$valueId] for on asset [$assetId] ")

    txManager.withTransaction {
      val currentMetadata = getMetadata(assetId)

      // find the field that has the value and filter it out
      val newData = currentMetadata.data.map {
        item =>
          val oldValues = item._2
          item._1 -> oldValues.filterNot(_.id.contains(valueId))
      }

      // FIXME: NotFound
      require(newData.nonEmpty)

      updateMetadata(assetId, UserMetadata(newData))
    }

  def updateFieldValue(assetId: String, valueId: String, newValue: String): Unit =

    logger.debug(s"Updating value [$valueId] for on asset [$assetId] with [$newValue] ")

    txManager.withTransaction {
      val currentMetadata = getMetadata(assetId)

      val newMdVal = UserMetadataValue(id = Some(valueId), value = newValue)
      // find the field that has the value by ID
      val search = currentMetadata.data.filter(_._2 /* values */ .exists(_.id.contains(valueId)))

      // FIXME: NotFound
      require(search.size == 1)

      val (fieldId, currentMdVals) = search.head

      val metadata = UserMetadata(Map(fieldId -> Set(UserMetadataValue(value = newValue))))
      val cleanMetadata = cleanAndValidate(metadata)

      // if after cleaning the value is not there - it's empty
      if !cleanMetadata.contains(fieldId) then
        val ex = ValidationException()
        ex.errors += (fieldId -> C.Msg.Err.VALUE_CANNOT_BE_EMPTY)
        ex.trigger()

      val cleamMdVal = cleanMetadata.get(fieldId).get.head

      val existingMdVal = currentMdVals.find(_.id.contains(valueId))

      // FIXME: NotFound
      require(existingMdVal.isDefined)

      // bail if the new values is identical to the old one
      if existingMdVal.get.value != newMdVal.value then
        // when checking for existing values, ignore the current ID
        if currentMdVals.filterNot(_.id.contains(valueId)).contains(cleamMdVal) then
          val ex = ValidationException()
          ex.errors += (fieldId -> C.Msg.Err.DUPLICATE)
          ex.trigger()

        val newData = currentMetadata.data.map {
          item =>
            val fId = item._1
            val mdVals = item._2

            // return all values as is, only replacing the one value we are working on
            val newMdVals =
              if fId == fieldId then mdVals.map(v => if v.persistedId == valueId then newMdVal else v)
              else mdVals

            fId -> newMdVals
        }

        updateMetadata(assetId, UserMetadata(newData))
    }

  private def clean(metadata: UserMetadata): UserMetadata =
    // get all metadata fields configured for this repository
    val fields = getAllFields

    // make sure all metadata field IDs given to us are known
    val existingFieldIds = fields.keys.toSet
    val suppliedFieldIds = metadata.data.keys.toSet

    val missing = suppliedFieldIds.diff(existingFieldIds)

    if missing.nonEmpty then
      throw NotFoundException(
        s"Fields [${missing.mkString(", ")}] are not supported by this repository"
      )

    /** Clean the metadata to be ready for validation */
    val cleanData = metadata.data.foldLeft(Map[String, Set[UserMetadataValue]]()) {
      (res, m) =>
        val fieldId = m._1
        val field: UserMetadataField = fields(fieldId)
        // every value is stored in NFC, so a value typed with a combining accent and one typed precomposed read back alike
        val mdVals: Set[UserMetadataValue] = m._2.map(mdVal => UserMetadataValue(mdVal.id, NameKey.normalized(mdVal.value)))

        val trimmed: Set[UserMetadataValue] = field.fieldType match {
          case FieldType.KEYWORD =>
            mdVals
              // trim leading/trailing
              .map(mdVal => UserMetadataValue(mdVal.id, mdVal.value.trim))
              // compact multiple space characters into one
              .map(mdVal => UserMetadataValue(mdVal.id, mdVal.value.replaceAll("[\\s]{2,}", " ")))
              // force a space character to be vanilla whitespace
              .map(mdVal => UserMetadataValue(mdVal.id, mdVal.value.replaceAll("\\s", " ")))
              // and lose the blanks
              .filter(_.nonEmpty)

          case FieldType.TEXT =>
            mdVals
              // trim leading/trailing
              .map(mdVal => UserMetadataValue(mdVal.id, mdVal.value.trim))
              // and lose the blanks
              .filter(_.nonEmpty)

          case FieldType.NUMBER | FieldType.BOOL | FieldType.DATETIME =>
            mdVals
              // trim leading/trailing
              .map(mdVal => UserMetadataValue(mdVal.id, mdVal.value.trim))
              // and lose the blanks
              .filter(_.nonEmpty)
        }

        if trimmed.nonEmpty then res + (fieldId -> trimmed) else res
    }

    UserMetadata(data = cleanData)

  private def validate(metadata: UserMetadata): Unit =
    if metadata.data.isEmpty then return

    // get all metadata fields configured for this repository
    // OPTIMIZE: only get the fields in the metadata
    val fields = getAllFields

    val ex = ValidationException()

    // for each field
    metadata.data.foreach {
      m =>
        val fieldId = m._1
        val field: UserMetadataField = fields(fieldId)
        val mdVals: Set[UserMetadataValue] = m._2

        // booleans cannot have multiple values
        if field.fieldType == FieldType.BOOL && mdVals.size > 1 then
          ex.errors += (field.persistedId -> C.Msg.Err.INCORRECT_VALUE_TYPE.format(field.name))
        else
          val illegalValues = collectInvalidTypeValues(field.fieldType, mdVals)

          // add to the validation exception if any
          if illegalValues.nonEmpty then
            ex.errors += (field.persistedId ->
              C.Msg.Err.INCORRECT_VALUE_TYPE.format(field.name, illegalValues.mkString(", ")))
    }

    ex.trigger()

  /**
   * Makes sure the metadata fields are configured in this system after common-sense data hygiene. Validates correct type for
   * anything
   *
   * @return
   *   clean, de-duplicated copy of the metadata
   */
  private def cleanAndValidate(metadata: UserMetadata): UserMetadata =
    val cleanMetadata = clean(metadata)
    validate(cleanMetadata)
    cleanMetadata

  /**
   * Presentation-level JSON transformer for metadata. This augments the limiting metadata JSON object to supply the names of
   * fields, pulled from field configuration that the Metadata domain object is not aware of.
   *
   * On the way in we get: { field id -> values field id -> values }
   *
   * We get out: [ VALUES -> values[] FIELD_TYPE -> ID -> field id NAME -> field name FIELD_TYPE -> field type
   *
   * VALUES -> values[] FIELD_TYPE -> ID -> field id NAME -> field name FIELD_TYPE -> field type ]
   *
   * Every field is listed, with its values or with none, in the order of the fields' name keys ([[UserMetadataField.nameKey]]).
   */
  def toJson(metadata: UserMetadata, allMetadataFields: Option[Map[String, UserMetadataField]] = None): ujson.Arr =
    txManager.asReadOnly {
      val allFields = if allMetadataFields.isDefined then allMetadataFields.get else getAllFields

      def toJsonEntry(field: UserMetadataField, mdVals: Set[UserMetadataValue]): ujson.Obj =
        ujson.Obj(
          FieldConst.MetadataField.FIELD -> field.toJson,
          FieldConst.VALUES -> ujson.Arr(mdVals.toSeq.map(v => v.toJson: ujson.Value)*)
        )

      val withValues = metadata.data.toSeq.map((fieldId, mdVals) => allFields(fieldId) -> mdVals)
      val withoutValues =
        allFields.values.filterNot(field => metadata.contains(field.persistedId)).map(_ -> Set.empty[UserMetadataValue])

      // By name key, the order of names, and by ID among fields of one key, so the order is total
      val sorted = (withValues ++ withoutValues)
        .sortBy((field, _) => (field.nameKey, field.persistedId))
        .map((field, mdVals) => toJsonEntry(field, mdVals))

      ujson.Arr(sorted*)
    }

  /**
   * Given a field type and a set values, collect all the values that DO NOT pass type checks.
   *
   * @param fieldType
   *   The type of field values for each we are validating
   * @param values
   *   Values that may or may note pass type validation
   * @return
   *   All values that FAIL type validation
   */
  def collectInvalidTypeValues(fieldType: FieldType, values: Set[UserMetadataValue]): Set[String] =
    // FIXME: foldLeft is better-suited here
    values
      .map {
        mdVal =>
          fieldType match {
            case FieldType.NUMBER =>
              try {
                mdVal.value.toDouble
                None
              } catch {
                case _: Throwable => Some(mdVal.value)
              }
            case FieldType.KEYWORD => None // everything is allowed
            case FieldType.TEXT => None // everything is allowed
            case FieldType.BOOL => // only values that we recognize as booleans
              if UserMetadataValue.asBoolean(mdVal.value).isDefined then None
              else Some(mdVal.value)
            case FieldType.DATETIME => None // TODO: Add datetime validation if needed
          }
          // get rid of None's - those are valid values
      }
      .filter(_.isDefined)
      .map(_.get)
