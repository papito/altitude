package software.altitude.core

import play.api.libs.json.JsObject
import software.altitude.core.{Const => C}

object Validators {

  /**
   * API request validator for JSON payloads
   *
   * @param required List of required fields in the request.
   * @param maxLengths Map of fields to their maximum allowed lengths.
   * @param minLengths Map of fields to their minimum required lengths.
   */
  case class ApiRequestValidator(required: List[String] = List(),
                                 maxLengths: Map[String, Int] = Map.empty,
                                 minLengths: Map[String, Int] = Map.empty) {
    /**
     * Validates a JSON object based on the specified rules.
     *
     * @param json The JSON object to validate.
     * @throws ValidationException If the JSON object does not meet the validation criteria.
     */
    def validate(json: JsObject): Unit = {
      val ex: ValidationException = ValidationException()

      // Check for required fields
      required.foreach { field =>
        if (!json.keys.contains(field) || json(field).as[String].isEmpty) {
          ex.errors += (field -> C.Msg.Err.REQUIRED)
        }
      }

      // Check for fields exceeding maximum length
      maxLengths foreach { case (field, maxLength) =>
        if (json.keys.contains(field) && json(field).as[String].length > maxLength) {
          ex.errors += (field -> C.Msg.Err.VALUE_TOO_LONG.format(maxLength))
        }
      }

      // Check for fields not meeting minimum length
      minLengths foreach { case (field, minLength) =>
        /* We add a check here to make sure there is no existing error for this field already.
           The required check should not be overridden by the min length check (which it would be otherwise)
         */
        if (json.keys.contains(field) &&
          json(field).as[String].length < minLength
          && !ex.errors.contains(field)) {

          ex.errors += (field -> C.Msg.Err.VALUE_TOO_SHORT.format(minLength))
        }
      }

      // Trigger the exception if there are any validation errors
      ex.trigger()
    }
  }
}
