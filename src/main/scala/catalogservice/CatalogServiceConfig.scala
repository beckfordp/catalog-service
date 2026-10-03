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

final case class RedisConfig(
    uri: String,
    listTtlSeconds: Int
) derives ConfigReader

final case class CatalogServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    postgres: PostgresConfig,
    redis: RedisConfig
) derives ConfigReader

object CatalogServiceConfig {
  def load[F[_]: Sync]: F[CatalogServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[CatalogServiceConfig])
}
