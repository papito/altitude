package altitude.test

import altitude.core.Altitude

trait TestAltitudeApp {
  val testApp: Altitude
}

object TestAltitudeApp {

  /** A test instance on the engine, its Scene and Describe models off until a suite turns them on */
  def build(dbEngine: String): Altitude =
    new Altitude(
      dbEngineOverride = Some(dbEngine),
      newSceneEmbedder = SwitchableSceneEmbedder(_),
      newDescribeEmbedder = SwitchableDescribeEmbedder(_))
}
