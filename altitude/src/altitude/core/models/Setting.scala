package altitude.core.models

/** A stored Setting: its dotted name, its value and its default, all as text; the registry in [[Settings]] types them */
case class Setting(name: String, value: String, defaultValue: String) extends BaseModel with NoId with NoDates:
  override def toString: String = s"<setting> $name=$value (default $defaultValue)"
