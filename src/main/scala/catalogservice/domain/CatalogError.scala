// SPDX-License-Identifier: MIT

package catalogservice.domain

/** Expected, nameable failures, mapped to HTTP statuses at the edge
  * (`CatalogRoutes`).
  */
sealed trait CatalogError

case object CatalogNotFound extends CatalogError
case object InvalidPagination extends CatalogError

/** A create/update body broke a `CatalogRules` rule; `reason` is the
  * client-facing message.
  */
final case class InvalidCatalog(reason: String) extends CatalogError
