package scorex.api.http

import javax.ws.rs.Path

import akka.actor.ActorRefFactory
import com.wordnik.swagger.annotations._
import play.api.libs.json.Json
import scorex.transaction.BlockChain
import scorex.transaction.state.wallet.Wallet
import spray.routing.Route


@Api(value = "/blocks", description = "Info about blockchain & individual blocks within it")
case class BlocksApiRoute(blockchain: BlockChain, wallet: Wallet)(implicit val context: ActorRefFactory)
  extends ApiRoute with CommonTransactionApiFunctions {

  override lazy val route =
    pathPrefix("blocks") {
      signature ~ first ~ last ~ at ~ height ~ heightEncoded ~ child ~ address ~ delay
    }

  @Path("/address/{address}")
  @ApiOperation(value = "Address", notes = "Get list of blocks generated by specified address", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", value = "Wallet address ", required = true, dataType = "String", paramType = "path")
  ))
  def address: Route = {
    path("address" / Segment) { case address =>
      jsonRoute {
        withPrivateKeyAccount(wallet, address) { account =>
          Json.arr(blockchain.generatedBy(account).map(_.json))
        }.toString()
      }
    }
  }

  @Path("/child/{signature}")
  @ApiOperation(value = "Child", notes = "Get children of specified block", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "String", paramType = "path")
  ))
  def child: Route = {
    path("child" / Segment) { case encodedSignature =>
      jsonRoute {
        withBlock(blockchain, encodedSignature) { block =>
          blockchain.children(block).head.json
        }.toString()
      }
    }
  }

  @Path("/delay/{height}/{blockNum}")
  @ApiOperation(value = "Average delay", notes = "Average delay in milliseconds between last $blockNum blocks starting from $height", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "height", value = "Height of block", required = true, dataType = "String", paramType = "path"),
    new ApiImplicitParam(name = "blockNum", value = "Number of blocks to count delay", required = true, dataType = "String", paramType = "path")
  ))
  def delay: Route = {
    path("delay" / IntNumber / IntNumber) { case (height, count) =>
      jsonRoute {
        blockchain.blockAt(height) match {
          case Some(block) =>
            blockchain.averageDelay(block, count).map(d => Json.obj("delay" -> d))
              .getOrElse(Json.obj("status" -> "error", "details" -> "Internal error")).toString
          case None =>
            Json.obj("status" -> "error", "details" -> "No block for this height").toString()
        }
      }
    }
  }

  @Path("/height/{signature}")
  @ApiOperation(value = "Height", notes = "Get height of a block by its Base58-encoded signature", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "String", paramType = "path")
  ))
  def heightEncoded: Route = {
    path("height" / Segment) { case encodedSignature =>
      jsonRoute {
        withBlock(blockchain, encodedSignature) { block =>
          Json.obj("height" -> blockchain.heightOf(block))
        }.toString()
      }
    }
  }

  @Path("/height")
  @ApiOperation(value = "Height", notes = "Get blockchain height", httpMethod = "GET")
  def height: Route = {
    path("height") {
      jsonRoute {
        Json.obj("height" -> blockchain.height()).toString()
      }
    }
  }

  @Path("/at/{height}")
  @ApiOperation(value = "At", notes = "Get block at specified height", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "height", value = "Block height", required = true, dataType = "Long", paramType = "path")
  ))
  def at: Route = {
    path("at" / IntNumber) { case height =>
      jsonRoute {
        val res = blockchain
          .blockAt(height)
          .map(_.json.toString())
          .getOrElse(Json.obj("status" -> "error", "details" -> "No block for this height").toString())
        res
      }
    }
  }

  @Path("/last")
  @ApiOperation(value = "Last", notes = "Get last block data", httpMethod = "GET")
  def last: Route = {
    path("last") {
      jsonRoute {
        blockchain.lastBlock.json.toString()
      }
    }
  }

  @Path("/first")
  @ApiOperation(value = "First", notes = "Get genesis block data", httpMethod = "GET")
  def first: Route = {
    path("first") {
      jsonRoute {
        blockchain.blockAt(1).get.json.toString()
      }
    }
  }

  @Path("/signature/{signature}")
  @ApiOperation(value = "Signature", notes = "Get block by a specified Base58-encoded signature", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "String", paramType = "path")
  ))
  def signature: Route = {
    path("signature" / Segment) { case encodedSignature =>
      jsonRoute {
        withBlock(blockchain, encodedSignature)(_.json).toString()
      }
    }
  }
}
