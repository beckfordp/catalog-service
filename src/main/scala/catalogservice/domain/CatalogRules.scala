package catalogservice.domain

/** The mutable fields of a catalog entry, parsed by `CatalogRules.details`. */
final case class CatalogDetails(
    name: String,
    description: String,
    priceCents: Int
)

/** A new catalog entry, parsed by `CatalogRules.newCatalog`. */
final case class NewCatalog(details: CatalogDetails, sku: String)

/** Field rules for create/update bodies, defined once: a non-blank name and
  * SKU, a price of at least 0 cents. `description` may be empty.
  */
object CatalogRules {

  def details(
      name: String,
      description: String,
      priceCents: Int
  ): Either[InvalidCatalog, CatalogDetails] =
    if (name.isBlank) Left(InvalidCatalog("name must not be blank"))
    else if (priceCents < 0)
      Left(InvalidCatalog(s"priceCents must be at least 0, got $priceCents"))
    else Right(CatalogDetails(name, description, priceCents))

  def newCatalog(
      name: String,
      description: String,
      priceCents: Int,
      sku: String
  ): Either[InvalidCatalog, NewCatalog] =
    details(name, description, priceCents).flatMap { d =>
      Either.cond(
        !sku.isBlank,
        NewCatalog(d, sku),
        InvalidCatalog("sku must not be blank")
      )
    }
}
