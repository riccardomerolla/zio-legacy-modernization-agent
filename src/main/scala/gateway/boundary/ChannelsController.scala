package gateway.boundary

import zio.*
import zio.http.*

trait ChannelsController:
  def routes: Routes[Any, Response]

object ChannelsController:
  def routes: ZIO[ChannelsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ChannelsController](_.routes)

  val live: ZLayer[ChannelController, Nothing, ChannelsController] =
    ZLayer.fromFunction((controller: ChannelController) =>
      new ChannelsController:
        override val routes: Routes[Any, Response] = controller.routes
    )
