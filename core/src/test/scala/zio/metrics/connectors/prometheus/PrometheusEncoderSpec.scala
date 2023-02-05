package zio.metrics.connectors.prometheus

import zio._
import zio.metrics.{MetricKey, MetricLabel}
import zio.metrics.connectors._
import zio.metrics.connectors.MetricEvent._
import zio.test._
import zio.test.TestAspect._

object PrometheusEncoderSpec extends ZIOSpecDefault with Generators {

  override def spec = suite("The Prometheus encoding should")(
    encodeCounter,
    encodeGauge,
    encodeFrequency,
    encodeSummary,
    encodeHistogram,
  ) @@ timed @@ timeoutWarning(60.seconds) @@ parallel @@ withLiveClock

  private def helpString(key: MetricKey.Untyped) =
    key.tags.find(_.key == descriptionKey).fold("")(d => s" ${d.value}")

  private def labelString(key: MetricKey.Untyped, extra: (String, String)*) = {
    val tags = key.tags.filter(_.key != descriptionKey) ++ extra.map(x => MetricLabel(x._1, x._2)).toSet
    if (tags.isEmpty) ""
    else tags.map(l => s"""${l.key}="${l.value}"""").mkString("{", ",", "}")
  }

  private val encodeCounter = test("Encode a Counter")(check(genCounter) { case (pair, state) =>
    for {
      timestamp <- ZIO.clockWith(_.instant)
      text      <- PrometheusEncoder.encode(New(pair.metricKey, state, timestamp), Some(descriptionKey))
      name       = pair.metricKey.name
      help       = helpString(pair.metricKey)
      labels     = labelString(pair.metricKey)
    } yield assertTrue(
      text == Chunk(
        s"# TYPE $name counter",
        s"# HELP $name$help",
        s"$name$labels ${state.count} ${timestamp.toEpochMilli}",
      ),
    )
  })

  private val encodeGauge = test("Encode a Gauge")(check(genGauge) { case (pair, state) =>
    for {
      timestamp <- ZIO.clockWith(_.instant)
      text      <- PrometheusEncoder.encode(New(pair.metricKey, state, timestamp), Some(descriptionKey))
      name       = pair.metricKey.name
      help       = helpString(pair.metricKey)
      labels     = labelString(pair.metricKey)
    } yield assertTrue(
      text == Chunk(
        s"# TYPE $name gauge",
        s"# HELP $name$help",
        s"$name$labels ${state.value} ${timestamp.toEpochMilli}",
      ),
    )
  })

  private val encodeFrequency = test("Encode a Frequency")(check(genFrequency1) { case (pair, state) =>
    for {
      timestamp <- ZIO.clockWith(_.instant)
      text      <- PrometheusEncoder.encode(New(pair.metricKey, state, timestamp), Some(descriptionKey))
      name       = pair.metricKey.name
      help       = helpString(pair.metricKey)
      expected   = Chunk.fromIterable(state.occurrences).flatMap { case (k, v) =>
                     val labels = labelString(pair.metricKey, "bucket" -> k)
                     Chunk(
                       s"# TYPE $name counter",
                       s"# HELP $name$help",
                       s"""$name$labels ${v.toDouble} ${timestamp.toEpochMilli}""",
                     )
                   }
    } yield assertTrue(text == expected)
  })

  private val encodeSummary = test("Encode a Summary")(check(genSummary) { case (pair, state) =>
    for {
      timestamp <- ZIO.clockWith(_.instant)
      text      <- PrometheusEncoder.encode(New(pair.metricKey, state, timestamp), Some(descriptionKey))
      name       = pair.metricKey.name
      epochMilli = timestamp.toEpochMilli
      help       = helpString(pair.metricKey)
      labels     = labelString(pair.metricKey)
    } yield assertTrue(
      text == Chunk(
        s"# TYPE $name summary",
        s"# HELP $name$help",
      ) ++ state.quantiles.map { case (k, v) =>
        val labelsWithExtra = labelString(pair.metricKey, "quantile" -> k.toString, "error" -> state.error.toString)
        s"""$name$labelsWithExtra ${v.getOrElse(Double.NaN)} $epochMilli"""
      } ++ Chunk(
        s"${name}_sum$labels ${state.sum} $epochMilli",
        s"${name}_count$labels ${state.count.toDouble} $epochMilli",
        s"${name}_min$labels ${state.min} $epochMilli",
        s"${name}_max$labels ${state.max} $epochMilli",
      ),
    )
  })

  private val encodeHistogram = test("Encode a Histogram")(check(genHistogram) { case (pair, state) =>
    for {
      timestamp    <- ZIO.clockWith(_.instant)
      text         <- PrometheusEncoder.encode(New(pair.metricKey, state, timestamp), Some(descriptionKey))
      name          = pair.metricKey.name
      epochMilli    = timestamp.toEpochMilli
      help          = helpString(pair.metricKey)
      labels        = labelString(pair.metricKey)
      labelsWithInf = labelString(pair.metricKey, "le" -> "+Inf")
    } yield assertTrue(
      text == Chunk(
        s"# TYPE $name histogram",
        s"# HELP $name$help",
      ) ++ state.buckets.filter(_._1 < Double.MaxValue).map { case (k, v) =>
        val labelsWithExtra = labelString(pair.metricKey, "le" -> k.toString)
        s"""${name}_bucket$labelsWithExtra ${v.toDouble} $epochMilli"""
      } ++ Chunk(
        s"""${name}_bucket$labelsWithInf ${state.count.toDouble} $epochMilli""",
        s"${name}_sum$labels ${state.sum} $epochMilli",
        s"${name}_count$labels ${state.count.toDouble} $epochMilli",
        s"${name}_min$labels ${state.min} $epochMilli",
        s"${name}_max$labels ${state.max} $epochMilli",
      ),
    )
  })
}
