package com.sky.kafka.topicloader

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.{KillSwitch, KillSwitches}
import cats.syntax.show._
import cats.{Always, Show}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import org.apache.kafka.common.{PartitionInfo, TopicPartition}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object TopicLoader extends LazyLogging {

  /**
    * Consumes the records from the provided topics, passing them through `onRecord`
    */
  def apply[T, Out](config: TopicLoaderConfig,
                    onRecord: ConsumerRecord[String, T] => Future[_],
                    valueDeserializer: Deserializer[T])(
      implicit system: ActorSystem,
      ec: ExecutionContext): Source[Option[Out], _] = {

    def topicPartition(topic: String) = new TopicPartition(topic, _: Int)

    val settings =
      ConsumerSettings(system, new StringDeserializer, valueDeserializer)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val offsetsSource: Source[Map[TopicPartition, LogOffsets], NotUsed] =
      lazySource {
        withStandaloneConsumer(settings) { c =>
          config.topics
            .map { topic =>
              val offsets =
                getOffsets(topicPartition(topic), c.partitionsFor(topic)) _
              val beginningOffsets = offsets(c.beginningOffsets)
              val endOffsets: Map[TopicPartition, Long] =
                config.strategy match {
                  case LoadAll => offsets(c.endOffsets)
                  case LoadCommitted =>
                    c.partitionsFor(topic)
                      .asScala
                      .map { i =>
                        val p = new TopicPartition(topic, i.partition)
                        p -> Option(c.committed(p)).fold(beginningOffsets(p))(
                          _.offset)
                      }
                      .toMap
                }
              beginningOffsets.map {
                case (k, v) => k -> LogOffsets(v, endOffsets(k))
              }
            }
            .toList
            .reduce(_ ++ _)
        }
      }

    def topicDataSource(
        offsets: Map[TopicPartition, LogOffsets]): Source[Option[Out], _] = {
      offsets.foreach {
        case (partition, offset) =>
          logger.info(s"${offset.show} for $partition")
      }

      val relativeOffsets = offsets.values.map(o => o.highest - o.lowest)
      val lowestOffsets = offsets.mapValues(_.lowest)
      val highestOffsets = offsets.mapValues(_.highest - 1)
      val numPartitionsWithRecords = relativeOffsets.count(_ > 0)

      val filterBelowHighestOffset = Flow[ConsumerRecord[String, T]]
        .map(r => r -> highestOffsets(topicPartition(r.topic)(r.partition)))
        .collect {
          case (r, highest) if r.offset == highest =>
            (r, LastRecordForPartition)
          case (r, highest) if r.offset < highest => (r, LessThanHighestOffset)
        }

      def handleRecord(r: (ConsumerRecord[String, T], RecordPosition)) =
        onRecord(r._1).map(_ => r)

      relativeOffsets.sum match {
        case 0 =>
          logger.info("No data to load")
          Source.empty
        case _ =>
          Consumer
            .plainSource(settings,
                         Subscriptions.assignmentWithOffset(lowestOffsets))
            .idleTimeout(config.idleTimeout)
            .via(filterBelowHighestOffset)
            .mapAsync(config.parallelism)(handleRecord)
            .collect {
              case (r, LastRecordForPartition) =>
                logger.info(
                  s"Finished loading data from ${r.topic}-${r.partition}")
                None
            }
            .take(numPartitionsWithRecords)
            .mapMaterializedValue(logResult)
      }
    }

    offsetsSource.flatMapConcat(topicDataSource)
  }

  private def logResult(control: Consumer.Control)(
      implicit ec: ExecutionContext) = {
    control.isShutdown.onComplete {
      case Success(_) => logger.info("Successfully loaded data")
      case Failure(t) => logger.error("Error occurred while loading data", t)
    }
    control
  }

  private def lazySource[T](t: => T): Source[T, NotUsed] =
    Source.single(Always(t)).map(_.value)

  private def withStandaloneConsumer[T, U](
      settings: ConsumerSettings[String, T])(f: Consumer[String, T] => U): U = {
    val consumer = settings.createKafkaConsumer()
    try {
      f(consumer)
    } finally {
      consumer.close()
    }
  }

  private def getOffsets(topicPartition: Int => TopicPartition,
                         partitions: JList[PartitionInfo])(
      f: JList[TopicPartition] => JMap[TopicPartition, JLong])
    : Map[TopicPartition, Long] =
    f(partitions.asScala.map(p => topicPartition(p.partition)).asJava).asScala.toMap
      .mapValues(_.longValue)

  private case class LogOffsets(lowest: Long, highest: Long)

  private implicit val showLogOffsets: Show[LogOffsets] = o =>
    s"LogOffsets(lowest = ${o.lowest}, highest = ${o.highest})"

  implicit class RunAfterSource[T1, M1](val s2: Source[T1, M1]) extends AnyVal {

    /**
      * Prepend s1 source to s2 source returning the source that will run them in order, after which the s1 Source
      * ends and its result is discarded.
      */
    def runAfter[T2, M2](s1: Source[T2, M2]): Source[T1, KillSwitch] =
      s2.map(collect(_))
        .prependLazily(s1.map(_ => Process))
        .collect { case Collect(t) => t }
        .viaMat(KillSwitches.single)(Keep.right)

    def prependLazily[M2](s1: => Source[T1, M2]): Source[T1, NotUsed] =
      Source(List(() => s1, () => s2)).flatMapConcat(_.apply)
  }

  private sealed trait RecordPosition
  private case object LessThanHighestOffset extends RecordPosition
  private case object LastRecordForPartition extends RecordPosition

  private sealed trait Collectable[+T]
  private case object Process extends Collectable[Nothing]
  private final case class Collect[T](elem: T) extends Collectable[T]
  private def collect[T](t: T): Collectable[T] = Collect(t)
}