/*
 * Copyright 2021 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vancats.raft

import cats.Monad
import cats.effect.Temporal
import cats.effect.kernel.{Concurrent, Deferred}
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._
import com.google.protobuf.ByteString
import fs2.concurrent.Channel
import fs2.{Pipe, Stream}
import io.grpc.Metadata
import vancats.raft.Role.{Candidate, Follower, Leader}
import vancats.raft.Server.Command
import vancats.util.Pacemaker

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration}

final case class State private[raft] (
    role: Role = Follower(),
    currentTerm: Int = 0,
    votedFor: Option[Int] = None,
    log: Seq[LogEntry] = Seq.empty,
    commitIndex: Int = 0, // All entries strictly below this index have been committed
    lastApplied: Int = 0 // All entries strictly below this index have been applied
) {

  private[raft] def becomeFollower(leaderId: Option[Int]): State =
    copy(role = Follower(leaderId), votedFor = None)

  private[raft] def updateTerm[F[_]: Monad](pacemaker: Pacemaker[F])(
      command: Command[F]): F[State] =
    command.term match {
      case Some(term) if term > currentTerm =>
        (if (role.isLeader) pacemaker.start else ().pure)
          .as(becomeFollower(command.leaderId).copy(currentTerm = term))
      case _ => (this: State).pure
    }

  private[raft] def tryIncrementCommitIndex(serverCount: Int): State =
    role match {
      case Leader(_, matchIndex) =>
        @tailrec def loop(N: Int): Option[Int] =
          if (N > commitIndex) {
            if (log(N - 1).term == currentTerm && matchIndex
                .values
                .count(_ >= N) > serverCount / 2)
              Some(N)
            else
              loop(N - 1)
          } else None

        loop(log.size).fold(this)(i => copy(commitIndex = i))
      case _ => this
    }

  private[raft] def commit[F[_]: Concurrent](commit: Pipe[F, ByteString, Nothing]): F[State] =
    if (commitIndex > lastApplied)
      Stream
        .emits(log.slice(lastApplied, commitIndex))
        .map(_.command)
        .through(commit)
        .compile
        .drain
        .start
        .as(copy(lastApplied = commitIndex min log.size))
    else
      (this: State).pure

}

sealed abstract class Role {
  def isFollower = false
  def isLeader = false
}
private[raft] object Role {
  final case class Follower(leader: Option[Int] = None) extends Role {
    override def isFollower: Boolean = true
  }
  final case class Candidate(votes: Int = 1) extends Role
  final case class Leader(
      nextIndex: Map[Int, Int], // Index of next entry to send
      matchIndex: Map[
        Int,
        Int
      ] // All entries strictly below this index are known to be replicated
  ) extends Role {
    override def isLeader: Boolean = true
  }
}

sealed abstract private[raft] class Server[F[_]] {
  def command: Pipe[F, Command[F], Nothing]
  def command1(command: Command[F]): F[Unit]
  def state: Stream[F, State]
}

private[raft] object Server {

  sealed abstract class Command[F[_]] {
    def term: Option[Int] = None
    def leaderId: Option[Int] = None
  }

  final case class ServiceClient[F[_]](command: ByteString) extends Command[F]

  final case class RequestVote[F[_]](
      request: RequestVoteRequest,
      reply: Deferred[F, RequestVoteReply])
      extends Command[F] {
    override def term = Some(request.term)
  }

  final case class CastBallot[F[_]](reply: RequestVoteReply) extends Command[F] {
    override def term = Some(reply.term)
  }

  final case class AppendEntries[F[_]](
      request: AppendEntriesRequest,
      reply: Deferred[F, AppendEntriesReply])
      extends Command[F] {
    override def term = Some(request.term)
    override def leaderId = Some(request.leaderId)
  }

  final case class ProcessAppendEntriesReply[F[_]](
      peerId: Int,
      targetNextIndex: Int,
      reply: AppendEntriesReply)
      extends Command[F]

  final case class HeartBeat[F[_]]() extends Command[F]
  final case class ElectionTimeout[F[_]]() extends Command[F]

  def apply[F[_]: Temporal: Random](
      id: Int,
      serverCount: Int,
      heartRate: FiniteDuration,
      electionTimeout: FiniteDuration,
      electionTimeoutNoiseFactor: Double,
      peers: Map[Int, RaftFs2Grpc[F, Metadata]],
      commit: Pipe[F, ByteString, Nothing]
  ): F[Server[F]] = for {

    channel <- Channel.unbounded[F, Command[F]]
    electionPacemaker <- Pacemaker(electionTimeout)
    _ <- electionPacemaker.start
    fsm = channel
      .stream
      .evalScan(State()) { (server, command) =>
        server.updateTerm(electionPacemaker)(command).flatMap {
          case state @ State(role, currentTerm, votedFor, log, commitIndex, _) =>
            def sendAppendEntries(peerId: Int, peer: RaftFs2Grpc[F, Metadata], nextIndex: Int)
                : F[Unit] =
              peer
                .appendEntries(
                  AppendEntriesRequest(
                    currentTerm,
                    id,
                    nextIndex,
                    log.lastOption.fold(currentTerm)(_.term),
                    log.drop(nextIndex),
                    commitIndex
                  ),
                  new Metadata)
                .flatMap(reply =>
                  channel.send(ProcessAppendEntriesReply(peerId, log.size, reply)))
                .start
                .void

            command match {
              case ServiceClient(command) =>
                role match {
                  case Leader(_, _) =>
                    state.copy(log = log :+ LogEntry(currentTerm, command)).pure
                  case Follower(Some(leaderId)) =>
                    peers(leaderId)
                      .serviceClient(ServiceClientRequest(command), new Metadata)
                      .start
                      .as(state)
                  case _ =>
                    channel.send(ServiceClient(command)).delayBy(heartRate).start.as(state)
                }

              case RequestVote(
                    RequestVoteRequest(term, candidateId, nextLogIndex, lastLogTerm),
                    reply) =>
                val grantVote =
                  term >= currentTerm && votedFor.forall(_ == candidateId) && lastLogTerm >= log
                    .lastOption
                    .fold(currentTerm)(_.term) && nextLogIndex >= log.size
                reply
                  .complete(RequestVoteReply(currentTerm, grantVote))
                  .as(state.copy(votedFor = if (grantVote) Some(candidateId) else votedFor))

              case CastBallot(RequestVoteReply(_, voteGranted)) =>
                role match {
                  case Candidate(votes) if voteGranted && votes + 1 > serverCount / 2 =>
                    electionPacemaker.stop *>
                      channel.send(HeartBeat[F]()).start as
                      state.copy(
                        role = Leader(
                          Map.empty.withDefaultValue(log.size),
                          Map.empty.withDefaultValue(0)
                        )
                      )
                  case Candidate(votes) if voteGranted =>
                    state.copy(role = Candidate(votes + 1)).pure
                  case _ =>
                    state.pure
                }

              case AppendEntries(
                    AppendEntriesRequest(
                      term,
                      leaderId,
                      logIndex,
                      prevLogTerm,
                      entries,
                      leaderCommit),
                    reply) =>
                for {
                  _ <-
                    if (term >= currentTerm)
                      electionPacemaker.reset
                    else
                      Temporal[F].unit
                  accept = term >= currentTerm &
                    logIndex <= log.size &
                    log.lift(logIndex - 1).forall(_.term == prevLogTerm)
                  _ <- reply.complete(AppendEntriesReply(currentTerm, accept))
                  server <-
                    if (accept) {
                      val (unchanged, unverified) = log.splitAt(logIndex)
                      val verified = unverified
                        .lazyZip(entries)
                        .map { (entry, shouldMatch) =>
                          if (entry.term == shouldMatch.term)
                            entry :: Nil
                          else
                            Nil
                        }
                        .takeWhile(_.nonEmpty)
                        .flatten
                      val updatedLog = unchanged ++ verified
                      state
                        .becomeFollower(Some(leaderId))
                        .copy(
                          commitIndex =
                            if (leaderCommit > commitIndex) leaderCommit min updatedLog.size
                            else commitIndex
                        )
                        .commit(commit)
                    } else if (term >= currentTerm)
                      state.becomeFollower(Some(leaderId)).pure
                    else state.pure
                } yield server

              case ProcessAppendEntriesReply(
                    peerId,
                    targetNextIndex,
                    AppendEntriesReply(_, success)) =>
                role match {
                  case Leader(nextIndex, matchIndex) if success =>
                    state
                      .copy(role = Leader(
                        nextIndex.updated(peerId, targetNextIndex),
                        matchIndex.updated(peerId, targetNextIndex)))
                      .tryIncrementCommitIndex(serverCount)
                      .commit(commit)
                  case Leader(nextIndex, matchIndex) if !success =>
                    sendAppendEntries(peerId, peers(peerId), nextIndex(peerId) - 1) as
                      state.copy(role =
                        Leader(nextIndex.updatedWith(peerId)(_.map(_ - 1)), matchIndex))
                  case _ => state.pure
                }

              case HeartBeat() =>
                role match {
                  case Leader(nextIndex, _) =>
                    channel.send(HeartBeat[F]()).delayBy(heartRate).start *>
                      peers.toList.traverse {
                        case (peerId, peer) =>
                          sendAppendEntries(peerId, peer, nextIndex(peerId))
                      } as state
                  case _ => state.pure
                }

              case ElectionTimeout() =>
                peers.values.toList.traverse { peer =>
                  peer
                    .requestVote(
                      RequestVoteRequest(
                        currentTerm + 1,
                        id,
                        log.size,
                        log.lastOption.fold(currentTerm + 1)(_.term)),
                      new Metadata())
                    .flatMap(reply => channel.send(CastBallot(reply)))
                    .start
                } as state.copy(
                  role = Candidate(),
                  currentTerm = currentTerm + 1,
                  votedFor = Some(id)
                )

            }
        }
      }
      .concurrently(
        electionPacemaker
          .stream
          .evalTap(_ =>
            Random[F]
              .betweenDouble(0.0, electionTimeoutNoiseFactor)
              .flatMap(x =>
                Temporal[F].sleep((electionTimeout * x) match {
                  case duration: FiniteDuration => duration
                  case _ => Duration.Zero
                })))
          .as(ElectionTimeout[F]())
          .evalMap(channel.send)
      )

  } yield new Server[F] {
    override def command: Pipe[F, Command[F], Nothing] =
      _.evalMap(command1).drain

    override def command1(command: Command[F]): F[Unit] =
      channel.send(command).void

    override val state: Stream[F, State] = fsm
  }

}
