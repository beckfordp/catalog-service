package catalogservice.domain

import cats.syntax.all._
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

import java.util.UUID

/** Opaque catalog-row identifier, parsed once at the HTTP boundary (parse,
  * don't validate - scala-fp.md law 5).
  */
opaque type CatalogId = UUID

object CatalogId {

  def parse(raw: String): Either[String, CatalogId] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(raw))
      .leftMap(_ => s"Invalid catalog id: '$raw'")

  def fromUuid(value: UUID): CatalogId = value

  def uuid(value: CatalogId): UUID = value

  def render(value: CatalogId): String = value.toString

  given Codec[String, CatalogId, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw =>
      parse(raw) match {
        case Right(id) => DecodeResult.Value(id)
        case Left(msg) =>
          DecodeResult.Error(raw, new IllegalArgumentException(msg))
      }
    )(render)

  given Schema[CatalogId] = Schema.string[CatalogId]
}
