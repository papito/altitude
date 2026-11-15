package altitude.test

/**
 * A model of a test instance that is off until a suite turns it on (`IntegrationTestCore`): off, the instance runs no pass of it
 * and answers with nothing in its place
 */
trait ModelSwitch {

  /** Whether the real model answers */
  def on: Boolean

  def turnOn(): Unit

  def turnOff(): Unit
}
