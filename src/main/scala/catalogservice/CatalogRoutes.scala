package catalogservice

import cats.{Monad, MonadThrow}
import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import catalogservice.domain.{
  Catalog,
  CatalogId,
  CatalogNotFound,
  CatalogRules,
  InvalidCatalog,
  InvalidPagination,
  Page
}
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

@description("A new catalog entry.")
final case class CreateCatalogRequest(
    @description("Product name.") @encodedExample("Rolex Submariner")
    name: String,
    @description("What the product is.")
    @encodedExample("Iconic diver's watch, 41mm stainless steel case")
    description: String,
    @description("Price in cents, at least 0.") @encodedExample(950000)
    priceCents: Int,
    @description("Stock-keeping unit.")
    @encodedExample("watch-rolex-submariner")
    sku: String
)

object CreateCatalogRequest {
  implicit val codec: Codec[CreateCatalogRequest] = deriveCodec
}

@description("Fields to update; `sku` is immutable.")
final case class UpdateCatalogRequest(
    @description("Product name.") @encodedExample("Rolex Submariner")
    name: String,
    @description("What the product is.")
    @encodedExample("Iconic diver's watch, 41mm stainless steel case")
    description: String,
    @description("Price in cents, at least 0.") @encodedExample(950000)
    priceCents: Int
)

object UpdateCatalogRequest {
  implicit val codec: Codec[UpdateCatalogRequest] = deriveCodec
}

@description("A catalog entry as stored.")
final case class CatalogResponse(
    @description("Catalog id (UUID).")
    @encodedExample("3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90")
    id: String,
    @description("Product name.") @encodedExample("Rolex Submariner")
    name: String,
    @description("What the product is.")
    @encodedExample("Iconic diver's watch, 41mm stainless steel case")
    description: String,
    @description("Price in cents.") @encodedExample(950000)
    priceCents: Int,
    @description("Stock-keeping unit.")
    @encodedExample("watch-rolex-submariner")
    sku: String,
    @description("When the entry was created (UTC).")
    @encodedExample("2026-11-01T09:30:00Z")
    createdAt: java.time.Instant,
    @description("When the entry last changed (UTC).")
    @encodedExample("2026-11-01T09:45:00Z")
    updatedAt: java.time.Instant
)

object CatalogResponse {
  implicit val codec: Codec[CatalogResponse] = deriveCodec

  def apply(entity: Catalog): CatalogResponse =
    CatalogResponse(
      CatalogId.render(entity.id),
      entity.name,
      entity.description,
      entity.priceCents,
      entity.sku,
      entity.createdAt,
      entity.updatedAt
    )
}

