package catalogservice

import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
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
      entity.id,
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

  private val createCatalogEndpoint: PublicEndpoint[
    CreateCatalogRequest,
    Unit,
    CatalogResponse,
    Any
  ] =
    endpoint.post
      .in("catalogs")
      .in(jsonBody[CreateCatalogRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[CatalogResponse])

  private val notFoundOutput: EndpointOutput[CatalogError] =
    statusCode(StatusCode.NotFound)
      .and(jsonBody[ErrorResponse])
      .map[CatalogError](_ => CatalogNotFound)(_ =>
        ErrorResponse("Catalog not found")
      )

  private val getCatalogEndpoint: PublicEndpoint[
    String,
    CatalogError,
    CatalogResponse,
    Any
  ] =
    endpoint.get
      .in("catalogs" / path[String]("id"))
      .out(jsonBody[CatalogResponse])
      .errorOut(notFoundOutput)

  private val updateCatalogEndpoint: PublicEndpoint[
    (String, UpdateCatalogRequest),
    CatalogError,
    CatalogResponse,
    Any
  ] =
    endpoint.patch
      .in("catalogs" / path[String]("id"))
      .in(jsonBody[UpdateCatalogRequest])
      .out(jsonBody[CatalogResponse])
      .errorOut(notFoundOutput)

  private val replaceCatalogEndpoint: PublicEndpoint[
    (String, UpdateCatalogRequest),
    CatalogError,
    CatalogResponse,
    Any
  ] =
    endpoint.put
      .in("catalogs" / path[String]("id"))
      .in(jsonBody[UpdateCatalogRequest])
      .out(jsonBody[CatalogResponse])
      .errorOut(notFoundOutput)

  private val deleteCatalogEndpoint
      : PublicEndpoint[String, CatalogError, Unit, Any] =
    endpoint.delete
      .in("catalogs" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)

  def serverEndpoint[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createCatalogEndpoint.serverLogicSuccess[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/catalogs"
          )
        )("Received request")
        entity <- store
          .create(req.name, req.description, req.priceCents, req.sku)
          .onError { case error =>
            logger.error(Map.empty, error)("Persisting the catalog failed")
          }
        _ <- logger.info(
          Map("catalog_id" -> entity.id)
        )("Request completed")
      } yield CatalogResponse(entity)
    }

  def getCatalogServerEndpoint[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getCatalogEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map("method" -> "GET", "path" -> s"/catalogs/$id", "catalog_id" -> id)
        )(
          "Received request"
        )
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("catalog_id" -> id))("Request completed")
              .as(Right(CatalogResponse(entity)))
          case None =>
            logger
              .warn(Map("catalog_id" -> id))("Catalog not found")
              .as(Left(CatalogNotFound))
        }
      } yield result
    }

  /** Shared handler for `PATCH` (partial update) and `PUT` (full replace) —
    * both call `CatalogStore.update` with the same required update body; only
    * the logged HTTP method differs.
    */
  private def updateLogic[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F],
      httpMethod: String
  )(
      id: String,
      req: UpdateCatalogRequest
  ): F[Either[CatalogError, CatalogResponse]] =
    for {
      _ <- logger.info(
        Map(
          "method" -> httpMethod,
          "path" -> s"/catalogs/$id",
          "catalog_id" -> id
        )
      )("Received request")
      result <- store
        .update(id, req.name, req.description, req.priceCents)
        .flatMap {
          case Some(entity) =>
            logger
              .info(Map("catalog_id" -> id))("Request completed")
              .as(Right(CatalogResponse(entity)))
          case None =>
            logger
              .warn(Map("catalog_id" -> id))("Catalog not found")
              .as(Left(CatalogNotFound))
        }
    } yield result

  def updateCatalogServerEndpoint[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    updateCatalogEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PATCH")(id, req)
    }

  def replaceCatalogServerEndpoint[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    replaceCatalogEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PUT")(id, req)
    }

  def deleteCatalogServerEndpoint[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteCatalogEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "DELETE",
            "path" -> s"/catalogs/$id",
            "catalog_id" -> id
          )
        )("Received request")
        result <- store.delete(id).flatMap {
          case true =>
            logger
              .info(Map("catalog_id" -> id))("Request completed")
              .as(Right(()))
          case false =>
            logger
              .warn(Map("catalog_id" -> id))("Catalog not found")
              .as(Left(CatalogNotFound))
        }
      } yield result
    }

  def routes[F[_]: Async](
      store: CatalogStore[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        serverEndpoint(store, logger),
        getCatalogServerEndpoint(store, logger),
        updateCatalogServerEndpoint(store, logger),
        replaceCatalogServerEndpoint(store, logger),
        deleteCatalogServerEndpoint(store, logger)
      )
    )
}
