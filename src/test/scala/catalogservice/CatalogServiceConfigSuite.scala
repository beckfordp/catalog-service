package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

class CatalogServiceConfigSuite extends CatsEffectSuite {

  private val validHocon =
    """
      |port = 8080
      |metrics-port = 9090
      |service-name = "catalog-service"
      |postgres {
      |  host = "localhost"
      |  port = 5432
      |  database = "catalog"
      |  user = "catalog"
      |  password = "catalog"
      |}
      |redis {
      |  uri = "redis://localhost:6379"
      |  list-ttl-seconds = 60
      |}
      |""".stripMargin

  test("loads a fully-specified config") {
    val result =
      ConfigSource.string(validHocon).load[CatalogServiceConfig]
    assertEquals(
      result,
      Right(
        CatalogServiceConfig(
          port = 8080,
          metricsPort = 9090,
          serviceName = "catalog-service",
          postgres = PostgresConfig(
            host = "localhost",
            port = 5432,
            database = "catalog",
            user = "catalog",
            password = "catalog"
          ),
          redis = RedisConfig(
            uri = "redis://localhost:6379",
            listTtlSeconds = 60
          )
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingPassword =
      """
        |port = 8080
        |metrics-port = 9090
        |postgres {
        |  host = "localhost"
        |  port = 5432
        |  database = "catalog"
        |  user = "catalog"
        |}
        |redis {
        |  uri = "redis://localhost:6379"
        |  list-ttl-seconds = 60
        |}
        |""".stripMargin

    assert(
      ConfigSource
        .string(missingPassword)
        .load[CatalogServiceConfig]
        .isLeft
    )
  }

  test("load[F] reads the shipped application.conf defaults") {
    CatalogServiceConfig.load[IO].map { config =>
      assertEquals(config.port, 8080)
      assertEquals(config.metricsPort, 9090)
      assertEquals(config.serviceName, "catalog-service")
      assertEquals(
        config.postgres,
        PostgresConfig(
          "localhost",
          5432,
          "catalog",
          "catalog",
          "catalog"
        )
      )
      assertEquals(
        config.redis,
        RedisConfig(uri = "redis://localhost:6379", listTtlSeconds = 60)
      )
    }
  }
}
