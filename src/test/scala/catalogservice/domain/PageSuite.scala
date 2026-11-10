// SPDX-License-Identifier: MIT

package catalogservice.domain

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class PageSuite extends ScalaCheckSuite {

  test("absent params default to limit 20, offset 0") {
    assertEquals(
      Page.from(None, None).map(p => (p.limit, p.offset)),
      Right((20, 0))
    )
  }

  test("the bounds are accepted: limit 1 and 100, offset 0") {
    assert(Page.from(Some(1), Some(0)).isRight)
    assert(Page.from(Some(100), Some(0)).isRight)
  }

  test("limit 0, limit 101 and offset -1 are rejected") {
    assertEquals(Page.from(Some(0), None), Left(InvalidPagination))
    assertEquals(Page.from(Some(101), None), Left(InvalidPagination))
    assertEquals(Page.from(None, Some(-1)), Left(InvalidPagination))
  }

  property("accepted exactly when 1 <= limit <= 100 and offset >= 0") {
    forAll(Gen.choose(-500, 500), Gen.choose(-500, 500)) { (limit, offset) =>
      val accepted = limit >= 1 && limit <= 100 && offset >= 0
      assertEquals(
        Page.from(Some(limit), Some(offset)).map(p => (p.limit, p.offset)),
        if (accepted) Right((limit, offset)) else Left(InvalidPagination)
      )
    }
  }
}
