package scorex.props

import java.io.{File, FileOutputStream}

import org.scalacheck.Gen
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.crypto.Sha256
import scorex.crypto.ads.merkle.MerkleTree

import scala.util.Random

class MerkleSpecification extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers {


  property("value returned from byIndex() is valid for random dataset") {
    //fix block numbers for faster tests
    for (blocks <- List(7, 8, 9, 128)) {
      val smallInteger = Gen.choose(0, blocks - 1)
      val (treeDirName: String, _, tempFile: String) = generateFile(blocks)
      val tree = MerkleTree.fromFile(tempFile, treeDirName)
      forAll(smallInteger) { (index: Int) =>
        val leafOption = tree.byIndex(index)
        leafOption should not be None
        val leaf = leafOption.get
        val resp = leaf.check(index, tree.rootHash)(Sha256)
        resp shouldBe true
      }
      tree.storage.close()
    }
  }

  property("hash root is the same") {
    //fix block numbers for faster tests
    for (blocks <- List(7, 8, 9, 128)) {
      val (treeDirName: String, _, tempFile: String) = generateFile(blocks, "2")

      val fileTree = MerkleTree.fromFile(tempFile, treeDirName)
      val rootHash = fileTree.rootHash

      fileTree.storage.close()

      val tree = new MerkleTree(treeDirName, fileTree.nonEmptyBlocks)
      val newRootHash = tree.rootHash
      tree.storage.close()
      rootHash shouldBe newRootHash
    }
  }


  def generateFile(blocks: Int, subdir: String = "1"): (String, File, String) = {
    val treeDirName = "/tmp/scorex/test/" + subdir + "/"
    val treeDir = new File(treeDirName)
    val tempFile = treeDirName + "/data.file"


    val data = new Array[Byte](1024 * blocks)
    Random.nextBytes(data)
    treeDir.mkdirs()
    for (file <- treeDir.listFiles) file.delete

    val fos = new FileOutputStream(tempFile)
    fos.write(data)
    fos.close()
    (treeDirName, treeDir, tempFile)
  }
}