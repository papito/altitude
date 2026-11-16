package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.{ an, be, should, shouldBe, thrownBy }

import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.util.JsonCodec

@DoNotDiscover class PermissionsTests extends funsuite.AnyFunSuite with TestFocus {

  test("Each Permission keeps its bit, in the order the UI lists them") {

    /**
     * Setup:
     *
     * The ten Permissions.
     *
     * Assertions:
     *
     * They are in the UI's order on bits 0 to 9. The bitmask is stored and carried in tokens, so a bit never moves.
     */
    Permission.values.toList.map(p => p -> p.bit) shouldBe List(
      Permission.Import -> 0,
      Permission.CurateOwn -> 1,
      Permission.CurateAll -> 2,
      Permission.Organize -> 3,
      Permission.EditMetadata -> 4,
      Permission.CreateUsers -> 5,
      Permission.CreateGuests -> 6,
      Permission.Recycle -> 7,
      Permission.Purge -> 8,
      Permission.EditImages -> 9
    )
  }

  test("A set of Permissions is the mask of their bits, and a mask with any other bit is refused") {

    /**
     * Setup:
     *
     * Import and Purge, every Permission, none, and masks with a bit no Permission has.
     *
     * Assertions:
     *
     * Import and Purge are the mask 257, holding and listing exactly those two. Every Permission is 1023 and none is an empty 0.
     * A set's JSON is its bare mask and reads back as the same set.
     *
     * Edge cases:
     *
     * A mask with bit 10 set, and a negative one, are refused.
     */
    val importAndPurge = Permissions.of(Permission.Import, Permission.Purge)

    importAndPurge.mask shouldBe 257
    importAndPurge.has(Permission.Import) shouldBe true
    importAndPurge.has(Permission.Recycle) shouldBe false
    importAndPurge.toList shouldBe List(Permission.Import, Permission.Purge)

    Permissions.all.mask shouldBe 1023
    Permissions.none.mask shouldBe 0
    Permissions.none.isEmpty shouldBe true

    JsonCodec.write(importAndPurge) shouldBe "257"
    JsonCodec.read[Permissions]("257") shouldBe importAndPurge

    an[IllegalArgumentException] should be thrownBy Permissions(1024)
    an[IllegalArgumentException] should be thrownBy Permissions(-1)
  }
}
