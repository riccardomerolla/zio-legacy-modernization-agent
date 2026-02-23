package models

import zio.json.JsonCodec

type WorkspaceMetadata = taskrun.entity.WorkspaceMetadata
val WorkspaceMetadata = taskrun.entity.WorkspaceMetadata

type TaskStep = taskrun.entity.TaskStep
given JsonCodec[TaskStep] = taskrun.entity.given_JsonCodec_TaskStep

type TaskError = taskrun.entity.TaskError
val TaskError = taskrun.entity.TaskError

type TaskStatus = taskrun.entity.TaskStatus
val TaskStatus = taskrun.entity.TaskStatus

type ProgressUpdate = taskrun.entity.ProgressUpdate
val ProgressUpdate = taskrun.entity.ProgressUpdate

type TelegramMode = _root_.config.entity.TelegramMode
val TelegramMode = _root_.config.entity.TelegramMode

type TelegramPollingSettings = _root_.config.entity.TelegramPollingSettings
val TelegramPollingSettings = _root_.config.entity.TelegramPollingSettings

type TelegramBotConfig = _root_.config.entity.TelegramBotConfig
val TelegramBotConfig = _root_.config.entity.TelegramBotConfig

type GatewayConfig = _root_.config.entity.GatewayConfig
val GatewayConfig = _root_.config.entity.GatewayConfig

type MigrationConfig = _root_.config.entity.MigrationConfig
val MigrationConfig = _root_.config.entity.MigrationConfig

type TaskState = taskrun.entity.TaskState
val TaskState = taskrun.entity.TaskState

type Checkpoint = taskrun.entity.Checkpoint
val Checkpoint = taskrun.entity.Checkpoint

type CheckpointSnapshot = taskrun.entity.CheckpointSnapshot
val CheckpointSnapshot = taskrun.entity.CheckpointSnapshot

type TaskRunSummary = taskrun.entity.TaskRunSummary
val TaskRunSummary = taskrun.entity.TaskRunSummary
