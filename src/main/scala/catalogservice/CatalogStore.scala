package catalogservice

import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Console
import cats.syntax.all._
import fs2.io.net.Network
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import skunk.Session
import skunk.codec.all._
import skunk.implicits._

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.SECONDS

final case class Catalog(
    id: String,
    name: String,
    description: String,
    priceCents: Int,
    sku: String,
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

trait CatalogStore[F[_]] {
  def create(
      name: String,
      description: String,
      priceCents: Int,
      sku: String
  ): F[Catalog]
  def get(id: String): F[Option[Catalog]]
  def update(
      id: String,
      name: String,
      description: String,
      priceCents: Int
  ): F[Option[Catalog]]
  def delete(id: String): F[Boolean]
  def list(limit: Int, offset: Int): F[(List[Catalog], Long)]
  def ping: F[Boolean]
}

object CatalogStore {

  def inMemory[F[_]: Sync]: F[CatalogStore[F]] =
    Ref.of[F, Map[String, Catalog]](Map.empty).map { ref =>
      new CatalogStore[F] {
        def create(
            name: String,
            description: String,
            priceCents: Int,
            sku: String
        ): F[Catalog] =
          for {
            id <- Sync[F].delay(java.util.UUID.randomUUID().toString)
            now <- Sync[F].realTimeInstant
            entity = Catalog(id, name, description, priceCents, sku, now, now)
            _ <- ref.update(_ + (id -> entity))
          } yield entity

        def get(id: String): F[Option[Catalog]] = ref.get.map(_.get(id))

        def update(
            id: String,
            name: String,
            description: String,
            priceCents: Int
        ): F[Option[Catalog]] =
          for {
            now <- Sync[F].realTimeInstant
            updated <- ref.modify { entities =>
              entities.get(id) match {
                case None           => (entities, None)
                case Some(existing) =>
                  val next =
                    existing.copy(
                      name = name,
                      description = description,
                      priceCents = priceCents,
                      updatedAt = now
                    )
                  (entities + (id -> next), Some(next))
              }
            }
          } yield updated

        def delete(id: String): F[Boolean] =
          ref.modify { entities =>
            if (entities.contains(id)) (entities - id, true)
            else (entities, false)
          }

        def list(limit: Int, offset: Int): F[(List[Catalog], Long)] =
          ref.get.map { entities =>
            val sorted = entities.values.toList
              .sortBy(_.createdAt)(using Ordering[java.time.Instant].reverse)
            (sorted.slice(offset, offset + limit), sorted.size.toLong)
          }

        def ping: F[Boolean] = Sync[F].pure(true)
      }
    }

  private val insertCatalog: skunk.Query[
    (UUID, String, String, Int, String),
    (OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      INSERT INTO "catalog" (id, name, description, price_cents, sku)
      VALUES ($uuid, $text, $text, $int4, $text)
      RETURNING created_at, updated_at
    """.query(timestamptz *: timestamptz)

  private val selectCatalog: skunk.Query[
    UUID,
    (String, String, Int, String, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      SELECT name, description, price_cents, sku, created_at, updated_at
      FROM "catalog"
      WHERE id = $uuid
    """.query(text *: text *: int4 *: text *: timestamptz *: timestamptz)

  private val updateCatalog: skunk.Query[
    (String, String, Int, UUID),
    (String, String, Int, String, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      UPDATE "catalog"
      SET name = $text, description = $text, price_cents = $int4, updated_at = now()
      WHERE id = $uuid
      RETURNING name, description, price_cents, sku, created_at, updated_at
    """.query(text *: text *: int4 *: text *: timestamptz *: timestamptz)

  private val deleteCatalog: skunk.Query[UUID, UUID] =
    sql"""
      DELETE FROM "catalog"
      WHERE id = $uuid
      RETURNING id
    """.query(uuid)

  private val pingQuery: skunk.Query[skunk.Void, Int] = sql"SELECT 1".query(
    int4
  )

  // count(*) OVER() rides along with each row so the page and its total are
  // read from one consistent query result, not two separate round trips that
  // could otherwise observe different data if a write lands in between.
  private val selectCatalogPage: skunk.Query[
    (Int, Int),
    (UUID, String, String, Int, String, OffsetDateTime, OffsetDateTime, Long)
  ] =
    sql"""
      SELECT id, name, description, price_cents, sku, created_at, updated_at,
             count(*) OVER() AS total_count
      FROM "catalog"
      ORDER BY created_at DESC
      LIMIT $int4 OFFSET $int4
    """.query(
      uuid *: text *: text *: int4 *: text *: timestamptz *: timestamptz *: int8
    )

  // Fallback for when selectCatalogPage returns zero rows (empty table, or
  // offset past the end) -- count(*) OVER() has no row to ride along with in
  // that case, so the total still needs a direct query.
  private val countCatalog: skunk.Query[skunk.Void, Long] =
    sql"""SELECT count(*) FROM "catalog"""".query(int8)

  def postgres[F[_]: Async: Console: Network](
      config: PostgresConfig,
      meter: Meter[F]
  ): Resource[F, CatalogStore[F]] = {
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    Session
      .Builder[F]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password)
      .withDatabase(config.database)
      .pooled(max = 10)
      .evalMap { pool =>
        meter
          .histogram[Double]("db.client.operation.duration")
          .withUnit("s")
          .create
          .map { histogram =>
            /** Times a Skunk query, recording a `db.client.operation.duration`
              * measurement tagged with `db.system`/`db.operation` (OTel
              * semantic-convention names), plus `error.type` if it fails - this
              * is this service's only Postgres consumer, so it's instrumented
              * directly here rather than via a new purerest combinator.
              */
            def timed[A](operation: String)(fa: F[A]): F[A] =
              for {
                start <- Async[F].monotonic
                result <- fa.attempt
                end <- Async[F].monotonic
                outcomeAttributes = result match {
                  case Right(_)    => Nil
                  case Left(error) =>
                    List(Attribute("error.type", error.getClass.getName))
                }
                _ <- histogram.record(
                  (end - start).toUnit(SECONDS),
                  List(
                    Attribute("db.system", "postgresql"),
                    Attribute("db.operation", operation)
                  ) ++ outcomeAttributes
                )
                a <- result.liftTo[F]
              } yield a

            new CatalogStore[F] {
              def create(
                  name: String,
                  description: String,
                  priceCents: Int,
                  sku: String
              ): F[Catalog] =
                for {
                  id <- Sync[F].delay(UUID.randomUUID())
                  timestamps <- timed("insert") {
                    pool.use { session =>
                      session
                        .prepare(insertCatalog)
                        .flatMap(
                          _.unique((id, name, description, priceCents, sku))
                        )
                    }
                  }
                } yield {
                  val (createdAt, updatedAt) = timestamps
                  Catalog(
                    id.toString,
                    name,
                    description,
                    priceCents,
                    sku,
                    createdAt.toInstant,
                    updatedAt.toInstant
                  )
                }

              def get(id: String): F[Option[Catalog]] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(None)
                  case Some(uuid) =>
                    for {
                      row <- timed("select") {
                        pool.use { session =>
                          session.prepare(selectCatalog).flatMap(_.option(uuid))
                        }
                      }
                    } yield row.map {
                      case (
                            name,
                            description,
                            priceCents,
                            sku,
                            createdAt,
                            updatedAt
                          ) =>
                        Catalog(
                          id,
                          name,
                          description,
                          priceCents,
                          sku,
                          createdAt.toInstant,
                          updatedAt.toInstant
                        )
                    }
                }

              def update(
                  id: String,
                  name: String,
                  description: String,
                  priceCents: Int
              ): F[Option[Catalog]] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(None)
                  case Some(uuid) =>
                    for {
                      row <- timed("update") {
                        pool.use { session =>
                          session
                            .prepare(updateCatalog)
                            .flatMap(
                              _.option((name, description, priceCents, uuid))
                            )
                        }
                      }
                    } yield row.map {
                      case (
                            name,
                            description,
                            priceCents,
                            sku,
                            createdAt,
                            updatedAt
                          ) =>
                        Catalog(
                          id,
                          name,
                          description,
                          priceCents,
                          sku,
                          createdAt.toInstant,
                          updatedAt.toInstant
                        )
                    }
                }

              def delete(id: String): F[Boolean] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(false)
                  case Some(uuid) =>
                    timed("delete") {
                      pool.use { session =>
                        session
                          .prepare(deleteCatalog)
                          .flatMap(_.option(uuid))
                          .map(_.isDefined)
                      }
                    }
                }

              def list(limit: Int, offset: Int): F[(List[Catalog], Long)] =
                for {
                  rows <- timed("list") {
                    pool.use { session =>
                      session
                        .prepare(selectCatalogPage)
                        .flatMap(_.stream((limit, offset), 1024).compile.toList)
                    }
                  }
                  total <- rows.headOption match {
                    case Some((_, _, _, _, _, _, _, totalCount)) =>
                      Sync[F].pure(totalCount)
                    case None =>
                      timed("count") { pool.use(_.unique(countCatalog)) }
                  }
                } yield rows.map {
                  case (
                        id,
                        name,
                        description,
                        priceCents,
                        sku,
                        createdAt,
                        updatedAt,
                        _
                      ) =>
                    Catalog(
                      id.toString,
                      name,
                      description,
                      priceCents,
                      sku,
                      createdAt.toInstant,
                      updatedAt.toInstant
                    )
                } -> total

              def ping: F[Boolean] =
                timed("ping") {
                  pool.use(_.unique(pingQuery))
                }.attempt.map(_.isRight)
            }
          }
      }
  }
}
