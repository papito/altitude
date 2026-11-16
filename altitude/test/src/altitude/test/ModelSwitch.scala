package altitude.test

/** A test instance's model, off until a suite turns it on (`IntegrationTestCore`); off, it runs no pass and answers nothing */
trait ModelSwitch {

  /** Whether the real model answers */
  def on: Boolean

  def turnOn(): Unit

  def turnOff(): Unit
}
