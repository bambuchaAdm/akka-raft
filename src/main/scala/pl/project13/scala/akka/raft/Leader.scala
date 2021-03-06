package pl.project13.scala.akka.raft

import akka.actor.ActorRef

import pl.project13.scala.akka.raft.cluster.ClusterProtocol.{IAmInState, AskForState}

import model._
import protocol._
import scala.annotation.tailrec

private[raft] trait Leader {
  this: RaftActor =>
  
  protected def raftConfig: RaftConfiguration

  private val HeartbeatTimerName = "heartbeat-timer"

  val leaderBehavior: StateFunction = {
    case Event(ElectedAsLeader(), m: LeaderMeta) =>
      log.info(bold(s"Became leader for ${m.currentTerm}"))
      initializeLeaderState(m.config.members)
      startHeartbeat(m)
      stay()

    case Event(SendHeartbeat, m: LeaderMeta) =>
      sendHeartbeat(m)
      stay()

    // already won election, but votes may still be coming in
    case Event(_: ElectionMessage, _) =>
      stay()

    // client request
    case Event(ClientMessage(client, cmd: Command), m: LeaderMeta) =>
      log.info(s"Appending command: [${bold(cmd)}] from $client to replicated log...")

      val entry = Entry(cmd, m.currentTerm, replicatedLog.nextIndex, Some(client))

      log.info(s"adding to log: $entry")
      replicatedLog += entry
      matchIndex.put(self, entry.index)
      log.info(s"log status = $replicatedLog")

      val meta = maybeUpdateConfiguration(m, entry.command)
      replicateLog(meta)

      if (meta.config.isPartOfNewConfiguration(self))
        stay() using meta
      else
        goto(Follower) using meta.forFollower // or maybe goto something else?

    case Event(AppendRejected(term, index), m: LeaderMeta) if term > m.currentTerm =>
      stopHeartbeat()
      stepDown(m) // since there seems to be another leader!

    case Event(msg: AppendRejected, m: LeaderMeta) =>
      registerAppendRejected(follower(), msg, m)

    case Event(msg: AppendSuccessful, m: LeaderMeta) =>
      registerAppendSuccessful(follower(), msg, m)

    case Event(RequestConfiguration, m: LeaderMeta) =>
      sender() ! ChangeConfiguration(m.config)
      stay()

    case Event(AskForState, _) =>
      sender() ! IAmInState(Leader)
      stay()
  }

  def initializeLeaderState(members: Set[Member]) {
    log.info(s"Preparing nextIndex and matchIndex table for followers, init all to: replicatedLog.lastIndex = ${replicatedLog.lastIndex}")
    nextIndex = LogIndexMap.initialize(members, replicatedLog.lastIndex)
    matchIndex = LogIndexMap.initialize(members, -1)
  }

  def sendEntries(follower: Member, m: LeaderMeta) {
    follower ! AppendEntries(
      m.currentTerm,
      replicatedLog,
      fromIndex = nextIndex.valueFor(follower),
      leaderCommitId = replicatedLog.committedIndex
    )
  }

  def stopHeartbeat() {
    cancelTimer(HeartbeatTimerName)
  }

  def startHeartbeat(m: LeaderMeta) {
//  def startHeartbeat(currentTerm: Term, members: Set[ActorRef]) {
    sendHeartbeat(m)
    log.info(s"Starting hearbeat, with interval: $heartbeatInterval")
    setTimer(HeartbeatTimerName, SendHeartbeat, heartbeatInterval, repeat = true)
  }

  /** heartbeat is implemented as basically sending AppendEntry messages */
  def sendHeartbeat(m: LeaderMeta) {
    replicateLog(m)
  }

  def replicateLog(m: LeaderMeta) {
    m.others foreach { member =>
      // todo remove me
      log.info(s"""sending : ${AppendEntries(
              m.currentTerm,
              replicatedLog,
              fromIndex = nextIndex.valueFor(member),
              leaderCommitId = replicatedLog.committedIndex
            )} to $member""")

      member ! AppendEntries(
        m.currentTerm,
        replicatedLog,
        fromIndex = nextIndex.valueFor(member),
        leaderCommitId = replicatedLog.committedIndex
      )
    }
  }

  def registerAppendRejected(member: ActorRef, msg: AppendRejected, m: LeaderMeta) = {
    val AppendRejected(followerTerm, followerIndex) = msg

    log.info(s"Follower ${follower()} rejected write: $followerTerm @ $followerIndex, back out the first index in this term and retry")
//    log.info(s"Leader log state: " + replicatedLog.entries)

    nextIndex.putIfSmaller(follower(), followerIndex)

//    todo think if we send here or keep in heartbeat
    sendEntries(follower(), m)

    stay()
  }

  def registerAppendSuccessful(member: ActorRef, msg: AppendSuccessful, m: LeaderMeta) = {
    val AppendSuccessful(followerTerm, followerIndex) = msg

    log.info(s"Follower ${follower()} took write in term: $followerTerm, index: ${nextIndex.valueFor(follower())}")

    // update our tables for this member
    nextIndex.put(follower(), followerIndex)
    matchIndex.putIfGreater(follower(), nextIndex.valueFor(follower()))

    replicatedLog = maybeCommitEntry(m, matchIndex, replicatedLog)

    stay()
  }

  def maybeCommitEntry(m: LeaderMeta, matchIndex: LogIndexMap, replicatedLog: ReplicatedLog[Command]): ReplicatedLog[Command] = {
    val indexOnMajority = matchIndex.consensusForIndex(m.config)
    val willCommit = indexOnMajority > replicatedLog.committedIndex
    log.info(s"Consensus for persisted index: $indexOnMajority. (Comitted index: ${replicatedLog.committedIndex}, will commit now: $willCommit)")

    if (willCommit) {
      val entries = replicatedLog.between(replicatedLog.committedIndex, indexOnMajority)
      
      entries foreach { entry =>
        handleCommitIfSpecialEntry.applyOrElse(entry, default = handleNormalEntry)

        if (raftConfig.publishTestingEvents) {
          log.info(s"Publishing EntryCommitted(${entry.index})")
          context.system.eventStream.publish(EntryCommitted(entry.index))
        }
      }

      replicatedLog.commit(indexOnMajority)
    } else {
      replicatedLog
    }
  }

  /**
   * Used for handling special messages, such as ''new Configuration'' or a ''Snapshot entry'' being comitted.
   *
   * Note that special log entries will NOT be propagated to the client state machine.
   */
  private val handleCommitIfSpecialEntry: PartialFunction[Entry[Command], Unit] = {
    case Entry(jointConfig: JointConsensusClusterConfiguration, _, _, _) =>
      self ! ClientMessage(self, jointConfig.transitionToStable) // will cause comitting of only "new" config

    case Entry(stableConfig: StableClusterConfiguration, _, _, _) =>
      // simply ignore, once this message is in our log we started using the new configuration anyway,
      // there's no need to apply this message onto the client state machine.
  }

  private val handleNormalEntry: PartialFunction[Any, Unit] = {
    case entry: Entry[Command] =>
      log.info(s"Committing log at index: ${entry.index}; Applying command: ${entry.command}, will send result to client: ${entry.client}")
      val result = apply(entry.command)
      entry.client foreach { _ ! result }
  }

  /**
   * Configurations must be used by each node right away when they get appended to their logs (doesn't matter if not committed).
   * This method updates the Meta object if a configuration change is discovered.
   */
  def maybeUpdateConfiguration(meta: LeaderMeta, entry: Command): LeaderMeta = entry match {
    case newConfig: ClusterConfiguration if newConfig.isNewerThan(meta.config) =>
      log.info("Appended new configuration, will start using it now: {}", newConfig)
      meta.withConfig(newConfig)

    case _ =>
      meta
  }

  // todo remove me
  private def bold(msg: Any): String = Console.BOLD + msg.toString + Console.RESET

}