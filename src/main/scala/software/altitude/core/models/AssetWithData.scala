package software.altitude.core.models


case class AssetWithData(asset: Asset, data: Array[Byte]) {

  override def toString: String =
    s"Asset with data: [${asset.id}]. Size: [${data.length}] bytes"
}
