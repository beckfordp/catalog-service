package catalogservice

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import purerest.docs.Docs
import purerest.logging.Logging
import purerest.metrics.{Metrics, ServerMetrics}
import purerest.tracing.{ServerTracing, Tracing, TracingConfig}

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    for {
      config <- CatalogServiceConfig.load[IO]
      port <- IO.fromOption(Port.fromInt(config.port))(
        new IllegalArgumentException(
          s"Invalid catalog-service port: ${config.port}"
        )
      )
      _ <- Migrations.run[IO](config.postgres)
      // PERF-5.3: OTLP/HTTP to Tempo when OTEL_EXPORTER_OTLP_ENDPOINT is set,
      // else the console; a malformed OTEL_TRACES_SAMPLER_ARG fails startup.
      tracingConfig <- IO.defer(
        IO.fromEither(
          TracingConfig
            .fromEnv(sys.env)
            .left
            .map(new IllegalArgumentException(_))
        )
      )
      _ <- Tracing.resource[IO](config.serviceName, tracingConfig).use {
        tracer =>
          Metrics
            .oteljava[IO](
              config.serviceName,
              config.metricsPort,
              Some(runtime.metrics)
            )
            .use { meter =>
              for {
                logger <- Logging.create[IO](tracer, config.serviceName)
                _ <- logger.info(
                  Map(
                    "port" -> config.port.toString,
                    "metrics_port" -> config.metricsPort.toString
                  )
                )("catalog-service starting")
                _ <- CatalogStore.postgres[IO](config.postgres, meter).use {
                  store =>
                    CatalogListCache
                      .resource[IO](config.redis, logger)
                      .evalMap(CatalogListCache.metered[IO](_, meter))
                      .use { cache =>
                        val docsRoutes = Docs.routes[IO](
                          "Catalog Service",
                          "1.0",
                          List(
                            CatalogRoutes.serverEndpoint[IO](store, logger),
                            CatalogRoutes
                              .getCatalogServerEndpoint[IO](store, logger),
                            CatalogRoutes
                              .updateCatalogServerEndpoint[IO](store, logger),
                            CatalogRoutes
                              .replaceCatalogServerEndpoint[IO](store, logger),
                            CatalogRoutes
                              .deleteCatalogServerEndpoint[IO](store, logger),
                            CatalogRoutes
                              .listCatalogsServerEndpoint[IO](
                                store,
                                logger,
                                cache
                              ),
                            HealthRoutes.healthServerEndpoint[IO],
                            HealthRoutes.readyServerEndpoint[IO](store)
                          )
                        )
                        val tracedRoutes =
                          ServerTracing.middleware(tracer)(docsRoutes)
                        val meteredRoutes =
                          ServerMetrics.middleware[IO](meter)(tracedRoutes)
                        val routes = Cors.middleware[IO](meteredRoutes)
                        EmberServerBuilder
                          .default[IO]
                          .withHost(host"0.0.0.0")
                          .withPort(port)
                          .withHttpApp(routes.orNotFound)
                          .build
                          .useForever
                      }
                }
              } yield ()
            }
      }
    } yield ()
}
