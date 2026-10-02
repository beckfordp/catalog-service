package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs

class CatalogDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      endpoint = CatalogRoutes.serverEndpoint[IO](store, NoOpLogger[IO])
      routes = Docs.routes[IO]("Catalog Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/catalogs")
        .withEntity(CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1"))
      response <- routes.orNotFound.run(request)
      entity <- response.as[CatalogResponse]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/catalogs"))
    }
  }

  test(
    "the GET /catalogs list endpoint is documented via purerest.docs"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      endpoint = CatalogRoutes.listCatalogsServerEndpoint[IO](store, NoOpLogger[IO])
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
}
