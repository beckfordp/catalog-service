// SPDX-License-Identifier: MIT

package catalogservice.domain

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class CatalogRulesSuite extends ScalaCheckSuite {

  test("details accepts a named, non-negatively priced catalog") {
    assertEquals(
      CatalogRules.details("Widget", "A very fine widget", 0),
      Right(CatalogDetails("Widget", "A very fine widget", 0))
    )
  }

  test("details rejects a blank name") {
    assertEquals(
      CatalogRules.details("  ", "A very fine widget", 1999),
      Left(InvalidCatalog("name must not be blank"))
    )
  }

  test("details rejects a negative price") {
    assertEquals(
      CatalogRules.details("Widget", "A very fine widget", -1),
      Left(InvalidCatalog("priceCents must be at least 0, got -1"))
    )
  }

  test("newCatalog rejects a blank sku") {
    assertEquals(
      CatalogRules.newCatalog("Widget", "A very fine widget", 1999, ""),
      Left(InvalidCatalog("sku must not be blank"))
    )
  }

  test("newCatalog accepts valid fields") {
    assertEquals(
      CatalogRules.newCatalog("Widget", "", 1999, "sku-widget-1"),
      Right(NewCatalog(CatalogDetails("Widget", "", 1999), "sku-widget-1"))
    )
  }

  property("a price is accepted exactly when it is at least 0") {
    forAll(Gen.choose(Int.MinValue, Int.MaxValue)) { price =>
      assertEquals(
        CatalogRules.details("Widget", "", price).isRight,
        price >= 0
      )
    }
  }
}
