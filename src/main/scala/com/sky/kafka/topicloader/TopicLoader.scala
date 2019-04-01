package com.sky.kafka.topicloader

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import cats.data.NonEmptyList
import cats.syntax.option._
import cats.syntax.show._
import cats.{Always, Show}
import com.typesafe.scalalogging.LazyLogging
import eu.timepit.refined.pureconfig._
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, Deserializer, StringDeserializer}
import org.apache.kafka.common.TopicPartition
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object TopicLoader extends LazyLogging {

  /**
    * Consumes the records from the provided topics, passing them through `onRecord`.
    *
    * @param strategy
    * All records on a topic can be consumed using the `LoadAll` strategy.
    * All records up to the last committed offset of the configured `group.id` (provided in your application.conf)
    * can be consumed using the `LoadCommitted` strategy.
    *
    */
  def fromTopics[T](
      strategy: LoadTopicStrategy,
      topics: NonEmptyList[String],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {

    val partitionsFromTopics: Consumer[String, _] => List[TopicPartition] = c =>
      topics.toList.flatMap(t => c.partitionsFor(t).asScala.map(p => new TopicPartition(t, p.partition)))

    TopicLoader(strategy, partitionsFromTopics, onRecord, valueDeserializer)
  }

  /**
    * Consumes the records from the provided partitions, passing them through `onRecord`.
    *
    * @param strategy
    * All records on a partition can be consumed using the `LoadAll` strategy.
    * All records up to the last committed offset of the configured `group.id` (provided in your application.conf)
    * can be consumed using the `LoadCommitted` strategy.
    *
    */
  def fromPartitions[T](
      strategy: LoadTopicStrategy,
      partitions: NonEmptyList[TopicPartition],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] =
    TopicLoader(strategy, _ => partitions.toList, onRecord, valueDeserializer)

  private def apply[T](
      strategy: LoadTopicStrategy,
      partitions: Consumer[String, _] => List[TopicPartition],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {

    import system.dispatcher

    val config = loadConfigOrThrow[Config](system.settings.config).topicLoader

    val settings =
      ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    def earliestOffsets(consumer: Consumer[String, Array[Byte]],
                        beginningOffsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] =
      beginningOffsets.keys.map(p => p -> Option(consumer.committed(p)).fold(beginningOffsets(p))(_.offset)).toMap

    def topicDataSource(offsets: Map[TopicPartition, LogOffsets]): Source[Map[TopicPartition, LogOffsets], _] = {
      offsets.foreach { case (partition, offset) => logger.info(s"${offset.show} for $partition") }

      val nonEmptyOffsets = offsets.filter { case (_, o) => o.highest > o.lowest }
      val lowestOffsets   = nonEmptyOffsets.mapValues(_.lowest)
      val allHighestOffsets: HighestOffsetsWithRecord[T] =
        HighestOffsetsWithRecord[T](nonEmptyOffsets.mapValues(_.highest - 1))

      val filterBelowHighestOffset =
        Flow[ConsumerRecord[String, T]]
          .scan(allHighestOffsets)(emitRecordRemovingConsumedPartition)
          .takeWhile(_.partitionOffsets.nonEmpty, inclusive = true)
          .collect { case WithRecord(r) => r }

      def handleRecord(r: ConsumerRecord[String, T]) = onRecord(r).map(_ => r)

      nonEmptyOffsets.size match {
        case 0 =>
          logger.info(s"No data to load from ${offsets.keys.show}, will return current offsets")
          Source.single(offsets)
        case _ =>
          Consumer
            .plainSource(settings, Subscriptions.assignmentWithOffset(lowestOffsets))
            .buffer(config.bufferSize.value, OverflowStrategy.backpressure)
            .idleTimeout(config.idleTimeout)
            .map(deserializeValue(_, valueDeserializer))
            .via(filterBelowHighestOffset)
            .mapAsync(config.parallelism.value)(handleRecord)
            .fold(offsets) { case (offset, _) => offset }
            .mapMaterializedValue(logResult(_, offsets.keys))
      }
    }

    val offsetsSourceFor: Source[Map[TopicPartition, LogOffsets], NotUsed] =
      lazySource {
        withStandaloneConsumer(settings) { c =>
          val requiredPartitions = partitions(c)
          val offsets            = getOffsets(requiredPartitions) _
          val beginningOffsets   = offsets(c.beginningOffsets)
          val partitionToLong = strategy match {
            case LoadAll       => offsets(c.endOffsets)
            case LoadCommitted => earliestOffsets(c, beginningOffsets)
          }
          val endOffsets = partitionToLong
          beginningOffsets.map {
            case (k, v) => k -> LogOffsets(v, endOffsets(k))
          }
        }
      }

    offsetsSourceFor.flatMapConcat(topicDataSource).map(_.mapValues(_.highest))
  }

  private def deserializeValue[T](cr: ConsumerRecord[String, Array[Byte]],
                                  valueDeserializer: Deserializer[T]): ConsumerRecord[String, T] =
    new ConsumerRecord[String, T](
      cr.topic,
      cr.partition,
      cr.offset,
      cr.timestamp,
      cr.timestampType,
      ConsumerRecord.NULL_CHECKSUM.toLong,
      cr.serializedKeySize,
      cr.serializedValueSize,
      cr.key,
      valueDeserializer.deserialize(cr.topic, cr.value),
      cr.headers
    )

  private def emitRecordRemovingConsumedPartition[T](t: HighestOffsetsWithRecord[T],
                                                     r: ConsumerRecord[String, T]): HighestOffsetsWithRecord[T] = {
    val partitionHighest: Option[Long] = t.partitionOffsets.get(new TopicPartition(r.topic, r.partition))
    val reachedHighest: Option[TopicPartition] = for {
      offset  <- partitionHighest
      highest <- if (r.offset >= offset) new TopicPartition(r.topic, r.partition).some else None
      _       = logger.info(s"Finished loading data from ${r.topic}-${r.partition}")
    } yield highest

    val updatedHighests = reachedHighest.fold(t.partitionOffsets)(highest => t.partitionOffsets - highest)
    val emittableRecord = partitionHighest.collect { case h if r.offset() <= h => r }
    HighestOffsetsWithRecord(updatedHighests, emittableRecord)
  }

  private def logResult(control: Consumer.Control, tps: Iterable[TopicPartition])(implicit ec: ExecutionContext) = {
    control.isShutdown.onComplete {
      case Success(_) => logger.info(s"Successfully loaded data from ${tps.show}")
      case Failure(t) => logger.error(s"Error occurred while loading data from ${tps.show}", t)
    }
    control
  }

  private def lazySource[T](t: => T): Source[T, NotUsed] =
    Source.single(Always(t)).map(_.value)

  private def withStandaloneConsumer[T, U](settings: ConsumerSettings[String, T])(f: Consumer[String, T] => U): U = {
    val consumer = settings.createKafkaConsumer()
    try {
      f(consumer)
    } finally {
      consumer.close()
    }
  }

  private def getOffsets(partitions: List[TopicPartition])(
      f: JList[TopicPartition] => JMap[TopicPartition, JLong]): Map[TopicPartition, Long] =
    f(partitions.asJava).asScala.toMap.mapValues(_.longValue)

  private case class LogOffsets(lowest: Long, highest: Long)

  private case class HighestOffsetsWithRecord[T](partitionOffsets: Map[TopicPartition, Long],
                                                 consumerRecord: Option[ConsumerRecord[String, T]] =
                                                   none[ConsumerRecord[String, T]])

  private object WithRecord {
    def unapply[T](h: HighestOffsetsWithRecord[T]): Option[ConsumerRecord[String, T]] = h.consumerRecord
  }

  private implicit val showLogOffsets: Show[LogOffsets] = o =>
    s"LogOffsets(lowest = ${o.lowest}, highest = ${o.highest})"

  private implicit val showOffsets: Show[Iterable[TopicPartition]] = _.map(_.topic).mkString(",")
}
