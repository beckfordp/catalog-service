// SPDX-License-Identifier: MIT

package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger

class CatalogValidationRoutesSuite extends CatsEffectSuite {

  private val noOpListCache: CatalogListCache[IO] =
    new CatalogListCache[IO] {
      def get(
          limit: Int,
          offset: Int
      ): IO[Option[(List[CatalogResponse], Long)]] = IO.pure(None)
      def set(
          limit: Int,
          offset: Int,
          catalogs: List[CatalogResponse],
          total: Long
      ): IO[Unit] = IO.unit
    }

  private def post(body: CreateCatalogRequest) =
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(body)
      )
      error <- response.as[ErrorResponse]
      (_, total) <- store.list(100, 0)
    } yield (response.status, error, total)

  test("POST /catalogs with a negative price returns 400 and stores nothing") {
    post(CreateCatalogRequest("Widget", "A widget", -1, "sku-widget-1")).map {
      case (status, error, total) =>
        assertEquals(status, Status.BadRequest)
        assertEquals(
          error,
          ErrorResponse("priceCents must be at least 0, got -1")
        )
        assertEquals(total, 0L)
    }
  }

  test("POST /catalogs with a blank name returns 400") {
    post(CreateCatalogRequest(" ", "A widget", 1999, "sku-widget-1")).map {
      case (status, error, _) =>
        assertEquals(status, Status.BadRequest)
        assertEquals(error, ErrorResponse("name must not be blank"))
    }
  }

  test("POST /catalogs with a blank sku returns 400") {
    post(CreateCatalogRequest("Widget", "A widget", 1999, "")).map {
      case (status, error, _) =>
        assertEquals(status, Status.BadRequest)
        assertEquals(error, ErrorResponse("sku must not be blank"))
    }
  }

  List(Method.PATCH, Method.PUT).foreach { method =>
    test(
      s"$method /catalogs/{id} with a negative price returns 400, unchanged"
    ) {
      for {
        store <- CatalogStore.inMemory[IO]
        routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
        created <- store.create("Widget", "A widget", 1999, "sku-widget-1")
        response <- routes.orNotFound.run(
          Request[IO](
            method,
            uri"/catalogs" / catalogservice.domain.CatalogId.render(created.id)
          ).withEntity(UpdateCatalogRequest("Widget", "A widget", -5))
        )
        error <- response.as[ErrorResponse]
        after <- store.get(created.id)
      } yield {
        assertEquals(response.status, Status.BadRequest)
        assertEquals(
          error,
          ErrorResponse("priceCents must be at least 0, got -5")
        )
        assertEquals(after.map(_.priceCents), Some(1999))
      }
    }
  }
}
