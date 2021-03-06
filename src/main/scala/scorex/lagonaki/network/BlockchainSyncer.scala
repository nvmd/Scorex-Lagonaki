package scorex.lagonaki.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM}
import scorex.block.Block
import scorex.lagonaki.network.BlockchainSyncer._
import scorex.lagonaki.network.message.{BlockMessage, GetSignaturesMessage}
import scorex.lagonaki.server.LagonakiApplication
import scorex.settings.Settings

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}


case class NewBlock(block: Block, sender: Option[InetSocketAddress])

class BlockchainSyncer(application: LagonakiApplication, networkController: ActorRef, settings: Settings)
  extends FSM[Status, Unit] {

  private val stateTimeout = 1.second
  val blockGenerationDelay = settings.blockGenerationDelay

  startWith(Offline, Unit)

  context.system.scheduler.schedule(500.millis, 1.second)(networkController ! GetMaxChainScore)

  when(Offline, stateTimeout) {
    case Event(StateTimeout, _) =>
      stay()

    case Event(Unit, _) =>
      log.info("Initializing")
      stay()
  }

  when(Syncing) {
    case Event(MaxChainScore(scoreOpt), _) =>
      processMaxScore(
        scoreOpt,
        onMax = () => {
          val sigs = application.blockchainImpl.lastSignatures(application.settings.MaxBlocksChunks)
          val msg = GetSignaturesMessage(sigs)
          networkController ! NetworkController.SendMessageToBestPeer(msg)
          stay()
        }
      )

    case Event(NewBlock(block, remoteOpt), _) =>
      assert(remoteOpt.isDefined, "Local generation attempt while syncing")
      processNewBlock(block, remoteOpt)
      stay()
  }

  when(Generating) {
    case Event(NewBlock(block, remoteOpt), _) =>
      processNewBlock(block, remoteOpt)
      stay()

    case Event(MaxChainScore(scoreOpt), _) =>
      processMaxScore(
        scoreOpt,
        onNone = () => {
          tryToGenerateABlock()
          context.system.scheduler.scheduleOnce(blockGenerationDelay, networkController, GetMaxChainScore)
          stay()
        },
        onLocal = () => {
          tryToGenerateABlock()
          networkController ! GetMaxChainScore
          context.system.scheduler.scheduleOnce(blockGenerationDelay, networkController, GetMaxChainScore)
          stay()
        }
      )
  }

  //common logic for all the states
  whenUnhandled {
    case Event(MaxChainScore(scoreOpt), _) =>
      processMaxScore(scoreOpt)

    case Event(GetStatus, _) =>
      sender() ! super.stateName.name
      stay()

    case Event(e, s) =>
      log.warning(s"received unhandled request {$e} in state {$stateName}/{$s}")
      stay()
  }

  initialize()

  def processMaxScore(
                       scoreOpt: Option[BigInt],
                       onLocal: () => State = () => goto(Generating),
                       onMax: () => State = () => goto(Syncing),
                       onNone: () => State = () =>
                         if (application.settings.offlineGeneration) goto(Generating).using(Unit) else goto(Offline)
                     ): State = scoreOpt match {
    case Some(maxScore) =>
      val localScore = application.blockchainImpl.score()
      log.info(s"maxScore: $maxScore, localScore: $localScore")
      if (maxScore > localScore) onMax() else onLocal()
    case None =>
      onNone()
  }

  def processNewBlock(block: Block, remoteOpt: Option[InetSocketAddress]) = {
    val fromStr = remoteOpt.map(_.toString).getOrElse("local")
    if (block.isValid) {
      application.storedState.processBlock(block)
      application.blockchainImpl.appendBlock(block)
      log.info(s"New block: $block from $fromStr. New size: ${application.blockchainImpl.height()}, " +
        s"score: ${application.blockchainImpl.score()}, timestamp: ${block.timestampField.value}")

      block.transactionModule.clearFromUnconfirmed(block.transactionDataField.value)
      val height = application.blockchainImpl.height()

      //broadcast block only if it is generated locally
      if (remoteOpt.isEmpty) {
        networkController ! NetworkController.BroadcastMessage(BlockMessage(height, block))
      }
    } else {
      log.warning(s"Non-valid block: $block from $fromStr")
    }
  }

  def tryToGenerateABlock() = {
    val consModule = application.consensusModule
    implicit val transModule = application.transactionModule

//    log.info("Trying to generate a new block")
    val accounts = application.wallet.privateKeyAccounts()
    consModule.generateNextBlocks(accounts)(transModule) onComplete {
      case Success(blocks: Seq[Block]) =>
        if (blocks.nonEmpty) {
          val bestBlock = blocks.maxBy(consModule.blockScore)
          self ! NewBlock(bestBlock, None)
        }
      case Failure(ex) => log.error("Failed to generate new block: {}", ex)
      case m => log.error("Unexpected message: {}", m)
    }
  }
}

object BlockchainSyncer {

  sealed trait Status {
    val name: String
  }

  case object Offline extends Status {
    override val name = "offline"
  }

  case object Syncing extends Status {
    override val name = "syncing"
  }

  case object Generating extends Status {
    override val name = "generating"
  }

  case class MaxChainScore(scoreOpt: Option[BigInt])

  case object GetMaxChainScore

  case object GetStatus

}