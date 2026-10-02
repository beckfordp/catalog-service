package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends CatsEffectSuite {

  private val readyStore: CatalogStore[IO] =
    new CatalogStore[IO] {
      def create(name: String, description: String, priceCents: Int, sku: String): IO[Catalog] =
        IO.raiseError(new NotImplementedError())
      def get(id: String): IO[Option[Catalog]] = IO.pure(None)
      def update(
          id: String,
          name: String,
          description: String,
          priceCents: Int
      ): IO[Option[Catalog]] = IO.pure(None)
      def delete(id: String): IO[Boolean] = IO.pure(false)
      def list(limit: Int, offset: Int): IO[(List[Catalog], Long)] =
        IO.pure((Nil, 0L))
      def ping: IO[Boolean] = IO.pure(true)
    }

  private val notReadyStore: CatalogStore[IO] =
    new CatalogStore[IO] {
      def create(name: String, description: String, priceCents: Int, sku: String): IO[Catalog] =
        IO.raiseError(new NotImplementedError())
      def get(id: String): IO[Option[Catalog]] = IO.pure(None)
      def update(
          id: String,
          name: String,
          description: String,
          priceCents: Int
      ): IO[Option[Catalog]] = IO.pure(None)
      def delete(id: String): IO[Boolean] = IO.pure(false)
      def list(limit: Int, offset: Int): IO[(List[Catalog], Long)] =
        IO.pure((Nil, 0L))
      def ping: IO[Boolean] = IO.pure(false)
    }

  test("GET /health returns 200") {
    val routes = HealthRoutes.routes[IO](readyStore)
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/health"))
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 200 when the store is ready") {
    val routes = HealthRoutes.routes[IO](readyStore)
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 503 when the store is not ready") {
    val routes = HealthRoutes.routes[IO](notReadyStore)
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.ServiceUnavailable)
  }
}
