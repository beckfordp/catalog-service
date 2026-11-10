// SPDX-License-Identifier: MIT

package catalogservice

import cats.effect.IO
import catalogservice.domain.CatalogId
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs
import sttp.tapir.EndpointOutput
import sttp.tapir.server.ServerEndpoint

class CatalogDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      endpoint = CatalogRoutes.serverEndpoint[IO](store, NoOpLogger[IO])
      routes = Docs.routes[IO]("Catalog Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/catalogs")
        .withEntity(
          CreateCatalogRequest(
            "Widget",
            "A very fine widget",
            1999,
            "sku-widget-1"
          )
        )
      response <- routes.orNotFound.run(request)
      entity <- response.as[CatalogResponse]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.toString.nonEmpty)
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/catalogs"))
    }
  }

  test(
    "the GET /catalogs list endpoint is documented via purerest.docs"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      cache <- CatalogListCache.inMemory[IO]
      endpoint = CatalogRoutes.listCatalogsServerEndpoint[IO](
        store,
        NoOpLogger[IO],
        cache
      )
      routes = Docs.routes[IO]("Catalog Service", "1.0", List(endpoint))
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/catalogs"))
      assert(clue(docsBody).contains("X-Total-Count"))
    }
  }

  /** Every fixed status an output can produce (tapir's own traversal is
    * internal).
    */
  private def statuses(output: EndpointOutput[?]): List[Int] =
    output match {
      case EndpointOutput.FixedStatusCode(code, _, _) => List(code.code)
      case EndpointOutput.OneOf(variants, _)          =>
        variants.flatMap(v => statuses(v.output))
      case EndpointOutput.Pair(left, right, _, _) =>
        statuses(left) ++ statuses(right)
      case EndpointOutput.MappedPair(pair, _) => statuses(pair)
      case _                                  => Nil
    }

  private def errorStatuses(endpoint: ServerEndpoint[Any, IO]): List[Int] =
    statuses(endpoint.endpoint.errorOutput).sorted

  test("each endpoint documents only the errors it can return") {
    for {
      store <- CatalogStore.inMemory[IO]
      cache <- CatalogListCache.inMemory[IO]
    } yield {
      val logger = NoOpLogger[IO]
      assertEquals(
        errorStatuses(CatalogRoutes.serverEndpoint[IO](store, logger)),
        List(400)
      )
      assertEquals(
        errorStatuses(
          CatalogRoutes.listCatalogsServerEndpoint[IO](store, logger, cache)
        ),
        List(400)
      )
      assertEquals(
        errorStatuses(
          CatalogRoutes.getCatalogServerEndpoint[IO](store, logger)
        ),
        List(404)
      )
      assertEquals(
        errorStatuses(
          CatalogRoutes.updateCatalogServerEndpoint[IO](store, logger)
        ),
        List(400, 404)
      )
      assertEquals(
        errorStatuses(
          CatalogRoutes.replaceCatalogServerEndpoint[IO](store, logger)
        ),
        List(400, 404)
      )
      assertEquals(
        errorStatuses(
          CatalogRoutes.deleteCatalogServerEndpoint[IO](store, logger)
        ),
        List(404)
      )
      assertEquals(
        errorStatuses(HealthRoutes.readyServerEndpoint[IO](store)),
        List(503)
      )
    }
  }

  test("every endpoint is documented") {
    for {
      store <- CatalogStore.inMemory[IO]
      cache <- CatalogListCache.inMemory[IO]
    } yield {
      val logger = NoOpLogger[IO]
      val endpoints = List(
        CatalogRoutes.serverEndpoint[IO](store, logger),
        CatalogRoutes.getCatalogServerEndpoint[IO](store, logger),
        CatalogRoutes.updateCatalogServerEndpoint[IO](store, logger),
        CatalogRoutes.replaceCatalogServerEndpoint[IO](store, logger),
        CatalogRoutes.deleteCatalogServerEndpoint[IO](store, logger),
        CatalogRoutes.listCatalogsServerEndpoint[IO](store, logger, cache),
        HealthRoutes.healthServerEndpoint[IO],
        HealthRoutes.readyServerEndpoint[IO](store)
      )
      assertEquals(
        Docs.undocumented(endpoints.map(_.endpoint)).map(_.render),
        Nil
      )
    }
  }
}
