package catalogservice

import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{
  ERROR,
  INFO,
  WARN
}
import org.typelevel.ci.CIStringSyntax
import purerest.tracing.{ServerTracing, Tracing}

import scala.concurrent.duration._

class CatalogRoutesSuite extends CatsEffectSuite {

  /** Used by every test that isn't specifically exercising cache-aside
    * behavior - always a miss, never actually caches anything.
    */
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

  private def failingStore(error: Throwable): CatalogStore[IO] =
    new CatalogStore[IO] {
      def create(name: String, description: String, priceCents: Int, sku: String): IO[Catalog] =
        IO.raiseError(error)
      def get(id: String): IO[Option[Catalog]] = IO.pure(None)
      def update(
          id: String,
          name: String,
          description: String,
          priceCents: Int
      ): IO[Option[Catalog]] =
        IO.raiseError(error)
      def delete(id: String): IO[Boolean] = IO.raiseError(error)
      def list(limit: Int, offset: Int): IO[(List[Catalog], Long)] =
        IO.raiseError(error)
      def ping: IO[Boolean] = IO.raiseError(error)
    }

  test("POST /catalogs returns 201 with the created entity") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      request = Request[IO](Method.POST, uri"/catalogs")
        .withEntity(CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1"))
      response <- routes.orNotFound.run(request)
      entity <- response.as[CatalogResponse]
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
    }
  }

  test("GET /catalogs/{id} returns 200 with the persisted entity") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs" / created.id)
      )
      fetched <- getResponse.as[CatalogResponse]
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      assertEquals(fetched, created)
    }
  }

  test(
    "GET /catalogs/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /catalogs logs a received-request line and a completed line with structured context"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      request = Request[IO](Method.POST, uri"/catalogs")
        .withEntity(CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1"))
      response <- routes.orNotFound.run(request)
      entity <- response.as[CatalogResponse]
      logged <- testLogger.logged
    } yield {
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("POST")
        ),
        s"expected a received-request INFO line with method context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("catalog_id").contains(entity.id)
        ),
        s"expected a completed INFO line with catalog_id context, got: $infos"
      )
    }
  }

  test(
    "POST /catalogs logs an ERROR with the raised throwable when persisting the entity fails"
  ) {
    val boom = new RuntimeException("boom")
    for {
      testLogger <- IO.pure(StructuredTestingLogger.impl[IO]())
      routes = CatalogRoutes.routes[IO](failingStore(boom), testLogger, noOpListCache)
      request = Request[IO](Method.POST, uri"/catalogs")
        .withEntity(CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1"))
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m => m.throwOpt.contains(boom)),
        s"expected an ERROR line with the raised throwable, got: $errors"
      )
    }
  }

  test(
    "GET /catalogs/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      _ <- testLogger.logged // drain POST's own log lines before the GET
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("GET") &&
            m.ctx.get("catalog_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("catalog_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test(
    "GET /catalogs/{id} logs a WARN for an unknown id"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("catalog_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test("PATCH /catalogs/{id} returns 200 with the updated entity") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      patchResponse <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/catalogs" / created.id)
          .withEntity(UpdateCatalogRequest("Widget", "A very fine widget", 1999))
      )
      updated <- patchResponse.as[CatalogResponse]
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      assertEquals(updated.id, created.id)
    }
  }

  test(
    "PATCH /catalogs/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/catalogs" / "unknown-id")
          .withEntity(UpdateCatalogRequest("Widget", "A very fine widget", 1999))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "PATCH /catalogs/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      _ <- testLogger.logged // drain POST's own log lines before the PATCH
      patchResponse <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/catalogs" / created.id)
          .withEntity(UpdateCatalogRequest("Widget", "A very fine widget", 1999))
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("PATCH") &&
            m.ctx.get("catalog_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("catalog_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("PATCH /catalogs/{id} logs a WARN for an unknown id") {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/catalogs" / "unknown-id")
          .withEntity(UpdateCatalogRequest("Widget", "A very fine widget", 1999))
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("catalog_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test(
    "DELETE /catalogs/{id} returns 204, and a subsequent GET returns 404"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/catalogs" / created.id)
      )
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs" / created.id)
      )
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }

  test(
    "DELETE /catalogs/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/catalogs" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "DELETE /catalogs/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      _ <- testLogger.logged // drain POST's own log lines before the DELETE
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/catalogs" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("DELETE") &&
            m.ctx.get("catalog_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("catalog_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("DELETE /catalogs/{id} logs a WARN for an unknown id") {
    for {
      store <- CatalogStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = CatalogRoutes.routes[IO](store, testLogger, noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/catalogs" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("catalog_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test("PUT /catalogs/{id} returns 200 with the replaced entity") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      created <- postResponse.as[CatalogResponse]
      putResponse <- routes.orNotFound.run(
        Request[IO](Method.PUT, uri"/catalogs" / created.id)
          .withEntity(UpdateCatalogRequest("Widget", "A very fine widget", 1999))
      )
      replaced <- putResponse.as[CatalogResponse]
    } yield {
      assertEquals(putResponse.status, Status.Ok)
      assertEquals(replaced.id, created.id)
    }
  }

  test(
    "PUT /catalogs/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.PUT, uri"/catalogs" / "unknown-id")
          .withEntity(UpdateCatalogRequest("Widget", "A very fine widget", 1999))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "GET /catalogs with no query params returns up to 20 catalogs newest-first with X-Total-Count"
  ) {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      first <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      firstEntity <- first.as[CatalogResponse]
      _ <- IO.sleep(2.millis)
      second <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Gadget", "A very fine gadget", 2999, "sku-gadget-1")
        )
      )
      secondEntity <- second.as[CatalogResponse]
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/catalogs"))
      body <- response.as[List[CatalogResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.map(_.id), List(secondEntity.id, firstEntity.id))
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("2")
      )
    }
  }

  test("GET /catalogs respects limit and offset") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      _ <- List(
        ("Widget", "A very fine widget", 1999, "sku-widget-1"),
        ("Gadget", "A very fine gadget", 2999, "sku-gadget-1"),
        ("Gizmo", "A very fine gizmo", 3999, "sku-gizmo-1"),
        ("Doohickey", "A very fine doohickey", 4999, "sku-doohickey-1"),
        ("Thingamajig", "A very fine thingamajig", 5999, "sku-thingamajig-1")
      ).traverse { case (name, description, priceCents, sku) =>
        routes.orNotFound.run(
          Request[IO](Method.POST, uri"/catalogs")
            .withEntity(CreateCatalogRequest(name, description, priceCents, sku))
        ) <* IO.sleep(2.millis)
      }
      response <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          uri"/catalogs".withQueryParam("limit", 2).withQueryParam("offset", 1)
        )
      )
      body <- response.as[List[CatalogResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.map(_.name), List("Doohickey", "Gizmo"))
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("5")
      )
    }
  }

  test("GET /catalogs on an empty store returns 200 with [] and X-Total-Count: 0") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/catalogs"))
      body <- response.as[List[CatalogResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body, Nil)
      assertEquals(
        response.headers.get(ci"X-Total-Count").map(_.head.value),
        Some("0")
      )
    }
  }

  test("GET /catalogs with limit<=0 returns 400") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs".withQueryParam("limit", 0))
      )
    } yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /catalogs with limit>100 returns 400") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs".withQueryParam("limit", 101))
      )
    } yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /catalogs with offset<0 returns 400") {
    for {
      store <- CatalogStore.inMemory[IO]
      routes = CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/catalogs".withQueryParam("offset", -1))
      )
    } yield assertEquals(response.status, Status.BadRequest)
  }

  test(
    "wrapped routes (with tracing middleware) record a span for a handled request"
  ) {
    Tracing.test[IO]("catalog-service-test").use { testTracer =>
      for {
        store <- CatalogStore.inMemory[IO]
        routes = ServerTracing.middleware(testTracer.tracer)(
          CatalogRoutes.routes[IO](store, NoOpLogger[IO], noOpListCache)
        )
        request = Request[IO](Method.POST, uri"/catalogs")
          .withEntity(CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1"))
        response <- routes.orNotFound.run(request)
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(spans.map(_.getName), List("POST /catalogs"))
      }
    }
  }

  test(
    "GET /catalogs serves from cache on a second call without querying the store again"
  ) {
    for {
      baseStore <- CatalogStore.inMemory[IO]
      callCount <- IO.ref(0)
      countingStore = new CatalogStore[IO] {
        def create(
            name: String,
            description: String,
            priceCents: Int,
            sku: String
        ): IO[Catalog] = baseStore.create(name, description, priceCents, sku)
        def get(id: String): IO[Option[Catalog]] = baseStore.get(id)
        def update(
            id: String,
            name: String,
            description: String,
            priceCents: Int
        ): IO[Option[Catalog]] =
          baseStore.update(id, name, description, priceCents)
        def delete(id: String): IO[Boolean] = baseStore.delete(id)
        def list(limit: Int, offset: Int): IO[(List[Catalog], Long)] =
          callCount.update(_ + 1) *> baseStore.list(limit, offset)
        def ping: IO[Boolean] = baseStore.ping
      }
      cache <- CatalogListCache.inMemory[IO]
      routes = CatalogRoutes.routes[IO](countingStore, NoOpLogger[IO], cache)
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      first <- routes.orNotFound.run(Request[IO](Method.GET, uri"/catalogs"))
      firstBody <- first.as[List[CatalogResponse]]
      second <- routes.orNotFound.run(Request[IO](Method.GET, uri"/catalogs"))
      secondBody <- second.as[List[CatalogResponse]]
      calls <- callCount.get
    } yield {
      assertEquals(first.status, Status.Ok)
      assertEquals(second.status, Status.Ok)
      assertEquals(firstBody, secondBody)
      assertEquals(
        first.headers.get(ci"X-Total-Count").map(_.head.value),
        second.headers.get(ci"X-Total-Count").map(_.head.value)
      )
      assertEquals(calls, 1)
    }
  }

  test("GET /catalogs with different limit/offset is a separate cache entry") {
    for {
      baseStore <- CatalogStore.inMemory[IO]
      callCount <- IO.ref(0)
      countingStore = new CatalogStore[IO] {
        def create(
            name: String,
            description: String,
            priceCents: Int,
            sku: String
        ): IO[Catalog] = baseStore.create(name, description, priceCents, sku)
        def get(id: String): IO[Option[Catalog]] = baseStore.get(id)
        def update(
            id: String,
            name: String,
            description: String,
            priceCents: Int
        ): IO[Option[Catalog]] =
          baseStore.update(id, name, description, priceCents)
        def delete(id: String): IO[Boolean] = baseStore.delete(id)
        def list(limit: Int, offset: Int): IO[(List[Catalog], Long)] =
          callCount.update(_ + 1) *> baseStore.list(limit, offset)
        def ping: IO[Boolean] = baseStore.ping
      }
      cache <- CatalogListCache.inMemory[IO]
      routes = CatalogRoutes.routes[IO](countingStore, NoOpLogger[IO], cache)
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/catalogs").withEntity(
          CreateCatalogRequest("Widget", "A very fine widget", 1999, "sku-widget-1")
        )
      )
      _ <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          uri"/catalogs".withQueryParam("limit", 20).withQueryParam("offset", 0)
        )
      )
      _ <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          uri"/catalogs".withQueryParam("limit", 5).withQueryParam("offset", 0)
        )
      )
      calls <- callCount.get
    } yield assertEquals(calls, 2)
  }
}
