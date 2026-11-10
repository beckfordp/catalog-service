// SPDX-License-Identifier: MIT

package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.WARN

/** A Redis failure must not fail `GET /catalogs`: Postgres is the source of
  * truth, the cache only an accelerator.
  */
class CatalogListCacheFallbackSuite extends CatsEffectSuite {

  private val boom = new RuntimeException("redis down")

  private def cache(failGet: Boolean, failSet: Boolean): CatalogListCache[IO] =
    new CatalogListCache[IO] {
      def get(
          limit: Int,
          offset: Int
      ): IO[Option[(List[CatalogResponse], Long)]] =
        if (failGet) IO.raiseError(boom) else IO.pure(None)
      def set(
          limit: Int,
          offset: Int,
          catalogs: List[CatalogResponse],
          total: Long
      ): IO[Unit] = if (failSet) IO.raiseError(boom) else IO.unit
    }

  private def run(c: CatalogListCache[IO]) =
    for {
      store <- CatalogStore.inMemory[IO]
      _ <- store.create("Widget", "A widget", 1999, "sku-widget-1")
      logger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, logger, c)
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/catalogs"))
      body <- response.as[List[CatalogResponse]]
      logged <- logger.logged
    } yield (response, body, logged.collect { case w: WARN => w })

  test("a cache read error falls back to the store and logs a WARN") {
    run(cache(failGet = true, failSet = false)).map {
      case (response, body, warns) =>
        assertEquals(response.status, Status.Ok)
        assertEquals(body.map(_.sku), List("sku-widget-1"))
        assertEquals(
          response.headers.get(ci"X-Total-Count").map(_.head.value),
          Some("1")
        )
        assert(
          clue(warns).exists(_.ctx.get("cache").contains("read_error"))
        )
    }
  }

  test("a cache write error still returns the page and logs a WARN") {
    run(cache(failGet = false, failSet = true)).map {
      case (response, body, warns) =>
        assertEquals(response.status, Status.Ok)
        assertEquals(body.map(_.sku), List("sku-widget-1"))
        assert(
          clue(warns).exists(_.ctx.get("cache").contains("write_error"))
        )
    }
  }
}
