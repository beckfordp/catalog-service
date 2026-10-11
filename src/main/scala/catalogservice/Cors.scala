package catalogservice

import cats.effect.Async
import org.http4s.{HttpRoutes, Method}
import org.http4s.server.middleware.CORS
import org.typelevel.ci._

/** CORS policy: allow all origins, restricted to the methods/headers this
  * service's routes actually use, credentials disallowed (required anyway
  * alongside an allow-all-origins policy — browsers reject credentialed
  * requests against a wildcard origin). `X-Total-Count` (GET /catalogs's
  * pagination total) is explicitly exposed — without it, browsers hide any
  * non-CORS-safelisted response header from JS by default even though the
  * header itself is sent and visible to non-browser clients like curl.
  */
object Cors {

  def middleware[F[_]: Async](routes: HttpRoutes[F]): HttpRoutes[F] =
    CORS.policy
      .withAllowOriginAll
      .withAllowMethodsIn(
        Set(Method.GET, Method.POST, Method.PATCH, Method.PUT, Method.DELETE)
      )
      .withAllowHeadersIn(Set(ci"Content-Type"))
      .withExposeHeadersIn(Set(ci"X-Total-Count"))
      .withAllowCredentials(false)
      .apply(routes)
}
