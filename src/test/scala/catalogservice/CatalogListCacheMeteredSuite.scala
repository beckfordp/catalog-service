package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite
import purerest.metrics.Metrics

import scala.jdk.CollectionConverters._

class CatalogListCacheMeteredSuite extends CatsEffectSuite {

  test("counts list-page lookups as hits or misses and delegates set") {
    Metrics.test[IO]("catalog-cache").use { tm =>
      for {
        underlying <- CatalogListCache.inMemory[IO]
        cache <- CatalogListCache.metered[IO](underlying, tm.meter)
        miss <- cache.get(10, 0)
        _ <- cache.set(10, 0, Nil, 0L)
        hit <- cache.get(10, 0)
        _ <- cache.get(10, 0)
        metrics <- tm.collectMetrics
      } yield {
        assertEquals(miss, None)
        assertEquals(hit, Some((Nil, 0L)))
        val points = metrics
          .find(_.getName == "catalog.cache.requests")
          .get
          .getLongSumData
          .getPoints
          .asScala
          .map(p =>
            p.getAttributes.asMap.asScala.values.head.toString -> p.getValue
          )
          .toMap
        assertEquals(points, Map("hit" -> 2L, "miss" -> 1L))
      }
    }
  }
}
