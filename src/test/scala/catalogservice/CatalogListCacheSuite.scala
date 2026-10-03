package catalogservice

import cats.effect.IO
import com.dimafeng.testcontainers.RedisContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger

import scala.concurrent.duration._

class CatalogListCacheSuite extends CatsEffectSuite with TestContainerForAll {

  override val containerDef: RedisContainer.Def = RedisContainer.Def()

  private def onePage(sku: String) = List(
    CatalogResponse(
      id = "catalog-1",
      name = "Widget",
      description = "A very fine widget",
      priceCents = 1999,
      sku = sku,
      createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
      updatedAt = java.time.Instant.parse("2026-01-01T00:00:00Z")
    )
  )

  test("get returns None for a limit/offset with nothing cached") {
    withContainers { redis =>
      val config = RedisConfig(uri = redis.redisUri, listTtlSeconds = 60)
      CatalogListCache.resource[IO](config, NoOpLogger[IO]).use { cache =>
        cache.get(20, 0).map(assertEquals(_, None))
      }
    }
  }

  test(
    "a cache miss followed by set populates the cache - subsequent get with the same limit/offset is a hit"
  ) {
    withContainers { redis =>
      val config = RedisConfig(uri = redis.redisUri, listTtlSeconds = 60)
      CatalogListCache.resource[IO](config, NoOpLogger[IO]).use { cache =>
        for {
          before <- cache.get(20, 0)
          _ <- cache.set(20, 0, onePage("sku-a"), 1L)
          after <- cache.get(20, 0)
        } yield {
          assertEquals(before, None)
          assertEquals(after, Some((onePage("sku-a"), 1L)))
        }
      }
    }
  }

  test("a get with different limit/offset than anything cached is a miss") {
    withContainers { redis =>
      val config = RedisConfig(uri = redis.redisUri, listTtlSeconds = 60)
      CatalogListCache.resource[IO](config, NoOpLogger[IO]).use { cache =>
        for {
          _ <- cache.set(20, 0, onePage("sku-b"), 1L)
          sameParams <- cache.get(20, 0)
          differentOffset <- cache.get(20, 20)
          differentLimit <- cache.get(5, 0)
        } yield {
          assertEquals(sameParams, Some((onePage("sku-b"), 1L)))
          assertEquals(differentOffset, None)
          assertEquals(differentLimit, None)
        }
      }
    }
  }

  test("a cached entry expires after the configured TTL") {
    withContainers { redis =>
      val config = RedisConfig(uri = redis.redisUri, listTtlSeconds = 1)
      CatalogListCache.resource[IO](config, NoOpLogger[IO]).use { cache =>
        for {
          _ <- cache.set(20, 0, onePage("sku-c"), 1L)
          immediately <- cache.get(20, 0)
          _ <- IO.sleep(2.seconds)
          afterTtl <- cache.get(20, 0)
        } yield {
          assertEquals(immediately, Some((onePage("sku-c"), 1L)))
          assertEquals(afterTtl, None)
        }
      }
    }
  }
}
