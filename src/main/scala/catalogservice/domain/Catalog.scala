package catalogservice.domain

/** A catalog entry as stored. */
final case class Catalog(
    id: CatalogId,
    name: String,
    description: String,
    priceCents: Int,
    sku: String,
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)
