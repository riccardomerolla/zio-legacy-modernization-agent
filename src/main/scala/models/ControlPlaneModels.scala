package models

type WorkflowStatus = orchestration.control.WorkflowStatus
val WorkflowStatus = orchestration.control.WorkflowStatus

type ControlPlaneEvent = orchestration.control.ControlPlaneEvent
type WorkflowStarted   = orchestration.control.WorkflowStarted
val WorkflowStarted = orchestration.control.WorkflowStarted
type WorkflowCompleted = orchestration.control.WorkflowCompleted
val WorkflowCompleted = orchestration.control.WorkflowCompleted
type WorkflowFailed = orchestration.control.WorkflowFailed
val WorkflowFailed = orchestration.control.WorkflowFailed
type StepStarted = orchestration.control.StepStarted
val StepStarted = orchestration.control.StepStarted
type StepProgress = orchestration.control.StepProgress
val StepProgress = orchestration.control.StepProgress
type StepCompleted = orchestration.control.StepCompleted
val StepCompleted = orchestration.control.StepCompleted
type StepFailed = orchestration.control.StepFailed
val StepFailed = orchestration.control.StepFailed
type ResourceAllocated = orchestration.control.ResourceAllocated
val ResourceAllocated = orchestration.control.ResourceAllocated
type ResourceReleased = orchestration.control.ResourceReleased
val ResourceReleased = orchestration.control.ResourceReleased

type ControlCommand = orchestration.control.ControlCommand
type PauseWorkflow  = orchestration.control.PauseWorkflow
val PauseWorkflow = orchestration.control.PauseWorkflow
type ResumeWorkflow = orchestration.control.ResumeWorkflow
val ResumeWorkflow = orchestration.control.ResumeWorkflow
type CancelWorkflow = orchestration.control.CancelWorkflow
val CancelWorkflow = orchestration.control.CancelWorkflow

type ActiveRun = orchestration.control.ActiveRun
val ActiveRun = orchestration.control.ActiveRun
type WorkflowRunState = orchestration.control.WorkflowRunState
val WorkflowRunState = orchestration.control.WorkflowRunState
type ResourceAllocationState = orchestration.control.ResourceAllocationState
val ResourceAllocationState = orchestration.control.ResourceAllocationState
type RateLimitConfig = orchestration.control.RateLimitConfig
val RateLimitConfig = orchestration.control.RateLimitConfig
type AgentCapability = orchestration.control.AgentCapability
val AgentCapability = orchestration.control.AgentCapability

type AgentExecutionState = orchestration.control.AgentExecutionState
val AgentExecutionState = orchestration.control.AgentExecutionState
type AgentExecutionInfo = orchestration.control.AgentExecutionInfo
val AgentExecutionInfo = orchestration.control.AgentExecutionInfo
type AgentExecutionEvent = orchestration.control.AgentExecutionEvent
val AgentExecutionEvent = orchestration.control.AgentExecutionEvent
type AgentMonitorSnapshot = orchestration.control.AgentMonitorSnapshot
val AgentMonitorSnapshot = orchestration.control.AgentMonitorSnapshot
