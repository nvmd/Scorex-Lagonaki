package scorex.transaction.state.database.blockchain

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.account.PrivateKeyAccount
import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.consensus.nxt.{NxtLikeConsensusBlockData, NxtLikeConsensusModule}
import scorex.lagonaki.TestingCommons
import scorex.transaction.{PaymentTransaction, SimpleTransactionModule, Transaction}

import scala.util.Random
import scorex.utils._

class BlockTreeSpecification extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers
with TestingCommons {

  implicit val consensusModule = new NxtLikeConsensusModule()
  implicit val transactionModule = new SimpleTransactionModule()
  val reference = Array.fill(Block.BlockIdLength)(Random.nextInt(100).toByte)
  val gen = new PrivateKeyAccount(reference)
  val genesis = Block.genesis()

  testTree(new StoredBlockTree(None), "Memory")
  testTree(new StoredBlockTree(Some("/tmp/scorex/test")), "File")

  def testTree(blockTree: StoredBlockTree, prefix: String): Unit = {

    var lastBlockId: BlockId = genesis.uniqueId

    def genBlock(bt: Long, gs: Array[Byte], seed: Array[Byte], parentId: Option[BlockId] = None)
                (implicit consensusModule: NxtLikeConsensusModule, transactionModule: SimpleTransactionModule): Block = {

      val reference = parentId.getOrElse(lastBlockId)

      val sender = new PrivateKeyAccount(seed)
      val tx: Transaction = PaymentTransaction(sender, gen, 5, 1000, System.currentTimeMillis() - 5000)

      val tbd = Seq(tx)
      val cbd = new NxtLikeConsensusBlockData {
        override val generationSignature: Array[Byte] = gs
        override val baseTarget: Long = math.max(math.abs(bt), 1)
      }

      val version = 1: Byte
      val timestamp = System.currentTimeMillis()

      val block = Block.buildAndSign(version, timestamp, reference, cbd, tbd, gen)
      lastBlockId = block.uniqueId
      block
    }

    val blockGen: Gen[Block] = for {
      gb <- Arbitrary.arbitrary[Long]
      gs <- Arbitrary.arbitrary[Array[Byte]]
      seed <- Arbitrary.arbitrary[Array[Byte]]
    } yield genBlock(gb, gs, seed)

    property(s"$prefix: Add genesis") {
      blockTree.height() shouldBe 0
      blockTree.appendBlock(genesis).isSuccess shouldBe true
      blockTree.height() shouldBe 1
    }

    property(s"$prefix: Add linear blocks in chain") {
      blockTree.height() shouldBe 1

      forAll(blockGen) { (block: Block) =>
        val prevH = blockTree.height()
        val prevS = blockTree.score()
        val prevB = blockTree.lastBlock

        blockTree.appendBlock(block).isSuccess shouldBe true

        blockTree.height() shouldBe prevH + 1
        blockTree.score() shouldBe prevS + consensusModule.blockScore(block)
        blockTree.lastBlock.uniqueId should contain theSameElementsAs block.uniqueId
        blockTree.parent(block).get.uniqueId should contain theSameElementsAs prevB.uniqueId
        blockTree.contains(block) shouldBe true
        blockTree.contains(prevB.uniqueId) shouldBe true
      }
    }

    property(s"$prefix: Add non-linear blocks in chain") {
      val branchPoint = blockTree.lastBlock

      //Add block to best chain
      val block = genBlock(20, randomBytes(32), randomBytes(32), Some(branchPoint.uniqueId))
      blockTree.appendBlock(block).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs block.uniqueId

      //Add block with the same score to branch point
      val branchedBlock = genBlock(20, randomBytes(32), randomBytes(32), Some(branchPoint.uniqueId))
      blockTree.appendBlock(branchedBlock).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs block.uniqueId

      //Add block with the better score to branch point
      val bestBlock = genBlock(19, randomBytes(32), randomBytes(32), Some(branchPoint.uniqueId))
      blockTree.appendBlock(bestBlock).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs bestBlock.uniqueId

      //Add block to subtree with smaller score to make it best subtree
      val longerTreeBlock = genBlock(19, randomBytes(32), randomBytes(32), Some(branchedBlock.uniqueId))
      blockTree.appendBlock(longerTreeBlock).isSuccess shouldBe true
      blockTree.lastBlock.uniqueId should contain theSameElementsAs longerTreeBlock.uniqueId
    }

    property(s"$prefix: Wrong block") {
      val prevS = blockTree.score()
      val prevB = blockTree.lastBlock
      val wrongBlock = genBlock(19, randomBytes(32), randomBytes(32), Some(randomBytes(51)))

      //Block with no parent in blockTree
      blockTree.appendBlock(wrongBlock).isSuccess shouldBe false
      blockTree.score() shouldBe prevS
      blockTree.lastBlock.uniqueId should contain theSameElementsAs prevB.uniqueId

      //Apply same block twice
      blockTree.appendBlock(prevB).isSuccess shouldBe false
      blockTree.score() shouldBe prevS
      blockTree.lastBlock.uniqueId should contain theSameElementsAs prevB.uniqueId
    }


  }
}