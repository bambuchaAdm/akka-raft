package pl.project13.scala.akka.raft

package object protocol extends Serializable
  with RaftProtocol
  with InternalProtocol
  with StateMetadata
  with RaftStates
