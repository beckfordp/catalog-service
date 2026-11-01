package catalogservice.domain

/** A validated `GET /catalogs` page request: `1 <= limit <= maxLimit`,
  * `offset >= 0`. Only `from` builds one.
  */
final case class Page private (limit: Int, offset: Int)

object Page {

  val defaultLimit: Int = 20
  val maxLimit: Int = 100

  /** Absent params default to `defaultLimit` / 0. */
  def from(
      limit: Option[Int],
      offset: Option[Int]
  ): Either[InvalidPagination.type, Page] = {
    val l = limit.getOrElse(defaultLimit)
    val o = offset.getOrElse(0)
    Either.cond(
      l >= 1 && l <= maxLimit && o >= 0,
      new Page(l, o),
      InvalidPagination
    )
  }
}