@description("Error body.")
final case class ErrorResponse(
    @description("Human-readable reason.")
    @encodedExample("Catalog not found")
    error: String
)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object CatalogRoutes {

  // REST docs convention (purerest README "Documenting endpoints"): every
  // endpoint, parameter, DTO field and error is described, and
  // CatalogDocsSuite asserts Docs.undocumented finds no gaps.
  private val tag = "catalogs"

  // Each fixed error's body, shared by its documented example and its real
  // mapping.
  private val notFoundBody = ErrorResponse("Catalog not found")
  private val invalidPaginationBody =
    ErrorResponse("Invalid pagination parameters")

  private val exampleCreateRequest = CreateCatalogRequest(
    "Rolex Submariner",
    "Iconic diver's watch, 41mm stainless steel case",
    950000,
    "watch-rolex-submariner"
  )

  private val exampleUpdateRequest = UpdateCatalogRequest(
    "Rolex Submariner",
    "Iconic diver's watch, 41mm stainless steel case",
    950000
  )

  private val exampleCatalog = CatalogResponse(
    "3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90",
    exampleCreateRequest.name,
    exampleCreateRequest.description,
    exampleCreateRequest.priceCents,
    exampleCreateRequest.sku,
    java.time.Instant.parse("2026-11-01T09:30:00Z"),
    java.time.Instant.parse("2026-11-01T09:45:00Z")
  )

  private val idPath: EndpointInput.PathCapture[CatalogId] =
    path[CatalogId]("id")
      .description("Catalog id (UUID). A malformed id returns 400.")
      .example(
        CatalogId.fromUuid(
          java.util.UUID.fromString("3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90")
        )
      )

  private def errorBody(
      what: String,
      example: ErrorResponse
  ): EndpointIO.Body[String, ErrorResponse] =
    jsonBody[ErrorResponse].description(what).example(example)

  private val invalidCatalogVariant
      : EndpointOutput.OneOfVariant[InvalidCatalog] =
    oneOfVariant(
      statusCode(StatusCode.BadRequest)
        .and(
          errorBody(
            "The body breaks a catalog rule: blank `name`/`sku` or `priceCents` < 0.",
            ErrorResponse("priceCents must be at least 0, got -1")
          )
        )
        .map(e => InvalidCatalog(e.error))(e => ErrorResponse(e.reason))
    )

  private val createCatalogEndpoint: PublicEndpoint[
    CreateCatalogRequest,
    InvalidCatalog,
    CatalogResponse,
    Any
  ] =
    endpoint.post
      .in("catalogs")
      .in(jsonBody[CreateCatalogRequest].example(exampleCreateRequest))
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[CatalogResponse].example(exampleCatalog))
      .errorOut(oneOf[InvalidCatalog](invalidCatalogVariant))
      .summary("Create a catalog entry")
      .description(
        "Adds a product. Blank `name`/`sku` or `priceCents` < 0 → 400. Not idempotent (new id each call; SKU uniqueness not enforced). Cached list pages are not invalidated."
      )
      .tag(tag)

  private val notFoundVariant
      : EndpointOutput.OneOfVariant[CatalogNotFound.type] =
    oneOfVariant(
      statusCode(StatusCode.NotFound)
        .and(errorBody("No catalog with this id.", notFoundBody))
        .map[CatalogNotFound.type](_ => CatalogNotFound)(_ => notFoundBody)
    )

  private val notFoundOutput: EndpointOutput[CatalogNotFound.type] =
    oneOf[CatalogNotFound.type](notFoundVariant)

  /** PATCH/PUT: a body breaking `CatalogRules` (400) or an unknown id (404). */
  private type UpdateError = InvalidCatalog | CatalogNotFound.type

  private val updateErrorOutput: EndpointOutput[UpdateError] =
    oneOf[UpdateError](invalidCatalogVariant, notFoundVariant)

  private val getCatalogEndpoint: PublicEndpoint[
    CatalogId,
    CatalogNotFound.type,
    CatalogResponse,
    Any
  ] =
    endpoint.get
      .in("catalogs" / idPath)
      .out(jsonBody[CatalogResponse].example(exampleCatalog))
      .errorOut(notFoundOutput)
      .summary("Get a catalog entry")
      .description(
        "Read from Postgres (not cached). 404 unknown id; 400 malformed id (tapir decode failure)."
      )
      .tag(tag)

  private val updateCatalogEndpoint: PublicEndpoint[
    (CatalogId, UpdateCatalogRequest),
    UpdateError,
    CatalogResponse,
    Any
  ] =
    endpoint.patch
      .in("catalogs" / idPath)
      .in(jsonBody[UpdateCatalogRequest].example(exampleUpdateRequest))
      .out(jsonBody[CatalogResponse].example(exampleCatalog))
      .errorOut(updateErrorOutput)
      .summary("Update a catalog entry")
      .description(
        "Sets name/description/priceCents (all required; `sku` immutable). Safe to repeat (only `updatedAt` advances). Body validated before lookup: 400, then 404."
      )
      .tag(tag)

  private val replaceCatalogEndpoint: PublicEndpoint[
    (CatalogId, UpdateCatalogRequest),
    UpdateError,
    CatalogResponse,
    Any
  ] =
    endpoint.put
      .in("catalogs" / idPath)
      .in(jsonBody[UpdateCatalogRequest].example(exampleUpdateRequest))
      .out(jsonBody[CatalogResponse].example(exampleCatalog))
      .errorOut(updateErrorOutput)
      .summary("Update a catalog entry (replace)")
      .description("Same as PATCH.")
      .tag(tag)

  private val deleteCatalogEndpoint
      : PublicEndpoint[CatalogId, CatalogNotFound.type, Unit, Any] =
    endpoint.delete
      .in("catalogs" / idPath)
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)
      .summary("Delete a catalog entry")
      .description("Hard delete; repeating returns 404.")
      .tag(tag)

  private val invalidPaginationOutput: EndpointOutput[InvalidPagination.type] =
    oneOf[InvalidPagination.type](
      oneOfVariant(
        statusCode(StatusCode.BadRequest)
          .and(
            errorBody(
              "`limit` outside 1–100 or `offset` < 0.",
              invalidPaginationBody
            )
          )
          .map[InvalidPagination.type](_ => InvalidPagination)(_ =>
            invalidPaginationBody
          )
      )
    )

  private val listCatalogsEndpoint: PublicEndpoint[
    (Option[Int], Option[Int]),
    InvalidPagination.type,
    (List[CatalogResponse], String),
    Any
  ] =
    endpoint.get
      .in("catalogs")
      .in(
        query[Option[Int]]("limit")
          .description("Maximum entries to return (1–100, default 20).")
          .example(Some(20))
      )
      .in(
        query[Option[Int]]("offset")
          .description("Entries to skip (default 0).")
          .example(Some(0))
      )
      .out(jsonBody[List[CatalogResponse]].example(List(exampleCatalog)))
      .out(
        header[String]("X-Total-Count")
          .description("Total catalog entries, independent of limit/offset.")
          .example("42")
      )
      .errorOut(invalidPaginationOutput)
      .summary("List catalog entries")
      .description(
        "One page, newest first (ties by id); `X-Total-Count` is the true total. Redis-cached per (limit, offset) with a 60s default TTL and no write invalidation; falls back to Postgres if Redis fails. Bad limit/offset → 400."
      )
      .tag(tag)

  def serverEndpoint[F[_]: MonadThrow](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createCatalogEndpoint.serverLogic[F] { req =>
      logger.info(
        Map(
          "method" -> "POST",
          "path" -> "/catalogs"
        )
      )("Received request") *>
        (CatalogRules.newCatalog(
          req.name,
          req.description,
          req.priceCents,
          req.sku
        ) match {
          case Left(invalid) =>
            logger
              .warn(Map("reason" -> invalid.reason))("Invalid catalog")
              .as(Left(invalid))
          case Right(valid) =>
            for {
              entity <- store
                .create(
                  valid.details.name,
                  valid.details.description,
                  valid.details.priceCents,
                  valid.sku
                )
                .onError { case error =>
                  logger.error(Map.empty, error)(
                    "Persisting the catalog failed"
                  )
                }
              _ <- logger.info(
                Map("catalog_id" -> CatalogId.render(entity.id))
              )("Request completed")
            } yield Right(CatalogResponse(entity))
        })
    }

  def getCatalogServerEndpoint[F[_]: Monad](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getCatalogEndpoint.serverLogic[F] { id =>
      val renderedId = CatalogId.render(id)
      for {
        _ <- logger.info(
          Map(
            "method" -> "GET",
            "path" -> s"/catalogs/$renderedId",
            "catalog_id" -> renderedId
          )
        )("Received request")
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("catalog_id" -> renderedId))("Request completed")
              .as(Right(CatalogResponse(entity)))
          case None =>
            logger
              .warn(Map("catalog_id" -> renderedId))("Catalog not found")
              .as(Left(CatalogNotFound))
        }
      } yield result
    }

  /** Shared handler for `PATCH` (partial update) and `PUT` (full replace) —
    * both call `CatalogStore.update` with the same required update body; only
    * the logged HTTP method differs.
    */
  private def updateLogic[F[_]: Monad](
      store: CatalogStore[F],
      logger: StructuredLogger[F],
      httpMethod: String
  )(
      id: CatalogId,
      req: UpdateCatalogRequest
  ): F[Either[UpdateError, CatalogResponse]] = {
    val renderedId = CatalogId.render(id)
    for {
      _ <- logger.info(
        Map(
          "method" -> httpMethod,
          "path" -> s"/catalogs/$renderedId",
          "catalog_id" -> renderedId
        )
      )("Received request")
      result <- CatalogRules.details(
        req.name,
        req.description,
        req.priceCents
      ) match {
        case Left(invalid) =>
          logger
            .warn(Map("catalog_id" -> renderedId, "reason" -> invalid.reason))(
              "Invalid catalog"
            )
            .as(Left(invalid))
        case Right(details) =>
          store
            .update(id, details.name, details.description, details.priceCents)
            .flatMap {
              case Some(entity) =>
                logger
                  .info(Map("catalog_id" -> renderedId))("Request completed")
                  .as(Right(CatalogResponse(entity)))
              case None =>
                logger
                  .warn(Map("catalog_id" -> renderedId))("Catalog not found")
                  .as(Left(CatalogNotFound))
            }
      }
    } yield result
  }

  def updateCatalogServerEndpoint[F[_]: Monad](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    updateCatalogEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PATCH")(id, req)
    }

  def replaceCatalogServerEndpoint[F[_]: Monad](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    replaceCatalogEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PUT")(id, req)
    }

  def deleteCatalogServerEndpoint[F[_]: Monad](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteCatalogEndpoint.serverLogic[F] { id =>
      val renderedId = CatalogId.render(id)
      for {
        _ <- logger.info(
          Map(
            "method" -> "DELETE",
            "path" -> s"/catalogs/$renderedId",
            "catalog_id" -> renderedId
          )
        )("Received request")
        result <- store.delete(id).flatMap {
          case true =>
            logger
              .info(Map("catalog_id" -> renderedId))("Request completed")
              .as(Right(()))
          case false =>
            logger
              .warn(Map("catalog_id" -> renderedId))("Catalog not found")
              .as(Left(CatalogNotFound))
        }
      } yield result
    }

  /** Cache-aside: a hit returns the cached page as-is; a miss queries
    * `CatalogStore.list`, populates the cache (TTL-only freshness - see
    * spec.md's "Out of Scope"), and returns the freshly-queried result. A cache
    * read error is treated as a miss and a write error is logged, so Redis
    * being down never fails the request.
    */
  def listCatalogsServerEndpoint[F[_]: MonadThrow](
      store: CatalogStore[F],
      logger: StructuredLogger[F],
      cache: CatalogListCache[F]
  ): ServerEndpoint[Any, F] = {
    def pageContext(page: Page, cacheOutcome: String): Map[String, String] =
      Map(
        "limit" -> page.limit.toString,
        "offset" -> page.offset.toString,
        "cache" -> cacheOutcome
      )

    // Postgres is the source of truth; a failed cache write is logged, never
    // surfaced.
    def fromStore(page: Page): F[(List[CatalogResponse], String)] =
      for {
        (catalogs, total) <- store.list(page.limit, page.offset)
        responses = catalogs.map(CatalogResponse(_))
        _ <- cache
          .set(page.limit, page.offset, responses, total)
          .handleErrorWith(error =>
            logger.warn(pageContext(page, "write_error"), error)(
              "Catalog-list cache write failed - response unaffected"
            )
          )
        _ <- logger.info(pageContext(page, "miss"))("Request completed")
      } yield (responses, total.toString)

    def serve(page: Page): F[(List[CatalogResponse], String)] =
      cache.get(page.limit, page.offset).attempt.flatMap {
        case Right(Some((catalogs, total))) =>
          logger
            .info(pageContext(page, "hit"))("Request completed")
            .as((catalogs, total.toString))
        case Right(None) => fromStore(page)
        case Left(error) =>
          logger.warn(pageContext(page, "read_error"), error)(
            "Catalog-list cache read failed - falling back to Postgres"
          ) *> fromStore(page)
      }

    listCatalogsEndpoint.serverLogic[F] { case (limitParam, offsetParam) =>
      logger.info(Map("method" -> "GET", "path" -> "/catalogs"))(
        "Received request"
      ) *> (Page.from(limitParam, offsetParam) match {
        case Left(invalid) =>
          logger
            .warn(
              Map(
                "limit" -> limitParam.fold("")(_.toString),
                "offset" -> offsetParam.fold("")(_.toString)
              )
            )("Invalid pagination parameters")
            .as(Left(invalid))
        case Right(page) => serve(page).map(Right(_))
      })
    }
  }

  def routes[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F],
      cache: CatalogListCache[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        serverEndpoint(store, logger),
        getCatalogServerEndpoint(store, logger),
        updateCatalogServerEndpoint(store, logger),
        replaceCatalogServerEndpoint(store, logger),
        deleteCatalogServerEndpoint(store, logger),
        listCatalogsServerEndpoint(store, logger, cache)
      )
    )
}
