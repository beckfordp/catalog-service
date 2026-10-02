package catalogservice

import cats.effect.IO
import cats.syntax.all._
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.Metrics
import skunk.Session
import skunk.implicits._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class CatalogStorePostgresSuite
    extends CatsEffectSuite
    with TestContainerForAll {

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(dockerImageName =
      DockerImageName.parse("postgres:16-alpine")
    )

  private def configFor(postgres: PostgreSQLContainer): PostgresConfig =
    PostgresConfig(
      host = postgres.host,
      port = postgres.mappedPort(5432),
      database = postgres.databaseName,
      user = postgres.username,
      password = postgres.password
    )

  // TestContainerForAll shares one Postgres instance (and its "catalog"
  // table) across every test in this suite, so the `list` tests -- the
  // only ones here that assert over the *whole* table rather than rows
  // looked up by their own id -- truncate first for isolation.
  private def truncateCatalogTable(config: PostgresConfig): IO[Unit] = {
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    Session
      .single[IO](
        host = config.host,
        port = config.port,
        user = config.user,
        database = config.database,
        password = Some(config.password)
      )
      .use(_.execute(sql"""TRUNCATE TABLE "catalog"""".command).void)
  }

  test("create persists an entity and returns it with a generated id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.create("Widget", "A very fine widget", 1999, "sku-widget-1").map { entity =>
            assert(entity.id.nonEmpty)
          }
        }
    }
  }

  test("create produces distinct ids across calls") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations
        .run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            first <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            second <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
          } yield assertNotEquals(first.id, second.id)
        }
    }
  }

  test("get returns the persisted entity") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            found <- store.get(created.id)
          } yield assertEquals(found, Some(created))
        }
    }
  }

  test("get returns None for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .get(java.util.UUID.randomUUID().toString)
            .map(assertEquals(_, None))
        }
    }
  }

  test("get returns None for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.get("not-a-uuid").map(assertEquals(_, None))
        }
    }
  }

  test("update returns the updated entity and returns it") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            updated <- store.update(created.id, "Widget", "A very fine widget", 1999)
          } yield {
            assertEquals(updated.map(_.id), Some(created.id))
            assert(
              updated.exists(!_.updatedAt.isBefore(created.updatedAt)),
              s"expected updatedAt not to move backwards, got: $updated"
            )
          }
        }
    }
  }

  test("update returns None for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .update(java.util.UUID.randomUUID().toString, "Widget", "A very fine widget", 1999)
            .map(assertEquals(_, None))
        }
    }
  }

  test("update returns None for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.update("not-a-uuid", "Widget", "A very fine widget", 1999).map(assertEquals(_, None))
        }
    }
  }

  test("delete removes the entity and returns true, and get then returns None") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            deleted <- store.delete(created.id)
            found <- store.get(created.id)
          } yield {
            assert(deleted)
            assertEquals(found, None)
          }
        }
    }
  }

  test("delete returns false for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .delete(java.util.UUID.randomUUID().toString)
            .map(deleted => assert(!deleted))
        }
    }
  }

  test("delete returns false for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.delete("not-a-uuid").map(deleted => assert(!deleted))
        }
    }
  }

  test("ping returns true against a real, reachable database") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.ping.map(assert(_))
        }
    }
  }

  test("ping returns false when the database is unreachable") {
    withContainers { postgres =>
      val unreachableConfig = configFor(postgres).copy(port = 1)
      CatalogStore
        .postgres[IO](unreachableConfig, Meter.noop[IO])
        .use { store =>
          store.ping.map(ready => assert(!ready))
        }
    }
  }

  test(
    "create and get each record a db.client.operation.duration measurement, tagged by operation"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      Metrics.test[IO]("catalog-store-postgres-metrics-test").use {
        testMeter =>
          Migrations.run[IO](config) *> CatalogStore
            .postgres[IO](config, testMeter.meter)
            .use { store =>
              for {
                created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
                _ <- store.get(created.id)
                metrics <- testMeter.collectMetrics
              } yield {
                val data =
                  metrics.find(_.getName == "db.client.operation.duration")
                assert(
                  data.isDefined,
                  s"expected a db.client.operation.duration series, got: $metrics"
                )
                val dbOperationKey =
                  io.opentelemetry.api.common.AttributeKey
                    .stringKey("db.operation")
                val operations = data.get.getHistogramData.getPoints.asScala
                  .flatMap(point =>
                    Option(point.getAttributes.get(dbOperationKey))
                  )
                  .toSet
                assert(
                  operations
                    .contains("insert") && operations.contains("select"),
                  s"expected db.operation attributes for both insert and select, got: $operations"
                )
              }
            }
      }
    }
  }

  test(
    "a failing query records a db.client.operation.duration measurement tagged with error.type"
  ) {
    withContainers { postgres =>
      // Port 1 is a privileged port nothing binds to in these tests; unlike
      // `mappedPort(5432) + 1`, it can't collide with another concurrently-running
      // Testcontainers Postgres instance's dynamically assigned port.
      val unreachableConfig = configFor(postgres).copy(port = 1)
      Metrics.test[IO]("catalog-store-postgres-metrics-test").use {
        testMeter =>
          CatalogStore
            .postgres[IO](unreachableConfig, testMeter.meter)
            .use { store =>
              for {
                result <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1").attempt
                metrics <- testMeter.collectMetrics
              } yield {
                assert(
                  result.isLeft,
                  s"expected the connection failure to propagate, got: $result"
                )
                val data =
                  metrics.find(_.getName == "db.client.operation.duration")
                assert(
                  data.isDefined,
                  s"expected a db.client.operation.duration series, got: $metrics"
                )
                val errorTypeKey =
                  io.opentelemetry.api.common.AttributeKey.stringKey(
                    "error.type"
                  )
                val hasErrorAttribute =
                  data.get.getHistogramData.getPoints.asScala
                    .exists(point =>
                      Option(point.getAttributes.get(errorTypeKey)).isDefined
                    )
                assert(
                  hasErrorAttribute,
                  s"expected a point tagged with error.type, got: ${data.get.getHistogramData.getPoints}"
                )
              }
            }
      }
    }
  }

  test(
    "full CRUD lifecycle: create -> read -> patch -> delete -> read-404, plus a readiness check"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            ready <- store.ping
            created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            read1 <- store.get(created.id)
            updated <- store.update(created.id, "Widget", "A very fine widget", 1999)
            read2 <- store.get(created.id)
            deleted <- store.delete(created.id)
            read3 <- store.get(created.id)
          } yield {
            assert(ready, "expected the database to be ready")
            assertEquals(read1, Some(created))
            assertEquals(updated.map(_.name), Some("Widget"))
            assertEquals(updated.map(_.description), Some("A very fine widget"))
            assertEquals(updated.map(_.priceCents), Some(1999))
            assertEquals(updated.map(_.sku), Some("sku-widget-1"))
            assertEquals(read2, updated)
            assert(deleted, "expected delete to report the entity existed")
            assertEquals(read3, None)
          }
        }
    }
  }

  test("list returns an empty list and zero total for an empty table") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> truncateCatalogTable(config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.list(20, 0).map(assertEquals(_, (Nil, 0L)))
        }
    }
  }

  test("list returns entities newest-first") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> truncateCatalogTable(config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            first <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            _ <- IO.sleep(2.millis)
            second <- store.create("Gadget", "A very fine gadget", 2999, "sku-gadget-1")
            _ <- IO.sleep(2.millis)
            third <- store.create("Gizmo", "A very fine gizmo", 3999, "sku-gizmo-1")
            result <- store.list(20, 0)
          } yield assertEquals(
            result._1.map(_.id),
            List(third.id, second.id, first.id)
          )
        }
    }
  }

  test("list respects limit and offset") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> truncateCatalogTable(config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            first <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            _ <- IO.sleep(2.millis)
            second <- store.create("Gadget", "A very fine gadget", 2999, "sku-gadget-1")
            _ <- IO.sleep(2.millis)
            third <- store.create("Gizmo", "A very fine gizmo", 3999, "sku-gizmo-1")
            _ <- IO.sleep(2.millis)
            fourth <- store.create("Doohickey", "A very fine doohickey", 4999, "sku-doohickey-1")
            _ <- IO.sleep(2.millis)
            fifth <- store.create(
              "Thingamajig",
              "A very fine thingamajig",
              5999,
              "sku-thingamajig-1"
            )
            result <- store.list(2, 1)
          } yield assertEquals(result._1.map(_.id), List(fourth.id, third.id))
        }
    }
  }

  test("list returns the correct total count regardless of page size") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> truncateCatalogTable(config) *> CatalogStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            _ <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
            _ <- store.create("Gadget", "A very fine gadget", 2999, "sku-gadget-1")
            _ <- store.create("Gizmo", "A very fine gizmo", 3999, "sku-gizmo-1")
            _ <- store.create("Doohickey", "A very fine doohickey", 4999, "sku-doohickey-1")
            _ <- store.create(
              "Thingamajig",
              "A very fine thingamajig",
              5999,
              "sku-thingamajig-1"
            )
            result <- store.list(2, 0)
          } yield {
            assertEquals(result._1.size, 2)
            assertEquals(result._2, 5L)
          }
        }
    }
  }
}
