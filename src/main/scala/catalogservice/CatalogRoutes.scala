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
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class CreateCatalogRequest(
    name: String,
    description: String,
    priceCents: Int,
    sku: String
)

object CreateCatalogRequest {
  implicit val codec: Codec[CreateCatalogRequest] = deriveCodec
}

final case class UpdateCatalogRequest(
    name: String,
    description: String,
    priceCents: Int
)

object UpdateCatalogRequest {
  implicit val codec: Codec[UpdateCatalogRequest] = deriveCodec
}

final case class CatalogResponse(
    id: String,
    name: String,
    description: String,
    priceCents: Int,
    sku: String,
    createdAt: java.time.Instant,
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

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object CatalogRoutes {

  private val invalidCatalogVariant
      : EndpointOutput.OneOfVariant[InvalidCatalog] =
    oneOfVariant(
      statusCode(StatusCode.BadRequest)
        .and(jsonBody[ErrorResponse])
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
      .in(jsonBody[CreateCatalogRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[CatalogResponse])
      .errorOut(oneOf[InvalidCatalog](invalidCatalogVariant))

  private val notFoundVariant
      : EndpointOutput.OneOfVariant[CatalogNotFound.type] =
    oneOfVariant(
      statusCode(StatusCode.NotFound)
        .and(jsonBody[ErrorResponse])
        .map[CatalogNotFound.type](_ => CatalogNotFound)(_ =>
          ErrorResponse("Catalog not found")
        )
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
      .in("catalogs" / path[CatalogId]("id"))
      .out(jsonBody[CatalogResponse])
      .errorOut(notFoundOutput)

  private val updateCatalogEndpoint: PublicEndpoint[
    (CatalogId, UpdateCatalogRequest),
    UpdateError,
    CatalogResponse,
    Any
  ] =
    endpoint.patch
      .in("catalogs" / path[CatalogId]("id"))
      .in(jsonBody[UpdateCatalogRequest])
      .out(jsonBody[CatalogResponse])
      .errorOut(updateErrorOutput)

  private val replaceCatalogEndpoint: PublicEndpoint[
    (CatalogId, UpdateCatalogRequest),
    UpdateError,
    CatalogResponse,
    Any
  ] =
    endpoint.put
      .in("catalogs" / path[CatalogId]("id"))
      .in(jsonBody[UpdateCatalogRequest])
      .out(jsonBody[CatalogResponse])
      .errorOut(updateErrorOutput)

  private val deleteCatalogEndpoint
      : PublicEndpoint[CatalogId, CatalogNotFound.type, Unit, Any] =
    endpoint.delete
      .in("catalogs" / path[CatalogId]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)

  private val invalidPaginationOutput: EndpointOutput[InvalidPagination.type] =
    oneOf[InvalidPagination.type](
      oneOfVariant(
        statusCode(StatusCode.BadRequest)
          .and(jsonBody[ErrorResponse])
          .map[InvalidPagination.type](_ => InvalidPagination)(_ =>
            ErrorResponse("Invalid pagination parameters")
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
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[List[CatalogResponse]])
      .out(header[String]("X-Total-Count"))
      .errorOut(invalidPaginationOutput)

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
