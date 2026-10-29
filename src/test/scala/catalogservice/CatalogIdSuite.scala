package catalogservice

import catalogservice.domain.CatalogId
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class CatalogIdSuite extends ScalaCheckSuite {

  test("parse accepts a canonical UUID and render round-trips it") {
    val raw = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    assertEquals(CatalogId.parse(raw).map(CatalogId.render), Right(raw))
  }

  test("parse rejects a malformed id") {
    assert(CatalogId.parse("not-a-uuid").isLeft)
  }

  property("parse then render is the identity for any UUID") {
    forAll(Gen.uuid) { uuid =>
      val raw = uuid.toString
      assertEquals(CatalogId.parse(raw).map(CatalogId.render), Right(raw))
    }
  }
}
