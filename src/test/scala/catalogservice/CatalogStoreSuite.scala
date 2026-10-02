package catalogservice

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration._

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

  test("list returns an empty list and zero total for an empty store") {
    for {
      store <- CatalogStore.inMemory[IO]
      result <- store.list(20, 0)
    } yield assertEquals(result, (Nil, 0L))
  }

  test("list returns entities newest-first") {
    for {
      store <- CatalogStore.inMemory[IO]
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

  test("list respects limit and offset") {
    for {
      store <- CatalogStore.inMemory[IO]
      first <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
      _ <- IO.sleep(2.millis)
      second <- store.create("Gadget", "A very fine gadget", 2999, "sku-gadget-1")
      _ <- IO.sleep(2.millis)
      third <- store.create("Gizmo", "A very fine gizmo", 3999, "sku-gizmo-1")
      _ <- IO.sleep(2.millis)
      fourth <- store.create("Doohickey", "A very fine doohickey", 4999, "sku-doohickey-1")
      _ <- IO.sleep(2.millis)
      fifth <- store.create("Thingamajig", "A very fine thingamajig", 5999, "sku-thingamajig-1")
      result <- store.list(2, 1)
    } yield assertEquals(result._1.map(_.id), List(fourth.id, third.id))
  }

  test("list returns the correct total count regardless of page size") {
    for {
      store <- CatalogStore.inMemory[IO]
      _ <- store.create("Widget", "A very fine widget", 1999, "sku-widget-1")
      _ <- store.create("Gadget", "A very fine gadget", 2999, "sku-gadget-1")
      _ <- store.create("Gizmo", "A very fine gizmo", 3999, "sku-gizmo-1")
      _ <- store.create("Doohickey", "A very fine doohickey", 4999, "sku-doohickey-1")
      _ <- store.create("Thingamajig", "A very fine thingamajig", 5999, "sku-thingamajig-1")
      result <- store.list(2, 0)
    } yield {
      assertEquals(result._1.size, 2)
      assertEquals(result._2, 5L)
    }
  }
}
