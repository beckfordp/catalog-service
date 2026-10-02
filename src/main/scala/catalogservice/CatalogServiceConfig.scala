package catalogservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String
) derives ConfigReader

final case class CatalogServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    postgres: PostgresConfig
) derives ConfigReader

object CatalogServiceConfig {
  def load[F[_]: Sync]: F[CatalogServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[CatalogServiceConfig])
}
