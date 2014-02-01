package pl.project13.scala.akka.raft.cluster

import akka.actor.{RootActorPath, Actor}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import pl.project13.scala.akka.raft.RaftActor
import pl.project13.scala.akka.raft.protocol.InternalProtocol.MemberAdded
import akka.util.Timeout
import concurrent.duration._

/**
 * Akka cluster ready [[pl.project13.scala.akka.raft.RaftActor]].
 */
trait ClusterRaftActor extends RaftActor {

  val cluster = Cluster(context.system)

  import context.dispatcher
  
  implicit val timeout = Timeout(2.seconds)

  abstract override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  abstract override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = msg match {
    case MemberUp(member) =>
        log.info("Member is Up: {}, adding to Raft cluster..", member.address)
        val memberSelection = context.actorSelection(RootActorPath(member.address))
        memberSelection.resolveOne() map { self ! MemberAdded(_) }
      
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)

    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)

    case _: MemberEvent => // ignore

    case _ =>
      // everything else just push to the RaftActor's receive
      super.aroundReceive(receive, msg)
  }
}