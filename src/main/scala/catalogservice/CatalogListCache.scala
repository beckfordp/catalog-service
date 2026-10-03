package catalogservice

import cats.effect.{Async, Ref, Resource, Sync}
import cats.syntax.all._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effect.{Log, MkRedis}
import dev.profunktor.redis4cats.log4cats.log4CatsInstance
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._
import org.typelevel.log4cats.StructuredLogger

import scala.concurrent.duration.FiniteDuration

final case class CatalogListCacheEntry(
    catalogs: List[CatalogResponse],
    total: Long
)

object CatalogListCacheEntry {
  implicit val codec: Codec[CatalogListCacheEntry] = deriveCodec
}

/** Cache-aside read-through cache for US-1.2's `GET /catalogs` endpoint, keyed
  * by the exact `(limit, offset)` pagination params requested. TTL-only
  * freshness (see spec.md's "Out of Scope") - no explicit invalidation when a
  * catalog is created/updated/deleted, so a write can take up to the configured
  * TTL to show up in a cached page.
  */
trait CatalogListCache[F[_]] {
  def get(limit: Int, offset: Int): F[Option[(List[CatalogResponse], Long)]]
  def set(
      limit: Int,
      offset: Int,
      catalogs: List[CatalogResponse],
      total: Long
  ): F[Unit]
}

object CatalogListCache {

  /** No TTL/expiry - only used for route-level tests that don't exercise expiry
    * behavior themselves (that's `CatalogListCacheSuite`'s job, against a real
    * Redis).
    */
  def inMemory[F[_]: Sync]: F[CatalogListCache[F]] =
    Ref.of[F, Map[(Int, Int), CatalogListCacheEntry]](Map.empty).map { ref =>
      new CatalogListCache[F] {
        def get(
            limit: Int,
            offset: Int
        ): F[Option[(List[CatalogResponse], Long)]] =
          ref.get.map(
            _.get((limit, offset)).map(entry => (entry.catalogs, entry.total))
          )

        def set(
            limit: Int,
            offset: Int,
            catalogs: List[CatalogResponse],
            total: Long
        ): F[Unit] =
          ref.update(
            _ + ((limit, offset) -> CatalogListCacheEntry(catalogs, total))
          )
      }
    }

  private def keyFor(limit: Int, offset: Int): String =
    s"catalog-list:$limit:$offset"

  def fromRedisCommands[F[_]: Async](
      commands: RedisCommands[F, String, String],
      ttl: FiniteDuration
  ): CatalogListCache[F] =
    new CatalogListCache[F] {
      def get(
          limit: Int,
          offset: Int
      ): F[Option[(List[CatalogResponse], Long)]] =
        commands
          .get(keyFor(limit, offset))
          .map(
            _.flatMap(decode[CatalogListCacheEntry](_).toOption)
              .map(entry => (entry.catalogs, entry.total))
          )

      def set(
          limit: Int,
          offset: Int,
          catalogs: List[CatalogResponse],
          total: Long
      ): F[Unit] =
        commands.setEx(
          keyFor(limit, offset),
          CatalogListCacheEntry(catalogs, total).asJson.noSpaces,
          ttl
        )
    }

  def resource[F[_]: Async](
      config: RedisConfig,
      logger: StructuredLogger[F]
  ): Resource[F, CatalogListCache[F]] = {
    given Log[F] = log4CatsInstance(using logger)
    given MkRedis[F] = MkRedis.forAsync[F]
    Redis[F]
      .utf8(config.uri)
      .map(
        fromRedisCommands(
          _,
          FiniteDuration(config.listTtlSeconds, "seconds")
        )
      )
  }
}
