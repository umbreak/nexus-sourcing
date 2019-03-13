package ch.epfl.bluebrain.nexus.sourcing.persistence

import _root_.akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import _root_.akka.persistence.cassandra.session.scaladsl.CassandraSession
import _root_.akka.persistence.query.{Offset, Sequence, TimeBasedUUID}
import _root_.akka.stream.scaladsl.Source
import cats.Monad
import cats.effect.LiftIO
import cats.implicits._
import io.circe.parser._
import io.circe.{Decoder, Encoder}
import monix.eval.Task

trait IndexFailuresStorage[F[_]] {

  /**
    * Record a specific event against a index failures log identifier.
    *
    * @param identifier    the unique identifier for the index failures log
    * @param persistenceId the persistenceId to record
    * @param offset        the offset to record
    * @param event         the event to be recorded
    * @tparam T the generic type of the ''event''s
    */
  def storeEvent[T](identifier: String, persistenceId: String, offset: Offset, event: T)(
      implicit E: Encoder[T]): F[Unit]

  /**
    * Retrieve the events for the provided index failures log identifier.
    *
    * @param identifier the unique identifier for the skipped log
    * @tparam T the generic type of the returned ''event''s
    * @return a list of the failed events on this identifier
    */
  def fetchEvents[T](identifier: String)(implicit D: Decoder[T]): Source[T, _]
}

final class CassandraIndexFailuresStorage[F[_]: LiftIO](session: CassandraSession, keyspace: String, table: String)(
    implicit F: Monad[F])
    extends IndexFailuresStorage[F]
    with Extension
    with ProjectionProgressCodec {

  override def storeEvent[T](identifier: String, persistenceId: String, offset: Offset, event: T)(
      implicit E: Encoder[T]): F[Unit] = {
    val stmt =
      s"insert into $keyspace.$table (identifier, persistenceId, offset, event) VALUES (?, ?, ?, ?) IF NOT EXISTS"
    liftIO(session.executeWrite(stmt, identifier, persistenceId, toValue(offset), E(event).noSpaces)) *> F.unit
  }

  override def fetchEvents[T](identifier: String)(implicit D: Decoder[T]): Source[T, _] = {
    val stmt = s"select event from $keyspace.$table where identifier = ? ALLOW FILTERING"
    session
      .select(stmt, identifier)
      .map(row => decode[T](row.getString("event")))
      .collect { case Right(evt) => evt }
  }

  private def toValue(offset: Offset): java.lang.Long = offset match {
    case o: Sequence      => o.value
    case o: TimeBasedUUID => o.value.timestamp()
  }
}

object IndexFailuresStorage
    extends ExtensionId[CassandraIndexFailuresStorage[Task]]
    with ExtensionIdProvider
    with CassandraStorage {

  override def lookup(): ExtensionId[_ <: Extension] = IndexFailuresStorage

  override def createExtension(system: ExtendedActorSystem): CassandraIndexFailuresStorage[Task] = {
    val (session, keyspace, table) =
      createSession(
        "index-failures",
        "identifier varchar, persistenceId text, offset bigint, event text, PRIMARY KEY (identifier, persistenceId, offset)",
        system)
    new CassandraIndexFailuresStorage[Task](session, keyspace, table)
  }

}
