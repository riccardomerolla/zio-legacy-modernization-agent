package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.json.*

object Codecs:
  given JsonCodec[Path] = JsonCodec[String].transform(
    str => Paths.get(str),
    path => path.toString,
  )

  given JsonCodec[Instant] = JsonCodec[String].transform(
    str => Instant.parse(str),
    instant => instant.toString,
  )

  given JsonCodec[zio.Duration] = JsonCodec[Long].transform(
    millis => zio.Duration.fromMillis(millis),
    duration => duration.toMillis,
  )
