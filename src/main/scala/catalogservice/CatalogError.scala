package catalogservice

sealed trait CatalogError

case object CatalogNotFound extends CatalogError
case object InvalidPagination extends CatalogError
