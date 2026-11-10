package altitude.test

import altitude.core.Altitude

trait TestAltitudeApp {
  val testApp: Altitude
}

object TestAltitudeApp {

  /** A test instance on the engine, its Scene models switched off until a suite turns them on (`SwitchableSceneEmbedder`) */
  def build(dbEngine: String): Altitude =
    new Altitude(dbEngineOverride = Some(dbEngine), newSceneEmbedder = () => SwitchableSceneEmbedder())
}
