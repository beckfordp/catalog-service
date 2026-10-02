package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite

class CatalogStoreSuite extends CatsEffectSuite {

  test("create returns a persisted entity with a generated id") {
    for {
      store <- CatalogStore.inMemory[IO]
      entity <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
    } yield assert(entity.id.nonEmpty)
  }

  test("get returns the persisted entity") {
    for {
      store <- CatalogStore.inMemory[IO]
      created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
      found <- store.get(created.id)
    } yield assertEquals(found, Some(created))
  }

  test("get returns None for an unknown id") {
    for {
      store <- CatalogStore.inMemory[IO]
      found <- store.get("unknown-id")
    } yield assertEquals(found, None)
  }

  test("create produces distinct ids across calls") {
    for {
      store <- CatalogStore.inMemory[IO]
      first <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
      second <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
    } yield assertNotEquals(first.id, second.id)
  }

  test("update returns the updated entity with updatedAt not moving backwards") {
    for {
      store <- CatalogStore.inMemory[IO]
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

  test("update returns None for an unknown id") {
    for {
      store <- CatalogStore.inMemory[IO]
      result <- store.update("unknown-id", "Widget", "A very fine widget", 1999)
    } yield assertEquals(result, None)
  }

  test("delete removes the entity and returns true, and get then returns None") {
    for {
      store <- CatalogStore.inMemory[IO]
      created <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
      deleted <- store.delete(created.id)
      found <- store.get(created.id)
    } yield {
      assert(deleted)
      assertEquals(found, None)
    }
  }

  test("delete returns false for an unknown id") {
    for {
      store <- CatalogStore.inMemory[IO]
      deleted <- store.delete("unknown-id")
    } yield assert(!deleted)
  }
}
