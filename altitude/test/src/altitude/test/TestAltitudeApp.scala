package altitude.test

import altitude.core.Altitude

trait TestAltitudeApp {
  val testApp: Altitude
}

object TestAltitudeApp {

  /**
   * A test instance on the engine, its Scene models and its Describe model switched off until a suite turns them on
   * (`SwitchableSceneEmbedder`, `SwitchableDescribeEmbedder`)
   */
  def build(dbEngine: String): Altitude =
    new Altitude(
      dbEngineOverride = Some(dbEngine),
      newSceneEmbedder = _ => SwitchableSceneEmbedder(),
      newDescribeEmbedder = _ => SwitchableDescribeEmbedder())
}
