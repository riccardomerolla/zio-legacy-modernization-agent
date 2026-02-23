package shared.entity

import zio.json.JsonCodec

type TaskStep = String
given JsonCodec[TaskStep] = JsonCodec.string.asInstanceOf[JsonCodec[TaskStep]]

type GatewayConfig = _root_.config.entity.GatewayConfig
val GatewayConfig = _root_.config.entity.GatewayConfig
