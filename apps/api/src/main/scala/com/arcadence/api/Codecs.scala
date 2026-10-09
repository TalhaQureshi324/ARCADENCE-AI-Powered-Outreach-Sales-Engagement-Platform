package com.arcadence.api

import com.arcadence.core.health.{HealthResponse, ReadyResponse}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import sttp.tapir.Schema

object Codecs:
  given Encoder[HealthResponse] = deriveEncoder
  given Decoder[HealthResponse] = deriveDecoder
  given Encoder[ReadyResponse] = deriveEncoder
  given Decoder[ReadyResponse] = deriveDecoder

  given Schema[HealthResponse] = Schema.derived
  given Schema[ReadyResponse] = Schema.derived
